package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.UnprotectedRootAction;
import hudson.model.User;
import hudson.scm.NullSCM;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.action.JobActivationForm;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import jenkins.branch.BranchSource;
import jenkins.model.Jenkins;
import jenkins.model.experimentalflags.NewJobPageUserExperimentalFlag;
import jenkins.model.experimentalflags.UserExperimentalFlagsProperty;
import jenkins.scm.impl.SingleSCMSource;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #153: on a job's activation page, a job whose activation is carried by a folder shows
 * "Carried by &lt;folder name&gt;"; for a viewer who may read the folder the name is a link to the
 * folder, while a viewer who may not read the folder still sees "Managed by an item you cannot
 * see" and no link.
 *
 * <p>The carrier is a multibranch project (a computed folder); the page under test is its
 * branch child's activation form ({@code job/ctl-mb/job/main/batch-control-activation/}), whose
 * carrier is the project.
 */
@WithJenkins
public class ActivationCarrierLinkTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    private WorkflowJob branchOf(WorkflowMultiBranchProject mb) throws Exception {
        mb.getSourcesList().add(new BranchSource(new SingleSCMSource("main", new NullSCM())));
        var indexing = mb.scheduleBuild2(0);
        assertNotNull(indexing, "branch indexing must be schedulable");
        indexing.getFuture().get();
        j.waitUntilNoActivity();
        WorkflowJob branch = mb.getItem("main");
        assertNotNull(branch, "indexing must have created the branch child job");
        return branch;
    }

    private HtmlPage activationPage(String user, Job<?, ?> item) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        String path = item.getUrl() + "batch-control-activation/";
        HtmlPage page = (HtmlPage) wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET));
        assertEquals(200, page.getWebResponse().getStatusCode(), user + " must reach " + path);
        return page;
    }

    /** The "Carried by" / "Managed by" cell of the state table. */
    private static String stateCellText(HtmlPage page) {
        List<?> cells = page.getByXPath("//th[contains(., 'State')]/following-sibling::td[1]");
        assertEquals(1, cells.size(), "the state row must have one cell: " + page.asXml());
        return ((org.htmlunit.html.HtmlElement) cells.get(0)).getTextContent().trim();
    }

    @Test
    public void t_153a_viewerWhoMayReadTheFolderGetsALinkToIt() throws Exception {
        WorkflowMultiBranchProject mb = j.jenkins.createProject(WorkflowMultiBranchProject.class, "ctl-mb");
        WorkflowJob branch = branchOf(mb);
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1"));

        HtmlPage page = activationPage("u1", branch);
        List<?> anchors = page.getByXPath("//th[contains(., 'State')]/following-sibling::td[1]//a[@href]");
        assertEquals(1, anchors.size(), "the carrier name must be the one link in the state cell: " + stateCellText(page));
        HtmlAnchor link = (HtmlAnchor) anchors.get(0);
        assertTrue(link.getTextContent().contains("ctl-mb"), "the link must name the carrier: " + link.getTextContent());
        assertTrue(link.getHrefAttribute().endsWith("job/ctl-mb/"),
                "the link must point at the carrier folder, not the job: " + link.getHrefAttribute());
        assertTrue(stateCellText(page).startsWith("Carried by"), "the readable branch says 'Carried by': " + stateCellText(page));
    }

    @Test
    public void t_153b_viewerWhoMayNotReadTheFolderSeesNoLink() throws Exception {
        WorkflowMultiBranchProject mb = j.jenkins.createProject(WorkflowMultiBranchProject.class, "ctl-mb");
        WorkflowJob branch = branchOf(mb);
        // u2 may read and request on the branch job itself, but holds nothing on the folder.
        // Core 404s URL resolution through an unreadable ancestor, so the form is reached
        // through the test-only exposer (as in ViewPermissionCheckTest), which hands Stapler
        // the action directly; the view then renders as u2 and its own permission check applies.
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ).everywhere().to("u2")
                .grant(Item.READ, BatchControlPermissions.REQUEST).onItems(branch).to("u2"));

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("u2");
        // The new-job-page branch checks the view permission against the job itself (see
        // index.jelly), so the form renders for u2 even though the classic layout would check
        // the exposer stack's ancestor instead.
        setNewJobPage("u2", true);
        String path = "bc-153/activation/ctl-mb%2Fmain/";
        HtmlPage page = (HtmlPage) wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET));
        assertEquals(200, page.getWebResponse().getStatusCode(), "u2 must reach the form through the exposer: " + path);
        assertTrue(stateCellText(page).contains("Managed by an item you cannot see"),
                "the non-readable branch must say so: " + stateCellText(page));
        List<?> anchors = page.getByXPath("//th[contains(., 'State')]/following-sibling::td[1]//a[@href]");
        assertTrue(anchors.isEmpty(), "no link may be rendered when the carrier is not readable: " + stateCellText(page));
        assertFalse(page.asXml().contains("job/ctl-mb/"), "the page must not leak the carrier's URL");
    }

    // ---------------------------------------------------------------- helpers

    /** Sets core's per-user new job page flag and checks that core reads it back. */
    private static void setNewJobPage(String userId, boolean on) throws Exception {
        NewJobPageUserExperimentalFlag flag = ExtensionList.lookupSingleton(NewJobPageUserExperimentalFlag.class);
        User user = User.getById(userId, true);
        user.addProperty(new UserExperimentalFlagsProperty(Map.of(flag.getFlagKey(), Boolean.toString(on))));
        assertEquals(on, flag.getFlagValue(user), "premise: core must read " + userId + "'s new job page flag as " + on);
    }

    // ---------------------------------------------------------------- exposer

    /** Test-only root action handing the activation form of a named job to Stapler as SYSTEM, so a
     *  viewer who cannot resolve the job's ancestors through the real URL can still reach the view. */
    @TestExtension
    public static class Exposer implements UnprotectedRootAction {
        @Override
        public String getIconFileName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getUrlName() {
            return "bc-153";
        }

        public Object getDynamic(String token) {
            if ("activation".equals(token)) {
                return new Picker(name -> asSystem(() -> {
                    Job<?, ?> target = Jenkins.get().getItemByFullName(name, Job.class);
                    return target == null ? null : target.getAction(JobActivationForm.class);
                }));
            }
            return null;
        }

        private static Object asSystem(Supplier<Object> lookup) {
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                return lookup.get();
            }
        }
    }

    /** One more URL segment: {@code <name>/} resolves through {@code lookup}. */
    public static final class Picker {
        private final Function<String, Object> lookup;

        Picker(Function<String, Object> lookup) {
            this.lookup = lookup;
        }

        public Object getDynamic(String name) {
            return lookup.apply(name);
        }
    }
}
