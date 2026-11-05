package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.decideActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decided requests offer no further action and refuse one in plain words (issue #31, note 370): the
 * pages of decided run, grant and activation requests offer the requester no Change Approvers and no
 * Cancel and the designated approver no decision (T-05-210, gap X-06, retired drivers browser/b3.mjs #11,
 * browser/b6.mjs #2, browser/audit/b4.mjs #21 and fresh/s2-more.mjs #4), and an approve, reject or
 * cancel posted to such a request is refused with a message naming its status (T-05-211, gap X-07,
 * extra/stale.mjs #2, #4, #5, extra/race.mjs #3, fresh/s2-more.mjs #6, browser/audit/b3.mjs #7, b4.mjs
 * #21 and browser/verify/c31.mjs #2).
 *
 * <p>Basis: SPEC section 4 (PENDING is the only state a decision or a cancel leaves: APPROVED,
 * REJECTED, CANCELLED and EXECUTED are not left by one), SPEC 3/D-26/D-37 (only a PENDING request's
 * approvers change) and the usability line of SPEC section 6 ("every button, link and form is shown only
 * to users who can use it; every refusal ... tells the user in plain words why"); T-03-94 is the same
 * refusal for a stale Change Approvers, whose message names the current status. T-OS-03 already reads
 * that a REJECTED run request's page offers no approve path to the requester; this class adds the other
 * states, kinds, controls and viewers.
 *
 * <p>Run and change control on; u1 holds Overall/Read, Item/Read, Job/Build, BatchControl/Request and
 * RequestGrant; a1 (on the approver list) holds Approve. Every request designates a1.
 *
 * <p>Written from docs/SPEC.md items 3, 4, 5, 6a and 8 and section 6, docs/DECISIONS.md D-26/D-37 and
 * docs/TEST-MATRIX.md (T-03-94, T-OS-03); the retired drivers were read only to learn what each looked
 * at. No src/main knowledge.
 */
@WithJenkins
public class DecidedRequestGuardTest {

    private static final java.util.regex.Pattern ACTION = java.util.regex.Pattern.compile("(?i)approv|reject|cancel|decid");

    private JenkinsRule j;
    private FreeStyleProject job;
    private FreeStyleProject inactive;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
        BatchControlFixtures.activate(job);
        inactive = j.createFreeStyleProject("new-x");
        BatchControlJobProperty cleared = new BatchControlJobProperty(true);
        cleared.setBlockTimer(false);
        cleared.setBlockUpstream(false);
        setBatchControl(inactive, cleared);
        assertFalse(ActivationService.get().isActivated(inactive), "fixture: new-x starts not activated");
    }

    /**
     * T-05-210 (SPEC 4, SPEC 3 D-26/D-37, SPEC 6 usability "every button, link and form is shown only
     * to users who can use it"; #31 gap X-06): guard first, on PENDING run, grant and activation requests
     * the requester u1's page offers a Change Approvers form and a Cancel control and the designated
     * approver a1's page offers Approve and Reject (controls whose target ends in
     * {@code <kind>/<id>/changeApprover}, {@code /cancel}, {@code /approve}, {@code /reject}). Then, on
     * run requests APPROVED (queued, no executor) and later EXECUTED, REJECTED and CANCELLED, and on an
     * APPROVED grant request and an APPROVED activation request, u1's page offers neither
     * {@code changeApprover} nor {@code cancel}, and a1's page offers neither {@code approve} nor
     * {@code reject}.
     */
    @Test
    public void t_05_210_decidedRequestPagesOfferNoActions() throws Exception {
        String pendingRun = submitRunOk(j, "u1", job, "pending run", "a1");
        String pendingGrant = submitGrantOk(j, "u1", "batch-x", List.of("CONFIGURE"), 30, "pending grant", null, "a1");
        String pendingActivation = submitActivationOk(j, "u1", inactive, "ACTIVATE", "pending activation", "a1");
        assertOffered("requests", pendingRun, "the PENDING run request");
        assertOffered("grants", pendingGrant, "the PENDING grant request");
        assertOffered("activations", pendingActivation, "the PENDING activation request");

        j.jenkins.setNumExecutors(0);
        assertSuccess(decideRun(j, "a1", pendingRun, "approve", "ok"), "fixture: a1 approves the run request");
        assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(pendingRun).getStatus(),
                "premise: no executor, so the approved run request waits APPROVED");
        assertNoActions("requests", pendingRun, "the APPROVED run request");
        j.jenkins.setNumExecutors(1);
        j.waitUntilNoActivity();
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(pendingRun).getStatus(), "premise: EXECUTED");
        assertNoActions("requests", pendingRun, "the EXECUTED run request");

        String rejected = submitRunOk(j, "u1", job, "run to be rejected", "a1");
        assertSuccess(decideRun(j, "a1", rejected, "reject", "not this month"), "fixture: a1 rejects the run request");
        assertNoActions("requests", rejected, "the REJECTED run request");

        String cancelled = submitRunOk(j, "u1", job, "run to be cancelled", "a1");
        assertSuccess(ApproverFormFixtures.post(j, "u1", "batch-control/requests/" + cancelled + "/cancel", List.of()),
                "fixture: u1 cancels the run request");
        assertEquals(RequestStatus.CANCELLED, RunRequestService.get().load(cancelled).getStatus(), "premise: CANCELLED");
        assertNoActions("requests", cancelled, "the CANCELLED run request");

        assertSuccess(decideGrant(j, "a1", pendingGrant, "approve", "ok"), "fixture: a1 approves the grant request");
        assertEquals(RequestStatus.APPROVED, GrantRequestService.get().load(pendingGrant).getStatus(), "premise: APPROVED");
        assertNoActions("grants", pendingGrant, "the APPROVED grant request");

        assertSuccess(decideActivation(j, "a1", pendingActivation, "approve", "ok"), "fixture: a1 approves the activation");
        assertEquals(RequestStatus.APPROVED, ActivationService.get().load(pendingActivation).getStatus(), "premise: APPROVED");
        assertNoActions("activations", pendingActivation, "the APPROVED activation request");
    }

    /**
     * T-05-211 (SPEC 4: a decided request is not decided or cancelled again; SPEC 6 usability: every
     * refusal tells the user in plain words why; T-03-94's rule for the same stale form; #31 gap X-07):
     * a1 posts approve and reject, and u1 posts cancel, to run requests EXECUTED, REJECTED and CANCELLED
     * (a1's approve of the CANCELLED one is the scripted approve), an APPROVED grant request and an
     * APPROVED activation request. Each answer is HTTP 4xx, carries no "Oops!" page, stack trace or bare
     * "Access Denied", does not say "Correct the fields marked below" (no field is wrong), and its message
     * names the request's current status (read from the service; letter case not pinned) in the
     * same sentence as the refused action. The status is
     * unchanged and {@code batch-x} still has its one build. The link back to the request is not pinned:
     * SPEC fixes no wording or link for this refusal. Guard: the first approve, reject and cancel of
     * PENDING requests succeed.
     */
    @Test
    public void t_05_211_actionOnADecidedRequestIsRefusedNamingItsStatus() throws Exception {
        String executed = submitRunOk(j, "u1", job, "run to be executed", "a1");
        assertSuccess(decideRun(j, "a1", executed, "approve", "ok"), "guard: a1 approves the PENDING run request");
        j.waitUntilNoActivity();
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(executed).getStatus(), "premise: EXECUTED");
        String rejected = submitRunOk(j, "u1", job, "run to be rejected", "a1");
        assertSuccess(decideRun(j, "a1", rejected, "reject", "not this month"), "guard: a1 rejects the PENDING run request");
        String cancelled = submitRunOk(j, "u1", job, "run to be cancelled", "a1");
        assertSuccess(ApproverFormFixtures.post(j, "u1", "batch-control/requests/" + cancelled + "/cancel", List.of()),
                "guard: u1 cancels the PENDING run request");
        String grant = submitGrantOk(j, "u1", "batch-x", List.of("CONFIGURE"), 30, "grant to be approved", null, "a1");
        assertSuccess(decideGrant(j, "a1", grant, "approve", "ok"), "guard: a1 approves the PENDING grant request");
        String activation = submitActivationOk(j, "u1", inactive, "ACTIVATE", "activation to be approved", "a1");
        assertSuccess(decideActivation(j, "a1", activation, "approve", "ok"), "guard: a1 approves the PENDING activation request");
        assertEquals(1, job.getBuilds().size(), "premise: batch-x has its one approved build");

        for (String id : new String[] {executed, rejected, cancelled}) {
            Supplier<RequestStatus> status = () -> RunRequestService.get().load(id).getStatus();
            assertRefusedNamingStatus("run request", "requests", id, status);
        }
        assertRefusedNamingStatus("grant request", "grants", grant, () -> GrantRequestService.get().load(grant).getStatus());
        assertRefusedNamingStatus("activation request", "activations", activation,
                () -> ActivationService.get().load(activation).getStatus());
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), "no refused action may build batch-x");
        assertEquals(2, job.getNextBuildNumber(), "no build number of batch-x may be consumed");
    }

    // ---------------------------------------------------------------- helpers

    private void assertOffered(String kind, String id, String what) throws Exception {
        HtmlPage requester = page("u1", kind, id);
        assertFalse(targets(requester, kind, id, "changeApprover").isEmpty(), "guard: " + what + " offers u1 Change Approvers");
        assertFalse(targets(requester, kind, id, "cancel").isEmpty(), "guard: " + what + " offers u1 Cancel");
        HtmlPage approver = page("a1", kind, id);
        assertFalse(targets(approver, kind, id, "approve").isEmpty(), "guard: " + what + " offers a1 Approve");
        assertFalse(targets(approver, kind, id, "reject").isEmpty(), "guard: " + what + " offers a1 Reject");
    }

    private void assertNoActions(String kind, String id, String what) throws Exception {
        HtmlPage requester = page("u1", kind, id);
        assertEquals(List.of(), targets(requester, kind, id, "changeApprover"), what + " must offer u1 no Change Approvers");
        assertEquals(List.of(), targets(requester, kind, id, "cancel"), what + " must offer u1 no Cancel");
        HtmlPage approver = page("a1", kind, id);
        assertEquals(List.of(), targets(approver, kind, id, "approve"), what + " must offer a1 no Approve");
        assertEquals(List.of(), targets(approver, kind, id, "reject"), what + " must offer a1 no Reject");
    }

    private HtmlPage page(String userId, String kind, String id) throws Exception {
        String path = "batch-control/" + kind + "/" + id + "/";
        HtmlPage page = UsabilityFixtures.htmlPage(j, userId, path);
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: " + userId + " opens " + path);
        return page;
    }

    private static List<String> targets(HtmlPage page, String kind, String id, String verb) throws Exception {
        List<String> out = new ArrayList<>();
        for (java.net.URL url : RequestPageFixtures.controlsEndingWith(page, kind + "/" + id + "/" + verb)) {
            out.add(url.getPath());
        }
        return out;
    }

    private void assertRefusedNamingStatus(String what, String kind, String id, Supplier<RequestStatus> status)
            throws Exception {
        RequestStatus before = status.get();
        assertNotEquals(RequestStatus.PENDING, before, "premise: the " + what + " is decided");
        String[][] attempts = {{"a1", "approve"}, {"a1", "reject"}, {"u1", "cancel"}};
        for (String[] attempt : attempts) {
            String where = attempt[0] + "'s " + attempt[1] + " of the " + before + " " + what;
            Page answer = post(attempt[0], "batch-control/" + kind + "/" + id + "/" + attempt[1]);
            int code = answer.getWebResponse().getStatusCode();
            String text = UsabilityFixtures.text(answer);
            assertTrue(code >= 400 && code < 500, where + " must be refused with HTTP 4xx, got " + code + ": " + excerpt(text));
            UsabilityFixtures.assertPlainRefusal(where, text, null);
            assertFalse(text.contains("Correct the fields marked below"), where + ": no field is wrong, so the answer must not"
                    + " say 'Correct the fields marked below': " + excerpt(text));
            String message = message(answer);
            String state = before.name().toLowerCase(Locale.ROOT);
            // one sentence (or line) names both the status and the refused action, so a status cell of a
            // re-rendered request page ("Status EXECUTED") cannot stand in for the message
            boolean named = Arrays.stream(message.split("[.!?\\n]"))
                    .anyMatch(s -> s.toLowerCase(Locale.ROOT).contains(state) && ACTION.matcher(s).find());
            assertTrue(named, where + ": the refusal message must name the current status " + before
                    + " together with the refused action: " + excerpt(message));
            assertEquals(before, status.get(), where + " must leave the status unchanged");
        }
    }

    /** {@code POST path} as {@code userId} with a comment and the crumb; redirects are not followed. */
    private Page post(String userId, String path) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(path), HttpMethod.POST);
        request.setCharset(StandardCharsets.UTF_8);
        request.setRequestParameters(new ArrayList<>(List.of(new NameValuePair("comment", "stale form"))));
        return wc.getPage(request);
    }

    /**
     * The refusal's own message: the texts of the answer's {@code role="alert"} elements (the convention of
     * this plugin's refusals, as in T-03-94); the whole text when it has none.
     */
    private static String message(Page answer) {
        if (answer instanceof HtmlPage page) {
            List<String> alerts = new ArrayList<>();
            for (DomNode alert : page.querySelectorAll("[role=alert]")) {
                String text = alert.asNormalizedText().trim();
                if (!text.isEmpty()) {
                    alerts.add(text);
                }
            }
            return alerts.isEmpty() ? page.asNormalizedText() : String.join(" | ", alerts);
        }
        return answer.getWebResponse().getContentAsString();
    }
}
