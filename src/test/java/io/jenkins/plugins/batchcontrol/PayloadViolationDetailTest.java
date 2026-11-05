package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationMatrixProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.has;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GRANT_VIOLATION of a creation payload or a copy says that the new item's authorization property was
 * removed (issue #31, retired legacy driver browser/audit/b9.mjs #16, gap X-43). Matrix row T-02-205 (note
 * 371).
 *
 * <p>Basis: SPEC 2 (D-35c: "a payload's authorization property is removed and recorded as GRANT_VIOLATION";
 * D-35b: a self-granted entry on an existing item is reverted, the item's property restored) and SPEC 6
 * usability ("recorded history names who did what"). SPEC requires the record (T-02-34, T-02-35 count it) but
 * fixes no wording, so the detail is read with a pattern: it speaks of the authorization property and says
 * that the property itself was removed (not entries inside it). The guard is the direct self-entry of T-02-48, where the property is restored, not removed: its
 * record must not say the property was removed.
 *
 * <p>Users (StrategyFixtures): bob holds only a CREATE window on the folder {@code team} and a CONFIGURE window
 * on {@code team/guard-post}; c1 and admin as there.
 *
 * <p>Written from docs/SPEC.md items 2 and 6, docs/DECISIONS.md D-35b, D-35c and D-48 and docs/TEST-MATRIX.md
 * (T-02-34, T-02-35, T-02-48, T-08-47); the retired driver was read only to learn what it looked at. No
 * src/main knowledge.
 */
@WithJenkins
public class PayloadViolationDetailTest {

    /** The detail speaks of the item's authorization property. */
    private static final Pattern AUTHORIZATION_PROPERTY = Pattern.compile("(?i)authori[sz]ation propert(y|ies)\\b");

    /**
     * The removal is said of the property itself ("the property was removed", "removed its authorization
     * property"), not of entries inside it; the wording is not pinned further.
     */
    private static final Pattern PROPERTY_ITSELF_REMOVED = Pattern.compile(
            "(?i)\\bpropert(y|ies)\\s+(was|were|has been|have been|is|are)\\s+(removed|stripped|dropped|deleted)\\b"
                    + "|\\b(removed|stripped|dropped|deleted)\\s+(the|its|their)\\s+(\\w+\\s+)?propert(y|ies)\\b");

    private static final String PAYLOAD_PROPERTY = "<hudson.security.AuthorizationMatrixProperty>"
            + "<inheritanceStrategy class=\"org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy\"/>"
            + "<permission>USER:hudson.model.Item.Configure:bob</permission>"
            + "<permission>USER:hudson.model.Item.Configure:carol</permission>"
            + "</hudson.security.AuthorizationMatrixProperty>";
    private static final String PLAIN = "<?xml version='1.1' encoding='UTF-8'?><project><properties/><builders/>"
            + "<publishers/><buildWrappers/></project>";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
        StrategyFixtures.changeControlOn();
    }

    /**
     * T-02-205 (SPEC 2 D-35b/D-35c, SPEC 6; #31 gap X-43): bob, with only a CREATE window on {@code team},
     * POSTs {@code createItem?name=new} with a payload carrying an AuthorizationMatrixProperty (as T-02-34) and
     * copies {@code team/src}, which carries one, as {@code team/copy} (as T-02-35). Each writes one
     * GRANT_VIOLATION naming bob and the new item (premise), and its detail says that the item's
     * authorization property was removed. Guard: bob's direct self-entry through {@code POST config.xml} on
     * {@code team/guard-post} (CONFIGURE window, as T-02-48) writes one GRANT_VIOLATION whose detail does not
     * say that the authorization property was removed, and the property is still there.
     */
    @Test
    public void t_02_205_payloadAndCopyViolationsSayTheAuthorizationPropertyWasRemoved() throws Exception {
        Folder team = j.jenkins.createProject(Folder.class, "team");
        FreeStyleProject src = team.createProject(FreeStyleProject.class, "src");
        src.addProperty(property("bob", "carol"));
        FreeStyleProject guarded = team.createProject(FreeStyleProject.class, "guard-post");
        guarded.addProperty(property("alice"));
        StrategyFixtures.grant("bob", "team", Arrays.asList(GrantAction.CREATE));
        StrategyFixtures.grant("bob", "team/guard-post", Arrays.asList(GrantAction.CONFIGURE));
        assertTrue(has(src, "bob", Item.EXTENDED_READ), "premise: bob may read the source's configuration");

        postCreateItem("name=new", PLAIN.replace("<properties/>", "<properties>" + PAYLOAD_PROPERTY + "</properties>"));
        ChangeRecord payload = onlyNewViolation(0, "team/new");
        assertTrue(saysPropertyRemoved(payload), "the payload's GRANT_VIOLATION must say that the new item's"
                + " authorization property was removed: " + describe(payload));

        postCreateItem("name=copy&mode=copy&from=src", null);
        ChangeRecord copy = onlyNewViolation(1, "team/copy");
        assertTrue(saysPropertyRemoved(copy), "the copy's GRANT_VIOLATION must say that the copied"
                + " authorization property was removed: " + describe(copy));

        String xml = guarded.getConfigFile().asString();
        String close = "</hudson.security.AuthorizationMatrixProperty>";
        assertTrue(xml.contains(close), "fixture: guard-post carries an authorization property");
        postConfigXml(guarded, xml.replace(close, "<permission>USER:hudson.model.Item.Configure:bob</permission>" + close));
        ChangeRecord direct = onlyNewViolation(2, "team/guard-post");
        assertNotNull(j.jenkins.getItemByFullName("team/guard-post", FreeStyleProject.class)
                .getProperty(AuthorizationMatrixProperty.class), "guard premise: the direct self-entry keeps the property");
        assertFalse(saysPropertyRemoved(direct), "guard: the direct self-entry's GRANT_VIOLATION must not say"
                + " that the authorization property was removed (it was restored): " + describe(direct));
    }

    // ---------------------------------------------------------------- helpers

    private static AuthorizationMatrixProperty property(String... configurers) {
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        for (String sid : configurers) {
            amp.add(Item.CONFIGURE, PermissionEntry.user(sid));
        }
        return amp;
    }

    /** Exactly one GRANT_VIOLATION beyond the {@code before} earlier ones; it names bob and {@code target}. */
    private static ChangeRecord onlyNewViolation(int before, String target) {
        List<ChangeRecord> violations = StrategyFixtures.records(ChangeType.GRANT_VIOLATION);
        assertEquals(before + 1, violations.size(), "premise: exactly one new GRANT_VIOLATION for " + target + ", got " + violations.size());
        ChangeRecord record = violations.get(violations.size() - 1);
        assertEquals("bob", record.getUser(), "premise: the record names bob: " + describe(record));
        assertEquals(target, record.getTarget(), "premise: the record names " + target + ": " + describe(record));
        return record;
    }

    private static boolean saysPropertyRemoved(ChangeRecord record) {
        String detail = detail(record);
        return AUTHORIZATION_PROPERTY.matcher(detail).find() && PROPERTY_ITSELF_REMOVED.matcher(detail).find();
    }

    private static String detail(ChangeRecord record) {
        return record.getDetail() == null ? "" : record.getDetail();
    }

    private static String describe(ChangeRecord record) {
        return record.getType() + " user=" + record.getUser() + " target=" + record.getTarget() + " detail=" + record.getDetail();
    }

    private void postCreateItem(String query, String body) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("bob");
        URL url = new URL(wc.createCrumbedUrl("job/team/createItem").toExternalForm() + "&" + query);
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        if (body != null) {
            req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
            req.setRequestBody(body);
        }
        wc.getPage(req);
    }

    private void postConfigXml(FreeStyleProject job, String xml) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("bob");
        WebRequest req = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml");
        req.setRequestBody(xml);
        wc.getPage(req);
    }
}
