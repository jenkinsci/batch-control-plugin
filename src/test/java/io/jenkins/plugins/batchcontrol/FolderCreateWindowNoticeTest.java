package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A user with an active name-restricted CREATE window on a folder sees, on the folder page, that they hold it,
 * the exact name or pattern it allows and when it ends (issue #31, retired legacy driver
 * browser/verify/v6.mjs #5, gap X-46). Matrix row T-UI-312 (note 371).
 *
 * <p>No SPEC sentence fixes this notice: it is the screen contract of e2e-03 DEF-19 (the restriction is
 * explained where the user creates items), next to SPEC 8 (D-40: a CREATE request may carry a name
 * restriction, "shown to the approver"; the permission screen shows an active window's remaining time, D-33)
 * and SPEC 6 usability. The wording is not pinned: the notice must name Create, the restriction and an end
 * time.
 *
 * <p>Users: {@code u1} and {@code u2} (Overall/Read, Item/Read, RequestGrant), {@code a1} approver. Change
 * control on; the Batch Control matrix strategy; folders {@code team} and {@code crew}.
 *
 * <p>Written from docs/SPEC.md items 8 and 6, docs/DECISIONS.md D-33 and D-40 and docs/TEST-MATRIX.md (T-08-50,
 * note 138); the retired driver was read only to learn what it looked at. No src/main knowledge.
 */
@WithJenkins
public class FolderCreateWindowNoticeTest {

    private static final String PATTERN = "app-[0-9]+";
    private static final String EXACT = "crew-nightly";
    /** An end time: a word for the end and a clock time. */
    private static final Pattern END_TIME = Pattern.compile("(?is)\\b(until|expires?|ends?|ending)\\b.*\\b\\d{1,2}:\\d{2}");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u2", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u2"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        j.jenkins.createProject(Folder.class, "team");
        j.jenkins.createProject(Folder.class, "crew");
    }

    /**
     * T-UI-312 (screen contract, e2e-03 DEF-19; SPEC 8 D-40; #31 gap X-46): u1 holds approved CREATE windows on
     * {@code team} restricted to {@code /app-[0-9]+/} and on {@code crew} restricted to the exact name
     * {@code crew-nightly}. On {@code job/team/} u1's main panel names Create, the pattern {@code app-[0-9]+}
     * and an end time ("until", "expires" or "ends" and a clock time), and not {@code crew-nightly}; on
     * {@code job/crew/} the same with {@code crew-nightly} and not the pattern. Guards: u2 (no window) sees
     * neither restriction on either page; with change control off (the windows end) u1 sees neither either.
     */
    @Test
    public void t_ui_312_folderPageShowsTheHoldersCreateWindowRestrictionAndEnd() throws Exception {
        window("team", "/" + PATTERN + "/");
        window("crew", EXACT);
        assertTrue(GrantService.get().hasActiveGrant("u1", "team", Item.CREATE), "premise: u1's CREATE window on team is active");
        assertTrue(GrantService.get().hasActiveGrant("u1", "crew", Item.CREATE), "premise: u1's CREATE window on crew is active");

        assertNotice("job/team/", PATTERN, EXACT);
        assertNotice("job/crew/", EXACT, PATTERN);

        for (String path : new String[] {"job/team/", "job/crew/"}) {
            String text = mainText("u2", path);
            assertFalse(text.contains(PATTERN) || text.contains(EXACT), "guard: u2 holds no window and must see no restriction"
                    + " on " + path + ": " + excerpt(text));
        }

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(false);
        cfg.save();
        for (String path : new String[] {"job/team/", "job/crew/"}) {
            String text = mainText("u1", path);
            assertFalse(text.contains(PATTERN) || text.contains(EXACT), "guard: with change control off u1 sees no window"
                    + " notice on " + path + ": " + excerpt(text));
        }
    }

    // ---------------------------------------------------------------- helpers

    private void window(String folder, String restriction) throws Exception {
        String id = submitGrantOk(j, "u1", folder, List.of("CREATE"), 30, "new jobs in " + folder, restriction, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: a1 approves u1's CREATE window on " + folder);
    }

    private void assertNotice(String path, String shown, String other) throws Exception {
        String text = mainText("u1", path);
        int at = text.indexOf(shown);
        assertTrue(at >= 0, path + ": u1's folder page must name the restriction " + shown + " of the CREATE window u1 holds"
                + " there: " + excerpt(text));
        String around = text.substring(Math.max(0, at - 300), Math.min(text.length(), at + shown.length() + 300));
        assertTrue(around.toLowerCase(Locale.ROOT).contains("create"), path + ": the notice must say it is a Create window: "
                + excerpt(around));
        assertTrue(END_TIME.matcher(around).find(), path + ": the notice must say when the window ends: " + excerpt(around));
        assertFalse(text.contains(other), path + ": the notice must not name the other folder's restriction " + other + ": "
                + excerpt(text));
    }

    private String mainText(String userId, String path) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(j, userId, path);
        DomElement main = page.getElementById("main-panel");
        assertNotNull(main, "fixture: " + userId + " opens " + path + " (HTTP " + page.getWebResponse().getStatusCode() + ")");
        return main.asNormalizedText();
    }
}
