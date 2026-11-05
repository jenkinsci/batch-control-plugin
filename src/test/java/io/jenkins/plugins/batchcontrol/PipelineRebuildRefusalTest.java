package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pipeline's Rebuild ({@code replay/rebuild}) of an approval-required job by a person who holds Job/Build
 * but not Run/Replay is refused, explained and recorded per attempt (issue #31, note 370, gap X-37,
 * retired drivers fresh/v/final-fd.mjs #7, fresh/v/ve-refusals.mjs #9, browser/verify/f1.mjs #11, f2.mjs
 * #1, #2, v2.mjs #3 and v2b.mjs #2..4). Matrix row T-06-113.
 *
 * <p>Basis: SPEC item 6 (a manual run of an approval-required job needs an approved request; a refusal page
 * links the request screen for a user who may open it, line 114; every refused re-run is recorded, line
 * 115; a refused re-run submitted by a person writes one record per attempt naming the user and the re-run
 * build, a repeat of the same attempt by the same user within one minute is merged, line 117), D-49 (core
 * and Pipeline draw re-run links no plugin can hide, so a click is refused with an explanation pointing to
 * Request Run), D-51/D-51a and the usability line of SPEC section 6. Existing rows cover the administrator's
 * Replay (T-06-68, T-06-80) and the D-58c marked-run Rebuild; this is a person's Pipeline Rebuild, which
 * needs only Job/Build.
 *
 * <p>The builds #1 and #2 run while the job is uncontrolled, so they carry no approval marker and the
 * refusal is a TRIGGER_BLOCKED, not a MARKER_REUSE_BLOCKED (SPEC 6 line 115). The answer's shape follows
 * the matrix rows on refused build submissions (T-UI-16, T-UI-23: a 4xx HTML page naming approval; T-E2E-49
 * and note 140: the gate's "Approval required" page): the job page of an approval-required job also names
 * approval and links the request form (T-06-56), so those two alone would not tell a refusal from an
 * accepted Rebuild that redirects there.
 *
 * <p>Written from docs/SPEC.md item 6 and section 6, docs/DECISIONS.md D-49, D-51 and D-51a and
 * docs/TEST-MATRIX.md (T-06-68, T-06-80, T-06-81); the retired drivers were read only to learn what each
 * looked at. No src/main knowledge.
 */
@WithJenkins
public class PipelineRebuildRefusalTest {

    private static final Pattern APPROVAL = Pattern.compile("(?i)approv");
    /** The job page {@code job/<name>/} or a build page {@code job/<name>/<n>/}, relative to the context path. */
    private static final Pattern JOB_OR_BUILD_PAGE = Pattern.compile("job/[^/]+/(\\d+/)?");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-06-113 (SPEC 6 lines 114, 115, 117; D-49; D-51/D-51a; #31 gap X-37): the activated Pipeline
     * {@code p} built #1 and #2 while uncontrolled and was then made approval-required; u1 holds Job/Build
     * and BatchControl/Request on it but not Run/Replay (premise). u1 POSTs {@code job/p/1/replay/rebuild}
     * twice within seconds and {@code job/p/2/replay/rebuild} once (crumb). Each answer is the refusal
     * itself, the way T-UI-16/T-UI-23 and T-E2E-49 describe a refused build submission: HTTP 4xx at the POSTed
     * URL (no redirect, so not the job page or a build page), an HTML page headed "Approval required" in
     * plain words (no "Oops!", stack trace or bare "Access Denied") naming approval and linking
     * {@code job/p/batch-control}; the queue stays empty, the next build number stays 3 and there are still
     * two builds. Exactly two TRIGGER_BLOCKED records of {@code p} name u1: one names build #1 only (the two
     * attempts within a minute are merged) and one build #2 only. Guard: the same POST by u1 on the
     * uncontrolled Pipeline {@code free-p} builds its #2, so u1's Job/Build is enough for a Rebuild and the
     * refusal is Batch Control's; its answer lands, through a redirect, on the job or build page and has no
     * "Approval required" heading, so the refusal-shape checks tell a refusal from an accepted Rebuild.
     */
    @Test
    public void t_06_113_pipelineRebuildByABuildHolderIsRefusedAndRecordedPerAttempt() throws Exception {
        WorkflowJob free = pipeline("free-p");
        WorkflowJob p = pipeline("p");
        j.buildAndAssertSuccess(p);
        setBatchControl(p, new BatchControlJobProperty(true));
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            assertTrue(p.hasPermission(Item.BUILD), "premise: u1 holds Job/Build on p");
            assertFalse(p.getBuildByNumber(1).hasPermission(ReplayAction.REPLAY), "premise: u1 does not hold Run/Replay on p");
        }

        Page freeAnswer = PluginInteractionFixtures.post(j, "u1", free.getUrl() + "1/replay/rebuild");
        assertTrue(freeAnswer.getWebResponse().getStatusCode() < 400, "guard: u1's Rebuild of the uncontrolled free-p is"
                + " accepted, got HTTP " + freeAnswer.getWebResponse().getStatusCode());
        // guard of the refusal-shape checks below: an accepted Rebuild lands, through a redirect, on the job or
        // build page, which carries no "Approval required" heading
        String freeLanded = freeAnswer.getUrl().getPath();
        assertTrue(JOB_OR_BUILD_PAGE.matcher(freeLanded.substring(j.contextPath.length() + 1)).matches(),
                "guard (premise): an accepted Rebuild lands on the job or build page, landed on " + freeLanded);
        assertFalse(freeAnswer instanceof HtmlPage && hasApprovalHeading((HtmlPage) freeAnswer),
                "guard: the page an accepted Rebuild lands on has no 'Approval required' heading");
        j.waitUntilNoActivity();
        assertNotNull(free.getBuildByNumber(2), "guard: u1's Rebuild of the uncontrolled free-p runs as #2");

        int[] sources = {1, 1, 2};
        for (int n : sources) {
            String what = "u1's Rebuild of p #" + n;
            Page answer = PluginInteractionFixtures.post(j, "u1", p.getUrl() + n + "/replay/rebuild");
            String text = UsabilityFixtures.text(answer);
            int code = answer.getWebResponse().getStatusCode();
            assertTrue(code >= 400 && code < 500, what + ": the refusal must answer HTTP 4xx (T-UI-16, T-UI-23), got " + code
                    + ": " + excerpt(text));
            String posted = j.contextPath + "/" + p.getUrl() + n + "/replay/rebuild";
            String landed = answer.getUrl().getPath();
            assertEquals(posted, landed, what + ": the answer must be the refusal itself, not a page reached through a redirect");
            assertFalse(JOB_OR_BUILD_PAGE.matcher(landed.substring(j.contextPath.length() + 1)).matches(),
                    what + ": the answer must not be the job or a build page: " + landed);
            UsabilityFixtures.assertPlainRefusal(what, text, APPROVAL);
            assertTrue(answer instanceof HtmlPage, what + ": the refusal must be a page the user reads, got "
                    + answer.getWebResponse().getContentType() + ": " + excerpt(text));
            assertTrue(hasApprovalHeading((HtmlPage) answer), what + ": the refusal must be the gate's 'Approval required' page"
                    + " (T-E2E-49, note 140): " + excerpt(text));
            assertTrue(UsabilityFixtures.hasLinkTo(j, (HtmlPage) answer, p.getUrl() + "batch-control"), what
                    + ": the refusal must link the request form; anchors were " + UsabilityFixtures.resolvedHrefs((HtmlPage) answer));
        }
        assertBlocked(j, p, 3, 2);

        List<ChangeRecord> byU1 = ActivationFixtures.recordsFor(ChangeType.TRIGGER_BLOCKED, p.getFullName()).stream()
                .filter(r -> "u1".equals(r.getUser())).collect(Collectors.toList());
        assertEquals(2, byU1.size(), "u1's attempts on #1 (twice within a minute) and #2 must leave exactly two TRIGGER_BLOCKED"
                + " records (D-51): " + describe(byU1));
        assertEquals(1, byU1.stream().filter(r -> namesOnly(r, 1)).count(), "one record must name #1 only: " + describe(byU1));
        assertEquals(1, byU1.stream().filter(r -> namesOnly(r, 2)).count(), "one record must name #2 only: " + describe(byU1));
    }

    // ---------------------------------------------------------------- helpers

    private WorkflowJob pipeline(String name) throws Exception {
        WorkflowJob pipeline = uncontrolled(j.createProject(WorkflowJob.class, name));
        pipeline.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        BatchControlFixtures.activateAsAdmin(pipeline); // D-46: a cause-less submission needs an activation
        j.buildAndAssertSuccess(pipeline);
        return pipeline;
    }

    /** The page's heading (an {@code h1}) reads "Approval required" (letter case not pinned). */
    private static boolean hasApprovalHeading(HtmlPage page) {
        return page.querySelectorAll("h1").stream().anyMatch(h -> "approval required".equalsIgnoreCase(h.asNormalizedText().trim()));
    }

    private static boolean namesOnly(ChangeRecord r, int n) {
        String text = r.getTarget() + " " + r.getDetail();
        for (int other = 1; other <= 3; other++) {
            boolean names = Pattern.compile("(#|/)" + other + "\\b").matcher(text).find();
            if (names != (other == n)) {
                return false;
            }
        }
        return true;
    }

    private static String describe(List<ChangeRecord> records) {
        return records.stream().map(r -> r.getType() + "(" + r.getTarget() + ": " + r.getDetail() + " by " + r.getUser() + ")")
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
