package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.AdministrativeMonitor;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationMatrixProperty;
import hudson.security.AuthorizationStrategy;
import hudson.security.Permission;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.ops.ConfigureWithoutGrantMonitor;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.AuthorizationContainer;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.has;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Using the Batch Control matrix strategy logs no matrix-auth deprecation message (issue #31, retired legacy
 * driver browser/audit/a03.mjs #3, gap X-41). Matrix row T-02-204 (note 371).
 *
 * <p>Basis: SPEC 2 (D-35a: the Batch Control variant of the matrix-auth strategy layers active grants over the
 * parent's ACLs; folder, job and agent properties are configurable and effective; it is installed and
 * reverted through the {@code batch-control-strategy} monitor, D-52) and SPEC 6 (compatibility with the current
 * matrix-auth line). No SPEC sentence speaks of deprecation messages; the retired driver counted them in the
 * container log. matrix-auth (the dependency jar, not this plugin) logs each call of a deprecated
 * {@code AuthorizationContainer} method at INFO as "Deprecated method &lt;class&gt;#&lt;method&gt; called by
 * &lt;caller class&gt;" on the logger of the declaring class. The caller is the class one frame up, which for
 * a reflective call is a JDK reflection accessor, so every such record is counted whatever its caller; the
 * only one left out is the record matrix-auth writes about its own delegation (its deprecated
 * {@code getAllSIDs} calls its deprecated {@code getGrantedPermissions}), and only right after a
 * {@code getAllSIDs} record, which is itself counted. The guards show the capture sees a direct and a
 * reflective call.
 *
 * <p>Written from docs/SPEC.md items 2 and 6, docs/DECISIONS.md D-35a and D-52 and docs/TEST-MATRIX.md (T-02-10,
 * T-08-06, T-02-41, T-CFG-06); the retired driver was read only to learn what it looked at. No src/main
 * knowledge.
 */
@WithJenkins
public class MatrixDeprecationLogTest {

    private static final String MATRIX_AUTH = "org.jenkinsci.plugins.matrixauth";
    private static final String MONITOR = "manage/administrativeMonitor/" + StrategyFixtures.MONITOR_ID + "/";
    private static final int CAPACITY = 100_000;

    private JenkinsRule j;

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
    }

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new ProjectMatrixAuthorizationStrategy()));
        StrategyFixtures.changeControlOn();
    }

    /**
     * T-02-204 (SPEC 2 D-35a, D-52, SPEC 6 compatibility; no SPEC sentence on the log; #31 gap X-41): the plain
     * project matrix (StrategyFixtures users) and change control are set up first; then, with every log record
     * at INFO and above captured, the Batch Control matrix strategy goes through its life: the administrator
     * installs it with the monitor's {@code migrate} (premise: installed); Jenkins saves its configuration and
     * reloads it from disk (premise: a new Batch Control strategy instance, read back from XML, with alice's
     * per-job entry and c1's native Configure effective); permission checks on the root, a folder and a job;
     * bob's approved CONFIGURE window and the checks it confers; with the plugin clock moved past the
     * standing-permission monitor's five-minute cache (LIMITATIONS 12), that monitor's scan (premise: active
     * because of c1) and every administrative monitor's {@code isActivated()}; the administrator's
     * {@code manage/}, {@code batch-control/} and the job page; the monitor's {@code revert} (premise: the plain
     * strategy is back) and another save, reload and monitor scan. No captured record
     * names {@code getAllSIDs}, and no matrix-auth deprecation record was written, whatever caller it names.
     * Guards: a direct {@code getAllSIDs()} from this test is counted as exactly one record naming this class
     * (the record about matrix-auth's own delegation is left out), and a reflective
     * {@code hasExplicitPermission(String, Permission)} is counted as exactly one record whose caller is not a
     * Batch Control class.
     */
    @Test
    @SuppressWarnings("deprecation")
    public void t_02_204_matrixStrategyLogsNoDeprecatedMatrixAuthCall() throws Exception {
        Folder team = j.jenkins.createProject(Folder.class, "team");
        FreeStyleProject job = team.createProject(FreeStyleProject.class, "x");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        job.addProperty(amp);

        try (LogRecorder log = new LogRecorder().record("", Level.INFO).capture(CAPACITY)) {
            assertTrue(postMonitor("migrate") < 400, "fixture: the administrator's migrate succeeds");
            AuthorizationStrategy installed = j.jenkins.getAuthorizationStrategy();
            assertTrue(installed instanceof BatchControlMatrixAuthorizationStrategy, "premise: migrate installs the Batch"
                    + " Control matrix strategy, got " + installed.getClass().getName());
            j.jenkins.save();
            j.jenkins.reload();
            AuthorizationStrategy loaded = j.jenkins.getAuthorizationStrategy();
            assertTrue(loaded instanceof BatchControlMatrixAuthorizationStrategy, "premise: the reloaded strategy is the Batch"
                    + " Control matrix strategy, got " + loaded.getClass().getName());
            assertNotSame(installed, loaded, "premise: the strategy was read back from disk");

            Folder reloadedTeam = j.jenkins.getItemByFullName("team", Folder.class);
            FreeStyleProject reloadedJob = j.jenkins.getItemByFullName("team/x", FreeStyleProject.class);
            assertTrue(has(j.jenkins, "admin", Jenkins.ADMINISTER), "premise: admin administers");
            assertTrue(has(reloadedJob, "alice", Item.CONFIGURE), "premise: alice's per-job entry is effective after the reload");
            assertTrue(has(reloadedTeam, "c1", Item.CONFIGURE), "premise: c1 holds native Configure");
            assertFalse(has(reloadedJob, "bob", Item.CONFIGURE), "premise: bob holds no native Configure");
            StrategyFixtures.grant("bob", "team/x", Arrays.asList(GrantAction.CONFIGURE));
            assertTrue(has(reloadedJob, "bob", Item.CONFIGURE), "premise: bob's window confers Configure on team/x");
            assertFalse(has(reloadedTeam, "bob", Item.CONFIGURE), "premise: the window confers nothing on the folder");
            // LIMITATIONS 12: the standing-permission verdict is cached for up to five minutes; move past it so it scans
            BatchClock.setForTest(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(6)));
            assertTrue(standingMonitor().isActivated(), "premise: the standing-permission monitor scans and names c1's"
                    + " native Configure");
            anyMonitorActive();
            JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
            for (String path : new String[] {"manage/", "batch-control/", "job/team/job/x/"}) {
                HtmlPage page = wc.goTo(path);
                assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: admin opens " + path);
            }

            assertTrue(postMonitor("revert") < 400, "fixture: the administrator's revert succeeds");
            assertFalse(j.jenkins.getAuthorizationStrategy() instanceof BatchControlMatrixAuthorizationStrategy,
                    "premise: revert installs the plain strategy");
            j.jenkins.save();
            j.jenkins.reload();
            assertTrue(j.jenkins.getAuthorizationStrategy() instanceof ProjectMatrixAuthorizationStrategy
                    && !(j.jenkins.getAuthorizationStrategy() instanceof BatchControlMatrixAuthorizationStrategy),
                    "premise: the plain project matrix is read back after the revert");
            BatchClock.setForTest(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(12)));
            standingMonitor().isActivated();
            assertTrue(anyMonitorActive(), "premise: a monitor is active (change control on without a Batch Control strategy)");

            List<LogRecord> exercised = inOrder(log);
            assertTrue(exercised.size() < CAPACITY, "premise: the capture kept every record (" + exercised.size() + ")");
            List<String> offenders = offenders(exercised);
            assertTrue(offenders.isEmpty(), "installing, saving, loading, using, scanning and reverting the Batch Control"
                    + " matrix strategy must log no matrix-auth deprecation message: " + offenders);

            AuthorizationContainer container = (AuthorizationContainer) j.jenkins.getAuthorizationStrategy();
            List<LogRecord> before = inOrder(log);
            container.getAllSIDs();
            List<LogRecord> direct = since(log, before);
            List<String> directOffenders = offenders(direct);
            assertEquals(2, deprecations(direct).size(), "guard premise: a direct getAllSIDs() writes its own record and"
                    + " matrix-auth's record about its delegation: " + deprecations(direct));
            assertEquals(1, directOffenders.size(), "guard: the direct call is counted once: " + directOffenders);
            assertTrue(directOffenders.get(0).contains("#getAllSIDs called by " + MatrixDeprecationLogTest.class.getName()),
                    "guard: the counted record names getAllSIDs and this class: " + directOffenders);

            before = inOrder(log);
            Method reflective = AuthorizationContainer.class.getMethod("hasExplicitPermission", String.class, Permission.class);
            reflective.invoke(container, "alice", Item.CONFIGURE);
            List<String> reflectiveOffenders = offenders(since(log, before));
            assertEquals(1, reflectiveOffenders.size(), "guard: a reflective call is counted once: " + reflectiveOffenders);
            assertTrue(reflectiveOffenders.get(0).contains("#hasExplicitPermission called by ")
                    && !reflectiveOffenders.get(0).contains("called by io.jenkins.plugins.batchcontrol"),
                    "guard: the counted record names hasExplicitPermission and a caller outside Batch Control: "
                            + reflectiveOffenders);
        }
    }

    // ---------------------------------------------------------------- helpers

    private int postMonitor(String action) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        return wc.getPage(new WebRequest(wc.createCrumbedUrl(MONITOR + action), HttpMethod.POST))
                .getWebResponse().getStatusCode();
    }

    private static AdministrativeMonitor standingMonitor() {
        AdministrativeMonitor monitor = AdministrativeMonitor.all().get(ConfigureWithoutGrantMonitor.class);
        assertNotNull(monitor, "fixture: the standing-permission monitor is registered");
        return monitor;
    }

    private static boolean anyMonitorActive() {
        boolean any = false;
        for (AdministrativeMonitor monitor : AdministrativeMonitor.all()) {
            any |= monitor.isActivated();
        }
        return any;
    }

    /** The captured records in the order they were logged (by sequence number). */
    private static List<LogRecord> inOrder(LogRecorder log) {
        List<LogRecord> all = new ArrayList<>(log.getRecords());
        all.sort(Comparator.comparingLong(LogRecord::getSequenceNumber));
        return all;
    }

    /** The records logged after the last of {@code earlier}, in order. */
    private static List<LogRecord> since(LogRecorder log, List<LogRecord> earlier) {
        long last = earlier.isEmpty() ? Long.MIN_VALUE : earlier.get(earlier.size() - 1).getSequenceNumber();
        return inOrder(log).stream().filter(r -> r.getSequenceNumber() > last).collect(Collectors.toList());
    }

    private static String message(LogRecord record) {
        return record.getMessage() == null ? "" : record.getMessage();
    }

    private static String describe(LogRecord record) {
        return record.getLevel() + " " + record.getLoggerName() + ": " + message(record);
    }

    /** A matrix-auth deprecation record ("Deprecated method ..." on a matrix-auth logger or naming a matrix-auth class). */
    private static boolean isMatrixAuthDeprecation(LogRecord record) {
        String text = message(record);
        String logger = record.getLoggerName() == null ? "" : record.getLoggerName();
        return text.contains("Deprecated method") && (logger.startsWith(MATRIX_AUTH) || text.contains(MATRIX_AUTH + "."));
    }

    private static List<String> deprecations(List<LogRecord> records) {
        return records.stream().filter(MatrixDeprecationLogTest::isMatrixAuthDeprecation)
                .map(MatrixDeprecationLogTest::describe).collect(Collectors.toList());
    }

    /**
     * Every record naming {@code getAllSIDs} and every matrix-auth deprecation record, whatever its caller,
     * except matrix-auth's record about its own delegation: {@code #getGrantedPermissions called by} its own
     * {@code AuthorizationContainer}, directly after a {@code #getAllSIDs} deprecation record.
     */
    private static List<String> offenders(List<LogRecord> records) {
        List<String> out = new ArrayList<>();
        LogRecord previousDeprecation = null;
        for (LogRecord record : records) {
            String text = message(record);
            boolean deprecation = isMatrixAuthDeprecation(record);
            boolean selfDelegation = deprecation && previousDeprecation != null
                    && message(previousDeprecation).contains("#getAllSIDs called by ")
                    && text.endsWith("#getGrantedPermissions called by " + AuthorizationContainer.class.getName());
            if (!selfDelegation && (deprecation || text.toLowerCase(Locale.ROOT).contains("getallsids"))) {
                out.add(describe(record));
            }
            if (deprecation) {
                previousDeprecation = record;
            }
        }
        return out;
    }
}
