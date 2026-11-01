package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8 (D-33): "the expiry guidance and the way to request again are on the permission screen:
 * the remaining time of active windows, the history of expired windows, a re-request link." The
 * history and the re-request link are T-08-49; this row asserts the remaining time. Matrix row
 * T-08-290 (note 367).
 *
 * <p>This is the server-side half of the retired e2e script {@code e2e/scripts/rest-screens-v2.sh}
 * (issue #31, UX-11), which only printed whether a remaining value was rendered. SPEC fixes no
 * format; the row accepts any rendering whose minute count follows the plugin clock ("20 min",
 * "20 minutes", "20m", "20 min 0 sec") and fails a value that does not move with the clock, a
 * missing value and a value computed from the window's length instead of its end.
 *
 * <p>Time never passes for real: BatchClock is fixed and moved (matrix note 1).
 *
 * <p>Written from docs/SPEC.md item 8 and docs/TEST-MATRIX.md (T-08-49, notes 1, 137) only (no
 * src/main knowledge).
 */
@WithJenkins
public class GrantRemainingTimeTest {

    private static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-08-290 (D-33, UX-11): u1's 30-minute CONFIGURE window on {@code rt-job} is approved at T0.
     * At T0+10 min u1's Active row for it on {@code batch-control/grants/} shows 20 minutes left; at
     * T0+25 min the same row shows 5 minutes left and no longer 20. Guard: at T0+31 min, after the
     * expiry work, the window has no Active row.
     */
    @Test
    public void t_08_290_activeWindowShowsTheRemainingTimeByThePluginClock() throws Exception {
        j.createFreeStyleProject("rt-job");
        Grant window = window("u1", "rt-job", 30);
        assertEquals(T0.plus(Duration.ofMinutes(30)), window.getExpiresAt(), "fixture: the window ends 30 minutes after T0");

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(10)), ZoneOffset.UTC));
        String at10 = activeRowText(window);
        assertTrue(minutes(20).matcher(at10).find(), "D-33: at T0+10 min the Active row must show 20 minutes remaining: " + at10);

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(25)), ZoneOffset.UTC));
        String at25 = activeRowText(window);
        assertTrue(minutes(5).matcher(at25).find(), "D-33: at T0+25 min the Active row must show 5 minutes remaining: " + at25);
        assertFalse(minutes(20).matcher(at25).find(), "the remaining time must follow the clock, not stay at 20 minutes: " + at25);

        // guard: past the end, after the expiry work, the window is no longer listed as active
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
        HtmlPage after = UsabilityFixtures.htmlPage(j, "u1", "batch-control/grants/");
        assertNull(WindowStateFixtures.activeRow(j, after, window.getId()), "guard: the expired window has no Active row");
    }

    // ---------------------------------------------------------------- helpers

    /** A whole number of minutes, as "N min", "N minutes", "N m" or "Nm" (not part of a larger number). */
    private static Pattern minutes(int n) {
        return Pattern.compile("(?i)(?<![0-9])" + n + "\\s*m(?:in(?:ute)?s?)?\\b");
    }

    private String activeRowText(Grant window) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(j, "u1", "batch-control/grants/");
        assertEquals(200, page.getWebResponse().getStatusCode(), "the permission screen must open for u1");
        DomElement row = WindowStateFixtures.activeRow(j, page, window.getId());
        assertNotNull(row, "the permission screen must list the window in its Active list: "
                + UsabilityFixtures.excerpt(page.asNormalizedText()));
        return row.asNormalizedText();
    }

    private Grant window(String user, String job, int minutes) {
        GrantRequest request;
        try (ACLContext ignored = as(user)) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, job),
                    Arrays.asList(GrantAction.CONFIGURE), minutes, "fix the schedule of " + job, "a1");
        }
        Grant grant;
        try (ACLContext ignored = as("a1")) {
            grant = GrantRequestService.get().approve(request.getId(), "ok");
        }
        assertNotNull(grant, "fixture: the window on " + job + " must have been issued");
        return grant;
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
