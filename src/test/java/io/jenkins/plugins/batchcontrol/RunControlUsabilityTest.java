package io.jenkins.plugins.batchcontrol;

import hudson.cli.CLICommandInvoker;
import hudson.model.Descriptor;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.requestAndApprove;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run control as its users meet it (SPEC 6, 6a and the section 6 usability line). Matrix rows
 * T-06-66 (e2e-03 DEF-14, note 128), T-06-67 (DEF-15, note 129), T-06-68 (DEF-16, note 130),
 * T-06-70 (DEF-25, note 131), T-06-110 (e2e-04 FD-16, note 366), T-06a-55 (DEF-21, note 132) and T-05-19 (DEF-12, note 133).
 *
 * <p>Users: {@code admin}; {@code u1} requester (Read, Item/Read, Item/Build, Request,
 * ViewHistory); {@code nb} requester without Item/Build (Read, Item/Read, Request, ViewHistory);
 * {@code reader} (Read, Item/Read); {@code a1} the approver.
 *
 * <p>Written from docs/SPEC.md, docs/reports/e2e-03.md and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class RunControlUsabilityTest {

    private static final Pattern APPROVAL = Pattern.compile("(?i)approv");
    private static final Pattern MANUAL = Pattern.compile("(?i)manual");
    private static final Pattern TIMER = Pattern.compile("(?i)block\\s*timer");
    private static final Pattern UPSTREAM = Pattern.compile("(?i)block\\s*upstream");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.VIEW_HISTORY).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.VIEW_HISTORY).everywhere().to("nb")
                .grant(Jenkins.READ, Item.READ).everywhere().to("reader")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-06-66 (DEF-14): a CLI {@code build} of an approval-required job is refused with a plain
     * message (why, and where to request), not "Unexpected exception" with a server stack trace;
     * the exit code is non-zero and nothing is queued.
     */
    @Test
    public void t_06_66_cliRefusalIsAPlainMessage() throws Exception {
        FreeStyleProject job = approvalRequired("cli-x");

        CLICommandInvoker.Result result = new CLICommandInvoker(j, "build").asUser("u1").invokeWithArgs("cli-x");
        String out = result.stdout() + "\n" + result.stderr();
        assertNotEquals(0, result.returnCode(), "the refused CLI build must exit non-zero");
        UsabilityFixtures.assertPlainRefusal("CLI build", out, APPROVAL);
        assertFalse(out.contains("hudson.model.Failure"), "the refusal must not print an exception class name: " + excerpt(out));
        assertTrue(out.toLowerCase(Locale.ROOT).contains("request"), "the refusal must say what to do instead (request a run): " + excerpt(out));
        assertBlocked(j, job, 1, 0);
    }

    /**
     * T-06-67 (DEF-15): the blockTimer / blockUpstream notice is shown on a job that does not
     * require approval to run — the scheduled job is exactly the case (SPEC 6, #21: whenever the
     * switch is on). Negative twin: the same job with both switches off shows no such notice.
     */
    @Test
    public void t_06_67_triggerLockNoticeWithoutApprovalRequirement() throws Exception {
        FreeStyleProject timer = lockOnly("lock-timer", true, false);
        FreeStyleProject upstream = lockOnly("lock-upstream", false, true);
        FreeStyleProject open = lockOnly("lock-open", false, false);

        String timerText = mainText(UsabilityFixtures.htmlPage(j, "reader", timer.getUrl()));
        assertTrue(TIMER.matcher(timerText).find(), "a job with blockTimer on and approvalRequired off must name blockTimer: " + excerpt(timerText));
        assertTrue(timerText.toLowerCase(Locale.ROOT).contains("configur"), "the notice must say how to clear it (the job configuration): "
                + excerpt(timerText));
        String upstreamText = mainText(UsabilityFixtures.htmlPage(j, "reader", upstream.getUrl()));
        assertTrue(UPSTREAM.matcher(upstreamText).find(), "a job with blockUpstream on and approvalRequired off must name blockUpstream: "
                + excerpt(upstreamText));
        String openText = mainText(UsabilityFixtures.htmlPage(j, "reader", open.getUrl()));
        assertFalse(TIMER.matcher(openText).find() || UPSTREAM.matcher(openText).find(),
                "negative twin: with both switches off no trigger-lock notice may be shown: " + excerpt(openText));
    }

    /**
     * T-06-68 (DEF-16): a Replay submitted on a build of an approval-required Pipeline job is
     * refused with a plain-words page naming approval and linking the request form for a user who
     * may open it, not the "Oops!" crash page; no build is queued; the job page carries the
     * approval notice.
     *
     * <p>Coordinator ruling (e2e-03 part 2, the SPEC section 6 usability line applied to controls
     * Batch Control does not own): workflow-cps contributes the Replay link and no other plugin can
     * hide it, so the link may stay visible (docs/LIMITATIONS.md items 40/41 describe the same
     * situation for other plugins' build links). Its absence is therefore not asserted; the refusal
     * and the notice are. Control: Replay is offered on an uncontrolled build.
     */
    @Test
    public void t_06_68_replayRefusalIsPlainAndLinksTheRequestForm() throws Exception {
        WorkflowJob controlled = pipeline("replay-x");
        WorkflowJob free = pipeline("replay-free");
        setBatchControl(controlled, new BatchControlJobProperty(true));

        HtmlPage freeBuild = UsabilityFixtures.htmlPage(j, "admin", free.getUrl() + "1/");
        assertTrue(UsabilityFixtures.hasLinkTo(j, freeBuild, free.getUrl() + "1/replay"),
                "control: Replay is offered on an uncontrolled Pipeline build; anchors were " + UsabilityFixtures.resolvedHrefs(freeBuild));

        JenkinsRule.WebClient wc = UsabilityFixtures.client(j, "admin");
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("mainScript", "echo 'replayed'"));
        params.add(new NameValuePair("json", "{\"mainScript\":\"echo 'replayed'\"}"));
        WebRequest request = new WebRequest(wc.createCrumbedUrl(controlled.getUrl() + "1/replay/run"), HttpMethod.POST);
        request.setRequestParameters(params);
        Page answer = wc.getPage(request);
        UsabilityFixtures.assertPlainRefusal("Replay of an approval-required job", UsabilityFixtures.text(answer), APPROVAL);
        assertRefusalLinksRequestForm("Replay refusal as admin", answer, controlled.getUrl());
        assertBlocked(j, controlled, 2, 1);

        assertApprovalNotice(UsabilityFixtures.htmlPage(j, "admin", controlled.getUrl()), controlled.getFullName());
    }

    /**
     * T-06-70 (DEF-25): on an approval-required job nobody is offered a build entry that can never
     * succeed and that a plugin can remove: no "Build Now" caption, no "Rebuild Last", and on its
     * builds no Rebuild — for the requester and for the administrator. Core's own build entry
     * (renamed "Direct Build (needs approval)") stays by coordinator ruling and is pinned, with its
     * explained refusal, by T-UI-16/18.
     * Request Run stays. Control: the same plugins offer Rebuild and Retry on an uncontrolled job,
     * so the row measures the entries, not their absence from the instance.
     *
     * <p>Coordinator ruling (e2e-03 part 2): naginator contributes its Retry link itself and no
     * other plugin can hide it (docs/LIMITATIONS.md item 41), so Retry may stay visible. Instead,
     * a click on it is refused with a plain-words page naming approval and linking the request form
     * for the requester (no "Oops!", no generic toast), nothing is queued, and the job page carries
     * the approval notice.
     */
    @Test
    public void t_06_70_noNeverSucceedingBuildEntriesOnApprovalRequiredJobs() throws Exception {
        FreeStyleProject free = uncontrolled(j.createFreeStyleProject("offer-free"));
        free.getBuildersList().add(new FailureBuilder());
        BatchControlFixtures.activateAsAdmin(free);
        j.assertBuildStatus(Result.FAILURE, free.scheduleBuild2(0));
        j.waitUntilNoActivity();

        FreeStyleProject job = approvalRequired("offer-x");
        job.getBuildersList().add(new FailureBuilder());
        requestAndApprove(job);
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), "fixture: the approved run must have executed once");
        assertEquals(Result.FAILURE, job.getBuildByNumber(1).getResult(), "fixture: the approved run fails, so Retry would apply");

        HtmlPage freeBuild = UsabilityFixtures.htmlPage(j, "u1", free.getUrl() + "1/");
        assertTrue(UsabilityFixtures.hasLinkTo(j, freeBuild, free.getUrl() + "1/rebuild"),
                "control: the rebuild plugin offers Rebuild on an uncontrolled build; anchors were " + UsabilityFixtures.resolvedHrefs(freeBuild));
        assertTrue(UsabilityFixtures.hasLinkTo(j, freeBuild, free.getUrl() + "1/retry"),
                "control: naginator offers Retry on a failed uncontrolled build; anchors were " + UsabilityFixtures.resolvedHrefs(freeBuild));
        assertTrue(UsabilityFixtures.hasLinkTo(j, UsabilityFixtures.htmlPage(j, "u1", free.getUrl()), free.getUrl() + "build"),
                "control: core's build entry is offered on an uncontrolled job");

        for (String user : new String[] {"u1", "admin"}) {
            HtmlPage jobPage = UsabilityFixtures.htmlPage(j, user, job.getUrl());
            String text = jobPage.asNormalizedText();
            // Core's build entry ("Direct Build (needs approval)") stays by coordinator ruling: core
            // draws it for every Item/Build holder; T-UI-16/18 pin it and its explained refusal.
            assertFalse(text.contains("Build Now") || text.contains("Rebuild Last"),
                    user + ": no never-succeeding build entry may be shown: " + excerpt(text));
            for (String href : UsabilityFixtures.resolvedHrefs(jobPage)) {
                assertFalse(href.endsWith("/rebuild"), user + ": no Rebuild entry may be offered on the job page: " + href);
            }
            assertApprovalNotice(jobPage, user + " on " + job.getFullName());
            HtmlPage buildPage = UsabilityFixtures.htmlPage(j, user, job.getUrl() + "1/");
            assertFalse(UsabilityFixtures.hasLinkTo(j, buildPage, job.getUrl() + "1/rebuild"),
                    user + ": Rebuild must not be offered on a build of an approval-required job");
            // naginator's Retry may stay visible (ruling above); its click is asserted below
        }
        HtmlPage requester = UsabilityFixtures.htmlPage(j, "u1", job.getUrl());
        assertFalse(UsabilityFixtures.anchorsCaptioned(requester, Pattern.compile("Request Run")).isEmpty(),
                "Request Run must still be offered to the requester (SPEC 6)");

        Page retry = PluginInteractionFixtures.post(j, "u1", job.getUrl() + "1/retry/");
        UsabilityFixtures.assertPlainRefusal("naginator Retry of an approved run", UsabilityFixtures.text(retry), APPROVAL);
        assertRefusalLinksRequestForm("naginator Retry refusal as u1", retry, job.getUrl());
        assertBlocked(j, job, 2, 1);
    }

    /**
     * T-06a-55 (DEF-21): the help of blockTimer and blockUpstream does not contradict activation
     * approval (SPEC 6a, D-46): it says that an unattended run also needs the job to be activated,
     * and does not say that turning the switch off brings the job into service.
     */
    @Test
    public void t_06a_55_triggerSwitchHelpNamesActivation() throws Exception {
        Descriptor<?> descriptor = j.jenkins.getDescriptorOrDie(BatchControlJobProperty.class);
        for (String field : new String[] {"blockTimer", "blockUpstream"}) {
            // Descriptor#doHelp serves the field's help (the URL the configure page's "?" opens)
            String help = "descriptorByName/" + descriptor.getId() + "/help/" + field;
            Page page = UsabilityFixtures.get(j, UsabilityFixtures.clientNoJs(j, "admin"), help);
            assertEquals(200, page.getWebResponse().getStatusCode(), "the help of " + field + " must be served at " + help);
            String text = page.getWebResponse().getContentAsString();
            assertTrue(text.toLowerCase(Locale.ROOT).contains("activat"), "the help of " + field
                    + " must say that an unattended run also needs the job activated (SPEC 6a): " + excerpt(text));
            assertFalse(Pattern.compile("(?i)into service means turning").matcher(text).find(), "the help of " + field
                    + " must not say that turning the switch off brings the job into service: " + excerpt(text));
        }
    }

    /**
     * T-05-19 (DEF-12, rewritten for D-38a): a requester holding Request and Item/Read but no
     * Item/Build can submit a run request (D-38a), so on an approval-required job they are offered
     * Request Run on the job page, the run request form and the incident's rerun form (with
     * ViewHistory), like a Build holder (u1, control). Guard: a reader without Request is offered
     * none of them.
     */
    @Test
    public void t_05_19_requestRunIsOfferedWithoutItemBuild() throws Exception {
        FreeStyleProject failing = uncontrolled(j.createFreeStyleProject("offer-rerun"));
        failing.getBuildersList().add(new FailureBuilder());
        BatchControlFixtures.activateAsAdmin(failing);
        j.assertBuildStatus(Result.FAILURE, failing.scheduleBuild2(0));
        j.waitUntilNoActivity();
        failing.getBuildersList().clear();
        setBatchControl(failing, new BatchControlJobProperty(true));
        io.jenkins.plugins.batchcontrol.model.Incident incident = io.jenkins.plugins.batchcontrol.ops.IncidentService.get()
                .list(java.time.YearMonth.now(io.jenkins.plugins.batchcontrol.store.BatchClock.clock())).stream()
                .filter(i -> "offer-rerun#1".equals(i.getRunId())).findFirst().orElse(null);
        assertNotNull(incident, "fixture: the FAILURE must have opened an incident");
        assertFalse(failing.getACL().hasPermission2(hudson.model.User.getById("nb", true).impersonate2(), Item.BUILD),
                "premise: nb holds no Item/Build on the job");
        String submit = failing.getUrl() + "batch-control/submit";
        String rerun = "batch-control/incidents/" + incident.getId() + "/rerun";

        for (String userId : new String[] {"u1", "nb"}) {
            HtmlPage jobPage = UsabilityFixtures.htmlPage(j, userId, failing.getUrl());
            assertFalse(UsabilityFixtures.anchorsCaptioned(jobPage, Pattern.compile("Request Run")).isEmpty(),
                    userId + " (Request + Item/Read) must be offered Request Run on an approval-required job");
            assertFalse(UsabilityFixtures.formsEndingWith(UsabilityFixtures.htmlPage(j, userId, failing.getUrl() + "batch-control/"),
                    submit).isEmpty(), userId + " must be offered the run request form");
            assertFalse(UsabilityFixtures.formsEndingWith(UsabilityFixtures.htmlPage(j, userId,
                    "batch-control/incidents/" + incident.getId() + "/"), rerun).isEmpty(), userId + " must be offered the rerun form");
        }

        // guard: a reader without BatchControl/Request is offered none of it
        HtmlPage readerJob = UsabilityFixtures.htmlPage(j, "reader", failing.getUrl());
        assertEquals(200, readerJob.getWebResponse().getStatusCode(), "fixture: reader opens the job page");
        assertTrue(UsabilityFixtures.anchorsCaptioned(readerJob, Pattern.compile("Request Run")).isEmpty(),
                "a reader without BatchControl/Request must not be offered Request Run");
        assertFalse(UsabilityFixtures.hasLinkTo(j, readerJob, failing.getUrl() + "batch-control"),
                "no link to the run request screen may be offered to a reader without BatchControl/Request");
    }

    /**
     * T-06-110 (e2e-04 FD-16, LIMITATIONS 41 "the rebuild plugin's Rebuild can be hidden, and is",
     * note 366): the job's run-request pages offer no "Rebuild Last" either. A parameterised
     * approval-required job with one completed (approved) run; u1 opens its Request Run page
     * ({@code job/<name>/batch-control/}) and submits the form with an empty reason, which is refused
     * (no request created, the form shown again). Neither page carries an anchor captioned "Rebuild
     * Last" nor an anchor to a {@code rebuild} URL (the defect's link was
     * {@code lastCompletedBuild/rebuild/parameterized} in the side panel). Control: the rebuild plugin
     * offers "Rebuild Last" on an uncontrolled parameterised job with a completed build.
     */
    @Test
    public void t_06_110_noRebuildLastOnTheRefusedSubmitPage() throws Exception {
        FreeStyleProject free = uncontrolled(j.createFreeStyleProject("rl-free"));
        free.addProperty(new hudson.model.ParametersDefinitionProperty(new hudson.model.StringParameterDefinition("DATE", "2000-01-01")));
        BatchControlFixtures.activateAsAdmin(free);
        j.buildAndAssertSuccess(free);
        HtmlPage freePage = UsabilityFixtures.htmlPage(j, "u1", free.getUrl());
        assertFalse(UsabilityFixtures.anchorsCaptioned(freePage, Pattern.compile("Rebuild Last")).isEmpty(),
                "control: the rebuild plugin offers Rebuild Last on an uncontrolled job with a completed build: "
                        + UsabilityFixtures.resolvedHrefs(freePage));

        FreeStyleProject job = approvalRequired("rl-x");
        job.addProperty(new hudson.model.ParametersDefinitionProperty(new hudson.model.StringParameterDefinition("DATE", "2000-01-01")));
        requestAndApprove(job);
        j.waitUntilNoActivity();
        assertNotNull(job.getLastCompletedBuild(), "fixture: the approved run completed, so Rebuild Last would apply");

        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        org.htmlunit.html.HtmlForm form = TypedParameterFixtures.requestRunForm(j, wc, job);
        assertNoRebuildLast((HtmlPage) form.getPage(), "the Request Run page");

        java.util.Set<String> before = ApproverFormFixtures.runRequestIds();
        Page answer = TypedParameterFixtures.submit(wc, form, "", "a1");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "premise: the submission with an empty reason is refused");
        assertTrue(answer instanceof HtmlPage, "premise: the refusal is a page, got " + answer.getWebResponse().getContentType());
        HtmlPage refused = (HtmlPage) answer;
        assertFalse(UsabilityFixtures.formsEndingWith(refused, job.getUrl() + "batch-control/submit").isEmpty(),
                "premise: the refused-submit page shows the form again: " + excerpt(refused.asNormalizedText()));
        assertNoRebuildLast(refused, "the refused-submit page");
    }

    private static void assertNoRebuildLast(HtmlPage page, String where) throws Exception {
        assertTrue(UsabilityFixtures.anchorsCaptioned(page, Pattern.compile("Rebuild Last")).isEmpty(),
                where + ": Rebuild Last must not be offered on an approval-required job: " + excerpt(page.asNormalizedText()));
        for (String href : UsabilityFixtures.resolvedHrefs(page)) {
            assertFalse(href.contains("/rebuild"), where + ": no rebuild link may be offered: " + href);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The refusal is an HTML page (not a toast) linking the job's request form, which the viewer may open. */
    private void assertRefusalLinksRequestForm(String what, Page answer, String jobUrl) throws Exception {
        assertTrue(answer instanceof HtmlPage, what + ": the refusal must be a page the user reads, got "
                + answer.getWebResponse().getContentType() + ": " + excerpt(UsabilityFixtures.text(answer)));
        assertTrue(UsabilityFixtures.hasLinkTo(j, (HtmlPage) answer, jobUrl + "batch-control"), what
                + ": the refusal must link the request form " + jobUrl + "batch-control/; anchors were "
                + UsabilityFixtures.resolvedHrefs((HtmlPage) answer));
    }

    /** The job page's approval notice (T-06-56, note 116): a main-panel element naming a manual run and approval. */
    private static void assertApprovalNotice(HtmlPage page, String where) {
        org.htmlunit.html.DomElement main = page.getElementById("main-panel");
        boolean found = false;
        if (main != null) {
            for (org.htmlunit.html.DomElement element : main.getHtmlElementDescendants()) {
                String text = element.getTextContent();
                if (text != null && MANUAL.matcher(text).find() && APPROVAL.matcher(text).find()) {
                    found = true;
                    break;
                }
            }
        }
        assertTrue(found, where + ": the job page must carry the approval notice (manual runs need an approved request): "
                + excerpt(page.asNormalizedText()));
    }

    private FreeStyleProject approvalRequired(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        BatchControlFixtures.activate(job);
        return job;
    }

    /** approvalRequired off, the given switches, activated (as batch-cron in e2e-03). */
    private FreeStyleProject lockOnly(String name, boolean blockTimer, boolean blockUpstream) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        BatchControlJobProperty property = new BatchControlJobProperty(false);
        property.setBlockTimer(blockTimer);
        property.setBlockUpstream(blockUpstream);
        setBatchControl(job, property);
        BatchControlFixtures.activate(job);
        assertFalse(job.getProperty(BatchControlJobProperty.class).isApprovalRequired(), "fixture: approvalRequired off");
        return job;
    }

    /** A Pipeline job with one completed build, uncontrolled and activated. */
    private WorkflowJob pipeline(String name) throws Exception {
        WorkflowJob pipeline = uncontrolled(j.createProject(WorkflowJob.class, name));
        pipeline.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        BatchControlFixtures.activateAsAdmin(pipeline);
        j.buildAndAssertSuccess(pipeline);
        return pipeline;
    }

    private static String mainText(HtmlPage page) {
        org.htmlunit.html.DomElement main = page.getElementById("main-panel");
        return main == null ? page.asNormalizedText() : main.asNormalizedText();
    }
}
