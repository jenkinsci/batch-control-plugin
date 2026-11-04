package io.jenkins.plugins.batchcontrol;

import io.jenkins.plugins.batchcontrol.store.BatchClock;
import hudson.XmlFile;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 1 (#19): a switch change takes effect only once the new configuration is saved.
 * If the save fails, the in-memory switch, the toggle record and any side effect (revoking the
 * active grants when change control goes off) are not applied. Matrix rows T-01-07 .. T-01-10
 * (note 72). D-42 carves out the direct setters (script console, JCasC): rows T-01-11/12
 * (note 74). S-02, one switch leaves the other untouched: rows T-01-13/14 (note 75).
 *
 * <p>How the save is made to fail from outside: the global configuration's standard file
 * ({@code $JENKINS_HOME/<descriptor id>.xml}, ARCHITECTURE section 5) is replaced by a
 * non-empty directory, so no write can land there. The fault is proven independently of the
 * plugin by writing through core's {@link XmlFile} and expecting an {@link IOException}.
 * {@code $JENKINS_HOME/batch-control/} (records, grants) stays writable, so any record or
 * revocation that runs before the save is observable.
 *
 * Written from docs/SPEC.md, docs/ARCHITECTURE.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class SwitchSaveFailureTest {

    private JenkinsRule j;
    private FreeStyleProject job;
    private BatchControlGlobalConfiguration cfg;

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

        job = j.createFreeStyleProject("batch-x");
        job.setDescription("base");
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-01-07: change control on, u1 holds an active CONFIGURE window; the configuration file cannot
     * be written; admin unticks changeControlEnabled on the global form -> error response, switch
     * still on in memory, no CONFIG_TOGGLE record, no GRANT_REVOKE record, the grant still active
     * and still conferring.
     */
    @Test
    @Tag("core")
    public void t_01_07_failedSaveTurningChangeControlOffAppliesNothing() throws Exception {
        Grant grant = changeControlOnWithActiveGrant();
        int toggles = records(ChangeType.CONFIG_TOGGLE, "changeControlEnabled").size();
        int revokes = records(ChangeType.GRANT_REVOKE, null).size();
        breakConfigFile();

        int code = submitSwitch("admin", "changeControlEnabled", false);

        assertTrue(code >= 400, "a configuration save that failed must answer an error, got HTTP " + code);
        assertTrue(BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "the in-memory switch must be unchanged when the save failed (#19)");
        assertEquals(toggles, records(ChangeType.CONFIG_TOGGLE, "changeControlEnabled").size(), "no CONFIG_TOGGLE record may be written for a change that was not saved (#19)");
        assertEquals(revokes, records(ChangeType.GRANT_REVOKE, null).size(), "no GRANT_REVOKE record may be written for a change that was not saved (#19)");
        assertTrue(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE), "the active grant must NOT be revoked when the save failed (#19)");
        assertTrue(GrantService.get().listActive().stream().anyMatch(g -> g.getId().equals(grant.getId())), "the grant must still be listed as active");
        assertEquals(200, postConfigXml("u1", "still-inside-window"), "the window must still confer: the switch never went off");
        assertEquals("still-inside-window", job.getDescription());
    }

    /** T-01-08 (guard of T-01-07): the same form submission without the fault toggles, records and revokes. */
    @Test
    @Tag("core")
    public void t_01_08_successfulSaveTurningChangeControlOffTogglesRecordsAndRevokes() throws Exception {
        Grant grant = changeControlOnWithActiveGrant();
        int revokes = records(ChangeType.GRANT_REVOKE, null).size();

        int code = submitSwitch("admin", "changeControlEnabled", false);

        assertTrue(code < 400, "the save must succeed, got HTTP " + code);
        assertFalse(BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "the switch must be off after a successful save");
        List<ChangeRecord> toggles = records(ChangeType.CONFIG_TOGGLE, "changeControlEnabled").stream()
                .filter(r -> "true -> false".equals(r.getDetail()))
                .collect(Collectors.toList());
        assertEquals(1, toggles.size(), "exactly one CONFIG_TOGGLE true -> false record must be written");
        assertEquals("admin", toggles.get(0).getUser());
        assertFalse(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE), "turning change control off must revoke the active window (P-15)");
        assertTrue(GrantService.get().listActive().stream().noneMatch(g -> g.getId().equals(grant.getId())), "the revoked grant must not be listed as active");
        assertEquals(revokes + 1, records(ChangeType.GRANT_REVOKE, null).size(), "one GRANT_REVOKE record per closed window");
        assertEquals(403, postConfigXml("u1", "after-switch-off"), "the window must no longer confer");
        assertEquals("base", job.getDescription());
    }

    /**
     * T-01-09: run control off, an approval-required job; the configuration file cannot be written;
     * admin ticks runControlEnabled -> error response, switch still off in memory, no CONFIG_TOGGLE
     * record, and a manual build still runs (run control never took effect).
     */
    @Test
    @Tag("core")
    public void t_01_09_failedSaveTurningRunControlOnAppliesNothing() throws Exception {
        setBatchControl(job, new BatchControlJobProperty(true));
        assertFalse(cfg.isRunControlEnabled(), "fixture: run control must start off");
        int toggles = records(ChangeType.CONFIG_TOGGLE, "runControlEnabled").size();
        breakConfigFile();

        int code = submitSwitch("admin", "runControlEnabled", true);

        assertTrue(code >= 400, "a configuration save that failed must answer an error, got HTTP " + code);
        assertFalse(BatchControlGlobalConfiguration.get().isRunControlEnabled(), "the in-memory switch must be unchanged when the save failed (#19)");
        assertEquals(toggles, records(ChangeType.CONFIG_TOGGLE, "runControlEnabled").size(), "no CONFIG_TOGGLE record may be written for a change that was not saved (#19)");
        j.assertBuildStatusSuccess(job.scheduleBuild2(0));
        assertEquals(1, job.getBuilds().size(), "run control never took effect, so a manual build must still run");
    }

    /** T-01-10 (guard of T-01-09): the same form submission without the fault toggles, records and blocks. */
    @Test
    @Tag("core")
    public void t_01_10_successfulSaveTurningRunControlOnTogglesRecordsAndBlocks() throws Exception {
        setBatchControl(job, new BatchControlJobProperty(true));
        int nextBuildNumber = job.getNextBuildNumber();

        int code = submitSwitch("admin", "runControlEnabled", true);

        assertTrue(code < 400, "the save must succeed, got HTTP " + code);
        assertTrue(BatchControlGlobalConfiguration.get().isRunControlEnabled(), "the switch must be on after a successful save");
        List<ChangeRecord> toggles = records(ChangeType.CONFIG_TOGGLE, "runControlEnabled").stream()
                .filter(r -> "false -> true".equals(r.getDetail()))
                .collect(Collectors.toList());
        assertEquals(1, toggles.size(), "exactly one CONFIG_TOGGLE false -> true record must be written");
        assertEquals("admin", toggles.get(0).getUser());

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        wc.getPage(new WebRequest(wc.createCrumbedUrl(job.getUrl() + "build"), HttpMethod.POST));
        j.waitUntilNoActivity();
        assertEquals(0, j.jenkins.getQueue().getItems().length, "the queue must be empty");
        assertEquals(nextBuildNumber, job.getNextBuildNumber(), "no build number may have been consumed");
        assertTrue(job.getBuilds().isEmpty(), "a manual build must now be blocked");
    }

    /**
     * T-01-11 (D-42, security-09 S-01): with the configuration file unwritable, a direct
     * {@code setChangeControlEnabled(true)} (script console, JCasC) does not throw, the switch is
     * on in memory and a CONFIG_TOGGLE record is written.
     */
    @Test
    @Tag("core")
    public void t_01_11_directSetterWithUnwritableFileAppliesAndDoesNotThrow() throws Exception {
        assertFalse(cfg.isChangeControlEnabled(), "fixture: change control must start off");
        int toggles = records(ChangeType.CONFIG_TOGGLE, "changeControlEnabled").size();
        breakConfigFile();

        BatchControlGlobalConfiguration.get().setChangeControlEnabled(true); // must not throw (D-42)

        assertTrue(BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "a direct setter applies the value in memory even if persisting fails (D-42)");
        List<ChangeRecord> added = records(ChangeType.CONFIG_TOGGLE, "changeControlEnabled");
        assertEquals(toggles + 1, added.size(), "the direct setter must write the toggle record (D-42)");
        assertEquals("false -> true", added.get(added.size() - 1).getDetail());
    }

    /**
     * T-01-12 (D-42, security-09 S-01): a JCasC apply of {@code unclassified: batchControl:} turning
     * change control on against the unwritable file does not throw, and the switch is on.
     */
    @Test
    public void t_01_12_cascApplyWithUnwritableFileDoesNotThrow() throws Exception {
        String exported = cascExport();
        assertTrue(exported.contains("batchControl:"), "fixture: the global configuration must use the JCasC symbol batchControl:\n" + exported);
        assertFalse(cfg.isChangeControlEnabled(), "fixture: change control must start off");
        int toggles = records(ChangeType.CONFIG_TOGGLE, "changeControlEnabled").size();
        breakConfigFile();

        io.jenkins.plugins.casc.ConfigurationAsCode.get().configureWith(io.jenkins.plugins.casc.yaml.YamlSource.of(
                new java.io.ByteArrayInputStream(("unclassified:\n  batchControl:\n    changeControlEnabled: true\n")
                        .getBytes(StandardCharsets.UTF_8)))); // must not throw: a boot-time apply may not abort startup (D-42)

        assertTrue(BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "a switch JCasC turns on must be on (D-42)");
        assertEquals(toggles + 1, records(ChangeType.CONFIG_TOGGLE, "changeControlEnabled").size(), "the JCasC toggle must be recorded");
    }

    /**
     * T-01-13 (security-09 S-02): both switches on; {@code setRunControlEnabled(false)} leaves
     * change control on in memory, writes no CONFIG_TOGGLE for changeControlEnabled, and a reload
     * from disk still reads change control on (run control off).
     */
    @Test
    public void t_01_13_togglingRunControlLeavesChangeControlUntouched() throws Exception {
        bothSwitchesOn();
        int changeToggles = records(ChangeType.CONFIG_TOGGLE, "changeControlEnabled").size();

        cfg.setRunControlEnabled(false);
        cfg.save();

        assertFalse(cfg.isRunControlEnabled(), "premise: run control must be off");
        assertTrue(cfg.isChangeControlEnabled(), "turning run control off must leave change control on in memory (S-02)");
        assertEquals(changeToggles, records(ChangeType.CONFIG_TOGGLE, "changeControlEnabled").size(), "no CONFIG_TOGGLE may be written for the untouched switch (S-02)");
        cfg.load();
        assertTrue(cfg.isChangeControlEnabled(), "on disk change control must still be on (S-02)");
        assertFalse(cfg.isRunControlEnabled(), "on disk run control must be off");
    }

    /** T-01-14 (S-02, mirror of T-01-13): {@code setChangeControlEnabled(false)} leaves run control on, in memory, in the records and on disk. */
    @Test
    public void t_01_14_togglingChangeControlLeavesRunControlUntouched() throws Exception {
        bothSwitchesOn();
        int runToggles = records(ChangeType.CONFIG_TOGGLE, "runControlEnabled").size();

        cfg.setChangeControlEnabled(false);
        cfg.save();

        assertFalse(cfg.isChangeControlEnabled(), "premise: change control must be off");
        assertTrue(cfg.isRunControlEnabled(), "turning change control off must leave run control on in memory (S-02)");
        assertEquals(runToggles, records(ChangeType.CONFIG_TOGGLE, "runControlEnabled").size(), "no CONFIG_TOGGLE may be written for the untouched switch (S-02)");
        cfg.load();
        assertTrue(cfg.isRunControlEnabled(), "on disk run control must still be on (S-02)");
        assertFalse(cfg.isChangeControlEnabled(), "on disk change control must be off");
    }

    /**
     * T-08-66 (D-63, backlog #85): admin turns change control off on the global form while u1 holds
     * an active window; the GRANT_REVOKE record for that window states "change control turned off",
     * and the grants screen (opened by admin once change control is back on) shows that reason; the
     * closed window's own Ended row gives the reason and names who turned change control off
     * ("by admin", D-63 revokedBy). Guard: an ordinary revoke by admin of another window leaves a
     * GRANT_REVOKE record without it, and its Ended row names admin without that reason.
     */
    @Test
    public void t_08_66_changeControlOffRevocationSaysSo() throws Exception {
        Grant grant = changeControlOnWithActiveGrant();
        Grant other = secondWindow();
        List<String> seen = records(ChangeType.GRANT_REVOKE, null).stream().map(ChangeRecord::getId).collect(Collectors.toList());
        try (ACLContext ignored = as("admin")) {
            GrantService.get().revoke(other.getId());
        }
        List<ChangeRecord> manual = records(ChangeType.GRANT_REVOKE, null).stream()
                .filter(r -> !seen.contains(r.getId())).collect(Collectors.toList());
        assertEquals(1, manual.size(), "premise: the ordinary revoke leaves one GRANT_REVOKE record");
        assertFalse(String.valueOf(manual.get(0).getDetail()).toLowerCase(java.util.Locale.ROOT).contains("change control turned off"),
                "guard: an ordinary revoke must not claim change control was turned off: " + manual.get(0).getDetail());
        manual.forEach(r -> seen.add(r.getId()));

        int code = submitSwitch("admin", "changeControlEnabled", false);
        assertTrue(code < 400, "the save must succeed, got HTTP " + code);
        assertFalse(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE), "premise: the window was closed (P-15)");
        List<ChangeRecord> bySwitch = records(ChangeType.GRANT_REVOKE, null).stream()
                .filter(r -> !seen.contains(r.getId())).collect(Collectors.toList());
        assertEquals(1, bySwitch.size(), "one GRANT_REVOKE record for the window closed by the switch, got " + bySwitch.size());
        String detail = String.valueOf(bySwitch.get(0).getDetail());
        assertTrue(detail.toLowerCase(java.util.Locale.ROOT).contains("change control turned off"),
                "the GRANT_REVOKE record must say the window was revoked because change control was turned off: " + detail);

        cfg.setChangeControlEnabled(true);
        cfg.save();
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        Page grants = wc.goTo("batch-control/grants/");
        assertEquals(200, grants.getWebResponse().getStatusCode(), "premise: admin opens the grants screen");
        String text = ((org.htmlunit.html.HtmlPage) grants).asNormalizedText();
        assertTrue(text.contains(grant.getId()), "premise: the closed window " + grant.getId() + " is listed");
        assertTrue(text.toLowerCase(java.util.Locale.ROOT).contains("change control turned off"),
                "the grants screen must show that the window was revoked because change control was turned off");

        // the same Ended row names the reason and who turned change control off (D-63 revokedBy; r9/s85.py)
        org.htmlunit.html.HtmlPage list = (org.htmlunit.html.HtmlPage) grants;
        String closed = endedRowText(list, grant.getId());
        assertTrue(closed.toLowerCase(java.util.Locale.ROOT).contains("change control turned off"),
                "the closed window's own Ended row must give the reason: " + closed);
        assertTrue(BY_ADMIN.matcher(closed).find(), "the closed window's own Ended row must name who turned change control off"
                + " (\"by admin\"): " + closed);
        // guard: the ordinary revoke's row names its actor without the switch's reason, so the reason is per row
        String manualRow = endedRowText(list, other.getId());
        assertTrue(BY_ADMIN.matcher(manualRow).find(), "guard: the ordinarily revoked window's row names admin: " + manualRow);
        assertFalse(manualRow.toLowerCase(java.util.Locale.ROOT).contains("change control turned off"),
                "guard: the ordinarily revoked window's row must not claim change control was turned off: " + manualRow);
    }

    private static final java.util.regex.Pattern BY_ADMIN = java.util.regex.Pattern.compile("\\bby admin\\b");

    /**
     * The text of the one row of the grants list's Ended table that carries {@code grantId} (as text
     * or in a link), whitespace collapsed.
     */
    private static String endedRowText(org.htmlunit.html.HtmlPage list, String grantId) {
        List<String> rows = new java.util.ArrayList<>();
        for (org.htmlunit.html.DomNode table : list.querySelectorAll(WindowStateFixtures.ENDED_LIST)) {
            for (org.htmlunit.html.DomNode row : table.querySelectorAll("tr")) {
                if (row.querySelector("tr") == null && row.asXml().contains(grantId)) {
                    rows.add(row.asNormalizedText().replaceAll("\\s+", " "));
                }
            }
        }
        assertEquals(1, rows.size(), "the Ended table must hold one row for the window " + grantId + ", got " + rows + ": "
                + ApproverFormFixtures.excerpt(list.asNormalizedText()));
        return rows.get(0);
    }

    /** A second approved window (CONFIGURE on job other-x) for u1. */
    private Grant secondWindow() throws Exception {
        j.createFreeStyleProject("other-x");
        GrantRequest request;
        try (ACLContext ignored = as("u1")) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "other-x"),
                    Arrays.asList(GrantAction.CONFIGURE), 60, "second window", "a1");
        }
        try (ACLContext ignored = as("a1")) {
            Grant grant = GrantRequestService.get().approve(request.getId(), "ok");
            assertNotNull(grant, "fixture: the second grant must have been issued");
            return grant;
        }
    }

    // ---------------------------------------------------------------- helpers

    private void bothSwitchesOn() {
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.save();
        cfg.load();
        assertTrue(cfg.isRunControlEnabled() && cfg.isChangeControlEnabled(), "fixture: both switches must be on, on disk");
    }

    private static String cascExport() throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        io.jenkins.plugins.casc.ConfigurationAsCode.get().export(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Turns change control on (saved) and gives u1 an approved 60-minute CONFIGURE window on batch-x. */
    private Grant changeControlOnWithActiveGrant() throws Exception {
        cfg.setChangeControlEnabled(true);
        cfg.save();
        assertTrue(cfg.isChangeControlEnabled(), "fixture: change control must really be on before it is turned off");
        GrantRequest request;
        try (ACLContext ignored = as("u1")) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "batch-x"),
                    Arrays.asList(GrantAction.CONFIGURE), 60, "scheduled maintenance", "a1");
        }
        Grant grant;
        try (ACLContext ignored = as("a1")) {
            grant = GrantRequestService.get().approve(request.getId(), "ok");
        }
        assertNotNull(grant, "fixture: the grant must have been issued");
        assertTrue(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE), "fixture: the window must be active");
        return grant;
    }

    /**
     * Replaces the global configuration's standard file with a non-empty directory, then proves
     * the fault with core's XmlFile: any write there must throw.
     */
    private void breakConfigFile() throws IOException {
        File file = new File(j.jenkins.getRootDir(), cfg.getId() + ".xml");
        assertTrue(file.isFile(), "fixture: the saved configuration must live at " + file + " (ARCHITECTURE section 5)");
        Files.delete(file.toPath());
        assertTrue(file.mkdir(), "fixture: could not create the blocking directory " + file);
        Files.writeString(new File(file, "keep").toPath(), "blocks the atomic replace", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> new XmlFile(file).write("probe"), "fixture: a write to " + file + " must now fail");
    }

    /** Opens the global configure page as {@code userId}, sets one switch checkbox and submits; returns the HTTP status. */
    private int submitSwitch(String userId, String field, boolean value) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        HtmlForm form = wc.goTo("configure").getFormByName("config");
        List<HtmlCheckBoxInput> boxes = form.getByXPath(".//input[@type='checkbox' and (@name='_." + field
                + "' or @name='" + field + "')]");
        assertEquals(1, boxes.size(), "fixture: the global form must render exactly one " + field + " checkbox");
        boxes.get(0).setChecked(value);
        Page result = j.submit(form);
        return result.getWebResponse().getStatusCode();
    }

    private int postConfigXml(String userId, String newDescription) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        String xml = job.getConfigFile().asString()
                .replace("<description>" + job.getDescription() + "</description>",
                        "<description>" + newDescription + "</description>");
        WebRequest request = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private static List<ChangeRecord> records(ChangeType type, String target) {
        return FileStore.get().listChangeRecords(YearMonth.now(BatchClock.clock())).stream()
                .filter(r -> r.getType() == type)
                .filter(r -> target == null || target.equals(r.getTarget()))
                .collect(Collectors.toList());
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
