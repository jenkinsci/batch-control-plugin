package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.html.DomElement;
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
 * The incident page's rerun guidance for a user who may open the incident but not request its rerun
 * (matrix row T-11-37, e2e-04 FD-10, note 366). Before the fix the page told such a user that a
 * rerun "is made by a user with the Batch Control/Request permission and Job/Build on the job; ask
 * one of them ... to request it from this page", while a Request holder without ViewHistory gets 403
 * on that page. D-57 keeps the page behind ViewHistory and says that "a user without ViewHistory
 * reruns through Request Run on the job page, which the incident page names"; e2e-04's verification
 * of the fix reads "an accurate text naming ViewHistory + Request ... or an administrator, and the
 * Request Run alternative". (D-38a later dropped Item/Build from requesting, so Job/Build is not
 * asserted either way.)
 *
 * <p>Users: {@code admin}; {@code viewer} (Item/Read, ViewHistory, no Request: opens the incident,
 * cannot rerun); {@code u2} (Item/Read, Request, no ViewHistory: the README's typical requester);
 * {@code a1} the approver.
 *
 * <p>Written from docs/SPEC.md item 11 and 5, docs/DECISIONS.md D-57 and D-38a and
 * docs/reports/e2e-04.md only (no src/main knowledge).
 */
@WithJenkins
public class IncidentRerunGuidanceTest {

    private static final Pattern VIEW_HISTORY = Pattern.compile("(?i)view\\s*history");
    private static final Pattern REQUEST_PERMISSION = Pattern.compile("\\bRequest\\b(?! Run)(?! Rerun)");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u2")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-11-37 (e2e-04 FD-10, D-57): the viewer opens the incident of a failed run. The page offers the
     * viewer no rerun form, and its guidance names what a rerun needs: one sentence names ViewHistory
     * and the Request permission; the main panel names an administrator and the job's "Request Run"
     * page as the alternative. Guards: the administrator's page carries the rerun form; u2 (Request
     * without ViewHistory) gets 403 on the incident page, as D-57 rules.
     */
    @Test
    public void t_11_37_rerunGuidanceNamesViewHistoryAndRequestRun() throws Exception {
        RerunFallbackFixtures.failedFreestyle(j, "guide-x");
        Incident incident = RerunFallbackFixtures.incidentFor("guide-x#1");
        String path = "batch-control/incidents/" + incident.getId() + "/";

        HtmlPage admin = UsabilityFixtures.htmlPage(j, "admin", path);
        assertFalse(rerunForms(admin).isEmpty(), "guard: the administrator is offered the rerun form; forms: "
                + UsabilityFixtures.formActions(admin));
        assertEquals(403, ApproverFormFixtures.get(j, "u2", path).getStatusCode(),
                "guard (D-57): a Request holder without ViewHistory cannot open the incident page");

        HtmlPage viewer = UsabilityFixtures.htmlPage(j, "viewer", path);
        assertEquals(200, viewer.getWebResponse().getStatusCode(), "premise: the viewer opens the incident");
        assertTrue(rerunForms(viewer).isEmpty(), "premise: the viewer may not rerun, so no rerun form is offered; forms: "
                + UsabilityFixtures.formActions(viewer));
        DomElement main = viewer.getElementById("main-panel");
        assertNotNull(main, "premise: the page has a main panel");
        String text = main.asNormalizedText();

        boolean namesBoth = false;
        for (String sentence : text.split("(?<=[.;])\\s+|\\n")) {
            if (VIEW_HISTORY.matcher(sentence).find() && REQUEST_PERMISSION.matcher(sentence).find()) {
                namesBoth = true;
                break;
            }
        }
        assertTrue(namesBoth, "the rerun guidance must name ViewHistory together with the Request permission in one"
                + " sentence (D-57): " + excerpt(text));
        assertTrue(text.contains("Request Run"), "the guidance must name the job's Request Run page as the alternative"
                + " (D-57): " + excerpt(text));
        assertTrue(text.toLowerCase(java.util.Locale.ROOT).contains("administrator"),
                "the guidance must name an administrator (e2e-04 FD-10 verification): " + excerpt(text));
    }

    private static java.util.List<org.htmlunit.html.HtmlForm> rerunForms(HtmlPage page) throws Exception {
        java.util.List<org.htmlunit.html.HtmlForm> out = new java.util.ArrayList<>();
        for (org.htmlunit.html.HtmlForm form : page.getForms()) {
            String action = form.getActionAttribute();
            if (action != null && !action.isEmpty()
                    && UsabilityFixtures.stripQueryAndSlash(page.getFullyQualifiedUrl(action).toExternalForm()).endsWith("/rerun")) {
                out.add(form);
            }
        }
        return out;
    }
}
