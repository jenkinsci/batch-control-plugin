package io.jenkins.plugins.batchcontrol.model;

import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.Ids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * A request for a temporary (JIT) change permission (SPEC item 8, section 3).
 * Persisted as XStream XML at {@code requests/grant/<id>.xml}.
 *
 * <p>Timestamps are persisted as epoch milliseconds ({@code long}) because the Jenkins XStream
 * class filter does not allow {@code java.time.Instant}; the accessors expose {@link Instant}.
 *
 * <p>Status transitions must only be performed by the policy services
 * ({@code policy.GrantRequestService}); other code must not call {@link #setStatus}.
 */
@Restricted(NoExternalUse.class)
public final class GrantRequest {

    /**
     * One approver change entry of a grant request: (previous set, new set, changed by, at)
     * (SPEC section 3, D-37). Same shape as {@link RunRequest.ApproverChange}.
     */
    public static final class ApproverChange {
        private final List<String> fromApprovers;
        private final List<String> toApprovers;
        private final String by;
        private final long atMillis;

        public ApproverChange(List<String> from, List<String> to, String by, Instant at) {
            this.fromApprovers = from == null ? new ArrayList<>() : new ArrayList<>(from);
            this.toApprovers = to == null ? new ArrayList<>() : new ArrayList<>(to);
            this.by = by;
            this.atMillis = Objects.requireNonNull(at, "at").toEpochMilli();
        }

        /** The previous designated set. */
        public List<String> getFromApprovers() {
            return fromApprovers == null ? new ArrayList<>() : new ArrayList<>(fromApprovers);
        }

        /** The new designated set. */
        public List<String> getToApprovers() {
            return toApprovers == null ? new ArrayList<>() : new ArrayList<>(toApprovers);
        }

        /** Compatibility view: the first member of the previous set, or {@code null}. */
        public String getFrom() {
            return fromApprovers == null || fromApprovers.isEmpty() ? null : fromApprovers.get(0);
        }

        /** Compatibility view: the first member of the new set, or {@code null}. */
        public String getTo() {
            return toApprovers == null || toApprovers.isEmpty() ? null : toApprovers.get(0);
        }

        public String getBy() {
            return by;
        }

        public Instant getAt() {
            return Instant.ofEpochMilli(atMillis);
        }
    }

    private final String id;
    private final GrantScope scope;
    /**
     * D-71: the kind of the scope item when the request was created (descriptor id, display name,
     * icon); approval is refused when the item's kind differs. {@code null} only for a request
     * built without one (never by {@code policy.GrantRequestService}), which cannot be approved.
     */
    private ItemKind itemKind;
    private final List<GrantAction> actions;
    private final int durationMinutes;
    private final String reason;
    private final String requester;
    /** The designated approver set (D-37); any member may decide. */
    private List<String> approvers;
    /** The approver who approved or rejected the request (D-37); {@code null} until decided. */
    private String decidedBy;
    /**
     * SPEC item 2, D-05 (#182): the approval was an administrator's self-approval (the deciding
     * approver is the requester). Recorded with the APPROVED decision only; a file written before
     * this field existed has no element and loads as {@code false}.
     */
    private boolean selfApproved;
    /** D-40: optional CREATE name restriction (exact name or {@code /regex/}); {@code null} for none. */
    private String createNamePattern;
    /** D-37: approver changes, as for run requests; {@code null} until the first change. */
    private List<ApproverChange> approverChanges;
    /** D-36: the EXPIRING notification was sent (persisted so a restart does not resend). */
    private boolean expiringNotified;
    private RequestStatus status;
    private final long createdAtMillis;
    private Long decidedAtMillis;
    private String decisionComment;

    private GrantRequest(String id, GrantScope scope, List<GrantAction> actions, int durationMinutes,
                         String reason, String requester, List<String> approvers,
                         String createNamePattern, RequestStatus status, Instant createdAt) {
        this.id = id;
        this.scope = scope;
        this.actions = new ArrayList<>(actions);
        this.durationMinutes = durationMinutes;
        this.reason = reason;
        this.requester = requester;
        this.approvers = Approvers.normalize(approvers);
        this.createNamePattern = CreateNamePattern.normalize(createNamePattern);
        this.status = status;
        this.createdAtMillis = createdAt.toEpochMilli();
    }

    /**
     * Creates a new PENDING grant request with a random UUID id (D-68) and its creation time
     * from {@link BatchClock}, recording the kind of the scope item (D-71).
     * Duplicate actions are collapsed while preserving order.
     */
    public static GrantRequest create(GrantScope scope, ItemKind itemKind, List<GrantAction> actions,
                                      int durationMinutes, String reason, String requester,
                                      List<String> approvers, String createNamePattern) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(actions, "actions");
        List<GrantAction> distinct = new ArrayList<>(new LinkedHashSet<>(actions));
        GrantRequest request = new GrantRequest(Ids.newRequestId(), scope, distinct, durationMinutes, reason,
                requester, approvers, createNamePattern, RequestStatus.PENDING, BatchClock.now());
        request.itemKind = itemKind;
        return request;
    }

    /**
     * As {@link #create(GrantScope, ItemKind, List, int, String, String, List, String)} without an
     * item kind. Such a request cannot be approved (D-71); kept for store fixtures.
     */
    public static GrantRequest create(GrantScope scope, List<GrantAction> actions, int durationMinutes,
                                      String reason, String requester, List<String> approvers,
                                      String createNamePattern) {
        return create(scope, null, actions, durationMinutes, reason, requester, approvers, createNamePattern);
    }

    /** Single-approver form without a name restriction, kept for callers written before D-37. */
    public static GrantRequest create(GrantScope scope, List<GrantAction> actions, int durationMinutes,
                                      String reason, String requester, String approver) {
        return create(scope, actions, durationMinutes, reason, requester, Approvers.of(approver), null);
    }

    public String getId() {
        return id;
    }

    public GrantScope getScope() {
        return scope;
    }

    /** D-71: the kind of the scope item recorded at creation, or {@code null} when none was recorded. */
    public ItemKind getItemKind() {
        return itemKind;
    }

    /** A defensive copy; the requested actions never change after creation. */
    public List<GrantAction> getActions() {
        return actions == null ? new ArrayList<>() : new ArrayList<>(actions);
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public String getReason() {
        return reason;
    }

    public String getRequester() {
        return requester;
    }

    /** The designated approver set (D-37), in designation order. */
    public List<String> getApprovers() {
        return approvers == null ? new ArrayList<>() : new ArrayList<>(approvers);
    }

    /** Compatibility view (D-37): the first designated approver, or {@code null} with none. */
    public String getApprover() {
        return approvers == null || approvers.isEmpty() ? null : approvers.get(0);
    }

    /** Whether {@code userId} is a member of the designated set. */
    public boolean isDesignatedApprover(String userId) {
        return Approvers.contains(approvers, userId);
    }

    /**
     * The user who closed the request: the approver who approved or rejected it, or the user who
     * cancelled it (e2e-03 DEF-13); {@code null} while undecided.
     */
    public String getDecidedBy() {
        return decidedBy;
    }

    /**
     * Whether the approving approver was the requester (administrator self-approval, SPEC item 2,
     * D-05, #182); {@code false} for any other decision and while undecided.
     */
    public boolean isSelfApproved() {
        return selfApproved;
    }

    /** D-40: the CREATE name restriction as written ({@code /regex/} or exact name), or {@code null}. */
    public String getCreateNamePattern() {
        return createNamePattern;
    }

    public List<ApproverChange> getApproverChanges() {
        return approverChanges == null ? new ArrayList<>() : new ArrayList<>(approverChanges);
    }

    /** Whether the D-36 EXPIRING notification was already sent. */
    public boolean isExpiringNotified() {
        return expiringNotified;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return Instant.ofEpochMilli(createdAtMillis);
    }

    public Instant getDecidedAt() {
        return decidedAtMillis == null ? null : Instant.ofEpochMilli(decidedAtMillis);
    }

    public String getDecisionComment() {
        return decisionComment;
    }

    /** Only the policy services may transition the status. */
    public void setStatus(RequestStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAtMillis = decidedAt == null ? null : decidedAt.toEpochMilli();
    }

    public void setDecisionComment(String decisionComment) {
        this.decisionComment = decisionComment;
    }

    /** Only the policy services change the designated set. */
    public void setApprovers(List<String> approvers) {
        this.approvers = Approvers.normalize(approvers);
    }

    /** Only the policy services record the deciding approver. */
    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    /** Only the policy services record a self-approval, with the APPROVED decision (SPEC item 2, #182). */
    public void setSelfApproved(boolean selfApproved) {
        this.selfApproved = selfApproved;
    }

    /** Only the policy services record approver changes. */
    public void addApproverChange(ApproverChange change) {
        if (approverChanges == null) {
            approverChanges = new ArrayList<>();
        }
        approverChanges.add(Objects.requireNonNull(change, "change"));
    }

    /** Only the policy services mark the D-36 EXPIRING notification as sent. */
    public void setExpiringNotified(boolean expiringNotified) {
        this.expiringNotified = expiringNotified;
    }
}
