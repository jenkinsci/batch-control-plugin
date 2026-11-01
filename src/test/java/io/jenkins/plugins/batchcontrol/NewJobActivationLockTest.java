package io.jenkins.plugins.batchcontrol;

import hudson.cli.CLICommandInvoker;
import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import javaposse.jobdsl.plugin.ExecuteDslScripts;
import javaposse.jobdsl.plugin.GlobalJobDslSecurityConfiguration;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8, D-34: <em>creating a job must not activate it.</em> While run control is enabled a
 * newly created job starts with all three job-level switches on — {@code approvalRequired},
 * {@code blockTimer} and {@code blockUpstream} — so the act of creating it starts nothing: not a
 * person pressing Build, not its own cron schedule, and not an upstream job calling it. Bringing
 * the job into service means turning those switches off in the job's configuration, and that
 * change is itself change-controlled and recorded (SPEC items 8 and 9).
 *
 * <p>Why the two extra switches exist: D-31 made a new job start {@code approvalRequired=true},
 * but that refuses <em>human</em> causes only (SPEC item 6, D-25). A job created with a cron
 * inside a change window therefore kept running indefinitely after the window closed, which is
 * exactly the hole D-17 was written to close.
 *
 * <p>Matrix rows T-08-30 (the New Item form path), T-08-31 (REST {@code createItem} with a cron
 * in the config.xml — the behavioural core: the new job's timer does not fire), T-08-32 (CLI
 * {@code create-job}), T-08-33 (an upstream job cannot start the new job either), T-08-34 (the
 * falsifiability guard: with run control off nothing is locked), T-08-35 (a copy of a live cron
 * job starts locked, so copying a nightly job does not silently double its schedule) and
 * T-08-36 (a Job DSL seed run — the generator path D-34's rejected alternative worried about).
 *
 * <p>What this class deliberately does not repeat:
 * <ul>
 *   <li>the human path: that a manual Build of such a job is refused with guidance is
 *       T-08-19..24 and T-08-29 — those rows also stay red if an implementation of D-34 were to
 *       trade {@code approvalRequired} away for the two new switches;</li>
 *   <li>the way out: that turning the switches off really brings the job into service is
 *       T-08-26 and T-08-27 in {@link NewJobAutomationSafetyTest} — a lock with no key would not
 *       be a feature;</li>
 *   <li>computed child jobs: a multibranch branch job is excluded from this default (D-32,
 *       unchanged by D-34 — it has no configuration screen, so the switches could never be
 *       turned off), which T-10-07 measures in {@code RunRecordListenerTest};</li>
 *   <li>the record left by the unlocking configuration change: SPEC item 9, covered by
 *       T-09-01/02.</li>
 * </ul>
 *
 * <p>Written from docs/SPEC.md (items 6, 8, 9 and D-17, D-25, D-31, D-32, D-34),
 * docs/DECISIONS.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class NewJobActivationLockTest {

    /** A job that would run on its own schedule the moment it exists. */
    private static final String CRON_FREESTYLE_XML =
            "<?xml version='1.1' encoding='UTF-8'?><project>"
                    + "<description>a nightly batch job</description>"
                    + "<triggers><hudson.triggers.TimerTrigger><spec>0 3 * * *</spec>"
                    + "</hudson.triggers.TimerTrigger></triggers>"
                    + "<builders/><publishers/><buildWrappers/></project>";

    private static final String PLAIN_FREESTYLE_XML =
            "<?xml version='1.1' encoding='UTF-8'?><project>"
                    + "<description>created under run control</description>"
                    + "<builders/><publishers/><buildWrappers/></project>";

    private JenkinsRule j;

    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to("a1"));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-08-30 (D-34): the ordinary "New Item" form POST carries a mode and no configuration at
     * all. The job it creates must still start fully locked — the creation itself decides
     * nothing about whether the job may run.
     */
    @Test
    public void t_08_30_uiNewItemStartsLocked() throws Exception {
        JenkinsRule.WebClient admin = webClient().login("admin");
        WebRequest create = new WebRequest(admin.createCrumbedUrl("createItem"), HttpMethod.POST);
        create.setRequestParameters(Arrays.asList(
                new NameValuePair("name", "ui-new"),
                new NameValuePair("mode", FreeStyleProject.class.getName())));
        int code = admin.getPage(create).getWebResponse().getStatusCode();
        assertTrue(code < 400, "the administrator must be able to create a job, got HTTP " + code);

        assertLocked(job("ui-new"), "an administrator's ordinary New Item creation");
    }

    /**
     * T-08-31 (D-34, SPEC 6): the behavioural core of the decision. A job is created through the
     * REST {@code createItem} path with a cron trigger in its config.xml — the shape that used
     * to start running immediately and keep running forever, because {@code approvalRequired}
     * alone refuses human causes only. The timer firing must now be refused at queue entry, and
     * the stored switches must say why.
     */
    @Test
    public void t_08_31_newCronJobDoesNotRunFromItsOwnTimer() throws Exception {
        JenkinsRule.WebClient admin = webClient().login("admin");
        assertTrue(createFromXml(admin, "", "rest-nightly", CRON_FREESTYLE_XML) < 400,
                "the REST creation must succeed");
        FreeStyleProject created = job("rest-nightly");

        // matrix note 4: cron firing is reproduced by scheduling with a TimerTriggerCause.
        // An unattended cause is refused quietly (T-06-07 convention): no exception, no queue item.
        Future<FreeStyleBuild> firing =
                created.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        assertNull(firing, "D-34: creating a job must not activate it — the cron firing of a job that was "
                + "just created under run control must be refused at queue entry");
        assertBlockingBaseline(created);

        assertLocked(created, "a REST createItem carrying a cron trigger");
    }

    /**
     * T-08-32 (D-34): the CLI {@code create-job} path — the door automation outside HTTP uses —
     * produces the same locked job. The lock is not a property of the UI.
     */
    @Test
    public void t_08_32_cliCreateJobStartsLocked() throws Exception {
        CLICommandInvoker.Result result = new CLICommandInvoker(j, "create-job")
                .asUser("admin")
                .withStdin(new ByteArrayInputStream(
                        CRON_FREESTYLE_XML.getBytes(StandardCharsets.UTF_8)))
                .invokeWithArgs("cli-nightly");
        assertEquals(0, result.returnCode(), "create-job must succeed: " + result.stderr());

        assertLocked(job("cli-nightly"), "a CLI create-job");
    }

    /**
     * T-08-33 (D-34, SPEC 6 + D-16): the other automatic way into a job. An upstream job calls
     * the newly created job with a {@code build} step; because a new job starts
     * {@code blockUpstream=true} with no allow list, and an empty allow list means "block all"
     * (D-16), the downstream job must not run. The upstream job is created while run control is
     * off so that only the new job's own gating is measured.
     */
    @Test
    public void t_08_33_newJobIsNotStartedByAnUpstreamJob() throws Exception {
        setRunControl(false);
        WorkflowJob upstream = j.createProject(WorkflowJob.class, "caller");
        upstream.setDefinition(new CpsFlowDefinition(
                "build job: 'rest-downstream', wait: false", true));
        setRunControl(true);

        JenkinsRule.WebClient admin = webClient().login("admin");
        assertTrue(createFromXml(admin, "", "rest-downstream", PLAIN_FREESTYLE_XML) < 400,
                "the REST creation must succeed");
        FreeStyleProject created = job("rest-downstream");

        // the upstream run itself may end FAILURE when its build step is refused (matrix note 6);
        // this row measures the downstream job, so the upstream result is not pinned here.
        upstream.scheduleBuild2(0).get();
        j.waitUntilNoActivity();

        assertNull(created.getBuildByNumber(1), "D-34: an upstream job must not be able to start a job that was just created "
                + "under run control (blockUpstream starts on, and an unset allow list blocks all)");
        assertBlockingBaseline(created);

        assertLocked(created, "a REST createItem called by an upstream job");
    }

    /**
     * T-08-34 (D-34 negative, D-12): the falsifiability guard. With run control off, no creation
     * path may lock anything — a newly created cron job starts unlocked and runs on its
     * schedule. Without this row an implementation that simply stamps all three switches on
     * every new job satisfies T-08-30..33 and T-08-35/36 while breaking the promise that an
     * installed-but-disabled control changes nothing.
     */
    @Test
    @Tag("core")
    public void t_08_34_runControlOffLocksNothing() throws Exception {
        setRunControl(false);

        JenkinsRule.WebClient admin = webClient().login("admin");
        assertTrue(createFromXml(admin, "", "off-nightly", CRON_FREESTYLE_XML) < 400,
                "the REST creation must succeed");
        FreeStyleProject created = job("off-nightly");

        BatchControlJobProperty property = created.getProperty(BatchControlJobProperty.class);
        if (property != null) {
            assertFalse(property.isBlockTimer(), "with run control off a new job must not be locked out of its own schedule");
            assertFalse(property.isBlockUpstream(), "with run control off a new job must not be locked out of upstream calls");
            assertFalse(property.isApprovalRequired(), "with run control off a new job must not be put under approval (D-12, T-08-25)");
        }

        Future<FreeStyleBuild> firing =
                created.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        assertNotNull(firing, "with run control off the new job's cron firing must be admitted");
        j.assertBuildStatusSuccess(firing);
        j.waitUntilNoActivity();
        assertEquals(1, created.getBuilds().size(), "the timer run must be the job's build #1");
    }

    /**
     * T-08-35 (D-34): a copy is a new job, so the copy starts locked even though its source is a
     * live, unlocked cron job. Operationally this is the sharpest case: copying a nightly job to
     * adapt it must not silently double the nightly schedule. The source is created while run
     * control is off, so it is provably unlocked and must stay that way.
     */
    @Test
    public void t_08_35_copyOfALiveCronJobStartsLocked() throws Exception {
        setRunControl(false);
        JenkinsRule.WebClient admin = webClient().login("admin");
        assertTrue(createFromXml(admin, "", "cron-source", CRON_FREESTYLE_XML) < 400,
                "the source creation must succeed");
        FreeStyleProject source = job("cron-source");
        assertUnlocked(source, "test precondition: the source job must be unlocked");
        setRunControl(true);

        WebRequest copy = new WebRequest(admin.createCrumbedUrl("createItem"), HttpMethod.POST);
        copy.setRequestParameters(Arrays.asList(
                new NameValuePair("name", "cron-copy"),
                new NameValuePair("mode", "copy"),
                new NameValuePair("from", "cron-source")));
        int code = admin.getPage(copy).getWebResponse().getStatusCode();
        assertTrue(code < 400, "copying the job must succeed, got HTTP " + code);

        FreeStyleProject created = job("cron-copy");
        assertLocked(created, "a copy of a live cron job");

        Future<FreeStyleBuild> firing =
                created.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        assertNull(firing, "D-34: the copy's inherited cron must not fire until somebody brings the copy "
                + "into service");
        assertBlockingBaseline(created);

        assertUnlocked(source, "copying a job must not change the source job");
    }

    /**
     * T-08-36 (D-34): the generator path. A Job DSL seed run creates a cron job; it starts
     * locked like every other new job. This is the friction D-34's rejected alternative ① was
     * weighed against (making new jobs Jenkins-disabled instead) — the outcome is that generated
     * jobs are created but not started, and bringing them into service is a recorded
     * configuration change. The seed job itself is created while run control is off so that its
     * own build is not part of what this row measures.
     */
    @Test
    public void t_08_36_jobDslGeneratedCronJobStartsLocked() throws Exception {
        GlobalConfiguration.all().get(GlobalJobDslSecurityConfiguration.class)
                .setUseScriptSecurity(false);

        setRunControl(false);
        FreeStyleProject seed = j.createFreeStyleProject("dsl-seed");
        ExecuteDslScripts dsl = new ExecuteDslScripts();
        dsl.setScriptText("job('dsl-nightly') {\n"
                + "  description('generated nightly batch job')\n"
                + "  triggers { cron('0 3 * * *') }\n"
                + "}");
        seed.getBuildersList().add(dsl);
        setRunControl(true);

        j.buildAndAssertSuccess(seed);
        j.waitUntilNoActivity();

        assertLocked(job("dsl-nightly"), "a Job DSL seed run");
    }

    /**
     * T-08-288 (SPEC 8 D-31/D-34 "a newly created job starts approvalRequired, blockTimer and
     * blockUpstream on, whatever the creation path"; SPEC 6a D-59a names the same lock with
     * {@code allowedUpstreamJobs} emptied; SPEC 6a "no configuration write path can activate a job"):
     * the REST {@code createItem} payload is a job's own config.xml carrying an opted-out property
     * ({@code approvalRequired}, {@code blockTimer}, {@code blockUpstream} false, upstream allow list
     * [{@code d34-upstream}]). Guard first, run control off: the payload is kept as given, so it really
     * opts out. Run control on: the created job carries one property with the lock and no allow list;
     * its timer is refused, and even after an approved activation its timer and an upstream call from
     * {@code d34-upstream} are refused (blocking baseline). Guard: the job created from the same payload
     * while run control was off passes both causes now that run control is on.
     */
    @Test
    public void t_08_288_creationPayloadCannotOptOutOfTheLock() throws Exception {
        setRunControl(false);
        FreeStyleProject upstream = j.createFreeStyleProject("d34-upstream");
        FreeStyleBuild upstreamRun = j.buildAndAssertSuccess(upstream);
        FreeStyleProject template = j.createFreeStyleProject("d34-template");
        BatchControlJobProperty open = new BatchControlJobProperty(false);
        open.setBlockTimer(false);
        open.setBlockUpstream(false);
        open.setAllowedUpstreamJobs(List.of("d34-upstream"));
        BatchControlFixtures.setBatchControl(template, open);
        String payload = template.getConfigFile().asString();
        assertTrue(payload.contains("BatchControlJobProperty") && payload.contains("d34-upstream"),
                "fixture: the payload carries the opted-out property: " + payload);

        JenkinsRule.WebClient admin = webClient().login("admin");
        assertTrue(createFromXml(admin, "", "d34-optout-off", payload) < 400, "guard: the creation with run control off must succeed");
        FreeStyleProject kept = job("d34-optout-off");
        BatchControlJobProperty keptProperty = kept.getProperty(BatchControlJobProperty.class);
        assertNotNull(keptProperty, "guard: with run control off the payload's property is kept");
        assertFalse(keptProperty.isApprovalRequired() || keptProperty.isBlockTimer() || keptProperty.isBlockUpstream(),
                "guard: with run control off the payload's switches are kept off (the payload really opts out)");
        assertEquals(List.of("d34-upstream"), keptProperty.getAllowedUpstreamJobs(), "guard: the payload's allow list is kept");

        setRunControl(true);
        assertTrue(createFromXml(admin, "", "d34-optout", payload) < 400, "the REST creation with the opt-out payload must succeed");
        FreeStyleProject created = job("d34-optout");
        assertEquals(1, created.getProperties().values().stream().filter(p -> p instanceof BatchControlJobProperty).count(),
                "the created job must carry exactly one Batch Control property");
        assertLocked(created, "a REST createItem whose payload opts out");
        List<String> allowed = created.getProperty(BatchControlJobProperty.class).getAllowedUpstreamJobs();
        assertTrue(allowed == null || allowed.isEmpty(), "the payload's upstream allow list must not survive the lock, got " + allowed);

        assertNull(created.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                "SPEC 6a: a job created under run control is not activated, so its timer is refused");
        assertBlockingBaseline(created);
        BatchControlFixtures.activate(created);
        assertNull(created.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                "D-34: once activated, the timer is still refused by the lock's blockTimer, not the payload's false");
        assertNull(created.scheduleBuild2(0, new Cause.UpstreamCause(upstreamRun)),
                "D-34: once activated, d34-upstream is still refused: the payload's allow list did not survive");
        assertBlockingBaseline(created);

        // guard: the same two causes pass on the job the same payload created while run control was off
        j.assertBuildStatusSuccess(kept.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        j.assertBuildStatusSuccess(kept.scheduleBuild2(0, new Cause.UpstreamCause(upstreamRun)));
        j.waitUntilNoActivity();
        assertEquals(2, kept.getBuilds().size(), "guard: the unlocked twin runs from its timer and from d34-upstream");
    }

    /**
     * T-08-289 (SPEC 8 D-34 "to bring it into service the switch is turned off in the job
     * configuration, a change that is recorded"; SPEC 9 REST {@code config.xml} path; SPEC 6a
     * activation): a job created through REST {@code createItem} with no plugin property is locked
     * (premise). The administrator POSTs its {@code config.xml} with {@code blockTimer} false: 200,
     * the property reads blockTimer off with approvalRequired and blockUpstream still on, and exactly
     * one new CONFIGURE record names the job and admin, its diff naming {@code blockTimer}. The timer
     * is still refused while the job is not activated (SPEC 6a); after an approved activation the same
     * timer cause builds (SUCCESS, one build).
     */
    @Test
    public void t_08_289_clearingBlockTimerThroughConfigXmlIsRecordedAndOpensTheTimerOnceActivated() throws Exception {
        JenkinsRule.WebClient admin = webClient().login("admin");
        assertTrue(createFromXml(admin, "", "d34-plain", PLAIN_FREESTYLE_XML) < 400, "the REST creation must succeed");
        FreeStyleProject created = job("d34-plain");
        assertLocked(created, "a REST createItem without a plugin property");

        Set<String> before = configureRecordIds("d34-plain");
        String xml = created.getConfigFile().asString();
        String cleared = xml.replace("<blockTimer>true</blockTimer>", "<blockTimer>false</blockTimer>");
        assertFalse(cleared.equals(xml), "fixture: the stored config.xml names blockTimer true: " + xml);
        WebRequest post = new WebRequest(admin.createCrumbedUrl(created.getUrl() + "config.xml"), HttpMethod.POST);
        post.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        post.setRequestBody(cleared);
        assertEquals(200, admin.getPage(post).getWebResponse().getStatusCode(), "the administrator's config.xml POST must succeed");

        created = job("d34-plain");
        BatchControlJobProperty property = created.getProperty(BatchControlJobProperty.class);
        assertFalse(property.isBlockTimer(), "the POST must clear blockTimer");
        assertTrue(property.isApprovalRequired() && property.isBlockUpstream(), "the other switches must stay on");

        List<ChangeRecord> added = ApproverFormFixtures.records(ChangeType.CONFIGURE).stream()
                .filter(r -> "d34-plain".equals(r.getTarget()) && !before.contains(r.getId())).collect(Collectors.toList());
        assertEquals(1, added.size(), "SPEC 9: the change through config.xml leaves exactly one CONFIGURE record: " + added.size());
        assertEquals("admin", added.get(0).getUser(), "the CONFIGURE record names who changed the switch");
        assertTrue(String.valueOf(added.get(0).getDiff()).contains("blockTimer"), "the CONFIGURE record's diff shows the switch: "
                + added.get(0).getDiff());

        assertNull(created.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                "SPEC 6a: clearing blockTimer alone does not let a job that is not activated run on its timer");
        assertBlockingBaseline(created);
        BatchControlFixtures.activate(created);
        Future<FreeStyleBuild> firing = created.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        assertNotNull(firing, "D-34: activated and with blockTimer cleared, the timer cause is admitted");
        j.assertBuildStatusSuccess(firing);
        assertEquals(1, created.getBuilds().size(), "exactly one build from the timer");
    }

    private static Set<String> configureRecordIds(String target) {
        return ApproverFormFixtures.records(ChangeType.CONFIGURE).stream().filter(r -> target.equals(r.getTarget()))
                .map(ChangeRecord::getId).collect(Collectors.toSet());
    }

    // ---------------------------------------------------------------- helpers

    private JenkinsRule.WebClient webClient() {
        return j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
    }

    private void setRunControl(boolean enabled) throws Exception {
        cfg.setRunControlEnabled(enabled);
        cfg.save();
    }

    private FreeStyleProject job(String fullName) {
        FreeStyleProject found = j.jenkins.getItemByFullName(fullName, FreeStyleProject.class);
        assertNotNull(found, "the job " + fullName + " must have been created");
        return found;
    }

    /** POSTs {@code createItem} with an XML body under the given container URL prefix. */
    private int createFromXml(JenkinsRule.WebClient wc, String containerUrl, String name, String xml)
            throws Exception {
        URL url = new URL(wc.createCrumbedUrl(containerUrl + "createItem").toExternalForm()
                + "&name=" + name);
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    /**
     * D-34: all three switches on. {@code approvalRequired} is asserted first so that a build
     * which traded D-31 away for the two new switches reports that, and not a missing lock.
     */
    private void assertLocked(Job<?, ?> target, String path) {
        BatchControlJobProperty property = target.getProperty(BatchControlJobProperty.class);
        assertNotNull(property, "D-34: a job created by " + path + " while run control is on must carry the "
                + "plugin job property");
        assertTrue(property.isApprovalRequired(), "D-31/D-34: approvalRequired must be true for a job created by " + path);
        assertTrue(property.isBlockTimer(), "D-34: blockTimer must be true for a job created by " + path
                + " — creating a job must not activate its cron schedule");
        assertTrue(property.isBlockUpstream(), "D-34: blockUpstream must be true for a job created by " + path
                + " — creating a job must not let an upstream job start it");
    }

    private void assertUnlocked(Job<?, ?> target, String message) {
        BatchControlJobProperty property = target.getProperty(BatchControlJobProperty.class);
        if (property == null) {
            return;
        }
        assertFalse(property.isBlockTimer(), message + " (blockTimer)");
        assertFalse(property.isBlockUpstream(), message + " (blockUpstream)");
        assertFalse(property.isApprovalRequired(), message + " (approvalRequired)");
    }

    /** Matrix common blocking baseline. */
    private void assertBlockingBaseline(Job<?, ?> target) throws Exception {
        assertEquals(0, j.jenkins.getQueue().getItems().length, "the queue must stay empty");
        j.waitUntilNoActivity();
        assertEquals(1, target.getNextBuildNumber(), "nextBuildNumber must not move");
        assertTrue(target.getBuilds().isEmpty(), "no build may have run");
    }
}
