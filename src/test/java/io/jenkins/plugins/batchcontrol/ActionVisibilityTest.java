package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 2, the two hosting-review acceptance lines (#31): a Batch Control action is
 * <em>absent</em> - not listed, and 404 - for a user who may not use it, and a link inside the
 * Batch Control screens is shown only to a user who may open its target. Matrix rows T-02-07,
 * T-02-08, T-02-09.
 *
 * <p>Frozen names: root URL {@code batch-control}, history screen {@code batch-control/history/},
 * per-job action {@code job/<name>/batch-control/}.
 *
 * <p>Every "hidden / 404" assertion is paired with a user for whom the same link is present and
 * the same URL answers 200, so a build that simply removed the action would fail the row.
 *
 * <p>Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ActionVisibilityTest {

    private static final String ROOT = "batch-control";
    private static final String HISTORY = "batch-control/history";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                // Overall/Read + Item/Read, and no Batch Control permission at all
                .grant(Jenkins.READ, Item.READ).everywhere().to("plain")
                // Request (no ViewHistory). Item/Build too: a run request needs it (D-38), and under
                // the SPEC section 6 usability line (e2e-03 DEF-12, T-05-19) the per-job Request Run
                // link is shown only to a user who can use it, so T-02-09's positive case needs it.
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                // ViewHistory only (no Request)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        assertTrue(BatchControlGlobalConfiguration.get().isRunControlEnabled(), "fixture: run control must be on");
    }

    /**
     * T-02-07 (#31 root action): a user with Overall/Read and no Batch Control permission does not
     * see the Batch Control link on the Jenkins home page and GET /batch-control/ answers 404; a
     * user holding only BatchControl/Request sees the link and gets 200.
     */
    @Test
    public void t_02_07_rootActionIsAbsentForAUserWithoutAnyBatchControlPermission() throws Exception {
        // guard first: the link and the page exist for a Request holder, so the negative half
        // below cannot pass merely because the action is gone for everyone
        HtmlPage requesterHome = page("u1", "");
        assertEquals(200, requesterHome.getWebResponse().getStatusCode(), "fixture: u1 must be able to open the home page");
        assertFalse(linksTo(requesterHome, ROOT).isEmpty(),
                "a BatchControl/Request holder must see the Batch Control link on the home page; anchors: "
                        + hrefs(requesterHome));
        assertEquals(200, status("u1", ROOT + "/"), "a BatchControl/Request holder must get 200 at /batch-control/");

        HtmlPage plainHome = page("plain", "");
        assertEquals(200, plainHome.getWebResponse().getStatusCode(),
                "fixture: a user with Overall/Read must be able to open the home page, or the absence below measures nothing");
        List<String> plainLinks = linksTo(plainHome, ROOT);
        assertTrue(plainLinks.isEmpty(),
                "a user with no Batch Control permission must not see the Batch Control link; found " + plainLinks);
        assertEquals(404, status("plain", ROOT + "/"),
                "a user with no Batch Control permission must get 404 (absent, not refused) at /batch-control/");
    }

    /**
     * T-02-08 (#31 links inside the screens): on /batch-control/ a user holding Request but not
     * ViewHistory is not shown a link to the history screen (which answers 403 to them), while a
     * ViewHistory holder is shown it and may open it.
     */
    @Test
    public void t_02_08_historyLinkIsShownOnlyToAViewHistoryHolder() throws Exception {
        // guard: the link exists on the Batch Control page for someone who may open it
        HtmlPage viewerPage = page("viewer", ROOT + "/");
        assertEquals(200, viewerPage.getWebResponse().getStatusCode(), "a ViewHistory holder must get 200 at /batch-control/");
        assertFalse(linksTo(viewerPage, HISTORY).isEmpty(),
                "a ViewHistory holder must be shown the history link on /batch-control/; anchors: " + hrefs(viewerPage));
        assertEquals(200, status("viewer", HISTORY + "/"), "the history link's target must open for a ViewHistory holder");

        // premise: u1 really may not open the target (SPEC item 12: without ViewHistory, 403)
        assertEquals(403, status("u1", HISTORY + "/"), "fixture: the history screen must be refused to a user without ViewHistory");

        HtmlPage requesterPage = page("u1", ROOT + "/");
        assertEquals(200, requesterPage.getWebResponse().getStatusCode(),
                "a BatchControl/Request holder must get 200 at /batch-control/, or the absence below measures nothing");
        List<String> historyLinks = linksTo(requesterPage, HISTORY);
        assertTrue(historyLinks.isEmpty(),
                "a user without ViewHistory must not be shown a link to the history screen; found " + historyLinks);
    }

    /**
     * T-02-09 (#31 per-job action): for an approval-required job with run control on, a user with
     * Item/Read but without BatchControl/Request is not shown the job's Batch Control link and
     * /job/&lt;name&gt;/batch-control/ answers 404; a Request holder is shown it and gets 200.
     */
    @Test
    public void t_02_09_perJobActionIsAbsentForAUserWithoutRequest() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("batch-x");
        BatchControlJobProperty property = BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
        assertTrue(property.isApprovalRequired(), "fixture: the job must require approval");
        String jobPath = job.getUrl();              // job/batch-x/
        String action = jobPath + ROOT;             // job/batch-x/batch-control

        // guard: the per-job action is there for a requester
        HtmlPage requesterJob = page("u1", jobPath);
        assertEquals(200, requesterJob.getWebResponse().getStatusCode(), "fixture: u1 must be able to open the job page");
        assertFalse(linksTo(requesterJob, action).isEmpty(),
                "a Request holder must see the job's Batch Control link; anchors: " + hrefs(requesterJob));
        assertEquals(200, status("u1", action + "/"), "a Request holder must get 200 at /" + action + "/");

        HtmlPage plainJob = page("plain", jobPath);
        assertEquals(200, plainJob.getWebResponse().getStatusCode(),
                "fixture: an Item/Read holder must be able to open the job page, or the absence below measures nothing");
        List<String> plainLinks = linksTo(plainJob, action);
        assertTrue(plainLinks.isEmpty(),
                "a user without BatchControl/Request must not see the job's Batch Control link; found " + plainLinks);
        assertEquals(404, status("plain", action + "/"),
                "a user without BatchControl/Request must get 404 (absent, not refused) at /" + action + "/");
    }

    /**
     * T-02-196 (#31 per-job action, SPEC 2 "absent, not merely refused, for a user who may not use
     * it ... its URL and every URL beneath it (for example submit) answer 404"): a1 holds
     * BatchControl/Approve and Item/Read but not Request. The job page lists no Batch Control link
     * for a1, the form answers 404 and a complete, crumbed POST to {@code submit} answers 404,
     * creating no run request and starting nothing. Guards: a1 reaches the Batch Control root page
     * (holding a Batch Control permission is not what keeps the action away), and u1's identical
     * submission creates exactly one request.
     */
    @Test
    public void t_02_196_perJobActionIsAbsentForAnApproverWithoutRequest() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("a1", "admin"));
        cfg.save();
        FreeStyleProject job = j.createFreeStyleProject("batch-y");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
        String jobPath = job.getUrl();
        String action = jobPath + ROOT;

        assertEquals(200, status("a1", ROOT + "/"), "guard: an Approve holder reaches /batch-control/");
        Set<String> before = ApproverFormFixtures.runRequestIds();
        ApproverFormFixtures.assertSuccess(ApproverFormFixtures.submitRun(j, "u1", job, "guard: the requester submits", "admin"),
                "guard: u1's submission");
        assertEquals(before.size() + 1, ApproverFormFixtures.runRequestIds().size(), "guard: u1's submission creates one request");

        HtmlPage approverJob = page("a1", jobPath);
        assertEquals(200, approverJob.getWebResponse().getStatusCode(), "fixture: a1 must be able to open the job page");
        assertTrue(linksTo(approverJob, action).isEmpty(), "an approver without Request must not see the job's Batch Control link; found "
                + linksTo(approverJob, action));
        assertEquals(404, status("a1", action + "/"), "an approver without Request must get 404 (absent, not refused) at /" + action + "/");

        Set<String> afterGuard = ApproverFormFixtures.runRequestIds();
        WebResponse submit = ApproverFormFixtures.submitRun(j, "a1", job, "an approver tries to request a run", "admin");
        assertEquals(404, submit.getStatusCode(), "a crumbed POST to /" + action + "/submit by an approver without Request must answer"
                + " 404 (absent, not refused): " + ApproverFormFixtures.excerpt(submit.getContentAsString()));
        assertEquals(afterGuard, ApproverFormFixtures.runRequestIds(), "the approver's submission must create no run request");
        assertEquals(0, j.jenkins.getQueue().getItems().length, "nothing may be queued");
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "nothing may have run");
        assertEquals(1, job.getNextBuildNumber(), "the next build number must not move");
    }

    // ---------------------------------------------------------------- helpers

    private JenkinsRule.WebClient client(String user) throws Exception {
        return j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
    }

    private HtmlPage page(String user, String path) throws Exception {
        Page p = client(user).getPage(new URL(j.getURL(), path));
        assertTrue(p instanceof HtmlPage, user + " GET /" + path + " must render HTML, got "
                + p.getWebResponse().getStatusCode() + " " + p.getWebResponse().getContentType());
        return (HtmlPage) p;
    }

    private int status(String user, String path) throws Exception {
        return client(user).getPage(new URL(j.getURL(), path)).getWebResponse().getStatusCode();
    }

    /**
     * Every link on the page whose resolved target is exactly {@code <root>/<path>} (with or
     * without a trailing slash). Read from the raw markup, not only from {@code <a>} elements:
     * the Jenkins header renders root actions as dropdown items inside {@code <template>}
     * elements ({@code data-dropdown-href}), which HtmlUnit does not expose as anchors.
     */
    private List<String> linksTo(HtmlPage page, String path) throws Exception {
        String target = new URL(j.getURL(), path).toString();
        List<String> found = new ArrayList<>();
        for (String href : linkTargets(page)) {
            String resolved = resolve(page, href);
            if (resolved != null && strip(resolved).equals(target)) {
                found.add(resolved);
            }
        }
        return found;
    }

    private static final Pattern LINK = Pattern.compile(
            "(?:\\bhref|data-dropdown-href)=(?:\"|&quot;)(.*?)(?:\"|&quot;)");

    private static List<String> linkTargets(HtmlPage page) {
        List<String> out = new ArrayList<>();
        Matcher m = LINK.matcher(page.getWebResponse().getContentAsString());
        while (m.find()) {
            out.add(m.group(1).replace("&amp;", "&"));
        }
        return out;
    }

    private static String resolve(HtmlPage page, String href) {
        if (href == null || href.isEmpty() || href.startsWith("javascript:")) {
            return null;
        }
        try {
            return page.getFullyQualifiedUrl(href).toString();
        } catch (java.net.MalformedURLException e) {
            return null;
        }
    }

    private static String strip(String url) {
        int cut = url.length();
        for (char c : new char[] {'?', '#'}) {
            int i = url.indexOf(c);
            if (i >= 0 && i < cut) {
                cut = i;
            }
        }
        String s = url.substring(0, cut);
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static List<String> hrefs(HtmlPage page) {
        return linkTargets(page);
    }
}
