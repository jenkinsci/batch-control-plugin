package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Incidents list pages a month of more than 50 incidents (issue #31, gap X-23, its Incidents paging half,
 * retired legacy drivers browser/b11.mjs #9, browser/b14.mjs #16, browser/audit/b11.mjs #13 and
 * browser/audit/b14.mjs #17). Matrix row T-UI-316 (note 371).
 *
 * <p>Basis: SPEC 11 (FAILURE results are registered as incidents automatically) and SPEC 12 (a period filter;
 * the Incidents list shows one month, {@code ?month=YYYY-MM}), with the page size of SPEC 10 ("페이지당 50건")
 * that the run lists use. No SPEC sentence or matrix row fixes the incidents footer's wording; the request
 * list's footer (T-UI-47, note 189) reads "Page N (K requests)", so the footer is read only as far as "51
 * incidents" (a count followed by the noun) and the month on the page. The pager links are found by their
 * target ({@code month=M&page=2}), not by their caption. The Changes paging is judged in the CI e2e.
 *
 * <p>Users: {@code a1} (Item/Read, BatchControl/Approve and ViewHistory). Change control on (records active,
 * D-13), run control off; the job {@code fail-x} fails every build, each failure an incident (SPEC 11).
 *
 * <p>Written from docs/SPEC.md items 10, 11 and 12 and docs/TEST-MATRIX.md (T-UI-47, T-UI-146, T-GAP-368);
 * the retired drivers were read only to learn what they looked at. No src/main knowledge.
 */
@WithJenkins
public class IncidentPagingTest {

    private static final Pattern COUNT = Pattern.compile("\\b(\\d+)\\s+incidents?\\b");

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        job = j.createFreeStyleProject("fail-x");
        job.getBuildersList().add(new FailureBuilder());
    }

    /**
     * T-UI-316 (SPEC 11, 12, 10 page size; #31 gap X-23, Incidents paging): guard first, with exactly 50
     * incidents in month M ({@code fail-x} #1..#50), a1's {@code batch-control/incidents/?month=M} lists all 50,
     * its footer counts 50 incidents and no link leads to {@code page=2}. After #51 fails, page 1 lists 50 rows,
     * names M and counts 51 incidents, and offers a link to {@code ?month=M&page=2}. Following it, page 2 lists
     * exactly the one remaining incident (pages 1 and 2 together list #1..#51, each once), counts 51
     * incidents, and offers a link back to page 1 of M ({@code month=M} with no page or {@code page=1}).
     */
    @Test
    public void t_ui_316_incidentsListPagesAMonthOfFiftyOneIncidents() throws Exception {
        YearMonth month = YearMonth.now(BatchClock.clock());
        String m = StoreDataFixtures.monthName(month);
        fail(50);
        assertEquals(50, IncidentService.get().list(month).size(), "premise: 50 incidents in " + m);
        HtmlPage fifty = page("batch-control/incidents/?month=" + m);
        assertEquals(50, runs(fifty).size(), "guard: the month's 50 incidents fit on one page: " + runs(fifty));
        assertEquals(50, count(fifty), "guard: the footer counts 50 incidents");
        assertTrue(links(fifty, m, "2").isEmpty(), "guard: with 50 incidents no link leads to page 2: " + links(fifty, m, "2"));

        fail(1);
        assertEquals(month, YearMonth.now(BatchClock.clock()), "premise: the builds ran within one month");
        assertEquals(51, IncidentService.get().list(month).size(), "premise: 51 incidents in " + m);
        HtmlPage first = page("batch-control/incidents/?month=" + m);
        Set<Integer> onFirst = runs(first);
        assertEquals(50, onFirst.size(), "page 1 lists 50 incidents: " + onFirst);
        assertTrue(first.getElementById("main-panel").asNormalizedText().contains(m), "page 1 names the month " + m);
        assertEquals(51, count(first), "page 1's footer counts the month's 51 incidents");
        List<DomElement> older = links(first, m, "2");
        assertEquals(1, older.size(), "page 1 offers one link to ?month=" + m + "&page=2: " + older);

        Page answer = ((HtmlElement) older.get(0)).click();
        assertEquals(200, answer.getWebResponse().getStatusCode(), "the link to page 2 opens");
        HtmlPage second = (HtmlPage) answer;
        Set<Integer> onSecond = runs(second);
        assertEquals(1, onSecond.size(), "page 2 lists the one remaining incident: " + onSecond);
        Set<Integer> all = new TreeSet<>(onFirst);
        all.addAll(onSecond);
        Set<Integer> expected = new TreeSet<>();
        for (int n = 1; n <= 51; n++) {
            expected.add(n);
        }
        assertEquals(expected, all, "pages 1 and 2 together list #1..#51, each once");
        assertEquals(51, count(second), "page 2's footer counts the month's 51 incidents");
        List<DomElement> newer = new ArrayList<>(links(second, m, null));
        newer.addAll(links(second, m, "1"));
        assertTrue(!newer.isEmpty(), "page 2 offers a link back to page 1 of " + m + ": "
                + excerpt(second.getElementById("main-panel").asNormalizedText()));
    }

    // ---------------------------------------------------------------- helpers

    private void fail(int builds) throws Exception {
        for (int i = 0; i < builds; i++) {
            j.buildAndAssertStatus(Result.FAILURE, job);
        }
        j.waitUntilNoActivity();
    }

    private HtmlPage page(String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("a1");
        wc.getOptions().setJavaScriptEnabled(false);
        Page answer = wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET));
        assertEquals(200, answer.getWebResponse().getStatusCode(), "fixture: a1 opens " + path);
        return (HtmlPage) answer;
    }

    /** The build numbers of {@code fail-x} linked from the rows of the page's tables. */
    private Set<Integer> runs(HtmlPage page) throws Exception {
        String jobPath = new URL(j.getURL(), job.getUrl()).getPath();
        Pattern run = Pattern.compile(Pattern.quote(jobPath) + "(\\d+)/");
        Set<Integer> out = new LinkedHashSet<>();
        int rows = 0;
        for (DomNode tr : page.querySelectorAll("#main-panel table tbody tr")) {
            Integer number = null;
            for (DomNode a : ((DomElement) tr).querySelectorAll("a[href]")) {
                Matcher matcher = run.matcher(page.getFullyQualifiedUrl(((DomElement) a).getAttribute("href")).getPath());
                if (matcher.matches()) {
                    number = Integer.valueOf(matcher.group(1));
                }
            }
            if (number != null) {
                rows++;
                out.add(number);
            }
        }
        assertEquals(rows, out.size(), "each incident is listed once on the page: " + out);
        return out;
    }

    /** The incidents count in the main panel ("51 incidents"); exactly one such count. */
    private static int count(HtmlPage page) {
        String text = page.getElementById("main-panel").asNormalizedText();
        Matcher matcher = COUNT.matcher(text);
        List<Integer> counts = new ArrayList<>();
        while (matcher.find()) {
            counts.add(Integer.valueOf(matcher.group(1)));
        }
        assertEquals(1, counts.size(), "the page counts its incidents once (\"N incidents\"): " + counts + ": " + excerpt(text));
        return counts.get(0);
    }

    /**
     * Links in the main panel to the Incidents list of {@code month} at {@code pageNumber} (null: no page
     * parameter).
     */
    private List<DomElement> links(HtmlPage page, String month, String pageNumber) throws Exception {
        String listPath = new URL(j.getURL(), "batch-control/incidents/").getPath();
        DomElement main = page.getElementById("main-panel");
        assertNotNull(main, "fixture: the page has a main panel");
        List<DomElement> out = new ArrayList<>();
        for (DomNode n : main.querySelectorAll("a[href]")) {
            URL url = page.getFullyQualifiedUrl(((DomElement) n).getAttribute("href"));
            if (!url.getPath().equals(listPath)) {
                continue;
            }
            Map<String, String> query = query(url);
            if (month.equals(query.get("month")) && Objects.equals(pageNumber, query.get("page"))) {
                out.add((DomElement) n);
            }
        }
        return out;
    }

    private static Map<String, String> query(URL url) {
        Map<String, String> out = new HashMap<>();
        if (url.getQuery() == null) {
            return out;
        }
        for (String pair : url.getQuery().split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(key, value);
        }
        return out;
    }
}
