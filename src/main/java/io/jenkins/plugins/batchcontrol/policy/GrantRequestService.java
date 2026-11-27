package io.jenkins.plugins.batchcontrol.policy;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.CreateNamePattern;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.ItemKind;
import io.jenkins.plugins.batchcontrol.model.PendingCount;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.NotificationDispatcher;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.Store;
import io.jenkins.plugins.batchcontrol.store.XmlChars;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

/**
 * The single entry point for every {@link GrantRequest} state transition (SPEC items 3 and 8).
 * No other class may change a grant request's status.
 *
 * <p><b>Concurrency (D-20 discipline, same as {@link RunRequestService})</b>: every
 * load→validate→transition→persist sequence runs under one {@link ReentrantLock}, which makes
 * each transition an effective compare-and-set together with the store's atomic rewrite.
 * Approval creates and registers the {@link Grant} inside the same critical section, so a
 * request can never yield two grants.
 *
 * <p><b>Failure families</b>: {@link IllegalArgumentException} for input validation,
 * {@link IllegalStateException} for wrong-state transitions, {@link AccessDeniedException}
 * (403 on the web layer) for authorization refusals.
 *
 * <p><b>S-15</b>: {@link #create} and {@link #approve} refuse outright while change control is off,
 * because the switch is a kill switch and a window approved while it is off would otherwise take
 * effect unreviewed the moment it was turned back on. See {@code checkChangeControlEnabled}.
 */
@Restricted(NoExternalUse.class)
public final class GrantRequestService {

    private static final Logger LOGGER = Logger.getLogger(GrantRequestService.class.getName());

    /** Same reason size cap as run requests (D-22). */
    private static final int MAX_REASON_LENGTH = 4000;

    private static final GrantRequestService INSTANCE = new GrantRequestService();

    private final ReentrantLock lock = new ReentrantLock();
    private final Store store = Store.get();

    private GrantRequestService() {
    }

    public static GrantRequestService get() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- read API

    /** Loads a grant request by id, or {@code null}. */
    public GrantRequest load(String id) {
        return store.loadGrantRequest(id);
    }

    /** All stored grant requests, in creation order. */
    public List<GrantRequest> list() {
        return store.listGrantRequests();
    }

    /**
     * D-61: the PENDING grant requests that concern {@code auth}, read from the open-request index only
     * (no history scan): those awaiting their decision as a designated approver holding
     * Jenkins-level {@code BatchControl/Approve}, else their own. Every counted request is visible
     * to {@code auth} under P-09. The single source for the tab badge and the section.
     */
    public PendingCount countPendingFor(Authentication auth) {
        return PendingCounter.count(store.listOpenGrantRequests(), auth, GrantRequest::getStatus, GrantRequest::isDesignatedApprover,
                GrantRequest::getRequester);
    }

    // ---------------------------------------------------------------- creation (SPEC 3, 8)

    /**
     * Creates a PENDING grant request. The requester is the current authentication; validation
     * failures throw {@link IllegalArgumentException}.
     *
     * <p>Rules: at least one action; duration in {@code (0, maxGrantMinutes]}; non-empty reason
     * of at most {@value #MAX_REASON_LENGTH} characters; the approver must be on the global
     * list and must not be the requester (admin exception per the self-approval policy); the
     * scope item must exist and be visible to the requester, and each action must apply to its
     * kind (D-71: CREATE only on a regular folder, DELETE only on a job).
     */
    public GrantRequest create(GrantScope scope, List<GrantAction> actions, int durationMinutes,
                               String reason, String approver) {
        return create(scope, actions, durationMinutes, reason, Approvers.of(approver), null);
    }

    /** Approver-set form without a name restriction (D-37). */
    public GrantRequest create(GrantScope scope, List<GrantAction> actions, int durationMinutes,
                               String reason, List<String> approvers) {
        return create(scope, actions, durationMinutes, reason, approvers, null);
    }

