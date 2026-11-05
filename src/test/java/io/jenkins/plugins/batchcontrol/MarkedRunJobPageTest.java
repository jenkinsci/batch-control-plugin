package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import hudson.model.User;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.security.QueueItemAuthenticatorConfiguration;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.authorizeproject.GlobalQueueItemAuthenticator;
import org.jenkinsci.plugins.authorizeproject.strategy.SpecificUsersAuthorizationStrategy;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.WINDOW_MINUTES;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The job page side of D-58b/D-58c (issue #31, note 370, gap X-19, retired driver
 * browser/check30/c08.mjs #13): the job's Batch Control page shows a native Configure holder that the
 * job was changed under a permission window, lists the marked run and offers Mark as reviewed. Matrix
 * row T-02-202.
 *
 * <p>Basis: SPEC item 2 (D-58b: a Replay by a grant-only user puts the job into the "changed under a grant"
 * state, which ends only through Mark as reviewed, a POST by a native Item/Configure or Overall/Administer
 * holder; D-58c: the run carries a marker, "the review surfaces list such runs", and a rebuild-plugin
 * Rebuild of a marked run is refused to everyone but an administrator) and the usability line of SPEC
 * section 6 (a control is shown only to a user who can use it). The monitor's listing is T-GAP-246, the
 * build page's Rebuild link T-02-83, the review POST T-02-64/69/74. Gap X-20 (no "Rebuild Last" on the
 * job page of a marked last run) has no row: the rebuild plugin on the test classpath offers "Rebuild
 * Last" on no Pipeline job page, not even to the administrator, and only Pipeline runs can be marked.
 *
 * <p>The fixture is T-02-83's: {@code replay-me} with #1; bob's CONFIGURE window on it; bob replays #1 as
 * the marked #2. c1 holds native Item/Configure, Job/Build and (LIMITATIONS 35: the job's Batch Control
 * page needs it) BatchControl/Request; u1 holds Item/Read, Job/Build and Request only. Builds run as
 * {@code batch} (a global build authorization).
 *
 * <p>Written from docs/SPEC.md item 2 and section 6, docs/DECISIONS.md D-58b/D-58c, docs/LIMITATIONS.md
 * item 35 and docs/TEST-MATRIX.md (T-02-64, T-02-74, T-02-83, T-GAP-246); the retired drivers were read
 * only to learn what each looked at. No src/main knowledge.
 */
@WithJenkins
public class MarkedRunJobPageTest {

