package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cover for the approval screen's "recent runs" window, requested by ui-dev for
 * commit d6c0878. Matrix rows T-UI-01 (the allowed window sizes), T-UI-02 (the value is trimmed
 * and then matched against the allowed list; anything else falls back to the default of 5 without
 * an error page), T-UI-03 (the window is not a way
 * around the P-09 visibility model), T-UI-04 (a deleted target job still renders, with no run
 * history), T-UI-05 (the post-approval notice is bound to APPROVED + not yet executed) and
 * T-UI-06 (following the window control puts no crumb in the URL — ui-dev e98d211).
 *
 * IMPORTANT — provenance: unlike every other row in the matrix, T-UI-* is NOT derived from a
 * SPEC acceptance criterion. docs/SPEC.md says nothing about a recent-run table on the approval
 * screen or about a {@code runs} query parameter; the contract asserted here is the one the
 * implementation chose (reported by ui-dev), and these rows pin it so it cannot change silently.
 * Whether it belongs in SPEC is a human decision — see matrix note 40.
 *
 * Detection technique: the rows never assume a DOM shape. A run is "listed" when the page
 * references it either as a build URL of the job ({@code job/<name>/<number>/}) or as a
 * {@code #<number>} token that is not part of a longer token (so CSS colours such as
 * {@code #1f2937} cannot be mistaken for build #1).
 *
 * Written from docs/SPEC.md, docs/TEST-MATRIX.md and the ui-dev endpoint contract only
 * (no src/main knowledge).
 */
@WithJenkins
public class ApprovalScreenRecentRunsTest {

    private static final String JOB = "batch-x";
    /** A second, never-built job, so the notice row's pages carry no run rows at all. */
    private static final String NOTICE_JOB = "batch-y";

    private static final String REQUESTER = "u1";
    /** Designated approver WITHOUT Item/Read on the job (P-09: still sees the request itself). */
    private static final String BLIND_APPROVER = "a1";
    /** Designated approver WITH Item/Read on the job (sees the job's run history). */
    private static final String READING_APPROVER = "a2";
    private static final String ADMIN = "admin";

    /** How many builds the job has before any request is opened. */
    private static final int BUILDS = 12;
    private static final int DEFAULT_RUNS = 5;

    /** The window sizes ui-dev's contract allows. */
    private static final List<String> ALLOWED = Arrays.asList("5", "10", "20", "50");

    /**
     * The id ui-dev put on the post-approval notice element as a stable test hook (reported for
     * {@code RequestItem/index.jelly}); it is rendered only while the request is APPROVED and not
     * yet executed. Asserting the hook lets the row measure the notice itself rather than only the
     * state it hangs off, without pinning wording SPEC does not define. Lower-case, so it never
     * collides with the {@code APPROVED} status text the same rows assert on.
     */
    private static final String APPROVED_NOTICE = "batch-control-approved-notice";

    /**
     * Values that are not on the allowed list even after trimming, and must therefore silently
     * fall back to the default of 5 (no error page). A whitespace-padded allowed value does
     * <em>not</em> belong here — trimming happens <em>before</em> the allow-list check, so
     * {@code "10 "} is the allowed value 10; see {@link #PADDED_ALLOWED}.
     */
    private static final List<String> UNSUPPORTED = Arrays.asList(
            "100000", "-1", "0", "7", "abc", "", "99999999999999999999", "5.5");

    /**
     * Allowed values carrying leading/trailing whitespace. {@code runs} is a query parameter a
     * human can type into the address bar, so the screen trims it and then matches the allowed
     * list: each of these must render the window of its <em>trimmed</em> value, not the default.
     * The default itself (5) is deliberately not padded here — a 5-window would be
     * indistinguishable from a fallback, so every entry names a different window size and the
     * assertion stays falsifiable.
     */
    private static final List<String> PADDED_ALLOWED = Arrays.asList("10 ", " 10", " 20 ", " 50");

    private JenkinsRule j;

    private final Map<String, JenkinsRule.WebClient> clients = new HashMap<>();

    private FreeStyleProject job;
    private FreeStyleProject noticeJob;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to(ADMIN)
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST)
                        .everywhere().to(REQUESTER)
                // a1 deliberately has NO Item/Read anywhere (P-09 fixture, same as T-SEC-08)
                .grant(Jenkins.READ, BatchControlPermissions.APPROVE).everywhere().to(BLIND_APPROVER)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to(READING_APPROVER));

        cfg = BatchControlGlobalConfiguration.get();
        // Recording stays active through change control (SPEC 9: any switch on), while run
        // control stays off for the fixture builds, so the job's history can be produced
        // without approvals and without the D-31 creation default interfering.
        cfg.setChangeControlEnabled(true);
        cfg.setRunControlEnabled(false);
        cfg.setApprovers(Arrays.asList(BLIND_APPROVER, READING_APPROVER));
        cfg.save();

        job = j.createFreeStyleProject(JOB);
        for (int i = 0; i < BUILDS; i++) {
            j.buildAndAssertSuccess(job);
        }
        assertEquals(BUILDS, job.getBuilds().size(), "fixture: the job must have " + BUILDS + " runs to page through");

        noticeJob = j.createFreeStyleProject(NOTICE_JOB);

        cfg.setRunControlEnabled(true);
        cfg.save();
        job.addProperty(new BatchControlJobProperty(true));
        noticeJob.addProperty(new BatchControlJobProperty(true));
    }

    /**
     * T-UI-01: the approval screen shows the latest {@code runs} runs of the target job, for
     * each allowed value, and the latest 5 when the parameter is absent.
     */
    @Test
    public void t_ui_01_recentRunWindowFollowsTheRunsParameter() throws Exception {
        RunRequest request = createRequest(READING_APPROVER, "runs-window review");

        assertWindow("no runs parameter", request.getId(), null, DEFAULT_RUNS);
        for (String allowed : ALLOWED) {
            assertWindow("runs=" + allowed, request.getId(), allowed,
                    Integer.parseInt(allowed));
        }
    }

    /**
     * T-UI-02: {@code runs} is <strong>trimmed first and then matched</strong> against the allowed
     * list (5/10/20/50). A value that is still not on the list falls back to the default of 5
     * silently — HTTP 200, the default window, and no error page or parse failure surfaced to the
     * user — while a padded allowed value is honoured as that value.
     *
     * Both halves live in one method because they are one rule, and the order inside it is the
     * point: if the allow-list check came first, {@code "10 "} would fall back to 5. Whatever the
     * value, the user-facing guarantee is identical and is asserted for every value of both sets:
     * no error page, no stack trace, no {@code NumberFormatException} leaking into the HTML.
     */
    @Test
    public void t_ui_02_runsValueIsTrimmedThenMatchedAgainstTheAllowedList() throws Exception {
        RunRequest request = createRequest(READING_APPROVER, "runs-fallback review");

        // (1) not on the allowed list even after trimming: silent fallback to the default window
        for (String unsupported : UNSUPPORTED) {
            String label = "runs=[" + unsupported + "]";
            WebResponse response = detail(READING_APPROVER, request.getId(), unsupported);
            String html = assertRendersWithoutParseFailure(label, response);
            assertWindowIn(label, html, DEFAULT_RUNS);
        }

        // (2) an allowed value with padding: trimming runs before the allow-list check, so the
        // window is that value and NOT the default - the padding is not an unsupported value
        for (String padded : PADDED_ALLOWED) {
            int trimmed = Integer.parseInt(padded.trim());
            String label = "runs=[" + padded + "] (trimmed to " + trimmed + ")";
            WebResponse response = detail(READING_APPROVER, request.getId(), padded);
            String html = assertRendersWithoutParseFailure(label, response);
            assertWindowIn(label, html, trimmed);
        }
    }

    /**
     * T-UI-03 (P0, P-09 must not be bypassable): the designated approver a1 can open the
     * request (P-09 makes the request itself visible to its approver — T-SEC-08) but holds no
     * Item/Read on the target job, so no {@code runs} value may disclose a single run of that
     * job. The reading approver a2 is the fixture control: the very same table does list runs
     * when the viewer may read the job, so this row measures the gate and not an empty screen.
     */
    @Test
    public void t_ui_03_runsParameterCannotBypassP09Visibility() throws Exception {
        RunRequest blindRequest = createRequest(BLIND_APPROVER, "p09 window probe");
        RunRequest readableRequest = createRequest(READING_APPROVER, "p09 control probe");

        // fixture control: with Item/Read the widest window really does list the oldest run
        String controlHtml = detail(READING_APPROVER, readableRequest.getId(), "50")
                .getContentAsString();
        assertTrue(showsRun(controlHtml, JOB, BUILDS) && showsRun(controlHtml, JOB, 1), "fixture control: an approver holding Item/Read must see the job's runs, "
                        + "otherwise this row cannot measure the visibility gate");

        // fixture control: P-09 still lets the designated approver open the request itself
        assertEquals(200, detail(BLIND_APPROVER, blindRequest.getId(), null).getStatusCode(), "P-09: the designated approver must be able to open the request detail");

        for (String value : concat(concat(ALLOWED, PADDED_ALLOWED), UNSUPPORTED)) {
            String label = "runs=[" + value + "] as an approver without Item/Read";
            WebResponse response = detail(BLIND_APPROVER, blindRequest.getId(), value);
            int code = response.getStatusCode();
            assertTrue(code == 200 || code == 403 || code == 404, label + " must answer 200, 403 or 404, got " + code);
            assertNoRunDisclosed(label, JOB, response.getContentAsString());
        }
    }

    /**
     * T-UI-04 (P0, same non-bypass clause): when the target job is gone, no {@code runs} value
     * may produce run rows — and the screen must still render instead of failing.
     */
    @Test
    public void t_ui_04_deletedJobShowsNoRunHistoryForAnyRunsValue() throws Exception {
        RunRequest request = createRequest(READING_APPROVER, "deleted job window probe");
        job.delete();
        assertNull(j.jenkins.getItemByFullName(JOB, FreeStyleProject.class), "fixture: the target job must be gone");

        for (String value : concat(ALLOWED, UNSUPPORTED)) {
            String label = "runs=[" + value + "] with the target job deleted";
            WebResponse response = detail(READING_APPROVER, request.getId(), value);
            assertTrue(response.getStatusCode() < 500, label + " must not fail the screen, got HTTP " + response.getStatusCode());
            String html = response.getContentAsString();
            assertFalse(html.contains("Stack Trace"), label + " must not render a stack trace");
            assertNoRunDisclosed(label, JOB, html);
        }
    }

    /**
     * T-UI-05: the post-approval notice is bound to "APPROVED and not executed yet". SPEC pins no
     * wording for it (matrix note 40), so the row measures the notice through the stable id
     * ui-dev exposes for it ({@link #APPROVED_NOTICE}) plus the state it is keyed on: the notice is
     * present exactly once — on the approved-but-unexecuted screen, which renders APPROVED with no
     * run attached — and absent from the executed screen (which renders EXECUTED and links its
     * run), from the REJECTED one and from the CANCELLED one, neither of which may render the
     * approved state at all.
     */
    @Test
    public void t_ui_05_approvedStateIsBoundToTheUnexecutedRequest() throws Exception {
        // (1) APPROVED, not executed yet - matrix note 16: quiet down keeps the submission idle
        RunRequest approved = createNoticeRequest("notice: approved not run");
        // the queue refuses the approved submission, so it stays APPROVED and unexecuted without a
        // cancelled queue item (D-72b (7); note 265)
        QueueRefusalFixtures.refusedBeforeTheGate(noticeJob, () -> approveAs(READING_APPROVER, approved.getId(), "notice-approved-comment"));
        RunRequest reloadedApproved = RunRequestService.get().load(approved.getId());
        assertEquals(RequestStatus.APPROVED, reloadedApproved.getStatus());
        assertNull(reloadedApproved.getExecutedRunId(), "fixture: the approved request must not have executed yet");

        String approvedHtml = detail(READING_APPROVER, approved.getId(), null).getContentAsString();
        assertTrue(approvedHtml.contains("APPROVED"), "the approved, not yet executed request must render its APPROVED state");
        assertTrue(approvedHtml.contains(APPROVED_NOTICE), "the post-approval notice must be rendered while the request is approved and "
                        + "not yet executed");
        assertNoRunDisclosed("an approved but unexecuted request", NOTICE_JOB, approvedHtml);

        // (2) EXECUTED
        RunRequest executed = createNoticeRequest("notice: executed");
        approveAs(READING_APPROVER, executed.getId(), "notice-executed-comment");
        j.waitUntilNoActivity();
        RunRequest reloadedExecuted = RunRequestService.get().load(executed.getId());
        assertNotNull(reloadedExecuted.getExecutedRunId(), "fixture: the approved request must have executed");
        String executedHtml = detail(READING_APPROVER, executed.getId(), "50").getContentAsString();
        assertTrue(executedHtml.contains("EXECUTED"), "an executed request must render its EXECUTED state");
        assertTrue(showsRun(executedHtml, NOTICE_JOB, 1), "an executed request must point at the run it produced");
        assertFalse(executedHtml.contains(APPROVED_NOTICE), "the post-approval notice must be gone once the approval has executed");

        // (3) REJECTED and (4) CANCELLED: the approved state must not be rendered at all
        RunRequest rejected = createNoticeRequest("notice: rejected");
        rejectAs(READING_APPROVER, rejected.getId(), "notice-rejected-comment");
        String rejectedHtml = detail(READING_APPROVER, rejected.getId(), null).getContentAsString();
        assertTrue(rejectedHtml.contains("REJECTED"), "a rejected request must render its REJECTED state");
        assertFalse(rejectedHtml.contains("APPROVED"), "a rejected request must not render the approved state");
        assertFalse(rejectedHtml.contains(APPROVED_NOTICE), "a rejected request must not render the post-approval notice");

        RunRequest cancelled = createNoticeRequest("notice: cancelled");
        cancelAs(REQUESTER, cancelled.getId());
        String cancelledHtml = detail(READING_APPROVER, cancelled.getId(), null)
                .getContentAsString();
        assertTrue(cancelledHtml.contains("CANCELLED"), "a cancelled request must render its CANCELLED state");
        assertFalse(cancelledHtml.contains("APPROVED"), "a cancelled request must not render the approved state");
        assertFalse(cancelledHtml.contains(APPROVED_NOTICE), "a cancelled request must not render the post-approval notice");
    }

    /**
     * T-UI-06 (regression, ui-dev {@code e98d211}): following the recent-run size control must not
     * put a crumb in the address bar.
     *
     * While that control was a {@code <form>}, Jenkins core's own JavaScript added a crumb to every
     * form on the page, so choosing a size produced
     * {@code ?runs=20&Jenkins-Crumb=41855d…&json=%7B…%7D} — observed in a browser against a real
     * Jenkins. Core offers no opt-out, so the only fix was to stop using a form: the control is now
     * a group of links. A crumb in a GET query is a CSRF token written into browser history, proxy
     * logs and {@code Referer} headers, which is why this is pinned rather than left to review.
     *
     * The row does not depend on HtmlUnit reproducing core's injection: a reverted implementation
     * has no per-size links at all, so step (1) fails first and the regression is caught either
     * way.
     */
    @Test
    public void t_ui_06_runsControlNavigatesWithoutACrumbInTheUrl() throws Exception {
        RunRequest request = createRequest(READING_APPROVER, "runs-control crumb regression");
        HtmlPage page = detailPage(READING_APPROVER, request.getId());

        // (1) the control offers a plain link per allowed size - being a link, and not a form, is
        // exactly what keeps core's crumb injection away from it
        for (String allowed : ALLOWED) {
            assertNotNull(runsAnchor(page, allowed), "the recent-run control must offer a plain link for runs=" + allowed
                            + "; a <form> would be given a crumb by core's JavaScript");
        }

        // (2) following it really works - a dead link would satisfy the URL checks below
        HtmlPage after = runsAnchor(page, "10").click();
        WebResponse response = after.getWebResponse();
        assertEquals(200, response.getStatusCode(), "following the runs control must render the screen");
        assertWindowIn("after following the runs=10 link", response.getContentAsString(), 10);

        // (3) and the address it landed on carries the chosen size and nothing else
        String url = after.getUrl().toString();
        assertTrue(url.contains("runs=10"), "the chosen window size must be in the query: " + url);
        assertFalse(url.contains("Jenkins-Crumb"), "a crumb must never reach the query string of a read-only screen: " + url);
        assertFalse(url.contains("json="), "no form payload may reach the query string: " + url);

        // (4) the active size stays visible on the screen, which is what the removed <select>'s
        // selected option used to convey: exactly one link is marked current (ui-dev renders
        // aria-current="true"), and it is the one just followed
        assertNotEquals("", runsAnchor(after, "10").getAttribute("aria-current"), "the active window size must be marked as current on its own link");
        for (String other : ALLOWED) {
            if (!"10".equals(other)) {
                assertEquals("", runsAnchor(after, other).getAttribute("aria-current"), "only the active window size may be marked current, not runs=" + other);
            }
        }
    }

    /**
     * T-UI-294 (#31, retired e2e script rest-screens-v2.sh #2; the request page's counterpart of the dashboard
     * and History columns of RunTableColumnsTest): on a2's view of a PENDING request, the table that
     * follows the heading "Recent Runs of This Job" has the header cells exactly Build, Result, Started,
     * Duration, in that order. Premise: it lists the latest run, #12, so it is the recent-run table and
     * not an empty placeholder.
     */
    @Test
    public void t_ui_294_recentRunsTableHasTheFourColumns() throws Exception {
        RunRequest request = createRequest(READING_APPROVER, "recent-run columns");
        HtmlPage page = detailPage(READING_APPROVER, request.getId());
        DomElement table = recentRunsTable(page);
        assertTrue(showsRun(table.asXml(), JOB, BUILDS), "premise: the recent-run table lists run #" + BUILDS);
        List<String> headers = new ArrayList<>();
        for (DomNode th : table.querySelectorAll("thead th")) {
            headers.add(th.asNormalizedText().trim());
        }
        assertEquals(Arrays.asList("Build", "Result", "Started", "Duration"), headers,
                "the recent-run table's columns must be exactly Build, Result, Started, Duration");
    }

    /**
     * T-UI-295 (#31, retired e2e script rest-screens-v2.sh #5; low, the wording is the screen's, note 40): the
     * recent-run size links ({@code ?runs=}) sit in a {@code nav} labelled (aria-labelledby) by an
     * element reading "Runs to show:", and one {@code .jenkins-description} sentence starting with
     * "Showing" names the number of runs shown: 5 by default; 10 and no longer 5 after {@code ?runs=10};
     * the job's 12 and not 20 after {@code ?runs=20} (more than the job has). A constant sentence, or
     * one that echoes the parameter, fails.
     */
    @Test
    public void t_ui_295_runsControlIsLabelledAndTheCountIsStated() throws Exception {
        RunRequest request = createRequest(READING_APPROVER, "recent-run wording");
        HtmlPage page = detailPage(READING_APPROVER, request.getId());

        DomElement nav = null;
        for (DomNode n : page.querySelectorAll("nav")) {
            DomElement candidate = (DomElement) n;
            if (candidate.querySelector("a[href*='runs=']") != null) {
                assertTrue(nav == null, "one nav holds the recent-run size links");
                nav = candidate;
            }
        }
        assertNotNull(nav, "the recent-run size links must sit in a nav element");
        String labelId = nav.getAttribute("aria-labelledby");
        assertFalse(labelId.isEmpty(), "the size links' nav must be labelled: " + nav.asXml());
        DomElement label = page.getElementById(labelId);
        assertNotNull(label, "the nav's aria-labelledby must name an element of the page: " + labelId);
        assertEquals("Runs to show:", label.asNormalizedText().trim(), "the size control's label");

        String byDefault = showingSentence(page);
        assertTrue(number(5).matcher(byDefault).find(), "by default the sentence must name the 5 runs shown: " + byDefault);
        String ten = showingSentence(runsAnchor(page, "10").click());
        assertTrue(number(10).matcher(ten).find(), "with runs=10 the sentence must name the 10 runs shown: " + ten);
        assertFalse(number(5).matcher(ten).find(), "with runs=10 the sentence must no longer name 5: " + ten);
        String twenty = showingSentence(runsAnchor(page, "20").click());
        assertTrue(number(BUILDS).matcher(twenty).find(), "with runs=20 the job's " + BUILDS + " runs are all shown, and the"
                + " sentence must name " + BUILDS + ": " + twenty);
        assertFalse(number(20).matcher(twenty).find(), "with runs=20 only " + BUILDS + " runs exist, so the sentence must not"
                + " claim 20: " + twenty);
    }

    // ---------------------------------------------------------------- assertions

    /** Fetches the screen and asserts the window it renders, for one {@code runs} value. */
    private void assertWindow(String label, String requestId, String runsValue, int expected)
            throws Exception {
        WebResponse response = detail(READING_APPROVER, requestId, runsValue);
        assertEquals(200, response.getStatusCode(), label + " must render");
        assertWindowIn(label, response.getContentAsString(), expected);
    }

    /**
     * The user-facing guarantee for ANY {@code runs} value, whatever the screen decides to do with
     * it: a rendered page, and no sign of a parse failure. Returns the HTML so the caller can go
     * on to assert the window.
     */
    private static String assertRendersWithoutParseFailure(String label, WebResponse response) {
        assertEquals(200, response.getStatusCode(), label + " must not produce an error page");
        String html = response.getContentAsString();
        assertFalse(html.contains("NumberFormatException"), label + " must not leak a parse failure into the page");
        assertFalse(html.contains("Stack Trace"), label + " must not render a stack trace");
        return html;
    }

    /**
     * The latest {@code expected} runs (capped by how many exist) must be listed, and the next
     * older one must not be - so a screen that simply lists everything fails.
     */
    private void assertWindowIn(String label, String html, int expected) {
        int shown = Math.min(expected, BUILDS);
        for (int number = BUILDS; number > BUILDS - shown; number--) {
            assertTrue(showsRun(html, JOB, number), label + ": run #" + number + " is inside the window and must be listed");
        }
        if (shown < BUILDS) {
            assertFalse(showsRun(html, JOB, BUILDS - shown), label + ": run #" + (BUILDS - shown) + " is outside the window and "
                            + "must not be listed");
        }
    }

    /**
     * The recent-run control's link for one window size, or {@code null} when the page renders no
     * such link. Matched on the query only, never on a DOM shape, and anchored at the end so that
     * {@code runs=10} cannot be satisfied by {@code runs=100000}.
     */
    private static HtmlAnchor runsAnchor(HtmlPage page, String size) {
        Pattern query = Pattern.compile("[?&]runs=" + Pattern.quote(size) + "$");
        for (HtmlAnchor anchor : page.getAnchors()) {
            if (query.matcher(anchor.getHrefAttribute()).find()) {
                return anchor;
            }
        }
        return null;
    }

    /** No run of the named job may be referenced by the page. */
    private void assertNoRunDisclosed(String label, String jobName, String html) {
        for (int number = 1; number <= BUILDS; number++) {
            assertFalse(showsRun(html, jobName, number), label + " must not disclose run #" + number + " of " + jobName);
        }
    }

    /**
     * True when the page references run {@code number} of {@code jobName}, either as the build
     * URL or as a {@code #<number>} token. The token must not be followed by another letter or
     * digit, so neither a longer build number nor a CSS colour can match.
     */
    private static boolean showsRun(String html, String jobName, int number) {
        return html.contains("job/" + jobName + "/" + number + "/")
                || Pattern.compile("#" + number + "(?![0-9A-Za-z])").matcher(html).find();
    }

    // ---------------------------------------------------------------- helpers

    /** The table that follows the heading "Recent Runs of This Job". */
    private static DomElement recentRunsTable(HtmlPage page) {
        List<DomElement> found = page.getByXPath("//h2[normalize-space(.)='Recent Runs of This Job']/following::table[1]");
        assertEquals(1, found.size(), "the request page must show a table under 'Recent Runs of This Job'");
        return found.get(0);
    }

    /** The one {@code .jenkins-description} of the main panel whose text starts with "Showing". */
    private static String showingSentence(HtmlPage page) {
        List<String> found = new ArrayList<>();
        for (DomNode n : page.getElementById("main-panel").querySelectorAll(".jenkins-description")) {
            String text = n.asNormalizedText().trim();
            if (text.startsWith("Showing")) {
                found.add(text);
            }
        }
        assertEquals(1, found.size(), "the recent-run table must be followed by one 'Showing ...' sentence: " + found);
        return found.get(0);
    }

    /** {@code n} as a whole number, not part of a longer one. */
    private static Pattern number(int n) {
        return Pattern.compile("(?<![0-9])" + n + "(?![0-9])");
    }


    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private RunRequest createRequest(String approver, String reason) {
        try (ACLContext ignored = as(REQUESTER)) {
            return RunRequestService.get().create(job, new LinkedHashMap<String, String>(),
                    reason, approver);
        }
    }

    private RunRequest createNoticeRequest(String reason) {
        try (ACLContext ignored = as(REQUESTER)) {
            return RunRequestService.get().create(noticeJob, new LinkedHashMap<String, String>(),
                    reason, READING_APPROVER);
        }
    }

    /** Refuses armed jobs before Batch Control's queue gate (QueueRefusalFixtures, note 265). */
    @TestExtension
    public static final class RefuseBeforeGate extends QueueRefusalFixtures.RefusingHandler {
    }

    private void approveAs(String userId, String requestId, String comment) {
        try (ACLContext ignored = as(userId)) {
            RunRequestService.get().approve(requestId, comment);
        }
    }

    private void rejectAs(String userId, String requestId, String comment) {
        try (ACLContext ignored = as(userId)) {
            RunRequestService.get().reject(requestId, comment);
        }
    }

    private void cancelAs(String userId, String requestId) {
        try (ACLContext ignored = as(userId)) {
            RunRequestService.get().cancel(requestId);
        }
    }

    /** The request detail screen as a live page, for the rows that follow its controls. */
    private HtmlPage detailPage(String userId, String requestId) throws Exception {
        return client(userId).getPage(
                new URL(j.getURL(), "batch-control/requests/" + requestId + "/"));
    }

    private WebResponse detail(String userId, String requestId, String runsValue)
            throws Exception {
        String relative = "batch-control/requests/" + requestId + "/";
        if (runsValue != null) {
            relative += "?runs=" + URLEncoder.encode(runsValue, StandardCharsets.UTF_8);
        }
        return client(userId)
                .getPage(new WebRequest(new URL(j.getURL(), relative), HttpMethod.GET))
                .getWebResponse();
    }

    private JenkinsRule.WebClient client(String userId) throws Exception {
        JenkinsRule.WebClient existing = clients.get(userId);
        if (existing != null) {
            return existing;
        }
        JenkinsRule.WebClient created = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false)
                .login(userId);
        clients.put(userId, created);
        return created;
    }
}
