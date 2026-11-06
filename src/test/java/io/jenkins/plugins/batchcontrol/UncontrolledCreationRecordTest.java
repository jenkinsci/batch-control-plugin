package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ActivationState;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.recordsFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A job created while run control is off leaves one {@code ACTIVATED} record by {@code (uncontrolled)}
 * (issue #31, retired legacy driver browser/audit/b10.mjs #14, gap X-33). Matrix row T-06a-114 (note 371).
 *
 * <p>Basis: SPEC 6a ("a job created while run control is off is recorded as activated at creation (D-45)";
 * "jobs that exist when this version is first installed are recorded as activated ({@code activatedBy =
 * (upgrade)}, one {@code ACTIVATED} record each), exactly once"), DECISIONS D-45 ("recorded as activated at
 * creation ({@code activatedBy = (uncontrolled)}), exactly like the jobs seeded at first install") and D-13
 * (records are active while either switch is on, so change control is on here). T-06a-08 reads the state of
 * such a job but not its record; the CI e2e reads D-13 as "nothing except D-45's ACTIVATED record" while both
 * switches are off (gap list, ambiguity note), which this row does not touch.
 *
 * <p>Written from docs/SPEC.md item 6a, docs/DECISIONS.md D-13 and D-45 and docs/TEST-MATRIX.md (T-06a-08,
 * T-06a-32); the retired driver was read only to learn what it looked at. No src/main knowledge.
 */
@WithJenkins
public class UncontrolledCreationRecordTest {

    private static final long RECORD_WAIT_MS = 5_000;

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.setChangeControlEnabled(true);
        cfg.save();
    }

    /**
     * T-06a-114 (D-45, SPEC 6a; #31 gap X-33): with run control off and change control on (records active,
     * D-13), creating {@code x} leaves exactly one ACTIVATED change record for {@code x}, naming
     * {@code (uncontrolled)} as its user (the value D-45 gives {@code activatedBy}); saving the job and then
     * turning run control on add no second one (exactly once). Guard: with run control on, creating {@code y}
     * leaves no ACTIVATED record and {@code y} is not activated (T-06a-01).
     */
    @Test
    public void t_06a_114_jobCreatedWhileRunControlIsOffLeavesOneUncontrolledActivatedRecord() throws Exception {
        assertFalse(cfg.isRunControlEnabled(), "premise: run control is off (D-12)");
        assertTrue(cfg.isChangeControlEnabled(), "premise: change control is on, so records are written (D-13)");
        assertTrue(recordsFor(ChangeType.ACTIVATED, "x").isEmpty(), "premise: no ACTIVATED record of x yet");

        FreeStyleProject x = j.createFreeStyleProject("x");
        assertTrue(isActivated(x), "premise (T-06a-08): a job created while run control is off is activated at creation");
        ActivationState state = ActivationService.get().getState(x);
        assertNotNull(state, "premise: the creation is stored as an activation state");
        assertEquals("(uncontrolled)", state.getActivatedBy(), "premise (D-45): activatedBy = (uncontrolled)");

        List<ChangeRecord> records = awaitRecords("x");
        assertEquals(1, records.size(), "D-45 / SPEC 6a: creating x while run control is off must leave exactly one ACTIVATED"
                + " record, like a seeded job: " + describe(records));
        assertEquals("(uncontrolled)", records.get(0).getUser(), "the ACTIVATED record must name (uncontrolled), the value"
                + " D-45 gives activatedBy, as who activated x: " + describe(records));

        x.setDescription("edited after creation");
        x.save();
        cfg.setRunControlEnabled(true);
        cfg.save();
        assertTrue(cfg.isRunControlEnabled(), "premise: run control is on now");
        assertTrue(isActivated(x), "premise: x stays activated once run control is on (D-45)");
        assertEquals(1, recordsFor(ChangeType.ACTIVATED, "x").size(), "exactly once: a save of x and turning run control on"
                + " must not add a second ACTIVATED record: " + describe(recordsFor(ChangeType.ACTIVATED, "x")));

        FreeStyleProject y = j.createFreeStyleProject("y");
        assertFalse(isActivated(y), "guard (T-06a-01): a job created while run control is on is not activated");
        j.waitUntilNoActivity();
        assertTrue(recordsFor(ChangeType.ACTIVATED, "y").isEmpty(), "guard: creating y while run control is on leaves no"
                + " ACTIVATED record: " + describe(recordsFor(ChangeType.ACTIVATED, "y")));
    }

    // ---------------------------------------------------------------- helpers

    /** Polls for the record a creation must write (a listener may write it just after the creation returns). */
    private static List<ChangeRecord> awaitRecords(String target) throws InterruptedException {
        long deadline = System.currentTimeMillis() + RECORD_WAIT_MS;
        List<ChangeRecord> found = recordsFor(ChangeType.ACTIVATED, target);
        while (found.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50); // polling for an asynchronous record, not waiting for an expiry
            found = recordsFor(ChangeType.ACTIVATED, target);
        }
        return found;
    }

    private static String describe(List<ChangeRecord> records) {
        return records.stream().map(r -> r.getType() + " user=" + r.getUser() + " target=" + r.getTarget()
                + " detail=" + r.getDetail()).collect(Collectors.toList()).toString();
    }
}
