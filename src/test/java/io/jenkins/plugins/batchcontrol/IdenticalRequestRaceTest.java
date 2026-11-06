package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.BatchControlNotifier;
import io.jenkins.plugins.batchcontrol.ops.Notification;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.FormEncodingType;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.approverPairs;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.runRequestIds;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two identical run requests submitted at the same moment create one request and one REQUEST_CREATED (issue
 * #31, retired legacy driver extra/race.mjs #4, gap X-51). Matrix row T-05-212 (note 371).
 *
 * <p>Basis: D-88 as frozen in the #1 contract (note 359; not yet in docs/DECISIONS.md): "When the same user
 * submits a run request for the same job with the same parameters, reason and approvers while an identical
 * request of theirs is still PENDING, no new request is created ... No second notification goes to the
 * approvers. A script (D-84 rules) gets a 4xx or 409 naming the existing request id. A request that differs in
 * anything ... is created normally." SPEC 7 (request state transitions are atomic, R-5/D-20) and SPEC 13 (a new
 * request notifies its designated approvers). T-05-204/205 submit one after the other; this row submits at
 * the same moment, several rounds, so a check that is not atomic with the store shows.
 *
 * <p>Users and job as T-05-204: u1 (Request) and a1 (Approve); the approval-required {@code batch-p} with the
 * string parameter {@code DATE}. Each submission is the Request Run form's multipart POST from its own
 * session, as a script ({@code Accept: application/json}), released by one latch.
 *
 * <p>Written from docs/SPEC.md items 4, 7 and 13, docs/DECISIONS.md D-84 and docs/TEST-MATRIX.md (T-05-204,
 * T-05-205, note 359); the retired driver was read only to learn what it looked at. No src/main knowledge.
 */
@WithJenkins
public class IdenticalRequestRaceTest {

    private static final String SCRIPT_ACCEPT = "application/json";
    private static final String DATE = "2026-10-31";
    private static final int ROUNDS = 6;

    private JenkinsRule j;
    private FreeStyleProject job;

    /** Records every event. */
    @TestExtension
    public static class CapturingNotifier extends BatchControlNotifier {
        @Override
        public void notify(NotificationEvent event, Notification notification) {
            NotificationCapture.record(event, notification);
        }
    }

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        NotificationCapture.clear();
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        job = j.createFreeStyleProject("batch-p");
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("DATE", "2000-01-01")));
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    @AfterEach
    public void tearDown() {
        NotificationCapture.clear();
    }

    /**
     * T-05-212 (D-88, SPEC 7 atomic transitions, SPEC 13; #31 gap X-51): in each of six rounds u1 submits the
     * same run request (DATE, the round's reason, a1) from two sessions released together. Each round creates
     * exactly one PENDING request, and the other answer is a 4xx naming that request's id; after the quiet
     * period exactly one REQUEST_CREATED names it. Guard first: two concurrent submissions that differ only in
     * the reason create two PENDING requests, each notified.
     */
    @Test
    public void t_05_212_identicalConcurrentSubmissionsCreateOneRequest() throws Exception {
        List<WebResponse> differing = race("guard reason one", "guard reason two");
        for (WebResponse answer : differing) {
            assertTrue(answer.getStatusCode() < 400, "guard: a submission that differs in its reason is created, got "
                    + answer.getStatusCode() + ": " + excerpt(answer.getContentAsString()));
        }
        Set<String> guardIds = runRequestIds();
        assertEquals(2, guardIds.size(), "guard: two concurrent submissions with different reasons create two requests: " + guardIds);
        for (String id : guardIds) {
            assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus(), "guard: " + id + " is PENDING");
            assertEquals(1, NotificationCapture.await(NotificationEvent.REQUEST_CREATED, id).size(), "guard: " + id + " is notified once");
        }

        for (int round = 1; round <= ROUNDS; round++) {
            String reason = "month-end close, round " + round;
            Set<String> before = runRequestIds();
            List<WebResponse> answers = race(reason, reason);
            Set<String> created = runRequestIds();
            created.removeAll(before);
            assertEquals(1, created.size(), "round " + round + ": two identical submissions at the same moment must create"
                    + " exactly one request, created " + created);
            String id = created.iterator().next();
            assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus(), "round " + round + ": PENDING");
            int refused = 0;
            for (WebResponse answer : answers) {
                int code = answer.getStatusCode();
                if (code >= 400) {
                    assertTrue(code < 500 && answer.getContentAsString().contains(id), "round " + round + ": the refused"
                            + " submission gets a 4xx naming " + id + ", got " + code + ": " + excerpt(answer.getContentAsString()));
                    refused++;
                }
            }
            assertEquals(1, refused, "round " + round + ": exactly one of the two submissions is refused as identical");
            assertEquals(1, NotificationCapture.afterQuietPeriod(NotificationEvent.REQUEST_CREATED, id).size(),
                    "round " + round + ": exactly one REQUEST_CREATED for " + id + ": " + NotificationCapture.describeAll());
            assertEquals(2 + round, NotificationCapture.matching(c -> c.event == NotificationEvent.REQUEST_CREATED).size(),
                    "round " + round + ": no second REQUEST_CREATED in all: " + NotificationCapture.describeAll());
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Two submissions by u1, each from its own session, released by one latch; returns both answers. */
    private List<WebResponse> race(String firstReason, String secondReason) throws Exception {
        JenkinsRule.WebClient first = ApproverFormFixtures.client(j, "u1");
        JenkinsRule.WebClient second = ApproverFormFixtures.client(j, "u1");
        WebRequest one = request(first, firstReason);
        WebRequest two = request(second, secondReason);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<WebResponse> a = pool.submit(() -> {
                start.await();
                return first.getPage(one).getWebResponse();
            });
            Future<WebResponse> b = pool.submit(() -> {
                start.await();
                return second.getPage(two).getWebResponse();
            });
            start.countDown();
            List<WebResponse> out = new ArrayList<>();
            out.add(a.get(60, TimeUnit.SECONDS));
            out.add(b.get(60, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** The Request Run form's multipart POST (as T-05-204), sent as a script. */
    private WebRequest request(JenkinsRule.WebClient wc, String reason) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("reason", reason));
        params.addAll(approverPairs("a1"));
        params.add(new NameValuePair("name", "DATE"));
        params.add(new NameValuePair("value", DATE));
        JSONObject json = new JSONObject();
        json.put("reason", reason);
        json.put("approvers", JSONArray.fromObject(new String[] {"a1"}));
        JSONArray parameters = new JSONArray();
        parameters.add(new JSONObject().element("name", "DATE").element("value", DATE));
        json.put("parameter", parameters);
        params.add(new NameValuePair("json", json.toString()));
        WebRequest request = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "batch-control/submit"), HttpMethod.POST);
        request.setEncodingType(FormEncodingType.MULTIPART);
        request.setCharset(StandardCharsets.UTF_8);
        request.setRequestParameters(params);
        request.setAdditionalHeader("Accept", SCRIPT_ACCEPT);
        return request;
    }
}
