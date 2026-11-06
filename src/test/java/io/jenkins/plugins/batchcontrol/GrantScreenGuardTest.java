package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.BatchControlNotifier;
import io.jenkins.plugins.batchcontrol.ops.Notification;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.grantRequestIds;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server checks of the permission window screens that only the retired legacy e2e drivers made
 * (issue #31, note 370): the custom duration field states {@code maxGrantMinutes} (T-08-293, gap X-02,
 * retired drivers browser/b1.mjs #24 and browser/audit/b1b.mjs #12), the request detail page shows its
 * reason and actions to the requester and the approver (T-08-294, gap X-09, browser/b7.mjs #6) and a
 * form submission naming a missing item is refused with nothing stored and nothing sent (T-08-295, gap
 * X-49, fresh/s4-grants.mjs #3).
 *
 * <p>Users: {@code u1} requester (Item/Read, BatchControl/RequestGrant), {@code a1} approver
 * (BatchControl/Approve), {@code admin}. Change control on; approvers [a1]; {@code batch-x} a
 * freestyle job.
 *
 * <p>Written from docs/SPEC.md item 8 and the usability line of section 6, docs/DECISIONS.md D-66 and
 * D-71 and docs/TEST-MATRIX.md (T-08-120, T-GAP-225, T-UI-105); the retired drivers were read only to
 * learn what each looked at. No src/main knowledge.
 */
@WithJenkins
public class GrantScreenGuardTest {

    private static final String CREATE = "batch-control/grants/create";

    /** Records every notification for the rows of this class. */
    @TestExtension
    public static class CapturingNotifier extends BatchControlNotifier {
        @Override
        public void notify(NotificationEvent event, Notification notification) {
            NotificationCapture.record(event, notification);
        }
    }

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        NotificationCapture.clear();
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        j.createFreeStyleProject("batch-x");
    }

    @AfterEach
    public void tearDown() {
        NotificationCapture.clear();
    }

    /**
     * T-08-293 (SPEC 8: {@code grantDurationOptions} and {@code maxGrantMinutes}, default 240; SPEC 6
     * usability: the user learns the limit before the submission is refused, not only from the refusal of
     * T-05-18/T-UI-105; #31 gap X-02): with {@code maxGrantMinutes} 200 (not one of the duration options
     * 15, 30, 60), the form item of u1's grant request page ({@code grants/new}) and of the grant dialog
     * fragment ({@code grants/dialog}) that holds the {@code customDurationMinutes} field reads 200 and
     * says it is a maximum. With 225 the same items read 225 and no longer 200, so the number is the
     * setting and not a constant. The wording ("Maximum: N minutes.") is not pinned: SPEC fixes the
     * setting, not the sentence.
     */
    @Test
    public void t_08_293_customDurationFieldStatesTheMaximum() throws Exception {
        assertEquals(List.of(15, 30, 60), cfg.getGrantDurationOptions(), "premise: the default duration options");
        Pattern max = Pattern.compile("(?i)\\bmax");
        for (int limit : new int[] {200, 225}) {
            cfg.setMaxGrantMinutes(limit);
            cfg.save();
            for (String path : new String[] {"batch-control/grants/new", "batch-control/grants/dialog"}) {
                String text = customDurationItem(path).asNormalizedText();
                assertTrue(Pattern.compile("\\b" + limit + "\\b").matcher(text).find() && max.matcher(text).find(),
                        path + ": the custom duration field must state the maximum of " + limit + " minutes: " + text);
                int other = limit == 200 ? 225 : 200;
                assertFalse(Pattern.compile("\\b" + other + "\\b").matcher(text).find(),
                        path + ": the field must state the current maximum only, not " + other + ": " + text);
            }
        }
    }

    /**
     * T-08-294 (SPEC 8: a window request names the item, the actions, the time and the reason, and the
     * approver decides on it; SPEC 6 usability: recorded history names who did what; #31 gap X-09): u1
     * requests CONFIGURE on {@code batch-x} with reason {@code grant-reason-x9} and, separately, DELETE
     * with reason {@code grant-reason-d9}. The requester u1 and the approver a1 each open
     * {@code grants/<id>/} of both: each page's main panel shows its own reason, and its summary row
     * headed "Actions" names its own action (Configure, Delete; letter case not pinned). Guards: neither
     * page shows the other's reason and neither Actions row names the other's action, so the texts are
     * the request's and not the page's furniture (the CONFIGURE page's rename notice mentions Delete, so
     * the action is read from its row, not from the whole page).
     */
    @Test
    public void t_08_294_grantDetailPageShowsTheReasonAndTheActions() throws Exception {
        String configure = submitGrantOk(j, "u1", "batch-x", List.of("CONFIGURE"), 30, "grant-reason-x9", null, "a1");
        String delete = submitGrantOk(j, "u1", "batch-x", List.of("DELETE"), 30, "grant-reason-d9", null, "a1");
        Pattern configureWord = Pattern.compile("(?i)\\bconfigure\\b");
        Pattern deleteWord = Pattern.compile("(?i)\\bdelete\\b");
        for (String viewer : new String[] {"u1", "a1"}) {
            HtmlPage onConfigure = page(viewer, "batch-control/grants/" + configure + "/");
            HtmlPage onDelete = page(viewer, "batch-control/grants/" + delete + "/");
            String configureText = main(onConfigure).asNormalizedText();
            String deleteText = main(onDelete).asNormalizedText();
            assertTrue(configureText.contains("grant-reason-x9"), viewer + " must see the reason on the CONFIGURE request's page: "
                    + excerpt(configureText));
            assertTrue(deleteText.contains("grant-reason-d9"), viewer + " must see the reason on the DELETE request's page: "
                    + excerpt(deleteText));
            String configureActions = actionsCell(onConfigure);
            String deleteActions = actionsCell(onDelete);
            assertTrue(configureWord.matcher(configureActions).find(), viewer + ": the CONFIGURE request's Actions row must name"
                    + " Configure: " + configureActions);
            assertTrue(deleteWord.matcher(deleteActions).find(), viewer + ": the DELETE request's Actions row must name Delete: "
                    + deleteActions);
            assertFalse(configureText.contains("grant-reason-d9") || deleteWord.matcher(configureActions).find(),
                    "guard: the CONFIGURE request's page shows neither the other reason nor Delete as an action: " + configureActions);
            assertFalse(deleteText.contains("grant-reason-x9") || configureWord.matcher(deleteActions).find(),
                    "guard: the DELETE request's page shows neither the other reason nor Configure as an action: " + deleteActions);
        }
    }

    /**
     * T-08-295 (SPEC 8 D-71: a window names exactly one existing item, and approval is refused when no
     * item exists at that name; SPEC 6 usability: a refusal says why in plain words; SPEC 13: a request
     * that is not created is not announced; #31 gap X-49): u1 POSTs {@code grants/create} with
     * {@code scopeFullName=no-such-item}, CONFIGURE, 30 minutes, a reason and approver a1, once as the
     * page form and once as the dialog posts it ({@code dialog=true}). Each answer is HTTP 4xx in plain
     * words saying the item does not exist (wording not pinned beyond that: SPEC fixes no sentence; the
     * item check's "No such item" of T-08-120 is accepted); no grant request is stored and no
     * REQUEST_CREATED is sent (after the quiet period). Guard: the same POST naming {@code batch-x} stores
     * one PENDING request and announces it.
     */
    @Test
    public void t_08_295_requestForAMissingItemIsRefusedWithNothingStored() throws Exception {
        Pattern why = Pattern.compile("(?i)no such item|does not exist|doesn't exist|not found|no item");
        Set<String> before = grantRequestIds();
        for (boolean dialog : new boolean[] {false, true}) {
            String what = dialog ? "the dialog submission" : "the page form submission";
            WebResponse refused = ApproverFormFixtures.post(j, "u1", CREATE, params("no-such-item", dialog));
            int code = refused.getStatusCode();
            assertTrue(code >= 400 && code < 500, what + " for a missing item must be refused with HTTP 4xx, got " + code
                    + ": " + excerpt(refused.getContentAsString()));
            UsabilityFixtures.assertPlainRefusal(what, plainText(refused), why);
            assertEquals(before, grantRequestIds(), what + " must store no grant request");
        }
        Thread.sleep(NotificationCapture.QUIET_PERIOD_MS); // bounded wait for a notification that must NOT come
        assertTrue(NotificationCapture.matching(c -> c.event == NotificationEvent.REQUEST_CREATED).isEmpty(),
                "a refused request must not be announced: " + NotificationCapture.describeAll());

        WebResponse accepted = ApproverFormFixtures.post(j, "u1", CREATE, params("batch-x", false));
        assertTrue(accepted.getStatusCode() < 400, "guard: the same submission naming batch-x is accepted, got HTTP "
                + accepted.getStatusCode() + ": " + excerpt(accepted.getContentAsString()));
        Set<String> added = grantRequestIds();
        added.removeAll(before);
        assertEquals(1, added.size(), "guard: the accepted submission stores exactly one request, got " + added);
        String id = added.iterator().next();
        assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(id).getStatus(), "guard: the stored request is PENDING");
        assertEquals(1, NotificationCapture.await(NotificationEvent.REQUEST_CREATED, id).size(),
                "guard: the stored request is announced once");
    }

    // ---------------------------------------------------------------- helpers

    private static List<NameValuePair> params(String scope, boolean dialog) {
        List<NameValuePair> p = new ArrayList<>();
        if (dialog) {
            p.add(new NameValuePair("dialog", "true"));
        }
        p.add(new NameValuePair("scopeFullName", scope));
        p.add(new NameValuePair("actions", "CONFIGURE"));
        p.add(new NameValuePair("durationMinutes", "30"));
        p.add(new NameValuePair("reason", "fix the job's schedule"));
        p.add(new NameValuePair("approvers", "a1"));
        return p;
    }

    /** The {@code .jenkins-form-item} of {@code path} (as u1) that holds the {@code customDurationMinutes} field. */
    private DomElement customDurationItem(String path) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(j, "u1", path);
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: u1 opens " + path);
        List<DomElement> items = new ArrayList<>();
        for (DomNode n : page.querySelectorAll(".jenkins-form-item")) {
            DomElement item = (DomElement) n;
            if (item.querySelector("input[name=customDurationMinutes]") != null
                    && item.querySelectorAll(".jenkins-form-item").stream().noneMatch(inner -> inner != item
                            && ((DomElement) inner).querySelector("input[name=customDurationMinutes]") != null)) {
                items.add(item);
            }
        }
        assertEquals(1, items.size(), path + " must have exactly one form item holding the customDurationMinutes field: "
                + excerpt(page.asXml()));
        return items.get(0);
    }

    private HtmlPage page(String viewer, String path) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(j, viewer, path);
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: " + viewer + " opens " + path);
        return page;
    }

    private static DomElement main(HtmlPage page) {
        DomElement main = page.getElementById("main-panel");
        assertTrue(main != null, "fixture: " + page.getUrl() + " has a main panel");
        return main;
    }

    /** The value cell of the one summary row headed "Actions". */
    private static String actionsCell(HtmlPage page) {
        List<String> cells = new ArrayList<>();
        for (DomNode th : main(page).querySelectorAll("tr > th")) {
            if ("Actions".equals(th.asNormalizedText().trim())) {
                DomNode td = th.getParentNode().querySelector("td");
                if (td != null) {
                    cells.add(td.asNormalizedText().trim());
                }
            }
        }
        assertEquals(1, cells.size(), "the request page must have exactly one summary row headed 'Actions': "
                + excerpt(main(page).asNormalizedText()));
        return cells.get(0);
    }

    private static String plainText(WebResponse response) {
        String body = response.getContentAsString();
        String type = response.getContentType() == null ? "" : response.getContentType().toLowerCase(Locale.ROOT);
        return type.contains("html") ? body.replaceAll("(?s)<script.*?</script>", " ").replaceAll("<[^>]+>", " ")
                .replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&") : body;
    }
}
