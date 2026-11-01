package io.jenkins.plugins.batchcontrol;

import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 2 (permission system). Matrix rows T-02-01, T-02-02, T-02-05.
 * T-02-03 and T-02-04 (admin self-approval) need the approval service and are deferred to slice S2.
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class PermissionsTest {

    private JenkinsRule j;

    @BeforeEach
    void setUpJenkins(JenkinsRule rule) {
        this.j = rule;
    }

    /** T-02-01: a user without BatchControl/Manage cannot save the global configuration (403). */
    @Test
    @Tag("core")
    public void t_02_01_userWithoutManageCannotSaveGlobalConfig() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ,
                        BatchControlPermissions.REQUEST,
                        BatchControlPermissions.APPROVE,
                        BatchControlPermissions.REQUEST_GRANT,
                        BatchControlPermissions.VIEW_HISTORY).everywhere().to("u1"));

        JenkinsRule.WebClient wc = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false)
                .login("u1");
        Page page = wc.getPage(new WebRequest(wc.createCrumbedUrl("configSubmit"), HttpMethod.POST));
        assertEquals(403, page.getWebResponse().getStatusCode(), "a user holding every BatchControl permission except Manage must get 403");
        assertFalse(BatchControlGlobalConfiguration.get().isRunControlEnabled(), "the rejected POST must not have changed any switch");
    }

    /** T-02-02: the five permissions appear as a "Batch Control" group in the Matrix Authorization screen. */
    @Test
    public void t_02_02_matrixScreenShowsBatchControlGroupWithFivePermissions() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        GlobalMatrixAuthorizationStrategy strategy = new GlobalMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        j.jenkins.setAuthorizationStrategy(strategy);

        PermissionGroup group = BatchControlPermissions.GROUP;
        assertEquals("Batch Control", group.title.toString());

        List<Permission> permissions = group.getPermissions();
        Permission[] expected = {
                BatchControlPermissions.REQUEST,
                BatchControlPermissions.APPROVE,
                BatchControlPermissions.REQUEST_GRANT,
                BatchControlPermissions.VIEW_HISTORY,
                BatchControlPermissions.MANAGE,
        };
        String[] expectedNames = {"Request", "Approve", "RequestGrant", "ViewHistory", "Manage"};
        for (int i = 0; i < expected.length; i++) {
            assertTrue(permissions.contains(expected[i]), expectedNames[i] + " must belong to the Batch Control group");
            assertEquals(expectedNames[i], expected[i].name);
            assertTrue(expected[i].getEnabled(), expectedNames[i] + " must be enabled so authorization strategies expose it");
        }

        HtmlPage page = j.createWebClient().login("admin").goTo("configureSecurity");
        // matrix-auth 3.3 renders permission group titles inside collapsed card bodies
        // (<legend class="mas-card__group-title">) and a hidden filter dropdown, so the title is
        // present in the DOM but invisible to HtmlUnit's normalized (visible-only) text. Assert on
        // the raw DOM instead; visible-text verification is covered by the Phase 5 e2e visual check.
        assertTrue(page.getWebResponse().getContentAsString().contains("Batch Control"), "the security configuration screen must expose the Batch Control permission group in its DOM");
    }

    /**
     * T-02-197 (SPEC 2 "the five permissions are exposed as a Batch Control group in the matrix
     * authorization screen"; #31, e2e script rest-admin-screens.sh): under the Batch Control matrix
     * strategy the administrator opens {@code manage/configureSecurity/}. Every permission group whose
     * title reads exactly "Batch Control" holds exactly the five Batch Control permissions, by their
     * permission ids. Guards: the "Overall" group holds Overall/Administer and none of the five, so the
     * group boundaries read here are real. Unlike T-02-02's raw-DOM check, a stray "Batch Control"
     * elsewhere on the page (a strategy name, a heading) cannot satisfy this row.
     */
    @Test
    public void t_02_197_securityPageGroupsTheFivePermissionsUnderBatchControl() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        strategy.add(Jenkins.READ, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        Set<String> five = new TreeSet<>(List.of(
                BatchControlPermissions.REQUEST.getId(),
                BatchControlPermissions.APPROVE.getId(),
                BatchControlPermissions.REQUEST_GRANT.getId(),
                BatchControlPermissions.VIEW_HISTORY.getId(),
                BatchControlPermissions.MANAGE.getId()));
        assertEquals(5, five.size(), "premise: five distinct permission ids");

        HtmlPage page = j.createWebClient().login("admin").goTo("manage/configureSecurity/");
        List<DomElement> groups = permissionGroupsTitled(page, "Batch Control");
        assertFalse(groups.isEmpty(), "the security page must render a permission group titled 'Batch Control'");
        for (DomElement group : groups) {
            assertEquals(five, permissionIds(group), "the 'Batch Control' group must hold exactly the five Batch Control permissions");
        }

        List<DomElement> overall = permissionGroupsTitled(page, "Overall");
        assertFalse(overall.isEmpty(), "guard: the security page renders the 'Overall' permission group the same way");
        for (DomElement group : overall) {
            Set<String> ids = permissionIds(group);
            assertTrue(ids.contains(Jenkins.ADMINISTER.getId()), "guard: the 'Overall' group holds Overall/Administer: " + ids);
            for (String id : five) {
                assertFalse(ids.contains(id), "guard: the 'Overall' group must not hold " + id + ": " + ids);
            }
        }
    }

    /**
     * The permission groups on {@code page} whose title (a legend, matrix-auth 3.x) reads exactly
     * {@code title}: each title's nearest ancestor that also contains permission cells.
     */
    private static List<DomElement> permissionGroupsTitled(HtmlPage page, String title) {
        List<DomElement> out = new ArrayList<>();
        for (Object o : page.getByXPath("//legend[normalize-space(.)='" + title + "']")) {
            DomNode n = ((DomNode) o).getParentNode();
            while (n instanceof DomElement && permissionIds((DomElement) n).isEmpty()) {
                n = n.getParentNode();
            }
            if (n instanceof DomElement) {
                out.add((DomElement) n);
            }
        }
        return out;
    }

    /** The permission ids of the permission cells inside {@code container}. */
    private static Set<String> permissionIds(DomElement container) {
        Set<String> ids = new TreeSet<>();
        for (DomNode n : container.querySelectorAll("[data-permission-id]")) {
            ids.add(((DomElement) n).getAttribute("data-permission-id"));
        }
        return ids;
    }

    /** T-02-05: a Manage holder can POST the global config form; values are saved (form round-trip keeps them). */
    @Test
    @Tag("core")
    public void t_02_05_manageUserCanSaveGlobalConfig() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("m1")); // BatchControl/Manage is implied by Administer

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.setAllowAdminSelfApproval(false);
        cfg.setPendingTimeoutHours(48);
        cfg.save();

        JenkinsRule.WebClient wc = j.createWebClient().login("m1");
        HtmlForm form = wc.goTo("configure").getFormByName("config");
        j.submit(form); // must succeed (200) for a Manage holder

        BatchControlGlobalConfiguration reloaded = BatchControlGlobalConfiguration.get();
        assertEquals(Arrays.asList("a1", "a2"), reloaded.getApprovers(), "approver list must survive the config form round-trip");
        assertFalse(reloaded.isAllowAdminSelfApproval());
        assertEquals(48, reloaded.getPendingTimeoutHours());
    }
}
