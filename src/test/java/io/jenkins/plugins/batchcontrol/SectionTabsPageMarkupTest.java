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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The markup of the eight Batch Control section pages that only the retired legacy e2e drivers looked
 * at (issue #31, note 369; {@code e2e/r6/s5b.py}, {@code e2e/r7/a_ui.py}, {@code e2e/r7/c_hist.py},
 * {@code e2e/r9/checklist.py}; verified in e2e-06 and e2e-09): each page marks its own tab current
 * (T-UI-299), its tables are core's compact tables (T-UI-300) and it carries no "Back to ..." link
 * (T-UI-301). The tab bar's markup is the frozen one of T-UI-40 ({@code nav[data-batch-control-tabs]}
 * holding {@code a[data-batch-control-tab=<section>]}; the root page is the {@code overview} tab).
 *
 * <p>Every section holds data, so that each list renders a table: u1's pending run request on
 * {@code batch-x}, u1's pending ACTIVATE request on {@code act-x}, g1's approved CONFIGURE window and a
 * pending grant request, and the failed run {@code fail-x#1} (a run record, an incident and change
 * records). Viewer: the administrator, who may open every section.
 *
 * <p>Written from docs/SPEC.md item 2 (D-61: the sections are a tab bar; "the breadcrumb already leads
 * back", note 140 of T-UI-23), docs/TEST-MATRIX.md (T-UI-23, T-UI-40) and the e2e-06 and e2e-09 reports;
 * no src/main knowledge.
 */
@WithJenkins
public class SectionTabsPageMarkupTest {

    /** Section tab id to its path below {@code batch-control/}. */
    private static final String[][] SECTIONS = {
        {"overview", ""}, {"requests", "requests/"}, {"activations", "activations/"}, {"grants", "grants/"},
        {"history", "history/"}, {"changes", "changes/"}, {"dashboard", "dashboard/"}, {"incidents", "incidents/"},
    };
    /** The sections whose fixture data must be listed in a table. */
    private static final List<String> LISTS = List.of("requests", "activations", "grants", "history", "changes",
            "dashboard", "incidents");
    private static final Pattern BACK_TEXT = Pattern.compile("(?i)\\bback\\s+to\\b");
    /**
     * T-UI-23's caption rule: a caption that starts with "Back" after any arrow or punctuation ("Back",
     * "← Back", "« Back to ..."). A bare arrow is not matched: the Changes page's month navigation
     * ("« 2026-10") is paging, not a way back.
     */
    private static final Pattern BACK_CAPTION = Pattern.compile("(?is)^\\W*back\\b.*");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST_GRANT).everywhere().to("g1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        populate();
    }

    /**
     * T-UI-299 (SPEC 2 D-61, screen contract; e2e-06: "the right tab marked aria-current=page"; round3 C
     * checks History only): on each of the eight section pages exactly one tab carries
     * {@code aria-current="page"}, the tab of that section, and no other tab carries
     * {@code aria-current} at all. Because each page names a different tab, a constant marking fails on
     * seven of them.
     */
    @Test
    public void t_ui_299_eachSectionPageMarksItsOwnTab() throws Exception {
        for (String[] section : SECTIONS) {
            HtmlPage page = sectionPage(section);
            List<DomElement> tabs = tabs(page, section[0]);
            List<String> current = new ArrayList<>();
            for (DomElement tab : tabs) {
                if ("page".equals(tab.getAttribute("aria-current"))) {
                    current.add(tab.getAttribute("data-batch-control-tab"));
                } else {
                    assertFalse(tab.hasAttribute("aria-current"), where(section) + ": only the section's own tab may carry"
                            + " aria-current: " + tab.asXml());
                }
            }
            assertEquals(List.of(section[0]), current, where(section) + ": exactly the section's own tab must carry"
                    + " aria-current=\"page\"");
        }
    }

    /**
     * T-UI-300 (screen contract; e2e-06: "Tables are jenkins-table jenkins-table--small"; e2e-09: "every
     * table jenkins-table--small"): every table in the main panel of each section page carries core's
     * {@code jenkins-table--small} class. Premise: each list section ({@link #LISTS}) renders at least
     * one table with the fixture data, so the check cannot pass on empty pages.
     */
    @Test
    public void t_ui_300_sectionTablesAreCompact() throws Exception {
        for (String[] section : SECTIONS) {
            HtmlPage page = sectionPage(section);
            List<DomNode> tables = main(page).querySelectorAll("table");
            if (LISTS.contains(section[0])) {
                assertFalse(tables.isEmpty(), where(section) + ": premise: the fixture data is listed in a table: "
                        + excerpt(main(page).asNormalizedText()));
            }
            for (DomNode t : tables) {
                String classes = " " + ((DomElement) t).getAttribute("class") + " ";
                assertTrue(classes.contains(" jenkins-table--small "), where(section) + ": every table must be core's compact"
                        + " table (jenkins-table--small), was class=\"" + classes.trim() + "\"");
            }
        }
    }

    /**
     * T-UI-301 (SPEC 6 usability, T-UI-23's rule on the section pages: the breadcrumb and the tab bar
     * already lead back; e2e-06: "there are no Back links"): no section page carries a "Back to ..."
     * text in its main panel nor an anchor whose caption starts with "Back" (after any arrow, as T-UI-23
     * reads it; the Changes page's "« 2026-10" month link is paging and allowed). Premise: each page renders
     * its tab bar and the list sections render a table, so an empty page cannot pass.
     */
    @Test
    public void t_ui_301_sectionPagesHaveNoBackLink() throws Exception {
        for (String[] section : SECTIONS) {
            HtmlPage page = sectionPage(section);
            DomElement main = main(page);
            assertFalse(tabs(page, section[0]).isEmpty(), where(section) + ": premise: the page renders its tab bar");
            if (LISTS.contains(section[0])) {
                assertFalse(main.querySelectorAll("table").isEmpty(), where(section) + ": premise: the page lists its data");
            }
            String text = main.asNormalizedText();
            assertFalse(BACK_TEXT.matcher(text).find(), where(section) + ": no 'Back to ...' text: " + excerpt(text));
            for (DomNode n : main.querySelectorAll("a")) {
                String caption = n.asNormalizedText().trim();
                assertFalse(BACK_CAPTION.matcher(caption).matches(), where(section) + ": no back link may be offered: '"
                        + caption + "' -> " + ((DomElement) n).getAttribute("href"));
            }
        }
    }

    // ---------------------------------------------------------------- fixture and helpers

    private void populate() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("batch-x");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
        FreeStyleProject act = j.createFreeStyleProject("act-x");
        RerunFallbackFixtures.failedFreestyle(j, "fail-x");
        try (ACLContext ignored = as("u1")) {
            RunRequestService.get().create(job, new LinkedHashMap<>(), "month-end batch", "a1");
            ActivationService.get().create(act, ActivationRequest.Action.ACTIVATE, "go live", List.of("a1"));
        }
        GrantRequest open;
        try (ACLContext ignored = as("g1")) {
            open = GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "batch-x"),
                    List.of(GrantAction.CONFIGURE), 30, "fix the schedule", "a1");
            GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "act-x"),
                    List.of(GrantAction.CONFIGURE), 30, "fix the trigger", "a1");
        }
        try (ACLContext ignored = as("a1")) {
            assertNotNull(GrantRequestService.get().approve(open.getId(), "ok"), "fixture: the window opens");
        }
    }

    private HtmlPage sectionPage(String[] section) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(j, "admin", "batch-control/" + section[1]);
        assertEquals(200, page.getWebResponse().getStatusCode(), where(section) + ": the administrator opens the section");
        return page;
    }

    /** The tabs of the page's Batch Control tab bar; the section's own tab must be among them (premise). */
    private static List<DomElement> tabs(HtmlPage page, String own) {
        DomNode nav = page.querySelector("nav[data-batch-control-tabs]");
        assertNotNull(nav, own + ": premise: the page renders the Batch Control tab bar");
        List<DomElement> out = new ArrayList<>();
        for (DomNode n : nav.querySelectorAll("a[data-batch-control-tab]")) {
            out.add((DomElement) n);
        }
        assertTrue(out.stream().anyMatch(t -> own.equals(t.getAttribute("data-batch-control-tab"))),
                own + ": premise: the tab bar holds the section's own tab");
        return out;
    }

    private static DomElement main(HtmlPage page) {
        DomElement main = page.getElementById("main-panel");
        assertNotNull(main, "the page must have a main panel");
        return main;
    }

    private static String where(String[] section) {
        return "/batch-control/" + section[1] + " (" + section[0] + ")";
    }

    private static ACLContext as(String user) {
        return ACL.as2(User.getById(user, true).impersonate2());
    }
}
