package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlOption;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two details of the permission-window screens that only the retired legacy e2e drivers checked
 * (issue #31, note 369): a window length or remaining time of one minute reads "1 minute", never
 * "1 minutes" (T-UI-296; maintainer decision 2026-11-06, issue #31; {@code e2e/r9/s72.py},
 * {@code e2e/r10/s86.py}, verified in e2e-10 item 4), and the grant form's dropdowns are core's
 * {@code jenkins-select} (T-UI-302; {@code e2e/r9/checklist.py}).
 *
 * <p>Users: {@code u1} (BatchControl/RequestGrant), {@code a1} (BatchControl/Approve), both with
 * Overall/Read and Item/Read. Change control on, approver a1, grant length options 1, 2 and 15
 * minutes; BatchClock fixed at T0 and moved by the test (no real waiting).
 *
 * <p>Written from docs/SPEC.md item 8 (the requester picks a length from the configured options;
 * D-33: the remaining time follows the plugin clock), docs/TEST-MATRIX.md (T-08-290, T-UI-250) and the
 * e2e-10 report; no src/main knowledge.
 */
@WithJenkins
public class GrantScreenPolishTest {

    /** "1 minutes" (a count of one with the plural), not part of a larger number such as "11 minutes". */
    private static final Pattern ONE_PLURAL = Pattern.compile("(?i)(?<![0-9.,])1\\s+minutes\\b");
    /** "2 minute" (a count of two with the singular), not "2 minutes", "12 minute" or "2 minute(s)". */
    private static final Pattern TWO_SINGULAR = Pattern.compile("(?i)(?<![0-9.,])2\\s+minute\\b(?!\\s*\\()");

    private JenkinsRule j;

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
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.setGrantDurationOptions(Arrays.asList(1, 2, 15));
        cfg.save();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        j.createFreeStyleProject("one-x");
        j.createFreeStyleProject("two-x");
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-UI-296 (SPEC 8, D-33; maintainer decision 2026-11-06, issue #31: "1 minute", never "1 minutes"):
     * <ol>
     * <li>u1's grant form, as a page ({@code grants/new}) and as the dialog ({@code grants/dialog}),
     * labels the length options exactly "1 minute", "2 minutes" and "15 minutes";</li>
     * <li>u1 requests a 1-minute window on {@code one-x} and a 2-minute window on {@code two-x}: each
     * request page, seen by u1 and by a1, has a "Duration" row reading exactly "1 minute" and
     * "2 minutes";</li>
     * <li>a1 approves both at T0; at T0 and at T0+1 min (when the 2-minute window has one minute left)
     * the grants list and both request pages never render "1 minutes" nor "2 minute" in the main
     * panel; premise: at each moment the 2-minute window has an Active row.</li>
     * </ol>
     * The pending grants list is checked the same way before the approval. Not covered: the run
     * request's approved-run timeout text (a different length; note 369).
     */
    @Test
    public void t_ui_296_oneMinuteIsSingular() throws Exception {
        for (String path : new String[] {"batch-control/grants/new?scopeFullName=one-x",
                "batch-control/grants/dialog?scopeFullName=one-x"}) {
            HtmlSelect length = durationSelect(grantForm(path), path);
            Map<String, String> labels = new LinkedHashMap<>();
            for (HtmlOption option : length.getOptions()) {
                labels.put(option.getValueAttribute(), option.asNormalizedText().trim());
            }
            assertEquals(Map.of("1", "1 minute", "2", "2 minutes", "15", "15 minutes"), labels,
                    path + ": the length options must read '1 minute' (singular) and 'N minutes' otherwise");
        }

        GrantRequest one = request("one-x", 1);
        GrantRequest two = request("two-x", 2);
        for (String viewer : new String[] {"u1", "a1"}) {
            assertEquals("1 minute", durationCell(viewer, one), viewer + ": the 1-minute request's Duration row");
            assertEquals("2 minutes", durationCell(viewer, two), viewer + ": the 2-minute request's Duration row");
        }
        assertNoWrongCount(mainText(page("u1", "batch-control/grants/")), "the grants list with two pending requests");

        Grant oneWindow = approve(one);
        Grant twoWindow = approve(two);
        for (int minute = 0; minute <= 1; minute++) {
            BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(minute)), ZoneOffset.UTC));
            String at = "T0+" + minute + " min";
            HtmlPage list = page("u1", "batch-control/grants/");
            assertNotNull(WindowStateFixtures.activeRow(j, list, twoWindow.getId()),
                    at + ": premise: the 2-minute window has an Active row: " + excerpt(mainText(list)));
            assertNoWrongCount(mainText(list), at + ": the grants list");
            for (GrantRequest r : List.of(one, two)) {
                assertNoWrongCount(mainText(page("u1", "batch-control/grants/" + r.getId() + "/")),
                        at + ": the request page of " + r.getId());
            }
        }
        assertNotNull(oneWindow, "fixture: the 1-minute window opened");
    }

    /**
     * T-UI-302 (screen contract; e2e/r9/checklist.py: "selects jenkins-select"): every {@code select}
     * of u1's grant form, on the page ({@code grants/new}) and in the dialog ({@code grants/dialog}),
     * is core's {@code jenkins-select} (the class {@code jenkins-select} or
     * {@code jenkins-select__input} on the select or an ancestor inside the form). Premise: the form
     * has at least one select (the length, {@code durationMinutes}).
     */
    @Test
    public void t_ui_302_grantFormSelectsAreJenkinsSelects() throws Exception {
        for (String path : new String[] {"batch-control/grants/new?scopeFullName=one-x",
                "batch-control/grants/dialog?scopeFullName=one-x"}) {
            HtmlForm form = grantForm(path);
            List<HtmlSelect> selects = new ArrayList<>();
            for (DomElement e : form.getHtmlElementDescendants()) {
                if (e instanceof HtmlSelect s) {
                    selects.add(s);
                }
            }
            assertNotNull(durationSelect(form, path), "premise");
            assertFalse(selects.isEmpty(), path + ": premise: the grant form has a dropdown");
            for (HtmlSelect select : selects) {
                boolean styled = false;
                for (DomNode n = select; n instanceof DomElement e && !styled; n = n.getParentNode()) {
                    styled = (" " + e.getAttribute("class") + " ").matches(".*\\sjenkins-select(__input)?\\s.*");
                    if (e == form) {
                        break;
                    }
                }
                assertTrue(styled, path + ": the dropdown '" + select.getAttribute("name") + "' must be core's"
                        + " jenkins-select: " + select.getParentNode().asXml());
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void assertNoWrongCount(String text, String where) {
        assertFalse(ONE_PLURAL.matcher(text).find(), where + ": one minute must read '1 minute', not '1 minutes': "
                + excerpt(text));
        assertFalse(TWO_SINGULAR.matcher(text).find(), where + ": two minutes must read '2 minutes', not '2 minute': "
                + excerpt(text));
    }

    private HtmlForm grantForm(String path) throws Exception {
        HtmlPage page = page("u1", path);
        HtmlForm form = null;
        for (HtmlForm f : page.getForms()) {
            if ("createGrantRequest".equals(f.getAttribute("name"))) {
                assertTrue(form == null, path + ": one grant form");
                form = f;
            }
        }
        assertNotNull(form, path + ": the page must carry the grant form; forms: " + UsabilityFixtures.formActions(page));
        return form;
    }

    private static HtmlSelect durationSelect(HtmlForm form, String path) {
        for (DomElement e : form.getHtmlElementDescendants()) {
            if (e instanceof HtmlSelect s && "durationMinutes".equals(s.getAttribute("name"))) {
                return s;
            }
        }
        throw new AssertionError(path + ": the grant form must offer the length as a dropdown (durationMinutes): "
                + excerpt(form.asXml()));
    }

    /** The text of the request page's row headed exactly "Duration", as {@code viewer} sees it. */
    private String durationCell(String viewer, GrantRequest request) throws Exception {
        HtmlPage page = page(viewer, "batch-control/grants/" + request.getId() + "/");
        List<String> cells = new ArrayList<>();
        for (DomNode th : main(page).querySelectorAll("tr > th")) {
            if ("Duration".equals(th.asNormalizedText().trim())) {
                DomNode td = th.getParentNode().querySelector("td");
                assertNotNull(td, "the Duration row must have a value cell");
                cells.add(td.asNormalizedText().trim());
            }
        }
        assertEquals(1, cells.size(), viewer + ": the request page must have one row headed 'Duration': "
                + excerpt(mainText(page)));
        return cells.get(0);
    }

    private HtmlPage page(String user, String path) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(j, user, path);
        assertEquals(200, page.getWebResponse().getStatusCode(), "premise: " + user + " opens " + path);
        return page;
    }

    private static DomElement main(HtmlPage page) {
        DomElement main = page.getElementById("main-panel");
        assertNotNull(main, "the page must have a main panel");
        return main;
    }

    private static String mainText(HtmlPage page) {
        return main(page).asNormalizedText();
    }

    private GrantRequest request(String item, int minutes) {
        try (ACLContext ignored = as("u1")) {
            return GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, item),
                    List.of(GrantAction.CONFIGURE), minutes, "fix the schedule of " + item, "a1");
        }
    }

    private Grant approve(GrantRequest request) {
        try (ACLContext ignored = as("a1")) {
            Grant grant = GrantRequestService.get().approve(request.getId(), "ok");
            assertNotNull(grant, "fixture: the window of " + request.getId() + " opens");
            return grant;
        }
    }

    private static ACLContext as(String user) {
        return ACL.as2(User.getById(user, true).impersonate2());
    }
}
