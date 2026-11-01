package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.records;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-59 with D-48 (a browser form save shows the refusal "on a page with the standard layout"):
 * a move refused by change control, made from a browser, answers 403 with an HTML page in
 * Jenkins' standard layout, the message names the item as text (an item name with HTML-special
 * characters cannot inject markup), and nothing moves. Matrix rows T-SEC-63 (note 192), T-SEC-64,
 * T-SEC-72, T-08-128 (a move into the Jenkins root, D-71, note 261) and T-08-286 (the alert starts
 * with its text, e2e-06 DEF-03, note 366). The plain
 * answer for non-browser clients stays covered by T-SEC-53..56 (MoveChangeControlTest).
 *
 * <p>Fixture as T-SEC-53: u1 holds native Item/Move and an approved [CREATE, CONFIGURE] window on
 * the folder {@code team} (D-71: scope type ITEM), no Delete on the item. A link to the item on the refusal page is
 * allowed and not asserted either way.
 *
 * <p>Written from docs/SPEC.md item 8, docs/DECISIONS.md D-59/D-48 and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class MoveRefusalPageTest {

    /**
     * Allowed by Jenkins' item name check (which forbids {@code < > & ...}) but able to break out of
     * an HTML attribute if written unescaped. With a double quote where the file system can hold one
     * in the item's directory name, with single quotes on NTFS (TEST-MATRIX note 291).
     */
    static final String HOSTILE = PlatformFixtures.HOSTILE_ITEM_NAME;

    private JenkinsRule j;
    private Folder prod;
    private Folder team;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(RelocationAction.RELOCATE, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        prod = j.jenkins.createProject(Folder.class, "prod");
        team = j.jenkins.createProject(Folder.class, "team");
        prod.createProject(FreeStyleProject.class, HOSTILE);
        assertNotNull(prod.getItem(HOSTILE), "fixture: the item with the hostile name must exist");

        String id = submitGrantOk(j, "u1", "team", Arrays.asList("CREATE", "CONFIGURE"), 30,
                "maintenance in team", null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        assertEquals(1, GrantService.get().listActive().stream().filter(g -> "u1".equals(g.getUser())).count(),
                "fixture: u1 holds one active grant");
    }

    /**
     * T-SEC-63: u1's browser POSTs the folders plugin's move form ({@code <item>/move/move},
     * {@code destination=/team}, {@code Accept: text/html}). The answer is 403, an HTML page with
     * core's page header and breadcrumbs (not the bare "Error" page), whose text names the item
     * verbatim and whose DOM gained no attribute from the name; the item stays in {@code prod},
     * nothing arrives in {@code team}, and one GRANT_VIOLATION is recorded.
     */
    @Test
    public void t_sec_63_refusedBrowserMoveRendersStandardLayoutWithEscapedName() throws Exception {
        Item item = prod.getItem(HOSTILE);
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");
        wc.getOptions().setJavaScriptEnabled(false);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(item.getUrl() + "move/move"), HttpMethod.POST);
        request.setAdditionalHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        request.setRequestParameters(List.of(new NameValuePair("destination", "/team")));
        Page page = wc.getPage(request);
        String body = page.getWebResponse().getContentAsString();

        assertEquals(403, page.getWebResponse().getStatusCode(), "a refused browser move must answer 403: " + excerpt(body));
        assertTrue(page.getWebResponse().getContentType().startsWith("text/html"),
                "a browser gets an HTML page, got " + page.getWebResponse().getContentType());
        assertTrue(page instanceof HtmlPage, "the refusal must parse as an HTML page");
        HtmlPage html = (HtmlPage) page;
        UsabilityFixtures.assertNotBareErrorPage("refused move", html);
        assertNotNull(html.querySelector("#page-header"), "the refusal must have core's page header (standard layout): " + excerpt(body));
        assertFalse(html.querySelectorAll("li.jenkins-breadcrumbs__list-item").isEmpty(),
                "the refusal must render breadcrumbs (standard layout): " + excerpt(body));

        assertTrue(html.asNormalizedText().contains(HOSTILE),
                "the message must name the item as text, verbatim: " + excerpt(html.asNormalizedText()));
        for (HtmlElement e : html.getHtmlElementDescendants()) {
            assertFalse(e.hasAttribute("onmouseover") || e.hasAttribute("data-injected"),
                    "the item name must not inject attributes, found on <" + e.getTagName() + ">: " + excerpt(e.asXml()));
        }
        for (DomElement script : html.getElementsByTagName("script")) {
            assertFalse(script.getTextContent().contains("alert(1)"), "the item name must not reach a script");
        }

        assertNotNull(prod.getItem(HOSTILE), "the refused move must leave the item in prod");
        assertNull(team.getItem(HOSTILE), "nothing may arrive in team");
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(1, violations.size(), "the refused move is recorded once as GRANT_VIOLATION, got " + violations);
        assertEquals("u1", violations.get(0).getUser(), "the GRANT_VIOLATION names u1");
    }

    /**
     * T-SEC-64 (SPEC 8 D-59 line: "the refusal page links the item for a user who may read it"):
     * the browser refusal page of T-SEC-63 carries an anchor to the item's own page, which u1 may
     * read. A user who cannot read the item cannot reach its move endpoint at all (core answers
     * 404 before the move), so the negative half has no page to inspect (note 200).
     */
    @Test
    public void t_sec_64_refusalPageLinksTheItemForAReader() throws Exception {
        Item item = prod.getItem(HOSTILE);
        assertTrue(item.getACL().hasPermission2(hudson.model.User.getById("u1", true).impersonate2(), Item.READ),
                "premise: u1 may read the item");
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");
        wc.getOptions().setJavaScriptEnabled(false);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(item.getUrl() + "move/move"), HttpMethod.POST);
        request.setAdditionalHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        request.setRequestParameters(List.of(new NameValuePair("destination", "/team")));
        Page page = wc.getPage(request);
        assertEquals(403, page.getWebResponse().getStatusCode(), "premise: the move is refused");
        assertTrue(page instanceof HtmlPage, "premise: a browser gets an HTML page");
        HtmlPage html = (HtmlPage) page;
        String target = decode(new java.net.URL(j.getURL(), item.getUrl()).getPath());
        List<String> seen = new java.util.ArrayList<>();
        boolean linked = false;
        // only the page body: the breadcrumb bar may link the item for unrelated reasons
        org.htmlunit.html.DomNode main = html.querySelector("#main-panel");
        assertNotNull(main, "premise: the standard layout has a main panel");
        for (org.htmlunit.html.DomNode n : main.querySelectorAll("a[href]")) {
            String href = ((DomElement) n).getAttribute("href");
            String path = decode(html.getFullyQualifiedUrl(href).getPath());
            seen.add(path);
            if (path.equals(target) || path.equals(target.replaceAll("/$", ""))) {
                linked = true;
            }
        }
        assertTrue(linked, "the refusal page body must link the item " + target + " for a reader; links: " + seen);
    }

    /**
     * T-SEC-72 (backlog #83), rewritten for D-71: the browser refusal page of T-SEC-63 (u1 lacks
     * Delete on the item, Create on {@code team} comes from a window) links, in its body, a grant
     * request form pre-filled for what is missing. Since D-71 a DELETE window names the job itself,
     * so the link is the frozen prefill URL {@code batch-control/grants/new?scopeFullName=<item>}
     * (with {@code actions=DELETE} if it names an action) and never names the source folder
     * {@code prod} (before D-71 a FOLDER window on {@code prod} was also accepted; note 260), the
     * destination {@code team} (Create is not missing) or a {@code scopeType}. Following the link
     * as u1 answers 200 with the form's {@code scopeFullName} field holding the item's full name.
     */
    @Test
    public void t_sec_72_refusalPageLinksAPrefilledGrantRequestForm() throws Exception {
        Item item = prod.getItem(HOSTILE);
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");
        wc.getOptions().setJavaScriptEnabled(false);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(item.getUrl() + "move/move"), HttpMethod.POST);
        request.setAdditionalHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        request.setRequestParameters(List.of(new NameValuePair("destination", "/team")));
        Page page = wc.getPage(request);
        assertEquals(403, page.getWebResponse().getStatusCode(), "premise: the move is refused");
        HtmlPage html = (HtmlPage) page;
        org.htmlunit.html.DomNode main = html.querySelector("#main-panel");
        assertNotNull(main, "premise: the standard layout has a main panel");

        List<java.net.URL> forms = new java.util.ArrayList<>();
        List<String> all = new java.util.ArrayList<>();
        for (org.htmlunit.html.DomNode n : main.querySelectorAll("a[href]")) {
            java.net.URL url = html.getFullyQualifiedUrl(((DomElement) n).getAttribute("href"));
            all.add(url.toString());
            if (url.getPath().contains("batch-control/grants") && url.getQuery() != null
                    && url.getQuery().contains("scopeFullName=")) {
                forms.add(url);
            }
        }
        assertFalse(forms.isEmpty(), "the refusal page must link a pre-filled grant request form (scopeFullName=); links: " + all);
        for (java.net.URL url : forms) {
            String scope = queryValue(url, "scopeFullName");
            assertEquals(item.getFullName(), scope, "D-71: a pre-filled form must be for the Delete that is missing, on the"
                    + " item itself (not the folder " + prod.getFullName() + "), got scopeFullName=" + scope + " in " + url);
            assertTrue(url.getPath().replaceAll("/+$", "").endsWith("batch-control/grants/new"), "the link must be the prefill URL grants/new: " + url);
            assertFalse(("&" + url.getQuery()).contains("&scopeType="), "D-71: the link must carry no scopeType: " + url);
            String action = queryValue(url, "actions");
            assertTrue(action.isEmpty() || "DELETE".equals(action), "a named action must be the missing DELETE: " + url);
        }

        java.net.URL first = forms.get(0);
        JenkinsRule.WebClient follow = ApproverFormFixtures.client(j, "u1");
        follow.getOptions().setJavaScriptEnabled(false);
        Page form = follow.getPage(first);
        assertEquals(200, form.getWebResponse().getStatusCode(), "u1 must be able to open the linked form " + first);
        HtmlPage formPage = (HtmlPage) form;
        String expected = queryValue(first, "scopeFullName");
        boolean prefilled = false;
        for (org.htmlunit.html.DomNode n : formPage.querySelectorAll("[name=scopeFullName]")) {
            DomElement e = (DomElement) n;
            if (expected.equals(e.getAttribute("value")) || expected.equals(e.getTextContent().trim())) {
                prefilled = true;
            }
        }
        assertTrue(prefilled, "the linked form must carry scopeFullName=" + expected);
    }

    /**
     * T-08-291 (SPEC 8 D-59 move line "answers with a plain message naming what is missing", D-71;
     * backlog #83 as T-SEC-72; #31, retired driver r9/s83.py): u1 holds native Move but no window, so
     * both permissions of a move of {@code prod/x} into {@code team} are missing: Delete on the job and
     * Create in the regular folder (a CREATE window can apply there, D-71). The browser refusal (403)
     * links, in {@code #main-panel}, one pre-filled grant request form per missing permission: the
     * Delete form for {@code prod/x} (as T-SEC-72) and the Create form
     * {@code batch-control/grants/new?scopeFullName=team&actions=CREATE} (prefill URL of T-08-119),
     * and no form for the folder {@code prod}. Following the Create link as u1 answers 200 with
     * {@code team} filled and CREATE, not DELETE, checked; nothing moves. Guard first: while the
     * setUp's window gives Create on {@code team}, the same refusal links no form for {@code team}
     * (T-SEC-72), so the Create link is there because Create is missing. The link's
     * {@code actions=CREATE} is asserted on the URL; that the form reads {@code actions=} is T-08-119's,
     * not this row's ({@code team} is a regular folder, where CREATE is checked by default, so the
     * checked CREATE here would not notice a form that ignored the parameter).
     */
    @Test
    public void t_08_291_refusalLinksACreateWindowWhenCreateIsMissingToo() throws Exception {
        Item x = prod.createProject(FreeStyleProject.class, "x");
        hudson.model.User u1 = hudson.model.User.getById("u1", true);
        assertTrue(team.getACL().hasPermission2(u1.impersonate2(), Item.CREATE), "premise: the setUp's window gives u1 Create on team");
        java.util.Map<String, List<java.net.URL>> guard = grantFormLinksByScope(refusedBrowserMove(x, "/team"));
        assertEquals(java.util.Set.of("prod/x"), guard.keySet(), "guard: with Create on team from a window, the refusal links only the"
                + " Delete form for prod/x: " + guard);

        String window = WindowStateFixtures.windowId("u1", "team");
        try (hudson.security.ACLContext ignored = hudson.security.ACL.as2(
                hudson.model.User.getById("admin", true).impersonate2())) {
            GrantService.get().revoke(window);
        }
        assertFalse(team.getACL().hasPermission2(u1.impersonate2(), Item.CREATE), "premise: u1 has no Create on team now");
        assertFalse(x.getACL().hasPermission2(u1.impersonate2(), Item.DELETE), "premise: u1 has no Delete on prod/x");
        assertTrue(x.getACL().hasPermission2(u1.impersonate2(), RelocationAction.RELOCATE), "premise: u1 holds native Move");

        java.util.Map<String, List<java.net.URL>> links = grantFormLinksByScope(refusedBrowserMove(x, "/team"));
        assertEquals(java.util.Set.of("prod/x", "team"), links.keySet(), "the refusal must link one pre-filled form per missing"
                + " permission, Delete on prod/x and Create on team, and none for the folder prod: " + links);
        for (java.net.URL url : links.get("prod/x")) {
            String action = queryValue(url, "actions");
            assertTrue(action.isEmpty() || "DELETE".equals(action), "the form for prod/x is for the missing Delete: " + url);
        }
        assertEquals(1, links.get("team").size(), "one Create form for team: " + links.get("team"));
        java.net.URL create = links.get("team").get(0);
        assertTrue(create.getPath().replaceAll("/+$", "").endsWith("batch-control/grants/new"), "the Create link is the prefill URL"
                + " grants/new: " + create);
        assertEquals("CREATE", queryValue(create, "actions"), "the form for team must name the missing Create: " + create);
        assertFalse(("&" + create.getQuery()).contains("&scopeType="), "D-71: the link carries no scopeType: " + create);
        assertNotNull(prod.getItem("x"), "the refused move leaves x in prod");
        assertNull(team.getItem("x"), "nothing arrives in team");

        JenkinsRule.WebClient follow = ApproverFormFixtures.client(j, "u1");
        follow.getOptions().setJavaScriptEnabled(false);
        Page opened = follow.getPage(create);
        assertEquals(200, opened.getWebResponse().getStatusCode(), "u1 must be able to open the Create form " + create);
        assertTrue(opened instanceof HtmlPage, "the Create form is an HTML page");
        assertEquals("team", ItemScopeUiTest.scopeValue((HtmlPage) opened), "the Create form must be filled with team");
        assertTrue(ItemScopeUiTest.actionChecked((HtmlPage) opened, "CREATE"), "the Create form must start with CREATE checked");
        assertFalse(ItemScopeUiTest.actionChecked((HtmlPage) opened, "DELETE"), "the Create form must not check DELETE");
    }

    /** u1's browser move of {@code item} to {@code destination}, which must be refused with an HTML 403. */
    private HtmlPage refusedBrowserMove(Item item, String destination) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");
        wc.getOptions().setJavaScriptEnabled(false);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(item.getUrl() + "move/move"), HttpMethod.POST);
        request.setAdditionalHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        request.setRequestParameters(List.of(new NameValuePair("destination", destination)));
        Page page = wc.getPage(request);
        assertEquals(403, page.getWebResponse().getStatusCode(), "premise: the move is refused: "
                + excerpt(page.getWebResponse().getContentAsString()));
        assertTrue(page instanceof HtmlPage, "premise: a browser gets an HTML page");
        return (HtmlPage) page;
    }

    /** The {@code #main-panel} links to a grant request form carrying {@code scopeFullName=}, by that scope. */
    private static java.util.Map<String, List<java.net.URL>> grantFormLinksByScope(HtmlPage html) throws Exception {
        org.htmlunit.html.DomNode main = html.querySelector("#main-panel");
        assertNotNull(main, "premise: the standard layout has a main panel");
        java.util.Map<String, List<java.net.URL>> out = new java.util.TreeMap<>();
        for (org.htmlunit.html.DomNode n : main.querySelectorAll("a[href]")) {
            java.net.URL url = html.getFullyQualifiedUrl(((DomElement) n).getAttribute("href"));
            if (url.getPath().contains("batch-control/grants") && url.getQuery() != null && url.getQuery().contains("scopeFullName=")) {
                out.computeIfAbsent(queryValue(url, "scopeFullName"), k -> new java.util.ArrayList<>()).add(url);
            }
        }
        return out;
    }

    /**
     * T-08-128 (SPEC 8 D-59 move line and D-71, note 261): u1 holds native Move, a DELETE window
     * on the job {@code prod/x} (Delete at the source is not missing) and no Create in the Jenkins
     * root; moving {@code prod/x} into the root (folders plugin destination {@code /}) is refused
     * with 403 from a browser and from a script. A window's CREATE applies only to a regular folder,
     * so the refusal must not offer what cannot be granted: it says that an administrator must make
     * the move, names the missing Create, does not suggest requesting a Create window, and neither
     * page nor message links or names the grant request form {@code batch-control/grants/new}.
     * Nothing moves and the refusal is recorded as GRANT_VIOLATION naming u1 and {@code prod/x}
     * (how many records the immediate scripted repeat adds is not pinned). Guards: the same user moves
     * the job into the regular folder {@code team}, where the setUp's CREATE window applies, and
     * the administrator moves it into the root with the same destination value.
     */
    @Test
    public void t_08_128_moveIntoTheJenkinsRootNeedsAnAdministratorAndOffersNoWindow() throws Exception {
        Item x = prod.createProject(FreeStyleProject.class, "x");
        String id = submitGrantOk(j, "u1", "prod/x", Arrays.asList("DELETE"), 30, "retire x", null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval of the DELETE window by a1");
        hudson.model.User u1 = hudson.model.User.getById("u1", true);
        assertTrue(x.getACL().hasPermission2(u1.impersonate2(), Item.DELETE), "premise: u1 holds Delete on prod/x (window)");
        assertTrue(x.getACL().hasPermission2(u1.impersonate2(), RelocationAction.RELOCATE), "premise: u1 holds native Move");
        assertFalse(j.jenkins.getACL().hasPermission2(u1.impersonate2(), Item.CREATE), "premise: u1 has no Create in the Jenkins root");
        int violationsBefore = records(ChangeType.GRANT_VIOLATION).size();

        // browser
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");
        wc.getOptions().setJavaScriptEnabled(false);
        WebRequest browser = new WebRequest(wc.createCrumbedUrl(x.getUrl() + "move/move"), HttpMethod.POST);
        browser.setAdditionalHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        browser.setRequestParameters(List.of(new NameValuePair("destination", "/")));
        Page page = wc.getPage(browser);
        String body = page.getWebResponse().getContentAsString();
        assertEquals(403, page.getWebResponse().getStatusCode(), "a move into the root without Create there must answer 403: " + excerpt(body));
        assertTrue(page instanceof HtmlPage, "a browser gets an HTML page: " + excerpt(body));
        HtmlPage html = (HtmlPage) page;
        org.htmlunit.html.DomNode main = html.querySelector("#main-panel");
        String message = main != null ? main.asNormalizedText() : html.asNormalizedText();
        assertRootRefusalMessage("browser", message);
        List<String> links = new java.util.ArrayList<>();
        for (org.htmlunit.html.DomNode n : html.querySelectorAll("a[href], form[action]")) {
            DomElement e = (DomElement) n;
            String target = e.hasAttribute("href") ? e.getAttribute("href") : e.getAttribute("action");
            links.add(target);
            assertFalse(target.contains("grants/new"), "the refusal page must not link the grant request form, found " + target);
        }
        assertFalse(html.asNormalizedText().contains("grants/new"), "the refusal page must not name the grant request form: "
                + excerpt(html.asNormalizedText()) + " links: " + links);
        assertNotMoved(x);
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(violationsBefore + 1, violations.size(), "the refused move is recorded once as GRANT_VIOLATION, got " + violations);
        ChangeRecord rec = violations.get(violations.size() - 1);
        assertEquals("u1", rec.getUser(), "the GRANT_VIOLATION names u1");
        assertTrue((rec.getTarget() + " " + rec.getDetail()).contains("prod/x"), "the GRANT_VIOLATION names prod/x: "
                + rec.getTarget() + " / " + rec.getDetail());

        // script (the same attempt repeated at once; how many records a repeat adds is not pinned, note 261)
        JenkinsRule.WebClient script = ApproverFormFixtures.client(j, "u1");
        WebRequest plain = new WebRequest(script.createCrumbedUrl(x.getUrl() + "move/move"), HttpMethod.POST);
        plain.setAdditionalHeader("Accept", "*/*");
        plain.setRequestParameters(List.of(new NameValuePair("destination", "/")));
        org.htmlunit.WebResponse scripted = script.getPage(plain).getWebResponse();
        String scriptedText = scripted.getContentAsString();
        assertEquals(403, scripted.getStatusCode(), "a scripted move into the root must answer 403: " + excerpt(scriptedText));
        String scriptedMessage = scriptedText.replaceAll("<[^>]+>", " ");
        assertRootRefusalMessage("script", scriptedMessage);
        assertFalse(scriptedText.contains("grants/new"), "the scripted refusal must not name the grant request form: " + excerpt(scriptedText));
        assertNotMoved(x);
        int violationsAfterRefusals = records(ChangeType.GRANT_VIOLATION).size();

        // guard: into a regular folder under the CREATE window the same user's move goes through
        assertSuccess(ApproverFormFixtures.post(j, "u1", x.getUrl() + "move/move", List.of(new NameValuePair("destination", "/team"))),
                "guard: u1 moves prod/x into team (Create from the window, Delete from the window)");
        assertNull(prod.getItem("x"), "guard: x left prod");
        assertNotNull(team.getItem("x"), "guard: x is in team");
        assertEquals(violationsAfterRefusals, records(ChangeType.GRANT_VIOLATION).size(), "guard: the permitted move adds no violation");

        // guard: "/" is the root destination (an administrator's move into it goes through)
        assertSuccess(ApproverFormFixtures.post(j, "admin", team.getItem("x").getUrl() + "move/move",
                List.of(new NameValuePair("destination", "/"))), "guard: the administrator moves team/x into the root");
        assertNotNull(j.jenkins.getItem("x"), "guard: x is in the root");
        assertNull(team.getItem("x"), "guard: x left team");
    }

    /** Plain words for the root refusal: an administrator, the missing Create, no window to request. */
    /**
     * T-08-286 (e2e-06 DEF-03, verified in e2e-07 "no leading {@code <p>}": the Move refusal's text
     * starts in the alert, note 366): on the browser refusal page of T-SEC-63 every alert of the main
     * panel ({@code .jenkins-alert} or {@code role="alert"}) starts with its text, not with a
     * {@code <p>} element (the defect's {@code <div class="jenkins-alert ..."><p ...>...</p></div>}
     * left a blank band above the text); its visible text holds no literal tag text ({@code <p},
     * {@code </p>}, {@code &lt;}) and is the refusal itself ("... was refused; nothing was moved.").
     */
    @Test
    public void t_08_286_moveRefusalAlertStartsWithItsText() throws Exception {
        Item item = prod.getItem(HOSTILE);
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");
        wc.getOptions().setJavaScriptEnabled(false);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(item.getUrl() + "move/move"), HttpMethod.POST);
        request.setAdditionalHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        request.setRequestParameters(List.of(new NameValuePair("destination", "/team")));
        Page page = wc.getPage(request);
        assertEquals(403, page.getWebResponse().getStatusCode(), "premise: the move is refused");
        assertTrue(page instanceof HtmlPage, "premise: a browser gets an HTML page");
        org.htmlunit.html.DomNode main = ((HtmlPage) page).querySelector("#main-panel");
        assertNotNull(main, "premise: the standard layout has a main panel");
        List<DomElement> alerts = new java.util.ArrayList<>();
        for (org.htmlunit.html.DomNode n : main.querySelectorAll(".jenkins-alert, [role='alert']")) {
            alerts.add((DomElement) n);
        }
        assertFalse(alerts.isEmpty(), "premise: the refusal is shown in an alert: " + excerpt(page.getWebResponse().getContentAsString()));
        boolean refusal = false;
        for (DomElement alert : alerts) {
            org.htmlunit.html.DomNode first = alert.getFirstChild();
            while (first != null && first.getNodeType() == org.htmlunit.html.DomNode.TEXT_NODE && first.getTextContent().isBlank()) {
                first = first.getNextSibling();
            }
            assertFalse(first instanceof DomElement e && "p".equals(e.getTagName()),
                    "the alert must start with its text, not with a <p> element: " + alert.asXml());
            String text = alert.asNormalizedText();
            for (String literal : new String[] {"<p", "</p>", "&lt;", "&gt;"}) {
                assertFalse(text.contains(literal), "the alert's text must not show the literal " + literal + ": " + text);
            }
            if (text.contains("was refused") && text.contains("nothing was moved")) {
                refusal = true;
            }
        }
        assertTrue(refusal, "an alert must carry the refusal (\"... was refused; nothing was moved.\"): "
                + excerpt(main.asNormalizedText()));
    }

    private static void assertRootRefusalMessage(String what, String message) {
        assertTrue(ADMINISTRATOR.matcher(message).find(), what + ": the refusal must say that an administrator must make the move: "
                + excerpt(message));
        assertTrue(CREATE.matcher(message).find(), what + ": the refusal must name what is missing (Create): " + excerpt(message));
        assertFalse(SUGGESTS_CREATE_WINDOW.matcher(message).find(), what + ": the refusal must not suggest requesting a Create"
                + " window for the Jenkins root: " + excerpt(message));
    }

    private static final java.util.regex.Pattern ADMINISTRATOR = java.util.regex.Pattern.compile("(?i)\\badministrator");
    private static final java.util.regex.Pattern CREATE = java.util.regex.Pattern.compile("(?i)\\bcreate\\b");
    /**
     * "request a Create window", "ask for Item/Create", "request a permission window for Create"
     * and the like, unless negated ("cannot request ..."): a suggestion to apply for what no
     * window can give in the Jenkins root.
     */
    private static final java.util.regex.Pattern SUGGESTS_CREATE_WINDOW = java.util.regex.Pattern.compile(
            "(?i)(?<!\\b(?:not|cannot|can't|never)\\s{1,3})\\b(?:request|ask\\s+for|apply\\s+for)\\s+(?:a|an|the)?\\s*"
            + "(?:new\\s+|temporary\\s+)?(?:(?:item/)?create\\b|(?:permission\\s+)?window\\b[^.]{0,40}\\bcreate\\b)");

    private void assertNotMoved(Item x) {
        assertNotNull(prod.getItem("x"), "the refused move must leave x in prod");
        assertNull(j.jenkins.getItem("x"), "nothing may arrive in the root");
        assertEquals("prod/x", x.getFullName(), "the item keeps its full name");
    }

    private static String queryValue(java.net.URL url, String name) {
        for (String pair : url.getQuery().split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                return java.net.URLDecoder.decode(eq < 0 ? "" : pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String decode(String path) {
        return java.net.URLDecoder.decode(path.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
    }
}
