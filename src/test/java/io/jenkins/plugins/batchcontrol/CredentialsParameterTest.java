package io.jenkins.plugins.batchcontrol;

import com.cloudbees.plugins.credentials.CredentialsParameterDefinition;
import com.cloudbees.plugins.credentials.CredentialsParameterValue;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebClient;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlOption;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.MASK;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.assertAbsent;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.readable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 5 (D-72: "for every parameter type ... the approved build receives exactly those
 * values, including the original value of a password or other sensitive parameter"; "a sensitive
 * value appears as {@code ********}") and item 6 (D-60: "sensitive values are never carried") for
 * the credentials plugin's {@code CredentialsParameterDefinition}, which is on the test classpath.
 * Its value (a credentials id) is sensitive ({@code CredentialsParameterValue#isSensitive()} is
 * true), so every textual form shows the mask while the approved build receives the chosen id.
 * Coverage inventory G-M1; matrix rows T-05-105, T-05-106 and T-06-104 (note 269).
 *
 * <p>Two system credentials, {@code deploy-cred} (the definition's default) and
 * {@code other-cred}; every row chooses {@code other-cred}, so a run that silently fell back to
 * the default is caught. The credentials list of the value control is filled by the credentials
 * plugin's own page script, which needs {@code Credentials/UseItem}; u1 holds it, as anyone who
 * picks credentials for a build must.
 *
 * <p>Users: {@code u1} requester (Item/Read, Item/Build, BatchControl/Request, Credentials/UseItem),
 * {@code a1} approver, {@code viewer} (ViewHistory), {@code admin}.
 *
 * <p>Written from docs/SPEC.md items 5 and 6, docs/DECISIONS.md D-60, D-66 and D-72 and the
 * credentials plugin's public API only (no src/main knowledge).
 */
@WithJenkins
public class CredentialsParameterTest {

    private static final String DEFAULT_ID = "deploy-cred";
    private static final String CHOSEN_ID = "other-cred";
    private static final String DEFAULT_PASSWORD = "cred-default-pw-Gm1a";
    private static final String CHOSEN_PASSWORD = "cred-chosen-pw-Gm1b";
    private static final String PLAIN = "cred-plain-value-Gm1";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST, CredentialsProvider.USE_ITEM)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(List.of("a1"));
        cfg.save();
        SystemCredentialsProvider.getInstance().getCredentials().add(new UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL, DEFAULT_ID, "the default deploy account", "deployer", DEFAULT_PASSWORD));
        SystemCredentialsProvider.getInstance().getCredentials().add(new UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL, CHOSEN_ID, "another account", "operator", CHOSEN_PASSWORD));
        SystemCredentialsProvider.getInstance().save();
        TypedParameterFixtures.CaptureEnv.SEEN.clear();
    }

    /**
     * T-05-105 (G-M1): u1 picks {@code other-cred} for CRED and types DATE on the Request Run page.
     * The stored display shows {@code ********} for CRED (a sensitive value) and DATE verbatim; the
     * detail page and {@code requests.csv} show neither the chosen id as CRED's value nor either
     * password. Once a1 approves, the build receives {@code other-cred} (a sensitive credentials
     * value; the build's CRED variable is the id), and the dashboard and {@code runs.csv} show the
     * mask, never the id or a password. Guard: the plain value is shown everywhere.
     */
    @Test
    public void t_05_105_credentialsParameterFromTheRequestPageReachesTheBuildMasked() throws Exception {
        FreeStyleProject job = credentialsJob("cred-page");
        Set<String> before = ApproverFormFixtures.runRequestIds();
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm form = TypedParameterFixtures.requestRunForm(j, wc, job);
        waitForCredentialsListAndChoose(form, CHOSEN_ID);
        TypedParameterFixtures.setValue(form, "DATE", PLAIN);
        assertCredentialsStillSelected(form, CHOSEN_ID);
        Page answer = TypedParameterFixtures.submit(wc, form, "month-end batch with credentials", "a1");
        assertTrue(answer.getWebResponse().getStatusCode() < 400, "the Request Run submission must succeed, got HTTP "
                + answer.getWebResponse().getStatusCode());
        String id = onlyNewRequest(before);

        assertMaskedRequest(id);
        assertBuildReceivesTheChosenCredentials(job, id);
    }

    /**
     * T-05-106 (G-M1): the same choice made in the request dialog (the fragment inserted into the
     * job page with its scripts, {@link DialogFixtures}): the request shows {@code ********} for
     * CRED, and the approved build receives {@code other-cred}.
     */
    @Test
    public void t_05_106_credentialsParameterFromTheDialogReachesTheBuildMasked() throws Exception {
        FreeStyleProject job = credentialsJob("cred-dialog");
        String id = DialogFixtures.submitThroughDialog(j, "u1", job, form -> {
            waitForCredentialsListAndChoose(form, CHOSEN_ID);
            TypedParameterFixtures.setValue(form, "DATE", PLAIN);
            assertCredentialsStillSelected(form, CHOSEN_ID);
        });

        assertMaskedRequest(id);
        assertBuildReceivesTheChosenCredentials(job, id);
    }

    /**
     * T-06-104 (G-M1, D-60): u1 picks {@code other-cred} and types DATE in core's parameters form
     * of an approval-required job: the answer is a 303 to the job's Request Run form carrying
     * {@code p.DATE} and no CRED value, the chosen id nowhere in the Location; nothing is queued or
     * stored. Following it shows DATE filled in and nothing of the chosen id in the page.
     */
    @Test
    public void t_06_104_d60NeverCarriesTheCredentialsValue() throws Exception {
        FreeStyleProject job = credentialsJob("cred-d60");
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        BatchControlFixtures.activate(job);
        Set<String> before = ApproverFormFixtures.runRequestIds();

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("u1");
        HtmlPage formPage = (HtmlPage) wc.getPage(new WebRequest(new URL(j.getURL(), job.getUrl() + "build?delay=0sec"), HttpMethod.GET));
        HtmlForm form = formPage.getFormByName("parameters");
        waitForCredentialsListAndChoose(form, CHOSEN_ID);
        TypedParameterFixtures.setValue(form, "DATE", PLAIN);
        assertCredentialsStillSelected(form, CHOSEN_ID);
        wc.getOptions().setRedirectEnabled(false);
        Page answer = j.submit(form);

        assertEquals(303, answer.getWebResponse().getStatusCode(), "a requester's refused build must answer 303 (D-60)");
        String location = answer.getWebResponse().getResponseHeaderValue("Location");
        assertNotNull(location, "the 303 must carry a Location");
        URL target = new URL(answer.getUrl(), location);
        assertEquals(new URL(j.getURL(), job.getUrl() + "batch-control/").getPath(), target.getPath(),
                "the refusal must lead to the job's Request Run form");
        Map<String, String> query = TypedParameterFixtures.query(target);
        assertEquals(PLAIN, query.get("p.DATE"), "the plain value must be carried: " + location);
        assertFalse(query.containsKey("p.CRED"), "a sensitive credentials value must not be carried: " + location);
        assertFalse(location.contains(CHOSEN_ID), "the chosen credentials id must not appear in the Location: " + location);
        assertNothingQueuedOrStored(job, before);

        Page followed = UsabilityFixtures.clientNoJs(j, "u1").getPage(target);
        assertEquals(200, followed.getWebResponse().getStatusCode(), "the prefilled Request Run form must open");
        HtmlForm request = UsabilityFixtures.formsEndingWith((HtmlPage) followed, job.getUrl() + "batch-control/submit").get(0);
        assertEquals(PLAIN, TypedParameterFixtures.valueOf(request, "DATE"), "guard: DATE must be filled in");
        assertFalse(followed.getWebResponse().getContentAsString().contains(CHOSEN_ID),
                "the prefilled form must not carry the chosen credentials id anywhere");
        assertNothingQueuedOrStored(job, before);
    }

    // ---------------------------------------------------------------- helpers

    private FreeStyleProject credentialsJob(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        job.addProperty(new ParametersDefinitionProperty(
                new CredentialsParameterDefinition("CRED", "the deploy account", DEFAULT_ID, StandardCredentials.class.getName(), true),
                new StringParameterDefinition("DATE", "2000-01-01", "the batch date")));
        job.getBuildersList().add(new TypedParameterFixtures.CaptureEnv(false, "CRED", "DATE"));
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    /** The credentials list of CRED's parameter block. */
    private static HtmlSelect credentialsSelect(HtmlForm form) {
        List<HtmlSelect> selects = TypedParameterFixtures.parameterBlock(form, "CRED").getByXPath(".//select");
        assertEquals(1, selects.size(), "fixture: CRED must offer one credentials list: "
                + UsabilityFixtures.excerpt(TypedParameterFixtures.parameterBlock(form, "CRED").asXml()));
        return selects.get(0);
    }

    /** Selects {@code id} in CRED's credentials list (filled by the credentials plugin's page script). */
    private static void chooseCredentials(HtmlForm form, String id) {
        HtmlSelect select = credentialsSelect(form);
        assertTrue(select.getOptions().stream().map(HtmlOption::getValueAttribute).anyMatch(id::equals),
                "fixture: the credentials list must offer " + id + ": " + UsabilityFixtures.excerpt(select.asXml()));
        select.setSelectedAttribute(id, true);
        assertEquals(id, select.getSelectedOptions().get(0).getValueAttribute(), "fixture: " + id + " must be selected");
    }

    /**
     * Waits until the CRED list is filled and offers {@code id} (up to 30 s), picks it, and
     * re-checks the pick after letting the page settle: the list is filled asynchronously
     * by the credentials plugin's page script, and a blind wait can return while the fill
     * is still pending (issue #173, one Windows failure out of 1585 tests). Waiting for no
     * background JavaScript at all cannot work here — these pages always keep at least one
     * timer scheduled — so the wait is on the condition the test actually needs. A refill
     * after the pick would silently restore the parameter's default, so the pick is checked
     * again after the settle and immediately before the submit. Fails as a fixture error
     * when the list never offers the id.
     */
    private static void waitForCredentialsListAndChoose(HtmlForm form, String id) {
        WebClient wc = ((HtmlPage) form.getPage()).getWebClient();
        long deadline = System.currentTimeMillis() + 30_000;
        boolean offered = credentialsSelect(form).getOptions().stream()
                .map(HtmlOption::getValueAttribute).anyMatch(id::equals);
        while (!offered && System.currentTimeMillis() < deadline) {
            wc.waitForBackgroundJavaScript(1000);
            offered = credentialsSelect(form).getOptions().stream()
                    .map(HtmlOption::getValueAttribute).anyMatch(id::equals);
        }
        assertTrue(offered, "fixture: the credentials list must offer " + id + " within 30 s: "
                + UsabilityFixtures.excerpt(credentialsSelect(form).asXml()));
        chooseCredentials(form, id);
        wc.waitForBackgroundJavaScript(1000);
        assertCredentialsStillSelected(form, id);
    }

    /**
     * The pick still holds immediately before the submit: a refill of the credentials list
     * after the pick would silently restore the parameter's default (issue #173).
     */
    private static void assertCredentialsStillSelected(HtmlForm form, String id) {
        assertEquals(id, credentialsSelect(form).getSelectedOptions().get(0).getValueAttribute(),
                "fixture: " + id + " must still be selected at submit time, not the default");
    }

    private static String onlyNewRequest(Set<String> before) {
        Set<String> after = ApproverFormFixtures.runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "exactly one run request must be created, got " + after);
        return after.iterator().next();
    }

    private void assertMaskedRequest(String id) throws Exception {
        Map<String, String> shown = RunRequestService.get().load(id).getParameters();
        assertEquals(MASK, shown.get("CRED"), "a credentials value is sensitive and is shown as " + MASK);
        assertEquals(PLAIN, shown.get("DATE"));
        List<String> forbidden = List.of(CHOSEN_ID, CHOSEN_PASSWORD, DEFAULT_PASSWORD);
        String detail = readable(j, "a1", "batch-control/requests/" + id + "/");
        assertTrue(detail.contains(MASK) && detail.contains(PLAIN), "guard: the detail page shows the mask and the plain value");
        assertAbsent("the request detail", detail, forbidden);
        String requestsCsv = readable(j, "viewer", "batch-control/history/requests.csv");
        assertTrue(requestsCsv.contains(PLAIN), "guard: requests.csv lists the request");
        assertAbsent("requests.csv", requestsCsv, forbidden);
    }

    private void assertBuildReceivesTheChosenCredentials(FreeStyleProject job, String id) throws Exception {
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "nothing may run before the approval");
        DialogTypedParameterTest.approve(id);
        j.waitUntilNoActivity();
        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build, "the approved request must have run as #1");
        j.assertBuildStatusSuccess(build);
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(id).getStatus());
        ParameterValue cred = build.getAction(ParametersAction.class).getParameter("CRED");
        assertTrue(cred instanceof CredentialsParameterValue, "the build must receive a credentials value, got " + cred);
        assertEquals(CHOSEN_ID, ((CredentialsParameterValue) cred).getValue(), "the build must receive the chosen credentials id");
        assertTrue(cred.isSensitive(), "the credentials value stays sensitive in the build");
        assertEquals(CHOSEN_ID, TypedParameterFixtures.CaptureEnv.seen(job.getFullName(), 1, "CRED"),
                "the build's CRED variable must name the chosen credentials, not the default");
        assertEquals(PLAIN, TypedParameterFixtures.CaptureEnv.seen(job.getFullName(), 1, "DATE"));

        List<String> forbidden = List.of(CHOSEN_ID, CHOSEN_PASSWORD, DEFAULT_PASSWORD);
        for (String surface : new String[] {"batch-control/dashboard/", "batch-control/history/runs.csv"}) {
            String body = readable(j, "viewer", surface);
            assertTrue(body.contains(PLAIN), "guard: " + surface + " lists the run with its plain value");
            assertAbsent(surface, body, forbidden);
        }
    }

    private void assertNothingQueuedOrStored(FreeStyleProject job, Set<String> before) throws Exception {
        j.waitUntilNoActivity();
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "no run request may be stored before the form is submitted");
        assertTrue(j.jenkins.getQueue().isEmpty(), "the queue must be empty");
        assertEquals(1, job.getNextBuildNumber(), "no build number may have been consumed");
    }
}