    /**
     * Creates a PENDING grant request designating an approver set (D-37) and, for CREATE, an
     * optional name restriction (D-40): an exact item name or a {@code /regex/} matched against
     * the whole name of the new item. A blank restriction means none; an invalid one (bad regex,
     * invalid item name, over {@value CreateNamePattern#MAX_LENGTH} characters) is refused with
     * {@link IllegalArgumentException}, as is a restriction on a request without CREATE.
     */
    public GrantRequest create(GrantScope scope, List<GrantAction> actions, int durationMinutes,
                               String reason, List<String> approvers, String createNamePattern) {
        Objects.requireNonNull(scope, "scope");
        // S-15, refused before any other validation: the switch being off is a precondition of the
        // whole feature rather than a property of this request, so it must not depend on the request
        // being well-formed, and the caller should read "change control is off" rather than a
        // complaint about a field of a request that could never have taken effect anyway.
        checkChangeControlEnabled("requested", scope.getFullName());
        // The RequestGrant permission is enforced by the HTTP layer (GrantsSection.doCreate,
        // T-08-13); the service stays callable by internal flows acting for a named requester.
        String requester = Jenkins.getAuthentication2().getName();

        if (actions == null || actions.isEmpty()) {
            throw new IllegalArgumentException("At least one action (CREATE, CONFIGURE, DELETE) "
                    + "must be requested.");
        }
        int max = BatchControlGlobalConfiguration.get().getMaxGrantMinutes();
        if (durationMinutes <= 0) {
            throw new IllegalArgumentException("The grant duration must be a positive number "
                    + "of minutes.");
        }
        if (durationMinutes > max) {
            throw new IllegalArgumentException("The grant duration must not exceed "
                    + max + " minutes (maxGrantMinutes).");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("A reason is required to create a grant request.");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("The reason must not exceed "
                    + MAX_REASON_LENGTH + " characters.");
        }
        // R1-01: the reason is written into the request file, which XML 1.0 must be able to read back.
        XmlChars.requireStorable(reason, "reason");
        // Approver rules mirror run requests; there is no per-job approver restriction here.
        List<String> designated = ApprovalPolicy.checkDesignation(requester, approvers, null);
        String pattern = CreateNamePattern.normalize(createNamePattern);
        // R1-01: the name restriction is stored in the request file as well.
        XmlChars.requireStorable(pattern, "name restriction");
        if (pattern != null) {
            if (!actions.contains(GrantAction.CREATE)) {
                throw new IllegalArgumentException("A name restriction applies only to a request "
                        + "that includes CREATE.");
            }
            CreateNamePattern.parse(pattern); // D-40: validated at submission
        }
        Item item = checkScopeAtCreation(scope, actions);
        ItemKind kind = ItemKind.of(item);
        // D-71a (security-34 S-34-04): the stored scope is the canonical full name of the item the
        // typed name resolved to (Jenkins resolves "ops/" or "OPS" to the item "ops"), so the window
        // the approver sees is the one that confers, and it is matched by that exact name.
        GrantScope canonical = GrantScope.item(item.getFullName());

        GrantRequest request = GrantRequest.create(canonical, kind, actions, durationMinutes, reason,
                requester, designated, pattern);
        lock.lock();
        try {
            store.saveGrantRequest(request);
        } finally {
            lock.unlock();
        }
        NotificationDispatcher.grant(NotificationEvent.REQUEST_CREATED, request);
        return request;
    }

    /**
     * The scope item of a new request (D-71): it must exist and be visible to the requester, be a
     * top-level item, and each requested action must apply to its kind. Returns the item, whose
     * kind and canonical full name the request records (D-71a, S-34-04).
     *
     * <p>S-03: an empty full name is rejected. A root scope would be instance-wide, which SPEC
     * item 8 never defines; root-scope grants stay impossible until a deliberate DECISIONS entry
     * introduces them.
     *
     * <p>The lookup is the requester's own: an item they cannot see is refused exactly like a
     * missing one ("No such item"), so the refusal discloses nothing.
     */
    private static Item checkScopeAtCreation(GrantScope scope, List<GrantAction> actions) {
        checkScopeName(scope);
        String fullName = scope.getFullName();
        Item item = findScopeItem(fullName);
        if (item == null || item.getFullName() == null || item.getFullName().isEmpty()) {
            throw new IllegalArgumentException("No such item: '" + fullName + "'.");
        }
        ItemKind kind = ItemKind.of(item);
        if (kind == null) {
            throw new IllegalArgumentException("'" + fullName + "' is part of another job and cannot be "
                    + "named by a permission window; name the job it belongs to.");
        }
        checkActionsApply(item, kind, actions);
        return item;
    }

    /** S-03 / S-13: the scope must name an item; there is no root-scope grant. */
    private static void checkScopeName(GrantScope scope) {
        String fullName = scope.getFullName();
        if (fullName == null || fullName.isEmpty()) {
            throw new IllegalArgumentException("root-scope grants are not supported");
        }
    }

