package io.jenkins.plugins.batchcontrol;

import hudson.model.FileParameterDefinition;
import hudson.model.FileParameterValue;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.User;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.Page;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlFormUtil;
import org.htmlunit.util.KeyDataPair;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.changeGrantApprovers;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.changeRunApprovers;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.get;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.grantRequestIds;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.runRequestIds;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunMultipartOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 3, D-37: a request designates one or more approvers; any one of them decides,
 * the first decision closes the request and names the decider ({@code decidedBy}); a
 * designation change edits the set and is recorded as (previous set, new set, by, at).
 * Matrix rows T-03-07 .. T-03-20. T-03-21 (the legacy single-{@code approver} load) is
 * withdrawn by D-69: there is no load-time conversion before the first release.
 *
 * <p>T-03-22 .. T-03-26 (D-37, D-26, P-09; matrix note 59) extend the requester-driven
 * designation change to grant requests through {@code POST batch-control/grants/<id>/changeApprover}:
 * the requester may edit a still-PENDING grant request's set; a holder who cannot see the
 * request at all is refused with 404 (P-09), while a designated approver who can see it but is
 * not the requester is refused with 403; the endpoint refuses GET (405); and a decided request
 * may no longer be changed.
 *
 * <p>T-03-28 .. T-03-31 (D-37, D-72; matrix note 289) repeat T-03-07 in the encoding the real
 * Request Run form uses. That form posts {@code multipart/form-data} (D-72 lets it upload file
 * parameters), and T-03-07's url-encoded POST did not cover that body: a multipart {@code submit}
 * shaped as the form posts it, raw parts plus core's {@code json} field (T-03-28 without
 * parameters, T-03-29 with a core file parameter), and the job's
 * {@code batch-control/} page driven in a browser with two approvers ticked (T-03-30 without
 * parameters, T-03-31 with a file chosen) must store the whole set, in submission (page) order.
 *
 * <p>T-03-32 (D-37, D-72; matrix note 290) repeats those paths and T-03-07 with a non-ASCII user
 * id ({@code 山田}, as LDAP, SAML or OIDC realms may hand out): the stored set is exactly the
 * designated ids, each once and intact, whichever position the non-ASCII id has.
 *
 * <p>Every creation, decision and designation change goes through the frozen HTTP form
 * contract (field {@code approvers}, one user id per value; see {@link ApproverFormFixtures}).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-37 and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class MultiApproverTest {

    private JenkinsRule j;
    private FreeStyleProject job;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(authorization(true));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2", "a3", "admin"));
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    private static MockAuthorizationStrategy authorization(boolean a1HoldsApprove) {
        MockAuthorizationStrategy strategy = new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1", "u2")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a2", "a3");
        if (a1HoldsApprove) {
            strategy.grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1");
        } else {
            strategy.grant(Jenkins.READ, Item.READ).everywhere().to("a1");
        }
        return strategy;
    }

    /**
     * T-03-07: a submission with approvers=[a1, a2] stores the whole set, in submission order,
     * with no decider yet. (The compatibility {@code approver} view was dropped from SPEC by D-69.)
     */
    @Test
    public void t_03_07_severalApproversAreStored() throws Exception {
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1", "a2");

        RunRequest request = RunRequestService.get().load(id);
        assertEquals(RequestStatus.PENDING, request.getStatus());
        assertEquals(Arrays.asList("a1", "a2"), request.getApprovers(), "the designated set must be stored as submitted");
        assertNull(request.getDecidedBy(), "nobody has decided a PENDING request");
    }

    /** T-03-08: a submission without any approver is refused; SPEC 3 requires one or more. */
    @Test
    public void t_03_08_emptyApproverSetIsRefused() throws Exception {
        Set<String> before = runRequestIds();
        assertClientError(submitRun(j, "u1", job, "month-end batch"), "a submission with no approvers value");
        assertClientError(submitRun(j, "u1", job, "month-end batch", ""), "a submission whose only approvers value is empty");
        assertEquals(before, runRequestIds(), "no request may be stored after a refused submission");
    }

    /**
     * T-03-09: either member of the set may approve. The second member a2 approves, the build
     * runs exactly once and the record names a2 as the decider.
     */
    @Test
    @Tag("core")
    public void t_03_09_anyDesignatedApproverMayApproveAndIsNamed() throws Exception {
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1", "a2");

        assertSuccess(decideRun(j, "a2", id, "approve", "checked"), "approval by the second member a2");
        j.waitUntilNoActivity();

        RunRequest reloaded = RunRequestService.get().load(id);
        assertEquals(RequestStatus.EXECUTED, reloaded.getStatus());
        assertEquals("a2", reloaded.getDecidedBy(), "the record must name the approver who decided");
        assertEquals(1, job.getBuilds().size(), "the approval must run the build exactly once");
        FreeStyleBuild build = job.getBuildByNumber(1);
        ApprovedCause cause = build.getCause(ApprovedCause.class);
        assertNotNull(cause, "the build must carry the ApprovedCause");
        assertEquals(id, cause.getRequestId());
        assertEquals("a2", cause.getApprover(), "the cause must name the approver who actually approved");
    }

    /**
     * T-03-10: the first decision closes the request. After a1 approves, a2 can neither approve
     * nor reject; the decider stays a1 and the job ran exactly once.
     */
    @Test
    @Tag("core")
    public void t_03_10_secondDecisionAfterApprovalIsRefused() throws Exception {
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1", "a2");
        assertSuccess(decideRun(j, "a1", id, "approve", "ok"), "the first approval by a1");
        j.waitUntilNoActivity();

        assertClientError(decideRun(j, "a2", id, "approve", "me too"), "a second approval by a2");
        assertClientError(decideRun(j, "a2", id, "reject", "changed my mind"), "a rejection by a2 after a1's approval");
        j.waitUntilNoActivity();

        RunRequest reloaded = RunRequestService.get().load(id);
        assertEquals(RequestStatus.EXECUTED, reloaded.getStatus(), "the closed request must not change state");
        assertEquals("a1", reloaded.getDecidedBy(), "the decider must stay the first approver");
        assertEquals(1, job.getBuilds().size(), "a refused second decision must not run the job again");
        assertEquals(2, job.getNextBuildNumber());
    }

    /**
     * T-03-11: a rejection by one member closes the request too. a2's later approval is
     * refused and nothing is ever built (queue empty, next build number unchanged, no build).
     */
    @Test
    @Tag("core")
    public void t_03_11_firstRejectionClosesTheRequest() throws Exception {
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1", "a2");
        assertSuccess(decideRun(j, "a1", id, "reject", "wrong date"), "the rejection by a1");

        assertClientError(decideRun(j, "a2", id, "approve", "looks fine to me"), "an approval by a2 after a1's rejection");

        RunRequest reloaded = RunRequestService.get().load(id);
        assertEquals(RequestStatus.REJECTED, reloaded.getStatus());
        assertEquals("a1", reloaded.getDecidedBy());
        assertTrue(j.jenkins.getQueue().isEmpty(), "nothing may be queued");
        assertEquals(1, job.getNextBuildNumber(), "the next build number must be unchanged");
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "no build may exist");
    }

    /** T-03-12: a non-admin requester may not be a member of the set, even among others. */
    @Test
    @Tag("core")
    public void t_03_12_requesterInsideTheSetIsRefused() throws Exception {
        // u1 is on the global list, so the only reason to refuse is self-designation
        cfg.setApprovers(Arrays.asList("a1", "a2", "a3", "admin", "u1"));
        cfg.save();
        Set<String> before = runRequestIds();

        assertClientError(submitRun(j, "u1", job, "month-end batch", "a1", "u1"), "a set containing the requester u1");
        assertClientError(submitRun(j, "u1", job, "month-end batch", "u1", "a1"), "a set whose first member is the requester u1");
        assertEquals(before, runRequestIds(), "no request may be stored");
    }

    /**
     * T-03-13: the administrator exception still holds for a set (allowAdminSelfApproval=true by
     * default): admin may be a member of the set of their own request, and may decide it.
     */
    @Test
    public void t_03_13_adminMayBeInTheSetOfTheirOwnRequest() throws Exception {
        String id = submitRunOk(j, "admin", job, "urgent hotfix batch", "a1", "admin");
        assertEquals(Arrays.asList("a1", "admin"), RunRequestService.get().load(id).getApprovers());

        assertSuccess(decideRun(j, "admin", id, "approve", "self approving as admin"), "admin's self-approval");
        j.waitUntilNoActivity();
        RunRequest reloaded = RunRequestService.get().load(id);
        assertEquals("admin", reloaded.getDecidedBy());
        assertTrue(reloaded.isSelfApproved(), "the self-approval must still be recorded (SPEC 2)");
        assertEquals(1, job.getBuilds().size());
    }

    /** T-03-14: every member must be on the approver list; one ineligible member refuses the whole set. */
    @Test
    @Tag("core")
    public void t_03_14_everyMemberMustBeOnTheApproverList() throws Exception {
        Set<String> before = runRequestIds();
        assertClientError(submitRun(j, "u1", job, "month-end batch", "a1", "x9"), "a set with the unlisted user x9");
        assertEquals(before, runRequestIds(), "no request may be stored");

        // the same set without the ineligible member is accepted (fixture control)
        submitRunOk(j, "u1", job, "month-end batch", "a1", "a2");
    }

    /**
     * T-03-15: D-29 applies to the set. A listed approver outside the set (a3) and an
     * administrator cannot decide; the request stays PENDING and nothing is built.
     */
    @Test
    @Tag("core")
    public void t_03_15_approverOutsideTheSetCannotDecide() throws Exception {
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1", "a2");

        assertClientError(decideRun(j, "a3", id, "approve", "stepping in"), "approval by the listed approver a3 outside the set");
        assertClientError(decideRun(j, "a3", id, "reject", "stepping in"), "rejection by the listed approver a3 outside the set");
        assertClientError(decideRun(j, "admin", id, "approve", "admin override"), "approval by an administrator outside the set");

        RunRequest reloaded = RunRequestService.get().load(id);
        assertEquals(RequestStatus.PENDING, reloaded.getStatus());
        assertNull(reloaded.getDecidedBy());
        assertEquals(Arrays.asList("a1", "a2"), reloaded.getApprovers(), "the set must be unchanged");
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(1, job.getNextBuildNumber());
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty());
    }

    /**
     * T-03-16: the decision-time Approve check applies member by member: a1 lost Approve after
     * the request was filed and is refused, while a2 in the same set still decides.
     */
    @Test
    public void t_03_16_memberWhoLostApproveIsRefusedOtherMemberDecides() throws Exception {
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1", "a2");
        j.jenkins.setAuthorizationStrategy(authorization(false));

        assertClientError(decideRun(j, "a1", id, "approve", "trying anyway"), "approval by a1 without Approve");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus());

        assertSuccess(decideRun(j, "a2", id, "approve", "ok"), "approval by a2");
        j.waitUntilNoActivity();
        assertEquals("a2", RunRequestService.get().load(id).getDecidedBy());
        assertEquals(1, job.getBuilds().size());
    }

    /**
     * T-03-17: the requester edits the set before a decision. The request now holds the new
     * set, one change is recorded with both whole sets, who changed it and when; a member
     * who was removed can no longer decide and a member who was added can.
     */
    @Test
    @Tag("core")
    public void t_03_17_changeApproverEditsTheSetAndRecordsBothSets() throws Exception {
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1", "a2");

        assertSuccess(changeRunApprovers(j, "u1", id, "a2", "a3"), "the requester's designation change");

        RunRequest reloaded = RunRequestService.get().load(id);
        assertEquals(Arrays.asList("a2", "a3"), reloaded.getApprovers());
        List<RunRequest.ApproverChange> changes = reloaded.getApproverChanges();
        assertEquals(1, changes.size(), "exactly one designation change must be recorded");
        assertEquals(Arrays.asList("a1", "a2"), changes.get(0).getFromApprovers(), "the previous set");
        assertEquals(Arrays.asList("a2", "a3"), changes.get(0).getToApprovers(), "the new set");
        assertEquals("u1", changes.get(0).getBy());
        assertNotNull(changes.get(0).getAt());

        assertClientError(decideRun(j, "a1", id, "approve", "stale"), "approval by the removed member a1");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus());

        assertSuccess(decideRun(j, "a3", id, "approve", "ok"), "approval by the added member a3");
        j.waitUntilNoActivity();
        assertEquals("a3", RunRequestService.get().load(id).getDecidedBy());
        assertEquals(1, job.getBuilds().size());
    }

    /** T-03-18: a change to a set that contains the requester (or an unlisted user) is refused and records nothing. */
    @Test
    public void t_03_18_changeToAnIneligibleSetIsRefused() throws Exception {
        cfg.setApprovers(Arrays.asList("a1", "a2", "a3", "admin", "u1"));
        cfg.save();
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1", "a2");

        assertClientError(changeRunApprovers(j, "u1", id, "a2", "u1"), "a change that puts the requester into the set");
        assertClientError(changeRunApprovers(j, "u1", id, "a2", "x9"), "a change that puts the unlisted user x9 into the set");
        assertClientError(changeRunApprovers(j, "u1", id), "a change to an empty set");

        RunRequest reloaded = RunRequestService.get().load(id);
        assertEquals(Arrays.asList("a1", "a2"), reloaded.getApprovers(), "the set must be unchanged");
        assertTrue(reloaded.getApproverChanges() == null || reloaded.getApproverChanges().isEmpty(), "a refused change must not be recorded");
    }

    /** T-03-19: only the requester changes the set, and only before a decision. */
    @Test
    @Tag("core")
    public void t_03_19_changeByAnotherUserOrAfterDecisionIsRefused() throws Exception {
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1", "a2");

        assertClientError(changeRunApprovers(j, "u2", id, "a3"), "a change by u2, who is not the requester");
        assertClientError(changeRunApprovers(j, "a1", id, "a3"), "a change by the member a1");
        assertEquals(Arrays.asList("a1", "a2"), RunRequestService.get().load(id).getApprovers());

        assertSuccess(decideRun(j, "a1", id, "approve", "ok"), "approval by a1");
        j.waitUntilNoActivity();
        assertClientError(changeRunApprovers(j, "u1", id, "a3"), "a change after the decision");

        RunRequest reloaded = RunRequestService.get().load(id);
        assertEquals(Arrays.asList("a1", "a2"), reloaded.getApprovers());
        assertTrue(reloaded.getApproverChanges() == null || reloaded.getApproverChanges().isEmpty());
    }

    /**
     * T-03-20: change (grant) requests take a set too. Either member decides, the decider is
     * named, the grant is effective once, and the other member's later decision is refused.
     */
    @Test
    public void t_03_20_grantRequestWithSeveralApprovers() throws Exception {
        cfg.setChangeControlEnabled(true);
        cfg.save();
        String id = submitGrantOk(j, "u1", "batch-x", Arrays.asList("CONFIGURE"), 30,
                "fix the cron expression", null, "a1", "a2");
        GrantRequest stored = GrantRequestService.get().load(id);
        assertEquals(Arrays.asList("a1", "a2"), stored.getApprovers());
        assertNull(stored.getDecidedBy());

        assertClientError(decideGrant(j, "a3", id, "approve", "stepping in"), "approval by a3 outside the set");
        assertFalse(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE));

        assertSuccess(decideGrant(j, "a2", id, "approve", "ok"), "approval by the member a2");
        GrantRequest decided = GrantRequestService.get().load(id);
        assertEquals(RequestStatus.APPROVED, decided.getStatus());
        assertEquals("a2", decided.getDecidedBy());
        assertTrue(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE));

        assertClientError(decideGrant(j, "a1", id, "approve", "me too"), "a second approval by a1");
        assertClientError(decideGrant(j, "a1", id, "reject", "no"), "a rejection by a1 after a2's approval");
        assertEquals(1, GrantService.get().listActive().stream()
                .filter(g -> "u1".equals(g.getUser())).count(), "the refused second approval must not create another grant");
        assertEquals("a2", GrantRequestService.get().load(id).getDecidedBy());

        // a set containing the requester is refused for grant requests as well
        cfg.setApprovers(Arrays.asList("a1", "a2", "a3", "admin", "u1"));
        cfg.save();
        Set<String> before = grantRequestIds();
        assertClientError(submitGrant(j, "u1", "batch-x", Arrays.asList("CONFIGURE"), 30,
                "again", null, "a1", "u1"), "a grant request whose set contains the requester");
        assertEquals(before, grantRequestIds());
    }

    /**
     * T-03-22: the requester may edit a still-PENDING grant request's designated approvers too
     * (D-26, D-37 applied to GrantRequest). The new set replaces the old one and exactly one
     * change is recorded with both whole sets, who changed it and when.
     */
    @Test
    public void t_03_22_grantChangeApproverByRequesterEditsTheSetAndRecords() throws Exception {
        cfg.setChangeControlEnabled(true);
        cfg.save();
        String id = submitGrantOk(j, "u1", "batch-x", Arrays.asList("CONFIGURE"), 30,
                "fix the cron expression", null, "a1", "a2");

        assertSuccess(changeGrantApprovers(j, "u1", id, "a2", "a3"), "the requester's grant designation change");

        GrantRequest reloaded = GrantRequestService.get().load(id);
        assertEquals(Arrays.asList("a2", "a3"), reloaded.getApprovers(), "the new set must replace the old one");
        List<GrantRequest.ApproverChange> changes = reloaded.getApproverChanges();
        assertEquals(1, changes.size(), "exactly one designation change must be recorded");
        assertEquals(Arrays.asList("a1", "a2"), changes.get(0).getFromApprovers(), "the previous set");
        assertEquals(Arrays.asList("a2", "a3"), changes.get(0).getToApprovers(), "the new set");
        assertEquals("u1", changes.get(0).getBy());
        assertNotNull(changes.get(0).getAt());
    }

    /**
     * T-03-23 (P-09): u2 holds {@code BatchControl/RequestGrant} but is neither this request's
     * requester, a designated approver, nor a {@code Manage} holder, so the request is not
     * visible to u2 at all (DECISIONS P-09): the action answers 404, like any other URL beneath
     * a request u2 cannot see, and the set and the (empty) change history are unchanged.
     */
    @Test
    public void t_03_23_grantChangeApproverByNonVisibleHolderIsNotFound() throws Exception {
        cfg.setChangeControlEnabled(true);
        cfg.save();
        String id = submitGrantOk(j, "u1", "batch-x", Arrays.asList("CONFIGURE"), 30,
                "fix the cron expression", null, "a1", "a2");

        assertEquals(404, changeGrantApprovers(j, "u2", id, "a3").getStatusCode(),
                "u2 cannot see this request at all (P-09), so its action URL must answer 404");

        GrantRequest reloaded = GrantRequestService.get().load(id);
        assertEquals(Arrays.asList("a1", "a2"), reloaded.getApprovers(), "the set must be unchanged");
        assertTrue(reloaded.getApproverChanges() == null || reloaded.getApproverChanges().isEmpty(),
                "a refused change must not be recorded");
    }

    /**
     * T-03-26 (P-09 pairing): a1 is a designated approver of this request, so P-09 makes it
     * visible to a1 (unlike u2 in T-03-23) — but a1 still is not its requester, so the
     * ownership check on top of visibility refuses with 403, not 404.
     */
    @Test
    @Tag("core")
    public void t_03_26_grantChangeApproverByDesignatedApproverIsForbidden() throws Exception {
        cfg.setChangeControlEnabled(true);
        cfg.save();
        String id = submitGrantOk(j, "u1", "batch-x", Arrays.asList("CONFIGURE"), 30,
                "fix the cron expression", null, "a1", "a2");

        assertEquals(403, changeGrantApprovers(j, "a1", id, "a3").getStatusCode(),
                "a1 can see this request (a designated approver) but is not its requester");

        GrantRequest reloaded = GrantRequestService.get().load(id);
        assertEquals(Arrays.asList("a1", "a2"), reloaded.getApprovers(), "the set must be unchanged");
        assertTrue(reloaded.getApproverChanges() == null || reloaded.getApproverChanges().isEmpty(),
                "a forbidden change must not be recorded");
    }

    /**
     * T-03-24: a state change must never happen on GET; the endpoint answers 405 and the
     * designation and its change history are unaffected.
     */
    @Test
    @Tag("core")
    public void t_03_24_grantChangeApproverByGetIsRefused() throws Exception {
        cfg.setChangeControlEnabled(true);
        cfg.save();
        String id = submitGrantOk(j, "u1", "batch-x", Arrays.asList("CONFIGURE"), 30,
                "fix the cron expression", null, "a1", "a2");

        WebResponse response = get(j, "u1", "batch-control/grants/" + id + "/changeApprover?approvers=a3");
        assertEquals(405, response.getStatusCode(), "GET must never change the designation");

        GrantRequest reloaded = GrantRequestService.get().load(id);
        assertEquals(Arrays.asList("a1", "a2"), reloaded.getApprovers(), "the set must be unchanged");
        assertTrue(reloaded.getApproverChanges() == null || reloaded.getApproverChanges().isEmpty(),
                "a refused GET must not be recorded");
    }

    /**
     * T-03-25: once a grant request has been decided, the designation may no longer be changed,
     * even by the requester.
     */
    @Test
    public void t_03_25_grantChangeApproverAfterDecisionIsRefused() throws Exception {
        cfg.setChangeControlEnabled(true);
        cfg.save();
        String id = submitGrantOk(j, "u1", "batch-x", Arrays.asList("CONFIGURE"), 30,
                "fix the cron expression", null, "a1", "a2");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: the decision that closes the request");

        assertClientError(changeGrantApprovers(j, "u1", id, "a3"), "a change attempted after the decision");

        GrantRequest reloaded = GrantRequestService.get().load(id);
        assertEquals(RequestStatus.APPROVED, reloaded.getStatus(), "fixture: the decision must have gone through");
        assertEquals("a1", reloaded.getDecidedBy());
        assertEquals(Arrays.asList("a1", "a2"), reloaded.getApprovers(), "the set must be unchanged after the decision");
        assertTrue(reloaded.getApproverChanges() == null || reloaded.getApproverChanges().isEmpty(),
                "a change after the decision must not be recorded");
    }

    // ------------------------------------------------------------------ multipart (note 289)

    /**
     * T-03-28: a {@code multipart/form-data} POST to {@code submit} shaped as the Request Run form
     * posts it (D-72): the raw parts {@code approvers}=a1 and {@code approvers}=a2 plus core's
     * {@code json} field with {@code "approvers": ["a1","a2"]}. It stores both, in submission
     * order, exactly as the url-encoded POST of T-03-07 does. A second submission in the other
     * order ([a3, a1]) keeps that order. Guards: a3, who was not sent, cannot decide the first
     * request; a1, the first value of the repeated field, approves it (EXECUTED, decidedBy a1,
     * one build). (A multipart body without the {@code json} field keeps only the last repeated
     * value, a documented limitation that no row asserts; note 289.)
     */
    @Test
    public void t_03_28_multipartSubmitStoresEveryApprover() throws Exception {
        String id = submitRunMultipartOk(j, "u1", job, "month-end batch", List.of(), null, "a1", "a2");

        RunRequest request = RunRequestService.get().load(id);
        assertEquals(RequestStatus.PENDING, request.getStatus());
        assertEquals(Arrays.asList("a1", "a2"), request.getApprovers(),
                "a multipart submission must store every approvers value, in submission order (as T-03-07 url-encoded)");
        assertNull(request.getDecidedBy(), "nobody has decided a PENDING request");

        String reversed = submitRunMultipartOk(j, "u1", job, "month-end batch, second run", List.of(), null, "a3", "a1");
        assertEquals(Arrays.asList("a3", "a1"), RunRequestService.get().load(reversed).getApprovers(),
                "the multipart set must keep the submission order, not sort it");

        assertClientError(decideRun(j, "a3", id, "approve", "not designated"), "approval by a3, who was not sent");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus());
        assertSuccess(decideRun(j, "a1", id, "approve", "checked"), "approval by a1, the first approvers value");
        j.waitUntilNoActivity();
        RunRequest decided = RunRequestService.get().load(id);
        assertEquals(RequestStatus.EXECUTED, decided.getStatus());
        assertEquals("a1", decided.getDecidedBy());
        assertEquals(1, job.getBuilds().size(), "the approval must run the build exactly once");
    }

    /**
     * T-03-29: the same multipart POST, as the Request Run form posts it, to a job with a core file
     * parameter UPLOAD (and a string DATE): core's parameter parts ({@code name}=UPLOAD, the file
     * renamed {@code file0}, {@code name}=DATE, {@code value}) and the {@code json} field naming
     * them ({@code "parameter": [{"name":"UPLOAD","file":"file0"}, {"name":"DATE","value":...}]})
     * next to the repeated {@code approvers} parts and {@code "approvers": ["a1","a2"]}. It stores
     * [a1, a2]. Guards: a3 cannot decide; a2 approves, the request is EXECUTED with decidedBy a2
     * and the one build received the uploaded file.
     */
    @Test
    public void t_03_29_multipartSubmitWithFileParameterStoresEveryApprover() throws Exception {
        FreeStyleProject fileJob = fileJob("batch-file");
        List<NameValuePair> parts = new ArrayList<>();
        parts.add(new NameValuePair("name", "UPLOAD"));
        parts.add(new KeyDataPair("file0", TypedParameterFixtures.uploadFile("data.csv",
                TypedParameterFixtures.payload("multi-approver-multipart-Qm28", 2048)), "data.csv",
                "application/octet-stream", StandardCharsets.UTF_8));
        parts.add(new NameValuePair("name", "DATE"));
        parts.add(new NameValuePair("value", "2026-10-01"));
        JSONArray parameters = new JSONArray();
        parameters.add(new JSONObject().element("name", "UPLOAD").element("file", "file0"));
        parameters.add(new JSONObject().element("name", "DATE").element("value", "2026-10-01"));

        String id = submitRunMultipartOk(j, "u1", fileJob, "month-end batch with a file", parts, parameters, "a1", "a2");

        RunRequest request = RunRequestService.get().load(id);
        assertEquals(RequestStatus.PENDING, request.getStatus());
        assertEquals(Arrays.asList("a1", "a2"), request.getApprovers(),
                "a multipart submission with a file part must store every approvers value, in submission order");

        assertClientError(decideRun(j, "a3", id, "approve", "not designated"), "approval by a3, who was not sent");
        assertSuccess(decideRun(j, "a2", id, "approve", "checked"), "approval by the second member a2");
        j.waitUntilNoActivity();
        assertDecidedAndRanWithFile(id, fileJob, "a2");
    }

    /**
     * T-03-30: the real Request Run form ({@code job/<name>/batch-control/}) in a browser. Guard
     * first: ticking only a2 stores [a2], and a1 cannot decide that request. Then ticking a1 and
     * a2 stores both, in the order the page lists them; the member listed first approves
     * (EXECUTED, decidedBy that member, one build).
     */
    @Test
    public void t_03_30_requestRunFormStoresEveryTickedApprover() throws Exception {
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        String single = submit(wc, TypedParameterFixtures.requestRunForm(j, wc, job), "a2");
        assertEquals(List.of("a2"), RunRequestService.get().load(single).getApprovers(),
                "guard: one ticked approver stores only that approver");
        assertClientError(decideRun(j, "a1", single, "approve", "not ticked"), "approval by a1, who was not ticked");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(single).getStatus());

        HtmlForm form = TypedParameterFixtures.requestRunForm(j, wc, job);
        List<String> expected = inPageOrder(form, "a1", "a2");
        String id = submit(wc, form, "a2", "a1");

        RunRequest request = RunRequestService.get().load(id);
        assertEquals(RequestStatus.PENDING, request.getStatus());
        assertEquals(expected, request.getApprovers(), "every approver ticked on the Request Run form must be stored, in page order"
                + " (form enctype " + form.getEnctypeAttribute() + ")");

        assertSuccess(decideRun(j, expected.get(0), id, "approve", "checked"), "approval by " + expected.get(0));
        j.waitUntilNoActivity();
        RunRequest decided = RunRequestService.get().load(id);
        assertEquals(RequestStatus.EXECUTED, decided.getStatus());
        assertEquals(expected.get(0), decided.getDecidedBy());
        assertEquals(1, job.getBuilds().size(), "the approval must run the build exactly once");
    }

    /**
     * T-03-31: the real Request Run form of a job with a core file parameter, in a browser (the
     * form must post multipart, or no browser sends the file): a file chosen and a1 and a2 ticked
     * stores both, in page order. Guards: a3 cannot decide; the member listed second approves,
     * the request is EXECUTED with that decider and the one build received the chosen file.
     */
    @Test
    public void t_03_31_requestRunFormWithFileParameterStoresEveryTickedApprover() throws Exception {
        FreeStyleProject fileJob = fileJob("batch-file");
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm form = TypedParameterFixtures.requestRunForm(j, wc, fileJob);
        assertEquals("multipart/form-data", form.getEnctypeAttribute().toLowerCase(Locale.ROOT),
                "premise: a Request Run form with a file control posts multipart/form-data");
        TypedParameterFixtures.setFile(form, "UPLOAD", TypedParameterFixtures.uploadFile("data.csv",
                TypedParameterFixtures.payload("multi-approver-form-Fz31", 2048)));
        List<String> expected = inPageOrder(form, "a1", "a2");
        String id = submit(wc, form, "a1", "a2");

        RunRequest request = RunRequestService.get().load(id);
        assertEquals(RequestStatus.PENDING, request.getStatus());
        assertEquals(expected, request.getApprovers(),
                "every approver ticked on the multipart Request Run form must be stored, in page order");

        assertClientError(decideRun(j, "a3", id, "approve", "not ticked"), "approval by a3, who was not ticked");
        assertSuccess(decideRun(j, expected.get(1), id, "approve", "checked"), "approval by " + expected.get(1));
        j.waitUntilNoActivity();
        assertDecidedAndRanWithFile(id, fileJob, expected.get(1));
    }

    // ------------------------------------------------------------------ non-ASCII ids (T-03-32)

    /** {@code 山田}: a user id outside ASCII, as LDAP, SAML or OIDC realms may hand out. */
    private static final String YAMADA = "山田";

    /** {@link #YAMADA}'s UTF-8 bytes read as ISO-8859-1, quoted in the messages so a garbled copy is recognisable. */
    private static final String YAMADA_AS_LATIN1 = new String(YAMADA.getBytes(StandardCharsets.UTF_8),
            StandardCharsets.ISO_8859_1);

    /**
     * T-03-32 (browser-shaped multipart, non-ASCII id first): a multipart {@code submit} shaped as
     * the Request Run form posts it (raw parts without a part charset plus core's {@code json}
     * field, as T-03-28) designating [山田, a2] stores exactly [山田, a2]: 山田 once, intact, first.
     * Guards: a3, who was not sent, cannot decide; 山田 approves (EXECUTED, decidedBy 山田, one build).
     */
    @Test
    public void t_03_32_multipartNonAsciiApproverFirstIsStoredOnce() throws Exception {
        allowNonAsciiApprover("a1", "a2", "a3", "admin", YAMADA);
        String id = acceptedMultipart(YAMADA, "a2");
        assertNonAsciiSetDecidedBy(id, Arrays.asList(YAMADA, "a2"), YAMADA, 1,
                "a multipart submission of [" + YAMADA + ", a2]");
    }

    /**
     * T-03-32 (browser-shaped multipart, non-ASCII id last): the same submission with [a2, 山田].
     * Stapler keeps one raw value per multipart field name, the last one (note 289), so this is the
     * order in which a raw {@code approvers} part decoded without a charset would carry 山田 next to
     * the {@code json} field's copy. It stores exactly [a2, 山田]. Guards as above.
     */
    @Test
    public void t_03_32_multipartNonAsciiApproverLastIsStoredOnce() throws Exception {
        allowNonAsciiApprover("a1", "a2", "a3", "admin", YAMADA);
        String id = acceptedMultipart("a2", YAMADA);
        assertNonAsciiSetDecidedBy(id, Arrays.asList("a2", YAMADA), YAMADA, 1,
                "a multipart submission of [a2, " + YAMADA + "]");
    }

    /**
     * T-03-32 (real form): the Request Run page in a browser (HtmlUnit, JavaScript on) offers 山田
     * as an approver; ticking 山田 and a2 stores exactly those two, in page order, each once. Done
     * twice, with 山田 configured first and then last in the approver list, so that 山田 is the
     * last ticked box in one of the submissions if the page follows the configured order. Both
     * sets are checked before either assertion can stop the test. Guards per request: a3 cannot
     * decide; 山田 approves (EXECUTED, decidedBy 山田, one build each).
     */
    @Test
    public void t_03_32_requestRunFormNonAsciiApproverIsStoredOnce() throws Exception {
        allowNonAsciiApprover(YAMADA, "a1", "a2", "a3", "admin");
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm firstForm = TypedParameterFixtures.requestRunForm(j, wc, job);
        List<String> firstExpected = inPageOrder(firstForm, YAMADA, "a2");
        String first = submit(wc, firstForm, YAMADA, "a2");

        allowNonAsciiApprover("a1", "a2", "a3", "admin", YAMADA);
        HtmlForm secondForm = TypedParameterFixtures.requestRunForm(j, wc, job);
        List<String> secondExpected = inPageOrder(secondForm, YAMADA, "a2");
        // amended for D-88 (note 359): another reason, so the second request is not identical to the pending first
        String second = submitWithReason(wc, secondForm, "month-end batch through the Request Run form, second run", YAMADA, "a2");

        List<String> firstStored = RunRequestService.get().load(first).getApprovers();
        List<String> secondStored = RunRequestService.get().load(second).getApprovers();
        assertAll(
                () -> assertEquals(firstExpected, firstStored, "the Request Run form with " + YAMADA
                        + " configured first must store the ticked approvers once each, intact, in page order"
                        + " (a garbled copy would read " + YAMADA_AS_LATIN1 + "; form enctype "
                        + firstForm.getEnctypeAttribute() + ")"),
                () -> assertEquals(secondExpected, secondStored, "the Request Run form with " + YAMADA
                        + " configured last must store the ticked approvers once each, intact, in page order"
                        + " (a garbled copy would read " + YAMADA_AS_LATIN1 + "; form enctype "
                        + secondForm.getEnctypeAttribute() + ")"));

        assertNonAsciiSetDecidedBy(first, firstExpected, YAMADA, 1, "the first Request Run form submission");
        assertNonAsciiSetDecidedBy(second, secondExpected, YAMADA, 2, "the second Request Run form submission");
    }

    /**
     * T-03-32 (url-encoded control, as T-03-07): url-encoded {@code submit}s with [山田, a2] and
     * with [a2, 山田] store exactly those sets. Guards as above.
     */
    @Test
    public void t_03_32_urlencodedNonAsciiApproverIsStoredOnce() throws Exception {
        allowNonAsciiApprover("a1", "a2", "a3", "admin", YAMADA);
        String first = submitRunOk(j, "u1", job, "month-end batch", YAMADA, "a2");
        String last = submitRunOk(j, "u1", job, "month-end batch, second run", "a2", YAMADA);
        assertNonAsciiSetDecidedBy(first, Arrays.asList(YAMADA, "a2"), YAMADA, 1,
                "a url-encoded submission of [" + YAMADA + ", a2]");
        assertNonAsciiSetDecidedBy(last, Arrays.asList("a2", YAMADA), YAMADA, 2,
                "a url-encoded submission of [a2, " + YAMADA + "]");
    }

    /**
     * u1's browser-shaped multipart {@code submit} to {@link #job} ({@code submitRunMultipart}: raw
     * parts plus the {@code json} field) designating {@code approvers}, every one an eligible
     * approver: it must be accepted; returns the id of the one request it created.
     */
    private String acceptedMultipart(String... approvers) throws Exception {
        Set<String> before = runRequestIds();
        WebResponse response = ApproverFormFixtures.submitRunMultipart(j, "u1", job, "month-end batch", List.of(), null,
                approvers);
        assertSuccess(response, "a browser-shaped multipart submission designating " + Arrays.toString(approvers)
                + " (every one an eligible approver; a refusal naming " + YAMADA_AS_LATIN1 + " means a raw part was"
                + " decoded as ISO-8859-1 next to the json field)");
        Set<String> after = runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "the accepted submission must have created exactly one run request, got " + after);
        return after.iterator().next();
    }

    /**
     * Makes {@link #YAMADA} an eligible approver: an existing user (the dummy realm accepts any id)
     * holding BatchControl/Approve, listed with the others in the given approver-list order.
     */
    private void allowNonAsciiApprover(String... approvers) throws Exception {
        j.jenkins.setAuthorizationStrategy(authorization(true)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to(YAMADA));
        User.getById(YAMADA, true).save();
        cfg.setApprovers(Arrays.asList(approvers));
        cfg.save();
    }

    /**
     * Request {@code id} is PENDING with exactly {@code expected}; a3 (not designated) is refused
     * and leaves it PENDING; {@code decider} approves: EXECUTED, decidedBy {@code decider}, and
     * {@link #job} then has {@code buildsAfter} builds.
     */
    private void assertNonAsciiSetDecidedBy(String id, List<String> expected, String decider, int buildsAfter,
                                            String what) throws Exception {
        RunRequest request = RunRequestService.get().load(id);
        assertEquals(RequestStatus.PENDING, request.getStatus());
        assertEquals(expected, request.getApprovers(), what + " must store exactly the designated ids, in submission"
                + " order, each once and intact (a garbled copy of " + YAMADA + " would read " + YAMADA_AS_LATIN1 + ")");
        assertClientError(decideRun(j, "a3", id, "approve", "not designated"), "approval by a3, who was not designated");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus());
        assertSuccess(decideRun(j, decider, id, "approve", "checked"), "approval by " + decider + ", a designated approver");
        j.waitUntilNoActivity();
        RunRequest decided = RunRequestService.get().load(id);
        assertEquals(RequestStatus.EXECUTED, decided.getStatus());
        assertEquals(decider, decided.getDecidedBy(), "the record must name the approver who decided");
        assertEquals(buildsAfter, job.getBuilds().size(), "each approval must run the build exactly once");
    }

    /** An approval-required Freestyle job with a core file parameter UPLOAD and a string DATE. */
    private FreeStyleProject fileJob(String name) throws Exception {
        FreeStyleProject fileJob = j.createFreeStyleProject(name);
        fileJob.addProperty(new ParametersDefinitionProperty(new FileParameterDefinition("UPLOAD", "input"),
                new StringParameterDefinition("DATE", "2000-01-01")));
        setBatchControl(fileJob, new BatchControlJobProperty(true));
        return fileJob;
    }

    /** The ticked approvers in the order the form lists them (the order a browser sends them in). */
    private static List<String> inPageOrder(HtmlForm form, String... ticked) {
        List<String> wanted = List.of(ticked);
        return UsabilityFixtures.approverChoices(form).stream().filter(wanted::contains).collect(Collectors.toList());
    }

    /**
     * Fills in the reason, ticks exactly {@code approvers} and submits {@code form} as a browser
     * does (core's form script, redirects followed); returns the id of the one request it created.
     */
    private String submit(JenkinsRule.WebClient wc, HtmlForm form, String... approvers) throws Exception {
        return submitWithReason(wc, form, "month-end batch through the Request Run form", approvers);
    }

    private String submitWithReason(JenkinsRule.WebClient wc, HtmlForm form, String reason, String... approvers) throws Exception {
        Set<String> before = runRequestIds();
        UsabilityFixtures.setField(form, "reason", reason);
        UsabilityFixtures.tickApprovers(form, approvers);
        Page answer = HtmlFormUtil.submit(form);
        PageScriptFixtures.settle(wc);
        assertTrue(answer.getWebResponse().getStatusCode() < 400, "fixture: the Request Run submission with "
                + Arrays.toString(approvers) + " ticked must succeed, got HTTP " + answer.getWebResponse().getStatusCode()
                + alerts(answer) + ": " + UsabilityFixtures.excerpt(answer.getWebResponse().getContentAsString()));
        Set<String> after = runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "fixture: the submission must have created exactly one run request, got " + after);
        return after.iterator().next();
    }

    /** The texts of the answer page's {@code role="alert"} elements (a refusal's reason), for failure messages. */
    private static String alerts(Page answer) {
        if (!(answer instanceof org.htmlunit.html.HtmlPage)) {
            return "";
        }
        List<String> texts = new ArrayList<>();
        for (org.htmlunit.html.HtmlElement element
                : ((org.htmlunit.html.HtmlPage) answer).getDocumentElement().getHtmlElementDescendants()) {
            String text = element.getTextContent().trim();
            if ("alert".equals(element.getAttribute("role")) && !text.isEmpty()) {
                texts.add(text);
            }
        }
        return texts.isEmpty() ? "" : " (alerts " + texts + ")";
    }

    /** Request {@code id} is EXECUTED, decided by {@code decider}, and the one build of {@code target} got data.csv as UPLOAD. */
    private static void assertDecidedAndRanWithFile(String id, FreeStyleProject target, String decider) {
        RunRequest decided = RunRequestService.get().load(id);
        assertEquals(RequestStatus.EXECUTED, decided.getStatus());
        assertEquals(decider, decided.getDecidedBy(), "the record must name the approver who decided");
        assertEquals(1, target.getBuilds().size(), "the approval must run the build exactly once");
        ParametersAction parameters = target.getBuildByNumber(1).getAction(ParametersAction.class);
        assertNotNull(parameters, "the approved build must carry its parameters");
        ParameterValue upload = parameters.getParameter("UPLOAD");
        assertTrue(upload instanceof FileParameterValue, "the approved build must receive the file parameter, got " + upload);
        assertEquals("data.csv", ((FileParameterValue) upload).getOriginalFileName(),
                "the approved build must receive the uploaded file");
    }
}
