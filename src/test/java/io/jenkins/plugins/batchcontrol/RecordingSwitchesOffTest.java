package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With both switches off a completed build adds no run record (issue #31, retired legacy driver
 * browser/audit/b1a.mjs #5 and browser/b1.mjs #6, gap X-27). Matrix row T-10-15 (note 370).
 *
 * <p>Basis: DECISIONS D-13 ("어느 스위치든 켜지면 모든 기록 활성": all records are active once either switch
 * is on, so none is written while both are off), SPEC item 1 (installing the plugin changes nothing while
 * the switches are off; D-12), SPEC item 9 ("기록은 어느 스위치든 켜지면 활성") and SPEC item 10 (every
 * build is recorded once recording is active). The change-record half is T-09-11; this row is the run
 * record. The CI e2e reads D-13 as "nothing except D-45's ACTIVATED record" (gap list, ambiguity note),
 * and a run record is not that exception.
 *
 * <p>A record that is written late (after the build completed) would let the negative assertion pass
 * too early, so the off-switch build runs first and is re-checked after the guards' later builds have
 * produced their records.
 *
 * <p>Written from docs/SPEC.md items 1, 9 and 10, docs/DECISIONS.md D-12, D-13 and D-45 and
 * docs/TEST-MATRIX.md (T-09-11); the retired drivers were read only to learn what they looked at. No
 * src/main knowledge.
 */
@WithJenkins
public class RecordingSwitchesOffTest {

    private static final long RECORD_WAIT_MS = 10_000;

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-10-15 (D-13, SPEC 1, SPEC 10; #31 gap X-27): with both switches off (the installed default,
     * premise), {@code off-x} (created then, so it needs no approval and is activated, D-45) builds #1:
     * no run record names {@code off-x#1} and the month's run records are unchanged. Guards: with run
     * control on, #2 adds exactly one run record ({@code off-x#2}); with run control off and only change
     * control on, #3 adds exactly one ({@code off-x#3}). With both off again, #4 adds none, and #1 still
     * has none after the guards' records appeared.
     */
    @Test
    public void t_10_15_noRunRecordWhileBothSwitchesAreOff() throws Exception {
        assertFalse(cfg.isRunControlEnabled(), "premise: run control is off by default (D-12)");
        assertFalse(cfg.isChangeControlEnabled(), "premise: change control is off by default (D-12)");
        FreeStyleProject job = j.createFreeStyleProject("off-x");
        List<String> before = runIds();

        j.buildAndAssertSuccess(job);
        j.waitUntilNoActivity();
        assertTrue(recordsOf("off-x#1").isEmpty(), "with both switches off build #1 must add no run record: " + runIds());

        cfg.setRunControlEnabled(true);
        cfg.save();
        j.buildAndAssertSuccess(job);
        assertEquals(1, awaitRecords("off-x#2").size(), "guard: with run control on build #2 adds exactly one run record: "
                + runIds());

        cfg.setRunControlEnabled(false);
        cfg.setChangeControlEnabled(true);
        cfg.save();
        j.buildAndAssertSuccess(job);
        assertEquals(1, awaitRecords("off-x#3").size(), "guard: with only change control on build #3 adds exactly one run"
                + " record (recording is active when either switch is on): " + runIds());

        cfg.setChangeControlEnabled(false);
        cfg.save();
        assertFalse(cfg.isRunControlEnabled() || cfg.isChangeControlEnabled(), "premise: both switches are off again");
        j.buildAndAssertSuccess(job);
        j.waitUntilNoActivity();

        assertTrue(recordsOf("off-x#1").isEmpty(), "build #1 (both switches off) must still have no run record after the"
                + " guards' records appeared: " + runIds());
        assertTrue(recordsOf("off-x#4").isEmpty(), "with both switches off again build #4 must add no run record: " + runIds());
        List<String> added = runIds();
        added.removeAll(before);
        assertEquals(List.of("off-x#2", "off-x#3"), added.stream().sorted().collect(Collectors.toList()),
                "only the builds made while a switch was on may be recorded");
    }

    // ---------------------------------------------------------------- helpers

    private static List<RunRecord> recordsOf(String runId) {
        return FileStore.get().listRunRecords(YearMonth.now(BatchClock.clock())).stream()
                .filter(r -> runId.equals(r.getRunId())).collect(Collectors.toList());
    }

    private static List<String> runIds() {
        return FileStore.get().listRunRecords(YearMonth.now(BatchClock.clock())).stream()
                .map(RunRecord::getRunId).collect(Collectors.toList());
    }

    /** Polls for the record of a build that must be recorded (the listener may run after the build completed). */
    private static List<RunRecord> awaitRecords(String runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + RECORD_WAIT_MS;
        List<RunRecord> found = recordsOf(runId);
        while (found.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50); // polling for an asynchronous record, not waiting for an expiry
            found = recordsOf(runId);
        }
        return found;
    }
}
