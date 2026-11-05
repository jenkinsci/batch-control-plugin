package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8, the human-facing half of a change window: "승인되면 그 시간 동안 본인 계정으로 직접
 * 작업하며" — during the window the requester works on the target job with their own account.
 * Matrix rows T-08-14 (the configure screen opens), T-08-15 (the config XML can be read),
 * T-08-16 (the ACL reports the permissions core derives from Item/CONFIGURE), T-08-17 (all of
 * that reverts when the window closes) and T-08-18 (the implication handling may not widen the
 * scope or the action set).
 *
 * Why this class exists (matrix note 31): T-08-01 asserts only the SAVE half of a change window
 * (POST config.xml -> 200, persisted). Jenkins core gates *reading* a job's configuration —
 * both the /configure screen and GET config.xml — on {@code Item.EXTENDED_READ}, a permission
 * core ships disabled and treats as implied by {@code Item.CONFIGURE}. A grant that answers only
 * the exact permission it was asked to confer therefore passes T-08-01 while leaving the window
 * unusable in a browser: the user can save a configuration they cannot open.
 *
 * Time never passes for real: BatchClock is fixed and moved (matrix note 1).
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class GrantConfigureAccessTest {

    private static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");
    private static final int WINDOW_MINUTES = 30;

    private JenkinsRule j;

    private FreeStyleProject jobX; // the grant's scope
    private FreeStyleProject jobY; // outside the grant's scope

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());

        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1", "m1", "c1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        strategy.add(BatchControlPermissions.MANAGE, PermissionEntry.user("m1"));
        // c1 is the control: a real Item/Configure straight from the delegate, no grant involved.
        strategy.add(Item.CONFIGURE, PermissionEntry.user("c1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        jobX = j.createFreeStyleProject("batch-x");
        jobX.setDescription("base-x");
        jobY = j.createFreeStyleProject("batch-y");
        jobY.setDescription("base-y");

        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-08-14: inside the window the requester can OPEN the target job's configure screen —
     * GET job/batch-x/configure is 200 and carries the item config form.
     */
    @Test
    public void t_08_14_configureScreenOpensInsideWindow() throws Exception {
        JenkinsRule.WebClient wc = webClient().login("u1");
        assertEquals(403, status(get(wc, jobX.getUrl() + "configure")), "before the grant the configure screen must be denied (Item/Read only)");

        grantConfigureOnJobX();

        Page page = get(wc, jobX.getUrl() + "configure");
        assertEquals(200, status(page), "with an active CONFIGURE grant the requester must be able to OPEN the "
                + "configure screen of the scoped job");
        assertTrue(page instanceof HtmlPage, "the configure screen must be an HTML page, got " + page.getClass().getSimpleName());

        HtmlPage html = (HtmlPage) page;
        HtmlForm configForm = configForm(html);
        assertNotNull(configForm, "the configure screen must render the item config form (action configSubmit); "
                + "forms on the page: " + formActions(html));
        assertNotNull(configForm.getTextAreaByName("description"), "the config form must carry the job's own fields");
    }

    /**
     * T-08-15: inside the window the requester can READ the target job's configuration —
     * GET job/batch-x/config.xml is 200 and returns the job's XML.
     */
    @Test
    public void t_08_15_configXmlIsReadableInsideWindow() throws Exception {
        JenkinsRule.WebClient wc = webClient().login("u1");
        assertEquals(403, status(get(wc, jobX.getUrl() + "config.xml")), "before the grant the config XML must be denied (Item/Read only)");

        grantConfigureOnJobX();

        Page page = get(wc, jobX.getUrl() + "config.xml");
        assertEquals(200, status(page), "with an active CONFIGURE grant the requester must be able to READ config.xml");
        String body = page.getWebResponse().getContentAsString();
        assertTrue(body.contains("<project") && body.contains("base-x"), "the response must be the job's configuration XML, got: " + excerpt(body));
    }

    /**
     * T-08-16: an active CONFIGURE grant must make the ACL report the permissions Jenkins core
     * derives from Item/CONFIGURE, not only the literal granted permission. Item/EXTENDED_READ
     * (the read-only view of a job's configuration) is disabled by default in core and implied
     * by Item/CONFIGURE, so it must answer true inside the window.
     */
    @Test
    public void t_08_16_grantReportsPermissionsImpliedByConfigure() throws Exception {
        assertFalse(hasPermissionAs("u1", jobX, Item.EXTENDED_READ), "before the grant u1 must not hold EXTENDED_READ");

        // Control: core really does derive EXTENDED_READ from a plain Item/Configure. If this
        // fixture assertion ever fails, this row can no longer measure anything and says so
        // loudly instead of passing silently.
        assertTrue(hasPermissionAs("c1", jobX, Item.EXTENDED_READ), "fixture: a holder of a direct Item/Configure must also hold EXTENDED_READ "
                + "(core treats it as implied by CONFIGURE)");

        grantConfigureOnJobX();

        assertTrue(hasPermissionAs("u1", jobX, Item.CONFIGURE), "an active CONFIGURE grant must confer Item/Configure on the scoped job");
        assertTrue(hasPermissionAs("u1", jobX, Item.EXTENDED_READ), "an active CONFIGURE grant must also report Item/EXTENDED_READ, which core "
                + "leaves disabled and treats as implied by Item/CONFIGURE — without it the "
                + "requester cannot open or read the configuration they are allowed to save");
    }

    /**
     * T-08-17: when the window closes every read path reverts. Opening the configure screen,
     * reading config.xml and the EXTENDED_READ/CONFIGURE verdicts must all go back to denied —
     * the implied-permission handling may not leak a permanent permission.
     */
    @Test
    @Tag("core")
    public void t_08_17_readPathsRevertWhenWindowCloses() throws Exception {
        grantConfigureOnJobX();

        JenkinsRule.WebClient inside = webClient().login("u1");
        assertEquals(200, status(get(inside, jobX.getUrl() + "configure")), "fixture: the window must be open before it is closed");

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(WINDOW_MINUTES + 1)), ZoneOffset.UTC));

        JenkinsRule.WebClient wc = webClient().login("u1");
        assertEquals(403, status(get(wc, jobX.getUrl() + "configure")), "past the expiry the configure screen must be denied again");
        assertEquals(403, status(get(wc, jobX.getUrl() + "config.xml")), "past the expiry config.xml must be denied again");
        assertFalse(hasPermissionAs("u1", jobX, Item.EXTENDED_READ), "past the expiry Item/EXTENDED_READ must be false again (no permanent leak "
                + "through the implication handling)");
        assertFalse(hasPermissionAs("u1", jobX, Item.CONFIGURE), "past the expiry Item/Configure must be false again");
        assertFalse(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE));
        assertEquals("base-x", jobX.getDescription(), "the job must be untouched");
    }

    /**
     * T-08-18 (over-grant guard for T-08-14..17): reporting the permissions implied by
     * Item/CONFIGURE must not widen the grant. Another job's configure screen and config XML
     * stay denied, and DELETE — an action this grant does not carry — stays denied too.
     */
    @Test
    @Tag("core")
    public void t_08_18_impliedPermissionsDoNotWidenScopeOrActions() throws Exception {
        grantConfigureOnJobX();

        JenkinsRule.WebClient wc = webClient().login("u1");

        // scope boundary: the grant names batch-x only
        assertEquals(403, status(get(wc, jobY.getUrl() + "configure")), "a window on one job must not open another job's configure screen");
        assertEquals(403, status(get(wc, jobY.getUrl() + "config.xml")), "a window on one job must not open another job's config XML");
        assertFalse(hasPermissionAs("u1", jobY, Item.EXTENDED_READ), "EXTENDED_READ must not leak to a job outside the scope");
        assertFalse(hasPermissionAs("u1", jobY, Item.CONFIGURE), "Item/Configure must not leak to a job outside the scope");

        // action boundary: the grant carries CONFIGURE only
        assertFalse(hasPermissionAs("u1", jobX, Item.DELETE), "a CONFIGURE-only grant must not report Item/Delete");
        assertFalse(GrantService.get().hasActiveGrant("u1", "batch-x", Item.DELETE));
        Page deletePage = wc.getPage(new WebRequest(
                wc.createCrumbedUrl(jobX.getUrl() + "doDelete"), HttpMethod.POST));
        assertTrue(status(deletePage) >= 400, "a CONFIGURE-only grant must not open the delete path, got HTTP "
                + status(deletePage));
        assertNotNull(j.jenkins.getItemByFullName("batch-x"), "the job must survive the denied delete");
        assertEquals("base-y", jobY.getDescription(), "job Y must be untouched");
    }

    /**
     * T-08-292 (SPEC 6 D-34: to run a locked job "turn the switch off in the job configuration; that
     * change is change-controlled, so it goes through a permission window and the records"; SPEC 3
     * job property; #31, e2e script rest-grant-configure.sh #5; T-CFG-15 checks the administrator's
     * form only): with run control on and {@code batch-x} carrying approvalRequired and blockTimer on
     * and blockUpstream off, u1 is refused the configure screen before the window (premise); inside
     * a CONFIGURE window the screen's config form carries the Batch Control section ("Require approval
     * to run"): the approvalRequired, blockTimer and blockUpstream checkboxes showing the stored values
     * (on, on, off) and the job approver and allowed upstream fields. u1 unticks blockTimer and saves:
     * the save is accepted and the job carries exactly one Batch Control property with blockTimer off
     * and approvalRequired still on, so the section is bound and not only drawn.
     */
    @Test
    public void t_08_292_windowHoldersConfigureScreenCarriesTheBatchControlSection() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.save();
        BatchControlJobProperty stored = new BatchControlJobProperty(true);
        stored.setBlockTimer(true);
        stored.setBlockUpstream(false);
        BatchControlFixtures.setBatchControl(jobX, stored);

        JenkinsRule.WebClient wc = webClient().login("u1");
        assertEquals(403, status(get(wc, jobX.getUrl() + "configure")), "premise: before the window u1 may not open the configure screen");
        grantConfigureOnJobX();

        Page page = get(wc, jobX.getUrl() + "configure");
        assertEquals(200, status(page), "inside the window u1 opens the configure screen");
        HtmlForm form = configForm((HtmlPage) page);
        assertNotNull(form, "the configure screen must carry the config form: " + formActions((HtmlPage) page));
        assertTrue(form.asNormalizedText().contains("Require approval to run"), "the window holder's config form must carry the"
                + " Batch Control section ('Require approval to run'): " + excerpt(form.asNormalizedText()));
        Map<String, Boolean> expected = new LinkedHashMap<>();
        expected.put("approvalRequired", true);
        expected.put("blockTimer", true);
        expected.put("blockUpstream", false);
        for (Map.Entry<String, Boolean> e : expected.entrySet()) {
            HtmlCheckBoxInput box = checkbox(form, e.getKey());
            assertNotNull(box, "the window holder's config form must offer the " + e.getKey() + " checkbox");
            assertEquals(e.getValue(), box.isChecked(), "the " + e.getKey() + " checkbox must show the stored value");
        }
        for (String field : new String[] {"jobApproversText", "allowedUpstreamJobsText"}) {
            assertTrue(UsabilityFixtures.hasField(form, field), "the window holder's config form must offer the " + field + " field");
        }

        checkbox(form, "blockTimer").setChecked(false);
        Page saved = j.submit(form);
        assertTrue(status(saved) < 400, "the window holder's save must be accepted, got HTTP " + status(saved));
        FreeStyleProject reloaded = j.jenkins.getItemByFullName("batch-x", FreeStyleProject.class);
        assertEquals(1, reloaded.getProperties().values().stream().filter(p -> p instanceof BatchControlJobProperty).count(),
                "the saved job must carry exactly one Batch Control property");
        BatchControlJobProperty after = reloaded.getProperty(BatchControlJobProperty.class);
        assertFalse(after.isBlockTimer(), "the unticked blockTimer must be saved");
        assertTrue(after.isApprovalRequired(), "approvalRequired must stay on");
        assertFalse(after.isBlockUpstream(), "blockUpstream must stay off");
    }

    /**
     * Fixture control for T-08-14/15: a holder of a plain Item/Configure from the delegate can
     * open and read the configuration. This pins that the assertions above are reachable in this
     * Jenkins version at all, so a failure of T-08-14/15 is a grant problem and not a broken
     * expectation about core's configure screen.
     */
    @Test
    public void directConfigurePermissionOpensAndReadsConfiguration() throws Exception {
        JenkinsRule.WebClient wc = webClient().login("c1");

        Page screen = get(wc, jobX.getUrl() + "configure");
        assertEquals(200, status(screen), "a direct Item/Configure holder must be able to open the configure screen");
        assertNotNull(configForm((HtmlPage) screen), "the configure screen must render the item config form; forms on the page: "
                + formActions((HtmlPage) screen));

        Page xml = get(wc, jobX.getUrl() + "config.xml");
        assertEquals(200, status(xml), "a direct Item/Configure holder must be able to read config.xml");
        assertTrue(xml.getWebResponse().getContentAsString().contains("base-x"));
    }

    // ---------------------------------------------------------------- helpers

    /** The form's checkbox bound to {@code field} (as T-CFG-15 finds it). */
    private static HtmlCheckBoxInput checkbox(HtmlForm form, String field) {
        for (DomElement element : form.getElementsByTagName("input")) {
            String name = element.getAttribute("name");
            if (element instanceof HtmlCheckBoxInput box && (name.equals("_." + field) || name.endsWith("." + field) || name.equals(field))) {
                return box;
            }
        }
        return null;
    }

    private JenkinsRule.WebClient webClient() {
        return j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
    }

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private boolean hasPermissionAs(String userId, FreeStyleProject target, Permission permission) {
        try (ACLContext ignored = as(userId)) {
            return target.hasPermission(permission);
        }
    }

    /** Creates a CONFIGURE grant request on batch-x as u1 and approves it as a1. */
    private Grant grantConfigureOnJobX() {
        GrantRequest request;
        try (ACLContext ignored = as("u1")) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "batch-x"),
                    Arrays.asList(GrantAction.CONFIGURE), WINDOW_MINUTES,
                    "scheduled maintenance", "a1");
        }
        Grant grant;
        try (ACLContext ignored = as("a1")) {
            grant = GrantRequestService.get().approve(request.getId(), "ok");
        }
        assertNotNull(grant, "the approval must produce a grant");
        assertTrue(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE), "fixture: the grant must be active for CONFIGURE on the scoped job");
        return grant;
    }

    private Page get(JenkinsRule.WebClient wc, String relative) throws Exception {
        return wc.getPage(new WebRequest(new URL(j.getURL(), relative), HttpMethod.GET));
    }

    private static int status(Page page) {
        return page.getWebResponse().getStatusCode();
    }

    private static HtmlForm configForm(HtmlPage page) {
        return page.getForms().stream()
                .filter(form -> form.getActionAttribute().contains("configSubmit"))
                .findFirst()
                .orElse(null);
    }

    private static List<String> formActions(HtmlPage page) {
        return page.getForms().stream()
                .map(HtmlForm::getActionAttribute)
                .collect(Collectors.toList());
    }

    private static String excerpt(String body) {
        return body.length() <= 400 ? body : body.substring(0, 400) + "...";
    }
}
