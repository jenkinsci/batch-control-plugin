package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.Failure;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedRunAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6 (D-23, D-30, D-47): the approved-run marker is bound to its request and consumed by one
 * queue submission; a re-use is refused and recorded as {@code MARKER_REUSE_BLOCKED}, naming the
 * account that tried, and the record is queryable on the history screen. Matrix row T-06-111
 * (note 367).
 *
 * <p>This is the server-side half of the retired e2e script {@code e2e/scripts/rest-marker-reuse.sh}
 * (issue #31): the spent marker presented on <em>another</em> job. T-RT-02 asserts that refusal but no
 * record; T-06-17/T-06-55 record re-uses on the job the marker was issued for; T-GAP-121 presents an
 * unspent marker on another job. The history screen's warning block for these records was asserted
 * only by the script.
 *
 * <p>Written from docs/SPEC.md item 6 and docs/TEST-MATRIX.md (T-06-17, T-06-55, notes 37 and 113)
 * only (no src/main knowledge).
 */
@WithJenkins
public class RefusedMarkerReuseTest {

    /** The heading of the history screen's block listing blocked marker re-uses (as the e2e script read it). */
    private static final String BLOCK_TITLE = "Blocked re-use of an approved-run marker";
    private static final String ATTEMPTED_BY = "Attempted by";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("u2")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-06-111 (D-23, D-30, D-47): u1's request on the approval-required {@code mr-x} is approved and
     * runs once, consuming its marker. {@code mr-y} does not require approval; u2's plain manual run
     * of it is admitted (guard: build #1). u2 then submits {@code mr-y} with the spent marker of
     * {@code mr-x}: it is refused (queue empty, next build number 2, one build), and exactly one
     * MARKER_REUSE_BLOCKED record is written with target {@code mr-y}, user u2 and the request id
     * in its detail; none targets {@code mr-x}. The history screen, empty of the block before the
     * attempt (guard), then shows "Blocked re-use of an approved-run marker" with "Attempted by",
     * and that block names u2 and the request id.
     */
    @Test
    public void t_06_111_spentMarkerOnAnotherJobIsRefusedAndRecordedAgainstThatJob() throws Exception {
        FreeStyleProject x = j.createFreeStyleProject("mr-x");
        BatchControlFixtures.setBatchControl(x, new BatchControlJobProperty(true));
        FreeStyleProject y = j.createFreeStyleProject("mr-y");
        BatchControlFixtures.setBatchControl(y, new BatchControlJobProperty(false));

        RunRequest request;
        try (ACLContext ignored = as("u1")) {
            request = RunRequestService.get().create(x, new LinkedHashMap<>(), "D-30 marker re-use on another job", "a1");
        }
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(request.getId(), "ok");
        }
        j.waitUntilNoActivity();
        assertEquals(1, x.getBuilds().size(), "fixture: the approved request runs mr-x exactly once");
        FreeStyleBuild approved = x.getBuildByNumber(1);
        ApprovedRunAction marker = approved.getAction(ApprovedRunAction.class);
        assertNotNull(marker, "fixture: the approved run carries the marker");
        assertEquals(request.getId(), marker.getRequestId(), "fixture: the marker is bound to the request");

        // guard: mr-y admits u2's plain manual run, so a refusal below can only come from the marker
        try (ACLContext ignored = as("u2")) {
            j.assertBuildStatusSuccess(y.scheduleBuild2(0, new Cause.UserIdCause()));
        }
        j.waitUntilNoActivity();
        assertEquals(1, y.getBuilds().size(), "guard: u2's plain run of mr-y is admitted");
        assertTrue(reuseRecords().isEmpty(), "guard: no MARKER_REUSE_BLOCKED record exists before a re-use: " + describe(reuseRecords()));
        assertFalse(historyHtml().getWebResponse().getContentAsString().contains(BLOCK_TITLE),
                "guard: the history screen shows no blocked re-use block while there is none");

        try (ACLContext ignored = as("u2")) {
            assertRefused(() -> y.scheduleBuild2(0, new Cause.UserIdCause(), marker));
        }
        assertEquals(0, j.jenkins.getQueue().getItems().length, "the queue must stay empty");
        j.waitUntilNoActivity();
        assertEquals(2, y.getNextBuildNumber(), "D-23: the spent marker must not start mr-y (next build number unchanged)");
        assertEquals(1, y.getBuilds().size(), "D-23: no build of mr-y may come from the spent marker");
        assertEquals(1, x.getBuilds().size(), "mr-x must still have exactly its approved run");

        List<ChangeRecord> records = reuseRecords();
        assertEquals(1, records.size(), "D-30: exactly one MARKER_REUSE_BLOCKED record for the attempt: " + describe(records));
        ChangeRecord record = records.get(0);
        assertEquals("mr-y", record.getTarget(), "the record is against the job the marker was presented on");
        assertEquals("u2", record.getUser(), "the record names the account that tried, not the requester");
        assertTrue(String.valueOf(record.getDetail()).contains(request.getId()), "the record names the consumed request: " + describe(records));

        HtmlPage history = historyHtml();
        DomElement title = history.getFirstByXPath("//*[contains(text(), '" + BLOCK_TITLE + "')]");
        assertNotNull(title, "the history screen must show the '" + BLOCK_TITLE + "' block: "
                + ApproverFormFixtures.excerpt(history.asNormalizedText()));
        DomNode block = title;
        while (block != null && !block.getTextContent().contains(ATTEMPTED_BY)) {
            block = block.getParentNode();
        }
        assertNotNull(block, "the block must carry the '" + ATTEMPTED_BY + "' column");
        String text = block.getTextContent();
        assertTrue(text.contains("u2"), "the block must name the account that tried: " + ApproverFormFixtures.excerpt(text));
        assertTrue(text.contains(request.getId()), "the block must name the consumed request: " + ApproverFormFixtures.excerpt(text));
    }

    // ---------------------------------------------------------------- helpers

    private HtmlPage historyHtml() throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(j, "viewer", "batch-control/history/");
        assertEquals(200, page.getWebResponse().getStatusCode(), "the history screen must open for a ViewHistory holder");
        return page;
    }

    private static List<ChangeRecord> reuseRecords() {
        return ApproverFormFixtures.records(ChangeType.MARKER_REUSE_BLOCKED);
    }

    private static String describe(List<ChangeRecord> records) {
        return records.stream().map(r -> "[target=" + r.getTarget() + " user=" + r.getUser() + " detail=" + r.getDetail() + "]")
                .collect(Collectors.joining(", "));
    }

    /** A refused submission either returns null (silent refusal) or throws the guidance {@link Failure}. */
    private static void assertRefused(Submission submission) throws Exception {
        try {
            assertNull(submission.call(), "D-23: the spent marker must not be admitted on another job");
        } catch (Failure expected) {
            // the guidance refusal is equally a refusal
        }
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    @FunctionalInterface
    private interface Submission {
        Future<?> call() throws Exception;
    }
}
