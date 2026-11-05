package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An approved run that is already waiting in the queue is not expired by the approved-run timeout
 * (issue #31, retired legacy driver browser/audit/b6.mjs #3, gap X-30). Matrix row T-07-17 (note 370).
 *
 * <p>Basis: SPEC item 7 ("APPROVED 상태에서 {@code approvedRunTimeoutMinutes} 안에 큐 투입이 안 되면
 * EXPIRED가 된다": only an approved request that was <em>not submitted to the queue</em> in time
 * expires; "큐 투입 직전에 만료를 재확인(check-at-submit)": the expiry is checked right before the
 * submission, not afterwards), the state machine of SPEC section 4 ({@code APPROVED -> EXECUTED},
 * {@code APPROVED -> EXPIRED (approvedRunTimeout)}) and SPEC item 6 (an approved request is queued
 * exactly once). DECISIONS D-08: expiry is a time comparison at check time, so the test moves the plugin
 * clock ({@link BatchClock}) and runs the periodic work itself; nothing waits in real time.
 *
 * <p>The run waits in the queue because the controller has no executor; the guard's request is refused
 * by the queue before Batch Control's gate (the {@link QueueRefusalFixtures} handler), so it is approved
 * but was never queued.
 *
 * <p>Written from docs/SPEC.md items 4, 6 and 7, docs/DECISIONS.md D-08 and D-20 and docs/TEST-MATRIX.md;
 * the retired driver was read only to learn what it looked at. No src/main knowledge.
 */
@WithJenkins
public class QueuedApprovedRunExpiryTest {

    private static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");
    private static final int TIMEOUT_MINUTES = 5;

    /** The queue refuses an armed job before Batch Control's gate (see {@link QueueRefusalFixtures}). */
    @TestExtension
    public static final class RefuseBeforeGate extends QueueRefusalFixtures.RefusingHandler {
    }

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.setApprovedRunTimeoutMinutes(TIMEOUT_MINUTES);
        cfg.save();
        assertEquals(TIMEOUT_MINUTES, BatchControlGlobalConfiguration.get().getApprovedRunTimeoutMinutes(),
                "premise: approvedRunTimeoutMinutes is " + TIMEOUT_MINUTES);
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-07-17 (SPEC 7, SPEC 4, SPEC 6; #31 gap X-30): at T0, with no executor, a1 approves u1's request
     * for the approval-required {@code queued-x}; its run waits in the queue (premise: APPROVED, one queue
     * item, no build). The clock moves to T0 + 2 x the timeout and the expiry work runs: the request is
     * still APPROVED and the queue item is still there. Once an executor is added the run starts exactly
     * once: EXECUTED, build #1 only, next build number 2, the queue empty; a further expiry pass changes
     * nothing. Guard (same clock move, same expiry pass): u1's request for {@code refused-y}, approved at
     * T0 while the queue refused its submission, is EXPIRED and never runs, so the expiry work does expire
     * an approved request that never reached the queue.
     */
    @Test
    public void t_07_17_approvedRunWaitingInTheQueueDoesNotExpire() throws Exception {
        FreeStyleProject queued = approvalRequired("queued-x");
        FreeStyleProject refused = approvalRequired("refused-y");
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        j.jenkins.setNumExecutors(0);

        RunRequest waiting = create(queued, "month-end batch, waits for an executor");
        approve(waiting.getId());
        assertEquals(RequestStatus.APPROVED, status(waiting.getId()), "premise: the request is APPROVED");
        Queue.Item item = j.jenkins.getQueue().getItem(queued);
        assertNotNull(item, "premise: the approved run waits in the queue");
        assertTrue(queued.getBuilds().isEmpty(), "premise: no build has started");

        RunRequest neverQueued = create(refused, "guard: approved, but the queue refuses it");
        QueueRefusalFixtures.refusedBeforeTheGate(refused, () -> approve(neverQueued.getId()));
        assertEquals(RequestStatus.APPROVED, status(neverQueued.getId()), "guard premise: APPROVED");
        assertNull(j.jenkins.getQueue().getItem(refused), "guard premise: the refused request's run was never queued");

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(2L * TIMEOUT_MINUTES)), ZoneOffset.UTC));
        runExpiryWork();

        assertEquals(RequestStatus.EXPIRED, status(neverQueued.getId()),
                "guard: an approved request that never reached the queue is EXPIRED once approvedRunTimeoutMinutes passed");
        assertEquals(RequestStatus.APPROVED, status(waiting.getId()),
                "an approved request whose run is already in the queue must not expire (SPEC 7: only a request not queued in"
                        + " time expires)");
        assertNotNull(j.jenkins.getQueue().getItem(queued), "the queued run must still wait in the queue after the expiry work");
        assertTrue(queued.getBuilds().isEmpty(), "premise: still no executor, so no build yet");

        j.jenkins.setNumExecutors(1);
        j.waitUntilNoActivity();

        RunRequest executed = RunRequestService.get().load(waiting.getId());
        assertEquals(RequestStatus.EXECUTED, executed.getStatus(), "the queued approved run must start and the request be EXECUTED");
        assertNotNull(executed.getExecutedRunId(), "the EXECUTED request names its run");
        assertEquals(1, queued.getBuilds().size(), "the approved run must start exactly once");
        assertNotNull(queued.getBuildByNumber(1), "the run is build #1");
        assertEquals(2, queued.getNextBuildNumber(), "no second build number may be consumed");
        assertTrue(j.jenkins.getQueue().isEmpty(), "nothing is left in the queue");

        runExpiryWork();
        j.waitUntilNoActivity();
        assertEquals(RequestStatus.EXECUTED, status(waiting.getId()), "a later expiry pass leaves the EXECUTED request alone");
        assertEquals(1, queued.getBuilds().size(), "still exactly one build");
        assertTrue(refused.getBuilds().isEmpty(), "guard: the expired approval never runs");
        assertEquals(1, refused.getNextBuildNumber(), "guard: no build number of refused-y was consumed");
    }

    // ---------------------------------------------------------------- helpers

    private FreeStyleProject approvalRequired(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    private static RunRequest create(FreeStyleProject job, String reason) {
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            return RunRequestService.get().create(job, new LinkedHashMap<>(), reason, "a1");
        }
    }

    private static void approve(String id) {
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            RunRequestService.get().approve(id, "ok");
        }
    }

    private static RequestStatus status(String id) {
        return RunRequestService.get().load(id).getStatus();
    }

    private static void runExpiryWork() throws Exception {
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
    }
}
