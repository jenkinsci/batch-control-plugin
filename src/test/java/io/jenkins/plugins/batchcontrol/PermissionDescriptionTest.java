package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each Batch Control permission explains itself on the security page (issue #31, note 370, gap X-05,
 * retired drivers browser/b2.mjs #2 and browser/audit/b2.mjs #2). Matrix row T-UI-305.
 *
 * <p>Basis: no SPEC sentence fixes the descriptions; SPEC 2 exposes the five permissions as a Batch
 * Control group of the matrix screen (T-02-02, T-02-197), and the usability line of SPEC section 6 asks
 * that a control say what it does. The texts are therefore not pinned; the row asserts that each
 * permission has its own description and that matrix-auth shows it as the permission's tooltip, the
 * way it shows core's descriptions.
 *
 * <p>Written from docs/SPEC.md item 2 and section 6, docs/TEST-MATRIX.md (T-02-02, T-02-197) and
 * matrix-auth's rendered page; the retired drivers were read only to learn what each looked at. No
 * src/main knowledge.
 */
@WithJenkins
public class PermissionDescriptionTest {

    /**
     * T-UI-305 (SPEC 2 matrix group, SPEC 6 usability; #31 gap X-05): each of Request, Approve,
     * RequestGrant, ViewHistory and Manage has a description longer than 20 characters, and the five are
     * distinct. Under the Batch Control matrix strategy, every checkbox of that permission on
     * {@code manage/configureSecurity/} (input named {@code [<permission id>]}) sits next to a
     * {@code data-html-tooltip} that starts with the description. Guard: core's Item/Configure is read the
     * same way and its tooltip starts with core's own description, so the reader finds tooltips.
     */
    @Test
    public void t_ui_305_permissionsCarryDescriptionsShownAsTooltips(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        strategy.add(Jenkins.READ, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        Permission[] five = {
            BatchControlPermissions.REQUEST,
            BatchControlPermissions.APPROVE,
            BatchControlPermissions.REQUEST_GRANT,
            BatchControlPermissions.VIEW_HISTORY,
            BatchControlPermissions.MANAGE,
        };
        Set<String> seen = new HashSet<>();
        for (Permission p : five) {
            assertNotNull(p.description, p.name + " must have a description");
            String text = p.description.toString().trim();
            assertTrue(text.length() > 20, p.name + " must have a description that says what it allows, got '" + text + "'");
            assertTrue(seen.add(text), p.name + " must have its own description, not another permission's: '" + text + "'");
        }

        HtmlPage page = j.createWebClient().login("admin").goTo("manage/configureSecurity/");
        List<String> core = tooltips(page, Item.CONFIGURE);
        assertFalse(core.isEmpty(), "guard: the security page shows Item/Configure checkboxes with tooltips");
        for (String tip : core) {
            assertTrue(tip.startsWith(Item.CONFIGURE.description.toString()), "guard: core's tooltip starts with its description: " + tip);
        }
        for (Permission p : five) {
            List<String> tips = tooltips(page, p);
            assertFalse(tips.isEmpty(), p.name + ": the security page must show its checkboxes with a tooltip");
            for (String tip : tips) {
                assertTrue(tip.startsWith(p.description.toString().trim()), p.name + ": the tooltip must start with the permission's"
                        + " description '" + p.description + "', got '" + tip + "'");
            }
        }
    }

    /**
     * The tooltip next to each checkbox of {@code p}: the {@code data-html-tooltip} of an element in the
     * checkbox's parent (asserted present for every checkbox found).
     */
    private static List<String> tooltips(HtmlPage page, Permission p) {
        List<String> out = new ArrayList<>();
        for (DomNode n : page.querySelectorAll("input[type=checkbox]")) {
            DomElement input = (DomElement) n;
            if (!("[" + p.getId() + "]").equals(input.getAttribute("name"))) {
                continue;
            }
            DomNode cell = input.getParentNode();
            String tip = null;
            for (DomNode sibling : cell.querySelectorAll("[data-html-tooltip]")) {
                tip = ((DomElement) sibling).getAttribute("data-html-tooltip");
                break;
            }
            assertNotNull(tip, p.getId() + ": the checkbox must sit next to a tooltip: " + ((DomElement) cell).asXml());
            out.add(tip);
        }
        assertEquals(out.size(), out.stream().filter(t -> !t.isBlank()).count(), p.getId() + ": no tooltip may be blank: " + out);
        return out;
    }
}
