package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.recordsFor;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.activate;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An activated job survives Jenkins' own Disable Project followed by Enable, and its timer runs again
 * (issue #31, retired legacy drivers browser/verify/d.mjs #11 and d10.mjs #2, gap X-36). Matrix row
 * T-06a-115 (note 371).
 *
 * <p>Basis: SPEC 6a ("an activation is per job and survives configuration edits"; "Jenkins' own Disable
 * Project and the global run-control switch remain available as immediate emergency stops"; "putting a job on
 * hold ({@code HOLD}) is also a request that needs approval"), so Disable is not a hold and Enable is not an
 * activation; DECISIONS D-07 (enable/disable belongs with configuration) and D-39.
 *
 * <p>Users: {@code admin} (Overall/Administer) presses Disable and Enable; {@code u1} requests and {@code a1}
 * approves the activation (the {@link BatchControlFixtures#activate} fixture). Run control on.
 *
 * <p>Written from docs/SPEC.md item 6a, docs/DECISIONS.md D-07 and D-39 and docs/TEST-MATRIX.md (T-06a-01,
 * T-06a-02); the retired drivers were read only to learn what they looked at. No src/main knowledge.
 */
@WithJenkins
public class ActivationDisableEnableTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-06a-115 (SPEC 6a; #31 gap X-36): the activated cron job {@code cron-live} (timer switch cleared, so
     * only activation decides) builds #1 from its timer. The administrator POSTs (crumb) core's
     * {@code disable}: the job is disabled, a timer cause builds nothing, and it is still activated. The
     * administrator POSTs {@code enable}: the job is still activated, a timer cause builds #2, and no HELD
     * record and no second ACTIVATED record exist. Guard: the never-activated {@code cron-new}, put through the
     * same Disable and Enable, is still not activated, its timer is refused (blocking triple) and it has no
     * ACTIVATED record.
     */
    @Test
    public void t_06a_115_activatedJobSurvivesDisableThenEnable() throws Exception {
        FreeStyleProject live = cronJob("cron-live");
        activate(live);
        assertEquals(1, recordsFor(ChangeType.ACTIVATED, "cron-live").size(), "premise: one ACTIVATED record");
        j.assertBuildStatusSuccess(live.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        assertEquals(1, live.getBuilds().size(), "premise: the activated job builds #1 from its timer");

        disableThenEnable(live, true);

        assertTrue(isActivated(live), "SPEC 6a: Disable then Enable must leave cron-live activated (Disable is not an"
                + " approved HOLD, Enable is not a new activation)");
        j.assertBuildStatusSuccess(live.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        assertEquals(2, live.getBuilds().size(), "the re-enabled activated job must build #2 from its timer");
        assertTrue(recordsFor(ChangeType.HELD, "cron-live").isEmpty(), "Disable and Enable must write no HELD record: "
                + recordsFor(ChangeType.HELD, "cron-live"));
        assertEquals(1, recordsFor(ChangeType.ACTIVATED, "cron-live").size(), "Enable must write no second ACTIVATED record");

        FreeStyleProject fresh = cronJob("cron-new");
        assertFalse(isActivated(fresh), "guard premise: a job created under run control is not activated");
        disableThenEnable(fresh, false);
        assertFalse(isActivated(fresh), "guard: Enable must not activate a job nobody activated");
        int next = fresh.getNextBuildNumber();
        assertNull(fresh.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                "guard: the never-activated job's timer must still be refused after Disable and Enable");
        assertBlocked(j, fresh, next, 0);
        assertTrue(recordsFor(ChangeType.ACTIVATED, "cron-new").isEmpty(), "guard: no ACTIVATED record for cron-new");
    }

    // ---------------------------------------------------------------- helpers

    /** A job created under run control with a nightly timer and its timer and upstream switches cleared. */
    private FreeStyleProject cronJob(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        job.addTrigger(new TimerTrigger("0 3 * * *"));
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        return job;
    }

    /**
     * The administrator POSTs core's {@code disable} and then {@code enable} (crumb). While disabled the job
     * is reported disabled and, for an activated job, still activated, and a timer cause builds nothing.
     */
    private void disableThenEnable(FreeStyleProject job, boolean activated) throws Exception {
        int builds = job.getBuilds().size();
        int next = job.getNextBuildNumber();
        assertSuccess(ApproverFormFixtures.post(j, "admin", job.getUrl() + "disable", List.of()),
                "fixture: the administrator's Disable Project of " + job.getFullName());
        assertTrue(job.isDisabled(), "premise: " + job.getFullName() + " is disabled");
        assertEquals(activated, isActivated(job), "Disable must not change the activation of " + job.getFullName());
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                "premise: a disabled job does not build from its timer (Jenkins' own emergency stop)");
        assertBlocked(j, job, next, builds);

        assertSuccess(ApproverFormFixtures.post(j, "admin", job.getUrl() + "enable", List.of()),
                "fixture: the administrator's Enable of " + job.getFullName());
        assertFalse(job.isDisabled(), "premise: " + job.getFullName() + " is enabled again");
    }
}
