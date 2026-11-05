package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationMatrixProperty;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
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
 * With change control off, a native Configure holder's authorization entry written through
 * {@code POST config.xml} is kept and no GRANT_VIOLATION is written, even on an item that the self-grant
 * guard would protect while change control is on (issue #31, retired legacy driver browser/verify/f3.mjs #2,
 * gap X-38). Matrix row T-02-203 (note 371).
 *
 * <p>Basis: SPEC 1 ("설치 직후 기존 잡의 빌드·설정·삭제가 이전과 동일하게 동작한다"; CLAUDE.md: a new feature does
 * not change existing Jenkins behaviour while the switch is off), SPEC 2 (D-58: "while change control is on,
 * an item in the scope of an active grant, or whose configuration was changed under a grant ..., is guarded:
 * any change that widens access on it ... is reverted and recorded as GRANT_VIOLATION whoever makes it, unless
 * it is an HTTP save by an Overall/Administer holder"; D-58b: an ordinary save does not end the state) and
 * TEST-MATRIX T-02-23 (c1, a native Configure holder, is reverted on a guarded item). T-02-56 is the build
 * script with change control off; this row is the {@code config.xml} POST.
 *
 * <p>Users (StrategyFixtures): {@code c1} holds native Item/Configure (not an administrator); {@code bob}
 * holds Configure only through windows; {@code carol} reads. Both jobs carry their own matrix property
 * (alice Configure).
 *
 * <p>Written from docs/SPEC.md items 1 and 2, docs/DECISIONS.md D-58, D-58a and D-58b, CLAUDE.md and
 * docs/TEST-MATRIX.md (T-02-23, T-02-56, T-02-70); the retired driver was read only to learn what it looked
 * at. No src/main knowledge.
 */
@WithJenkins
public class ChangeControlOffAuthorizationTest {

    private static final String CAROL_CONFIGURE = "USER:hudson.model.Item.Configure:carol";

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
        cfg = StrategyFixtures.changeControlOn();
    }

    /**
     * T-02-203 (SPEC 1, SPEC 2 D-58; #31 gap X-38): change control on; bob holds CONFIGURE windows on
     * {@code off-x} and {@code on-y} and saves a description change of each through {@code POST config.xml}
     * (each is now changed under a grant). Guard first (change control on): c1 POSTs {@code on-y}'s
     * {@code config.xml} adding carol Item/Configure; the answer is 403, carol has no entry and no Configure,
     * and one GRANT_VIOLATION names c1 and {@code on-y} (T-02-23). Then change control is turned off (saved;
     * bob's windows end). c1 POSTs {@code off-x}'s {@code config.xml} adding the same entry: the answer is 200,
     * the entry is kept in memory and in the stored {@code config.xml}, carol holds Configure on {@code off-x},
     * and no GRANT_VIOLATION is written.
     */
    @Test
    public void t_02_203_nativeEntryIsKeptWithChangeControlOff() throws Exception {
        FreeStyleProject off = jobWithProperty("off-x");
        FreeStyleProject on = jobWithProperty("on-y");
        StrategyFixtures.grant("bob", "off-x", Arrays.asList(GrantAction.CONFIGURE));
        StrategyFixtures.grant("bob", "on-y", Arrays.asList(GrantAction.CONFIGURE));
        assertTrue(has(off, "bob", Item.CONFIGURE) && has(on, "bob", Item.CONFIGURE), "premise: bob's windows confer Configure");
        for (FreeStyleProject job : new FreeStyleProject[] {off, on}) {
            WebResponse saved = postConfigXml("bob", job, withDescription(job, "changed by bob under his window"));
            assertEquals(200, saved.getStatusCode(), "fixture: bob's description save of " + job.getFullName() + " under his window");
        }
        assertTrue(has(off, "c1", Item.CONFIGURE), "premise: c1 holds native Item/Configure");
        assertFalse(has(off, "carol", Item.CONFIGURE), "premise: carol holds no Configure");
        int violations = StrategyFixtures.records(ChangeType.GRANT_VIOLATION).size();

        WebResponse guarded = postConfigXml("c1", on, withCarolEntry(on));
        assertEquals(violations + 1, StrategyFixtures.records(ChangeType.GRANT_VIOLATION).size(),
                "guard (T-02-23): with change control on, c1's widening of on-y is reverted and recorded");
        assertEquals(403, guarded.getStatusCode(), "guard (D-48): the reverted save answers 403");
        assertFalse(entries(on).contains(CAROL_CONFIGURE), "guard: carol's entry on on-y is reverted");
        assertFalse(has(on, "carol", Item.CONFIGURE), "guard: carol holds no Configure on on-y");
        ChangeRecord recorded = last(StrategyFixtures.records(ChangeType.GRANT_VIOLATION));
        assertEquals("c1", recorded.getUser(), "guard: the GRANT_VIOLATION names c1");
        assertEquals("on-y", recorded.getTarget(), "guard: the GRANT_VIOLATION names on-y");

        cfg.setChangeControlEnabled(false);
        cfg.save();
        assertFalse(BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "premise: change control is off");
        assertFalse(has(off, "bob", Item.CONFIGURE), "premise: turning change control off ended bob's windows");
        violations = StrategyFixtures.records(ChangeType.GRANT_VIOLATION).size();

        WebResponse kept = postConfigXml("c1", off, withCarolEntry(off));
        assertEquals(200, kept.getStatusCode(), "SPEC 1: with change control off, c1's config.xml POST must answer 200 as in"
                + " Jenkins, got " + kept.getStatusCode() + ": " + UsabilityFixtures.excerpt(kept.getContentAsString()));
        FreeStyleProject current = j.jenkins.getItemByFullName("off-x", FreeStyleProject.class);
        assertTrue(entries(current).contains(CAROL_CONFIGURE), "SPEC 1: with change control off, carol's entry must be kept:"
                + " " + entries(current));
        assertTrue(current.getConfigFile().asString().contains("hudson.model.Item.Configure:carol"),
                "SPEC 1: the stored config.xml must keep carol's entry");
        assertTrue(has(current, "carol", Item.CONFIGURE), "SPEC 1: carol holds Configure on off-x through the kept entry");
        assertEquals(violations, StrategyFixtures.records(ChangeType.GRANT_VIOLATION).size(),
                "SPEC 1: with change control off, no GRANT_VIOLATION may be written");
    }

    // ---------------------------------------------------------------- helpers

    private FreeStyleProject jobWithProperty(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        job.setDescription("desc-before");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        job.addProperty(amp);
        assertTrue(has(job, "alice", Item.CONFIGURE), "fixture: alice's per-job entry is effective");
        assertFalse(has(job, "bob", Item.CONFIGURE), "fixture: bob holds no native Configure on " + name);
        return job;
    }

    private static String withDescription(FreeStyleProject job, String description) throws Exception {
        String xml = job.getConfigFile().asString();
        String changed = xml.replaceFirst("<description>[^<]*</description>", "<description>" + description + "</description>");
        assertTrue(changed.contains(description), "fixture: the description edit must be in the XML: " + xml);
        return changed;
    }

    private static String withCarolEntry(FreeStyleProject job) throws Exception {
        String xml = job.getConfigFile().asString();
        String close = "</hudson.security.AuthorizationMatrixProperty>";
        assertTrue(xml.contains(close), "fixture: " + job.getFullName() + " carries an AuthorizationMatrixProperty: " + xml);
        return xml.replace(close, "<permission>" + CAROL_CONFIGURE + "</permission>" + close);
    }

    /** The job's own matrix entries as "TYPE:permission:sid" strings. */
    private static Set<String> entries(FreeStyleProject job) {
        AuthorizationMatrixProperty amp = job.getProperty(AuthorizationMatrixProperty.class);
        assertNotNull(amp, job.getFullName() + " must keep its authorization property");
        Set<String> out = new java.util.TreeSet<>();
        amp.getGrantedPermissionEntries().forEach((p, sids) -> sids.forEach(e -> out.add(e.getType() + ":" + p.getId() + ":" + e.getSid())));
        return out;
    }

    private static ChangeRecord last(List<ChangeRecord> records) {
        assertFalse(records.isEmpty(), "a record must exist");
        return records.get(records.size() - 1);
    }

    private WebResponse postConfigXml(String user, FreeStyleProject job, String xml) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        WebRequest req = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml");
        req.setRequestBody(xml);
        return wc.getPage(req).getWebResponse();
    }
}
