package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server checks of the approver choices on the request forms and of the decision form that only the
 * retired legacy e2e drivers made (issue #31, note 370): the grant request form, its dialog and the
 * activation form when no approver is configured (T-03-96, gap X-01, retired drivers browser/b1.mjs #17
 * and browser/audit/b1b.mjs #5), the administrator's own Request Run form under
 * {@code allowAdminSelfApproval} (T-03-97, gap X-29, browser/audit/b1b.mjs #7) and a designated approver
 * whose id is spelled in another letter case (T-03-98, gap X-28, browser/audit/b1b.mjs #2).
 *
 * <p>Users: {@code u1} requester (Item/Read, Item/Build, BatchControl/Request and RequestGrant),
 * {@code a1}, {@code a3}, {@code approver-2} (also granted as {@code APPROVER-2}, since
 * {@link MockAuthorizationStrategy} matches sids literally) approvers, {@code admin} administrator. Run
 * control and change control on. The dummy realm's user id strategy is case-insensitive (premise of
 * T-03-98, as in T-03-33).
 *
 * <p>Written from docs/SPEC.md items 2, 3, 5, 6a, 8 and 6 (usability line), docs/DECISIONS.md D-29, D-37
 * and D-53 and docs/TEST-MATRIX.md (T-03-95, T-03-33); the retired drivers were read only to learn what
 * each looked at. No src/main knowledge.
 */
@WithJenkins
public class ApproverChoiceGuardTest {

    private static final Pattern NO_APPROVERS = Pattern.compile("(?i)no approvers are configured");
    private static final String GRANT_CREATE = "batch-control/grants/create";

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere()
                        .to("a1", "a3", "approver-2", "APPROVER-2"));
        for (String id : new String[] {"a1", "a3", "approver-2", "admin"}) {
            User.getById(id, true).save();
        }
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a3"));
        cfg.save();
        job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-03-96 (D-53: an empty approver list is refused only on the form, so a direct setter (JCasC,
     * script console) can leave it empty; SPEC 3: approvers are picked from the configured list; SPEC 6a
     * and 8: the activation and grant requests designate approvers the same way; SPEC 6 usability: a
     * refusal says why and what to do instead, and a control is shown only to a user who can use it;
     * #31 gap X-01). Guard first: with approvers a1 and a3, u1's grant request page
     * ({@code grants/new?scopeFullName=batch-x}), the grant dialog fragment
     * ({@code grants/dialog?scopeFullName=batch-x}) and the activation form of the not-activated
     * {@code batch-x} ({@code job/batch-x/batch-control-activation/}) each carry the request form with
     * an {@code approvers} checkbox for a1 and a3 and a submit control, and no "no approvers" alert. With
     * the list emptied by the direct setter, each holds exactly one {@code .jenkins-alert} reading "No
     * approvers are configured" and naming an administrator, no {@code approvers} input, and no request
     * form with a submit control. The Request Run form is T-03-95.
     */
    @Test
    public void t_03_96_emptyApproverListShowsTheAlertOnTheGrantAndActivationForms() throws Exception {
        assertFalse(ActivationService.get().isActivated(job), "premise: batch-x is not activated, so u1 may request it");
        String[][] screens = {
            {"the grant request page", "batch-control/grants/new?scopeFullName=batch-x", GRANT_CREATE},
            {"the grant dialog", "batch-control/grants/dialog?scopeFullName=batch-x", GRANT_CREATE},
            {"the activation form", job.getUrl() + "batch-control-activation/", job.getUrl() + "batch-control-activation/submit"},
        };

        for (String[] screen : screens) {
            HtmlPage page = page("u1", screen[1]);
            List<HtmlForm> forms = UsabilityFixtures.formsEndingWith(page, screen[2]);
            assertEquals(1, forms.size(), "guard: " + screen[0] + " carries the request form posting to " + screen[2]
                    + "; forms: " + UsabilityFixtures.formActions(page));
            assertEquals(List.of("a1", "a3"), UsabilityFixtures.approverChoices(forms.get(0)),
                    "guard: " + screen[0] + " offers the configured approvers as checkboxes");
            assertFalse(submitControls(forms.get(0)).isEmpty(), "guard: " + screen[0] + " offers a submit control: "
                    + excerpt(forms.get(0).asXml()));
            assertTrue(alerts(page).isEmpty(), "guard: " + screen[0] + " shows no 'no approvers' alert while approvers are"
                    + " configured: " + excerpt(scope(page).asNormalizedText()));
        }

        cfg.setApprovers(new ArrayList<>());
        assertTrue(cfg.getApprovers() == null || cfg.getApprovers().isEmpty(),
                "premise: the direct setter emptied the global approver list (D-53 refuses it only on the form)");

        for (String[] screen : screens) {
            HtmlPage page = page("u1", screen[1]);
            List<DomElement> alerts = alerts(page);
            assertEquals(1, alerts.size(), screen[0] + " must say in one .jenkins-alert that no approvers are configured: "
                    + excerpt(scope(page).asNormalizedText()));
            assertTrue(alerts.get(0).asNormalizedText().toLowerCase(Locale.ROOT).contains("administrator"),
                    screen[0] + ": the alert must say what to do instead (ask an administrator): " + alerts.get(0).asNormalizedText());
            assertTrue(page.querySelectorAll("input[name=approvers]").isEmpty(),
                    screen[0] + " must offer no approver checkbox when none is configured: " + excerpt(scope(page).asXml()));
            for (HtmlForm form : UsabilityFixtures.formsEndingWith(page, screen[2])) {
                assertTrue(submitControls(form).isEmpty(), screen[0] + " must offer no submit control when the request"
                        + " cannot be made: " + excerpt(form.asXml()));
            }
        }
    }

    /**
     * T-03-97 (SPEC 2 {@code allowAdminSelfApproval}: off, the administrator is under separation of
     * duties too; SPEC 3: the requester may not designate himself, administrator exception while the
     * option is on; SPEC 6 usability: a choice is offered only when it can be used; #126 L2; #31 gap
     * X-29): approvers a1 and admin. With the option on (the default, premise), the administrator's own
     * Request Run form of {@code batch-x} offers {@code admin} and a1. With it off, the same form offers
     * a1 and not {@code admin}. Turned on again, {@code admin} is offered again (a form that never or
     * always offers it fails one of the three reads). Guard: u1's form offers admin while the option is
     * off (the option is about self-approval only).
     */
    @Test
    public void t_03_97_adminSelfApprovalOptionDecidesWhetherTheFormOffersAdmin() throws Exception {
        cfg.setApprovers(Arrays.asList("a1", "admin"));
        cfg.save();
        assertTrue(cfg.isAllowAdminSelfApproval(), "premise: allowAdminSelfApproval is on by default (SPEC 2)");

        List<String> allowed = choices("admin");
        assertTrue(allowed.contains("admin") && allowed.contains("a1"),
                "with allowAdminSelfApproval on the administrator's own form must offer admin (and a1), offered " + allowed);

        cfg.setAllowAdminSelfApproval(false);
        cfg.save();
        List<String> separated = choices("admin");
        assertTrue(separated.contains("a1"), "guard: the form still offers a1, offered " + separated);
        assertFalse(separated.contains("admin"), "with allowAdminSelfApproval off the administrator's own form must not offer"
                + " admin, who could not be designated, offered " + separated);
        assertTrue(choices("u1").contains("admin"), "guard: u1's form still offers admin (an approver who is not u1)");

        cfg.setAllowAdminSelfApproval(true);
        cfg.save();
        assertTrue(choices("admin").contains("admin"), "turned on again, the administrator's own form offers admin again");
    }

    /**
     * T-03-98 (SPEC 3: user ids are compared with Jenkins' configured user id strategy, not by plain
     * string equality; D-29/D-37: only a designated approver decides; #31 gap X-28): approvers a1 and
     * {@code APPROVER-2}; u1's PENDING request for {@code batch-x} designates {@code APPROVER-2}. The user
     * signed in as {@code approver-2} opens {@code requests/<id>/}: its Decision section offers Approve and
     * Reject; pressing Approve approves the request, which runs once (EXECUTED, decided by approver-2 in
     * either spelling, one build). Guard, before the approval: a3 (on the list, holding Approve, not
     * designated) is offered neither button and its direct approve is refused with HTTP 4xx, the request
     * still PENDING and nothing built.
     */
    @Test
    public void t_03_98_caseVariantDesignatedApproverFindsTheDecisionFormAndApproves() throws Exception {
        assertTrue(j.jenkins.getSecurityRealm().getUserIdStrategy().equals("APPROVER-2", "approver-2"),
                "premise: the configured user id strategy is case-insensitive");
        cfg.setApprovers(Arrays.asList("a1", "APPROVER-2", "a3"));
        cfg.save();
        String id = submitRunOk(j, "u1", job, "month-end batch for the case-variant approver", "APPROVER-2");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus(), "premise: PENDING");
        String detail = "batch-control/requests/" + id + "/";

        HtmlPage other = browserPage("a3", detail);
        assertNull(DecisionAreaFixtures.button(other, "Approve"), "guard: a3 is not designated and must not be offered Approve");
        assertNull(DecisionAreaFixtures.button(other, "Reject"), "guard: a3 is not designated and must not be offered Reject");
        assertClientError(decideRun(j, "a3", id, "approve", "not mine"), "guard: a3's direct approve");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus(), "guard: a3's approve changes nothing");
        assertTrue(job.getBuilds().isEmpty(), "guard: a3's approve builds nothing");

        HtmlPage page = browserPage("approver-2", detail);
        HtmlElement approve = DecisionAreaFixtures.button(page, "Approve");
        assertNotNull(approve, "approver-2 is the designated APPROVER-2 under the case-insensitive id strategy and must be"
                + " offered Approve: " + excerpt(scope(page).asNormalizedText()));
        assertNotNull(DecisionAreaFixtures.button(page, "Reject"), "approver-2 must be offered Reject");

        DecisionAreaFixtures.decide(page, "Approve", "checked, case-variant approver");
        j.waitUntilNoActivity();
        RunRequest decided = RunRequestService.get().load(id);
        assertEquals(RequestStatus.EXECUTED, decided.getStatus(), "the approval through the decision form must run the request");
        assertTrue("approver-2".equalsIgnoreCase(decided.getDecidedBy()), "the decision names approver-2, got "
                + decided.getDecidedBy());
        assertEquals(1, job.getBuilds().size(), "the approved request runs exactly once");
    }

    // ---------------------------------------------------------------- helpers

    private HtmlPage page(String userId, String relative) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(j, userId, relative);
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: " + userId + " opens " + relative);
        return page;
    }

    /** {@code relative} as {@code userId} with JavaScript on (the decision buttons are pressed). */
    private HtmlPage browserPage(String userId, String relative) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        Page answer = wc.getPage(new WebRequest(new URL(j.getURL(), relative), HttpMethod.GET));
        assertEquals(200, answer.getWebResponse().getStatusCode(), "fixture: " + userId + " opens " + relative);
        return (HtmlPage) answer;
    }

    private List<String> choices(String userId) throws Exception {
        HtmlPage page = page(userId, job.getUrl() + "batch-control/");
        List<HtmlForm> forms = UsabilityFixtures.formsEndingWith(page, job.getUrl() + "batch-control/submit");
        assertEquals(1, forms.size(), "fixture: " + userId + "'s Request Run page carries the form; forms: "
                + UsabilityFixtures.formActions(page));
        return UsabilityFixtures.approverChoices(forms.get(0));
    }

    /** The main panel of a page, or the whole document of a fragment (a dialog has no main panel). */
    private static DomNode scope(HtmlPage page) {
        DomElement main = page.getElementById("main-panel");
        return main != null ? main : page.getDocumentElement();
    }

    private static List<DomElement> alerts(HtmlPage page) {
        List<DomElement> out = new ArrayList<>();
        for (DomNode n : scope(page).querySelectorAll(".jenkins-alert")) {
            if (NO_APPROVERS.matcher(n.asNormalizedText()).find()) {
                out.add((DomElement) n);
            }
        }
        return out;
    }

    /** Submit inputs and buttons that submit (no type, or type submit) inside {@code form}. */
    private static List<DomElement> submitControls(HtmlForm form) {
        List<DomElement> out = new ArrayList<>();
        for (DomNode n : form.querySelectorAll("input[type=submit], button")) {
            DomElement e = (DomElement) n;
            String type = e.getAttribute("type");
            if ("input".equals(e.getTagName()) || type.isEmpty() || "submit".equalsIgnoreCase(type)) {
                out.add(e);
            }
        }
        return out;
    }
}
