package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.Action;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.UnprotectedRootAction;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.action.ActiveGrantsSection;
import io.jenkins.plugins.batchcontrol.action.BatchControlRootAction;
import io.jenkins.plugins.batchcontrol.action.JobActivationForm;
import io.jenkins.plugins.batchcontrol.action.JobRequestAction;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import jenkins.model.Jenkins;
import jenkins.model.experimentalflags.NewJobPageUserExperimentalFlag;
import jenkins.model.experimentalflags.UserExperimentalFlagsProperty;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #132: the view-level permission check of four Batch Control views must refuse a user who
 * may not use the view, with core's ordinary 403, when the view is reached. Matrix rows T-02-206,
 * T-02-207, T-08-297, T-08-298 (note 372).
 *
 * <p>Each of these views has two layers: the URL gate in front of it, and a check in the view
 * itself (defence in depth). Through the real URLs the gate refuses the user first, so the view's
 * own check is never reached and a check that asks no ACL goes unnoticed. The test-only root
 * action {@link Exposer} ({@code /bc-issue132/}) hands Stapler the plugin's model objects directly,
 * so the view's own check is the only check between the user and the page:
 * <ul>
 *   <li>{@code bc-issue132/request/<job>/} - the job's {@link JobRequestAction} (Request Run);</li>
 *   <li>{@code bc-issue132/activation/<job>/} - the job's {@link JobActivationForm};</li>
 *   <li>{@code bc-issue132/grants-active/} - the {@link ActiveGrantsSection};</li>
 *   <li>{@code bc-issue132/grant/<grantId>/} - an {@link ActiveGrantsSection.Item}.</li>
 * </ul>
 *
 * <p>Every refusal is paired with a user whom the same exposer serves (200 with the request form,
 * or the 302 of the grants views), so a refusal cannot come from the exposer or the fixture. The
 * activation view's classic branch, whose check already refuses, is the control: through the
 * exposer it refuses the same user with the same core page.
 *
 * <p>Users: {@code u1} holds Overall/Read, Item/Read, Item/Build and BatchControl/Request (allowed
 * on the job views, refused on the grants views); {@code a1} holds Overall/Read, Item/Read,
 * Item/Build and BatchControl/Approve (refused on the job views, allowed on the grants views; it
 * differs from u1 on the job only by Request); {@code g1} holds Overall/Read, Item/Read and
 * BatchControl/RequestGrant (allowed on the grants views).
 *
 * <p>Written from docs/SPEC.md items 2 and 8, docs/ARCHITECTURE.md (package tree) and the issue #132
 * hand-over contract (class names, how each model object is obtained, each view's permission); no
 * src/main knowledge.
 */
@WithJenkins
public class ViewPermissionCheckTest {

    private static final String EXPOSER = "bc-issue132";

    private JenkinsRule j;

    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST_GRANT).everywhere().to("g1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        assertTrue(BatchControlGlobalConfiguration.get().isRunControlEnabled(), "fixture: run control must be on");

        job = j.createFreeStyleProject("batch-x");
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        assertTrue(setBatchControl(job, property).isApprovalRequired(), "fixture: batch-x must require approval");
        assertFalse(isActivated(job), "premise: a job created after the first start is not activated (the activation form applies)");
    }

    /**
     * T-02-206 (P0): the Request Run view ({@link JobRequestAction}, view permission
     * BatchControl/Request on the job) reached past its URL gate. Guards: u1 (Request) is shown the
     * request form through the exposer, on the classic page and on the new job page. Then a1
     * (Approve and Item/Build, no Request) GETs the same view on the classic page and with the new
     * job page on: each answer is core's 403 naming a1 as missing a permission.
     */
    @Test
    public void t_02_206_requestRunViewRefusesAUserWithoutRequestPastItsUrlGate() throws Exception {
        assertNotNull(job.getAction(JobRequestAction.class), "fixture: batch-x must carry the Request Run action");
        String path = EXPOSER + "/request/" + job.getName() + "/";

        setNewJobPage("u1", false);
        assertClassicPage(assertFormShown("u1", path, "Request Run view, classic page"), "Request Run view");
        setNewJobPage("u1", true);
        assertNewJobPage(assertFormShown("u1", path, "Request Run view, new job page"), "Request Run view");

        setNewJobPage("a1", false);
        WebResponse classic = get("a1", path);
        setNewJobPage("a1", true);
        WebResponse newJobPage = get("a1", path);
        assertAll(
                () -> assertCoreRefusal(classic, "a1", "the Request Run view on the classic page"),
                () -> assertCoreRefusal(newJobPage, "a1", "the Request Run view on the new job page"));
    }

    /**
     * T-02-207 (P0): the activation view ({@link JobActivationForm}, view permission
     * BatchControl/Request on the item) reached past its URL gate. Guards: u1 is shown the
     * activation form through the exposer on both pages. Control: with the new job page off (the
     * classic branch, whose layout carries the permission), a1 gets core's 403 through the exposer.
     * With the new job page on (the job sub-page branch), a1 must get the same core 403.
     */
    @Test
    public void t_02_207_activationViewRefusesAUserWithoutRequestOnTheNewJobPage() throws Exception {
        assertNotNull(job.getAction(JobActivationForm.class), "fixture: batch-x must carry the activation action");
        String path = EXPOSER + "/activation/" + job.getName() + "/";

        setNewJobPage("u1", false);
        assertClassicPage(assertFormShown("u1", path, "activation view, classic page"), "activation view");
        setNewJobPage("u1", true);
        assertNewJobPage(assertFormShown("u1", path, "activation view, new job page"), "activation view");

        setNewJobPage("a1", false);
        assertCoreRefusal(get("a1", path), "a1", "control: the activation view on the classic page");

        setNewJobPage("a1", true);
        assertCoreRefusal(get("a1", path), "a1", "the activation view on the new job page");
    }

    /**
     * T-08-297 (P0): the active grants view ({@link ActiveGrantsSection}, view permission any of
     * BatchControl/RequestGrant, Approve, Manage) reached past its URL gate. Guards: g1
     * (RequestGrant) and a1 (Approve) are redirected (302) by the view through the exposer. u1
     * (Request, none of the three) must get core's 403.
     */
    @Test
    public void t_08_297_activeGrantsViewRefusesAUserWithoutAGrantsPermission() throws Exception {
        enableChangeControl();
        assertNotNull(activeGrantsSection(), "fixture: the grants screen must have its active grants section");
        String path = EXPOSER + "/grants-active/";

        assertRedirect("g1", path, "guard: the active grants view for a RequestGrant holder");
        assertRedirect("a1", path, "guard: the active grants view for an Approve holder");

        assertCoreRefusal(get("u1", path), "u1", "the active grants view");
    }

    /**
     * T-08-298 (P0): the view of one active grant ({@link ActiveGrantsSection.Item}, same view
     * permission as the section) reached past its URL gate. Fixture: g1's approved CONFIGURE window
     * on batch-x, active. Guards: g1 and a1 are redirected (302) by the item's view through the
     * exposer. u1 must get core's 403.
     */
    @Test
    public void t_08_298_activeGrantItemViewRefusesAUserWithoutAGrantsPermission() throws Exception {
        enableChangeControl();
        GrantRequest request;
        try (ACLContext ignored = ACL.as2(User.getById("g1", true).impersonate2())) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, job.getFullName()),
                    Arrays.asList(GrantAction.CONFIGURE), 30, "issue 132 fixture", "a1");
        }
        Grant grant;
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            grant = GrantRequestService.get().approve(request.getId(), "ok");
        }
        assertNotNull(grant, "fixture: the approval must issue a window");
        assertNotNull(WindowStateFixtures.active(grant.getId()), "fixture: g1's window must be active");
        assertTrue(activeGrantsSection().getDynamic(grant.getId()) instanceof ActiveGrantsSection.Item,
                "fixture: the active grants section must resolve the window's id to its item");
        String path = EXPOSER + "/grant/" + grant.getId() + "/";

        assertRedirect("g1", path, "guard: the active grant's view for its holder (RequestGrant)");
        assertRedirect("a1", path, "guard: the active grant's view for an Approve holder");

        assertCoreRefusal(get("u1", path), "u1", "the active grant's view");
    }

    // ---------------------------------------------------------------- exposer

    /**
     * Test-only URL {@code /bc-issue132/} that hands Stapler the plugin's model objects directly,
     * past the URL gates that normally stand in front of their views.
     */
    @TestExtension
    public static class Exposer implements UnprotectedRootAction {

        @Override
        public String getIconFileName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getUrlName() {
            return EXPOSER;
        }

        public Object getDynamic(String token) {
            switch (token) {
                case "request":
                    return new Picker(name -> jobAction(name, JobRequestAction.class));
                case "activation":
                    return new Picker(name -> jobAction(name, JobActivationForm.class));
                case "grants-active":
                    return asSystem(ViewPermissionCheckTest::activeGrantsSection);
                case "grant":
                    return new Picker(id -> asSystem(() -> activeGrantsSection().getDynamic(id)));
                default:
                    return null;
            }
        }

        private static Object jobAction(String name, Class<? extends Action> type) {
            return asSystem(() -> {
                Job<?, ?> target = Jenkins.get().getItemByFullName(name, Job.class);
                return target == null ? null : target.getAction(type);
            });
        }

        /**
         * Looks the object up as SYSTEM: a lookup made as the requesting user could meet the same
         * gate this exposer exists to step past (an action absent for the user, a section getter
         * that checks the user). The view then renders as the requesting user, which is what the
         * rows assert on.
         */
        private static Object asSystem(Supplier<Object> lookup) {
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                return lookup.get();
            }
        }
    }

    /** One more URL segment: {@code <name>/} resolves through {@code lookup}. */
    public static final class Picker {

        private final Function<String, Object> lookup;

        Picker(Function<String, Object> lookup) {
            this.lookup = lookup;
        }

        public Object getDynamic(String name) {
            return lookup.apply(name);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static ActiveGrantsSection activeGrantsSection() {
        return ExtensionList.lookupSingleton(BatchControlRootAction.class).getGrants().getActive();
    }

    private void enableChangeControl() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.save();
        assertTrue(BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "fixture: change control must be on");
    }

    /** Sets core's per-user new job page flag and checks that core reads it back. */
    private static void setNewJobPage(String userId, boolean on) throws Exception {
        NewJobPageUserExperimentalFlag flag = ExtensionList.lookupSingleton(NewJobPageUserExperimentalFlag.class);
        User user = User.getById(userId, true);
        user.addProperty(new UserExperimentalFlagsProperty(Map.of(flag.getFlagKey(), Boolean.toString(on))));
        assertEquals(on, flag.getFlagValue(user), "premise: core must read " + userId + "'s new job page flag as " + on);
    }

    private JenkinsRule.WebClient client(String userId) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        wc.login(userId); // with redirects on: the login form itself redirects
        wc.getOptions().setRedirectEnabled(false);
        return wc;
    }

    private Page page(String userId, String path) throws Exception {
        return client(userId).getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET));
    }

    private WebResponse get(String userId, String path) throws Exception {
        return page(userId, path).getWebResponse();
    }

    /** Core's ordinary refusal: HTTP 403 and its access-denied page naming the user as missing a permission. */
    private static void assertCoreRefusal(WebResponse answer, String userId, String what) {
        String body = answer.getContentAsString();
        assertEquals(403, answer.getStatusCode(), what + ": " + userId + " must be refused with 403 by the view's own"
                + " permission check, got HTTP " + answer.getStatusCode() + " " + location(answer) + ": " + excerpt(body));
        assertTrue(body.contains(userId + " is missing"), what + ": the refusal must be core's access-denied page"
                + " naming " + userId + " as missing a permission: " + excerpt(body));
    }

    private void assertRedirect(String userId, String path, String what) throws Exception {
        WebResponse answer = get(userId, path);
        assertEquals(302, answer.getStatusCode(), what + ": " + userId + " GET /" + path + " must be served by the view"
                + " (its redirect, 302) through the exposer, got HTTP " + answer.getStatusCode() + ": "
                + excerpt(answer.getContentAsString()));
        assertNotNull(answer.getResponseHeaderValue("Location"), what + ": the redirect must carry a Location");
    }

    /** The view answers 200 to {@code userId} and carries its request form (a form posting to submit, with a reason). */
    private HtmlPage assertFormShown(String userId, String path, String what) throws Exception {
        Page answer = page(userId, path);
        WebResponse response = answer.getWebResponse();
        assertEquals(200, response.getStatusCode(), "guard (" + what + "): " + userId + " GET /" + path + " must be served"
                + " the view through the exposer, got HTTP " + response.getStatusCode() + " " + location(response) + ": "
                + excerpt(response.getContentAsString()));
        assertTrue(answer instanceof HtmlPage, "guard (" + what + "): the view must be an HTML page, got "
                + response.getContentType());
        HtmlPage html = (HtmlPage) answer;
        List<HtmlForm> forms = new ArrayList<>();
        for (HtmlForm form : UsabilityFixtures.formsEndingWith(html, "/submit")) {
            if (UsabilityFixtures.hasField(form, "reason")) {
                forms.add(form);
            }
        }
        assertFalse(forms.isEmpty(), "guard (" + what + "): the view must carry its request form (posting to submit,"
                + " with a reason field); form actions: " + UsabilityFixtures.formActions(html));
        return html;
    }

    private static void assertClassicPage(HtmlPage page, String what) {
        assertNotNull(page.querySelector("#side-panel"), "premise: with the new job page off, the " + what
                + " must render as a classic page (side panel) through the exposer");
    }

    private static void assertNewJobPage(HtmlPage page, String what) {
        assertNull(page.querySelector("#side-panel"), "premise: with the new job page on, the " + what
                + " must not render the classic side panel through the exposer");
        assertNotNull(page.querySelector("div.app-build-bar"), "premise: with the new job page on, the " + what
                + " must render as a new job page sub-page (app bar) through the exposer");
    }

    private static String location(WebResponse response) {
        String location = response.getResponseHeaderValue("Location");
        return location == null ? "" : "(Location " + location + ")";
    }
}
