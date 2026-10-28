package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.ModelObject;
import hudson.security.Permission;
import hudson.util.FormValidation;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.model.CreateNamePattern;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.ItemKind;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.ui.ApproverInput;
import io.jenkins.plugins.batchcontrol.ui.ApproverOptions;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.Dialogs;
import io.jenkins.plugins.batchcontrol.ui.FormErrors;
import io.jenkins.plugins.batchcontrol.ui.KindIcon;
import io.jenkins.plugins.batchcontrol.ui.Visibility;
import io.jenkins.plugins.batchcontrol.ui.HttpVerbs;
import io.jenkins.plugins.batchcontrol.ui.Paging;
import io.jenkins.plugins.batchcontrol.ui.RecordLookup;
import io.jenkins.plugins.batchcontrol.ui.ScopeDisplay;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
import io.jenkins.plugins.batchcontrol.ui.StatusLabels;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * The grant screens at {@code /batch-control/grants/} (SPEC item 8, JIT permission requests).
 *
 * <p>URL space (fixed contract, asserted by tests):
 * <ul>
 *   <li>{@code /batch-control/grants/} — pending requests, active and ended windows (served from
 *       {@code index.jelly}; PUT/DELETE/PATCH get 405). D-66: no form here; the old prefill
 *       links ({@code ?scopeFullName=}, {@code ?from=}) are redirected to {@code new}</li>
 *   <li>{@code /batch-control/grants/new} — the new request form as a page;
 *       {@code /batch-control/grants/dialog} — the same form for core's dialog (D-66)</li>
 *   <li>{@code POST /batch-control/grants/create} — submit a new grant request</li>
 *   <li>{@code POST /batch-control/grants/checkScopeFullName} — the form's check of the item
 *       name (D-71): its kind, or "No such item"</li>
 *   <li>{@code /batch-control/grants/<id>/} — request detail; POST {@code approve} /
 *       {@code reject} / {@code cancel} / {@code revoke} (see {@link GrantRequestItem})</li>
 *   <li>{@code POST /batch-control/grants/active/<grantId>/revoke} — revoke an active grant
 *       (see {@link ActiveGrantsSection})</li>
 * </ul>
 *
 * <p>No state transition logic lives here; everything is delegated to
 * {@link GrantRequestService} and {@link GrantService}. Viewing requires one of the plugin
 * permissions (requesters see their requests, approvers their inbox, managers the active
 * grants), and the whole subtree additionally closes while change control is off — both enforced
 * by {@link #getTarget()}. The one exception to the closure is {@code POST <id>/approve}, which
 * goes on to {@link GrantRequestService#approve} so that the service refuses and records the
 * approval (LIMITATIONS 29).
 *
 * <p>The switch closes this screen and nothing else. It must never reach the audit trail: the
 * Change Records and History screens keep showing the windows that existed and the changes made
 * under them, because a switch flip that retroactively hid audit records would be a worse defect
 * than the one the gate fixes.
 */
@Restricted(NoExternalUse.class)
public class GrantsSection implements ModelObject, StaplerProxy {

    /** {@link FormErrors} name of the new-request form. */
    static final String CREATE_FORM = "create";

    /** Page size for both the request list and the active grant list. */
    public static final int PAGE_SIZE = Paging.PAGE_SIZE;

    /**
     * What a caller is told when they reach this screen while change control is off (P-15).
     *
     * <p>It names the switch and where it lives on purpose. The alternative — 404 or a bare 403 —
     * is the U-01 failure recreated one screen along: a user following a link they were given
     * yesterday would have no way to tell which of "I lost a permission", "the screen moved" and
     * "an administrator turned something off" had happened. The closing sentence exists because
     * the switch going off revokes every open window, so the natural next question is whether the
     * record of them survived; it does.
     */
    private static final String CHANGE_CONTROL_OFF_MESSAGE =
            "Change control is off, so this instance is not using permission windows and the "
            + "Grants screen is closed. While the switch is off a window confers nothing, and a "
            + "window approved now would take effect unreviewed the moment it was turned back on, "
            + "so no window can be requested or approved either. An administrator can turn it on "
            + "with \"Enable change control\" in the Batch Control section of Manage Jenkins > "
            + "System. Nothing has been deleted from the audit trail: windows that existed, and "
            + "the changes made under them, are still on the Change Records and History screens.";

    /** Per-request cache of the pending, active and ended lists (D-66). */
    private Lists lists;

    /** Per-request cache of every stored grant by id. */
    private Map<String, Grant> grantsById;

    /** Per-request cache of {@link #getRenewScope()}. */
    private ScopeDisplay renewScope;

    @Override
    public Object getTarget() {
        // Gate the entire /batch-control/grants/** subtree (list, details, POST endpoints go
        // through their own additional checks below and in GrantRequestItem/ActiveGrantsSection).
        Jenkins.get().checkAnyPermission(SectionAccess.grants());
        HttpVerbs.refuseUnsupported();
        // P-15 / SPEC item 1: with change control off, no change-control UI may appear. This whole
        // subtree is change-control UI, so it closes with the switch. GrantRequestService refuses
        // create and approve independently — this is the screen half of the same gate, not a
        // replacement for it, and the permission check above stays first so only a caller who
        // would otherwise be let in learns which switch is off.
        if (!BatchControlGlobalConfiguration.get().isChangeControlEnabled()) {
            if (isApprovalOfVisibleRequest()) {
                // LIMITATIONS 29, P-15 (3): the one POST that passes. GrantRequestService#approve
                // refuses an approval while the switch is off and writes its GRANT_REQUEST_BLOCKED
                // record, so the rule and the record stay in the service; GrantRequestItem#doApprove
                // still checks POST and Approve first and shows the refusal on the request's page.
                return this;
            }
            recordRefusedCreate();
            throw new Failure(CHANGE_CONTROL_OFF_MESSAGE);
        }
        return this;
    }

    /** {@code /<id>/approve} below this screen, with the id as the only segment before it. */
    private static final Pattern APPROVE_PATH = Pattern.compile("/([^/]+)/approve/?");

    /**
     * T-GAP-230: whether this is a POST to {@code <id>/approve} of a grant request the caller may
     * see ({@link #getDynamic}). Anything else below the closed screen keeps getting the
     * explanation, an id that does not resolve included, so no other endpoint or view opens.
     */
    private boolean isApprovalOfVisibleRequest() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null || !"POST".equals(req.getMethod())) {
            return false;
        }
        String rest = req.getRestOfPath();
        Matcher m = rest == null ? null : APPROVE_PATH.matcher(rest);
        return m != null && m.matches() && getDynamic(m.group(1)) != null;
    }

    /**
     * e2e-03 DEF-27: a grant request POSTed while change control is off never reaches
     * {@link #doCreate}, because this whole subtree is closed. It is still a refused request, so
     * the policy layer records it ({@link GrantRequestService#refuseRequestWhileChangeControlOff});
     * the caller then gets the screen's explanation as before. Only a POST to {@code create}
     * counts: opening the closed screen is not a request. The permission check of
     * {@link #getTarget()} has already passed, and the CSRF crumb filter runs before Stapler.
     */
    private static void recordRefusedCreate() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null || !"POST".equals(req.getMethod())) {
            return;
        }
        String rest = req.getRestOfPath();
        if (rest == null || !(rest.equals("/create") || rest.equals("/create/"))) {
            return;
        }
        String scope = Util.fixEmptyAndTrim(req.getParameter("scopeFullName"));
        if (scope != null && scope.length() > MAX_RECORDED_SCOPE_LENGTH) {
            scope = scope.substring(0, MAX_RECORDED_SCOPE_LENGTH);
        }
        try {
            GrantRequestService.get().refuseRequestWhileChangeControlOff(scope);
        } catch (IllegalStateException expected) {
            // Recorded; the caller gets CHANGE_CONTROL_OFF_MESSAGE from getTarget().
        }
    }

    /** Bound on the user-supplied scope name written into the refusal record. */
    private static final int MAX_RECORDED_SCOPE_LENGTH = 1000;

    @Override
    public String getDisplayName() {
        return "Grants";
    }


    /** Permissions for this screen's {@code l:layout} (the same set its section gate checks). */
    public Permission[] getViewPermissions() {
        return SectionAccess.grants();
    }

    // ---------------------------------------------------------------- routing

    /**
     * Stapler: serves {@code /batch-control/grants/<id>/}; {@code null} renders a 404.
     * A grant request the caller may not see (P-09, S-01) renders exactly like a nonexistent
     * one so its existence is not disclosed, and so does every failed lookup (S-39-01,
     * {@link RecordLookup}).
     */
    @CheckForNull
    public GrantRequestItem getDynamic(String id) {
        GrantRequest request = RecordLookup.find(id, "grant request", i -> GrantRequestService.get().load(i),
                GrantRequest::getId, Visibility::canSeeGrantRequest);
        return request == null ? null : new GrantRequestItem(request);
    }

    /** Stapler: serves {@code /batch-control/grants/active/...} (revoke endpoints). */
    public ActiveGrantsSection getActive() {
        return new ActiveGrantsSection();
    }

    // ---------------------------------------------------------------- create endpoint

    /**
     * POST {@code /batch-control/grants/create} — submits a new grant request. This method only
     * parses HTTP input; all business validation (action set non-empty, duration cap, approver
     * eligibility, reason required) is enforced by {@link GrantRequestService#create}.
     *
     * <p>Form fields: {@code scopeFullName} (the one job or folder the window names, D-71; a
     * {@code scopeType} field sent by an earlier form is ignored), {@code actions}
     * (multi-valued checkboxes), {@code durationMinutes} (preset select),
     * {@code customDurationMinutes} (optional free number overriding the preset, capped
     * client-side at {@code maxGrantMinutes} and re-checked by the service), {@code reason},
     * {@code approvers} (repeated, one user id each; D-37) and the optional
     * {@code createNamePattern} (exact name or {@code /regex/}, CREATE only; D-40). The form shows
     * the name restriction only while Create is ticked, so it is read only for a request
     * that includes CREATE, as core's {@code f:optionalBlock} does not count the fields of a
     * collapsed block.
     */
    @RequirePOST
    public void doCreate(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST_GRANT);

        // e2e-03 DEF-09: every refusal of the user's input is shown on this screen next to the
        // field it concerns, with the input kept (FormErrors), instead of a bare "Error" page.
        FormErrors errors = new FormErrors(CREATE_FORM);
        GrantScope scope = parseScope(req.getParameter("scopeFullName"), errors);
        List<GrantAction> actions = parseActions(req.getParameterValues("actions"), errors);
        int durationMinutes = parseDuration(
                req.getParameter("durationMinutes"), req.getParameter("customDurationMinutes"), errors);
        String reason = req.getParameter("reason");
        if (reason == null || reason.trim().isEmpty()) {
            errors.field("reason", "Enter a reason: the approvers decide on it.");
        }
        List<String> approvers = List.of();
        try {
            approvers = ApproverInput.read(req, null);
        } catch (Failure e) {
            errors.field("approvers", e.getMessage());
        }
        if (approvers.isEmpty()) {
            errors.field("approvers", "Check at least one approver.");
        }
        // A value left in the hidden field of an unticked Create does not count.
        String createNamePattern = actions.contains(GrantAction.CREATE)
                ? parseCreateNamePattern(req.getParameter("createNamePattern"), errors)
                : null;

        if (errors.isEmpty()) {
            try {
                GrantRequest created = GrantRequestService.get().create(scope, actions, durationMinutes,
                        reason, approvers, createNamePattern);
                // Land on the new request's detail page, like run and activation
                // requests (the requester may always see their own request, P-09).
                rsp.sendRedirect2(req.getContextPath() + "/batch-control/grants/"
                        + Util.rawEncode(created.getId()) + "/");
                return;
            } catch (IllegalArgumentException | IllegalStateException e) {
                // D-71: the item refusals first, by phrases that an item name inside the message
                // cannot imitate as easily as a single word ("reason", "duration") can.
                errors.fromService(e.getMessage(), "no such item", "scopeFullName",
                        "part of another job", "scopeFullName", "action applies only", "actions",
                        "name restriction", "createNamePattern",
                        "duration", "customDurationMinutes", "reason", "reason",
                        "approver", "approvers", "action", "actions", "no such", "scopeFullName",
                        "scope", "scopeFullName");
            }
        }
        // D-66: a refusal is shown where the form was, in the dialog or on the form page.
        errors.render(req, rsp, this, Dialogs.refusalView(req, "new.jelly"));
    }

    /**
     * D-66: the query string to carry from an old prefill link of the list page
     * ({@code /batch-control/grants/?scopeFullName=...} or {@code ?from=...}) to the form page,
     * or {@code null} when the list page was opened without one. Only the prefill parameters are
     * carried, each URL-encoded; the form page resolves them again before it shows anything.
     */
    @CheckForNull
    public String getLegacyFormQuery() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null || (req.getParameter("scopeFullName") == null && req.getParameter("from") == null)) {
            return null;
        }
        StringBuilder query = new StringBuilder();
        for (String name : new String[] {"scopeFullName", "from", "actions"}) {
            String[] values = req.getParameterValues(name);
            if (values == null) {
                continue;
            }
            for (String value : values) {
                query.append(query.length() == 0 ? "" : "&").append(name).append('=')
                        .append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return query.toString();
    }

    /** The refusal of the new-request form on this request, or an empty one (DEF-09, Jelly). */
    public FormErrors getFormErrors() {
        return FormErrors.current(CREATE_FORM);
    }

    // ---------------------------------------------------------------- form input parsing

    /**
     * D-71: a window names exactly one item, so the scope is the full name alone. Whether that
     * item exists, is visible to the requester and can carry the requested actions is the
     * service's to decide ({@link GrantRequestService#create}).
     */
    @CheckForNull
    private static GrantScope parseScope(@CheckForNull String rawFullName, FormErrors errors) {
        String fullName = rawFullName == null ? "" : rawFullName.trim();
        if (fullName.isEmpty()) {
            errors.field("scopeFullName",
                    "Enter the full name of the job or folder, for example team/nightly-report.");
            return null;
        }
        return GrantScope.item(fullName);
    }

    // ---------------------------------------------------------------- form validation (D-71)

    /** What the item check answers for an item that does not exist or that the caller cannot see. */
    static final String NO_SUCH_ITEM = "No such item";

    /**
     * POST {@code /batch-control/grants/checkScopeFullName} — the request form's check of the
     * item name (D-71): the kind of the job or folder the window would name, for example
     * {@code Pipeline 'team/nightly'}, so the requester sees what they are asking for before
     * submitting. Read-only; {@link #doCreate} and the service decide for real.
     *
     * <p>The item is looked up as the caller ({@link GrantRequestService#findScopeItem}): an
     * item that does not exist and one the caller may not read (Item/Discover only included)
     * get the same answer, so the check cannot be used to probe for names. The answer only
     * repeats the item's own full name and its descriptor's display name. A blank field gets no
     * message (the form is not filled in yet; submitting it is refused next to the field).
     *
     * <p>spec-review-S6 M-1: the kind is shown with its icon, as in the lists
     * ({@code tags/scopeItem.jelly}), so the answer is markup ({@link #kindMarkup}).
     */
    @RequirePOST
    public FormValidation doCheckScopeFullName(@QueryParameter String value) {
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST_GRANT);
        String fullName = Util.fixEmptyAndTrim(value);
        if (fullName == null) {
            return FormValidation.ok();
        }
        Item item = GrantRequestService.findScopeItem(fullName);
        if (item == null) {
            return FormValidation.error(NO_SUCH_ITEM);
        }
        ItemKind kind = ItemKind.of(item);
        if (kind == null) {
            // A matrix configuration or Maven module: readable, but part of its job.
            return FormValidation.error("'" + item.getFullName() + "' is part of another job and cannot be "
                    + "named by a permission window; name the job it belongs to.");
        }
        return FormValidation.okWithMarkup(kindMarkup(kind, item.getFullName()));
    }

    /** The most full names offered for one keystroke in the item field. */
    static final int MAX_SCOPE_SUGGESTIONS = 20;

    /**
     * GET {@code /batch-control/grants/autoCompleteScopeFullName} — the suggestions of the request
     * form's item field: the full names of the jobs and folders that contain what the requester has
     * typed so far, ignoring case, those that start with it first. Read-only, so it needs no POST; it
     * needs the permission to request a window, like the item check.
     *
     * <p>Only items the caller may read are offered (Item/Read, as {@link Jenkins#allItems}
     * applies it), and only items a window can name ({@link ItemKind#of}, which leaves out the parts
     * of a job such as a matrix configuration), so a suggestion is never refused by
     * {@link #doCheckScopeFullName} and an invisible item is not disclosed by being offered. An
     * empty field gets no suggestions, and at most {@link #MAX_SCOPE_SUGGESTIONS} are offered.
     */
    // Read-only GET (autocomplete); REQUEST_GRANT is checked first and only readable items are offered.
    @SuppressWarnings("lgtm[jenkins/csrf]")
    public AutoCompletionCandidates doAutoCompleteScopeFullName(@QueryParameter String value) {
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST_GRANT);
        AutoCompletionCandidates candidates = new AutoCompletionCandidates();
        String typed = Util.fixEmptyAndTrim(value);
        if (typed == null) {
            return candidates;
        }
        String needle = typed.toLowerCase(Locale.ROOT);
        for (String fullName : scopeSuggestions(Jenkins.get().allItems(Item.class), needle)) {
            candidates.add(fullName);
        }
        return candidates;
    }

    /** The full names in {@code items} that contain {@code needle} (lower case), best first, capped. */
    static List<String> scopeSuggestions(Iterable<? extends Item> items, String needle) {
        List<String> starting = new ArrayList<>();
        List<String> containing = new ArrayList<>();
        for (Item item : items) {
            String fullName = item.getFullName();
            if (ItemKind.of(item) == null) {
                continue;
            }
            String lower = fullName.toLowerCase(Locale.ROOT);
            if (lower.startsWith(needle)) {
                starting.add(fullName);
            } else if (lower.contains(needle)) {
                containing.add(fullName);
            }
        }
        starting.sort(String.CASE_INSENSITIVE_ORDER);
        containing.sort(String.CASE_INSENSITIVE_ORDER);
        return Stream.concat(starting.stream(), containing.stream()).limit(MAX_SCOPE_SUGGESTIONS)
                .collect(Collectors.toList());
    }

    /**
     * The item check's answer for an item of {@code kind}: one element carrying the descriptor id
     * as {@code data-batch-control-item-kind} (the hook of {@code tags/scopeItem.jelly}), holding
     * the kind's icon ({@link KindIcon}, an {@code svg} without text) and then the text
     * {@code <kind display name> '<full name>'} — exactly the text of the earlier plain answer.
     *
     * <p>Every value that is not a constant of this method is escaped with {@link Util#escape},
     * which is what {@link FormValidation#ok(String)} applied to the plain answer: the display
     * name and the full name (a job name is user input), and the descriptor id. The icon markup
     * is core's symbol SVG for the descriptor's icon class, which is plugin code, not user input.
     */
    static String kindMarkup(ItemKind kind, String fullName) {
        return "<span data-batch-control-item-kind=\"" + Util.escape(kind.getDescriptorId()) + "\">"
                + KindIcon.svg(kind.getIconClassName(), "icon-sm")
                + "<span class=\"jenkins-!-margin-left-1\">"
                + Util.escape(kind.getDisplayName() + " '" + fullName + "'")
                + "</span></span>";
    }

    private static List<GrantAction> parseActions(@CheckForNull String[] raw, FormErrors errors) {
        List<GrantAction> actions = new ArrayList<>();
        if (raw != null) {
            for (String value : raw) {
                try {
                    actions.add(GrantAction.valueOf(value.trim()));
                } catch (IllegalArgumentException e) {
                    errors.field("actions", "Only Create, Configure and Delete can be requested.");
                }
            }
        }
        if (actions.isEmpty()) {
            errors.field("actions", "Check at least one action.");
        }
        return actions;
    }

    /**
     * Blank means no restriction. Only the length is bounded here, before the text reaches the
     * regex compiler; syntax and item-name validity are the service's to refuse. Called only for a
     * request that includes CREATE; the service still refuses a restriction without it.
     */
    @CheckForNull
    private static String parseCreateNamePattern(@CheckForNull String raw, FormErrors errors) {
        String pattern = CreateNamePattern.normalize(raw);
        if (pattern != null && pattern.length() > CreateNamePattern.MAX_LENGTH) {
            errors.field("createNamePattern", "The name restriction must not exceed "
                    + CreateNamePattern.MAX_LENGTH + " characters.");
        }
        return pattern;
    }

    /**
     * The requested window length. The upper bound is checked here too so the message names the
     * limit in the user's terms (e2e-03 DEF-09 quoted the service's internal setting name); the
     * service re-checks it.
     */
    private static int parseDuration(@CheckForNull String preset, @CheckForNull String custom,
                                     FormErrors errors) {
        boolean customSet = custom != null && !custom.trim().isEmpty();
        String field = customSet ? "customDurationMinutes" : "durationMinutes";
        String raw = customSet ? custom : preset;
        if (raw == null || raw.trim().isEmpty()) {
            errors.field(field, "Choose a duration.");
            return 0;
        }
        int minutes;
        try {
            minutes = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            errors.field(field, "Enter the duration as a whole number of minutes.");
            return 0;
        }
        int max = BatchControlGlobalConfiguration.get().getMaxGrantMinutes();
        if (minutes < 1) {
            errors.field(field, "The duration must be at least 1 minute.");
        } else if (minutes > max) {
            errors.field(field, "At most " + max + " minutes can be requested. Ask for a shorter "
                    + "window, and request a new one when it ends if you need more time.");
        }
        return minutes;
    }

    // ---------------------------------------------------------------- view model (used from Jelly)

    /** View gating for the new-request form; {@link #doCreate} re-checks for real. */
    public boolean isCanRequest() {
        return Jenkins.get().hasPermission(BatchControlPermissions.REQUEST_GRANT);
    }

    /** View gating for the Revoke buttons; the revoke endpoint re-checks for real. */
    public boolean isCanRevoke() {
        return Jenkins.get().hasPermission(BatchControlPermissions.MANAGE);
    }

    /** Preset duration choices from the global configuration. */
    public List<Integer> getDurationOptions() {
        return BatchControlGlobalConfiguration.get().getGrantDurationOptions();
    }

    /** Maximum length of the CREATE name restriction, for the form's {@code maxlength}. */
    public int getCreateNamePatternMaxLength() {
        return CreateNamePattern.MAX_LENGTH;
    }

    /** Upper bound in minutes for a grant request (client-side cap; the service re-checks). */
    public int getMaxGrantMinutes() {
        return BatchControlGlobalConfiguration.get().getMaxGrantMinutes();
    }

    /** Approver candidates (global list minus the current user, per the self-approval policy). */
    public List<String> getApproverOptions() {
        return ApproverOptions.forJob(null);
    }

    /**
     * e2e-09 DEF-01: a timestamp as its date and its time-with-zone, for the grant tables,
     * which render each part unbroken so that a narrow window breaks a cell only between them
     * (never inside a date) and the page does not scroll sideways at 1280 px. Empty for null.
     */
    public List<String> formatParts(Instant instant) {
        String text = Dates.format(instant);
        int space = text.indexOf(' ');
        return space < 0 ? List.of(text) : List.of(text.substring(0, space), text.substring(space + 1));
    }

    /**
     * Jelly helper: how much of a grant window is left ({@code 12 min 30 sec}), so the user does
     * not have to subtract the absolute expiry time from the current time (UX-11).
     *
     * @return the remaining span, or {@code "expired"} once the window has closed (a grant can
     *         still be listed for the moment between expiry and the sweeper run)
     */
    public String remaining(@CheckForNull Instant expiresAt) {
        return Dates.until(expiresAt);
    }

    /** Jelly helper: {@code 1 minute} / {@code 15 minutes} for the duration select (UX-12). */
    public String minutesLabel(int minutes) {
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }

    // ------------------------------------------------------- new-request prefill (U-01)

    /**
     * Whether the form was opened for a folder where a window's Create applies
     * ({@code ?scopeFullName=} resolves, as the viewer, to a regular folder; D-71: not a job and
     * not a computed folder such as a multibranch project or an organization folder).
     */
    public boolean isPrefilledForFolder() {
        if (getRenewSource() != null) {
            return false;
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String raw = req == null ? null : req.getParameter("scopeFullName");
        if (raw == null || raw.trim().isEmpty()) {
            return false;
        }
        return GrantScope.createAppliesTo(Visibility.findVisibleItem(raw.trim()));
    }

    /**
     * Full name the new-request form starts with: the renewed grant's scope (D-33), else
     * {@code ?scopeFullName=} — how {@link JobGrantRequestAction} hands the job over, so a user
     * who arrives from a job page does not have to retype its path (U-01).
     *
     * <p>The parameter is <em>resolved</em>, never echoed: the name is looked up through
     * {@link Visibility#findVisibleItem} and what the form receives is the model object's own
     * {@code getFullName()}. An item that does not exist, or that the caller cannot see, yields
     * an empty field, so this cannot be used to reflect arbitrary text into the page or to probe
     * for names — it renders exactly what a caller could already read from the item's own URL.
     * A renewed grant is likewise resolved from the store, and only the caller's own; its item's
     * name is the one {@link #getRenewScope()} shows (D-75 (1)).
     *
     * @return the canonical full name, or an empty string when there is nothing to prefill
     */
    public String getPrefillScopeFullName() {
        ScopeDisplay renewed = getRenewScope();
        if (renewed != null) {
            // D-75 (1): the renewed window's followed name only to a viewer who may read the item;
            // otherwise the field starts empty (the approved name may name another item by now, and
            // a request for an item the requester cannot read is refused anyway).
            return renewed.isMoved() ? "" : Util.fixNull(renewed.getFullName());
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String raw = req == null ? null : req.getParameter("scopeFullName");
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        Item item = Visibility.findVisibleItem(raw.trim());
        return item == null ? "" : item.getFullName();
    }

    /**
     * Whether the form was opened for a specific item: from a job's "Request Change Permission"
     * entry (CONFIGURE then starts checked, the user is trying to change that job) or from the
     * re-request link of an ended grant (its actions start checked, D-33).
     */
    public boolean isPrefilled() {
        return !getPrefillScopeFullName().isEmpty();
    }

    /**
     * D-75 (1), security-39 S-39-04: the name the form's prefill note shows for the renewed grant's
     * item ({@code tags/scopeItem.jelly}), or {@code null} when the form renews nothing. A window's
     * followed name only to a viewer who may read the item now; otherwise the approved name with
     * the fixed "moved" note, and the name field starts empty ({@link #getPrefillScopeFullName()}).
     */
    @CheckForNull
    public ScopeDisplay getRenewScope() {
        Grant source = getRenewSource();
        if (source == null) {
            return null;
        }
        if (renewScope == null) {
            GrantRequest request = GrantRequestService.get().load(source.getGrantRequestId());
            renewScope = ScopeDisplay.of(request == null ? null : request.getScope(), source.getScope());
        }
        return renewScope;
    }

    /** The ended grant the form renews ({@code ?from=<grantId>}), or {@code null}. */
    @CheckForNull
    public Grant getRenewSource() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String raw = req == null ? null : Util.fixEmptyAndTrim(req.getParameter("from"));
        if (raw == null) {
            return null;
        }
        Grant grant = grantsById().get(raw);
        // Only the caller's own grant: a window is requested for oneself.
        return grant != null && isOwn(grant) ? grant : null;
    }

    /**
     * Whether the action checkbox starts checked: the renewed grant's actions; else the
     * {@code ?actions=} values (matched against {@link GrantAction}, how a refusal such as the
     * delete veto hands over the action it needs, DEF-26); else CONFIGURE when the form was
     * opened for an item.
     */
    public boolean isPrefillAction(String action) {
        Grant source = getRenewSource();
        if (source != null) {
            return source.getActions().stream().anyMatch(a -> a.name().equals(action));
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String[] requested = req == null ? null : req.getParameterValues("actions");
        if (requested != null && isPrefilled()) {
            for (String value : requested) {
                if (value != null && value.trim().equals(action)) {
                    return true;
                }
            }
            return false;
        }
        // R4-14: from a regular folder, Create (New Item) is the usual need; from a job, a
        // multibranch project or an organization folder, Configure (D-71: Create applies only to
        // a regular folder).
        GrantAction usual = isPrefilledForFolder() ? GrantAction.CREATE : GrantAction.CONFIGURE;
        return isPrefilled() && usual.name().equals(action);
    }

    /** The renewed grant's CREATE name restriction, or empty. */
    public String getPrefillCreateNamePattern() {
        Grant source = getRenewSource();
        return source == null ? "" : Util.fixNull(source.getCreateNamePattern());
    }

    /**
     * Whether the preset {@code minutes} starts selected: the renewed grant's length when it is
     * one of the presets. Otherwise none is marked and the browser shows the first option.
     */
    public boolean isPrefillDuration(int minutes) {
        Grant source = getRenewSource();
        return source != null && minutesOf(source) == minutes;
    }

    // ------------------------------------------------- the three lists (D-66, R4-8)

    /**
     * One row of the Grants lists: a grant request and, once approved, its permission window
     * (the window carries the request's id). D-66: every request is listed exactly once — pending,
     * active (its window is open) or ended (its window ended, or it was never approved) — instead
     * of a request table that repeated the window tables.
     *
     * <p>A window whose request is no longer stored is listed on its own, without a detail link.
     *
     * <p>D-74: a row with a window names the window's item ({@link Grant#getScope()}), not the
     * name the request was made for: an open window follows its item when an administrator (or a
     * user with their own permissions) renames or moves it, and an ended one keeps the name its
     * item had when it ended. D-75 (1), security-39 S-39-04: that followed name is shown only to a
     * viewer who may read the item ({@link #getShownScope()}).
     */
    public static final class Row {

        @CheckForNull
        private final GrantRequest request;

        @CheckForNull
        private final Grant grant;

        private final String id;

        /** The item the request was made for, or {@code null} for a window without its request. */
        @CheckForNull
        private final GrantScope approvedScope;

        /** The item the row concerns now: the window's (D-74), else the request's. */
        @CheckForNull
        private final GrantScope scope;

        /** {@link #getShownScope()}, decided once per rendering, for the rows on the page only. */
        @CheckForNull
        private ScopeDisplay shownScope;

        /** D-71: the kind of the scope item, or {@code null} when none was recorded. */
        @CheckForNull
        private final ItemKind itemKind;

        private final List<GrantAction> actions;

        private final String user;

        @CheckForNull
        private final String createNamePattern;

        /** A request, with its window once approved. */
        Row(GrantRequest request, @CheckForNull Grant grant) {
            this.request = request;
            this.grant = grant;
            this.id = request.getId();
            this.approvedScope = request.getScope();
            this.scope = grant != null && grant.getScope() != null ? grant.getScope() : request.getScope();
            this.itemKind = request.getItemKind() != null || grant == null
                    ? request.getItemKind() : grant.getItemKind();
            this.actions = request.getActions();
            this.user = grant != null ? grant.getUser() : request.getRequester();
            this.createNamePattern = request.getCreateNamePattern();
        }

        /** A window whose request is no longer stored. */
        Row(Grant grant) {
            this.request = null;
            this.grant = grant;
            this.id = grant.getId();
            this.approvedScope = null;
            this.scope = grant.getScope();
            this.itemKind = grant.getItemKind();
            this.actions = grant.getActions();
            this.user = grant.getUser();
            this.createNamePattern = grant.getCreateNamePattern();
        }

        public String getId() {
            return id;
        }

        @CheckForNull
        public GrantRequest getRequest() {
            return request;
        }

        @CheckForNull
        public Grant getGrant() {
            return grant;
        }

        /** Whether the row links to a detail page ({@code /batch-control/grants/<id>/}). */
        public boolean isLinked() {
            return request != null;
        }

        /**
         * D-75 (1), security-39 S-39-04: the name the Scope cell shows ({@code tags/scopeItem.jelly}):
         * a window's followed name only to a viewer who may read the item now (or an administrator),
         * otherwise the approved name with the fixed "moved" note; unchanged when the name did not
         * change. The current name itself has no view getter, so the page cannot show it otherwise.
         */
        public ScopeDisplay getShownScope() {
            if (shownScope == null) {
                shownScope = ScopeDisplay.of(approvedScope, scope);
            }
            return shownScope;
        }

        /** D-71: the kind of the item the row names (icon and display name), or {@code null}. */
        @CheckForNull
        public ItemKind getItemKind() {
            return itemKind;
        }

        public List<GrantAction> getActions() {
            return actions;
        }

        /** The window holder, or the requester before there is a window. */
        public String getUser() {
            return user;
        }

        /**
         * #18.2 (colour badges): the status of the row's request, which the How cell of an ended
         * row carries in its badge ({@code tags/status.jelly}) next to its own wording: "Rejected
         * by a1" for a rejected request, and for an approved one how its window ended ("Expired",
         * "Revoked by admin"). {@code null} for a window without a stored request, whose cell is
         * plain text.
         */
        @CheckForNull
        public RequestStatus getRequestStatus() {
            GrantRequest r = request;
            return r == null ? null : r.getStatus();
        }

        /** When the row was created: the request's creation, else the window's start. */
        Instant createdAt() {
            GrantRequest r = request;
            if (r != null) {
                return r.getCreatedAt();
            }
            Grant g = grant;
            return g == null ? Instant.EPOCH : g.getGrantedAt();
        }
    }

    /** Pending grant requests on the current page, oldest first (closest to its timeout on top). */
    public List<Row> getPendingItems() {
        return Paging.slice(lists().pending, getPendingPage());
    }

    public int getPendingPage() {
        return Paging.currentPage("pendingPage");
    }

    public int getPendingTotal() {
        return lists().pending.size();
    }

    public boolean isHasPendingPrevious() {
        return Paging.hasPrevious(getPendingPage());
    }

    public boolean isHasPendingNext() {
        return Paging.hasNext(getPendingPage(), getPendingTotal());
    }

    /** Open permission windows on the current page, most recently granted first. */
    public List<Row> getActiveItems() {
        return Paging.slice(lists().active, getActivePage());
    }

    public int getActivePage() {
        return Paging.currentPage("activePage");
    }

    public int getActiveTotal() {
        return lists().active.size();
    }

    public boolean isHasActivePrevious() {
        return Paging.hasPrevious(getActivePage());
    }

    public boolean isHasActiveNext() {
        return Paging.hasNext(getActivePage(), getActiveTotal());
    }

    /**
     * Ended windows and requests that never became one (rejected, cancelled, expired), on the
     * current page, most recently ended first. SPEC item 8 (D-33): the history of expired windows
     * with a re-request link.
     */
    public List<Row> getEndedItems() {
        return Paging.slice(lists().ended, getEndedPage());
    }

    public int getEndedPage() {
        return Paging.currentPage("endedPage");
    }

    public int getEndedTotal() {
        return lists().ended.size();
    }

    public boolean isHasEndedPrevious() {
        return Paging.hasPrevious(getEndedPage());
    }

    public boolean isHasEndedNext() {
        return Paging.hasNext(getEndedPage(), getEndedTotal());
    }

    /**
     * How a grant ended, in words: "Expired", "Revoked by admin", or for a revocation by the
     * change control switch "Revoked (change control turned off) by admin".
     */
    public String endedLabel(Grant grant) {
        return GrantRequestItem.endedLabel(grant);
    }

    /** When a grant ended: its revocation time, else its expiry. */
    public Instant endedAt(Grant grant) {
        return grant.getRevokedAt() != null ? grant.getRevokedAt() : grant.getExpiresAt();
    }

    /** When a row ended: its window's end, else the request's decision (or creation). */
    public Instant endedAt(Row row) {
        Grant grant = row.grant;
        if (grant != null) {
            return endedAt(grant);
        }
        GrantRequest request = row.request;
        if (request != null && request.getDecidedAt() != null) {
            return request.getDecidedAt();
        }
        return row.createdAt();
    }

    /** How a row ended, in words: the window's end, else the request's outcome. */
    public String howEnded(Row row) {
        Grant grant = row.grant;
        if (grant != null) {
            return endedLabel(grant);
        }
        GrantRequest request = row.request;
        if (request == null) {
            return "";
        }
        RequestStatus status = request.getStatus();
        String by = request.getDecidedBy() == null ? "" : " by " + request.getDecidedBy();
        switch (status) {
            case REJECTED:
                return "Rejected" + by;
            case CANCELLED:
                return "Cancelled" + by;
            case EXPIRED:
                return "Expired without a decision";
            case INVALIDATED:
                return "Invalidated";
            default:
                return StatusLabels.of(status);
        }
    }

    /** Whether the re-request button is shown: the caller's own ended window, and may request. */
    public boolean isCanRenew(Row row) {
        Grant grant = row.grant;
        return grant != null && isCanRequest() && isOwn(grant);
    }

    /**
     * The CREATE name restriction of a row in words (DEF-19): the pattern, "any name" for an
     * unrestricted CREATE, empty when the row does not include CREATE.
     */
    public String createNameLabel(Row row) {
        if (!row.getActions().contains(GrantAction.CREATE)) {
            return "";
        }
        return row.createNamePattern == null ? "any name" : row.createNamePattern;
    }

    /** Jelly helper: comma-joined action list ("CREATE, CONFIGURE"). */
    public String join(Collection<?> items) {
        return items == null
                ? ""
                : items.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    // ---------------------------------------------------------------- helpers

    /** The three lists, computed once per rendering. */
    private static final class Lists {
        final List<Row> pending = new ArrayList<>();
        final List<Row> active = new ArrayList<>();
        final List<Row> ended = new ArrayList<>();
    }

    private Lists lists() {
        if (lists == null) {
            Instant now = BatchClock.now();
            Map<String, Grant> grants = grantsById();
            Lists built = new Lists();
            java.util.Set<String> listed = new java.util.HashSet<>();
            // P-09 visibility (S-01): only Manage, the requester or a designated approver see a
            // grant request (the same predicate as its detail page); paging runs over the filtered
            // lists.
            for (GrantRequest request : GrantRequestService.get().list()) {
                if (!Visibility.canSeeGrantRequest(request)) {
                    continue;
                }
                Grant grant = grants.get(request.getId());
                listed.add(request.getId());
                Grant window = request.getStatus() == RequestStatus.APPROVED ? grant : null;
                Row row = new Row(request, window);
                if (request.getStatus() == RequestStatus.PENDING) {
                    built.pending.add(row);
                } else if (window != null && window.isActiveAt(now)) {
                    built.active.add(row);
                } else {
                    built.ended.add(row);
                }
            }
            // A window whose request is no longer stored: own windows, or all with Manage (who
            // holds what where is recon data, S-01).
            for (Grant grant : grants.values()) {
                if (listed.contains(grant.getId()) || grant.getGrantedAt().isAfter(now)
                        || !Visibility.canSeeGrant(grant)) {
                    continue;
                }
                (grant.isActiveAt(now) ? built.active : built.ended).add(new Row(grant));
            }
            built.pending.sort(Comparator.comparing(Row::createdAt).thenComparing(Row::getId));
            built.active.sort(Comparator.comparing(GrantsSection::grantedAt).thenComparing(Row::getId).reversed());
            built.ended.sort(Comparator.comparing((Row r) -> endedAt(r)).thenComparing(Row::getId).reversed());
            lists = built;
        }
        return lists;
    }

    private static Instant grantedAt(Row row) {
        Grant grant = row.grant;
        return grant == null ? row.createdAt() : grant.getGrantedAt();
    }

    private static boolean isOwn(Grant grant) {
        // R4-01: under the realm's user id strategy, as the service compares ids.
        return Approvers.sameUser(Jenkins.getAuthentication2().getName(), grant.getUser());
    }

    private static long minutesOf(Grant grant) {
        return java.time.Duration.between(grant.getGrantedAt(), grant.getExpiresAt()).toMinutes();
    }

    /**
     * Every known grant by id, once per rendering, as the permission checks see it (D-74): read
     * through {@link GrantService#listAll()} so these lists agree with {@link GrantService#listActive()}.
     */
    private Map<String, Grant> grantsById() {
        if (grantsById == null) {
            Map<String, Grant> byId = new LinkedHashMap<>();
            for (Grant grant : GrantService.get().listAll()) {
                byId.put(grant.getId(), grant);
            }
            grantsById = byId;
        }
        return grantsById;
    }
}
