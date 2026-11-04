package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.User;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextArea;
import org.htmlunit.util.NameValuePair;
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
 * The Batch Control global configuration, seen by the people who maintain it. Matrix rows
 * T-CFG-04 (e2e-03 DEF-08, note 123) and T-02-44 (DEF-10, note 124).
 *
 * <p>Written from docs/SPEC.md (section 5, section 6 usability line, items 2, 7, 8, 11),
 * docs/reports/e2e-03.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ConfigUsabilityTest {

    private static final String DESCRIPTOR =
            "descriptorByName/io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration/";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, BatchControlPermissions.MANAGE).everywhere().to("manager")
                .grant(Jenkins.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.save();
    }

    /**
     * T-CFG-04 (DEF-08): invalid values in the number, duration-option and incident-result fields
     * get a message next to the field (the field's form validation answers an error; a valid
     * value answers none), and a submitted form carrying them is not saved as a success: nothing
     * invalid is stored and the previous values are kept.
     */
    @Test
    public void t_cfg_04_invalidGlobalSettingsAreRefusedWithAFieldMessage() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        assertEquals(240, cfg.getMaxGrantMinutes(), "fixture: maxGrantMinutes default");
        assertEquals(72, cfg.getPendingTimeoutHours(), "fixture: pendingTimeoutHours default");
        assertEquals(Arrays.asList(15, 30, 60), cfg.getGrantDurationOptions(), "fixture: grantDurationOptions default");
        assertEquals(Arrays.asList("FAILURE", "UNSTABLE"), cfg.getIncidentResults(), "fixture: incidentResults default");

        // the message next to the field: each field's check refuses the invalid value and accepts a valid one
        String[][] checks = {
            {"MaxGrantMinutes", "0", "120"},
            {"MaxGrantMinutes", "-1", "240"},
            {"PendingTimeoutHours", "0", "24"},
            {"ApprovedRunTimeoutMinutes", "-1", "60"},
            {"RetentionMonths", "0", "24"},
            {"GrantDurationOptions", "15,abc", "15, 30"},
            {"IncidentResults", "FAILURE, BOGUS", "FAILURE, ABORTED"},
        };
        for (String[] check : checks) {
            String invalid = validate(check[0], check[1]);
            assertTrue(isError(invalid), "check" + check[0] + " must show an error next to the field for '" + check[1]
                    + "', answered: " + excerpt(invalid));
            String valid = validate(check[0], check[2]);
            assertFalse(isError(valid), "check" + check[0] + " must accept the valid value '" + check[2] + "' (negative twin), answered: "
                    + excerpt(valid));
        }

        // the submitted form: invalid values are not saved as a success
        JenkinsRule.WebClient wc = UsabilityFixtures.client(j, "admin");
        HtmlForm form = wc.goTo("configure").getFormByName("config");
        UsabilityFixtures.setField(form, "maxGrantMinutes", "0");
        UsabilityFixtures.setField(form, "grantDurationOptions", "15,abc");
        UsabilityFixtures.setField(form, "incidentResults", "FAILURE, BOGUS");
        Page result = j.submit(form);
        int code = result.getWebResponse().getStatusCode();
        String resultUrl = result.getUrl().toExternalForm();
        // a successful save of the global form redirects to the Manage Jenkins page
        assertTrue(code >= 400 || !UsabilityFixtures.stripQueryAndSlash(resultUrl).endsWith("/manage"),"a form with invalid values must not be answered as a successful save (HTTP "
                + code + ", landed on " + resultUrl + ")");
        UsabilityFixtures.assertPlainRefusal("the refused global save", UsabilityFixtures.text(result), null);

        BatchControlGlobalConfiguration after = BatchControlGlobalConfiguration.get();
        assertEquals(240, after.getMaxGrantMinutes(), "maxGrantMinutes 0 must not be stored and the previous value must stay");
        assertEquals(Arrays.asList(15, 30, 60), after.getGrantDurationOptions(), "'15,abc' must not be stored (not even as [15])");
        assertFalse(after.getIncidentResults().contains("BOGUS"), "BOGUS is not a build result and must not be stored as an incident result: "
                + after.getIncidentResults());
        assertEquals(Arrays.asList("FAILURE", "UNSTABLE"), after.getIncidentResults(), "the previous incident results must stay");
    }

    /**
     * T-02-44 (DEF-10): the BatchControl/Manage permission is enough to open and save the Batch
     * Control configuration (SPEC 6 usability line, SPEC 2). A holder of Overall/Read +
     * BatchControl/Manage finds the configuration from the Batch Control screen, it opens (no 403)
     * and a changed value is saved. Negative: a holder of ViewHistory only is offered no such link
     * and the same URL refuses them without changing anything.
     */
    @Test
    public void t_02_44_manageHolderOpensAndSavesTheBatchControlConfiguration() throws Exception {
        HtmlPage root = UsabilityFixtures.htmlPage(j, "manager", "batch-control/");
        assertEquals(200, root.getWebResponse().getStatusCode(), "fixture: a Manage holder reaches the Batch Control screen");
        HtmlAnchor link = configurationLink(root);
        assertNotNull(link, "the Batch Control screen must offer a Manage holder a link to the Batch Control configuration"
                + " (caption naming configuration or settings); anchors were " + UsabilityFixtures.resolvedHrefs(root));
        String target = root.getFullyQualifiedUrl(link.getHrefAttribute()).toExternalForm();
        String relative = target.substring(j.getURL().toExternalForm().length());

        JenkinsRule.WebClient wc = UsabilityFixtures.client(j, "manager");
        Page opened = wc.getPage(new WebRequest(new java.net.URL(target), HttpMethod.GET));
        assertEquals(200, opened.getWebResponse().getStatusCode(), "the configuration must open for a BatchControl/Manage holder, got HTTP "
                + opened.getWebResponse().getStatusCode() + " at " + target + ": " + excerpt(UsabilityFixtures.text(opened)));
        assertTrue(opened instanceof HtmlPage, "the configuration must be an HTML page");
        HtmlForm form = null;
        for (HtmlForm candidate : ((HtmlPage) opened).getForms()) {
            if (UsabilityFixtures.hasField(candidate, "pendingTimeoutHours")) {
                form = candidate;
            }
        }
        assertNotNull(form, "the page must carry the Batch Control settings form (field pendingTimeoutHours); forms: "
                + UsabilityFixtures.formActions((HtmlPage) opened));
        UsabilityFixtures.setField(form, "pendingTimeoutHours", "48");
        Page saved = j.submit(form);
        assertTrue(saved.getWebResponse().getStatusCode() < 400, "the Manage holder's save must succeed, got HTTP "
                + saved.getWebResponse().getStatusCode() + ": " + excerpt(UsabilityFixtures.text(saved)));
        assertEquals(48, BatchControlGlobalConfiguration.get().getPendingTimeoutHours(), "the Manage holder's change must be saved");

        // negative: ViewHistory alone offers no link and cannot open or change the configuration
        HtmlPage viewerRoot = UsabilityFixtures.htmlPage(j, "viewer", "batch-control/");
        assertEquals(null, configurationLink(viewerRoot), "a user without BatchControl/Manage must not be offered the configuration link");
        Page refused = UsabilityFixtures.get(j, UsabilityFixtures.clientNoJs(j, "viewer"), relative);
        int code = refused.getWebResponse().getStatusCode();
        assertTrue(code == 403 || code == 404, "the configuration must refuse a user without BatchControl/Manage, got HTTP " + code);
        assertEquals(48, BatchControlGlobalConfiguration.get().getPendingTimeoutHours(), "nothing may have changed");
    }

    /**
     * T-CFG-15 (SPEC 3 job property: {@code approvalRequired}, the job's own approver list;
     * SPEC 6 {@code blockTimer}, {@code blockUpstream}, {@code allowedUpstreamJobs}; #31, e2e script
     * rest-admin-screens.sh): the administrator opens a job's configuration page. Its form offers the
     * three switches as checkboxes and the job approver and allowed upstream lists as fields, each
     * showing the stored value (approvalRequired, blockTimer and blockUpstream ticked; approver
     * {@code admin}; upstream {@code feeder}). Unticking approvalRequired, adding {@code manager} to
     * the approvers and {@code other-feeder} to the upstream list and saving stores exactly that, and
     * leaves the two other switches on. A field that is not rendered, not pre-filled or not bound
     * fails this row.
     */
    @Test
    public void t_cfg_15_jobConfigurationFormOffersAndSavesTheBatchControlFields() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("admin", "manager"));
        cfg.save();
        User.getById("manager", true);
        j.createFreeStyleProject("feeder");
        j.createFreeStyleProject("other-feeder");
        FreeStyleProject job = j.createFreeStyleProject("form-job");
        BatchControlJobProperty stored = new BatchControlJobProperty(true);
        stored.setBlockTimer(true);
        stored.setBlockUpstream(true);
        stored.setJobApprovers(Arrays.asList("admin"));
        stored.setAllowedUpstreamJobs(Arrays.asList("feeder"));
        BatchControlFixtures.setBatchControl(job, stored);

        HtmlForm form = j.createWebClient().login("admin").getPage(job, "configure").getFormByName("config");
        for (String box : new String[] {"approvalRequired", "blockTimer", "blockUpstream"}) {
            HtmlCheckBoxInput checkbox = checkbox(form, box);
            assertNotNull(checkbox, "the job configuration form must offer the " + box + " checkbox");
            assertTrue(checkbox.isChecked(), "the " + box + " checkbox must show the stored value (on)");
        }
        for (String field : new String[] {"jobApproversText", "allowedUpstreamJobsText"}) {
            assertTrue(UsabilityFixtures.hasField(form, field), "the job configuration form must offer the " + field + " field");
        }
        assertEquals(List.of("admin"), lines(fieldValue(form, "jobApproversText")), "the approver field must show the stored approver");
        assertEquals(List.of("feeder"), lines(fieldValue(form, "allowedUpstreamJobsText")), "the upstream field must show the stored job");

        checkbox(form, "approvalRequired").setChecked(false);
        setList(form, "jobApproversText", "admin", "manager");
        setList(form, "allowedUpstreamJobsText", "feeder", "other-feeder");
        Page saved = j.submit(form);
        assertTrue(saved.getWebResponse().getStatusCode() < 400, "the administrator's save must succeed, got HTTP "
                + saved.getWebResponse().getStatusCode());

        FreeStyleProject reloaded = j.jenkins.getItemByFullName("form-job", FreeStyleProject.class);
        assertEquals(1, reloaded.getProperties().values().stream().filter(p -> p instanceof BatchControlJobProperty).count(),
                "the saved job must carry exactly one Batch Control property");
        BatchControlJobProperty property = reloaded.getProperty(BatchControlJobProperty.class);
        assertFalse(property.isApprovalRequired(), "the unticked approvalRequired must be saved");
        assertTrue(property.isBlockTimer(), "blockTimer must stay on");
        assertTrue(property.isBlockUpstream(), "blockUpstream must stay on");
        assertEquals(List.of("admin", "manager"), property.getJobApprovers(), "the approver field must be saved");
        assertEquals(List.of("feeder", "other-feeder"), property.getAllowedUpstreamJobs(), "the upstream field must be saved");
    }

    // ---------------------------------------------------------------- helpers

    private static HtmlCheckBoxInput checkbox(HtmlForm form, String field) {
        for (DomElement element : form.getElementsByTagName("input")) {
            String name = element.getAttribute("name");
            if (element instanceof HtmlCheckBoxInput box && (name.equals("_." + field) || name.endsWith("." + field) || name.equals(field))) {
                return box;
            }
        }
        return null;
    }

    private static DomElement field(HtmlForm form, String field) {
        for (String tag : new String[] {"textarea", "input"}) {
            for (DomElement element : form.getElementsByTagName(tag)) {
                String name = element.getAttribute("name");
                if (name.equals("_." + field) || name.endsWith("." + field) || name.equals(field)) {
                    return element;
                }
            }
        }
        return null;
    }

    private static String fieldValue(HtmlForm form, String name) {
        DomElement element = field(form, name);
        assertNotNull(element, "the form must carry " + name);
        return element instanceof HtmlTextArea area ? area.getText() : ((HtmlInput) element).getValue();
    }

    /** Fills a list field: one entry per line in a text area, comma-separated in a one-line input. */
    private static void setList(HtmlForm form, String name, String... entries) {
        DomElement element = field(form, name);
        assertNotNull(element, "the form must carry " + name);
        if (element instanceof HtmlTextArea area) {
            area.setText(String.join("\n", entries));
        } else {
            ((HtmlInput) element).setValue(String.join(", ", entries));
        }
    }

    private static List<String> lines(String value) {
        List<String> out = new ArrayList<>();
        for (String entry : value.split("[,\\r\\n]+")) {
            if (!entry.isBlank()) {
                out.add(entry.trim());
            }
        }
        return out;
    }


    private static final Pattern CONFIG_CAPTION = Pattern.compile("(?i)(configur|settings)");

    private HtmlAnchor configurationLink(HtmlPage page) throws Exception {
        String root = j.getURL().toExternalForm();
        for (HtmlAnchor a : UsabilityFixtures.anchorsCaptioned(page, CONFIG_CAPTION)) {
            String href = a.getHrefAttribute();
            if (href == null || href.isEmpty() || href.startsWith("#")) {
                continue;
            }
            String resolved = page.getFullyQualifiedUrl(href).toExternalForm();
            if (resolved.startsWith(root) && !resolved.contains("/job/")) {
                return a;
            }
        }
        return null;
    }

    /** POSTs the field's form validation (as the configure page does, with the crumb) and returns the answer. */
    private String validate(String field, String value) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("value", value));
        org.htmlunit.WebResponse response = ApproverFormFixtures.post(j, "admin", DESCRIPTOR + "check" + field, params);
        assertEquals(200, response.getStatusCode(), "check" + field + " must be served for the configure form (a message next to the field),"
                + " got HTTP " + response.getStatusCode());
        return response.getContentAsString();
    }

    private static boolean isError(String formValidationBody) {
        return formValidationBody.contains("class=\"error\"") || formValidationBody.contains("class=error")
                || formValidationBody.contains("class='error'");
    }
}
