package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Issue #45 (Wave C-UI contract, matrix rows T-UI-146..148, note 327): on the run requests, grants
 * and activations pages a pager link keeps every other page parameter of the current URL and
 * changes only its own list's parameter, and still escapes values. An approver on page 2 of the
 * pending list who pages the ended list stays on page 2 of the pending list.
 *
 * <p>Basis: SPEC 5/6a/8 (D-66: the pages list pending requests first, then active and ended items;
 * the lists page on {@code pendingPage}, {@code activePage}, {@code endedPage}, note 249), SPEC 6
 * usability, issue #45 and the Wave C-UI contract. The page size is not specified, so each list is
 * filled until its pager offers page 2 (measured, not assumed).
 *
 * <p>Each row runs its guards first: a list's own pager parameter moves that list (page 2 shows
 * other rows than page 1), and a hostile value in another page parameter is never rendered as
 * markup. Then the defect: the ended list's link to its page 2, on {@code ?pendingPage=2}, must keep
 * {@code pendingPage=2}, and following it must leave the pending list on its page 2; the same in the
 * other direction.
 *
 * <p>Written from docs/SPEC.md, docs/TEST-MATRIX.md, issue #45 and the Wave C-UI contract only (no
 * src/main knowledge).
 *
 * <p>T-UI-318 (issue #150, note 375): the pager's links say Newer and Older, as History and Change
 * Records already do, because every list is sorted newest first. The pager is found by the
 * accessible name of its navigation landmark ({@code Pages of pending requests}), read from the page
 * rendered on origin/main 06cfe2f1; its links are judged by the page they lead to, then by caption.
 */
@WithJenkins
public class PagerParameterTest {

    private static final String PENDING = "pending";
    private static final String ENDED = "ended";
    private static final String PENDING_PAGE = "pendingPage";
    private static final String ENDED_PAGE = "endedPage";
    /** The accessible name of the pending list's pager on the run requests page (T-UI-318). */
    private static final String PENDING_PAGER = "pending requests";
    private static final String NEWER = "Newer";
    private static final String OLDER = "Older";
    /** Upper bound of the adaptive fill per list; a pager must appear well before. */
    private static final int MAX_FILL = 300;
    private static final int CHUNK = 10;

    private JenkinsRule j;
    private FreeStyleProject job;
    private int jobCounter;

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
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-UI-146 (#45): {@code batch-control/requests/} with pending run requests and cancelled ones,
     * each list longer than one page.
     */
    @Test
    public void t_ui_146_requestsPagerKeepsTheOtherListsPage() throws Exception {
        String path = "batch-control/requests/";
        // amended for D-88 (note 359): each pending request has its own reason, so none is identical to another
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        Set<String> pending = fill(path, PENDING_PAGE, () -> as("u1",
                () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "pending run " + n.incrementAndGet(), "a1").getId()));
        Set<String> ended = fill(path, ENDED_PAGE, () -> as("u1", () -> {
            String id = RunRequestService.get().create(job, new LinkedHashMap<>(), "cancelled run", "a1").getId();
            RunRequestService.get().cancel(id);
            return id;
        }));
        assertPagersKeepEachOther(path, pending, ended);
    }

    /**
     * T-UI-147 (#45): {@code batch-control/grants/} with pending grant requests and rejected ones,
     * each list longer than one page.
     */
    @Test
    public void t_ui_147_grantsPagerKeepsTheOtherListsPage() throws Exception {
        String path = "batch-control/grants/";
        Set<String> pending = fill(path, PENDING_PAGE, () -> grantRequest("pending window").getId());
        Set<String> ended = fill(path, ENDED_PAGE, () -> {
            GrantRequest r = grantRequest("rejected window");
            as("a1", () -> GrantRequestService.get().reject(r.getId(), "no"));
            return r.getId();
        });
        assertPagersKeepEachOther(path, pending, ended);
    }

    /**
     * T-UI-148 (#45): {@code batch-control/activations/} with pending ACTIVATE requests and
     * cancelled ones (one job each), each list longer than one page.
     */
    @Test
    public void t_ui_148_activationsPagerKeepsTheOtherListsPage() throws Exception {
        String path = "batch-control/activations/";
        Set<String> pending = fill(path, PENDING_PAGE, () -> activation(false));
        Set<String> ended = fill(path, ENDED_PAGE, () -> activation(true));
        assertPagersKeepEachOther(path, pending, ended);
    }

    /**
     * T-UI-318 (#150): every list is sorted newest first, so the pager names its links by where
     * they lead. On {@code batch-control/requests/} with more than one page of pending requests,
     * page 1 of the pending list links to page 2 as "Older", page 2 links back to page 1 as
     * "Newer", and neither reads "Previous" or "Next". Guard: the links still lead to the same
     * pages (page 1 to 2, page 2 to 1), so a change that renamed them by swapping them would fail.
     */
    @Test
    public void t_ui_318_pagerLinksSayNewerAndOlder() throws Exception {
        String path = "batch-control/requests/";
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        fill(path, PENDING_PAGE, () -> as("u1",
                () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "pending run " + n.incrementAndGet(), "a1").getId()));
        Map<String, String> first = pagerLinks(page(path), PENDING_PAGER);
        Map<String, String> second = pagerLinks(page(path + "?" + PENDING_PAGE + "=2"), PENDING_PAGER);

        // Guard: the links lead where they did before (labels aside).
        assertTrue(first.containsValue("2") && !first.containsValue("1"),
                "guard: page 1 of the pending list links to page 2 and not to itself: " + first);
        assertTrue(second.containsValue("1"), "guard: page 2 of the pending list links back to page 1: " + second);

        List<String> failures = new ArrayList<>();
        if (!"2".equals(first.get(OLDER))) {
            failures.add("page 1: the link to page 2 must read '" + OLDER + "', links were " + first);
        }
        if (!"1".equals(second.get(NEWER))) {
            failures.add("page 2: the link to page 1 must read '" + NEWER + "', links were " + second);
        }
        for (String old : Arrays.asList("Previous", "Next")) {
            if (first.containsKey(old) || second.containsKey(old)) {
                failures.add("no pager link may read '" + old + "': page 1 " + first + ", page 2 " + second);
            }
        }
        assertTrue(failures.isEmpty(), "#150: " + String.join("; ", failures));
    }

    // ---------------------------------------------------------------- the check

    private void assertPagersKeepEachOther(String path, Set<String> pending, Set<String> ended) throws Exception {
        Set<String> all = new LinkedHashSet<>(pending);
        all.addAll(ended);
        HtmlPage first = page(path);
        List<String> pending1 = idsInList(first, PENDING, all);
        List<String> ended1 = idsInList(first, ENDED, all);
        List<String> pending2 = idsInList(page(path + "?" + PENDING_PAGE + "=2"), PENDING, all);
        List<String> ended2 = idsInList(page(path + "?" + ENDED_PAGE + "=2"), ENDED, all);
        assertFalse(pending1.isEmpty() || ended1.isEmpty(), "premise: both lists have rows on page 1: pending " + pending1 + ", ended " + ended1);

        // Guard 1: a list's own parameter moves that list.
        assertFalse(pending2.isEmpty(), "guard: pendingPage=2 shows pending rows");
        assertTrue(Collections.disjoint(pending1, pending2), "guard: pendingPage=2 shows other pending rows than page 1: " + pending1 + " / " + pending2);
        assertFalse(ended2.isEmpty(), "guard: endedPage=2 shows ended rows");
        assertTrue(Collections.disjoint(ended1, ended2), "guard: endedPage=2 shows other ended rows than page 1: " + ended1 + " / " + ended2);
        assertTrue(pending.containsAll(pending2) && ended.containsAll(ended2), "guard: each list shows only its own rows");

        // Guard 2: a hostile value in another page parameter is never rendered as markup.
        String hostile = "2%22%3E%3Cb%20id%3D%22bc45inj%22%3Ex%3C%2Fb%3E";
        Page p = client().getPage(new URL(j.getURL(), path + "?" + PENDING_PAGE + "=" + hostile));
        int status = p.getWebResponse().getStatusCode();
        assertTrue(status < 500, "guard: a malformed pendingPage must not cause a server error, got " + status);
        if (p instanceof HtmlPage) {
            assertNull(((HtmlPage) p).getElementById("bc45inj"), "guard: the pendingPage value must be escaped wherever it is echoed");
        }
        assertFalse(p.getWebResponse().getContentAsString().contains("<b id=\"bc45inj\">"),
                "guard: the pendingPage value must be escaped wherever it is echoed");

        // #45: paging the ended list keeps the pending list's page, and the other way round.
        List<String> problems = new ArrayList<>();
        problems.addAll(followKeeps(path, PENDING_PAGE, PENDING, pending2, ENDED_PAGE, ENDED, ended2, all));
        problems.addAll(followKeeps(path, ENDED_PAGE, ENDED, ended2, PENDING_PAGE, PENDING, pending2, all));
        assertTrue(problems.isEmpty(), "#45: a pager link must keep every other page parameter of the current URL: " + problems);
    }

    /**
     * On {@code path?<keptParam>=2}, the links of {@code movedParam} to its page 2 must carry
     * {@code keptParam=2}, and following one must show page 2 of both lists.
     */
    private List<String> followKeeps(String path, String keptParam, String keptList, List<String> keptRows,
                                     String movedParam, String movedList, List<String> movedRows, Set<String> all) throws Exception {
        List<String> out = new ArrayList<>();
        String current = path + "?" + keptParam + "=2";
        HtmlPage page = page(current);
        assertEquals(keptRows, idsInList(page, keptList, all), "premise: " + current + " shows page 2 of the " + keptList + " list");
        List<URL> links = new ArrayList<>();
        for (HtmlAnchor a : page.getAnchors()) {
            String href = a.getHrefAttribute();
            if (href == null || href.isEmpty() || href.startsWith("#")) {
                continue;
            }
            URL url = page.getFullyQualifiedUrl(href);
            if ("2".equals(query(url).get(movedParam))) {
                links.add(url);
            }
        }
        if (links.isEmpty()) {
            fail("premise: " + current + " offers a link to page 2 of the " + movedList + " list (" + movedParam + "=2)");
        }
        for (URL link : links) {
            Map<String, String> q = query(link);
            if (!"2".equals(q.get(keptParam))) {
                out.add("on " + current + " the " + movedList + " pager link " + link.getFile() + " drops " + keptParam + "=2");
            }
        }
        HtmlPage followed = (HtmlPage) client().getPage(links.get(0));
        List<String> keptAfter = idsInList(followed, keptList, all);
        List<String> movedAfter = idsInList(followed, movedList, all);
        if (!keptRows.equals(keptAfter)) {
            out.add("following " + links.get(0).getFile() + " from " + current + " moved the " + keptList
                    + " list off its page 2 (shows " + keptAfter.size() + " rows other than page 2's)");
        }
        if (!movedRows.equals(movedAfter)) {
            out.add("following " + links.get(0).getFile() + " did not show page 2 of the " + movedList + " list");
        }
        return out;
    }

    // ---------------------------------------------------------------- fixture

    /** Creates items in chunks until the page offers a link with {@code param=2}; returns their ids. */
    private Set<String> fill(String path, String param, Callable<String> create) throws Exception {
        Set<String> ids = new LinkedHashSet<>();
        while (ids.size() < MAX_FILL) {
            for (int i = 0; i < CHUNK; i++) {
                ids.add(create.call());
            }
            if (offersPage2(page(path), param)) {
                return ids;
            }
        }
        fail("fixture: " + path + " shows no " + param + "=2 link after " + ids.size() + " items");
        return ids;
    }

    private static boolean offersPage2(HtmlPage page, String param) {
        for (HtmlAnchor a : page.getAnchors()) {
            String href = a.getHrefAttribute();
            if (href != null && !href.isEmpty() && "2".equals(query(page, href).get(param))) {
                return true;
            }
        }
        return false;
    }

    private GrantRequest grantRequest(String reason) throws Exception {
        return as("u1", () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "batch-x"),
                Arrays.asList(GrantAction.CONFIGURE), 30, reason, "a1"));
    }

    /**
     * One ACTIVATE request by u1, cancelled by u1 when {@code ended}. It is filed on {@code batch-x};
     * should the service refuse another pending request on that job, it is filed on a fresh
     * approval-required job instead (saving a job is slow, so the shared job keeps the fill short;
     * whether one job may hold several pending ACTIVATE requests is not what this row measures).
     */
    private String activation(boolean ended) throws Exception {
        String reason = ended ? "cancelled activation" : "pending activation";
        ActivationRequest r;
        try {
            r = as("u1", () -> ActivationService.get().create(job, ActivationRequest.Action.ACTIVATE, reason, List.of("a1")));
        } catch (RuntimeException refused) {
            FreeStyleProject fresh = j.createFreeStyleProject("act-" + (jobCounter++));
            BatchControlFixtures.setBatchControl(fresh, new BatchControlJobProperty(true));
            r = as("u1", () -> ActivationService.get().create(fresh, ActivationRequest.Action.ACTIVATE, reason, List.of("a1")));
        }
        if (ended) {
            String id = r.getId();
            as("u1", () -> {
                ActivationService.get().cancel(id);
                return null;
            });
        }
        return r.getId();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The links of the pager whose navigation is labelled {@code Pages of <label>}, as caption to
     * the page of this list they lead to ({@code 1} when the link carries no page parameter).
     */
    private static Map<String, String> pagerLinks(HtmlPage page, String label) {
        List<DomElement> navs = new ArrayList<>();
        for (DomNode node : page.querySelectorAll("[role=navigation]")) {
            DomElement nav = (DomElement) node;
            if (("Pages of " + label).equals(nav.getAttribute("aria-label"))) {
                navs.add(nav);
            }
        }
        assertEquals(1, navs.size(), "premise: " + page.getUrl() + " has one pager labelled 'Pages of " + label + "'");
        Map<String, String> out = new LinkedHashMap<>();
        for (DomNode a : navs.get(0).querySelectorAll("a[href]")) {
            String caption = a.getTextContent().trim();
            out.put(caption, query(page, ((DomElement) a).getAttribute("href")).getOrDefault(PENDING_PAGE, "1"));
        }
        return out;
    }

    /** Ids of {@code candidates} linked from rows of {@code table[data-batch-control-list=<list>]}, in order. */
    private static List<String> idsInList(HtmlPage page, String list, Set<String> candidates) {
        List<String> out = new ArrayList<>();
        for (DomNode t : page.querySelectorAll("table[data-batch-control-list=" + list + "]")) {
            for (DomNode a : t.querySelectorAll("a[href]")) {
                String linkPath;
                try {
                    linkPath = page.getFullyQualifiedUrl(((DomElement) a).getAttribute("href")).getPath();
                } catch (java.net.MalformedURLException e) {
                    continue;
                }
                for (String id : candidates) {
                    if (linkPath.contains(id) && !out.contains(id)) {
                        out.add(id);
                    }
                }
            }
        }
        return out;
    }

    private static Map<String, String> query(HtmlPage page, String href) {
        try {
            return query(page.getFullyQualifiedUrl(href));
        } catch (java.net.MalformedURLException e) {
            return Map.of();
        }
    }

    /** The decoded query parameters of a URL (last value wins). */
    private static Map<String, String> query(URL url) {
        Map<String, String> out = new LinkedHashMap<>();
        String q = url.getQuery();
        if (q == null || q.isEmpty()) {
            return out;
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private HtmlPage page(String path) throws Exception {
        Page p = client().getPage(new URL(j.getURL(), path));
        assertEquals(200, p.getWebResponse().getStatusCode(), "admin GET " + path + " must answer 200");
        if (!(p instanceof HtmlPage)) {
            fail("admin GET " + path + " must render HTML");
        }
        return (HtmlPage) p;
    }

    private JenkinsRule.WebClient client() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getOptions().setCssEnabled(false);
        return wc.login("admin");
    }

    private static <T> T as(String id, Callable<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(id, true).impersonate2())) {
            return body.call();
        }
    }
}
