package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No "Back" link on the detail pages and request forms (issue #31, note 370, gap X-18, retired drivers
 * browser/section-a.mjs #8b, browser/audit/sa-read.mjs #12 and browser/verify/v7.mjs #6). Matrix row
 * T-UI-310.
 *
 * <p>Basis: T-UI-23's rule (e2e-run3 DEF-28, note 140: the breadcrumb already leads back, so no
 * hierarchical "Back to ..." link), applied by T-UI-301 to the eight section pages and by T-UI-23 to the
 * refusal pages; this row applies it to the four detail pages and the three request forms. No SPEC
 * sentence beyond the usability line.
 *
 * <p>Written from docs/SPEC.md section 6 (usability), docs/TEST-MATRIX.md (T-UI-23, T-UI-301) and the
 * retired drivers, read only to learn what each looked at. No src/main knowledge.
 */
@WithJenkins
public class DetailPageBackLinkTest {

    /** T-UI-23's text pattern. */
    private static final Pattern BACK_TEXT = Pattern.compile("(?i)\\bback\\s+to\\b");
    /** T-UI-23's caption pattern: a caption starting with "Back" after any arrow or punctuation. */
    private static final Pattern BACK_CAPTION = Pattern.compile("(?is)^\\W*back\\b.*");

    private JenkinsRule j;

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
    }

    /**
     * T-UI-310 (T-UI-23's rule, SPEC 6 usability; #31 gap X-18): u1 opens the detail pages of a PENDING
     * run request, activation request and grant request, and the Request Run form of {@code batch-x}, the
     * grant form ({@code grants/new?scopeFullName=batch-x}) and the activation form of {@code new-x}; the
     * administrator opens the detail page of the incident of {@code fail-x}'s failed build. No main panel
     * holds a "Back to" text, and no anchor, button or submit input in it has a caption starting with
     * "Back" after any arrow or punctuation (T-UI-23's patterns). Premise: each page renders core's
     * breadcrumb list with at least two items, so the way back exists.
     */
    @Test
    public void t_ui_310_detailPagesAndFormsHaveNoBackLink() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
        BatchControlFixtures.activate(job);
        FreeStyleProject inactive = j.createFreeStyleProject("new-x");
        setBatchControl(inactive, new BatchControlJobProperty(true));
        FreeStyleProject failing = BatchControlFixtures.uncontrolled(j.createFreeStyleProject("fail-x"));
        BatchControlFixtures.activateAsAdmin(failing); // D-46: a cause-less submission needs an activation
        failing.getBuildersList().add(new FailureBuilder());
        j.assertBuildStatus(Result.FAILURE, failing.scheduleBuild2(0));
        j.waitUntilNoActivity();
        Incident incident = IncidentService.get().list(YearMonth.now(BatchClock.clock())).stream()
                .filter(i -> "fail-x#1".equals(i.getRunId())).findFirst().orElse(null);
        assertNotNull(incident, "fixture: the failed build opened an incident");

        String run = submitRunOk(j, "u1", job, "month-end batch", "a1");
        String activation = submitActivationOk(j, "u1", inactive, "ACTIVATE", "go live", "a1");
        String grant = submitGrantOk(j, "u1", "batch-x", List.of("CONFIGURE"), 30, "fix the schedule", null, "a1");

        Map<String, String[]> pages = new LinkedHashMap<>();
        pages.put("the run request page", new String[] {"u1", "batch-control/requests/" + run + "/"});
        pages.put("the activation request page", new String[] {"u1", "batch-control/activations/" + activation + "/"});
        pages.put("the grant request page", new String[] {"u1", "batch-control/grants/" + grant + "/"});
        pages.put("the incident page", new String[] {"admin", "batch-control/incidents/" + incident.getId() + "/"});
        pages.put("the Request Run form", new String[] {"u1", job.getUrl() + "batch-control/"});
        pages.put("the grant form", new String[] {"u1", "batch-control/grants/new?scopeFullName=batch-x"});
        pages.put("the activation form", new String[] {"u1", inactive.getUrl() + "batch-control-activation/"});

        for (Map.Entry<String, String[]> e : pages.entrySet()) {
            String what = e.getKey() + " (" + e.getValue()[1] + ")";
            HtmlPage page = UsabilityFixtures.htmlPage(j, e.getValue()[0], e.getValue()[1]);
            assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: " + e.getValue()[0] + " opens " + what);
            List<?> crumbs = page.getByXPath("//li[contains(concat(' ', normalize-space(@class), ' '), ' jenkins-breadcrumbs__list-item ')]");
            assertTrue(crumbs.size() >= 2, "premise: " + what + " renders core's breadcrumb list, which leads back");
            DomElement main = page.getElementById("main-panel");
            assertNotNull(main, "fixture: " + what + " has a main panel");
            String text = main.asNormalizedText();
            assertFalse(BACK_TEXT.matcher(text).find(), what + ": no 'Back to ...' text: " + excerpt(text));
            for (DomNode n : main.querySelectorAll("a, button, input[type=submit], input[type=button]")) {
                String caption = n instanceof HtmlInput input ? input.getValue() : n.asNormalizedText();
                assertFalse(BACK_CAPTION.matcher(caption.trim()).matches(), what + ": no back link may be offered: '" + caption
                        + "' " + ((DomElement) n).asXml());
            }
        }
    }
}
