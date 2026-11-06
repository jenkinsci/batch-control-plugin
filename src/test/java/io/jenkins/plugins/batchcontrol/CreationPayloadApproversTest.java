package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A REST {@code createItem} under run control forces the D-31/D-34 lock and keeps the payload's job
 * approver list (issue #31, retired legacy drivers browser/b10.mjs #7 and browser/audit/b10.mjs #5,
 * gap X-10). Matrix row T-08-296 (note 370).
 *
 * <p>Basis: SPEC item 8 (D-31: every job created under run control starts {@code approvalRequired=true};
 * D-34: also {@code blockTimer} and {@code blockUpstream}; SPEC 6a D-59a names the lock with
 * {@code allowedUpstreamJobs} emptied), DECISIONS P-14 (implemented, awaiting ratification: of the
 * payload's property only {@code jobApprovers} is carried over, because it can only narrow who may
 * approve) and docs/LIMITATIONS.md ("Values in the creation payload do not survive the lock ... Only
 * {@code jobApprovers} is carried over"). T-08-288 covers the lock against an opt-out payload; this row
 * adds the approver list it carries. If P-14 is not ratified, this row follows the ruling.
 *
 * <p>Written from docs/SPEC.md items 6a and 8, docs/DECISIONS.md D-31, D-34, D-59a and P-14,
 * docs/LIMITATIONS.md and docs/TEST-MATRIX.md (T-08-288); the retired drivers were read only to learn
 * what they looked at. No src/main knowledge.
 */
@WithJenkins
public class CreationPayloadApproversTest {

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2"));
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();
    }

    /**
     * T-08-296 (SPEC 8 D-31/D-34, P-14; #31 gap X-10): two payloads are the {@code config.xml} Jenkins
     * wrote, while run control was off, for jobs whose Batch Control property opts out of everything
     * (approvalRequired, blockTimer and blockUpstream false, allow list [{@code feeder}]); one carries
     * {@code jobApprovers} [a2] (premise: the payload names a2), the other none. With run control on the
     * administrator POSTs {@code createItem} with each. {@code new-x} (with a2): exactly one Batch Control
     * property, approvalRequired, blockTimer and blockUpstream on, no allow list, and {@code jobApprovers}
     * exactly [a2]. Guard: {@code new-y} (without) carries the same lock and no job approver.
     */
    @Test
    public void t_08_296_restCreationKeepsThePayloadsJobApproversUnderTheLock() throws Exception {
        assertFalse(cfg.isRunControlEnabled(), "premise: the payloads are written while run control is off");
        String withApprovers = payload("tpl-with", List.of("a2"));
        String withoutApprovers = payload("tpl-without", null);
        assertTrue(withApprovers.contains("a2") && withApprovers.contains("feeder"),
                "fixture: the payload carries the job approver a2 and the allow list: " + withApprovers);
        assertFalse(withoutApprovers.contains("a2"), "fixture: the guard's payload names no job approver: " + withoutApprovers);

        cfg.setRunControlEnabled(true);
        cfg.save();
        JenkinsRule.WebClient admin = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        assertTrue(createFromXml(admin, "new-x", withApprovers) < 400, "the REST creation of new-x must succeed");
        assertTrue(createFromXml(admin, "new-y", withoutApprovers) < 400, "the REST creation of new-y must succeed");

        BatchControlJobProperty created = lockedProperty("new-x");
        assertEquals(List.of("a2"), created.getJobApprovers(),
                "P-14: the payload's jobApprovers must be carried over verbatim while the lock is forced");

        BatchControlJobProperty guard = lockedProperty("new-y");
        List<String> none = guard.getJobApprovers();
        assertTrue(none == null || none.isEmpty(), "guard: a payload without jobApprovers leaves the job without any, got " + none);
    }

    // ---------------------------------------------------------------- helpers

    /** The config.xml of a template job whose property opts out of the lock and names {@code approvers}. */
    private String payload(String name, List<String> approvers) throws Exception {
        FreeStyleProject template = j.createFreeStyleProject(name);
        BatchControlJobProperty open = new BatchControlJobProperty(false);
        open.setBlockTimer(false);
        open.setBlockUpstream(false);
        open.setAllowedUpstreamJobs(List.of("feeder"));
        if (approvers != null) {
            open.setJobApprovers(approvers);
        }
        BatchControlFixtures.setBatchControl(template, open);
        String xml = template.getConfigFile().asString();
        assertTrue(xml.contains("BatchControlJobProperty"), "fixture: the payload carries the Batch Control property: " + xml);
        return xml;
    }

    /** The created job's only Batch Control property, asserted to carry the D-31/D-34 lock with no allow list. */
    private BatchControlJobProperty lockedProperty(String name) {
        FreeStyleProject job = j.jenkins.getItemByFullName(name, FreeStyleProject.class);
        assertNotNull(job, name + " must have been created");
        assertEquals(1, job.getProperties().values().stream().filter(p -> p instanceof BatchControlJobProperty).count(),
                name + " must carry exactly one Batch Control property");
        BatchControlJobProperty property = job.getProperty(BatchControlJobProperty.class);
        assertTrue(property.isApprovalRequired(), "D-31: " + name + " must require approval");
        assertTrue(property.isBlockTimer(), "D-34: " + name + " must block its timer");
        assertTrue(property.isBlockUpstream(), "D-34: " + name + " must block upstream triggers");
        List<String> allowed = property.getAllowedUpstreamJobs();
        assertTrue(allowed == null || allowed.isEmpty(), "D-59a: the payload's allow list must not survive, got " + allowed);
        return property;
    }

    private static int createFromXml(JenkinsRule.WebClient wc, String name, String xml) throws Exception {
        URL url = new URL(wc.createCrumbedUrl("createItem").toExternalForm() + "&name=" + name);
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }
}