    /**
     * D-71: CREATE applies only to a regular folder, DELETE only to a job.
     *
     * @throws IllegalArgumentException naming the action and the item's kind
     */
    private static void checkActionsApply(Item item, ItemKind kind, List<GrantAction> actions) {
        String fullName = item.getFullName();
        if (actions.contains(GrantAction.CREATE) && !GrantScope.createAppliesTo(item)) {
            throw new IllegalArgumentException("The Create action applies only to a folder, not to "
                    + kind.getDisplayName() + " '" + fullName + "'. To create an item, request Create on the "
                    + "folder that should contain it.");
        }
        if (actions.contains(GrantAction.DELETE) && !GrantScope.deleteAppliesTo(item)) {
            throw new IllegalArgumentException("The Delete action applies only to a job, not to "
                    + kind.getDisplayName() + " '" + fullName + "': deleting it would also delete every item "
                    + "inside it. Ask an administrator to delete it.");
        }
    }

    /**
     * D-71: the item a window would name, as the current user sees it, or {@code null} when no
     * item exists at {@code fullName} or the user may not read it (an item visible only through
     * Item/Discover counts as not visible). The single lookup used for requests and approvals.
     */
    @CheckForNull
    public static Item findScopeItem(@CheckForNull String fullName) {
        if (fullName == null || fullName.isEmpty()) {
            return null;
        }
        try {
            return Jenkins.get().getItemByFullName(fullName);
        } catch (AccessDeniedException e) {
            // Item/Discover without Item/Read somewhere on the path: the same answer as a missing
            // item, so the refusal does not tell the two apart.
            return null;
        }
    }

    /**
     * S-13, D-71: re-validates a request's scope right before it becomes a live grant. Creation-time
     * validation does not bind a request that was persisted earlier, or whose item has since been
     * deleted, renamed or replaced.
     *
     * <ul>
     *   <li>The item exists but the approver cannot see it (T-SEC-18, P-10: the decision may not be
     *       made blind): {@link IllegalArgumentException}.</li>
     *   <li>No item exists at that name any more, or its kind (descriptor id) differs from the one
     *       recorded at creation (D-71): {@link IllegalStateException}.</li>
     * </ul>
     *
     * <p>The first two cases share one message, so a page that shows the message does not tell an
     * approver who cannot see the item whether it still exists. The kind is only compared, and
     * named, for an item the approver can see.
     *
     * <p>D-69, D-71: a request whose stored scope type is not {@code ITEM} (an earlier type, which
     * loads without a type and is not converted) is refused with {@link IllegalStateException}.
     *
     * <p>D-71a: the scope must be the item's canonical full name (requests are stored with it,
     * S-34-04); a request that does not name it so is refused with {@link IllegalStateException}
     * (only reached for an item the approver can see).
     *
     * @return the item the window will be on
     * @throws IllegalArgumentException for an empty (root) scope, as at creation, or an item the
     *                                  approver cannot see
     * @throws IllegalStateException when the item is gone or its kind changed
     */
    private static Item checkScopeAtApproval(GrantRequest request) {
        GrantScope scope = request.getScope();
        String id = request.getId();
        if (scope == null || scope.getType() != GrantScope.Type.ITEM) {
            // D-69, D-71 (T-08-145): a request stored with an earlier scope type (JOB, FOLDER,
            // FOLDER_ONLY) loads without a type and is not converted; approving it would create a
            // window that confers nothing (or, converted silently, one the approver never saw).
            throw new IllegalStateException("Grant request " + id + " cannot be approved: it was stored with an"
                    + " earlier scope type, which is not converted. Ask the requester to request a window on the"
                    + " item again.");
        }
        checkScopeName(scope);
        String fullName = scope.getFullName();
        Item item = findScopeItem(fullName);
        if (item == null) {
            String unavailable = "Grant request " + id + " cannot be approved: no item named '" + fullName
                    + "' exists any more, or you cannot see it.";
            if (itemExists(fullName)) {
                throw new IllegalArgumentException(unavailable);
            }
            throw new IllegalStateException(unavailable);
        }
        ItemKind recorded = request.getItemKind();
        ItemKind current = ItemKind.of(item);
        if (recorded == null) {
            throw new IllegalStateException("Grant request " + id + " cannot be approved: the kind of '"
                    + fullName + "' was not recorded when the window was requested. Ask the requester to "
                    + "request it again.");
        }
        if (current == null || !recorded.getDescriptorId().equals(current.getDescriptorId())) {
            throw new IllegalStateException("Grant request " + id + " cannot be approved: the item '" + fullName
                    + "' is now of kind " + (current == null ? "unknown" : current.getDisplayName()) + ", not "
                    + recorded.getDisplayName() + " as when the window was requested.");
        }
        try {
            checkActionsApply(item, current, request.getActions());
        } catch (IllegalArgumentException e) {
            // Only reachable for a request stored without the creation-time check.
            throw new IllegalStateException("Grant request " + id + " cannot be approved. " + e.getMessage(), e);
        }
        if (!fullName.equals(item.getFullName())) {
            // Only reachable for a request stored before D-71a (S-34-04): the window would be matched
            // by a name no item has.
            throw new IllegalStateException("Grant request " + id + " cannot be approved: it names '" + fullName
                    + "', but the item's name is '" + item.getFullName() + "'. Ask the requester to request it again.");
        }
        return item;
    }

