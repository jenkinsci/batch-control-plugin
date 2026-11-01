package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
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
 * SPEC item 1 (a switch change and its side effect, "revoking active grants when change control is
 * turned off"), item 8 (revocation is recorded) and items 4 and 12 (records are append-only and stay
 * queryable): the change-control kill switch revokes every open permission window with exactly one
 * GRANT_REVOKE record per window, and the audit trail written before the switch-off is still listed
 * afterwards. Matrix row T-08-287 (note 367).
 *
 * <p>This is the server-side half of the retired e2e script {@code e2e/scripts/rest-kill-switch.sh}
 * (issue #31, S-15). One window per switch-off is T-01-08; two windows with one of them unreadable is
 * T-GAP-308, which is skipped where the platform cannot make a file unreadable. This row runs
 * everywhere and adds what neither asserts: the records made under a window before the switch-off
 * (a CONFIGURE record linked to it) are still on the Changes screen and in {@code changes.csv}.
 *
 * <p>Written from docs/SPEC.md items 1, 4, 8, 9 and 12 and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class GrantKillSwitchAuditTest {

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;

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

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-08-287 (SPEC 1, 8, 4, 12; S-15): u1 holds a CONFIGURE window on {@code ks-a} and saves the
     * job under it (premise: one CONFIGURE record linked to that window, exported in
     * {@code changes.csv}); u2 holds a CONFIGURE window on {@code ks-b}. The administrator turns
     * change control off on the global configuration form. Exactly two GRANT_REVOKE records are
     * added, exactly one identifying each window; neither window is active and u1's next save
     * answers 403. The CONFIGURE record made under u1's window is still listed on the Changes screen
     * and still exported in {@code changes.csv} with that window's id, next to both GRANT_REVOKE rows; on
 * the Changes screen it is still one CONFIGURE row and each revocation is one GRANT_REVOKE row
 * naming its job or window (guard: no such row before the switch-off).
     */
    @Test
    public void t_08_287_killSwitchRevokesEachWindowOnceAndKeepsTheAuditTrail() throws Exception {
        FreeStyleProject a = j.createFreeStyleProject("ks-a");
        a.setDescription("base");
        FreeStyleProject b = j.createFreeStyleProject("ks-b");
        b.setDescription("base");
        Grant w1 = window("u1", "ks-a");
        Grant w2 = window("u2", "ks-b");

        assertEquals(200, postConfigXml("u1", a, "changed under the window"), "premise: u1 saves ks-a inside the window");
        List<ChangeRecord> configures = ApproverFormFixtures.records(ChangeType.CONFIGURE).stream()
                .filter(r -> "ks-a".equals(r.getTarget()) && "u1".equals(r.getUser())).collect(Collectors.toList());
        assertEquals(1, configures.size(), "premise: one CONFIGURE record for ks-a by u1: " + WindowStateFixtures.describe(configures));
        assertEquals(w1.getId(), configures.get(0).getGrantId(), "premise: the CONFIGURE record is linked to u1's window (SPEC 9)");
        String configureId = configures.get(0).getId();
        assertEquals(1, csvRows(csv(), configureId, w1.getId()).size(), "premise: changes.csv exports the CONFIGURE row with the window id");

        assertEquals(1, changeRows("CONFIGURE", w1.getId()).size(), "premise: the Changes screen lists u1's CONFIGURE change as a row");
        assertTrue(revokeRows(w1.getId(), "ks-a").isEmpty() && revokeRows(w2.getId(), "ks-b").isEmpty(),
                "guard: before the switch-off the Changes screen has no GRANT_REVOKE row for either window");

        Set<String> before = WindowStateFixtures.revokeRecordIds();
        Page saved = switchChangeControlOff();
        assertTrue(saved.getWebResponse().getStatusCode() < 400, "the administrator's save turning change control off must succeed, got HTTP "
                + saved.getWebResponse().getStatusCode());
        assertFalse(BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "premise: change control is off");

        List<ChangeRecord> revokes = WindowStateFixtures.revokeRecordsSince(before);
        assertEquals(2, revokes.size(), "S-15: one GRANT_REVOKE record per open window, two windows: " + WindowStateFixtures.describe(revokes));
        assertEquals(1, revokes.stream().filter(r -> WindowStateFixtures.identifies(r, w1.getId(), "ks-a")).count(),
                "exactly one GRANT_REVOKE record identifies u1's window: " + WindowStateFixtures.describe(revokes));
        assertEquals(1, revokes.stream().filter(r -> WindowStateFixtures.identifies(r, w2.getId(), "ks-b")).count(),
                "exactly one GRANT_REVOKE record identifies u2's window: " + WindowStateFixtures.describe(revokes));
        assertNull(WindowStateFixtures.active(w1.getId()), "u1's window must no longer be active");
        assertNull(WindowStateFixtures.active(w2.getId()), "u2's window must no longer be active");
        assertEquals(403, postConfigXml("u1", a, "after the switch-off"), "the revoked window must confer nothing");
        assertEquals("changed under the window", a.getDescription(), "the refused save changed nothing");

        // the audit trail written before the switch-off is still there
        String exported = csv();
        assertEquals(1, csvRows(exported, configureId, w1.getId()).size(), "changes.csv must still export the CONFIGURE row made under u1's"
                + " window, with the window id: " + ApproverFormFixtures.excerpt(exported));
        for (ChangeRecord revoke : revokes) {
            assertEquals(1, csvRows(exported, revoke.getId(), "GRANT_REVOKE").size(), "changes.csv must export the GRANT_REVOKE row "
                    + revoke.getId() + ": " + ApproverFormFixtures.excerpt(exported));
        }
        List<String> configureRows = changeRows("CONFIGURE", w1.getId()).stream()
                .filter(r -> r.contains("ks-a") && !r.contains("GRANT_REVOKE")).collect(Collectors.toList());
        assertEquals(1, configureRows.size(), "the Changes screen must still list, as one row, the CONFIGURE change made on ks-a under"
                + " u1's window " + w1.getId() + ": " + configureRows);
        assertEquals(1, revokeRows(w1.getId(), "ks-a").size(), "the Changes screen must list the revocation of u1's window on ks-a as"
                + " one GRANT_REVOKE row: " + revokeRows(w1.getId(), "ks-a"));
        assertEquals(1, revokeRows(w2.getId(), "ks-b").size(), "the Changes screen must list the revocation of u2's window on ks-b as"
                + " one GRANT_REVOKE row: " + revokeRows(w2.getId(), "ks-b"));
    }

    /**
     * The rows (innermost {@code tr}) of the administrator's Changes screen whose text carries
     * {@code type} as the screen renders the change type (the constant's name, as the history change
     * list shows TRIGGER_BLOCKED in T-06-48) and names at least one of {@code names}.
     */
    private List<String> changeRows(String type, String... names) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(j, "admin", "batch-control/changes/");
        assertEquals(200, page.getWebResponse().getStatusCode(), "the Changes screen must open for the administrator");
        List<String> out = new ArrayList<>();
        for (Object o : page.getByXPath("//tr[not(.//tr)]")) {
            String text = ((DomElement) o).asNormalizedText();
            if (text.contains(type) && Arrays.stream(names).anyMatch(text::contains)) {
                out.add(text);
            }
        }
        return out;
    }

    /** The Changes screen's GRANT_REVOKE rows naming the window id or its job. */
    private List<String> revokeRows(String grantId, String job) throws Exception {
        return changeRows("GRANT_REVOKE", grantId, job);
    }

    // ---------------------------------------------------------------- helpers

    /** A 60-minute CONFIGURE window for {@code user} on {@code job}, requested and approved by a1. */
    private Grant window(String user, String job) throws Exception {
        GrantRequest request;
        try (ACLContext ignored = as(user)) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, job),
                    Arrays.asList(GrantAction.CONFIGURE), 60, "kill switch scenario on " + job, "a1");
        }
        Grant grant;
        try (ACLContext ignored = as("a1")) {
            grant = GrantRequestService.get().approve(request.getId(), "ok");
        }
        assertNotNull(grant, "fixture: the window on " + job + " must have been issued");
        assertNotNull(WindowStateFixtures.active(grant.getId()), "fixture: the window on " + job + " must be active");
        return grant;
    }

    /** The administrator unticks changeControlEnabled on the global configuration page and submits. */
    private Page switchChangeControlOff() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        HtmlForm form = wc.goTo("configure").getFormByName("config");
        List<HtmlCheckBoxInput> boxes = form.getByXPath(
                ".//input[@type='checkbox' and (@name='_.changeControlEnabled' or @name='changeControlEnabled')]");
        assertEquals(1, boxes.size(), "fixture: the global form must render exactly one changeControlEnabled checkbox");
        boxes.get(0).setChecked(false);
        return j.submit(form);
    }

    private int postConfigXml(String userId, FreeStyleProject job, String newDescription) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        String xml = job.getConfigFile().asString()
                .replace("<description>" + job.getDescription() + "</description>",
                        "<description>" + newDescription + "</description>");
        WebRequest request = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private String csv() throws Exception {
        WebResponse response = ApproverFormFixtures.get(j, "admin", "batch-control/history/changes.csv");
        assertEquals(200, response.getStatusCode(), "the administrator must download changes.csv");
        return response.getContentAsString();
    }

    /** The rows of {@code csv} that contain every one of {@code needles}. */
    private static List<String> csvRows(String csv, String... needles) {
        List<String> out = new ArrayList<>();
        for (String line : csv.split("\r?\n")) {
            boolean all = true;
            for (String needle : needles) {
                all &= line.contains(needle);
            }
            if (all) {
                out.add(line);
            }
        }
        return out;
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
