package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import jenkins.model.Jenkins;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The ended lists of the run requests and grants pages show the newest request first (issue #31, note
 * 370, gap X-08, retired drivers browser/b4.mjs #8 and browser/audit/b4.mjs #10). Matrix row T-UI-306.
 *
 * <p>Basis: D-66 lists pending requests first, then active and ended items (SPEC 8, T-UI-95/96); no SPEC
 * sentence orders the rows inside one list. The retired drivers required newest first when the page was
 * one table (before D-66). The row keeps that screen contract for the ended lists. It asserts no order
 * for the pending lists: they list the oldest request first today, which contradicts the retired
 * contract, and SPEC does not decide it (reported as a question, note 370). T-UI-96 cannot tell either
 * apart: its two requests sit in different lists.
 *
 * <p>Time is the plugin clock ({@link BatchClock}), moved one minute per request; nothing waits in real
 * time. Four rows per list make an accidental order (for example by random id) pass with a chance of
 * 1 in 24 per list.
 *
 * <p>Written from docs/SPEC.md item 8 (D-66), docs/DECISIONS.md D-66 and docs/TEST-MATRIX.md (T-UI-95,
 * T-UI-96); the retired drivers were read only to learn what each looked at. No src/main knowledge.
 */
@WithJenkins
public class RequestListOrderTest {

    private static final Instant T0 = Instant.parse("2026-09-20T09:00:00Z");

    private JenkinsRule j;
    private FreeStyleProject job;
    private int minute;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-UI-306 (D-66 lists; screen contract of the retired drivers; #31 gap X-08): with the plugin clock
     * moved one minute before each step, u1 files run requests R1..R4 (PENDING) and then R5..R8, which a1
     * rejects in the same order; likewise grant requests G1..G4 (PENDING) and G5..G8 (rejected in order).
     * On admin's {@code batch-control/requests/} the ended list ({@code data-batch-control-list=ended})
     * links R8, R7, R6, R5 in that order; on {@code batch-control/grants/} the ended list links G8, G7, G6,
     * G5. Each ended list holds all four, and each pending list holds its four pending requests (order not
     * asserted, see the class comment), so a request in the wrong list fails too.
     */
    @Test
    public void t_ui_306_endedRequestListsShowTheNewestRequestFirst() throws Exception {
        List<String> pendingRuns = new ArrayList<>();
        List<String> endedRuns = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            pendingRuns.add(runRequest("pending run " + i));
        }
        for (int i = 5; i <= 8; i++) {
            endedRuns.add(runRequest("ended run " + i));
        }
        for (String id : endedRuns) {
            tick();
            as("a1", () -> {
                RunRequestService.get().reject(id, "not this month");
                return null;
            });
        }

        List<String> pendingGrants = new ArrayList<>();
        List<String> endedGrants = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            pendingGrants.add(grantRequest("pending grant " + i));
        }
        for (int i = 5; i <= 8; i++) {
            endedGrants.add(grantRequest("ended grant " + i));
        }
        for (String id : endedGrants) {
            tick();
            as("a1", () -> {
                GrantRequestService.get().reject(id, "not now");
                return null;
            });
        }

        HtmlPage runs = UsabilityFixtures.htmlPage(j, "admin", "batch-control/requests/");
        assertEquals(new java.util.TreeSet<>(pendingRuns), new java.util.TreeSet<>(idsInList(runs, "pending", pendingRuns)),
                "premise: the pending list holds R1..R4");
        assertEquals(reversed(endedRuns), idsInList(runs, "ended", endedRuns),
                "the ended run requests must be listed newest first (R8, R7, R6, R5)");

        HtmlPage grants = UsabilityFixtures.htmlPage(j, "admin", "batch-control/grants/");
        assertEquals(new java.util.TreeSet<>(pendingGrants), new java.util.TreeSet<>(idsInList(grants, "pending", pendingGrants)),
                "premise: the pending list holds G1..G4");
        assertEquals(reversed(endedGrants), idsInList(grants, "ended", endedGrants),
                "the ended grant requests must be listed newest first (G8, G7, G6, G5)");
    }

    // ---------------------------------------------------------------- helpers

    private void tick() {
        minute++;
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(minute)), ZoneOffset.UTC));
    }

    private String runRequest(String reason) throws Exception {
        tick();
        return as("u1", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), reason, "a1")).getId();
    }

    private String grantRequest(String reason) throws Exception {
        tick();
        return as("u1", () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "batch-x"),
                Arrays.asList(GrantAction.CONFIGURE), 30, reason, "a1")).getId();
    }

    private static List<String> reversed(List<String> ids) {
        List<String> out = new ArrayList<>(ids);
        java.util.Collections.reverse(out);
        return out;
    }

    /** Ids of {@code candidates} linked from rows of {@code table[data-batch-control-list=<list>]}, in page order. */
    private static List<String> idsInList(HtmlPage page, String list, List<String> candidates) throws Exception {
        List<String> out = new ArrayList<>();
        for (DomNode t : page.querySelectorAll("table[data-batch-control-list=" + list + "]")) {
            for (DomNode a : t.querySelectorAll("a[href]")) {
                String path = page.getFullyQualifiedUrl(((DomElement) a).getAttribute("href")).getPath();
                for (String id : candidates) {
                    if (path.contains(id) && !out.contains(id)) {
                        out.add(id);
                    }
                }
            }
        }
        return out;
    }

    private static <T> T as(String id, Callable<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(id, true).impersonate2())) {
            return body.call();
        }
    }
}
