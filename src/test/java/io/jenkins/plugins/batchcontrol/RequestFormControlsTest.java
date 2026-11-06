package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlFormUtil;
import org.htmlunit.html.HtmlLabel;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Controls of the request forms that only the retired legacy browser drivers checked (issue #31, note 371):
 * the Request Run form ticks the only approver it offers (T-UI-311, gap X-42, retired driver
 * browser/audit/b3.mjs #2), the grant form's action checkboxes are toggled by their labels (T-UI-313, gap
 * X-47, browser/audit/a07.mjs #7) and the Request Run page's Cancel leads back to the job and stores nothing
 * (T-UI-315, gap X-48, its page half, browser/audit/b4.mjs #4 and browser/verify/v5.mjs #4).
 *
 * <p>No SPEC sentence fixes either control; both are screen contracts of the request forms (SPEC 3: the
 * requester designates approvers from the configured list; SPEC 8: a window request names its actions; SPEC 6
 * usability). Users: {@code u1} (Item/Read, Item/Build, BatchControl/Request and RequestGrant), {@code a1}
 * and {@code a2} approvers. Run control and change control on; the approval-required job {@code batch-x}.
 * Pages are read with JavaScript on, as a browser shows them.
 *
 * <p>Written from docs/SPEC.md items 3, 8 and 6 (usability line) and docs/TEST-MATRIX.md (T-03-95, T-UI-104);
 * the retired drivers were read only to learn what they looked at. No src/main knowledge.
 */
@WithJenkins
public class RequestFormControlsTest {

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2"));
        for (String id : new String[] {"a1", "a2"}) {
            User.getById(id, true).save();
        }
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-UI-311 (screen contract; SPEC 3; #31 gap X-42): with the approver list [a1], u1's Request Run page
     * ({@code job/batch-x/batch-control/}) and its dialog fragment ({@code job/batch-x/batch-control/dialog})
     * each offer exactly a1 as an {@code approvers} checkbox, and it is ticked. Guard: with the list [a1, a2]
     * both are offered and neither is ticked on either form (a form that ticks every choice, or the first
     * one, fails).
     */
    @Test
    public void t_ui_311_requestRunFormTicksTheOnlyApprover() throws Exception {
        Map<String, Boolean> single = ticks("job/batch-x/batch-control/");
        assertEquals(List.of("a1"), new ArrayList<>(single.keySet()), "premise: the page offers exactly a1");
        assertTrue(single.get("a1"), "with exactly one approver offered, the Request Run page must tick it: " + single);
        Map<String, Boolean> singleDialog = ticks("job/batch-x/batch-control/dialog");
        assertEquals(List.of("a1"), new ArrayList<>(singleDialog.keySet()), "premise: the dialog offers exactly a1");
        assertTrue(singleDialog.get("a1"), "with exactly one approver offered, the Request Run dialog must tick it: " + singleDialog);

        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();
        for (String path : new String[] {"job/batch-x/batch-control/", "job/batch-x/batch-control/dialog"}) {
            Map<String, Boolean> several = ticks(path);
            assertEquals(List.of("a1", "a2"), new ArrayList<>(several.keySet()), "guard premise: " + path + " offers a1 and a2");
            assertFalse(several.containsValue(Boolean.TRUE), "guard: with several approvers offered, " + path
                    + " must tick none of them: " + several);
        }
    }

    /**
     * T-UI-313 (screen contract; SPEC 8, SPEC 6 usability; #31 gap X-47): on u1's grant request page
     * ({@code batch-control/grants/new}) and the grant dialog fragment ({@code batch-control/grants/dialog}),
     * every {@code actions} checkbox (premise: CREATE, CONFIGURE and DELETE are offered) has exactly one
     * associated label (a {@code label for} its id, a label around it, or core's {@code label.attach-previous}
     * right after it). On the page, for each enabled checkbox a click on that label flips its state and a
     * second click flips it back (guard: the state before each click is read, so a label that does nothing
     * fails either way). The dialog fragment is checked for the markup only: fetched on its own it runs
     * without the hosting page's scripts, which bind {@code attach-previous} labels; pressing a label inside
     * the opened dialog is the CI e2e's (gap list, X-48 driver).
     */
    @Test
    public void t_ui_313_grantActionLabelsToggleTheirCheckbox() throws Exception {
        for (String path : new String[] {"batch-control/grants/new", "batch-control/grants/dialog"}) {
            boolean page = path.endsWith("/new");
            HtmlPage html = browserPage(path);
            List<HtmlCheckBoxInput> boxes = new ArrayList<>();
            for (DomNode n : html.querySelectorAll("input[type=checkbox][name=actions]")) {
                boxes.add((HtmlCheckBoxInput) n);
            }
            List<String> values = new ArrayList<>();
            boxes.forEach(b -> values.add(b.getValueAttribute()));
            assertTrue(values.containsAll(List.of("CREATE", "CONFIGURE", "DELETE")), "premise: " + path + " offers the three"
                    + " actions as checkboxes named actions, offered " + values + ": " + excerpt(html.asNormalizedText()));
            int toggled = 0;
            for (HtmlCheckBoxInput box : boxes) {
                List<HtmlLabel> labels = labelsOf(html, box);
                assertEquals(1, labels.size(), path + ": the " + box.getValueAttribute() + " checkbox must have exactly one"
                        + " label, found " + labels.size() + ": " + box.getParentNode().asXml());
                if (!page || box.isDisabled()) {
                    continue;
                }
                boolean before = box.isChecked();
                labels.get(0).click();
                assertNotEquals(before, box.isChecked(), path + ": a click on the " + box.getValueAttribute() + " label must"
                        + " toggle its checkbox (was " + before + ")");
                labels.get(0).click();
                assertEquals(before, box.isChecked(), path + ": a second click on the " + box.getValueAttribute()
                        + " label must toggle it back");
                toggled++;
            }
            if (page) {
                assertTrue(toggled >= 2, "premise: at least two action checkboxes of " + path + " are enabled, toggled " + toggled);
            }
        }
    }

    /**
     * T-UI-315 (screen contract; SPEC 6 usability: no link leads to a 404 or 403 page; #31 gap X-48, its
     * page half, retired drivers browser/audit/b4.mjs #4 and browser/verify/v5.mjs #4): u1's Request Run page
     * ({@code job/batch-x/batch-control/}) offers exactly one control captioned Cancel, and it is a link, not
     * a submit control of the Request Run form; its target is the job page. Following it, with a reason
     * typed and the approver ticked, lands on the job page (200) and stores no run request. Guard: the same
     * form, filled the same way and submitted, stores exactly one PENDING request.
     */
    @Test
    public void t_ui_315_requestRunPageCancelLeadsBackToTheJobAndStoresNothing() throws Exception {
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm form = TypedParameterFixtures.requestRunForm(j, wc, job);
        HtmlPage page = (HtmlPage) form.getPage();
        List<DomElement> cancels = new ArrayList<>();
        DomElement main = page.getElementById("main-panel");
        for (DomElement e : main.getHtmlElementDescendants()) {
            String tag = e.getTagName();
            String caption = "input".equals(tag) ? e.getAttribute("value") : e.asNormalizedText();
            if (("a".equals(tag) || "button".equals(tag) || "input".equals(tag)) && "Cancel".equalsIgnoreCase(caption.trim())) {
                cancels.add(e);
            }
        }
        assertEquals(1, cancels.size(), "the Request Run page must offer exactly one Cancel control: " + cancels);
        DomElement cancel = cancels.get(0);
        assertEquals("a", cancel.getTagName(), "Cancel must be a link, not a submit control of the form: " + cancel.asXml());
        String jobPath = new URL(j.getURL(), job.getUrl()).getPath();
        assertEquals(jobPath, page.getFullyQualifiedUrl(cancel.getAttribute("href")).getPath(),
                "Cancel must lead to the job page: " + cancel.asXml());

        UsabilityFixtures.setField(form, "reason", "typed, then cancelled");
        UsabilityFixtures.tickApprovers(form, "a1");
        Set<String> before = ApproverFormFixtures.runRequestIds();
        Page landed = ((HtmlElement) cancel).click();
        wc.waitForBackgroundJavaScript(2000);
        assertEquals(200, landed.getWebResponse().getStatusCode(), "following Cancel must land on a page that opens");
        assertEquals(jobPath, landed.getUrl().getPath(), "following Cancel must land on the job page");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "Cancel must store no run request");

        HtmlForm again = TypedParameterFixtures.requestRunForm(j, wc, job);
        UsabilityFixtures.setField(again, "reason", "typed, then cancelled");
        UsabilityFixtures.tickApprovers(again, "a1");
        Page answer = HtmlFormUtil.submit(again);
        wc.waitForBackgroundJavaScript(5000);
        assertTrue(answer.getWebResponse().getStatusCode() < 400, "guard: the submitted form must succeed, got "
                + answer.getWebResponse().getStatusCode());
        Set<String> after = ApproverFormFixtures.runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "guard: submitting the same form stores exactly one run request, got " + after);
        assertEquals(RequestStatus.PENDING,
                RunRequestService.get().load(after.iterator().next()).getStatus(),
                "guard: the stored request is PENDING");
    }

    // ---------------------------------------------------------------- helpers

    private HtmlPage browserPage(String relative) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("u1");
        Page answer = wc.getPage(new WebRequest(new URL(j.getURL(), relative), HttpMethod.GET));
        assertEquals(200, answer.getWebResponse().getStatusCode(), "fixture: u1 opens " + relative);
        assertTrue(answer instanceof HtmlPage, "fixture: " + relative + " is an HTML page");
        return (HtmlPage) answer;
    }

    /** The {@code approvers} checkboxes of the Request Run form on {@code relative}, in page order, with their state. */
    private Map<String, Boolean> ticks(String relative) throws Exception {
        HtmlPage page = browserPage(relative);
        List<HtmlForm> forms = UsabilityFixtures.formsEndingWith(page, job.getUrl() + "batch-control/submit");
        assertEquals(1, forms.size(), "fixture: " + relative + " carries the Request Run form; forms: "
                + UsabilityFixtures.formActions(page));
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (DomElement e : forms.get(0).getHtmlElementDescendants()) {
            if (e instanceof HtmlCheckBoxInput box && "approvers".equals(box.getAttribute("name"))) {
                out.put(box.getValueAttribute(), box.isChecked());
            }
        }
        return out;
    }

    /**
     * Labels associated with {@code box}: {@code label for} its id, a label element around it, and the
     * {@code label.attach-previous} right after it (core's form convention, bound by core's script).
     */
    private static List<HtmlLabel> labelsOf(HtmlPage page, HtmlCheckBoxInput box) {
        List<HtmlLabel> out = new ArrayList<>();
        String id = box.getId();
        if (!id.isEmpty()) {
            for (DomNode n : page.querySelectorAll("label")) {
                if (id.equals(((DomElement) n).getAttribute("for"))) {
                    out.add((HtmlLabel) n);
                }
            }
        }
        for (DomNode n = box.getParentNode(); n != null; n = n.getParentNode()) {
            if (n instanceof HtmlLabel label && !out.contains(label)) {
                out.add(label);
            }
        }
        // core's form convention: a label.attach-previous right after the input is bound to it by core's script
        DomNode next = box.getNextElementSibling();
        if (next instanceof HtmlLabel label && label.getAttribute("class").contains("attach-previous") && !out.contains(label)) {
            out.add(label);
        }
        return out;
    }
}
