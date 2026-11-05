package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matrix rows T-UI-16 .. T-UI-18 (T-UI-18 revised for D-60, note 188) — finding U-02: a controlled job showed <em>two</em> sidebar
 * entries both reading "Request Run", and the one a user was most likely to click was core's own
 * build link, whose href schedules a run with no approved marker and is refused (e2e-01 UX-1).
 *
 * <p>Core's build entry cannot be hidden or re-pointed from a plugin, so the fix renamed it. These
 * rows therefore assert the pairing rather than a single caption: exactly one entry reads
 * "Request Run" and it is the one that opens the request form, while the relabelled core entry
 * says what it is and still points at the build URL. A coordinator ruling on e2e-03 part 2
 * (DEF-25) confirmed this contract — core draws the link for every Item/Build holder — and added
 * that a click on it is refused with a plain-words explanation (SPEC section 6 usability line,
 * docs/LIMITATIONS.md; matrix note 131).
 *
 * <p>Relationship to the existing SPEC row: T-06-15 pins the acceptance criterion of SPEC item 6
 * ("Build Now" is replaced by "Request Run") at page level and keeps passing unchanged. These rows
 * are the screen contract that tells the two entries apart (matrix note 47).
 */
@WithJenkins
public class JobRunSidebarEntryTest {

    private static final String REQUEST_RUN = "Request Run";
    private static final String DIRECT_BUILD = "Direct Build (needs approval)";
    private static final String BUILD_NOW = "Build Now";

    private JenkinsRule j;

