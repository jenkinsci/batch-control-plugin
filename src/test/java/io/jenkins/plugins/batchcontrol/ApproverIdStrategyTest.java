package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlFormUtil;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.runRequestIds;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug hunt B, R4-01 (a) (matrix rows T-03-33, T-03-34, note 296): the approver forms compare user
 * ids with the realm's id strategy, so a job approver list that spells an approver in another letter
 * case ({@code Approver-1}) than the global list ({@code approver-1}) still offers that approver.
 *
 * <p>Basis: SPEC 3 ("the job's own approver list ({@code jobApprovers}) in force at decision time
 * applies ... User ids are compared with Jenkins' configured user id strategy, not by plain string
 * equality") and the frozen bug-hunt contract: everywhere the plugin compares user ids, the UI
 * predicates included, it uses the realm's id strategy; the Request Run form offers approver-1 and
 * submits. Reproduced on a real Jenkins: the forms said "No approvers are configured" and offered no
 * Submit, while the service accepted the same designation.
 *
 * <p>The dummy realm's user id strategy is case-insensitive (asserted as a premise).
 * {@link MockAuthorizationStrategy} matches sids literally, so both spellings of the approver hold
 * the same permissions; what is under test is the plugin's own comparison. Users: {@code u1}
 * requester, {@code approver-1} (also {@code Approver-1}) and {@code a2} approvers. Global approver
 * list [approver-1, a2]; the job's list [Approver-1].
 *
 * <p>Written from docs/SPEC.md item 3 and the bug-hunt B contract only (no src/main knowledge).
 */
@WithJenkins
public class ApproverIdStrategyTest {

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("approver-1", "Approver-1", "a2"));
        User.getById("approver-1", true).save();
        User.getById("a2", true).save();

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("approver-1", "a2"));
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        BatchControlJobProperty property = setBatchControl(job, new BatchControlJobProperty(true));
        property.setJobApprovers(Arrays.asList("Approver-1"));
        job.save();

        assertTrue(j.jenkins.getSecurityRealm().getUserIdStrategy().equals("Approver-1", "approver-1"),
                "premise: the configured user id strategy is case-insensitive");
    }

    /**
     * T-03-33 (R4-01 a): u1 opens the Request Run form of {@code batch-x} in HtmlUnit. It offers
     * approver-1 (in either spelling) and not a2 (outside the job's list); ticking it and submitting
     * creates one PENDING request designating approver-1. Guards first: the url-encoded service path
     * accepts the designation of approver-1 and approver-1 approves it (EXECUTED, one build), and the
     * form does not offer a2.
     */
    @Test
    public void t_03_33_requestRunFormOffersMixedCaseJobApprover() throws Exception {
        String serviceId = submitRunOk(j, "u1", job, "month-end batch, service path", "approver-1");
        assertSuccess(decideRun(j, "approver-1", serviceId, "approve", "checked"), "guard: approver-1 approves");
        j.waitUntilNoActivity();
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(serviceId).getStatus(),
                "guard: the service accepts approver-1 against the job list [Approver-1]");
        assertEquals(1, job.getBuilds().size(), "guard: the approval runs the build once");

        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm form = TypedParameterFixtures.requestRunForm(j, wc, job);
        List<String> offered = UsabilityFixtures.approverChoices(form);
        assertFalse(offered.contains("a2"), "guard: a2 is outside the job's approver list and must not be offered, offered " + offered);
        String choice = sameUser(offered, "approver-1");
        assertTrue(choice != null, "the Request Run form must offer approver-1, who is on the global list and (as Approver-1)"
                + " on the job's list under the case-insensitive id strategy; offered " + offered + ", page says: "
                + UsabilityFixtures.excerpt(form.asNormalizedText()));

        Set<String> before = runRequestIds();
        UsabilityFixtures.setField(form, "reason", "month-end batch through the Request Run form");
        UsabilityFixtures.tickApprovers(form, choice);
        Page answer = HtmlFormUtil.submit(form);
        PageScriptFixtures.settle(wc);
        assertTrue(answer.getWebResponse().getStatusCode() < 400, "the Request Run submission designating " + choice
                + " must be accepted, got HTTP " + answer.getWebResponse().getStatusCode()
                + ApproverFormFixtures.alerts(answer.getWebResponse().getContentAsString()));
        Set<String> after = runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "the submission must create exactly one run request, got " + after);
        RunRequest created = RunRequestService.get().load(after.iterator().next());
        assertEquals(RequestStatus.PENDING, created.getStatus());
        assertEquals(1, created.getApprovers().size(), "one approver designated, got " + created.getApprovers());
        assertTrue("approver-1".equalsIgnoreCase(created.getApprovers().get(0)),
                "the request must designate approver-1, got " + created.getApprovers());
    }

    /**
     * T-03-34 (R4-01 a): on u1's PENDING request designating approver-1 (url-encoded, accepted), the
     * change-approver form of the request page offers approver-1 (in either spelling) and not a2.
     */
    @Test
    public void t_03_34_changeApproverFormOffersMixedCaseJobApprover() throws Exception {
        String id = submitRunOk(j, "u1", job, "month-end batch", "approver-1");
        HtmlPage page = UsabilityFixtures.htmlPage(j, "u1", "batch-control/requests/" + id + "/");
        List<String> offered = new ArrayList<>();
        for (HtmlForm form : page.getForms()) {
            if (!form.getActionAttribute().contains("changeApprover")) {
                continue;
            }
            for (DomElement element : form.getElementsByTagName("select")) {
                if (element instanceof HtmlSelect && "approvers".equals(element.getAttribute("name"))) {
                    ((HtmlSelect) element).getOptions().forEach(o -> offered.add(o.getValueAttribute()));
                }
            }
            for (DomElement element : form.getElementsByTagName("input")) {
                if ("approvers".equals(element.getAttribute("name")) && "checkbox".equalsIgnoreCase(element.getAttribute("type"))) {
                    offered.add(element.getAttribute("value"));
                }
            }
        }
        assertFalse(offered.contains("a2"), "guard: a2 is outside the job's approver list and must not be offered, offered " + offered);
        assertTrue(sameUser(offered, "approver-1") != null, "the change-approver form must offer approver-1 (the job's list"
                + " names Approver-1, the same user under the case-insensitive id strategy); offered " + offered
                + ", page says: " + UsabilityFixtures.excerpt(page.asNormalizedText()));
    }

    /** The first offered id that names {@code id} under a case-insensitive comparison, or null. */
    private static String sameUser(List<String> offered, String id) {
        return offered.stream().filter(o -> o.toLowerCase(Locale.ROOT).equals(id.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);
    }
}