    /**
     * D-71: whether any item exists at {@code fullName}, whoever may see it. Used only by
     * {@link #checkScopeAtApproval} once the approver's own lookup came back empty, to tell an item
     * that is gone (the world changed: {@link IllegalStateException}) from one the approver cannot
     * see (the approver may not decide: {@link IllegalArgumentException}).
     *
     * <p>ACL.SYSTEM2 switch, with its reason: a caller-scoped lookup cannot tell a missing item from
     * an invisible one, and that distinction is the whole question. The approver's permission checks
     * are complete before this is reached ({@link ApprovalPolicy#checkDecision} in {@link #approve}:
     * the caller is a designated approver holding BatchControl/Approve), the switch covers the one
     * lookup only, and the item found is never returned or acted on: only its existence is used.
     */
    private static boolean itemExists(String fullName) {
        // ACL.SYSTEM2 switch: the approver's permission checks are complete (see javadoc).
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            return Jenkins.get().getItemByFullName(fullName) != null;
        }
    }

    // ---------------------------------------------------------------- decisions (SPEC 3, 8)

    /**
     * Approves a PENDING grant request and creates the grant. The caller must be the designated
     * approver holding the Approve permission (or the admin self-approval path). The grant
     * window is {@code [now, now + durationMinutes)} on the {@link BatchClock}; SPEC item 8:
     * the requester holds the permissions immediately.
     *
     * <p>The stored scope is re-validated here (S-13, D-71): approval fails with
     * {@link IllegalStateException} if no item exists at the scope name any more or its kind
     * (descriptor id) differs from the one recorded at creation, and with
     * {@link IllegalArgumentException} for a root scope or an item the approver cannot see
     * (T-SEC-18): the approver must be able to see the scope item to approve a window on it.
     *
     * <p>D-71c (3): if the item is deleted after it was checked and before the window is registered,
     * the window ends at once; the request stays APPROVED and its APPROVED notice says that the
     * window ended at once and why (owner decision 2026-10-06).
     *
     * <p>SPEC item 2, D-05 (#182): an administrator's self-approval is recorded as
     * {@code selfApproved}, set with the other APPROVED decision fields and stored in the same write.
     * A refused approval never stores it: every refusal before that write throws before it is set
     * (a passed pending timeout stores EXPIRED without it), and a failed write leaves the previous
     * file. The request object is this call's own (each call loads it from the store), so no later
     * save can write the flag of a refused attempt. A window that ends at once (D-71c (3)) is still
     * an approval: the request is APPROVED and keeps the flag with its other decision fields.
     *
     * <p>#184, all or nothing: the request is written APPROVED first and the window's file second
     * ({@link GrantService#registerApproved} gives the reason for that order). When anything after
     * the request's write fails before the window's file is written (a grant file that cannot be
     * written, in practice), the request is put back exactly as it was before this call (status,
     * decision time, decider, comment and {@code selfApproved}) and written again, under this
     * service's lock and {@code GrantService}'s monitor, before the failure is thrown: the approver
     * gets that failure, no APPROVED notice is sent, and the request can still be approved, rejected,
     * cancelled or expired. If that write fails too, it is logged and added to the failure as
     * suppressed: the request stays APPROVED with no window, which confers nothing (fail-closed), and
     * the requester files a new request. Jenkins stopping between the two writes leaves it the same way.
     *
     * @return the created, immediately effective {@link Grant}, or the window that ended at once
     */
    public Grant approve(String id, String comment) {
        Grant created;
        GrantRequest approved;
        boolean endedAtOnce;
        lock.lock();
        try {
            GrantRequest request = require(id);
            // S-15: after require(id), so the refusal record names the scope the window was for and
            // the history stays queryable by job; before every other check, because an approval that
            // cannot confer anything should not turn on whether the request is still PENDING or the
            // caller happens to be its designated approver. Existence was already disclosed to any
            // caller by require(id) before this change, so nothing new leaks.
            checkChangeControlEnabled("approved", request.getScope() == null ? null : request.getScope().getFullName());
            // R1-01: refused before anything changes, as for run requests (D-72b (2)); after the S-15
            // refusal, which does not depend on the request or the decision being well-formed.
            XmlChars.requireStorable(comment, "comment");
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Grant request " + id + " is "
                        + request.getStatus() + " and can no longer be approved.");
            }
            // SPEC item 2, D-05 (#182): an administrator's self-approval is recorded; set below with the
            // other decision fields, so no refusal before that point (scope, pending timeout, switch) ever
            // carries it.
            boolean selfApproval = ApprovalPolicy.checkDecision(request.getId(), request.getRequester(),
                    request.getApprovers());
            // S-13: re-validate the stored scope before it becomes a live grant. Creation-time
            // validation does not bind a request that was persisted earlier (or whose target has
            // since been deleted or renamed), and approval is the last point where a bad scope
            // can still be stopped. Deliberately after checkDecision, so a non-approver learns
            // nothing about the scope's validity.
            Item item = checkScopeAtApproval(request);
            Instant now = BatchClock.now();
            if (pendingExpired(request, now)) {
                String reason = EndReasons.pendingExpired();
                request.setStatus(RequestStatus.EXPIRED);
                request.setDecisionComment(reason);
                store.saveGrantRequest(request);
                NotificationDispatcher.grantEnded(NotificationEvent.EXPIRED, request, true, reason);
                throw new IllegalStateException("Grant request " + id
                        + " passed its pending timeout and is now EXPIRED.");
            }
            String decidedBy = Jenkins.getAuthentication2().getName();
            Grant[] made = new Grant[1];
            // Registration persists the grant and makes it effective in the same critical
            // section, so approval and effectiveness are atomic. D-74: on the item checked above,
            // under its name at registration (it follows a rename that happened meanwhile).
            // D-71c (3), S-39-02: registration re-verifies that this item is still at its name;
            // if it was deleted meanwhile, the window ends at once ("its item was deleted").
            //
            // #39: the decision runs inside GrantService's monitor, the one revokeAllActive holds when
            // change control is turned off, and checks the switch again there. The check above may
            // have passed just before a switch-off; this one either sees the switch off and refuses
            // with the same message, leaving the request PENDING and nothing registered, or runs
            // before the switch-off's revocation, which then ends the window. Lock order: this
            // service's lock, then GrantService's monitor, then the store; nothing below takes the
            // configuration's monitor (the switch is a volatile read), so the switch-off path
            // (configuration monitor, then GrantService's monitor) cannot deadlock with this.
            //
            // #184: a failure after the request's write and before the window's file is written puts
            // the request back as it was, under both locks; the undo takes only the store.
            Decision before = Decision.of(request);
            boolean[] stored = new boolean[1];
            endedAtOnce = !GrantService.get().registerApproved(item, () -> {
                checkChangeControlEnabled("approved", request.getScope() == null ? null : request.getScope().getFullName());
                request.setStatus(RequestStatus.APPROVED);
                request.setDecidedAt(now);
                request.setDecidedBy(decidedBy);
                request.setDecisionComment(comment);
                request.setSelfApproved(selfApproval);
                store.saveGrantRequest(request);
                stored[0] = true;
                made[0] = Grant.createFor(request, now);
                return made[0];
            }, failure -> {
                if (stored[0]) {
                    putBack(request, before, failure);
                }
            });
            Grant grant = made[0];
            if (!endedAtOnce) {
                LOGGER.info(() -> "Grant " + grant.getId() + " created for user '" + grant.getUser()
                        + "' on " + grant.getScope() + " until " + grant.getExpiresAt());
            }
            created = grant;
            approved = request;
        } finally {
            lock.unlock();
        }
        // Owner decision 2026-10-06: a window that ended at registration is still announced as
        // APPROVED (no new notice type), with one details line saying it ended at once and why.
        NotificationDispatcher.grantApproved(approved, endedAtOnce ? Grant.REVOKED_ITEM_DELETED : null);
        return created;
    }

    /** #184: the fields an approval sets, as a request had them before it. */
    private record Decision(RequestStatus status, Instant decidedAt, String decidedBy, String comment,
                            boolean selfApproved) {

        static Decision of(GrantRequest request) {
            return new Decision(request.getStatus(), request.getDecidedAt(), request.getDecidedBy(),
                    request.getDecisionComment(), request.isSelfApproved());
        }

        void applyTo(GrantRequest request) {
            request.setStatus(status);
            request.setDecidedAt(decidedAt);
            request.setDecidedBy(decidedBy);
            request.setDecisionComment(comment);
            request.setSelfApproved(selfApproved);
        }
    }

    /**
     * #184: puts back {@code request}, stored APPROVED by an approval whose window's file could not be
     * written, as it was {@code before} that approval, and writes it again; see {@link #approve}. A
     * write that fails too is logged and added to {@code failure} as suppressed.
     */
    private void putBack(GrantRequest request, Decision before, RuntimeException failure) {
        before.applyTo(request);
        try {
            store.saveGrantRequest(request);
        } catch (RuntimeException again) {
            failure.addSuppressed(again);
            LOGGER.log(java.util.logging.Level.SEVERE, again, () -> "Grant request " + request.getId() + " was stored"
                    + " APPROVED, but its window could not be written, and the request could not be put back to "
                    + before.status() + " either; it stays APPROVED with no window, which confers nothing, and the"
                    + " requester has to file a new request");
        }
    }

    /** Rejects a PENDING grant request; the comment is mandatory (SPEC item 5 rule reused). */
    public GrantRequest reject(String id, String comment) {
        if (comment == null || comment.trim().isEmpty()) {
            throw new IllegalArgumentException("A comment is required to reject a grant request.");
        }
        XmlChars.requireStorable(comment, "comment"); // R1-01
        GrantRequest request;
        lock.lock();
        try {
            request = require(id);
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Grant request " + id + " is "
                        + request.getStatus() + " and can no longer be rejected.");
            }
            ApprovalPolicy.checkDecision(request.getId(), request.getRequester(), request.getApprovers());
            request.setStatus(RequestStatus.REJECTED);
            request.setDecidedAt(BatchClock.now());
            request.setDecidedBy(Jenkins.getAuthentication2().getName());
            request.setDecisionComment(comment);
            store.saveGrantRequest(request);
        } finally {
            lock.unlock();
        }
        NotificationDispatcher.grant(NotificationEvent.REJECTED, request);
        return request;
    }

    /**
     * Replaces the designated approver set of a PENDING grant request; requester only (SPEC
     * item 3 rules reused, D-26, D-37). Recorded as (previous set, new set, changed by, time).
     */
    public GrantRequest changeApprovers(String id, List<String> newApprovers) {
        String caller = Jenkins.getAuthentication2().getName();
        GrantRequest request;
        lock.lock();
        try {
            request = require(id);
            if (!Approvers.sameUser(caller, request.getRequester())) {
                throw new AccessDeniedException(
                        "Only the requester may change the approvers of grant request " + id + ".");
            }
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new RequestNotPendingException("permission window request", request.getStatus());
            }
            List<String> designated = ApprovalPolicy.checkDesignation(request.getRequester(), newApprovers, null);
            request.addApproverChange(new GrantRequest.ApproverChange(
                    request.getApprovers(), designated, caller, BatchClock.now()));
            request.setApprovers(designated);
            store.saveGrantRequest(request);
        } finally {
            lock.unlock();
        }
        NotificationDispatcher.grant(NotificationEvent.APPROVERS_CHANGED, request);
        return request;
    }

    /** Cancels a PENDING grant request; requester or a Manage holder only (SPEC item 7 rule). */
    public GrantRequest cancel(String id) {
        String caller = Jenkins.getAuthentication2().getName();
        lock.lock();
        try {
            GrantRequest request = require(id);
            if (!Approvers.sameUser(caller, request.getRequester())
                    && !Jenkins.get().hasPermission(BatchControlPermissions.MANAGE)) {
                throw new AccessDeniedException(
                        "Only the requester or a Manage holder may cancel grant request " + id + ".");
            }
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Grant request " + id + " is "
                        + request.getStatus() + "; only PENDING requests can be cancelled.");
            }
            request.setStatus(RequestStatus.CANCELLED);
            // e2e-03 DEF-13: the history names who cancelled and when (the requester or a
            // Manage holder), in the same fields a decision uses.
            request.setDecidedAt(BatchClock.now());
            request.setDecidedBy(caller);
            store.saveGrantRequest(request);
            NotificationDispatcher.grantEnded(NotificationEvent.CANCELLED, request, true, "Cancelled by " + caller);
            return request;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- expiry (SPEC 7, 8)

    /**
     * Expires PENDING grant requests past {@code pendingTimeoutHours}. Called by the periodic
     * work; expiry truth stays the clock comparison (a late approval attempt expires the
     * request on its own, see {@link #approve}).
     */
    public void expireOverduePending() {
        Instant now = BatchClock.now();
        for (GrantRequest snapshot : store.listOpenGrantRequests()) {
            if (snapshot.getStatus() != RequestStatus.PENDING) {
                continue;
            }
            lock.lock();
            try {
                GrantRequest request = store.loadGrantRequest(snapshot.getId());
                if (request != null && request.getStatus() == RequestStatus.PENDING
                        && pendingExpired(request, now)) {
                    String reason = EndReasons.pendingExpired();
                    request.setStatus(RequestStatus.EXPIRED);
                    request.setDecisionComment(reason);
                    store.saveGrantRequest(request);
                    NotificationDispatcher.grantEnded(NotificationEvent.EXPIRED, request, true, reason);
                    LOGGER.info(() -> "Grant request " + request.getId()
                            + " expired (pending timeout)");
                }
            } catch (RuntimeException e) {
                // One request that cannot be read or written never ends the expiry of the others; it
                // is tried again on the next run (an approval compares the clock meanwhile).
                LOGGER.log(java.util.logging.Level.WARNING, "Could not expire change request " + snapshot.getId()
                        + "; it is tried again on the next run", e);
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * D-36: sends {@link NotificationEvent#EXPIRING} once for every PENDING grant request whose
     * pending timeout falls within {@code notifyBeforeExpiryMinutes}, and
     * {@link NotificationEvent#GRANT_EXPIRING} once for every active window ending within it. The
     * "notified" flags are persisted before dispatch, so a restart never resends.
     *
     * <p>One request or window that cannot be read or written never keeps the others from their
     * notice: the pending requests and the windows are handled apart, each request and each window
     * on its own, and a failure is logged.
     */
    public void notifyExpiring() {
        Instant now = BatchClock.now();
        Duration lead = Duration.ofMinutes(
                BatchControlGlobalConfiguration.get().getNotifyBeforeExpiryMinutes());
        try {
            notifyPendingExpiring(now, lead);
        } catch (RuntimeException e) {
            // Listing the pending requests failed; the windows below are told all the same.
            LOGGER.log(java.util.logging.Level.WARNING,
                    "Could not send the EXPIRING notifications of pending change requests", e);
        }
        notifyWindowsExpiring(lead);
    }

    /** EXPIRING for the PENDING requests close to their timeout; a request that fails is tried next run. */
    private void notifyPendingExpiring(Instant now, Duration lead) {
        for (GrantRequest snapshot : store.listOpenGrantRequests()) {
            if (snapshot.getStatus() != RequestStatus.PENDING || snapshot.isExpiringNotified()) {
                continue;
            }
            GrantRequest notified = null;
            lock.lock();
            try {
                GrantRequest request = store.loadGrantRequest(snapshot.getId());
                if (request != null && request.getStatus() == RequestStatus.PENDING
                        && !request.isExpiringNotified()) {
                    Instant expiresAt = pendingExpiry(request);
                    if (now.isBefore(expiresAt) && !now.isBefore(expiresAt.minus(lead))) {
                        request.setExpiringNotified(true);
                        store.saveGrantRequest(request);
                        notified = request;
                    }
                }
            } catch (RuntimeException e) {
                // Not marked (the flag is only ever stored before the dispatch), so the next run tries again.
                LOGGER.log(java.util.logging.Level.WARNING, "Could not send the EXPIRING notification of change request "
                        + snapshot.getId() + "; it is tried again on the next run", e);
            } finally {
                lock.unlock();
            }
            if (notified != null) {
                NotificationDispatcher.grant(NotificationEvent.EXPIRING, notified);
            }
        }
    }

    /** GRANT_EXPIRING for the active windows close to their end, each window on its own. */
    private void notifyWindowsExpiring(Duration lead) {
        for (Grant grant : GrantService.get().claimExpiringNotifications(lead)) {
            GrantRequest request;
            try {
                request = store.loadGrantRequest(grant.getGrantRequestId());
            } catch (RuntimeException e) {
                // LIMITATIONS 11: the change request's file is there but cannot be read, so no expiry
                // notice is sent for this window (its approved name is unknown); its claim stands, so
                // it is not retried. The windows after it are still told.
                LOGGER.log(java.util.logging.Level.WARNING, "The change request of grant " + grant.getId()
                        + " cannot be read; the GRANT_EXPIRING notification of its window is not sent", e);
                continue;
            }
            // D-75 (1): the request gives the reason and the approved name, which the notice names
            // instead of a followed name its holder cannot read.
            NotificationDispatcher.grantExpiring(grant, request);
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * S-15: refuses the transition while change control is off, and records the attempt.
     *
     * <p>The change-control switch is a kill switch. With it off a window confers nothing
     * ({@code security.GrantAwareACL}) and the windows that were open when it was flipped have been
     * revoked ({@code security.GrantService#revokeAllActive}). Letting a window still be requested
     * and approved would put the removed state straight back, only displaced in time: a window
     * approved while the switch is off would spring to life the moment it is turned back on, having
     * been reviewed by nobody at that point. Refusing at the source leaves nothing to resurrect,
     * which is why this is preferred over sweeping again when the switch goes on.
     *
     * <p>Only {@code create} and {@code approve} are gated. {@code reject}, {@code cancel} and the
     * pending-expiry sweep all <em>close</em> requests, and refusing those would strand every
     * pending request for as long as the switch is off, with nothing gained — a closed request
     * confers nothing either way.
     *
     * <p>{@link IllegalStateException} rather than {@link AccessDeniedException} on purpose: per
     * this class's failure families, the caller's authorization is not in question (the HTTP layer
     * already checked {@code RequestGrant}/{@code Approve}) — the instance is in a state where the
     * transition does not exist. Both web entry points already turn this family into a
     * {@code hudson.model.Failure} carrying the message, so the user reads the reason rather than a
     * bare 500 and no UI change is needed.
     *
     * @param attemptedTransition past participle used in the message and the record
     *                            ("requested", "approved")
     * @param scopeFullName the scope the window was for; becomes the record's target
     * @throws IllegalStateException if change control is off
     */
    /**
     * e2e-03 DEF-27: the refusal of a grant request that the HTTP layer turns away before it
     * reaches {@link #create} (the Grants screen closes with the change-control switch). Records
     * the refused request exactly as {@link #create} would and throws the same
     * {@link IllegalStateException}; returns normally while change control is on.
     *
     * <p>#44: only a caller who may request a window at all gets the refusal and its record.
     * The screen's gate lets in Approve and Manage holders too, so RequestGrant (Manage implies
     * it, P-02) is checked here first: anyone else is denied before anything is written, exactly
     * as {@code doCreate} would deny them while the switch is on.
     *
     * @param scopeFullName the scope the request named, or {@code null} when none was given
     * @throws AccessDeniedException if the caller lacks RequestGrant (nothing is recorded)
     * @throws IllegalStateException if change control is off
     */
    public void refuseRequestWhileChangeControlOff(String scopeFullName) {
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST_GRANT);
        checkChangeControlEnabled("requested", scopeFullName);
    }

    private static void checkChangeControlEnabled(String attemptedTransition, String scopeFullName) {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        if (cfg.isChangeControlEnabled()) {
            return;
        }
        String user = Jenkins.getAuthentication2().getName();
        String target = scopeFullName == null || scopeFullName.isEmpty()
                ? "(no scope)" : scopeFullName;
        // D-13 / SPEC item 9 last criterion: with BOTH switches off the plugin writes nothing at
        // all, so the record follows the same activity gate every other record does. The refusal
        // itself still applies — it is the recording that is conditional, not the behaviour.
        if (cfg.isRunControlEnabled()) {
            ChangeRecord record = ChangeRecord.create(ChangeType.GRANT_REQUEST_BLOCKED, target, user,
                    "A permission window for '" + target + "' could not be " + attemptedTransition
                            + ": change control is off");
            try {
                Store.get().appendChangeRecord(record);
            } catch (RuntimeException e) {
                // R3-01 rule: the refusal below stands whatever happens to its record.
                LOGGER.log(java.util.logging.Level.SEVERE, "Could not record the refused permission window for '"
                        + target + "' (" + attemptedTransition + ") of '" + user + "'; it is refused all the same", e);
            }
        }
        LOGGER.info(() -> "Refused to let '" + user + "' have a permission window for '" + target
                + "' " + attemptedTransition + ": change control is off");
        throw new IllegalStateException("Change control is off, so permission windows cannot be "
                + attemptedTransition + ". While the switch is off a window would confer nothing, "
                + "and it would take effect unreviewed as soon as the switch was turned back on. "
                + "Ask an administrator to enable change control first.");
    }

    private GrantRequest require(String id) {
        GrantRequest request = store.loadGrantRequest(id);
        if (request == null) {
            throw new IllegalArgumentException("No such grant request: " + id);
        }
        return request;
    }

    private static boolean pendingExpired(GrantRequest request, Instant now) {
        return now.isAfter(pendingExpiry(request));
    }

    private static Instant pendingExpiry(GrantRequest request) {
        Duration timeout = Duration.ofHours(
                BatchControlGlobalConfiguration.get().getPendingTimeoutHours());
        return request.getCreatedAt().plus(timeout);
    }
}
