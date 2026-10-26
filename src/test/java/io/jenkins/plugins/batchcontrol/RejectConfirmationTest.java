package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextArea;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.DecisionAreaFixtures.button;
import static io.jenkins.plugins.batchcontrol.DecisionAreaFixtures.theCommentBox;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #93, matrix rows T-UI-292 and T-UI-293 (note 365): rejecting a run request from the
 * request page asks for confirmation first.
 *
 * <p>The tests stub {@code window.dialog} wholesale — HtmlUnit has no real browser dialog, so
 * they assert the page <em>asks</em> for confirmation (one call, the page's sentence) and that
 * the form is posted only when the stub confirms. The dialog itself, in a real browser, is
 * covered by the e2e check in {@code e2e/r27/decision_tables.py} (section R93).
 *
 * <p>Written from the TEST-MATRIX rows and note 365; no src/main knowledge beyond the matrix.
 */
@WithJenkins
class RejectConfirmationTest {

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a2"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();
        job = j.createFreeStyleProject("batch-x");
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("DAY", "2026-10-01"),
                new StringParameterDefinition("MODE", "full")));
        j.buildAndAssertSuccess(job); // a recent run, so the page has its recent-runs section
        cfg.setRunControlEnabled(true);
        cfg.save();
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-UI-292 (#93): pressing Reject with a comment opens core's confirmation first, carrying the
     * page's sentence; nothing is posted until the approver confirms.
     */
    @Test
    void t_ui_292_rejectOpensConfirmationWithThePageSentence() throws Exception {
        RunRequest request = request("order check");
        HtmlPage page = page("a1", request);
        // The stub records the call but never resolves: the dialog stays open, so the form
        // is never posted and the page cannot navigate while the test reads the recording.
        // (A resolving stub would post asynchronously on a background thread and could
        // navigate before the recording is read — racy on fast runners.)
        stubDialogOpenOnly(page);
        waitForPageSettled(page);
        typeComment(page, "wrong day");
        HtmlElement reject = button(page, "Reject");
        assertNotNull(reject, "the Decision section must offer Reject");
        reject.click();
        // The click handler calls dialog.confirm synchronously, so the recording is already
        // there — and with the dialog still open, no navigation can race the assertions.
        assertEquals(1, dialogCalls(page), "pressing Reject must open the confirmation exactly once");
        assertEquals("Reject this run request?", dialogTitle(page), "the dialog title");
        assertEquals("Rejection is final for this request; a rejected request cannot be reopened.",
                dialogMessage(page), "the dialog message must match the sentence on the page");
    }

    /**
     * T-UI-292 (#93): dismissing the confirmation (Cancel or Escape) posts nothing and the request
     * stays PENDING.
     */
    @Test
    void t_ui_292_dismissingTheDialogPostsNothingAndRequestStaysPending() throws Exception {
        RunRequest request = request("order check");
        HtmlPage page = page("a1", request);
        stubDialog(page, false); // the stub rejects: Cancel / Escape
        waitForPageSettled(page);
        typeComment(page, "wrong day");
        HtmlElement reject = button(page, "Reject");
        assertNotNull(reject, "the Decision section must offer Reject");
        reject.click();
        assertEquals(1, dialogCalls(page), "pressing Reject must open the confirmation exactly once");
        page.getWebClient().waitForBackgroundJavaScript(2000);
        Page current = page.getWebClient().getCurrentWindow().getEnclosedPage();
        assertSame(page, current, "dismissing the dialog must not post or navigate");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(request.getId()).getStatus(),
                "the request must stay PENDING after the dialog is dismissed");
    }

    /**
     * T-UI-292 (#93): confirming the dialog rejects the request with the typed comment.
     */
    @Test
    void t_ui_292_confirmingTheDialogRejectsTheRequest() throws Exception {
        RunRequest request = request("order check");
        HtmlPage page = page("a1", request);
        stubDialog(page, true); // the stub confirms
        waitForPageSettled(page);
        typeComment(page, "wrong day");
        HtmlElement reject = button(page, "Reject");
        assertNotNull(reject, "the Decision section must offer Reject");
        reject.click();
        // The dialog confirm resolves async; poll for the rejection (robust on slow runners).
        RunRequest decided = pollForStatus(request.getId(), RequestStatus.REJECTED, page);
        assertEquals(RequestStatus.REJECTED, decided.getStatus(),
                "confirming the dialog must reject the request");
        assertEquals("wrong day", decided.getDecisionComment(), "the typed comment is kept");
        assertEquals("a1", decided.getDecidedBy(), "the designated approver decides");
    }

    @Test
    void t_ui_292_approveIsStillSingleClick() throws Exception {
        RunRequest request = request("order check");
        HtmlPage page = page("a1", request);

        // If Approve accidentally invokes the confirmation dialog, the test fails immediately.
        page.executeJavaScript("window.dialog = { confirm: function () {"
                + "throw new Error('Approve must not ask for confirmation');"
                + "} };");

        waitForPageSettled(page);
        typeComment(page, "looks good");

        HtmlElement approve = button(page, "Approve");
        assertNotNull(approve, "the Decision section must offer Approve");

        approve.click();

        // Do not inspect window.__bcDialogCalls after navigation.
        // The throwing stub above already proves Approve did not call dialog.confirm.
        RunRequest decided = pollForLeavingStatus(request.getId(), RequestStatus.PENDING, page);

        assertTrue(decided.getStatus() == RequestStatus.APPROVED
                        || decided.getStatus() == RequestStatus.EXECUTED,
                "Approve must post with a single click (no confirmation); got " + decided.getStatus());
        assertEquals("looks good", decided.getDecisionComment(),
                "the typed comment is kept");
    }

    /**
     * T-UI-293 (#93): pressing Reject with a blank comment skips the confirmation — the server
     * refuses the rejection anyway, so the dialog would only add a click. The form submits
     * directly (the click returns a new page), the server's error shows, and the request stays
     * PENDING.
     */
    @Test
    void t_ui_293_blankCommentSkipsTheDialog() throws Exception {
        RunRequest request = request("order check");
        HtmlPage page = page("a1", request);
        page.executeJavaScript("window.__bcDialogOpened = false;"
                + "window.dialog = { confirm: function () {"
                + "  window.__bcDialogOpened = true;"
                + "  return Promise.resolve();"
                + "} };");
        waitForPageSettled(page);
        typeComment(page, "   ");
        HtmlElement reject = button(page, "Reject");
        assertNotNull(reject, "the Decision section must offer Reject");
        Page clicked = reject.click();
        assertTrue(clicked != page,
                "with a blank comment Reject must submit directly without opening the confirmation");
        page.getWebClient().waitForBackgroundJavaScript(2000);
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(request.getId()).getStatus(),
                "the server refuses the blank-comment rejection; the request stays PENDING");
    }

    // ------------------------------------------------------------------ helpers

    private RunRequest request(String reason) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("DAY", "2026-10-09");
        params.put("MODE", "delta");
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            return RunRequestService.get().create(job, params, reason, "a1");
        }
    }

    private HtmlPage page(String user, RunRequest request) throws Exception {
        Page answer = client(user, true).getPage(new WebRequest(
                new URL(j.getURL(), "batch-control/requests/" + request.getId() + "/"), HttpMethod.GET));
        assertEquals(200, answer.getWebResponse().getStatusCode(), user + " opens the request page");
        return (HtmlPage) answer;
    }

    private JenkinsRule.WebClient client(String user, boolean javaScript) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        wc.getOptions().setJavaScriptEnabled(javaScript);
        wc.getOptions().setThrowExceptionOnScriptError(false);
        return wc;
    }

    private void typeComment(HtmlPage page, String comment) {
        HtmlElement box = theCommentBox(page);
        assertNotNull(box, "the Decision section must offer the comment box");
        if (box instanceof HtmlTextArea t) {
            t.setText(comment);
        } else {
            ((HtmlInput) box).setValue(comment);
        }
    }

    /**
     * Bounded poll (no fixed sleep): lets the page's own scripts — including the adjunct that
     * intercepts Reject — finish running before the test clicks. Linux runners schedule
     * background JavaScript differently, so a fixed sleep is fragile here.
     */
    private void waitForPageSettled(HtmlPage page) {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            page.getWebClient().waitForBackgroundJavaScript(500);
            Object readyState = page.executeJavaScript("document.readyState").getJavaScriptResult();
            if ("complete".equals(String.valueOf(readyState))) {
                page.getWebClient().waitForBackgroundJavaScript(1000);
                return;
            }
        }
    }

    /**
     * Replaces the whole {@code window.dialog} object with a recording stub: core may define
     * the property late or as non-writable, in which case assigning to
     * {@code window.dialog.confirm} silently fails and the real dialog would be called.
     * Resolves (confirm) or rejects (Cancel / Escape) the recorded calls.
     */
    private void stubDialog(HtmlPage page, boolean confirm) {
        page.executeJavaScript("window.__bcDialogCalls = [];"
                + "window.dialog = { confirm: function (title, opts) {"
                + "  window.__bcDialogCalls.push({"
                + "    title: String(title),"
                + "    message: opts && opts.message ? String(opts.message) : '',"
                + "    okText: opts && opts.okText ? String(opts.okText) : ''"
                + "  });"
                + "  return " + (confirm ? "Promise.resolve()" : "Promise.reject(new Error('dismissed'))") + ";"
                + "} };");
    }

    /**
     * Records {@code window.dialog.confirm} calls but never resolves: the dialog stays open,
     * so the form is never posted and the page cannot navigate while the test reads the
     * recording. For asserting that the confirmation opens (and its text) deterministically.
     */
    private void stubDialogOpenOnly(HtmlPage page) {
        page.executeJavaScript("window.__bcDialogCalls = [];"
                + "window.dialog = { confirm: function (title, opts) {"
                + "  window.__bcDialogCalls.push({"
                + "    title: String(title),"
                + "    message: opts && opts.message ? String(opts.message) : '',"
                + "    okText: opts && opts.okText ? String(opts.okText) : ''"
                + "  });"
                + "  return new Promise(function () {});" // the approver hasn't answered yet
                + "} };");
    }

    private int dialogCalls(HtmlPage page) {
        Object result = page.executeJavaScript(
                "window.__bcDialogCalls ? window.__bcDialogCalls.length : -1").getJavaScriptResult();
        assertTrue(result instanceof Number, "the dialog stub must have recorded calls");
        return ((Number) result).intValue();
    }

    private String dialogTitle(HtmlPage page) {
        Object result = page.executeJavaScript(
                "window.__bcDialogCalls && window.__bcDialogCalls.length"
                        + " ? window.__bcDialogCalls[0].title : null").getJavaScriptResult();
        return result == null ? null : String.valueOf(result);
    }

    private String dialogMessage(HtmlPage page) {
        Object result = page.executeJavaScript(
                "window.__bcDialogCalls && window.__bcDialogCalls.length"
                        + " ? window.__bcDialogCalls[0].message : null").getJavaScriptResult();
        return result == null ? null : String.valueOf(result);
    }

    /**
     * Polls the request's status until it becomes {@code expected} or the timeout (30 s) expires.
     * The dialog confirm resolves async, so a fixed sleep is fragile on slow runners.
     */
    private RunRequest pollForStatus(String requestId, RequestStatus expected, HtmlPage page) throws Exception {
        long deadline = System.currentTimeMillis() + 30000;
        RunRequest decided = RunRequestService.get().load(requestId);
        while (decided.getStatus() != expected && System.currentTimeMillis() < deadline) {
            page.getWebClient().waitForBackgroundJavaScript(500);
            decided = RunRequestService.get().load(requestId);
        }
        return decided;
    }

    /**
     * Polls until the request leaves {@code initial} status or the timeout (30 s) expires.
     * Used where the terminal state is timing-dependent (e.g. an approved run may already be
     * EXECUTED on fast runners) — what matters is that the decision was posted.
     */
    private RunRequest pollForLeavingStatus(String requestId, RequestStatus initial, HtmlPage page) throws Exception {
        long deadline = System.currentTimeMillis() + 30000;
        RunRequest decided = RunRequestService.get().load(requestId);
        while (decided.getStatus() == initial && System.currentTimeMillis() < deadline) {
            page.getWebClient().waitForBackgroundJavaScript(500);
            decided = RunRequestService.get().load(requestId);
        }
        return decided;
    }
}
