package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.MONITOR_ID;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Access to the {@code batch-control-strategy} monitor's review action and its confirmation page, and
 * the review control's look (issue #31, note 369; retired drivers {@code e2e/r9/s86.py},
 * {@code e2e/r10/s86.py} and {@code e2e/r8/monitor.py}, verified in e2e-08 and e2e-10 item 3).
 * Matrix rows T-02-198 (who may open {@code batch-control/reviewed}), T-02-199 (the confirmation's
 * old monitor URL is gone), T-02-200 (a BatchControl/Manage holder cannot mark an item reviewed) and
 * T-UI-304 (the control is a button).
 *
 * <p>Users ({@link StrategyFixtures#matrix}): {@code admin} (Overall/Administer), {@code bob}
 * (RequestGrant; changes jobs under his CONFIGURE windows, so they are listed), {@code m1}
 * (BatchControl/Manage, not Administer), {@code alice} (Overall/Read and Item/Read only, no Batch
 * Control permission); added here: {@code u1} (BatchControl/Request, a run requester). Change
 * control on, the Batch Control matrix strategy, BatchClock fixed at T0 (time never passes for real).
 *
 * <p>Basis: SPEC item 2's D-58b line ("The state ends only through the explicit 'Mark as reviewed'
 * action (POST, native Item/Configure or Overall/Administer, not by a grant), which writes a
 * {@code GUARD_REVIEWED} record") and its #31 line ("a user who holds none of the Batch Control
 * permissions ... {@code /batch-control/} and every URL beneath it answer 404 ... A user who holds at
 * least one Batch Control permission but not the one a section needs still gets 403"); D-61
 * ("Existing URLs are unchanged" for the sections); docs/TEST-MATRIX.md T-02-195 and T-GAP-247 (the
 * confirmation page {@code batch-control/reviewed}). Written from those and the reports; no src/main
 * knowledge.
 */
@WithJenkins
public class StrategyMonitorReviewAccessTest {

    private static final String MONITOR = "manage/administrativeMonitor/" + MONITOR_ID + "/";
    private static final String ROOT_MONITOR = "administrativeMonitor/" + MONITOR_ID + "/";
    /** The confirmation sentence of the reviewed page (as T-GAP-247). */
    private static final Pattern CONFIRMATION = Pattern.compile("(?i)\\b(was|has been|is now) marked as reviewed\\b");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        StrategyFixtures.changeControlOn();
        BatchControlMatrixAuthorizationStrategy strategy = StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy());
        strategy.add(Jenkins.READ, PermissionEntry.user("u1"));
        strategy.add(Item.READ, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        j.jenkins.setAuthorizationStrategy(strategy);
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-02-198 (SPEC 2 #31: 403 for a holder of another Batch Control permission, 404 for a user who
     * holds none; D-58b: the review is the administrator's or a native Configure holder's): after the
     * administrator marked {@code rv-1} reviewed, GET {@code batch-control/reviewed?item=rv-1} answers
     * 200 to the administrator with the confirmation (guard), 403 to m1 (BatchControl/Manage) and to u1
     * (BatchControl/Request), neither of which carries the confirmation, and 404 to alice (no Batch
     * Control permission).
     */
    @Test
    public void t_02_198_reviewedPageIsTheAdministrators() throws Exception {
        listedJob("rv-1");
        revokeAsM1(windowOn("rv-1"));
        WebResponse review = post("admin", MONITOR + "markReviewed", "rv-1");
        assertTrue(review.getStatusCode() >= 300 && review.getStatusCode() < 400, "premise: the administrator's review redirects,"
                + " got HTTP " + review.getStatusCode());
        String path = "batch-control/reviewed?item=rv-1";

        WebResponse admin = get("admin", path);
        assertEquals(200, admin.getStatusCode(), "guard: the administrator opens the reviewed page");
        assertTrue(CONFIRMATION.matcher(visible(admin)).find(), "guard: the administrator's page confirms the review: "
                + excerpt(visible(admin)));
        for (String user : new String[] {"m1", "u1"}) {
            WebResponse refused = get(user, path);
            assertEquals(403, refused.getStatusCode(), user + " holds a Batch Control permission but may not open the"
                    + " reviewed page: SPEC 2 says 403");
            assertFalse(CONFIRMATION.matcher(visible(refused)).find(), user + "'s refusal must not carry the confirmation: "
                    + excerpt(visible(refused)));
        }
        assertEquals(404, get("alice", path).getStatusCode(),
                "alice holds no Batch Control permission: every URL beneath /batch-control/ answers 404 (SPEC 2)");
    }

    /**
     * T-02-199 (e2e-09 DEF-03 moved the confirmation from the monitor to {@code batch-control/reviewed},
     * verified in e2e-10 item 3): {@code administrativeMonitor/batch-control-strategy/reviewed?item=rv-2}
     * and its {@code manage/} form answer 404 to the administrator's GET and to the administrator's POST
     * (crumb), and the POST reviews nothing: {@code rv-2} stays listed and no GUARD_REVIEWED record is
     * written. Guard: {@code batch-control/reviewed?item=rv-2} answers the administrator 200.
     */
    @Test
    public void t_02_199_oldMonitorReviewedUrlIsGone() throws Exception {
        listedJob("rv-2");
        int records = reviewRecords().size();
        for (String base : new String[] {ROOT_MONITOR, MONITOR}) {
            assertEquals(404, get("admin", base + "reviewed?item=rv-2").getStatusCode(),
                    "GET " + base + "reviewed must answer 404 (the page moved to batch-control/reviewed)");
            assertEquals(404, post("admin", base + "reviewed", "rv-2").getStatusCode(),
                    "POST " + base + "reviewed must answer 404");
        }
        assertTrue(listedItems().contains("rv-2"), "the POSTs to the old URL review nothing: rv-2 stays listed");
        assertEquals(records, reviewRecords().size(), "the POSTs to the old URL write no GUARD_REVIEWED record");
        assertEquals(200, get("admin", "batch-control/reviewed?item=rv-2").getStatusCode(),
                "guard: the reviewed page answers at its own URL");
    }

    /**
     * T-02-200 (SPEC 2 D-58b: "Mark as reviewed (POST, native Item/Configure or Overall/Administer, not
     * by a grant)"; e2e-10 item 3): m1 (BatchControl/Manage, no Administer, no Configure) POSTs
     * {@code markReviewed?item=rv-3} with a crumb to the monitor, under {@code manage/} and at the root:
     * each answers 403, {@code rv-3} stays listed and no GUARD_REVIEWED record is written. Guard: the
     * administrator's same POST redirects, takes {@code rv-3} off the list and writes one GUARD_REVIEWED
     * record naming admin.
     */
    @Test
    public void t_02_200_manageHolderCannotMarkReviewed() throws Exception {
        listedJob("rv-3");
        revokeAsM1(windowOn("rv-3"));
        int records = reviewRecords().size();
        for (String base : new String[] {MONITOR, ROOT_MONITOR}) {
            assertEquals(403, post("m1", base + "markReviewed", "rv-3").getStatusCode(),
                    "m1 (BatchControl/Manage without Administer) must be refused by " + base + "markReviewed");
        }
        assertTrue(listedItems().contains("rv-3"), "the refused review leaves rv-3 listed: " + listedItems());
        assertEquals(records, reviewRecords().size(), "a refused review writes no GUARD_REVIEWED record");

        WebResponse admin = post("admin", MONITOR + "markReviewed", "rv-3");
        assertTrue(admin.getStatusCode() >= 300 && admin.getStatusCode() < 400, "guard: the administrator's review redirects,"
                + " got HTTP " + admin.getStatusCode());
        assertFalse(listedItems().contains("rv-3"), "guard: the administrator's review takes rv-3 off the list");
        List<ChangeRecord> after = reviewRecords();
        assertEquals(records + 1, after.size(), "guard: the administrator's review writes one GUARD_REVIEWED record");
        assertEquals("admin", after.get(after.size() - 1).getUser(), "guard: the record names the administrator");
    }

    /**
     * T-UI-304 (screen contract, e2e-08: "Mark as reviewed" rendered as a {@code jenkins-button}, not a
     * bare link; retired driver r8/monitor.py): on Manage Jenkins the monitor's row for {@code rv-4}
     * offers one review control, which targets {@code markReviewed} for {@code rv-4} (premise) and
     * carries core's {@code jenkins-button} class (for a form, its submit button).
     */
    @Test
    public void t_ui_304_markAsReviewedIsAButton() throws Exception {
        listedJob("rv-4");
        HtmlPage manage = manage();
        DomElement row = rowOf(manage, "rv-4");
        List<DomElement> controls = new ArrayList<>();
        for (DomNode n : row.querySelectorAll("[data-url]")) {
            DomElement e = (DomElement) n;
            if (e.getAttribute("data-url").contains("markReviewed")) {
                controls.add(e);
            }
        }
        for (DomNode n : row.querySelectorAll("form")) {
            DomElement form = (DomElement) n;
            if (form.getAttribute("action").contains("markReviewed")) {
                for (DomNode b : form.querySelectorAll("button, input[type=submit]")) {
                    controls.add((DomElement) b);
                }
            }
        }
        assertEquals(1, controls.size(), "premise: the row of rv-4 offers one Mark as reviewed control: " + row.asXml());
        DomElement control = controls.get(0);
        assertTrue(control.asNormalizedText().contains("Mark as reviewed") || control.getAttribute("value").contains("Mark as reviewed"),
                "premise: the control reads 'Mark as reviewed': " + control.asXml());
        String target = control.hasAttribute("data-url") ? control.getAttribute("data-url")
                : enclosing(control, "form").getAttribute("action") + " " + row.asXml();
        assertTrue(target.contains("markReviewed") && (target.contains("rv-4")),
                "premise: the control reviews rv-4: " + control.asXml());
        assertTrue((" " + control.getAttribute("class") + " ").contains(" jenkins-button "),
                "the Mark as reviewed control must be core's jenkins-button, not a bare link: " + control.asXml());
    }

    // ---------------------------------------------------------------- helpers

    /** A job bob changed under his CONFIGURE window (an HTTP save), so the monitor lists it. */
    private void listedJob(String name) throws Exception {
        j.createFreeStyleProject(name);
        StrategyFixtures.grant("bob", name, Arrays.asList(GrantAction.CONFIGURE));
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("bob");
        String itemUrl = "job/" + name + "/";
        String xml = wc.goTo(itemUrl + "config.xml", "application/xml").getWebResponse().getContentAsString();
        String edited = xml.contains("<description/>") ? xml.replace("<description/>", "<description>edited by bob</description>")
                : xml.contains("<description>") ? xml.replaceFirst("<description>[^<]*</description>", "<description>edited by bob</description>")
                : xml.replaceFirst("(<project[^>]*>)", "$1<description>edited by bob</description>");
        assertFalse(edited.equals(xml), "fixture: the edit must change " + name);
        WebRequest req = new WebRequest(wc.createCrumbedUrl(itemUrl + "config.xml"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody(edited);
        int code = wc.getPage(req).getWebResponse().getStatusCode();
        assertTrue(code < 400, "fixture: bob's edit of " + name + " under his window must be saved, got " + code);
        assertTrue(listedItems().contains(name), "premise: the monitor lists " + name + ": " + listedItems());
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

    private static List<ChangeRecord> reviewRecords() {
        return StrategyFixtures.records(ChangeType.GUARD_REVIEWED);
    }

    /** The full names the administrator sees listed by the monitor (as MarkedRunRestartFollowUpTest reads them). */
    private List<String> listedItems() throws Exception {
        return new ArrayList<>(MarkedRunRestartFollowUpTest.monitor(j).keySet());
    }

    /** The monitor's list item whose name ({@code code} element) is exactly {@code name}. */
    private static DomElement rowOf(HtmlPage manage, String name) {
        DomNode monitor = manage.querySelector("div[data-monitor-id=" + MONITOR_ID + "]");
        assertNotNull(monitor, "premise: Manage Jenkins shows the " + MONITOR_ID + " monitor");
        for (DomNode li : monitor.querySelectorAll("li")) {
            for (DomNode code : li.querySelectorAll("code")) {
                if (name.equals(code.asNormalizedText().trim()) && enclosing(code, "li") == li) {
                    return (DomElement) li;
                }
            }
        }
        throw new AssertionError("premise: the monitor has a row for " + name + ": " + excerpt(monitor.asNormalizedText()));
    }

    private HtmlPage manage() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage manage = wc.goTo("manage/");
        assertEquals(200, manage.getWebResponse().getStatusCode(), "Manage Jenkins must render");
        return manage;
    }

    private WebResponse get(String user, String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getOptions().setRedirectEnabled(false);
        return wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET)).getWebResponse();
    }

    /** {@code user} POSTs {@code path?item=<item>} with a crumb, redirects not followed. */
    private WebResponse post(String user, String path, String item) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getOptions().setRedirectEnabled(false);
        URL url = new URL(wc.createCrumbedUrl(path).toExternalForm() + "&item=" + URLEncoder.encode(item, StandardCharsets.UTF_8));
        return wc.getPage(new WebRequest(url, HttpMethod.POST)).getWebResponse();
    }

    /** The nearest ancestor of {@code node} with the tag {@code tag}. */
    private static DomElement enclosing(DomNode node, String tag) {
        for (DomNode n = node.getParentNode(); n != null; n = n.getParentNode()) {
            if (n instanceof DomElement e && tag.equals(e.getTagName())) {
                return e;
            }
        }
        throw new AssertionError("premise: " + node.getNodeName() + " is inside a " + tag);
    }

    private static String visible(WebResponse response) {
        return RenameRefusalFixtures.visible(response.getContentAsString());
    }
}
