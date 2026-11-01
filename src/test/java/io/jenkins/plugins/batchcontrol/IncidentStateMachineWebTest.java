package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.IncidentStatus;
import io.jenkins.plugins.batchcontrol.model.IncidentTransition;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 11 and the SPEC section 4 incident state machine, through the web endpoints an operator
 * uses: {@code OPEN -> ACKNOWLEDGED -> RESOLVED}, no reverse transition, a comment still possible on
 * RESOLVED. Matrix rows T-11-38 and T-11-39 (note 367).
 *
 * <p>These rows replace the server-side half of the retired e2e script
 * {@code e2e/scripts/rest-incident-flow.sh} (issue #31): "POST resolve while OPEN answers 400" and
 * "a RESOLVED incident refuses a further acknowledge". The service-level reverse-transition refusal
 * is T-11-05 ({@link IncidentTest}) and a second resolve over HTTP is T-GAP-133/134
 * ({@link RequestFormRefusalGapTest}); these rows add the two HTTP shortcuts neither covers.
 *
 * <p>Endpoints, as the incident page's forms name them (T-11-26): {@code POST
 * batch-control/incidents/<id>/acknowledge|resolve|comment} with a {@code comment} field and a crumb.
 * The operator is {@code viewer}, who holds ViewHistory, the permission those endpoints check.
 *
 * <p>Written from docs/SPEC.md items 4 and 11 and section 6 (usability), and docs/TEST-MATRIX.md
 * only (no src/main knowledge).
 */
@WithJenkins
public class IncidentStateMachineWebTest {

    private JenkinsRule j;
    private Incident incident;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(List.of("admin"));
        cfg.save();

        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("inc-flow"));
        job.getBuildersList().add(new FailureBuilder());
        BatchControlFixtures.activateAsAdmin(job); // D-46: the cause-less fixture build needs an activation
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        j.waitUntilNoActivity();
        incident = RerunFallbackFixtures.incidentFor("inc-flow#1");
        assertEquals(IncidentStatus.OPEN, incident.getStatus(), "fixture: the incident starts OPEN");
    }

    /**
     * T-11-38 (SPEC 4 "OPEN -> ACKNOWLEDGED -> RESOLVED", SPEC 11): the viewer POSTs {@code resolve}
     * on the OPEN incident, skipping the acknowledgement. The answer is HTTP 400 with a plain
     * message, the incident stays OPEN and no transition is recorded. Guard: after the viewer
     * acknowledges it, the same resolve is accepted and the incident is RESOLVED.
     */
    @Test
    public void t_11_38_resolvingAnOpenIncidentIsRefused() throws Exception {
        List<String> before = transitions();
        WebResponse shortcut = post("resolve", "skipping acknowledgement on purpose");
        assertEquals(400, shortcut.getStatusCode(), "SPEC 4: OPEN -> RESOLVED is not a transition, so resolving an OPEN incident"
                + " must be refused: " + UsabilityFixtures.excerpt(shortcut.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal("resolving an OPEN incident", shortcut.getContentAsString(), Pattern.compile("(?i)\\bopen\\b|acknowledg"));
        assertEquals(IncidentStatus.OPEN, reload().getStatus(), "the refused resolve must leave the incident OPEN");
        assertEquals(before, transitions(), "the refused resolve must record nothing");

        // guard: the endpoint does resolve once the incident has been acknowledged
        assertAccepted(post("acknowledge", "cause: the feed delivered an empty file"), "fixture: the acknowledge");
        assertEquals(IncidentStatus.ACKNOWLEDGED, reload().getStatus(), "fixture: the incident is ACKNOWLEDGED");
        assertAccepted(post("resolve", "the redelivered file processed cleanly"), "guard: resolving an ACKNOWLEDGED incident");
        assertEquals(IncidentStatus.RESOLVED, reload().getStatus(), "guard: the incident is RESOLVED");
    }

    /**
     * T-11-39 (SPEC 4 "no reverse transition; a comment can still be added on RESOLVED", SPEC 11):
     * on a RESOLVED incident the viewer POSTs {@code acknowledge}. The answer is a 4xx with a plain
     * message, the incident stays RESOLVED and no transition is recorded. Guard: a {@code comment}
     * POST on the same RESOLVED incident is accepted, records exactly one entry with the viewer and
     * the text, and leaves it RESOLVED.
     */
    @Test
    public void t_11_39_resolvedIncidentRefusesAcknowledgeButTakesAComment() throws Exception {
        assertAccepted(post("acknowledge", "on it"), "fixture: the acknowledge");
        assertAccepted(post("resolve", "fixed"), "fixture: the resolve");
        assertEquals(IncidentStatus.RESOLVED, reload().getStatus(), "fixture: the incident is RESOLVED");

        List<String> before = transitions();
        WebResponse reopen = post("acknowledge", "again");
        int code = reopen.getStatusCode();
        assertTrue(code >= 400 && code < 500, "SPEC 4: RESOLVED -> ACKNOWLEDGED is a reverse transition and must be refused with a 4xx,"
                + " got HTTP " + code + ": " + UsabilityFixtures.excerpt(reopen.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal("acknowledging a RESOLVED incident", reopen.getContentAsString(), null);
        assertEquals(IncidentStatus.RESOLVED, reload().getStatus(), "the refused acknowledge must leave the incident RESOLVED");
        assertEquals(before, transitions(), "the refused acknowledge must record nothing");

        // guard: SPEC 4 keeps commenting open on RESOLVED, so the refusal above is about the transition
        assertAccepted(post("comment", "post-mortem attached"), "guard: a comment on the RESOLVED incident");
        List<IncidentTransition> after = reload().getTransitions();
        assertEquals(before.size() + 1, after.size(), "guard: the comment adds exactly one entry");
        IncidentTransition last = after.get(after.size() - 1);
        assertEquals("viewer", last.getBy(), "guard: the comment names who wrote it");
        assertEquals("post-mortem attached", last.getComment(), "guard: the comment keeps its text");
        assertEquals(IncidentStatus.RESOLVED, reload().getStatus(), "guard: a comment does not change the status");
    }

    // ---------------------------------------------------------------- helpers

    private WebResponse post(String action, String comment) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("comment", comment));
        return ApproverFormFixtures.post(j, "viewer", "batch-control/incidents/" + incident.getId() + "/" + action, params);
    }

    private static void assertAccepted(WebResponse response, String what) {
        assertTrue(response.getStatusCode() < 400, what + " must be accepted, got HTTP " + response.getStatusCode() + ": "
                + UsabilityFixtures.excerpt(response.getContentAsString()));
    }

    private Incident reload() {
        Incident reloaded = IncidentService.get().load(incident.getId());
        assertNotNull(reloaded, "the incident must still load");
        return reloaded;
    }

    private List<String> transitions() {
        List<String> out = new ArrayList<>();
        List<IncidentTransition> list = reload().getTransitions();
        if (list != null) {
            for (IncidentTransition t : list) {
                out.add(t.getStatus() + " " + t.getBy() + " " + t.getComment() + " " + t.getAt());
            }
        }
        return out;
    }
}
