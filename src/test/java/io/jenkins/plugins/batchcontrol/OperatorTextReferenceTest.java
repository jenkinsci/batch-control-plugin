package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.PluginWrapper;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.tasks.Mailer;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.mock_javamail.Mailbox;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.decideActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No text an operator reads outside the crawled pages carries an internal design reference (issue #31,
 * note 370): the help texts (T-UI-307, gap X-11, retired drivers browser/b10.mjs #13, browser/a19.mjs #2,
 * browser/audit/a19.mjs #3 and browser/audit/b1b.mjs #16) and the notification mails and CSV exports
 * (T-UI-308, gap X-12, browser/a19.mjs #3, browser/audit/a19.mjs #3, browser/audit/b10.mjs #15 and
 * browser/verify/v7.mjs #12).
 *
 * <p>Basis: no SPEC sentence; e2e-03 DEF-04 and matrix note 119 (T-08-47: a GRANT_VIOLATION detail and
 * changes.csv carry no "(D-..." or "SPEC") and the #31 crawl rule of the CI e2e (no "SPEC item", "D-61",
 * "DECISIONS.md" or "TEST-MATRIX" on a page a user sees), whose pattern {@link #DESIGN} is reused here. The
 * crawl reads page bodies only; help files are only status-checked there, and mails are not read.
 *
 * <p>Written from docs/SPEC.md items 6a, 7, 8, 9, 11, 12 and 13, docs/DECISIONS.md D-54, docs/reports/e2e-03.md
 * (DEF-04) and docs/TEST-MATRIX.md (T-08-47, T-13-21/22); the retired drivers were read only to learn what each
 * looked at. No src/main knowledge (the help directory is listed at run time from the loaded plugin).
 */
@WithJenkins
public class OperatorTextReferenceTest {

    /** The CI crawl's design-reference pattern (#31, r14/crawl.py DESIGN). */
    static final Pattern DESIGN = Pattern.compile("\\bSPEC (?:item|section)\\b|\\bD-\\d{2,3}[a-z]?\\b|\\bDECISIONS\\.md\\b|\\bTEST-MATRIX\\b");

    private static final String U1_MAIL = "requester.one@example.com";
    private static final String A1_MAIL = "alpha.one@example.com";

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        Mailbox.clearAll();
        JenkinsLocationConfiguration.get().setAdminAddress("batch-control@example.com");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        User.getById("u1", true).addProperty(new Mailer.UserProperty(U1_MAIL));
        User.getById("a1", true).addProperty(new Mailer.UserProperty(A1_MAIL));
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
        Mailbox.clearAll();
    }

    /**
     * T-UI-307 (DEF-04 rule, #31 crawl rule; #31 gap X-11): every help text of Batch Control is fetched as a
     * browser fetches it: (a) each help button ({@code [helpurl]}) whose URL belongs to Batch Control on
     * admin's {@code manage/configure}, {@code manage/batch-control-configuration/} and
     * {@code job/batch-x/configure}; (b) each {@code .html} file under the loaded plugin's {@code help/}
     * directory, through {@code plugin/batch-control/help/<file>}. No text matches {@link #DESIGN}. Guards:
     * the pattern finds "see SPEC item 8 (D-61)"; each fetch answers 200 with non-blank text; the global and
     * the job configure pages each yield at least one Batch Control help button, and the help directory at
     * least one file, so a scan that reads nothing fails.
     */
    @Test
    public void t_ui_307_helpTextsCarryNoInternalReference() throws Exception {
        assertTrue(DESIGN.matcher("see SPEC item 8 (D-61)").find(), "guard: the pattern finds a design reference");
        FreeStyleProject job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));

        Map<String, String> sources = new LinkedHashMap<>();
        for (String page : new String[] {"manage/configure", "manage/batch-control-configuration/", "job/batch-x/configure"}) {
            Set<String> urls = pluginHelpUrls(page);
            if (!page.startsWith("manage/batch-control-configuration")) {
                assertFalse(urls.isEmpty(), "guard: " + page + " must offer at least one Batch Control help button");
            }
            for (String url : urls) {
                sources.putIfAbsent(url, page);
            }
        }

        PluginWrapper plugin = j.jenkins.getPluginManager().getPlugin("batch-control");
        assertNotNull(plugin, "premise: the plugin under test is loaded");
        URL base = plugin.baseResourceURL;
        assertTrue(base != null && "file".equals(base.getProtocol()), "premise: the plugin's resources are a directory, was " + base);
        Path help = Paths.get(base.toURI()).resolve("help");
        assertTrue(Files.isDirectory(help), "guard: the plugin ships a help directory at " + help);
        List<String> files;
        try (Stream<Path> walk = Files.walk(help)) {
            files = walk.filter(p -> p.toString().endsWith(".html")).map(p -> help.relativize(p).toString().replace('\\', '/'))
                    .sorted().collect(Collectors.toList());
        }
        assertFalse(files.isEmpty(), "guard: the help directory holds at least one .html file");
        for (String file : files) {
            sources.putIfAbsent(j.contextPath + "/plugin/batch-control/help/" + file, "help/" + file);
        }

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            URL url = new URL(j.getURL(), e.getKey().startsWith("/") ? e.getKey().substring(j.contextPath.length() + 1) : e.getKey());
            WebResponse r = wc.getPage(new WebRequest(url, HttpMethod.GET)).getWebResponse();
            assertEquals(200, r.getStatusCode(), "guard: the help text " + url + " (from " + e.getValue() + ") must load");
            String text = textOf(r.getContentAsString());
            assertFalse(text.isBlank(), "guard: the help text " + url + " must not be blank");
            Matcher m = DESIGN.matcher(text);
            if (m.find()) {
                problems.add(url + " (from " + e.getValue() + "): '" + m.group() + "' in " + around(text, m.start()));
            }
        }
        assertTrue(problems.isEmpty(), "no help text may carry an internal design reference: " + problems);
    }

    /**
     * T-UI-308 (DEF-04 rule, T-08-47 extended; #31 gap X-12): with e-mail notifications on (u1 and a1 have
     * Mailer addresses), u1's run requests on {@code batch-x} are approved (the run fails: a run record and
     * an incident), rejected, cancelled, left to expire (pendingTimeoutHours 1, plugin clock +2 h, expiry
     * work) and, on {@code move-x}, invalidated by a rename; u1's grant requests on {@code batch-x} are
     * approved, rejected and left to expire; u1's activation of {@code new-x} is approved; a timer cause on
     * the not-activated {@code timer-x} is refused; the administrator changes {@code batch-x}'s description.
     * No subject or body of any mail to u1 or a1, and no line of {@code runs.csv}, {@code requests.csv},
     * {@code changes.csv} or {@code incidents.csv} (admin, the days around now), matches {@link #DESIGN}.
     * Guards: some mail names each of the eight requests; {@code requests.csv} (the run request history,
     * SPEC 12) names each of the five run requests and the states EXECUTED, REJECTED, CANCELLED, EXPIRED and
     * INVALIDATED; {@code changes.csv} holds ACTIVATED,
     * TRIGGER_BLOCKED, CONFIGURE and RENAME rows; {@code runs.csv} and {@code incidents.csv} name
     * {@code batch-x}. GRANT_VIOLATION wording is T-08-47.
     */
    @Test
    public void t_ui_308_mailsAndCsvExportsCarryNoInternalReference() throws Exception {
        cfg.setEmailNotifications(true);
        cfg.setPendingTimeoutHours(1);
        cfg.save();
        FreeStyleProject job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
        BatchControlFixtures.activate(job);
        job.getBuildersList().add(new FailureBuilder());
        FreeStyleProject move = j.createFreeStyleProject("move-x");
        setBatchControl(move, new BatchControlJobProperty(true));
        FreeStyleProject inactive = j.createFreeStyleProject("new-x");
        setBatchControl(inactive, new BatchControlJobProperty(true));
        FreeStyleProject timer = j.createFreeStyleProject("timer-x");
        setBatchControl(timer, new BatchControlJobProperty(true));
        assertFalse(ActivationService.get().isActivated(timer), "fixture: timer-x is not activated");

        String executed = submitRunOk(j, "u1", job, "run to be executed", "a1");
        assertSuccess(decideRun(j, "a1", executed, "approve", "ok"), "fixture: approval");
        j.waitUntilNoActivity();
        String rejected = submitRunOk(j, "u1", job, "run to be rejected", "a1");
        assertSuccess(decideRun(j, "a1", rejected, "reject", "not this month"), "fixture: rejection");
        String cancelled = submitRunOk(j, "u1", job, "run to be cancelled", "a1");
        assertSuccess(ApproverFormFixtures.post(j, "u1", "batch-control/requests/" + cancelled + "/cancel", List.of()),
                "fixture: cancel");
        String expired = submitRunOk(j, "u1", job, "run left to expire", "a1");
        String invalidated = submitRunOk(j, "u1", move, "run on a job about to be renamed", "a1");
        move.renameTo("moved-x");

        String grantApproved = submitGrantOk(j, "u1", "batch-x", List.of("CONFIGURE"), 30, "grant to be approved", null, "a1");
        assertSuccess(decideGrant(j, "a1", grantApproved, "approve", "ok"), "fixture: grant approval");
        String grantRejected = submitGrantOk(j, "u1", "batch-x", List.of("DELETE"), 30, "grant to be rejected", null, "a1");
        assertSuccess(decideGrant(j, "a1", grantRejected, "reject", "not now"), "fixture: grant rejection");
        String grantExpired = submitGrantOk(j, "u1", "batch-x", List.of("CONFIGURE"), 15, "grant left to expire", null, "a1");
        String activation = submitActivationOk(j, "u1", inactive, "ACTIVATE", "go live", "a1");
        assertSuccess(decideActivation(j, "a1", activation, "approve", "ok"), "fixture: activation approval");

        assertNull(timer.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "fixture: the timer of timer-x is refused");
        job.setDescription("changed by the administrator");

        BatchClock.setForTest(Clock.offset(Clock.systemDefaultZone(), Duration.ofHours(2)));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
        BatchClock.reset();
        j.waitUntilNoActivity();
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(executed).getStatus(), "premise: EXECUTED");
        assertEquals(RequestStatus.EXPIRED, RunRequestService.get().load(expired).getStatus(), "premise: EXPIRED");
        assertEquals(RequestStatus.INVALIDATED, RunRequestService.get().load(invalidated).getStatus(), "premise: INVALIDATED");
        assertEquals(RequestStatus.EXPIRED, GrantRequestService.get().load(grantExpired).getStatus(), "premise: grant EXPIRED");

        List<String> ids = List.of(executed, rejected, cancelled, expired, invalidated, grantApproved, grantRejected, activation);
        List<String> mails = awaitMails(ids);
        List<String> problems = new ArrayList<>();
        for (String mail : mails) {
            Matcher m = DESIGN.matcher(mail);
            if (m.find()) {
                problems.add("mail: '" + m.group() + "' in " + around(mail, m.start()));
            }
        }

        String period = "?from=" + LocalDate.now().minusDays(2) + "&to=" + LocalDate.now().plusDays(2);
        Map<String, List<String>> expected = new LinkedHashMap<>();
        List<String> requestTokens = new ArrayList<>(List.of(executed, rejected, cancelled, expired, invalidated));
        requestTokens.addAll(List.of("EXECUTED", "REJECTED", "CANCELLED", "EXPIRED", "INVALIDATED"));
        expected.put("requests.csv", requestTokens);
        expected.put("changes.csv", List.of("ACTIVATED", "TRIGGER_BLOCKED", "CONFIGURE", "RENAME"));
        expected.put("runs.csv", List.of("batch-x"));
        expected.put("incidents.csv", List.of("batch-x"));
        for (Map.Entry<String, List<String>> e : expected.entrySet()) {
            WebResponse csv = ApproverFormFixtures.get(j, "admin", "batch-control/history/" + e.getKey() + period);
            assertEquals(200, csv.getStatusCode(), "fixture: admin downloads " + e.getKey());
            String body = csv.getContentAsString();
            for (String token : e.getValue()) {
                assertTrue(body.contains(token), "guard: " + e.getKey() + " must hold " + token + ":\n" + body);
            }
            for (String line : body.split("\\R")) {
                Matcher m = DESIGN.matcher(line);
                if (m.find()) {
                    problems.add(e.getKey() + ": '" + m.group() + "' in " + line);
                }
            }
        }
        assertTrue(problems.isEmpty(), "no mail and no CSV line may carry an internal design reference: " + problems);
    }

    // ---------------------------------------------------------------- helpers

    /** The help URLs of the Batch Control help buttons on {@code page} as admin. */
    private Set<String> pluginHelpUrls(String page) throws Exception {
        HtmlPage html = UsabilityFixtures.htmlPage(j, "admin", page);
        assertEquals(200, html.getWebResponse().getStatusCode(), "fixture: admin opens " + page);
        Set<String> out = new LinkedHashSet<>();
        for (DomNode n : html.querySelectorAll("[helpurl]")) {
            String url = ((DomElement) n).getAttribute("helpurl");
            if (url.contains("batchcontrol") || url.contains("batch-control")) {
                out.add(url);
            }
        }
        return out;
    }

    /** Subject and body of every mail to u1 and a1, once each of {@code ids} is named by some mail. */
    private static List<String> awaitMails(List<String> ids) throws Exception {
        long deadline = System.currentTimeMillis() + NotificationCapture.DELIVERY_TIMEOUT_MS;
        List<String> mails = readMails();
        while (!allNamed(mails, ids) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100); // polling for asynchronous delivery, not waiting for an expiry
            mails = readMails();
        }
        for (String id : ids) {
            assertTrue(mails.stream().anyMatch(m -> m.contains(id)), "guard: some mail to u1 or a1 must name request " + id
                    + "; mails: " + mails.size());
        }
        return mails;
    }

    private static boolean allNamed(List<String> mails, List<String> ids) {
        return ids.stream().allMatch(id -> mails.stream().anyMatch(m -> m.contains(id)));
    }

    private static List<String> readMails() throws Exception {
        List<String> out = new ArrayList<>();
        for (String address : new String[] {U1_MAIL, A1_MAIL}) {
            for (Message message : new ArrayList<>(Mailbox.get(address))) {
                out.add(message.getSubject() + "\n" + content(message.getContent()));
            }
        }
        return out;
    }

    private static String content(Object content) throws Exception {
        if (content instanceof Multipart multipart) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                sb.append(content(multipart.getBodyPart(i).getContent())).append('\n');
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    private static String textOf(String html) {
        return html.replaceAll("(?is)<(script|style)\\b.*?</\\1>", " ").replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    private static String around(String text, int at) {
        int from = Math.max(0, at - 80);
        int to = Math.min(text.length(), at + 80);
        return text.substring(from, to).replaceAll("\\s+", " ");
    }
}
