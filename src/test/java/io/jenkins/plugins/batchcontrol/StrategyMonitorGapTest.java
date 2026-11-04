package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Queue;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jenkins.security.QueueItemAuthenticator;
import jenkins.security.QueueItemAuthenticatorConfiguration;
import jenkins.security.QueueItemAuthenticatorDescriptor;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.core.Authentication;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.MONITOR_ID;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 2, scenarios L2-11 and L2-16 (matrix rows T-GAP-241 .. T-GAP-244, T-GAP-246, T-GAP-247,
 * T-GAP-262, T-GAP-263, note 277; T-02-194 and T-02-195 for e2e-09 DEF-02 and DEF-03, note 366): the {@code batch-control-strategy} monitor's actions, the review
 * surfaces and the SYSTEM-build warning. The restart bullet (L2-11 (F), an item whose directory is
 * removed between sessions) is {@link StrategyMonitorRestartGapTest}.
 *
 * <p>Basis: SPEC item 8 "the administrative monitor {@code batch-control-strategy} ... offers to install
 * the matching Batch Control strategy"; the D-50/D-50a line ("shows one fixed warning (no job names)");
 * SPEC item 2, the D-58b line ("The state ends only through the explicit 'Mark as reviewed' action
 * (POST, native Item/Configure or Overall/Administer, not by a grant)") and the D-58c line ("the review
 * surfaces list such runs"); DECISIONS D-58b (1) ("guarding covers the item and every item below it")
 * and D-50a ("cached for five minutes, and an authenticator that fails counts as SYSTEM (fail-safe)");
 * SPEC 6 usability ("every refusal ... tells the user in plain words why"; "no link leads to a 404");
 * SPEC 15 D-52 ({@code STRATEGY_CHANGE}); ARCHITECTURE section 1 (recording is independent of control);
 * CLAUDE.md (user input reaching HTML output or a redirect is validated); LIMITATIONS 12, 35 and 43.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md, docs/LIMITATIONS.md, docs/ARCHITECTURE.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class StrategyMonitorGapTest {

    private static final String BASE = "manage/administrativeMonitor/" + MONITOR_ID + "/";
    /** A sentence that confirms a review (the positive twin in T-GAP-247 shows that it is the page's confirmation). */
    private static final Pattern CONFIRMATION = Pattern.compile("(?i)\\b(was|has been|is now) marked as reviewed\\b");

    private JenkinsRule j;
    private final List<Path> restoreAfter = new ArrayList<>();

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        StrategyFixtures.changeControlOn();
    }

    @AfterEach
    public void tearDown() throws IOException {
        for (Path p : restoreAfter) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> walk = Files.walk(p)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
                }
            }
        }
        BatchClock.reset();
    }

    // ------------------------------------------------------------------ migrate / revert refusals

    /**
     * T-GAP-241 (L2-11, SPEC 8 "offers to install the matching Batch Control strategy", SPEC 6
     * usability): with FullControlOnceLoggedIn installed (no Batch Control variant exists) the
     * administrator's migrate is refused with 4xx, says that there is no Batch Control variant of the
     * installed strategy, and leaves it installed. With the plain project matrix installed, revert is
     * refused with 4xx saying there is nothing to revert, and the strategy stays. Both refusals are plain
     * (no "Oops!" page, no stack trace; SPEC 6 usability). Guard: migrate of the plain project matrix
     * installs the Batch Control matrix strategy.
     */
    @Test
    public void t_gap_241_migrateAndRevertWithoutAMatchRefuseWithAReason() throws Exception {
        StrategyFixtures.configureBuildAuthenticator();
        FullControlOnceLoggedInAuthorizationStrategy full = new FullControlOnceLoggedInAuthorizationStrategy();
        j.jenkins.setAuthorizationStrategy(full);
        WebResponse migrate = post("admin", "migrate", null, null);
        assertTrue(migrate.getStatusCode() >= 400 && migrate.getStatusCode() < 500, "migrate without a Batch Control variant must be"
                + " refused with 4xx, got " + migrate.getStatusCode());
        String text = RenameRefusalFixtures.visible(migrate.getContentAsString());
        String lower = text.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("batch control") && (lower.contains("no ") || lower.contains("not ")),
                "the refusal must say that there is no Batch Control variant: " + UsabilityFixtures.excerpt(text));
        assertSame(full, j.jenkins.getAuthorizationStrategy(), "the refused migrate leaves the strategy");

        ProjectMatrixAuthorizationStrategy plain = StrategyFixtures.matrix(new ProjectMatrixAuthorizationStrategy());
        j.jenkins.setAuthorizationStrategy(plain);
        WebResponse revert = post("admin", "revert", null, null);
        assertTrue(revert.getStatusCode() >= 400 && revert.getStatusCode() < 500, "revert of a plain strategy must be refused with"
                + " 4xx, got " + revert.getStatusCode());
        String revertText = RenameRefusalFixtures.visible(revert.getContentAsString());
        String revertLower = revertText.toLowerCase(Locale.ROOT);
        assertTrue(revertLower.contains("nothing to revert") || revertLower.contains("not a batch control"),
                "the refusal must say that there is nothing to revert: " + UsabilityFixtures.excerpt(revertText));
        assertSame(plain, j.jenkins.getAuthorizationStrategy(), "the refused revert leaves the strategy");

        // SPEC 6 usability: both refusals in plain words, no "Oops!" page or stack trace (checked last, both reported)
        List<String> problems = new ArrayList<>();
        for (String[] answer : new String[][] {{"migrate", text}, {"revert", revertText}}) {
            try {
                UsabilityFixtures.assertPlainRefusal(answer[0] + " refusal", answer[1], null);
            } catch (AssertionError e) {
                problems.add(e.getMessage());
            }
        }
        assertTrue(problems.isEmpty(), "SPEC 6 usability: " + problems);

        assertTrue(post("admin", "migrate", null, null).getStatusCode() < 400, "guard: migrate of the plain project matrix succeeds");
        assertSame(BatchControlMatrixAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "guard: the Batch Control matrix strategy is installed");
    }

    // ------------------------------------------------------------------ markReviewed

    /**
     * T-GAP-242 (L2-11, CLAUDE.md escaping, P-09 style existence hiding): {@code markReviewed} with
     * {@code item=nope-zz9} (no such item, nothing listed) answers 404 and the answer does not echo the
     * name. Guard: {@code markReviewed} of a listed job answers a redirect.
     */
    @Test
    public void t_gap_242_markReviewedOfAnUnknownItemIs404WithoutEcho() throws Exception {
        installMatrix();
        listedJob("listed-1");
        WebResponse unknown = post("admin", "markReviewed", "item=nope-zz9", null);
        assertEquals(404, unknown.getStatusCode(), "markReviewed of an unknown item must answer 404");
        assertFalse(unknown.getContentAsString().contains("nope-zz9"), "the answer must not echo the name");
        WebResponse listed = post("admin", "markReviewed", "item=listed-1", null);
        assertTrue(listed.getStatusCode() >= 300 && listed.getStatusCode() < 400, "guard: markReviewed of a listed job redirects, got "
                + listed.getStatusCode());
    }

    /**
     * T-GAP-243 (L2-11, CLAUDE.md "user input ... validated"): {@code markReviewed} of a listed job with
     * a malformed Referer ({@code http://x/ y}) and with a Referer outside the Jenkins context
     * ({@code http://evil.example/steal}) redirects to a page inside Jenkins, never to the Referer.
     * Guard: without a Referer the redirect is the same default page.
     */
    @Test
    public void t_gap_243_markReviewedNeverRedirectsToAForeignReferer() throws Exception {
        installMatrix();
        listedJob("listed-a");
        listedJob("listed-b");
        listedJob("listed-c");
        String root = j.getURL().getPath();

        String plain = post("admin", "markReviewed", "item=listed-c", null).getResponseHeaderValue("Location");
        assertNotNull(plain, "guard: the review redirects");
        assertTrue(location(plain).startsWith(root), "guard: the default page is inside Jenkins: " + plain);

        for (String[] c : new String[][] {{"listed-a", "http://x/ y"}, {"listed-b", "http://evil.example/steal"}}) {
            WebResponse answer = post("admin", "markReviewed", "item=" + c[0], c[1]);
            assertTrue(answer.getStatusCode() >= 300 && answer.getStatusCode() < 400, "the review of " + c[0] + " must redirect, got "
                    + answer.getStatusCode());
            String to = answer.getResponseHeaderValue("Location");
            assertNotNull(to, "the redirect must carry a Location");
            assertFalse(to.contains("evil.example") || to.contains("x/ y") || to.startsWith("http://x/"),
                    "the redirect must not follow the Referer '" + c[1] + "': " + to);
            assertTrue(location(to).startsWith(root), "the redirect must stay inside Jenkins: " + to);
        }
    }

    /**
     * T-GAP-244 (L2-11 (F), ARCHITECTURE 1, SPEC 15 D-52): the month's change file
     * ({@code changes/2026-09.jsonl}, premise: it exists) is replaced by a non-empty directory, so the
     * STRATEGY_CHANGE record cannot be written; the administrator's migrate of the plain project matrix
     * still installs the Batch Control matrix strategy.
     */
    @Test
    public void t_gap_244_migrateStillInstallsWhenItsRecordCannotBeWritten() throws Exception {
        StrategyFixtures.configureBuildAuthenticator();
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new ProjectMatrixAuthorizationStrategy()));
        Path month = j.jenkins.getRootDir().toPath().resolve("batch-control/changes/"
                + YearMonth.from(T0.atZone(ZoneOffset.UTC)) + ".jsonl");
        assertTrue(Files.isRegularFile(month), "premise (ARCHITECTURE 5): the month's change file is " + month);
        Files.delete(month);
        Files.createDirectories(month);
        Files.writeString(month.resolve("keep"), "x", StandardCharsets.UTF_8);
        restoreAfter.add(month);

        WebResponse migrate = post("admin", "migrate", null, null);
        assertTrue(migrate.getStatusCode() < 500, "migrate must not fail because its record cannot be written, got "
                + migrate.getStatusCode());
        assertSame(BatchControlMatrixAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "the Batch Control matrix strategy must be installed although its record could not be written");
        assertTrue(Files.isDirectory(month), "premise: the change file was not writable during the migrate");
    }

    // ------------------------------------------------------------------ review surfaces

    /**
     * T-GAP-246 (L2-11, SPEC 2 D-58c "the review surfaces list such runs"): bob (Run/Replay only through
     * his CONFIGURE window) replays {@code replay-me} #1 as #2. Manage Jenkins lists {@code replay-me}
     * and links its marked run #2 for the administrator. Guard: the unmarked run #1 is not linked as a
     * marked run.
     */
    @Test
    public void t_gap_246_monitorListsTheMarkedRunForTheAdministrator() throws Exception {
        installMatrix();
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "replay-me");
        job.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        j.buildAndAssertSuccess(job);
        StrategyFixtures.grant("bob", "replay-me", Arrays.asList(GrantAction.CONFIGURE));
        replay("bob", job, 1, "echo 'planted'");
        j.waitUntilNoActivity();
        assertNotNull(job.getBuildByNumber(2), "fixture: bob's replay must have run as #2");

        HtmlPage manage = manage();
        String text = manage.asNormalizedText();
        assertTrue(text.contains("replay-me"), "the monitor must list replay-me: " + UsabilityFixtures.excerpt(text));
        boolean linked = false;
        boolean firstLinked = false;
        for (HtmlAnchor a : manage.getAnchors()) {
            String href = a.getHrefAttribute();
            if (href.matches(".*job/replay-me/2/?$")) {
                linked = true;
            }
            if (href.matches(".*job/replay-me/1/?$")) {
                firstLinked = true;
            }
        }
        assertTrue(linked, "the monitor must link the marked run #2 for the administrator");
        assertFalse(firstLinked, "guard: the unmarked run #1 is not listed as a marked run");
    }

    /**
     * T-GAP-247 (L2-11, D-58b (1), SPEC 6 usability): the page {@code batch-control/reviewed?item=...}
     * after a review. For a job reviewed while nothing else guards it, it confirms the review (the
     * positive twin, which also shows what a confirmation is). For an unknown item it confirms nothing;
     * for an item still listed it confirms nothing. For job {@code fold9/jj9} reviewed while its folder
     * {@code fold9} is still listed as changed under a window, the page names {@code fold9} as what still
     * guards it.
     */
    @Test
    public void t_gap_247_reviewedPageConfirmsOnlyWhatWasReviewed() throws Exception {
        installMatrix();
        listedJob("done-1");
        listedJob("still-1");
        Folder folder = j.jenkins.createProject(Folder.class, "fold9");
        folder.createProject(FreeStyleProject.class, "jj9");
        Grant onFolder = StrategyFixtures.grant("bob", "fold9", Arrays.asList(GrantAction.CONFIGURE));
        Grant onJob = StrategyFixtures.grant("bob", "fold9/jj9", Arrays.asList(GrantAction.CONFIGURE));
        editAsBob(folder.getUrl());
        editAsBob(folder.getUrl() + "job/jj9/");
        revokeAsM1(onFolder);
        revokeAsM1(onJob);
        revokeAsM1(windowOn("done-1"));

        assertTrue(post("admin", "markReviewed", "item=done-1", null).getStatusCode() < 400, "fixture: review of done-1");
        String done = reviewedText("done-1");
        assertTrue(CONFIRMATION.matcher(done).find() && done.contains("done-1"),
                "guard: the page confirms the review of done-1: " + UsabilityFixtures.excerpt(done));

        String unknown = reviewedText("nope-zz9");
        assertFalse(CONFIRMATION.matcher(unknown).find(), "an unknown item gets no confirmation: " + UsabilityFixtures.excerpt(unknown));
        String still = reviewedText("still-1");
        assertFalse(CONFIRMATION.matcher(still).find(), "an item still listed gets no confirmation: " + UsabilityFixtures.excerpt(still));

        assertTrue(post("admin", "markReviewed", "item=fold9/jj9", null).getStatusCode() < 400, "fixture: review of fold9/jj9");
        String nested = reviewedText("fold9/jj9");
        String rest = nested.replace("fold9/jj9", "").replace("fold9 » jj9", "");
        assertTrue(rest.contains("fold9"), "the page must name the folder fold9 as what still guards the job: "
                + UsabilityFixtures.excerpt(nested));
        assertTrue(nested.toLowerCase(Locale.ROOT).contains("guard"), "the page must say the job is still guarded: "
                + UsabilityFixtures.excerpt(nested));
    }

    /**
     * T-02-194 (e2e-09 DEF-02, verified in e2e-10 item 2, note 366): with a job listed as changed under a
     * permission window, the monitor on Manage Jenkins tells the administrator to check the change on
     * the Changes tab: its text says "Changes tab", it links {@code batch-control/changes} with an anchor
     * reading "Changes", and "Change Records" (the old label) appears nowhere on the page.
     */
    @Test
    public void t_02_194_monitorNamesTheChangesTab() throws Exception {
        installMatrix();
        listedJob("tab-1");
        HtmlPage manage = manage();
        String text = manage.asNormalizedText();
        assertTrue(text.contains("tab-1"), "premise: the monitor lists tab-1: " + UsabilityFixtures.excerpt(text));
        assertTrue(text.contains("Changes tab"), "the monitor must name the Changes tab: " + UsabilityFixtures.excerpt(text));
        assertFalse(text.contains("Change Records"), "the old label \"Change Records\" must be gone: " + UsabilityFixtures.excerpt(text));
        String changes = UsabilityFixtures.stripQueryAndSlash(new URL(j.getURL(), "batch-control/changes").toExternalForm());
        List<String> seen = new ArrayList<>();
        boolean linked = false;
        for (HtmlAnchor a : manage.getAnchors()) {
            String href = a.getHrefAttribute();
            if (href == null || href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:")) {
                continue;
            }
            String target = UsabilityFixtures.stripQueryAndSlash(manage.getFullyQualifiedUrl(href).toExternalForm());
            if (target.equals(changes)) {
                seen.add(a.asNormalizedText());
                linked |= a.asNormalizedText().contains("Changes");
            }
        }
        assertTrue(linked, "the monitor must link the Changes tab with an anchor reading \"Changes\"; anchors to it: " + seen);
    }

    /**
     * T-02-195 (e2e-09 DEF-03, verified in e2e-10 item 3, note 366): "Mark as reviewed" redirects the
     * browser to {@code <root>/batch-control/reviewed?item=done-2} (the Location of the POST's answer,
     * not the monitor's own URL), and the page landed on (the redirect followed) has the breadcrumb "Batch Control ›
     * Marked as reviewed" and the title "Marked as reviewed": its last breadcrumb reads "Marked as
     * reviewed", the one before it "Batch Control", and no breadcrumb names the strategy monitor
     * ("authorization strategy", "grants need"). Premise: the page confirms the review (T-GAP-247).
     */
    @Test
    public void t_02_195_reviewedPageBreadcrumbNamesTheReview() throws Exception {
        installMatrix();
        listedJob("done-2");
        revokeAsM1(windowOn("done-2"));
        WebResponse review = post("admin", "markReviewed", "item=done-2", null);
        assertTrue(review.getStatusCode() >= 300 && review.getStatusCode() < 400, "Mark as reviewed must send the browser on to"
                + " the confirmation page (a redirect), got HTTP " + review.getStatusCode());
        String header = review.getResponseHeaderValue("Location");
        assertNotNull(header, "the redirect must carry a Location");
        URL landing = new URL(review.getWebRequest().getUrl(), header);
        String expectedPath = new URL(j.getURL(), "batch-control/reviewed").getPath();
        assertEquals(expectedPath, landing.getPath().replaceAll("/$", ""), "Mark as reviewed must land on batch-control/reviewed"
                + " (not on the monitor's URL), was " + landing);
        String query = landing.getQuery() == null ? "" : landing.getQuery();
        assertTrue(Arrays.stream(query.split("&")).anyMatch(kv -> kv.startsWith("item=")
                        && "done-2".equals(java.net.URLDecoder.decode(kv.substring(5), StandardCharsets.UTF_8))),
                "the landing URL must name item=done-2, was " + landing);
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.getPage(landing);
        assertEquals(200, page.getWebResponse().getStatusCode(), "premise: the page landed on renders");
        assertTrue(CONFIRMATION.matcher(page.asNormalizedText()).find(), "premise: the page confirms the review: "
                + UsabilityFixtures.excerpt(page.asNormalizedText()));
        List<String> crumbs = new ArrayList<>();
        for (org.htmlunit.html.DomNode n : page.querySelectorAll("li.jenkins-breadcrumbs__list-item")) {
            crumbs.add(n.asNormalizedText().trim());
        }
        assertTrue(crumbs.size() >= 2, "premise: the page has breadcrumbs: " + crumbs);
        assertEquals("Marked as reviewed", crumbs.get(crumbs.size() - 1), "the last breadcrumb must name the page: " + crumbs);
        assertEquals("Batch Control", crumbs.get(crumbs.size() - 2), "the page must sit under Batch Control: " + crumbs);
        for (String crumb : crumbs) {
            String lower = crumb.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("authorization strategy") || lower.contains("grants need"),
                    "no breadcrumb may name the strategy monitor: " + crumbs);
        }
        String title = page.getTitleText() == null ? "" : page.getTitleText().trim();
        assertTrue(title.startsWith("Marked as reviewed"), "the title must name the page, was '" + title + "'");
    }

    // ------------------------------------------------------------------ SYSTEM-build warning (L2-16)

    /**
     * T-GAP-262 (L2-16, D-50a "cached for five minutes", LIMITATIONS 35): change control on, the Batch
     * Control strategy, no build authenticator configured (builds run as SYSTEM). Manage Jenkins rendered
     * twice in a row shows the same SYSTEM-build warning both times, and the warning names no job.
     */
    @Test
    public void t_gap_262_systemBuildWarningIsStableAcrossRenders() throws Exception {
        installMatrix();
        j.createFreeStyleProject("sys-named-job");
        String first = manage().asNormalizedText();
        String second = manage().asNormalizedText();
        assertTrue(first.contains("SYSTEM"), "the SYSTEM-build warning is shown: " + UsabilityFixtures.excerpt(first));
        assertEquals(first.contains("SYSTEM"), second.contains("SYSTEM"), "the warning state must be the same on both renders");
        assertTrue(second.contains("SYSTEM"), "the SYSTEM-build warning is shown again: " + UsabilityFixtures.excerpt(second));
        assertFalse(second.contains("sys-named-job"), "the warning names no job");
    }

    /**
     * T-GAP-263 (L2-16 (F), D-50a "an authenticator that fails counts as SYSTEM (fail-safe)"): the only
     * configured build authenticator throws when asked. Manage Jenkins renders (200) and shows the
     * SYSTEM-build warning. The negative twin (a global default that gives builds a user identity shows
     * no warning) is T-08-60.
     */
    @Test
    public void t_gap_263_failingAuthenticatorCountsAsSystem() throws Exception {
        installMatrix();
        QueueItemAuthenticatorConfiguration.get().getAuthenticators().add(new ThrowingAuthenticator());
        assertFalse(QueueItemAuthenticatorConfiguration.get().getAuthenticators().isEmpty(), "premise: an authenticator is configured");
        String text = manage().asNormalizedText();
        assertTrue(text.contains("SYSTEM"), "a failing authenticator must count as SYSTEM: " + UsabilityFixtures.excerpt(text));
    }

    /** A build authenticator whose every answer is an unexpected exception. */
    public static class ThrowingAuthenticator extends QueueItemAuthenticator {
        @Override
        public Authentication authenticate2(Queue.Item item) {
            throw new IllegalStateException("test: the authenticator is broken");
        }

        @Override
        public Authentication authenticate2(Queue.Task task) {
            throw new IllegalStateException("test: the authenticator is broken");
        }

        @TestExtension("t_gap_263_failingAuthenticatorCountsAsSystem")
        public static class DescriptorImpl extends QueueItemAuthenticatorDescriptor {
        }
    }

    // ---------------------------------------------------------------- helpers

    private void installMatrix() {
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
    }

    /** A job bob changed under his CONFIGURE window (an HTTP save), so it is listed as changed under a grant. */
    private void listedJob(String name) throws Exception {
        j.createFreeStyleProject(name);
        StrategyFixtures.grant("bob", name, Arrays.asList(GrantAction.CONFIGURE));
        editAsBob("job/" + name + "/");
    }

    private void editAsBob(String itemUrl) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("bob");
        String xml = wc.goTo(itemUrl + "config.xml", "application/xml").getWebResponse().getContentAsString();
        String edited = xml.contains("<description/>") ? xml.replace("<description/>", "<description>edited by bob</description>")
                : xml.contains("<description>") ? xml.replaceFirst("<description>[^<]*</description>", "<description>edited by bob</description>")
                : xml.replaceFirst("(<(project|com\\.cloudbees\\.hudson\\.plugins\\.folder\\.Folder)[^>]*>)", "$1<description>edited by bob</description>");
        assertFalse(edited.equals(xml), "fixture: the edit must change " + itemUrl);
        WebRequest req = new WebRequest(wc.createCrumbedUrl(itemUrl + "config.xml"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody(edited);
        int code = wc.getPage(req).getWebResponse().getStatusCode();
        assertTrue(code < 400, "fixture: bob's edit of " + itemUrl + " under his window must be saved, got " + code);
    }

    private Grant windowOn(String item) {
        return GrantService.get().listActive().stream().filter(g -> item.equals(g.getScope().getFullName())).findFirst()
                .orElseThrow(() -> new AssertionError("fixture: a window on " + item));
    }

    private void revokeAsM1(Grant grant) throws Exception {
        StrategyFixtures.as("m1", () -> {
            GrantService.get().revoke(grant.getId());
            return null;
        });
    }

    private String reviewedText(String item) throws Exception {
        WebResponse page = ApproverFormFixtures.get(j, "admin", "batch-control/reviewed?item=" + item);
        assertEquals(200, page.getStatusCode(), "the reviewed page must render for " + item);
        return RenameRefusalFixtures.visible(page.getContentAsString());
    }

    private HtmlPage manage() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        HtmlPage manage = wc.goTo("manage/");
        assertEquals(200, manage.getWebResponse().getStatusCode(), "Manage Jenkins must render");
        return manage;
    }

    private static String location(String header) throws Exception {
        return header.startsWith("/") ? header : new URL(header).getPath();
    }

    private WebResponse post(String user, String action, String query, String referer) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        wc.getOptions().setRedirectEnabled(false);
        URL url = new URL(wc.createCrumbedUrl(BASE + action).toExternalForm() + (query == null ? "" : "&" + query));
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        if (referer != null) {
            req.setAdditionalHeader("Referer", referer);
        }
        return wc.getPage(req).getWebResponse();
    }

    private void replay(String user, WorkflowJob job, int number, String script) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        List<org.htmlunit.util.NameValuePair> params = new ArrayList<>();
        params.add(new org.htmlunit.util.NameValuePair("mainScript", script));
        params.add(new org.htmlunit.util.NameValuePair("json", "{\"mainScript\":\"" + script.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"}"));
        WebRequest req = new WebRequest(wc.createCrumbedUrl(job.getUrl() + number + "/replay/run"), HttpMethod.POST);
        req.setRequestParameters(params);
        wc.getPage(req);
    }
}
