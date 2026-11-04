package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.AbstractPasswordBasedSecurityRealm;
import hudson.security.GroupDetails;
import hudson.security.SecurityRealm;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.ops.BatchControlNotifier;
import io.jenkins.plugins.batchcontrol.ops.Notification;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.model.Jenkins;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backlog #75 (security-33 S-33-09), SPEC item 5 / D-38a: the request page's notice
 * {@value RequesterBuildNoticeTest#NOTICE} stays correct across views, and repeated views of the
 * same request page do not each look the requester up in the security realm (one LDAP query per
 * view in production). The answer is cached per request for 5 minutes on the plugin clock and
 * dropped when the request is saved (core-dev, backlog #75). Matrix rows T-05-36..39 (note 211).
 *
 * <p>Observation: a test security realm counts {@code loadUserByUsername2} calls per user name
 * (impersonating a user goes through it). Only lookups of the requester {@code nb} are counted, and
 * only after the REQUEST_CREATED notification was handed to the notifiers, so asynchronous
 * notification work cannot add to the count.
 *
 * <p>Written from docs/SPEC.md item 5, docs/DECISIONS.md D-38a, issue #75 and docs/TEST-MATRIX.md
 * only (no src/main knowledge).
 */
@WithJenkins
public class RequesterBuildNoticeCacheTest {

    static final String NOTICE = RequesterBuildNoticeTest.NOTICE;
    private static final int VIEWS = 5;

    private JenkinsRule j;
    private FreeStyleProject job;

    @TestExtension
    public static class CapturingNotifier extends BatchControlNotifier {
        @Override
        public void notify(NotificationEvent event, Notification notification) {
            NotificationCapture.record(event, notification);
        }
    }

    /** Password = user name; counts realm lookups per user name. */
    public static class CountingRealm extends AbstractPasswordBasedSecurityRealm {
        static final Map<String, AtomicInteger> LOOKUPS = new ConcurrentHashMap<>();

        @Override
        protected UserDetails authenticate2(String username, String password) throws AuthenticationException {
            if (!username.equals(password)) {
                throw new org.springframework.security.authentication.BadCredentialsException(username);
            }
            return details(username);
        }

        @Override
        public UserDetails loadUserByUsername2(String username) throws UsernameNotFoundException {
            LOOKUPS.computeIfAbsent(username, k -> new AtomicInteger()).incrementAndGet();
            return details(username);
        }

        @Override
        public GroupDetails loadGroupByGroupname2(String groupname, boolean fetchMembers) throws UsernameNotFoundException {
            throw new UsernameNotFoundException(groupname);
        }

        private static UserDetails details(String username) {
            return new org.springframework.security.core.userdetails.User(username, "", true, true, true, true,
                    List.of(SecurityRealm.AUTHENTICATED_AUTHORITY2));
        }

        static int lookups(String username) {
            AtomicInteger n = LOOKUPS.get(username);
            return n == null ? 0 : n.get();
        }
    }

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        NotificationCapture.clear();
        CountingRealm.LOOKUPS.clear();
        j.jenkins.setSecurityRealm(new CountingRealm());
        j.jenkins.setAuthorizationStrategy(strategy(false));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
        assertFalse(can("nb", Item.BUILD), "fixture: nb must NOT hold Item/Build on batch-x");
    }

    @AfterEach
    public void tearDown() {
        NotificationCapture.clear();
        CountingRealm.LOOKUPS.clear();
    }

    /**
     * T-05-36 (#75): a1 opens the detail page of nb's request (nb lacks Item/Build) {@value #VIEWS}
     * times in one session; every view shows the notice, and the views together look nb up in the
     * realm at most once.
     */
    @Test
    public void t_05_36_repeatedViewsShowTheNoticeWithoutARealmLookupEach() throws Exception {
        String id = submitRunOk(j, "nb", job, "month-end batch", "a1");
        NotificationCapture.await(NotificationEvent.REQUEST_CREATED, id); // notification work is done

        JenkinsRule.WebClient wc = client();
        int before = CountingRealm.lookups("nb");
        for (int i = 1; i <= VIEWS; i++) {
            String text = detailText(wc, id);
            assertTrue(text.contains(NOTICE), "view " + i + " must show \"" + NOTICE + "\": " + excerpt(text));
        }
        int during = CountingRealm.lookups("nb") - before;
        assertTrue(during <= 1, VIEWS + " views of the same request page must look the requester up at most once,"
                + " got " + during + " realm lookups of nb");
    }

    /**
     * T-05-37 (#75, correctness guard): the notice on nb's first request is shown; nb then gains
     * Item/Build and files a second request, whose detail page does not show the notice.
     */
    @Test
    public void t_05_37_newRequestAfterGainingBuildHasNoNotice() throws Exception {
        String first = submitRunOk(j, "nb", job, "month-end batch", "a1");
        JenkinsRule.WebClient wc = client();
        assertTrue(detailText(wc, first).contains(NOTICE), "premise: the first request shows the notice");

        j.jenkins.setAuthorizationStrategy(strategy(true));
        assertTrue(can("nb", Item.BUILD), "premise: nb now holds Item/Build on batch-x");
        String second = submitRunOk(j, "nb", job, "second batch", "a1");

        String text = detailText(wc, second);
        assertFalse(text.contains("does not have Build permission"),
                "the new request of a requester who now holds Item/Build must not show the notice: " + excerpt(text));
    }

    /**
     * T-05-38 (#75, cache dropped on save): nb's request shows the notice; nb gains Item/Build and
     * re-designates the request (a1 to a2, which saves it); the same request's page no longer shows
     * the notice. Guard: the page before the gain shows it.
     */
    @Test
    public void t_05_38_savingTheRequestReevaluatesTheNotice() throws Exception {
        String id = submitRunOk(j, "nb", job, "month-end batch", "a1");
        JenkinsRule.WebClient wc = client();
        assertTrue(detailText(wc, id).contains(NOTICE), "premise: the request shows the notice");

        j.jenkins.setAuthorizationStrategy(strategy(true));
        assertTrue(can("nb", Item.BUILD), "premise: nb now holds Item/Build on batch-x");
        ApproverFormFixtures.assertSuccess(ApproverFormFixtures.changeRunApprovers(j, "nb", id, "a1", "a2"),
                "nb re-designating the own request");

        String text = detailText(wc, id);
        assertFalse(text.contains("does not have Build permission"),
                "after the request was saved the notice must be re-evaluated (nb now holds Item/Build): " + excerpt(text));
    }

    /**
     * T-05-207 (#75, SPEC 5 D-38a; #31, retired driver r9/s75.py): the notice as seen by an approver
     * added by a re-designation. Guard first: nb (no Item/Build) files a request to a1 and adds a2 to
     * its approvers; a2's first and second view of it both show the notice. Then nb's other request
     * shows the notice to a1 (premise), nb gains Item/Build and adds a2 to that request too (a save);
     * a2's first view and second, cached view of it both show no notice. What fails it: an answer
     * cached before the save that the approver change does not drop, and Build evaluated as the
     * viewer (a2) instead of the requester. A cache keyed per viewer does not fail it (a2 has no entry
     * before the save, so its first view is fresh); T-05-38 catches that.
     */
    @Test
    public void t_05_207_newlyDesignatedApproverSeesTheReevaluatedNoticeOnEveryView() throws Exception {
        JenkinsRule.WebClient a2 = client("a2");

        String lacking = submitRunOk(j, "nb", job, "guard batch", "a1");
        ApproverFormFixtures.assertSuccess(ApproverFormFixtures.changeRunApprovers(j, "nb", lacking, "a1", "a2"),
                "guard: nb adds a2 to the approvers of the request");
        for (int view = 1; view <= 2; view++) {
            String text = detailText(a2, lacking);
            assertTrue(text.contains(NOTICE), "guard: view " + view + " by the newly designated a2 must show the notice while nb lacks"
                    + " Item/Build: " + excerpt(text));
        }

        String id = submitRunOk(j, "nb", job, "month-end batch", "a1");
        assertTrue(detailText(client(), id).contains(NOTICE), "premise: a1 sees the notice on the request");
        j.jenkins.setAuthorizationStrategy(strategy(true));
        assertTrue(can("nb", Item.BUILD), "premise: nb now holds Item/Build on batch-x");
        ApproverFormFixtures.assertSuccess(ApproverFormFixtures.changeRunApprovers(j, "nb", id, "a1", "a2"),
                "nb adding a2 to the approvers of the own request");
        for (int view = 1; view <= 2; view++) {
            String text = detailText(a2, id);
            assertFalse(text.contains("does not have Build permission"), "view " + view + " by the newly designated a2 must not show"
                    + " the notice (nb now holds Item/Build and the request was saved): " + excerpt(text));
        }
    }

    /**
     * T-05-39 (#75, TTL on the plugin clock): with the plugin clock fixed, nb's request shows the
     * notice; nb gains Item/Build and the clock moves 6 minutes on (beyond the 5-minute cache life);
     * the same request's page no longer shows the notice.
     */
    @Test
    public void t_05_39_cacheExpiryReevaluatesTheNotice() throws Exception {
        Instant t0 = Instant.now();
        BatchClock.setForTest(Clock.fixed(t0, ZoneOffset.UTC));
        try {
            String id = submitRunOk(j, "nb", job, "month-end batch", "a1");
            JenkinsRule.WebClient wc = client();
            assertTrue(detailText(wc, id).contains(NOTICE), "premise: the request shows the notice");

            j.jenkins.setAuthorizationStrategy(strategy(true));
            assertTrue(can("nb", Item.BUILD), "premise: nb now holds Item/Build on batch-x");
            BatchClock.setForTest(Clock.fixed(t0.plus(Duration.ofMinutes(6)), ZoneOffset.UTC));

            String text = detailText(wc, id);
            assertFalse(text.contains("does not have Build permission"),
                    "after the cache life the notice must be re-evaluated (nb now holds Item/Build): " + excerpt(text));
        } finally {
            BatchClock.reset();
        }
    }

    /**
     * T-05-90 (R4-4, cache keyed by the request id; reverse of T-05-38/37): nb holds Item/Build
     * and its request shows no notice; nb then loses Item/Build. A new request shows the notice at
     * once (its own cache entry), and after nb re-designates the first request (a save) that
     * request shows it too.
     */
    @Test
    public void t_05_90_losingBuildIsShownAfterResaveAndOnANewRequest() throws Exception {
        j.jenkins.setAuthorizationStrategy(strategy(true));
        assertTrue(can("nb", Item.BUILD), "premise: nb holds Item/Build on batch-x");
        String first = submitRunOk(j, "nb", job, "month-end batch", "a1");
        JenkinsRule.WebClient wc = client();
        assertFalse(detailText(wc, first).contains("does not have Build permission"),
                "premise: no notice while nb holds Item/Build");

        j.jenkins.setAuthorizationStrategy(strategy(false));
        assertFalse(can("nb", Item.BUILD), "premise: nb no longer holds Item/Build on batch-x");
        String second = submitRunOk(j, "nb", job, "second batch", "a1");
        String secondText = detailText(wc, second);
        assertTrue(secondText.contains(NOTICE), "a new request must not reuse another request's answer: " + excerpt(secondText));

        ApproverFormFixtures.assertSuccess(ApproverFormFixtures.changeRunApprovers(j, "nb", first, "a1", "a2"),
                "nb re-designating the first request");
        String firstText = detailText(wc, first);
        assertTrue(firstText.contains(NOTICE),
                "after the re-designation the first request must show the notice (nb lost Item/Build): " + excerpt(firstText));
    }

    // ---------------------------------------------------------------- helpers

    /** a1's client: redirects off, HtmlUnit's response cache off so that every view reaches Jenkins. */
    private JenkinsRule.WebClient client() throws Exception {
        return client("a1");
    }

    /** {@code userId}'s client: redirects off, HtmlUnit's response cache off. */
    private JenkinsRule.WebClient client(String userId) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, userId);
        wc.getCache().setMaxSize(0);
        return wc;
    }

    private static MockAuthorizationStrategy strategy(boolean nbBuilds) {
        MockAuthorizationStrategy s = new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("nb")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2");
        if (nbBuilds) {
            s.grant(Item.BUILD).everywhere().to("nb");
        }
        return s;
    }

    private String detailText(JenkinsRule.WebClient wc, String id) throws Exception {
        org.htmlunit.Page got = wc.getPage(new WebRequest(new java.net.URL(j.getURL(), "batch-control/requests/" + id + "/?view=" + System.nanoTime())));
        WebResponse response = got.getWebResponse();
        assertEquals(200, response.getStatusCode(), "the approver must be able to open the request detail page");
        assertTrue(got instanceof org.htmlunit.html.HtmlPage, "the detail page must be HTML");
        org.htmlunit.html.HtmlPage page = (org.htmlunit.html.HtmlPage) got;
        String text = page.asNormalizedText().replaceAll("\\s+", " ");
        assertTrue(text.contains(id), "premise: the detail page renders request " + id);
        return text;
    }

    private boolean can(String userId, hudson.security.Permission permission) {
        return job.getACL().hasPermission2(User.getById(userId, true).impersonate2(), permission);
    }
}
