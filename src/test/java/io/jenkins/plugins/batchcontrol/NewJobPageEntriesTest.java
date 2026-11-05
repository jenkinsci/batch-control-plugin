package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.Action;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.model.experimentalflags.UserExperimentalFlagsProperty;
import jenkins.model.menu.event.Event;
import jenkins.model.menu.event.JavaScriptEvent;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Core's new job page (user experimental flag {@code new-job-page.flag}): its entries and notices.
 * SPEC item 8 (D-66: requesting a run and a permission window "open a dialog on the current page")
 * with D-70 (on the new job page an action opens a dialog only through {@code Action#getEvent()}
 * returning core's {@code DialogEvent}); SPEC item 6 ("the page of an approval-required job always
 * shows a notice that manual runs need an approved request, with a link to the request screen";
 * the {@code blockTimer}/{@code blockUpstream} notice "naming the switch that blocks it and how to
 * clear it") and item 6a ("the job page shows whether the job is activated or on hold and links to
 * the activation request form"). Coverage inventory G-M11 and G-M15 (JenkinsRule part); matrix rows
 * T-UI-119, T-06-105 and T-06a-56 (note 269); T-UI-297 (a nested job's grant dialog) and T-UI-303 (the
 * notices' card) for issue #31 (note 369).
 *
 * <p>Core's {@code DialogEvent.of(url)} is a {@link JavaScriptEvent} whose attributes are
 * {@code type=dialog-opener} and {@code dialog-url=<url>}; core's dialog fetches that URL from the
 * job page, so the URL is resolved against the job page here. New-job-page pages are read without
 * JavaScript (core's new job page scripts do not run in HtmlUnit; ActivationPageLayoutTest).
 *
 * <p>Users: {@code u1} requester (Item/Read, Item/Build, BatchControl/Request), {@code g1}
 * (Item/Read, BatchControl/RequestGrant), {@code nobc} (Item/Read, Item/Build), {@code a1}
 * approver; all with the new job page on.
 *
 * <p>Written from docs/SPEC.md items 6, 6a and 8, docs/DECISIONS.md D-66 and D-70 and core's public
 * API only (no src/main knowledge).
 */
@WithJenkins
public class NewJobPageEntriesTest {

    private static final String FLAG = "new-job-page.flag";
    private static final String GRANT_ENTRY = "Request Change Permission";
    private static final Pattern MANUAL = Pattern.compile("(?i)manual");
    private static final Pattern APPROVAL = Pattern.compile("(?i)approv");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST_GRANT).everywhere().to("g1")
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("nobc")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(List.of("a1"));
        cfg.save();
        for (String user : new String[] {"u1", "g1", "nobc", "a1"}) {
            User.getById(user, true).addProperty(new UserExperimentalFlagsProperty(Map.of(FLAG, "true")));
        }
    }

    /**
     * T-UI-119 (G-M11): on the new job page of the approval-required job {@code batch-x}, u1's
     * Request Run action ({@code job/batch-x/batch-control}) opens core's dialog: its event is a
     * dialog opener whose URL, resolved against the job page, is exactly
     * {@code <root>job/batch-x/batch-control/dialog}. g1's "Request Change Permission" action opens
     * the dialog at exactly {@code <root>batch-control/grants/dialog?scopeFullName=batch-x} (one
     * parameter, no scope type). Guard: nobc is offered neither action.
     */
    @Test
    public void t_ui_119_newJobPageEntriesOpenTheRequestDialogs() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
        URL jobPage = new URL(j.getURL(), job.getUrl());

        Action requestRun = only(actions("u1", job, a -> "batch-control".equals(a.getUrlName()) && a.getIconFileName() != null),
                "u1's Request Run action");
        assertEquals(j.getURL() + "job/batch-x/batch-control/dialog", dialogUrl("u1", requestRun, jobPage),
                "the Request Run entry must open the dialog fragment of the job");

        Action grant = only(actions("g1", job, a -> GRANT_ENTRY.equals(a.getDisplayName()) && a.getIconFileName() != null),
                "g1's " + GRANT_ENTRY + " action");
        assertEquals(j.getURL() + "batch-control/grants/dialog?scopeFullName=batch-x", dialogUrl("g1", grant, jobPage),
                "the " + GRANT_ENTRY + " entry must open the grant dialog for the job with exactly one parameter");

        assertTrue(actions("nobc", job, a -> ("batch-control".equals(a.getUrlName()) || GRANT_ENTRY.equals(a.getDisplayName()))
                && a.getIconFileName() != null).isEmpty(), "guard: nobc must be offered neither entry");
    }

    /**
     * T-06-105 (G-M15): with the new job page on, u1's page of an approval-required job shows the
     * notice that manual runs need an approved request, linking {@code job/<name>/batch-control/};
     * the page of a job with {@code blockTimer} on names the switch and says to change it in the
     * job configuration. Guard: a job that needs no approval and blocks nothing shows neither.
     */
    @Test
    public void t_06_105_newJobPageShowsTheApprovalAndTriggerLockNotices() throws Exception {
        FreeStyleProject approval = j.createFreeStyleProject("np-approval");
        BatchControlJobProperty open = new BatchControlJobProperty(true);
        open.setBlockTimer(false);
        open.setBlockUpstream(false);
        setBatchControl(approval, open);
        HtmlPage page = UsabilityFixtures.htmlPage(j, "u1", approval.getUrl());
        DomElement notice = findApprovalNotice(page);
        assertNotNull(notice, "the new job page of an approval-required job must show the manual-run notice: "
                + UsabilityFixtures.excerpt(mainText(page)));
        assertTrue(linksTo(page, approval.getUrl() + "batch-control/"), "the new job page must link the request screen for u1");

        FreeStyleProject timer = j.createFreeStyleProject("np-timer");
        BatchControlJobProperty blocked = new BatchControlJobProperty(false);
        blocked.setBlockTimer(true);
        blocked.setBlockUpstream(false);
        setBatchControl(timer, blocked);
        String timerText = UsabilityFixtures.htmlPage(j, "u1", timer.getUrl()).asNormalizedText();
        assertTrue(Pattern.compile("(?i)block\\s*timer").matcher(timerText).find(),
                "the new job page must name the blockTimer switch: " + UsabilityFixtures.excerpt(timerText));
        assertTrue(timerText.toLowerCase(Locale.ROOT).contains("configur"), "the notice must say how to clear it (the job configuration)");

        FreeStyleProject free = BatchControlFixtures.uncontrolled(j.createFreeStyleProject("np-free"));
        HtmlPage freePage = UsabilityFixtures.htmlPage(j, "u1", free.getUrl());
        assertNull(findApprovalNotice(freePage), "guard: no manual-run notice on a job that needs no approval");
        assertFalse(Pattern.compile("(?i)block\\s*timer").matcher(freePage.asNormalizedText()).find(),
                "guard: no trigger-lock notice on a job that blocks nothing");
    }

    /**
     * T-06a-56 (G-M15): with the new job page on, the page of a job that was never activated says
     * it is not activated (or on hold) and links the activation form
     * ({@code batch-control-activation}); after an approved ACTIVATE it says it is activated, still
     * with the link.
     */
    @Test
    public void t_06a_56_newJobPageShowsTheActivationState() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("np-activation");
        BatchControlJobProperty cleared = new BatchControlJobProperty(true);
        cleared.setBlockTimer(false);
        cleared.setBlockUpstream(false);
        setBatchControl(job, cleared);

        String before = UsabilityFixtures.htmlPage(j, "u1", job.getUrl()).getWebResponse().getContentAsString();
        String lower = before.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("not activated") || lower.contains("on hold"),
                "the new job page of a never-activated job must say it is not activated or on hold");
        assertTrue(before.contains("batch-control-activation"), "the new job page must link the activation form");

        BatchControlFixtures.activate(job);
        String after = UsabilityFixtures.htmlPage(j, "u1", job.getUrl()).getWebResponse().getContentAsString();
        String afterLower = after.toLowerCase(Locale.ROOT);
        assertTrue(afterLower.contains("activated") && !afterLower.contains("not activated"),
                "after the approved ACTIVATE the new job page must say the job is activated");
        assertTrue(after.contains("batch-control-activation"), "the activated job's new page must still link the activation form");
    }

    /**
     * T-UI-297 (SPEC 8 D-66/D-70, as T-UI-119 for a job two folders deep; #31, retired drivers
     * r6/s4a.py, r7/a_ui.py, r7/n_newjob.py, r11/s_newmenu.py, the DEF-01 family): on the new job page
     * of the approval-required {@code ops/nightly/k}, g1's "Request Change Permission" action opens the
     * dialog at exactly {@code <root>batch-control/grants/dialog?scopeFullName=ops%2Fnightly%2Fk}
     * (resolved against the job page, so a relative URL that lands below the folders fails). g1's GET
     * of that URL answers 200 with the grant form's {@code scopeFullName} field holding
     * {@code ops/nightly/k}.
     */
    @Test
    public void t_ui_297_nestedJobGrantEntryOpensTheDialogForItsFullName() throws Exception {
        Folder ops = j.jenkins.createProject(Folder.class, "ops");
        Folder nightly = ops.createProject(Folder.class, "nightly");
        FreeStyleProject k = nightly.createProject(FreeStyleProject.class, "k");
        setBatchControl(k, new BatchControlJobProperty(true));
        assertEquals("ops/nightly/k", k.getFullName(), "fixture: the job is two folders deep");
        URL jobPage = new URL(j.getURL(), k.getUrl());

        Action grant = only(actions("g1", k, a -> GRANT_ENTRY.equals(a.getDisplayName()) && a.getIconFileName() != null),
                "g1's " + GRANT_ENTRY + " action on ops/nightly/k");
        String dialog = dialogUrl("g1", grant, jobPage);
        assertEquals(j.getURL() + "batch-control/grants/dialog?scopeFullName=ops%2Fnightly%2Fk", dialog,
                "the " + GRANT_ENTRY + " entry of a nested job must open the grant dialog at the root with the encoded full name");

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("g1");
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.getPage(new URL(dialog));
        assertEquals(200, page.getWebResponse().getStatusCode(), "the dialog URL must answer g1");
        List<String> scopes = new ArrayList<>();
        for (DomNode n : page.querySelectorAll("form[name=createGrantRequest] input[name=scopeFullName]")) {
            scopes.add(((DomElement) n).getAttribute("value"));
        }
        assertEquals(List.of("ops/nightly/k"), scopes, "the dialog's grant form must carry the nested job's full name as its scope");
    }

    /**
     * T-UI-303 (SPEC 6 notices on the job page; #72, retired drivers r9/s72.py and r9/s72b.py): with
     * the new job page on, u1's page of the approval-required {@code batch-x} renders exactly one
     * {@code .jenkins-card} titled "Batch Control", and it holds the manual-run notice, the link to
     * the request screen ({@code job/batch-x/batch-control/}) and every link of the main panel to it or
     * to the activation form ({@code batch-control-activation/}; premise: at least one of each). Core's
     * card titled "Legacy" (premise: rendered by core) holds no "Batch Control" text and none of those
     * links.
     */
    @Test
    public void t_ui_303_newJobPageNoticesSitInTheBatchControlCard() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
        HtmlPage page = UsabilityFixtures.htmlPage(j, "u1", job.getUrl());
        DomElement main = page.getElementById("main-panel");
        assertNotNull(main, "the new job page must have a main panel");

        List<DomElement> bcCards = cardsTitled(main, "Batch Control");
        assertEquals(1, bcCards.size(), "the new job page must render one card titled 'Batch Control': " + UsabilityFixtures.excerpt(mainText(page)));
        DomElement card = bcCards.get(0);
        DomElement notice = findApprovalNotice(page);
        assertNotNull(notice, "premise: the page shows the manual-run notice");
        assertTrue(isInside(notice, card), "the manual-run notice must sit in the Batch Control card: " + notice.asNormalizedText());

        String requestScreen = new URL(j.getURL(), job.getUrl() + "batch-control/").getPath();
        String activation = new URL(j.getURL(), job.getUrl() + "batch-control-activation/").getPath();
        int toRequest = 0;
        int toActivation = 0;
        for (DomNode n : main.querySelectorAll("a[href]")) {
            DomElement a = (DomElement) n;
            String path = page.getFullyQualifiedUrl(a.getAttribute("href")).getPath();
            if (path.equals(requestScreen) || path.equals(activation)) {
                assertTrue(isInside(a, card), "every link to " + path + " must sit in the Batch Control card: " + a.asXml());
                toRequest += path.equals(requestScreen) ? 1 : 0;
                toActivation += path.equals(activation) ? 1 : 0;
            }
        }
        assertTrue(toRequest >= 1, "premise: the card links the request screen");
        assertTrue(toActivation >= 1, "premise: the card links the activation form");

        List<DomElement> legacy = cardsTitled(main, "Legacy");
        assertEquals(1, legacy.size(), "premise: core renders its Legacy card on the new job page");
        String legacyText = legacy.get(0).asNormalizedText();
        assertFalse(legacyText.toLowerCase(Locale.ROOT).contains("batch control"), "core's Legacy card must hold no Batch Control"
                + " text (#72): " + UsabilityFixtures.excerpt(legacyText));
        for (DomNode n : legacy.get(0).querySelectorAll("a[href]")) {
            assertFalse(((DomElement) n).getAttribute("href").contains("batch-control"),
                    "core's Legacy card must hold no Batch Control link: " + ((DomElement) n).asXml());
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The {@code .jenkins-card} elements whose own title ({@code .jenkins-card__title} child) reads {@code title}. */
    private static List<DomElement> cardsTitled(DomElement main, String title) {
        List<DomElement> out = new ArrayList<>();
        for (DomNode n : main.querySelectorAll(".jenkins-card")) {
            for (DomNode child = n.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof DomElement e && (" " + e.getAttribute("class") + " ").contains(" jenkins-card__title ")
                        && title.equals(e.asNormalizedText().trim())) {
                    out.add((DomElement) n);
                }
            }
        }
        return out;
    }

    private static boolean isInside(DomNode node, DomNode ancestor) {
        for (DomNode n = node; n != null; n = n.getParentNode()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }


    private interface ActionFilter {
        boolean test(Action a);
    }

    private static List<Action> actions(String user, FreeStyleProject job, ActionFilter filter) {
        List<Action> out = new ArrayList<>();
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            for (Action a : job.getAllActions()) {
                if (filter.test(a)) {
                    out.add(a);
                }
            }
        }
        return out;
    }

    private static Action only(List<Action> found, String what) {
        assertEquals(1, found.size(), "exactly one " + what + " must be offered, got " + found);
        return found.get(0);
    }

    /** The dialog URL of {@code action}'s event as {@code user} sees it, resolved against the job page. */
    private static String dialogUrl(String user, Action action, URL jobPage) throws Exception {
        Event event;
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            event = action.getEvent();
        }
        assertTrue(event instanceof JavaScriptEvent, "the entry must open a dialog through core's DialogEvent, got " + event);
        Map<String, String> attributes = ((JavaScriptEvent) event).getAttributes();
        assertEquals("dialog-opener", attributes.get("type"), "the event must be core's dialog opener: " + attributes);
        String url = attributes.get("dialog-url");
        assertNotNull(url, "the dialog opener must name its URL: " + attributes);
        return new URL(jobPage, url).toExternalForm();
    }

    private static DomElement findApprovalNotice(HtmlPage page) {
        DomElement main = page.getElementById("main-panel");
        if (main == null) {
            return null;
        }
        DomElement best = null;
        for (DomElement element : main.getHtmlElementDescendants()) {
            String tag = element.getTagName();
            if ("script".equals(tag) || "style".equals(tag)) {
                continue;
            }
            String text = element.getTextContent();
            if (text == null || !MANUAL.matcher(text).find() || !APPROVAL.matcher(text).find()) {
                continue;
            }
            if (best == null || text.length() < best.getTextContent().length()) {
                best = element;
            }
        }
        return best;
    }

    private boolean linksTo(HtmlPage page, String rootRelative) throws Exception {
        String target = UsabilityFixtures.stripQueryAndSlash(new URL(j.getURL(), rootRelative).toExternalForm());
        DomElement main = page.getElementById("main-panel");
        for (HtmlAnchor anchor : (main == null ? page.getDocumentElement() : main).getElementsByTagName("a").stream()
                .filter(e -> e instanceof HtmlAnchor).map(e -> (HtmlAnchor) e).toList()) {
            String href = anchor.getHrefAttribute();
            if (href != null && !href.isEmpty()
                    && UsabilityFixtures.stripQueryAndSlash(page.getFullyQualifiedUrl(href).toExternalForm()).equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static String mainText(HtmlPage page) {
        DomElement main = page.getElementById("main-panel");
        return main == null ? page.asNormalizedText() : main.asNormalizedText();
    }
}
