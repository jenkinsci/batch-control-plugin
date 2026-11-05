package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.AdministrativeMonitor;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationMatrixProperty;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.has;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Using the Batch Control matrix strategy logs no matrix-auth deprecation message, {@code getAllSIDs} in
 * particular (issue #31, retired legacy driver browser/audit/a03.mjs #3, gap X-41). Matrix row T-02-204 (note
 * 371).
 *
 * <p>Basis: SPEC 2 (D-35a: the Batch Control variant of the matrix-auth strategy layers active grants over the
 * parent's ACLs; folder, job and agent properties are configurable and effective) and SPEC 6 (compatibility
 * with the current matrix-auth line). No SPEC sentence speaks of deprecation messages; the retired driver
 * counted them in the container log. matrix-auth (the dependency jar, not this plugin) logs each call of a
 * deprecated {@code AuthorizationContainer} method at INFO as "Deprecated method ... called by &lt;caller
 * class&gt;" on the logger of the declaring class; the guard shows the capture sees exactly that.
 *
 * <p>Written from docs/SPEC.md items 2 and 6 and docs/TEST-MATRIX.md (T-02-10, T-08-06, T-02-41); the retired
 * driver was read only to learn what it looked at. No src/main knowledge.
 */
@WithJenkins
public class MatrixDeprecationLogTest {

    private JenkinsRule j;
    private BatchControlMatrixAuthorizationStrategy strategy;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        strategy = StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy());
        j.jenkins.setAuthorizationStrategy(strategy);
        StrategyFixtures.changeControlOn();
    }

    /**
     * T-02-204 (SPEC 2 D-35a, SPEC 6 compatibility; no SPEC sentence on the log; #31 gap X-41): with every
     * log record at INFO and above captured, the Batch Control matrix strategy is exercised: permission checks
     * on the root, a folder and a job with its own matrix property, bob's approved CONFIGURE window and the
     * checks it confers, every administrative monitor's {@code isActivated()} (the standing-permission
     * monitor is active because c1 holds native Configure, premise), and the administrator's
     * {@code manage/}, {@code batch-control/} and the job page. No captured record names
     * {@code getAllSIDs} and no "Deprecated method" record names a caller in Batch Control. Guard: a call of
     * the deprecated {@code getAllSIDs()} from this test is captured as exactly one such record naming this
     * class as the caller (matrix-auth's own delegation may log a second record, naming itself as the caller).
     */
    @Test
    @SuppressWarnings("deprecation")
    public void t_02_204_matrixStrategyLogsNoDeprecatedMatrixAuthCall() throws Exception {
        Folder team = j.jenkins.createProject(Folder.class, "team");
        FreeStyleProject job = team.createProject(FreeStyleProject.class, "x");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        job.addProperty(amp);

        try (LogRecorder log = new LogRecorder().record("", Level.INFO).capture(5000)) {
            assertTrue(has(j.jenkins, "admin", Jenkins.ADMINISTER), "premise: admin administers");
            assertTrue(has(job, "alice", Item.CONFIGURE), "premise: alice's per-job entry is effective");
            assertTrue(has(team, "c1", Item.CONFIGURE), "premise: c1 holds native Configure");
            assertFalse(has(job, "bob", Item.CONFIGURE), "premise: bob holds no native Configure");
            StrategyFixtures.grant("bob", "team/x", Arrays.asList(GrantAction.CONFIGURE));
            assertTrue(has(job, "bob", Item.CONFIGURE), "premise: bob's window confers Configure on team/x");
            assertFalse(has(team, "bob", Item.CONFIGURE), "premise: the window confers nothing on the folder");
            boolean anyActive = false;
            for (AdministrativeMonitor monitor : AdministrativeMonitor.all()) {
                anyActive |= monitor.isActivated();
            }
            assertTrue(anyActive, "premise: a monitor is active (c1's standing Configure)");
            JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
            for (String path : new String[] {"manage/", "batch-control/", "job/team/job/x/"}) {
                HtmlPage page = wc.goTo(path);
                assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: admin opens " + path);
            }

            List<String> offenders = log.getRecords().stream()
                    .filter(r -> message(r).toLowerCase(Locale.ROOT).contains("getallsids")
                            || (message(r).contains("Deprecated method") && message(r).contains("called by io.jenkins.plugins.batchcontrol")))
                    .map(r -> r.getLevel() + " " + r.getLoggerName() + ": " + message(r))
                    .collect(Collectors.toList());
            assertTrue(offenders.isEmpty(), "using the Batch Control matrix strategy must log no matrix-auth deprecation"
                    + " message: " + offenders);

            int before = deprecations(log).size();
            strategy.getAllSIDs();
            List<String> captured = deprecations(log);
            List<String> mine = captured.subList(before, captured.size()).stream()
                    .filter(m -> m.contains("getAllSIDs") && m.contains("called by " + MatrixDeprecationLogTest.class.getName()))
                    .collect(Collectors.toList());
            assertEquals(1, mine.size(), "guard: the test's own getAllSIDs() call must be captured as one record naming"
                    + " getAllSIDs and this class as the caller: " + captured);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String message(LogRecord record) {
        return record.getMessage() == null ? "" : record.getMessage();
    }

    private static List<String> deprecations(LogRecorder log) {
        return log.getRecords().stream().map(MatrixDeprecationLogTest::message)
                .filter(m -> m.contains("Deprecated method")).collect(Collectors.toList());
    }
}
