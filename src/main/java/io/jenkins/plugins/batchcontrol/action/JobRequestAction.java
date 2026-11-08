package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.Action;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.SimpleParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.security.Permission;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.IdenticalPendingRequestException;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.ParameterFiles;
import io.jenkins.plugins.batchcontrol.policy.RequestBodyLimit;
import io.jenkins.plugins.batchcontrol.policy.RequestTooLargeException;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantLayer;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.ParameterDisplay;
import io.jenkins.plugins.batchcontrol.store.StoreWriteException;
import io.jenkins.plugins.batchcontrol.store.XmlChars;
import io.jenkins.plugins.batchcontrol.ui.ApprovalRequiredFailure;
import io.jenkins.plugins.batchcontrol.ui.ApproverInput;
import io.jenkins.plugins.batchcontrol.ui.ApproverOptions;
import io.jenkins.plugins.batchcontrol.ui.Dialogs;
import io.jenkins.plugins.batchcontrol.ui.FormErrors;
import io.jenkins.plugins.batchcontrol.ui.RepeatedField;
import io.jenkins.plugins.batchcontrol.ui.ReplayedRuns;
import io.jenkins.plugins.batchcontrol.ui.RequestRunPrefill;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import jenkins.model.menu.Group;
import jenkins.model.menu.Semantic;
import jenkins.model.menu.event.DialogEvent;
import jenkins.model.menu.event.Event;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Per-job "Request Run" page at {@code /job/<name>/batch-control/} (attached by
 * {@link JobRequestActionFactory}). Renders the request form — reason, approver set and the
 * job's parameter definitions exactly like the core build page — and submits it to
 * {@link RunRequestService#create}.
 *
 * <p>The sidebar link is only visible when run control is on, the job requires approval and the
 * user holds {@code BatchControl/Request}. Without {@code BatchControl/Request} the action is
 * absent altogether ({@link #getUrlName()} returns {@code null}, SPEC item 2, S-07): the
 * form exposes the eligible approver user-id list, which is not for plain {@code Item/Read}
 * holders, so {@code /job/<name>/batch-control/} and every URL beneath it answer 404.
 */
@Restricted(NoExternalUse.class)
public class JobRequestAction implements Action {

    /** {@link FormErrors} name of the request form. */
    static final String FORM = "request";

    /** D-72b: prefix of the {@link FormErrors} field of one parameter ({@link #parameterField}). */
    static final String PARAMETER_FIELD_PREFIX = "parameter:";

    /**
     * D-72a: request attribute holding the incident reference {@link #doSubmit} validated, so a
     * refused submission re-renders the rerun notice and the hidden {@value RequestRunPrefill#FROM_RERUN}
     * field from the validated id, never from the raw field.
     */
    private static final String RERUN_ATTRIBUTE = JobRequestAction.class.getName() + ".rerunIncident";

    private final Job<?, ?> job;

    public JobRequestAction(Job<?, ?> job) {
        this.job = job;
    }

    public Job<?, ?> getJob() {
        return job;
    }

    /**
     * The job, under the name core's {@code l:job-subpage} reads ({@code it.object}), so the
     * request form renders as a sub-page of the job in both the classic and the new job page.
     */
    public Job<?, ?> getObject() {
        return job;
    }

    // ---------------------------------------------------------------- Action

    @Override
    @CheckForNull
    public String getIconFileName() {
        // e2e-03 DEF-12: the entry is offered only to a user who can submit the form
        // (BatchControl/Request and Item/Read on the job; Item/Build is not needed, D-38a).
        if (!isActive() || !isCanRequestRun()) {
            return null;
        }
        return "symbol-paper-plane-outline plugin-ionicons-api";
    }

    /**
     * Whether the current user may use this action at all: {@code BatchControl/Request} on this
     * job, so a Request assigned on the job or a folder above it counts (D-38a).
     */
    private boolean canRequest() {
        return job.hasPermission(BatchControlPermissions.REQUEST);
    }

    /**
     * Whether the current user may submit a run request for this job: {@code BatchControl/Request}
     * and {@code Item/Read} on the job (D-38a; {@code Item/Build} is not required), the checks
     * {@link RunRequestService#create} makes. View gating only; {@link #doSubmit} and the service
     * check for real.
     */
    public boolean isCanRequestRun() {
        return RunRequestService.get().canRequest(job);
    }

    /**
     * The one sidebar entry on a controlled job that reads "Request Run" (U-02): core's relabeled
     * build link is named {@link RequestRunUiDecorator#BLOCKED_BUILD_LABEL} so the two cannot be
     * confused.
     */
    @Override
    public String getDisplayName() {
        return RequestRunUiDecorator.REQUEST_RUN_LABEL;
    }

    /**
     * Hosting review 2026-10-02: on the new job page the run request is the job's build button,
     * so it sits first in the app bar rather than in the overflow menu. Only where the entry is
     * shown at all ({@link #getIconFileName()}); core's own {@code BuildJobAction} is in the same
     * group and reads {@link RequestRunUiDecorator#BLOCKED_BUILD_LABEL} on a controlled job.
     */
    @Override
    public Group getGroup() {
        return Group.FIRST_IN_APP_BAR;
    }

    /** Rendered as a build action (the build colour) on the new job page; see {@link #getGroup()}. */
    @Override
    public Semantic getSemantic() {
        return Semantic.BUILD;
    }

    /**
     * {@code null} without {@code BatchControl/Request}: per {@link Action#getUrlName()} that makes
     * the action unreachable, so its whole URL space answers 404 (absent, not refused).
     * {@link #doSubmit} re-checks the permission on top of this. Also {@code null} on a job's
     * sub-item (a matrix configuration, a Maven module, D-82): run requests are made on its parent
     * job, whose refusal page and notices link that job's form.
     */
    @Override
    @CheckForNull
    public String getUrlName() {
        return canRequest() && !ActivationService.isSubItem(job) ? "batch-control" : null;
    }

    /**
     * D-66: root-relative URL of the dialog form ({@code dialog.jelly}), which the classic sidebar
     * entry ({@code action.jelly}) opens in core's dialog.
     */
    public String getDialogUrl() {
        return job.getUrl() + "batch-control/dialog";
    }

    /**
     * D-66: on the new job page the entry opens the request form in core's dialog, as core's own
     * "Build with Parameters" does ({@link DialogEvent}); relative to the job's URL. The full page
     * stays for the refused direct build (D-60) and direct links.
     */
    @Override
    public Event getEvent() {
        return DialogEvent.of("batch-control/dialog");
    }

    /**
     * D-66: the check of the dialog view ({@code dialog.jelly}, rendered without a layout): the
     * permission of {@link #getViewPermissions()}, on the job.
     */
    public void checkDialogPermission() {
        job.checkPermission(BatchControlPermissions.REQUEST);
    }

    /** Permissions for the request form's {@code l:layout}. */
    public Permission[] getViewPermissions() {
        return new Permission[] {BatchControlPermissions.REQUEST};
    }

    /**
     * #132: the check of the request page ({@code index.jelly}), called first in the view, before
     * anything is written: {@link #getViewPermissions()} on the job, the nearest access-controlled
     * ancestor {@code l:layout permissions="..."} would check, refused with the same 403.
     * {@code l:job-subpage} takes no permissions attribute, and the view cannot make the check
     * through {@code h} above the layout: {@code h} is set by {@code l:view} inside
     * {@code l:layout}, and JEXL resolves a method call on an undefined variable to {@code null}
     * without calling anything.
     */
    public void checkViewPermission() {
        job.checkAnyPermission(getViewPermissions());
    }

    // ---------------------------------------------------------------- view model

    /**
     * True when run control is on and this job requires approved runs. Never on a job's sub-item
     * (D-82): it has no approval property and no run requests of its own.
     */
    public boolean isActive() {
        return !ActivationService.isSubItem(job) && RunRequestService.requiresApprovalToRun(job);
    }

    /**
     * The job's parameter definitions, rendered by each definition's own {@code index.jelly}.
     *
     * <p>e2e-03 DEF-09: when a submission is being refused, each definition whose value was
     * parsed is replaced by a copy defaulting to that value
     * ({@link ParameterDefinition#copyWithDefaultValue}), so the re-rendered form shows what the
     * user entered. Sensitive values (password parameters) and file values are never copied back
     * into the page (D-72: the form says to provide them again, {@link #getReenterParameterNames}).
     */
    public List<ParameterDefinition> getParameterDefinitions() {
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property == null) {
            return Collections.emptyList();
        }
        List<ParameterDefinition> definitions = property.getParameterDefinitions();
        Object submitted = getFormErrors().getAttachment();
        if (!(submitted instanceof List)) {
            // D-60: a refused build submission redirects here with its values as p.<name>.
            return RequestRunPrefill.apply(definitions,
                    org.kohsuke.stapler.Stapler.getCurrentRequest2());
        }
        Map<String, ParameterValue> byName = new LinkedHashMap<>();
        for (Object value : (List<?>) submitted) {
            if (value instanceof ParameterValue) {
                byName.put(((ParameterValue) value).getName(), (ParameterValue) value);
            }
        }
        List<ParameterDefinition> refilled = new ArrayList<>(definitions.size());
        for (ParameterDefinition definition : definitions) {
            ParameterValue value = byName.get(definition.getName());
            ParameterDefinition shown = definition;
            if (value != null && !value.isSensitive() && !ParameterDisplay.isFile(value)
                    && !(value.getValue() instanceof Secret)) {
                ParameterValue displayable = displayable(value);
                try {
                    shown = displayable == null ? definition : definition.copyWithDefaultValue(displayable);
                } catch (RuntimeException e) {
                    shown = definition; // a definition that cannot copy keeps its own default
                }
            }
            refilled.add(shown == null ? definition : shown);
        }
        return refilled;
    }

    /**
     * D-72b: {@code value} as the refused form may show it again. A string value holding a
     * character XML cannot store comes back with that character as U+FFFD
     * ({@link FormErrors#displayable}); another type of value holding one is not shown again
     * ({@code null}: the definition keeps its default). The page never carries such a character.
     */
    @CheckForNull
    private static ParameterValue displayable(ParameterValue value) {
        String text = ParameterDisplay.storedText(value);
        if (XmlChars.isStorable(text)) {
            return value;
        }
        if (value instanceof StringParameterValue && text != null) {
            return new StringParameterValue(value.getName(), FormErrors.displayable(text));
        }
        return null;
    }

    /** D-60: whether the form shows values carried from a refused build submission. */
    public boolean isPrefilled() {
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        return property != null && RequestRunPrefill.isPrefilled(property.getParameterDefinitions(),
                org.kohsuke.stapler.Stapler.getCurrentRequest2());
    }

    /**
     * The names of the job's parameters whose values the form never fills in, so the user provides
     * them again (D-72, D-60): file parameters ({@link RequestRunPrefill#isFileDefinition}) and
     * password parameters. Shown on a refused submission, on a refused direct build (D-60; files
     * are not carried, issue #10) and on a rerun that continues here.
     */
    public List<String> getReenterParameterNames() {
        List<String> names = new ArrayList<>();
        for (ParameterDefinition definition : definitions()) {
            if (RequestRunPrefill.isFileDefinition(definition)
                    || definition instanceof PasswordParameterDefinition) {
                names.add(definition.getName());
            }
        }
        return names;
    }

    /** The names of the job's file parameters ({@link RequestRunPrefill#isFileDefinition}). */
    public List<String> getFileParameterNames() {
        List<String> names = new ArrayList<>();
        for (ParameterDefinition definition : definitions()) {
            if (RequestRunPrefill.isFileDefinition(definition)) {
                names.add(definition.getName());
            }
        }
        return names;
    }

    private List<ParameterDefinition> definitions() {
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        return property == null ? Collections.emptyList() : property.getParameterDefinitions();
    }

    /**
     * D-72, D-72a (SPEC item 11): the id of the incident whose rerun continues on this form, or
     * {@code null}. On a GET it comes from {@value RequestRunPrefill#FROM_RERUN}{@code =<id>}; on a
     * refused submission it is the reference {@link #doSubmit} validated (the hidden field of the
     * same name, or the action's query string when the body was over the size cap and never
     * read), and nothing is read from the body here. Either way the id counts only when
     * {@link IncidentService#linkableIncident} accepts it (an existing incident of this job, and a
     * viewer holding {@code BatchControl/ViewHistory} who may request a run of the job, the rights
     * of the rerun itself), the same rule {@link #doSubmit} links the request by; anything else is
     * ignored, so a crafted link shows no notice and reveals nothing. The form then says which
     * values must be provided again and that the request will be linked to the incident, and
     * carries the id back in the hidden field; the values that could be recovered arrive as
     * {@code p.<name>} like a refused build's (D-60).
     */
    @CheckForNull
    public String getRerunIncidentId() {
        StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        if (req == null) {
            return null;
        }
        String reference;
        if (getFormErrors().isPresent()) {
            // A refusal: the reference doSubmit validated (on a body over the size cap, from the
            // action's query string alone). Checked again in case the incident went away since.
            Object validated = req.getAttribute(RERUN_ATTRIBUTE);
            reference = validated instanceof String ? (String) validated : null;
        } else if ("GET".equals(req.getMethod())) {
            reference = req.getParameter(RequestRunPrefill.FROM_RERUN);
        } else {
            reference = null;
        }
        return IncidentService.get().linkableIncident(reference, job);
    }

    /**
     * The reason the form starts with on a rerun that continues here
     * ({@link #getRerunIncidentId()}): the reason the rerun would have generated, editable;
     * otherwise empty. No argument on purpose: Stapler would bind a one-argument getter to a URL.
     */
    public String getRerunReason() {
        String incidentId = getRerunIncidentId();
        if (incidentId == null) {
            return "";
        }
        Incident incident;
        try {
            incident = IncidentService.get().load(incidentId);
        } catch (RuntimeException e) {
            return ""; // unreadable since it was validated: the user writes the reason
        }
        return incident == null ? "" : "Rerun requested from incident " + incidentId
                + " (failed run " + incident.getRunId() + ")";
    }

    /**
     * D-66, D-72a: the query string of the request form's action, with its {@code ?}, or empty:
     * {@code dialog=true} for the dialog form ({@link Dialogs#fromDialogQuery}) and
     * {@value RequestRunPrefill#FROM_RERUN}{@code =<id>} when the form continues an incident rerun
     * ({@link #getRerunIncidentId()}, already validated). Both travel in the query string as well
     * as in hidden fields so that a submission refused before its body is read (over the size cap)
     * is still answered with the right view and the incident reference; {@link #doSubmit} reads
     * the hidden field first and validates the reference again wherever it comes from.
     */
    public String submitQuery(boolean dialog) {
        List<String> parts = new ArrayList<>(2);
        if (dialog) {
            parts.add(Dialogs.PARAMETER + "=true");
        }
        String rerunIncidentId = getRerunIncidentId();
        if (rerunIncidentId != null) {
            parts.add(RequestRunPrefill.FROM_RERUN + "=" + Util.rawEncode(rerunIncidentId));
        }
        return parts.isEmpty() ? "" : "?" + String.join("&", parts);
    }

    /** The refusal of the last submission on this request, or an empty one (DEF-09). */
    public FormErrors getFormErrors() {
        return FormErrors.current(FORM);
    }

    /** Approver candidates: global approver list ∩ job-level restriction (if configured). */
    public List<String> getApproverOptions() {
        return ApproverOptions.forJob(job);
    }

    // ---------------------------------------------------------------- submission

    /**
     * D-58b (3): whether this page offers "Mark as reviewed": the job is in the "changed under a
     * grant" state (itself or through an ancestor, {@link GrantService#isChangedUnderGrant}) and
     * the viewer holds Item/Configure on it. {@link #doMarkReviewed} re-checks, and
     * {@link GrantService#markReviewed} refuses a user whose Configure comes from a grant.
     */
    public boolean isShowMarkReviewed() {
        // S-29-08: nothing is undone while change control is off, so the block would be untrue.
        // S-29-03: offered only to a user who may review it, Configure (or Administer) held
        // natively, as GrantService#markReviewed demands; a grant-only holder sees no button.
        if (!BatchControlGlobalConfiguration.get().isChangeControlEnabled()) {
            return false;
        }
        org.springframework.security.core.Authentication auth = Jenkins.getAuthentication2();
        boolean mayReview = GrantLayer.hasPermissionWithoutGrants(job, auth, Item.CONFIGURE)
                || GrantLayer.hasPermissionWithoutGrants(Jenkins.get(), auth, Jenkins.ADMINISTER);
        return mayReview && GrantService.get().isChangedUnderGrant(job);
    }

    /** D-58c: the runs of this job replayed under a permission window, for the review section. */
    public List<ReplayedRuns.Row> getMarkedRuns() {
        return ReplayedRuns.of(job);
    }

    /**
     * POST {@code markReviewed} (D-58b (3)): the deliberate review of this job after changes made
     * under a permission window. {@link GrantService#markReviewed} refuses a user whose Configure
     * comes from a grant and writes the {@code GUARD_REVIEWED} record; this page only asks for
     * Item/Configure first. Redirects back to this page with a short confirmation.
     */
    @RequirePOST
    public HttpResponse doMarkReviewed() {
        job.checkPermission(Item.CONFIGURE);
        GrantService.get().markReviewed(job);
        return HttpResponses.redirectTo(".?reviewed=1");
    }

    /** Whether the page shows the "marked as reviewed" confirmation (constant text only). */
    public boolean isReviewedNotice() {
        // S-29-08: the parameter alone is not trusted; the notice only states what is true now.
        // GET only: the redirect of doMarkReviewed. On a refused submission (a POST) reading a
        // parameter would parse a multipart body, which a body over the D-72 cap must not be.
        StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        return req != null && "GET".equals(req.getMethod()) && "1".equals(req.getParameter("reviewed"))
                && !GrantService.get().isChangedUnderGrant(job);
    }

    /**
     * POST {@code submit} — creates the run request and redirects to its detail page at
     * {@code /batch-control/requests/<id>/}. A refused submission re-renders the form with HTTP 400,
     * the message next to the field it concerns and the user's input kept (e2e-03 DEF-09).
     *
     * <p>D-72: the form posts {@code multipart/form-data}, so file parameters are uploaded with it,
     * and the request keeps the submitted values with their types ({@link RunRequestService#create(Job,
     * List, Map, String, List, String) create}). Because requesting a run does not require
     * {@code Item/Build} (D-38a), the size is checked in two stages (D-74 (2),
     * {@link RequestBodyLimit}), each answered with HTTP 413 and the empty form, nothing created
     * or kept:
     * <ol>
     *   <li>right after the permission checks and before this endpoint reads the body, the
     *       declared {@code Content-Length} ({@link RequestBodyLimit#exceeds}), an early filter
     *       that a body without a declared length passes;</li>
     *   <li>before anything is stored, what the request would keep
     *       ({@link RequestBodyLimit#checkKept}, in {@link RunRequestService#create(Job, List, Map,
     *       String, List, String) create}): the values plus the uploaded parts each was created
     *       from, measured here ({@link RequestBodyLimit#uploadedSize}) before its
     *       {@code createValue}, since creating a value may consume its part.</li>
     * </ol>
     * (Core's own dispatch to this URL may already have parsed a multipart body:
     * {@code Job#getDynamic} builds the job's widgets and {@code HistoryWidget} reads a paging
     * parameter. That happens for every URL under a job, before any plugin code, and is bounded
     * only by Stapler's {@code org.kohsuke.stapler.RequestImpl.FILEUPLOAD_MAX_*} properties.)
     * When the form's own
     * checks refuse a submission, the temporary files of its file values are disposed of here
     * ({@link ParameterFiles}); once the values are handed to the service, it disposes of them on
     * its own refusals. The re-rendered form never shows a password or a file again.
     *
     * <p>D-72a (SPEC item 11): a submission from the form an incident rerun fell back to carries
     * the incident id in the field {@value RequestRunPrefill#FROM_RERUN}. It is read after the
     * permission and size checks, passed through {@link IncidentService#linkableIncident} and only
     * the id that check returns goes to {@link RunRequestService#create(Job, List, Map, String,
     * List, String) create}, which links the request so that a successful run records
     * {@code resolvedByRunId}; an invalid reference is ignored. A refused submission keeps the
     * validated reference on the re-rendered form.
     *
     * <p>N-01: {@link #parseParameters} is inside the {@code try} on purpose. A parameter
     * definition rejects a bad value by throwing {@link IllegalArgumentException} — a choice
     * parameter given a value outside its choices, for instance — and that is the same class the
     * service's own validation throws. Parsing outside the {@code try} turned user-supplied
     * input into an uncaught exception and an HTTP 500 "Oops!" page while an empty reason
     * correctly answered 400; both are user input and both belong in the same 400 channel, now
     * the re-rendered form.
     *
     * <p>security-08 S-10: a scripted submission with no {@code json} field used to store an
     * empty parameter map, so the later approved build ran with whatever defaults the job had at
     * build time rather than the values the approver saw. {@link #parseRawParameters} now reads
     * each defined parameter straight from the request the way core's
     * {@code ParametersDefinitionProperty#_doBuild} / {@code buildWithParameters} do, so the
     * stored values are complete either way.
     *
     * <p>D-72b: a parameter name submitted more than once in the {@code json} field is refused
     * here, before the service sees any value, as a field error below that parameter
     * ({@link #parameterField}); on the raw channel each definition reads its own field, and a
     * definition that refuses a repeated field is reported the same way. The service's own
     * refusals are filed by {@link #fileServiceRefusal}: a message about one of the job's
     * parameters below it, the reason's next to the reason, and a failed save
     * ({@link StoreWriteException}) above the form, always as the re-rendered form (HTTP 400),
     * never an error page. A request over the kept-size cap ({@link RequestTooLargeException}) is
     * answered with HTTP 413, like an oversized body.
     */
    @RequirePOST
    public void doSubmit(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        job.checkPermission(Item.READ);
        // D-38a: Request is checked on the requested job (assigned there, on a folder or globally).
        job.checkPermission(BatchControlPermissions.REQUEST);
        // D-38a: Item/Build is not required to request a run; the approval decides.

        // D-72, D-74 (2) stage 1: the declared Content-Length, before this endpoint reads a single
        // form value; the body stays unread. An early filter only: a body without a declared
        // length (chunked) is judged by stage 2, the kept-size check in RunRequestService#create.
        if (RequestBodyLimit.exceeds(req)) {
            refuseOversizedBody(req, rsp);
            return;
        }

        // The rendered form posts a json blob (f:form) plus the raw fields; a script may post
        // the raw fields only. Both carry the same contract: reason, repeated approvers (D-37).
        JSONObject formData = req.getParameter("json") != null ? req.getSubmittedForm() : null;
        // R4-03: a multipart body without the blob has its text part read as UTF-8 (RepeatedField).
        String reason = Util.fixEmptyAndTrim(formData != null
                ? formData.optString("reason", "") : Util.fixNull(RepeatedField.text(req, "reason")));
        // D-72a (SPEC item 11): the incident reference the rerun fallback form carries back. The
        // request is linked only to what IncidentService#linkableIncident accepts; a missing,
        // malformed, unknown or foreign reference makes an unlinked request, never an error.
        String rerunIncident = IncidentService.get().linkableIncident(rerunReference(req, formData), job);

        // e2e-03 DEF-09: every refusal of the user's input is shown on the form, next to the
        // field, with the input kept (FormErrors), instead of a bare "Error" page.
        FormErrors errors = new FormErrors(FORM);
        List<ParameterValue> submitted = new ArrayList<>();
        // D-74 (2): the size of the uploaded parts each value was created from, by parameter name.
        Map<String, Long> uploaded = new LinkedHashMap<>();
        boolean handedOver = false;
        try {
            List<String> approvers = List.of();
            try {
                approvers = ApproverInput.read(req, formData);
            } catch (Failure e) {
                errors.field("approvers", e.getMessage());
            }
            try {
                if (formData == null) {
                    parseRawParameters(req, submitted, uploaded);
                } else {
                    parseParameters(req, formData, submitted, uploaded);
                }
            } catch (ParameterRefusal e) {
                // D-72b (1): a repeated name or a value the definition refused, below that parameter.
                errors.field(e.parameter == null ? "parameters" : parameterField(e.parameter), e.getMessage());
            } catch (IllegalArgumentException | Failure e) {
                errors.field("parameters", refusedValueMessage(e));
            }
            if (reason == null) {
                errors.field("reason", "Enter a reason: the approvers decide on it.");
            }
            if (approvers.isEmpty()) {
                errors.field("approvers", "Check at least one approver.");
            }
            if (errors.isEmpty()) {
                // From here the service owns the values' temporary files, on a refusal too.
                handedOver = true;
                try {
                    RunRequest request = RunRequestService.get().create(job, submitted, uploaded, reason,
                            approvers, rerunIncident);
                    rsp.sendRedirect2(req.getContextPath() + "/batch-control/requests/"
                            + Util.rawEncode(request.getId()) + "/");
                    return;
                } catch (IdenticalPendingRequestException e) {
                    // D-88 (#1.9): nothing was created, stored or notified. A browser is sent to the
                    // request the user already has, whose page says so; a script is answered 409
                    // naming it (ApprovalRequiredFailure#isScriptCaller, the R4-02 rule).
                    refuseIdentical(req, rsp, e);
                    return;
                } catch (RequestTooLargeException e) {
                    // security-37 S-37-01: what the request would keep is over the cap. The service
                    // stored nothing and disposed of the values' files: 413, like an oversized body.
                    refuseTooLarge(req, rsp, rerunIncident, e.getMessage(), Dialogs.refusalView(req, "index.jelly"));
                    return;
                } catch (IllegalArgumentException | IllegalStateException e) {
                    // D-72b: StoreWriteException (a failed save) is an IllegalStateException too.
                    fileServiceRefusal(errors, e);
                }
            }
        } finally {
            if (!handedOver) {
                // D-72: refused by this form before the service saw the values (or parsing
                // failed half-way): their uploaded files are not kept.
                ParameterFiles.dispose(submitted, "a refused run request form for job '" + job.getFullName() + "'");
            }
        }
        if (rerunIncident != null) {
            // D-72a: the re-rendered form keeps the validated reference (getRerunIncidentId).
            req.setAttribute(RERUN_ATTRIBUTE, rerunIncident);
        }
        // D-66: a refusal is shown where the form was, in the dialog or on this page.
        errors.attach(submitted).render(req, rsp, this, Dialogs.refusalView(req, "index.jelly"));
    }

    /**
     * D-88 (#1.9): answers a submission identical to a PENDING request of the same user. A browser
     * (or the request dialog's page script) is redirected to that request's page with
     * {@value RequestItem#IDENTICAL_PARAMETER}, which shows that the identical request was not
     * submitted again; a script gets HTTP 409 with the service's plain-text message, which names
     * the existing request's id.
     */
    private static void refuseIdentical(StaplerRequest2 req, StaplerResponse2 rsp, IdenticalPendingRequestException e)
            throws IOException {
        if (ApprovalRequiredFailure.isScriptCaller(req)) {
            rsp.setStatus(HttpServletResponse.SC_CONFLICT);
            rsp.setContentType("text/plain;charset=UTF-8");
            rsp.getWriter().println(e.getMessage());
            return;
        }
        rsp.sendRedirect2(req.getContextPath() + "/batch-control/requests/" + Util.rawEncode(e.getExistingRequestId())
                + "/?" + RequestItem.IDENTICAL_PARAMETER + "=true");
    }

    /**
     * D-72a: the raw incident reference of a submission, unvalidated. The hidden field
     * {@value RequestRunPrefill#FROM_RERUN} is the primary carrier: its value in the {@code json}
     * blob of the rendered form, else the plain field ({@code getParameter}, which for a multipart
     * body reads the body's part first), else the action's query string
     * ({@link RequestRunPrefill#rerunFromQuery}). Only ever passed to
     * {@link IncidentService#linkableIncident}, so a crafted value in any of them is ignored alike.
     */
    @CheckForNull
    private static String rerunReference(StaplerRequest2 req, @CheckForNull JSONObject formData) {
        if (formData != null && formData.opt(RequestRunPrefill.FROM_RERUN) instanceof String) {
            return formData.getString(RequestRunPrefill.FROM_RERUN);
        }
        String field = req.getParameter(RequestRunPrefill.FROM_RERUN);
        return field != null ? field : RequestRunPrefill.rerunFromQuery(req);
    }

    /**
     * D-72: answers a submission whose body is over the cap with HTTP 413 and the request form,
     * empty, with a message saying why: nothing of the body is read (the view never reads the
     * submitted fields, {@link FormErrors#withoutInput()}), so no request is created and no
     * uploaded file is stored. The dialog is recognised by its action's query string
     * ({@link Dialogs#fromDialogQuery}), never by a body field, and so is the incident reference of
     * a rerun that continues on the form (D-72a, {@link RequestRunPrefill#rerunFromQuery}), kept
     * only when {@link IncidentService#linkableIncident} accepts it.
     */
    private void refuseOversizedBody(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        // D-72a: the incident reference survives from the action's query string alone (the body
        // is not read), validated like the hidden field; a crafted value is ignored.
        String rerunIncident = IncidentService.get().linkableIncident(RequestRunPrefill.rerunFromQuery(req), job);
        refuseTooLarge(req, rsp, rerunIncident,
                "The request was not submitted: it is larger than the limit of "
                + sizeText(RequestBodyLimit.maxRequestBodyBytes())
                + " for a run request. Nothing was saved, and what"
                + " you entered could not be kept. Fill in the form again with smaller files, or"
                + " ask a Jenkins administrator to raise the limit.",
                Dialogs.fromDialogQuery(req) ? Dialogs.DIALOG_VIEW : "index.jelly");
    }

    /**
     * D-72, security-37 S-37-01: renders a size refusal as HTTP 413 with the request form, empty
     * ({@link FormErrors#withoutInput()}), {@code message} above it, in {@code view}, keeping the
     * already validated incident reference ({@code rerunIncident}, D-72a) when there is one.
     */
    private void refuseTooLarge(StaplerRequest2 req, StaplerResponse2 rsp, @CheckForNull String rerunIncident,
            String message, String view) throws IOException, ServletException {
        if (rerunIncident != null) {
            req.setAttribute(RERUN_ATTRIBUTE, rerunIncident);
        }
        new FormErrors(FORM).withoutInput().message(message)
                .render(req, rsp, this, view, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    }

    /**
     * D-72b: the {@link FormErrors} field of the parameter named {@code name}, whose message the
     * form shows right below that parameter ({@code _form.jelly}). Not a getter on purpose: a
     * one-argument getter would be bound to a URL.
     */
    public String parameterField(String name) {
        return PARAMETER_FIELD_PREFIX + name;
    }

    /**
     * T-06-103 (D-60, DEF-09): the run the form shows selected for the run parameter
     * {@code definition}, or {@code null} to render the definition's own view
     * ({@link RequestRunPrefill#selectedRunId}; core's view does not show a carried run). Not a
     * getter on purpose, like {@link #parameterField}.
     */
    @CheckForNull
    public String selectedRunId(ParameterDefinition definition) {
        return RequestRunPrefill.selectedRunId(definition);
    }

    /**
     * D-72b: files a refusal of {@link RunRequestService#create} on the form. A failed save
     * ({@link StoreWriteException}, nothing stored) goes above the form with its own message; a
     * message about one of the job's parameters ({@code Parameter '<name>' ...}) below that
     * parameter; any other message about parameters below the Parameters heading; the rest next
     * to the reason or the approvers when it is about them, else above the form.
     */
    private void fileServiceRefusal(FormErrors errors, RuntimeException e) {
        String text = e.getMessage();
        if (e instanceof StoreWriteException) {
            errors.message((text == null ? "The run request could not be saved; nothing was stored." : text)
                    + " Submit the form again; if it keeps failing, ask a Jenkins administrator to check the"
                    + " Jenkins log.");
            return;
        }
        String parameter = parameterNamedBy(text);
        if (parameter != null) {
            errors.field(parameterField(parameter), text);
        } else if (text != null && (text.startsWith("Parameter") || text.startsWith("A parameter"))) {
            errors.field("parameters", text);
        } else {
            errors.fromService(text, "reason", "reason", "approver", "approvers", "parameter", "parameters");
        }
    }

    /**
     * The name of the job's parameter a service message starts with ({@code Parameter '<name>'}),
     * the longest when several match, or {@code null}. Matched against the job's own definitions,
     * so a quote inside a name cannot confuse it.
     */
    @CheckForNull
    private String parameterNamedBy(@CheckForNull String text) {
        if (text == null || !text.startsWith("Parameter '")) {
            return null;
        }
        String found = null;
        for (ParameterDefinition definition : definitions()) {
            String name = definition.getName();
            if (text.startsWith("Parameter '" + name + "'")
                    && (found == null || name.length() > found.length())) {
                found = name;
            }
        }
        return found;
    }

    /** D-72b (1): the refusal of a parameter name submitted more than once. */
    private static String repeatedNameMessage(String name) {
        return "Parameter '" + name + "' was submitted more than once; give each parameter one value.";
    }

    /** N-01: the refusal of a value a parameter definition would not accept. */
    private static String refusedValueMessage(RuntimeException e) {
        return "A parameter value was refused" + (e.getMessage() == null ? "." : ": " + e.getMessage());
    }

    /**
     * D-72b: the refusal of one parameter's submitted value (its name repeated, or a value its
     * definition refused), filed below that parameter. Only ever thrown and caught inside
     * {@link #doSubmit}; carries no stack trace.
     */
    private static final class ParameterRefusal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String parameter;

        ParameterRefusal(String parameter, String message) {
            super(message, null, false, false);
            this.parameter = parameter;
        }
    }

    /** {@code bytes} as text for the size cap message: whole MB, KB or bytes, rounded down. */
    static String sizeText(long bytes) {
        long mega = 1024L * 1024L;
        if (bytes >= mega && bytes % mega == 0) {
            return bytes / mega + " MB";
        }
        if (bytes >= 1024L && bytes % 1024L == 0) {
            return bytes / 1024L + " KB";
        }
        return String.format(java.util.Locale.ROOT, "%,d bytes", bytes);
    }

    /**
     * Parses the {@code parameter} JSON array the same way core's
     * {@code ParametersDefinitionProperty._doBuild} does (each entry goes through the parameter
     * definition's {@code createValue}, file parameters reading their upload from the multipart
     * body) and adds the typed values to {@code submitted} (D-72: kept as they are, not flattened).
     *
     * <p>Throws {@link Failure} for a parameter name the job does not define. D-72b (1): a name
     * that occurs a second time is refused before its second value is created, and a
     * definition's own {@link IllegalArgumentException} (or {@link Failure}) for a value it
     * refuses is reported for that parameter; both as {@link ParameterRefusal}, which the caller
     * turns into the same 400 as every other rejected submission (N-01). The values created
     * before the refusal are in {@code submitted}, so the caller disposes of their files.
     *
     * <p>D-74 (2): before each value is created, the size of the uploaded parts its entry can name
     * ({@link RequestBodyLimit#partNames}) is recorded in {@code uploaded} under the parameter's
     * name, for the kept-size check. It is measured first because creating a value may consume
     * its part (a stashed file value deletes the part it copied).
     *
     * <p>#40: a parameter the job defines that the {@code parameter} array leaves out (or the
     * whole array, when the blob has none) is added as its
     * {@link ParameterDefinition#getDefaultParameterValue() default} at request time, the same
     * fallback {@link #parseRawParameters} uses, so every submission form stores a value for
     * every parameter. No uploaded size is recorded for such a value: it was not uploaded.
     */
    private void parseParameters(StaplerRequest2 req, JSONObject formData, List<ParameterValue> submitted,
                                 Map<String, Long> uploaded) {
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property == null) {
            return;
        }
        Object parameter = formData.opt("parameter");
        Set<String> seen = new HashSet<>();
        for (Object entry : parameter == null ? List.of() : JSONArray.fromObject(parameter)) {
            if (!(entry instanceof JSONObject)) {
                continue;
            }
            JSONObject jsonEntry = (JSONObject) entry;
            String name = jsonEntry.optString("name", null);
            if (name == null) {
                continue;
            }
            ParameterDefinition definition = property.getParameterDefinition(name);
            if (definition == null) {
                throw new Failure("No such parameter definition: " + name);
            }
            if (!seen.add(name)) {
                throw new ParameterRefusal(name, repeatedNameMessage(name));
            }
            uploaded.put(name, RequestBodyLimit.uploadedSize(req, RequestBodyLimit.partNames(jsonEntry)));
            ParameterValue value;
            try {
                value = definition.createValue(req, jsonEntry);
            } catch (IllegalArgumentException | Failure e) {
                throw new ParameterRefusal(name, refusedValueMessage(e));
            }
            if (value != null) {
                submitted.add(value);
            }
        }
        // #40 (security-08 S-10): a parameter the blob leaves out is stored as the job's default
        // now, explicitly, as on the raw channel, so the approver sees every value and the build
        // runs with them even if the defaults change before it starts.
        for (ParameterDefinition definition : property.getParameterDefinitions()) {
            if (!seen.contains(definition.getName())) {
                ParameterValue value = definition.getDefaultParameterValue();
                if (value != null) {
                    submitted.add(value);
                }
            }
        }
    }

    /**
     * Parses parameter values for a scripted submission that carries no {@code json} form field
     * (security-08 S-10). Each of the job's defined parameters reads its own raw request field
     * through {@link ParameterDefinition#createValue(StaplerRequest2)} — the same call core's
     * {@code buildWithParameters} makes — falling back explicitly to
     * {@link ParameterDefinition#getDefaultParameterValue()} when the definition itself returns
     * {@code null} for a missing field, so the stored values are complete rather than empty. A
     * definition's own {@link IllegalArgumentException} for a bad value is reported for that
     * parameter ({@link ParameterRefusal}, N-01: caught by the caller's {@code try}).
     *
     * <p>D-72b (1): each definition yields at most one value here, so no name can be stored twice.
     * Whether a field repeated in the request is acceptable is the definition's call, as in core's
     * {@code buildWithParameters} (core's own simple parameter types refuse it); when it refuses
     * a repeated field, the message says that the parameter was submitted more than once.
     *
     * <p>D-74 (2): before each value is created, the size of the uploaded part named after the
     * parameter is recorded in {@code uploaded}, as in {@link #parseParameters}.
     */
    private void parseRawParameters(StaplerRequest2 req, List<ParameterValue> submitted,
                                    Map<String, Long> uploaded) {
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property == null) {
            return;
        }
        for (ParameterDefinition definition : property.getParameterDefinitions()) {
            String name = definition.getName();
            uploaded.put(name, RequestBodyLimit.uploadedSize(req, Collections.singletonList(name)));
            ParameterValue value;
            try {
                value = definition instanceof SimpleParameterDefinition && RepeatedField.isMultipart(req)
                        ? createSimpleValue((SimpleParameterDefinition) definition, req)
                        : definition.createValue(req);
            } catch (IllegalArgumentException | Failure e) {
                String[] raw = req.getParameterValues(name);
                throw new ParameterRefusal(name, raw != null && raw.length > 1
                        ? repeatedNameMessage(name) : refusedValueMessage(e));
            }
            if (value == null) {
                value = definition.getDefaultParameterValue();
            }
            if (value != null) {
                submitted.add(value);
            }
        }
    }
    /**
     * R4-03: what core's {@code SimpleParameterDefinition#createValue(StaplerRequest2)} does (one
     * value: {@code createValue(String)}; none: the default; several: refused) for a multipart
     * body, with the field's part read as UTF-8 ({@link RepeatedField#parameterValues}) instead of
     * Stapler's ISO-8859-1 reading, so a raw string, text, choice or password value keeps its
     * non-ASCII characters. A urlencoded body keeps core's own call.
     */
    @CheckForNull
    private static ParameterValue createSimpleValue(SimpleParameterDefinition definition, StaplerRequest2 req) {
        String[] values = RepeatedField.parameterValues(req, definition.getName());
        if (values == null) {
            return definition.getDefaultParameterValue();
        }
        if (values.length != 1) {
            throw new IllegalArgumentException("Illegal number of parameter values for " + definition.getName()
                    + ": " + values.length);
        }
        return definition.createValue(values[0]);
    }
}