    private BatchControlGlobalConfiguration cfg;

    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                // Item/Build matters: core only renders its build entry for a user who has it,
                // so without it this class would measure an absent entry, not a renamed one.
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST)
                        .everywhere().to("u1")
                // nobc: Item/Build without Batch Control permission, who may not request (D-60 twin)
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("nobc")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to("a1"));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        job = j.createFreeStyleProject("plain-x");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-UI-16: on a controlled Freestyle job exactly one entry reads "Request Run" and it opens
     * the request form; a separate, differently named entry is core's build link; and no entry
     * reads "Build Now".
     */
    @Test
    public void t_ui_16_exactlyOneRequestRunEntryAndARenamedDirectBuildEntry() throws Exception {
        assertSidebarPairing(job);
    }

    /**
     * T-UI-17: with run control off the plugin touches neither entry — core's "Build Now" is back
     * and neither plugin caption appears. This is the falsifiability twin of T-UI-16: it proves the
     * captions in that row come from the plugin and that the plugin leaves an uncontrolled
     * installation alone (SPEC item 1).
     */
    @Test
    public void t_ui_17_runControlOffLeavesCoresBuildNowEntryAlone() throws Exception {
        cfg.setRunControlEnabled(false);
        cfg.save();
        assertFalse(BatchControlGlobalConfiguration.get().isRunControlEnabled(), "fixture: run control must really be off");
        assertTrue(job.getProperty(BatchControlJobProperty.class).isApprovalRequired(), "fixture: the job still asks for approval - only the global switch moved, so"
                + " this row measures the switch");

        HtmlPage page = jobPage("u1", job);
        String text = page.asNormalizedText();

        assertTrue(text.contains(BUILD_NOW), "with run control off core's own Build Now caption must be back; the page read: "
                + excerpt(text));
        assertFalse(text.contains(DIRECT_BUILD), "the plugin's relabelled caption must not appear while run control is off");
        assertTrue(captionedEntries(page, REQUEST_RUN).isEmpty(), "no Request Run entry may appear while run control is off");
    }

    /**
     * T-UI-18: a parameterised Pipeline job behaves the same. This is the build-with-parameters
     * label path — core asks for the same message key with a different default caption — and the
     * other {@code ParameterizedJob} implementation, which uses a different constant from
     * Freestyle.
     */
    @Test
    public void t_ui_18_parameterisedPipelineJobBehavesTheSame() throws Exception {
        WorkflowJob pipeline = j.createProject(WorkflowJob.class, "pipe-x");
        pipeline.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        pipeline.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("DATE", "2026-09-27", "batch date")));
        BatchControlFixtures.setBatchControl(pipeline, new BatchControlJobProperty(true));
        assertTrue(pipeline.isParameterized(), "fixture: the pipeline must be parameterised, or this row does not exercise the"
                + " build-with-parameters caption at all");

        assertSidebarPairing(pipeline);

        // Core's parameterised caption must not survive either: it is the same message key.
        assertFalse(jobPage("u1", pipeline).asNormalizedText().contains("Build with Parameters"), "core's parameterised build caption must be replaced as well");
    }

    /**
     * T-06-112 (SPEC 6: "Build Now" is replaced by "Request Run" on the sidebar of a job <em>subject
     * to approval</em>, so not on any other; #31, retired e2e script rest-screens.sh #3): with run control on,
     * u1 opens {@code free-x}, whose job property has {@code approvalRequired} off, and {@code bare-x},
     * which carries no Batch Control job property. Each page offers core's "Build Now" entry at the
     * job's {@code build} URL, no "Request Run" entry, no "Direct Build (needs approval)" caption and
     * no link to the job's request form ({@code batch-control/}); u1's POST of that build URL (crumb)
     * runs the job once. Guard: on the same instance the approval-required {@code plain-x} shows
     * "Request Run" and no "Build Now", so the run-control decoration is on and the difference is the
     * job's setting. T-UI-17 covers the run-control-off case.
     */
    @Test
    public void t_06_112_jobWithoutApprovalKeepsBuildNowWhileRunControlIsOn() throws Exception {
        assertTrue(cfg.isRunControlEnabled(), "premise: run control is on");
        FreeStyleProject free = j.createFreeStyleProject("free-x");
        BatchControlFixtures.setBatchControl(free, new BatchControlJobProperty(false));
        assertFalse(free.getProperty(BatchControlJobProperty.class).isApprovalRequired(), "fixture: free-x does not ask for approval");
        FreeStyleProject bare = BatchControlFixtures.uncontrolled(j.createFreeStyleProject("bare-x"));

        HtmlPage controlled = jobPage("u1", job);
        assertEquals(1, captionedEntries(controlled, REQUEST_RUN).size(), "guard: the approval-required plain-x offers Request Run");
        assertTrue(captionedEntries(controlled, BUILD_NOW).isEmpty(), "guard: the approval-required plain-x offers no Build Now");

        for (FreeStyleProject target : List.of(free, bare)) {
            String name = target.getFullName();
            HtmlPage page = jobPage("u1", target);
            String buildPath = new URL(j.getURL(), target.getUrl() + "build").getPath();
            List<HtmlAnchor> buildNow = captionedEntries(page, BUILD_NOW);
            assertFalse(buildNow.isEmpty(), name + ": core's Build Now entry must stay while run control is on: "
                    + excerpt(page.asNormalizedText()));
            for (HtmlAnchor a : buildNow) {
                assertEquals(buildPath, page.getFullyQualifiedUrl(a.getHrefAttribute()).getPath(),
                        name + ": Build Now must stay core's build link");
            }
            assertTrue(captionedEntries(page, REQUEST_RUN).isEmpty(), name + ": no Request Run entry on a job that needs no"
                    + " approval: " + hrefs(captionedEntries(page, REQUEST_RUN)));
            assertFalse(page.asNormalizedText().contains(DIRECT_BUILD), name + ": core's entry must not be relabelled");
            String formPath = new URL(j.getURL(), target.getUrl() + "batch-control/").getPath();
            for (HtmlAnchor a : page.getAnchors()) {
                String href = a.getHrefAttribute();
                if (!href.isEmpty() && !href.startsWith("#") && !href.startsWith("javascript:")) {
                    assertFalse(page.getFullyQualifiedUrl(href).getPath().equals(formPath),
                            name + ": no link to the request form may be offered: " + a.asXml());
                }
            }

            JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("u1");
            int code = wc.getPage(new WebRequest(wc.createCrumbedUrl(target.getUrl() + "build"),
                    HttpMethod.POST)).getWebResponse().getStatusCode();
            assertTrue(code < 400, name + ": u1's Build Now POST must be accepted, got HTTP " + code);
            j.waitUntilNoActivity();
            assertEquals(1, target.getBuilds().size(), name + ": the Build Now POST runs the job once");
        }
    }

    // ---------------------------------------------------------------- shared assertion

    /**
     * The U-02 contract on one job page: one "Request Run" entry into the plugin's form, one
     * differently named entry at core's build URL, and no "Build Now".
     */
    private void assertSidebarPairing(Job<?, ?> target) throws Exception {
        HtmlPage page = jobPage("u1", target);
        String text = page.asNormalizedText();

        List<HtmlAnchor> requestRun = captionedEntries(page, REQUEST_RUN);
        assertEquals(1, requestRun.size(), target.getFullName() + ": exactly one sidebar entry may read \"" + REQUEST_RUN
                + "\", but " + requestRun.size() + " did: " + hrefs(requestRun));
        assertTrue(requestRun.get(0).getHrefAttribute().endsWith(target.getUrl() + "batch-control"), target.getFullName() + ": the Request Run entry must open the plugin's request"
                + " form, but pointed at " + requestRun.get(0).getHrefAttribute());

        List<HtmlAnchor> directBuild = captionedEntries(page, DIRECT_BUILD);
        assertEquals(1, directBuild.size(), target.getFullName() + ": core's build entry must be present exactly once under its"
                + " own caption, but " + directBuild.size() + " entries matched: " + hrefs(directBuild));
        assertTrue(directBuild.get(0).getHrefAttribute().contains("build"), target.getFullName() + ": the relabelled entry must still be core's build link,"
                + " but pointed at " + directBuild.get(0).getHrefAttribute());
        assertNotNull(directBuild.get(0).getHrefAttribute(), "the relabelled entry must carry an href");

        assertFalse(text.contains(BUILD_NOW), target.getFullName() + ": the Build Now caption must not be visible (SPEC item 6);"
                + " the page read: " + excerpt(text));

        // The two entries must be different links: the whole finding was two entries with the same
        // caption going to different places, and a fix that merged them would lose the request form.
        assertFalse(requestRun.get(0).getHrefAttribute().equals(directBuild.get(0).getHrefAttribute()), target.getFullName() + ": the two entries must be distinct links");

        assertDirectBuildClickIsExplained(target, page.getFullyQualifiedUrl(directBuild.get(0).getHrefAttribute()).toExternalForm());
    }

    /**
     * Coordinator ruling (e2e-03 part 2, reverting the DEF-25 revision of these rows): core draws
     * its build link for every Item/Build holder and a plugin cannot remove it, so the renamed
     * entry stays (docs/LIMITATIONS.md). Under the SPEC section 6 usability line its click must be
     * refused with a plain-words explanation naming approval — no "Oops!", stack trace or bare
     * "Access Denied" — and nothing may be queued.
     *
     * <p>A parameterised job's click opens core's parameters form, and the answer to submitting it
     * follows SPEC item 6's D-60 line: for the requester it is a 303 to the job's Request Run form
     * carrying the typed value ({@code p.<NAME>}), which names approval, pre-fills the value and is
     * not the classic build form; for a user who may not request (nobc) it is still a refusal
     * (>= 400) naming approval. Nothing is queued in either case (note 188).
     */
    private void assertDirectBuildClickIsExplained(Job<?, ?> target, String absoluteHref) throws Exception {
        String relative = absoluteHref.substring(j.getURL().toExternalForm().length());
        int nextBuildNumber = target.getNextBuildNumber();
        int builds = target.getBuilds().size();
        if (target instanceof jenkins.model.ParameterizedJobMixIn.ParameterizedJob
                && ((jenkins.model.ParameterizedJobMixIn.ParameterizedJob<?, ?>) target).isParameterized()) {
            assertParameterizedSubmissionLeadsToRequestForm(target, absoluteHref);
            PluginInteractionFixtures.assertBlocked(j, target, nextBuildNumber, builds);

            org.htmlunit.Page refused = submitParameters("nobc", absoluteHref, null, true);
            assertTrue(refused.getWebResponse().getStatusCode() >= 400, target.getFullName() + ": a user who may not"
                    + " request must still be refused, got HTTP " + refused.getWebResponse().getStatusCode());
            UsabilityFixtures.assertPlainRefusal(target.getFullName() + ": the direct build click by nobc",
                    UsabilityFixtures.text(refused), java.util.regex.Pattern.compile("(?i)approv"));
            PluginInteractionFixtures.assertBlocked(j, target, nextBuildNumber, builds);
            return;
        }
        org.htmlunit.Page answer = PluginInteractionFixtures.post(j, "u1", relative);
        assertTrue(answer.getWebResponse().getStatusCode() >= 400, target.getFullName() + ": the direct build click must be refused, got HTTP "
                + answer.getWebResponse().getStatusCode());
        UsabilityFixtures.assertPlainRefusal(target.getFullName() + ": the direct build click", UsabilityFixtures.text(answer),
                java.util.regex.Pattern.compile("(?i)approv"));
        PluginInteractionFixtures.assertBlocked(j, target, nextBuildNumber, builds);
    }

    /** D-60: the requester's submission answers 303 to the pre-filled Request Run form. */
    private void assertParameterizedSubmissionLeadsToRequestForm(Job<?, ?> target, String absoluteHref) throws Exception {
        String typed = "2026-10-31";
        org.htmlunit.Page answer = submitParameters("u1", absoluteHref, typed, false);
        assertEquals(303, answer.getWebResponse().getStatusCode(), target.getFullName() + ": the requester's parameterised"
                + " build submission must answer 303 to the Request Run form (D-60), got HTTP " + answer.getWebResponse().getStatusCode());
        String location = answer.getWebResponse().getResponseHeaderValue("Location");
        assertNotNull(location, target.getFullName() + ": the 303 must carry a Location");
        java.net.URL to = new URL(answer.getUrl(), location);
        assertEquals(new URL(j.getURL(), target.getUrl() + "batch-control/").getPath(), to.getPath(),
                target.getFullName() + ": the redirect must lead to the job's Request Run form: " + location);
        assertTrue(to.getQuery() != null && to.getQuery().contains("p.DATE=" + typed), target.getFullName()
                + ": the redirect must carry the typed value: " + location);

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("u1");
        org.htmlunit.Page landing = wc.getPage(to);
        assertEquals(200, landing.getWebResponse().getStatusCode(), target.getFullName() + ": the Request Run form must open");
        assertTrue(landing instanceof HtmlPage, target.getFullName() + ": the Request Run form must be HTML");
        HtmlPage page = (HtmlPage) landing;
        assertTrue(page.asNormalizedText().toLowerCase(java.util.Locale.ROOT).contains("approv"), target.getFullName()
                + ": the landing page must tell the user that approval is needed: " + excerpt(page.asNormalizedText()));
        assertFalse(UsabilityFixtures.formsEndingWith(page, target.getUrl() + "batch-control/submit").isEmpty(),
                target.getFullName() + ": the landing page must carry the Request Run form; forms: " + UsabilityFixtures.formActions(page));
        assertTrue(page.getForms().stream().noneMatch(f -> "parameters".equals(f.getNameAttribute())),
                target.getFullName() + ": the classic build form must not be shown");
        assertTrue(UsabilityFixtures.pageKeepsValue(page, typed), target.getFullName() + ": the typed value must be pre-filled");
    }

    /** Opens core's parameters form at {@code absoluteHref} and submits it, optionally typing into DATE. */
    private org.htmlunit.Page submitParameters(String userId, String absoluteHref, String date, boolean followRedirects)
            throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        HtmlPage formPage = (HtmlPage) wc.getPage(new URL(absoluteHref));
        org.htmlunit.html.HtmlForm form = formPage.getFormByName("parameters");
        if (date != null) {
            List<org.htmlunit.html.HtmlInput> inputs = form.getByXPath(
                    ".//*[@name='parameter'][.//input[@name='name' and @value='DATE']]//input[@name='value']");
            assertEquals(1, inputs.size(), "fixture: one value input for DATE");
            inputs.get(0).setValue(date);
        }
        wc.getOptions().setRedirectEnabled(followRedirects);
        return j.submit(form);
    }

    // ---------------------------------------------------------------- helpers

    private HtmlPage jobPage(String userId, Job<?, ?> target) throws Exception {
        HtmlPage page = j.createWebClient().withThrowExceptionOnFailingStatusCode(false)
                .login(userId).getPage(target);
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: " + userId + " must be able to open " + target.getFullName());
        return page;
    }

    private static List<HtmlAnchor> captionedEntries(HtmlPage page, String caption) {
        return page.getAnchors().stream()
                .filter(a -> a.asNormalizedText().trim().contains(caption))
                .collect(Collectors.toList());
    }

    private static List<String> hrefs(List<HtmlAnchor> anchors) {
        return anchors.stream().map(HtmlAnchor::getHrefAttribute).collect(Collectors.toList());
    }

    private static String excerpt(String text) {
        return text.length() <= 600 ? text : text.substring(0, 600) + "...";
    }
}
