package io.jenkins.plugins.batchcontrol;

import hudson.model.Action;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every Batch Control entry renders an icon (issue #31, note 370, gap X-16, retired drivers
 * browser/section-a.mjs #6 and browser/audit/sa-read.mjs #9). Matrix row T-UI-309.
 *
 * <p>Basis: no SPEC sentence; ARCHITECTURE 4 and T-02-86 make ionicons-api a required dependency (the
 * entries use its symbols), and the retired drivers found every side-panel icon rendered (a non-empty
 * {@code svg}). The visibility of the entries is T-02-07 and T-UI-119; this row is their icons.
 *
 * <p>Batch Control's actions are recognised by their class's package ({@code io.jenkins.plugins.batchcontrol}),
 * read at run time; an action without an icon is not an entry and is not judged.
 *
 * <p>Written from docs/SPEC.md item 2, docs/ARCHITECTURE.md section 2 and docs/TEST-MATRIX.md (T-02-07,
 * T-02-86, T-UI-40, T-UI-119); the retired drivers were read only to learn what each looked at. No
 * src/main knowledge.
 */
@WithJenkins
public class EntryIconTest {

    private static final String PACKAGE = "io.jenkins.plugins.batchcontrol.";

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("nobc")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-UI-309 (ARCHITECTURE 4 / T-02-86 ionicons-api; screen contract of the retired drivers; #31 gap
     * X-16): as u1, every Batch Control action with an icon among the root actions, the actions of
     * {@code batch-x} and the actions of its approved build #1 names a {@code symbol-} icon (premise: at
     * least the root action and two job entries). Rendered: the header link to {@code batch-control},
     * each section tab ({@code a[data-batch-control-tab]}) of {@code batch-control/} and the job page's
     * side-panel entries for Request Run ({@code job/batch-x/batch-control}) and Request Change Permission
     * ({@code batch-control/grants/new}) each hold an {@code svg} that draws a path or shape (the header link is the one inside the page's
     * {@code header}). Guard: nobc (no Batch
     * Control permission) is offered none of these actions and no side-panel entry to those targets.
     */
    @Test
    public void t_ui_309_everyBatchControlEntryRendersASymbolIcon() throws Exception {
        String id = ApproverFormFixtures.submitRunOk(j, "u1", job, "month-end batch", "a1");
        ApproverFormFixtures.assertSuccess(ApproverFormFixtures.decideRun(j, "a1", id, "approve", "ok"), "fixture: approval");
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), "fixture: the approved build #1 ran");

        List<Action> root = iconActions("u1", j.jenkins.getActions());
        List<Action> onJob = iconActions("u1", job.getAllActions());
        List<Action> onBuild = iconActions("u1", job.getBuildByNumber(1).getAllActions());
        assertTrue(root.size() >= 1, "premise: u1 is offered the Batch Control root action");
        assertTrue(onJob.size() >= 2, "premise: u1 is offered the job's Request Run and Request Change Permission entries: " + onJob);
        List<Action> all = new ArrayList<>(root);
        all.addAll(onJob);
        all.addAll(onBuild);
        for (Action a : all) {
            String icon = iconOf("u1", a);
            assertTrue(icon.startsWith("symbol-"), a.getDisplayName() + " must name a symbol icon, got '" + icon + "'");
        }

        HtmlPage overview = UsabilityFixtures.htmlPage(j, "u1", "batch-control/");
        List<DomElement> header = new ArrayList<>();
        for (DomNode n : overview.querySelectorAll("a[href]")) {
            DomElement a = (DomElement) n;
            if (path(overview, a).equals(j.contextPath + "/batch-control") && insideHeader(a)) {
                header.add(a);
            }
        }
        assertEquals(1, header.size(), "premise: the page header links to batch-control once");
        header.forEach(a -> assertIcon(a, "the header link to Batch Control"));
        List<DomNode> tabs = overview.querySelectorAll("a[data-batch-control-tab]");
        assertTrue(tabs.size() >= 2, "premise: u1 sees the section tabs");
        for (DomNode tab : tabs) {
            assertIcon((DomElement) tab, "the section tab " + ((DomElement) tab).getAttribute("data-batch-control-tab"));
        }

        HtmlPage jobPage = UsabilityFixtures.htmlPage(j, "u1", job.getUrl());
        for (String target : new String[] {j.contextPath + "/job/batch-x/batch-control", j.contextPath + "/batch-control/grants/new"}) {
            List<DomElement> entries = sidePanelLinks(jobPage, target);
            assertEquals(1, entries.size(), "u1's job page must have one side-panel entry to " + target);
            assertIcon(entries.get(0), "the side-panel entry to " + target);
        }

        assertTrue(iconActions("nobc", j.jenkins.getActions()).isEmpty(), "guard: nobc is offered no Batch Control root action");
        assertTrue(iconActions("nobc", job.getAllActions()).isEmpty(), "guard: nobc is offered no Batch Control job entry");
        HtmlPage nobcJob = UsabilityFixtures.htmlPage(j, "nobc", job.getUrl());
        for (String target : new String[] {j.contextPath + "/job/batch-x/batch-control", j.contextPath + "/batch-control/grants/new"}) {
            assertTrue(sidePanelLinks(nobcJob, target).isEmpty(), "guard: nobc's job page has no side-panel entry to " + target);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static List<Action> iconActions(String user, List<? extends Action> actions) {
        List<Action> out = new ArrayList<>();
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            for (Action a : actions) {
                if (a.getClass().getName().startsWith(PACKAGE) && a.getIconFileName() != null) {
                    out.add(a);
                }
            }
        }
        return out;
    }

    private static String iconOf(String user, Action a) {
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            return a.getIconFileName();
        }
    }

    private static void assertIcon(DomElement entry, String what) {
        List<DomNode> svgs = entry.querySelectorAll("svg");
        assertFalse(svgs.isEmpty(), what + " must render an svg icon: " + entry.asXml());
        for (DomNode svg : svgs) {
            assertFalse(svg.querySelectorAll("path, circle, rect, polygon, polyline, line, ellipse, use").isEmpty(),
                    what + ": the svg must draw something (a path or shape): " + svg.asXml());
        }
    }

    private static List<DomElement> sidePanelLinks(HtmlPage page, String targetPath) throws Exception {
        List<DomElement> out = new ArrayList<>();
        DomElement side = page.getElementById("side-panel");
        if (side == null) {
            return out;
        }
        for (DomNode n : side.querySelectorAll("a[href]")) {
            if (path(page, (DomElement) n).equals(targetPath)) {
                out.add((DomElement) n);
            }
        }
        return out;
    }

    private static String path(HtmlPage page, DomElement anchor) {
        try {
            String p = URI.create(page.getFullyQualifiedUrl(anchor.getAttribute("href")).toExternalForm()).normalize().getPath();
            return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean insideHeader(DomElement e) {
        for (DomNode n = e; n != null; n = n.getParentNode()) {
            if (n instanceof DomElement d && "header".equals(d.getTagName())) {
                return true;
            }
        }
        return false;
    }
}
