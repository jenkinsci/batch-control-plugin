package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.PasswordParameterValue;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.file_parameters.StashedFileParameterDefinition;
import io.jenkins.plugins.file_parameters.StashedFileParameterValue;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.htmlunit.Page;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jvnet.hudson.test.JenkinsRule;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared steps of the D-72a rows (matrix note 263): failed runs that open incidents, the incident
 * "Request Rerun" POST, the job's Request Run form opened with an incident reference
 * ({@code job/<name>/batch-control/?...&fromRerun=<incident id>}, the rerun fallback of SPEC item
 * 11), and the notice markers {@code data-batch-control-notice} of the D-72 form surface.
 *
 * <p>The reference travels in the URL and, on the form, in a field named {@code fromRerun}. A
 * crafted reference is put into the submitted form with {@link #forceFromRerun}, so the server's
 * own re-validation at submission is what the rows measure, whatever the page did with the URL.
 *
 * <p>Written from docs/SPEC.md items 5 and 11, docs/DECISIONS.md D-72 and D-72a and the frozen
 * D-72 contract only (no src/main knowledge).
 */
final class RerunFallbackFixtures {

    static final String FIELD = "fromRerun";
    static final String SECRET = "fallback-s3cr3t-263-Qm4";
    static final String SECRET_DEFAULT = "fallback-d3fault-263-Hc8";
    static final String DATE = "2026-09-29";
    /** The stable substring of the rerun notice when the reference is linkable (ui-dev b7ce183). */
    static final String LINKED = "will be linked to incident";
    /** The sentence the notice carried before D-72a; it must be gone. */
    static final String NOT_LINKED = "is not linked to the incident";

    private RerunFallbackFixtures() {
        // utility class
    }

    /**
     * An activated Pipeline taken out of run control whose first run (stashed DATA, string DATE,
     * password TOKEN) unstashes DATA and fails; later runs unstash DATA and succeed. The job is
     * then made approval-required. A stashed file cannot be recovered after the build completes,
     * so "Request Rerun" on its incident falls back to the Request Run form (SPEC item 11).
     */
    static WorkflowJob failedStashPipeline(JenkinsRule j, String name, String marker) throws Exception {
        WorkflowJob job = uncontrolled(j.createProject(WorkflowJob.class, name));
        job.setDefinition(new CpsFlowDefinition(
                "node {\n  unstash 'DATA'\n}\nif (currentBuild.number == 1) {\n  error 'the batch failed'\n}\n", true));
        job.addProperty(new ParametersDefinitionProperty(new StashedFileParameterDefinition("DATA"),
                new StringParameterDefinition("DATE", "2000-01-01"),
                new PasswordParameterDefinition("TOKEN", Secret.fromString(SECRET_DEFAULT), "token")));
        BatchControlFixtures.activateAsAdmin(job);
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, new ParametersAction(
                new StashedFileParameterValue("DATA", TypedParameterFixtures.fileItem("report.bin",
                        TypedParameterFixtures.payload(marker, 1500))),
                new StringParameterValue("DATE", DATE),
                new PasswordParameterValue("TOKEN", SECRET))));
        j.waitUntilNoActivity();
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    /**
     * An activated Freestyle job taken out of run control with a string parameter DATE whose first
     * run fails (later runs succeed); then approval-required. Its incident is an ordinary incident
     * of the job, used where a row only needs a valid incident reference.
     */
    static FreeStyleProject failedFreestyle(JenkinsRule j, String name) throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject(name));
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("DATE", "2000-01-01")));
        job.getBuildersList().add(new TypedParameterFixtures.CaptureEnv(true, "DATE"));
        BatchControlFixtures.activateAsAdmin(job);
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, (Cause) null,
                new ParametersAction(new StringParameterValue("DATE", DATE))));
        j.waitUntilNoActivity();
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    /** An approval-required Freestyle job with a string parameter DATE and no runs. */
    static FreeStyleProject approvalRequired(JenkinsRule j, String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("DATE", "2000-01-01")));
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    static Incident incidentFor(String runId) {
        Incident incident = IncidentService.get().list(YearMonth.now(BatchClock.clock())).stream()
                .filter(i -> runId.equals(i.getRunId())).findFirst().orElse(null);
        assertNotNull(incident, "fixture: the failed run " + runId + " must have opened an incident");
        return incident;
    }

    static List<String> rerunIds(String incidentId) {
        Incident reloaded = IncidentService.get().load(incidentId);
        assertNotNull(reloaded, "fixture: incident " + incidentId + " must still load");
        return reloaded.getRerunRequestIds() == null ? List.of() : new ArrayList<>(reloaded.getRerunRequestIds());
    }

    /** The incident "Request Rerun" POST as {@code userId} (crumb, redirects not followed). */
    static WebResponse postRerun(JenkinsRule j, String userId, Incident incident) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("approvers", "a1"));
        params.add(new NameValuePair("approver", "a1"));
        params.add(new NameValuePair("reason", "rerun after the fix"));
        return ApproverFormFixtures.post(j, userId, "batch-control/incidents/" + incident.getId() + "/rerun", params);
    }

    /** The absolute target of the rerun's 302 (asserted to be a 302 with a Location). */
    static URL fallbackTarget(JenkinsRule j, Incident incident, WebResponse rerun) throws Exception {
        assertEquals(302, rerun.getStatusCode(), "fixture: an unrecoverable value must lead to the Request Run form: "
                + UsabilityFixtures.excerpt(rerun.getContentAsString()));
        String location = rerun.getResponseHeaderValue("Location");
        assertNotNull(location, "fixture: the 302 must carry a Location");
        return new URL(new URL(j.getURL(), "batch-control/incidents/" + incident.getId() + "/rerun"), location);
    }

    /** {@code job/<name>/batch-control/?p.<k>=<v>...&fromRerun=<reference>} (reference omitted when null). */
    static URL formUrl(JenkinsRule j, Job<?, ?> job, Map<String, String> prefill, String reference) throws Exception {
        StringBuilder sb = new StringBuilder(job.getUrl()).append("batch-control/");
        char sep = '?';
        for (Map.Entry<String, String> e : prefill.entrySet()) {
            sb.append(sep).append(enc("p." + e.getKey())).append('=').append(enc(e.getValue()));
            sep = '&';
        }
        if (reference != null) {
            sb.append(sep).append(FIELD).append('=').append(enc(reference));
        }
        return new URL(j.getURL(), sb.toString());
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Opens {@code url} in {@code wc} (status 200, HTML) and returns the job's Request Run form on it. */
    static HtmlForm openForm(JenkinsRule.WebClient wc, Job<?, ?> job, URL url) throws Exception {
        Page page = PageScriptFixtures.settled(wc.getPage(url));
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: " + url + " must open");
        assertTrue(page instanceof HtmlPage, "fixture: the Request Run page must be HTML");
        List<HtmlForm> forms = UsabilityFixtures.formsEndingWith((HtmlPage) page, job.getUrl() + "batch-control/submit");
        assertTrue(!forms.isEmpty(), "fixture: the page must carry the Request Run form; forms: "
                + UsabilityFixtures.formActions((HtmlPage) page));
        return forms.get(0);
    }

    /**
     * Makes the form submit {@code reference} as its incident reference: every {@code fromRerun}
     * input of the form gets the value, and a hidden one is added if the page rendered none. This is
     * what a crafted submission does; the server must re-validate it.
     */
    static void forceFromRerun(HtmlForm form, String reference) {
        List<DomElement> inputs = new ArrayList<>();
        for (DomElement e : form.getElementsByTagName("input")) {
            if (FIELD.equals(e.getAttribute("name"))) {
                inputs.add(e);
            }
        }
        if (inputs.isEmpty()) {
            HtmlElement hidden = (HtmlElement) form.getPage().createElement("input");
            hidden.setAttribute("type", "hidden");
            hidden.setAttribute("name", FIELD);
            form.appendChild(hidden);
            inputs.add(hidden);
        }
        for (DomElement e : inputs) {
            e.setAttribute("value", reference);
            if (e instanceof org.htmlunit.html.HtmlInput input) {
                input.setValue(reference);
            }
        }
    }

    /** The values of the form's {@code fromRerun} inputs as rendered (empty when it has none). */
    static List<String> fromRerunValues(HtmlForm form) {
        List<String> out = new ArrayList<>();
        for (DomElement e : form.getElementsByTagName("input")) {
            if (FIELD.equals(e.getAttribute("name"))) {
                out.add(e instanceof org.htmlunit.html.HtmlInput input ? input.getValue() : e.getAttribute("value"));
            }
        }
        return out;
    }

    /**
     * Adds {@code fromRerun=<reference>} to the query of the form's action (replacing any
     * {@code fromRerun} already there) and keeps everything else the page put in it, the crumb
     * included: what a crafted link to the submit endpoint does.
     */
    static void actionWithReference(HtmlForm form, Job<?, ?> job, String reference) throws Exception {
        String action = ((HtmlPage) form.getPage()).getFullyQualifiedUrl(form.getActionAttribute()).toExternalForm();
        int q = action.indexOf('?');
        String path = q < 0 ? action : action.substring(0, q);
        assertTrue(UsabilityFixtures.stripQueryAndSlash(path).endsWith(job.getUrl() + "batch-control/submit"),
                "fixture: the form must post to the job's submit endpoint, was " + action);
        List<String> query = new ArrayList<>();
        if (q >= 0) {
            for (String pair : action.substring(q + 1).split("&")) {
                if (!pair.isEmpty() && !pair.startsWith(FIELD + "=")) {
                    query.add(pair);
                }
            }
        }
        query.add(FIELD + "=" + enc(reference));
        form.setActionAttribute(path + "?" + String.join("&", query));
    }

    /**
     * Submits {@code form} (reason and approver a1) as a browser does and returns the id of the one
     * request the submission created; the submission must succeed (no error for an ignored
     * reference).
     */
    static String submitOne(JenkinsRule.WebClient wc, HtmlForm form, String what) throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        Page answer = TypedParameterFixtures.submit(wc, form, "rerun from the Request Run form", "a1");
        int code = answer.getWebResponse().getStatusCode();
        assertTrue(code < 400, what + ": the submission must be accepted (an invalid reference is ignored, never an error),"
                + " got HTTP " + code + ": " + UsabilityFixtures.excerpt(answer.getWebResponse().getContentAsString()));
        Set<String> after = ApproverFormFixtures.runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), what + ": exactly one request must be created, got " + after);
        return after.iterator().next();
    }

    /** The source of the incident's detail page as {@code userId} sees it (status 200 asserted). */
    static String incidentPage(JenkinsRule j, String userId, String incidentId) throws Exception {
        WebResponse page = ApproverFormFixtures.get(j, userId, "batch-control/incidents/" + incidentId + "/");
        assertEquals(200, page.getStatusCode(), "fixture: " + userId + " must open incident " + incidentId);
        return page.getContentAsString();
    }

    /** The elements of {@code page} marked {@code data-batch-control-notice="<kind>"}. */
    static List<DomElement> notices(HtmlPage page, String kind) {
        PageScriptFixtures.settled(page);
        return page.getByXPath("//*[@data-batch-control-notice='" + kind + "']");
    }

    /** The normalized text of all {@code kind} notices of {@code page} (empty when there is none). */
    static String noticeText(HtmlPage page, String kind) {
        StringBuilder sb = new StringBuilder();
        for (DomElement e : notices(page, kind)) {
            sb.append(e.asNormalizedText()).append('\n');
        }
        return sb.toString();
    }
}
