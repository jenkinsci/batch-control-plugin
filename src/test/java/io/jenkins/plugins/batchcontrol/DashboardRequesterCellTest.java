package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Run Dashboard's User cell of an approved run names the requester (issue #31, retired legacy drivers
 * fresh/s5-history.mjs #7 and fresh/v/vd-screens.mjs #4, gap X-52). Matrix row T-10-16 (note 371).
 *
 * <p>Basis: SPEC 10 ("모든 잡의 실행을 잡, 실행 원인, 사용자, 파라미터, 결과, 소요 시간과 함께 한 화면에서 봅니다";
 * "승인 건과 실행 결과가 한 줄로 이어집니다") and docs/LIMITATIONS.md 42 (an approved run carries the requester as
 * its user). T-10-11 and T-12-13 read History and runs.csv; RunTableColumnsTest reads the dashboard's columns
 * and cell layout, not the User cell's text.
 *
 * <p>Users: {@code u1} (Item/Read, Item/Build, BatchControl/Request), {@code a1} approver, {@code admin}.
 * {@code cron-x} is created before run control is turned on (D-45, activated as uncontrolled); then run
 * control is turned on and {@code batch-x} (approval required) is created.
 *
 * <p>Written from docs/SPEC.md item 10, docs/DECISIONS.md D-45 and docs/TEST-MATRIX.md (T-10-11, T-12-13,
 * T-UI-276); the retired drivers were read only to learn what they looked at. No src/main knowledge.
 */
@WithJenkins
public class DashboardRequesterCellTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
    }

    /**
     * T-10-16 (SPEC 10, LIMITATIONS 42; #31 gap X-52): u1's request for {@code batch-x}, approved by a1, ran
     * as #1; {@code cron-x} ran #1 from its timer. On the administrator's {@code batch-control/dashboard/},
     * the User cell (the column headed User) of the {@code batch-x #1} row reads exactly {@code u1} (not the
     * approver a1). Guard: the User cell of the {@code cron-x #1} row (a timer run nobody requested) is not
     * {@code u1}, so a dashboard that writes the requester into every row fails.
     */
    @Test
    public void t_10_16_dashboardUserCellOfAnApprovedRunNamesTheRequester() throws Exception {
        FreeStyleProject cron = j.createFreeStyleProject("cron-x");
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        assertTrue(ActivationService.get().isActivated(cron), "premise (D-45): cron-x, created before run control, is activated");
        j.assertBuildStatusSuccess(cron.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));

        FreeStyleProject batch = j.createFreeStyleProject("batch-x");
        setBatchControl(batch, new BatchControlJobProperty(true));
        String id = submitRunOk(j, "u1", batch, "month-end batch", "a1");
        assertSuccess(decideRun(j, "a1", id, "approve", "ok"), "fixture: a1 approves u1's request");
        j.waitUntilNoActivity();
        assertNotNull(batch.getBuildByNumber(1), "premise: the approved request ran as batch-x #1");

        HtmlPage page = dashboard();
        assertEquals("u1", userCell(page, batch, 1), "SPEC 10 / LIMITATIONS 42: the dashboard's User cell of the approved run"
                + " names the requester u1");
        assertNotEquals("u1", userCell(page, cron, 1), "guard: the timer run's User cell does not name u1");
    }

    // ---------------------------------------------------------------- helpers

    private HtmlPage dashboard() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        wc.getOptions().setJavaScriptEnabled(false);
        Page answer = wc.getPage(new WebRequest(new URL(j.getURL(), "batch-control/dashboard/"), HttpMethod.GET));
        assertEquals(200, answer.getWebResponse().getStatusCode(), "fixture: admin opens the dashboard");
        return (HtmlPage) answer;
    }

    /** The trimmed text of the User cell in the row linking to run {@code number} of {@code job}. */
    private String userCell(HtmlPage page, Job<?, ?> job, int number) throws Exception {
        String run = new URL(j.getURL(), job.getUrl()).getPath() + number + "/";
        DomElement row = null;
        for (DomNode n : page.querySelectorAll("#main-panel table tbody tr")) {
            for (DomNode a : ((DomElement) n).querySelectorAll("a")) {
                String href = ((DomElement) a).getAttribute("href");
                if (!href.isEmpty() && !href.startsWith("#") && page.getFullyQualifiedUrl(href).getPath().equals(run)) {
                    row = (DomElement) n;
                }
            }
        }
        assertNotNull(row, "fixture: the dashboard lists " + job.getFullName() + " #" + number + ": "
                + excerpt(page.asNormalizedText()));
        DomElement table = row;
        while (!"table".equals(table.getTagName())) {
            table = (DomElement) table.getParentNode();
        }
        List<String> headers = new ArrayList<>();
        for (DomNode th : table.querySelectorAll("thead th")) {
            headers.add(th.asNormalizedText().trim());
        }
        int index = headers.indexOf("User");
        assertTrue(index >= 0, "fixture: the dashboard's run table has a User column: " + headers);
        List<DomElement> cells = new ArrayList<>();
        for (DomElement child : row.getChildElements()) {
            if ("td".equals(child.getTagName()) || "th".equals(child.getTagName())) {
                cells.add(child);
            }
        }
        assertEquals(headers.size(), cells.size(), "fixture: the row has one cell per column: " + row.asXml());
        return cells.get(index).asNormalizedText().trim();
    }
}