    private static final Pattern CHANGED_UNDER = Pattern.compile("(?i)changed under");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy());
        for (String user : new String[] {"batch", "u1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(user));
            strategy.add(Item.READ, PermissionEntry.user(user));
            strategy.add(Item.BUILD, PermissionEntry.user(user));
        }
        strategy.add(Item.BUILD, PermissionEntry.user("c1"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("c1"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        User.getById("batch", true).save();
        QueueItemAuthenticatorConfiguration.get().getAuthenticators()
                .add(new GlobalQueueItemAuthenticator(new SpecificUsersAuthorizationStrategy("batch")));
        StrategyFixtures.changeControlOn();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-02-202 (SPEC 2 D-58b "Mark as reviewed", D-58c "the review surfaces list such runs", SPEC 6
     * usability; #31 gap X-19): after bob's window has ended, c1 opens {@code job/replay-me/batch-control/}:
     * its main panel holds a {@code .jenkins-alert} saying the job was changed under a window (wording
     * beyond "changed under" not pinned), the page links run #2 ({@code job/replay-me/2/}) and not run #1,
     * and offers a control targeting {@code job/replay-me/batch-control/markReviewed}. Guards: on the
     * page of {@code plain-p} (a Pipeline never changed under a window) c1 finds no such alert and no
     * {@code markReviewed} control; on {@code replay-me}'s page u1 (Request, no native Configure) is offered
     * no {@code markReviewed} control; after c1's review through that control the page shows the alert no
     * more and offers no {@code markReviewed}.
     */
    @Test
    public void t_02_202_jobPageShowsTheChangedUnderAWindowAlertWithTheMarkedRun() throws Exception {
        WorkflowJob job = replayedUnderGrant();
        WorkflowJob plain = j.jenkins.createProject(WorkflowJob.class, "plain-p");
        plain.setDefinition(new CpsFlowDefinition("echo 'plain'", true));
        j.buildAndAssertSuccess(plain);
        afterWindow();

        HtmlPage page = UsabilityFixtures.htmlPage(j, "c1", job.getUrl() + "batch-control/");
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: c1 opens the job's Batch Control page");
        assertEquals(1, alerts(page).size(), "c1 must see one alert saying the job was changed under a window: "
                + excerpt(main(page).asNormalizedText()));
        List<String> runs = runLinks(page, job);
        assertTrue(runs.contains("2"), "the page must link the marked run #2: " + runs);
        assertFalse(runs.contains("1"), "the page must not link the unmarked run #1 as a marked run: " + runs);
        assertEquals(1, reviewControls(page, job).size(), "c1 must be offered Mark as reviewed (markReviewed)");

        HtmlPage plainPage = UsabilityFixtures.htmlPage(j, "c1", plain.getUrl() + "batch-control/");
        assertEquals(200, plainPage.getWebResponse().getStatusCode(), "fixture: c1 opens plain-p's Batch Control page");
        assertTrue(alerts(plainPage).isEmpty(), "guard: a job never changed under a window shows no such alert");
        assertTrue(reviewControls(plainPage, plain).isEmpty(), "guard: a job never changed under a window offers no review");

        HtmlPage requester = UsabilityFixtures.htmlPage(j, "u1", job.getUrl() + "batch-control/");
        assertEquals(200, requester.getWebResponse().getStatusCode(), "fixture: u1 opens the job's Batch Control page");
        assertTrue(reviewControls(requester, job).isEmpty(), "guard: u1 (no native Configure) must not be offered Mark as reviewed");

        int code = ApproverFormFixtures.post(j, "c1", job.getUrl() + "batch-control/markReviewed", List.<NameValuePair>of())
                .getStatusCode();
        assertTrue(code < 400, "fixture: c1's review succeeds, got " + code);
        HtmlPage reviewed = UsabilityFixtures.htmlPage(j, "c1", job.getUrl() + "batch-control/");
        assertTrue(alerts(reviewed).isEmpty(), "guard: after the review the alert is gone: " + excerpt(main(reviewed).asNormalizedText()));
        assertTrue(reviewControls(reviewed, job).isEmpty(), "guard: after the review no markReviewed is offered");
    }

    // ---------------------------------------------------------------- helpers

    /** replay-me with #1; bob's CONFIGURE window on it; bob replays #1 as #2 (a harmless script). */
    private WorkflowJob replayedUnderGrant() throws Exception {
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "replay-me");
        job.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        j.buildAndAssertSuccess(job);
        StrategyFixtures.grant("bob", "replay-me", Arrays.asList(GrantAction.CONFIGURE));
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("bob");
        String script = "echo 'planted'";
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("mainScript", script));
        params.add(new NameValuePair("json", "{\"mainScript\":\"" + script.replace("'", "'") + "\"}"));
        WebRequest req = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "1/replay/run"), HttpMethod.POST);
        req.setRequestParameters(params);
        wc.getPage(req);
        j.waitUntilNoActivity();
        assertNotNull(job.getBuildByNumber(2), "fixture: bob's replay must have run as #2");
        return job;
    }

    private void afterWindow() {
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(WINDOW_MINUTES + 1)), ZoneOffset.UTC));
    }

    private static DomElement main(HtmlPage page) {
        DomElement main = page.getElementById("main-panel");
        assertNotNull(main, "fixture: the page has a main panel");
        return main;
    }

    private static List<DomElement> alerts(HtmlPage page) {
        List<DomElement> out = new ArrayList<>();
        for (DomNode n : main(page).querySelectorAll(".jenkins-alert")) {
            if (CHANGED_UNDER.matcher(n.asNormalizedText()).find()) {
                out.add((DomElement) n);
            }
        }
        return out;
    }

    /** Numbers of the runs of {@code job} linked from the main panel ({@code job/<name>/<n>/}). */
    private List<String> runLinks(HtmlPage page, WorkflowJob job) {
        List<String> out = new ArrayList<>();
        String prefix = j.contextPath + "/" + job.getUrl();
        for (DomNode n : main(page).querySelectorAll("a[href]")) {
            String p = path(page, (DomElement) n);
            if (p.startsWith(prefix)) {
                String rest = p.substring(prefix.length()).replaceAll("/+$", "");
                if (rest.matches("\\d+")) {
                    out.add(rest);
                }
            }
        }
        return out;
    }

    private static List<java.net.URL> reviewControls(HtmlPage page, WorkflowJob job) throws Exception {
        return RequestPageFixtures.controlsEndingWith(page, job.getUrl() + "batch-control/markReviewed");
    }

    private static String path(HtmlPage page, DomElement anchor) {
        try {
            String p = URI.create(page.getFullyQualifiedUrl(anchor.getAttribute("href")).toExternalForm()).normalize().getPath();
            return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        } catch (Exception e) {
            return "";
        }
    }
}
