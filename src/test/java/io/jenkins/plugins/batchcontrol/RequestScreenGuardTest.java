package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.model.experimentalflags.UserExperimentalFlagsProperty;
import org.htmlunit.Page;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlFormUtil;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.runRequestIds;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server checks of the run request screens that only the retired legacy e2e drivers made (issue #31,
 * note 369): the Request Run form when no approver is configured (T-03-95,
 * {@code e2e/scripts/rest-approvers-empty.sh}), the Requester row of the request page (T-05-208,
 * {@code rest-screens.sh}), the form's title, headings and breadcrumb (T-UI-298, {@code e2e/r6/s4d.py},
 * {@code e2e/r7/n_classic.py}) and a refused submission that keeps the ticked approver and the typed
 * parameter value (T-05-209, {@code e2e/r11/s_rundlg_err.py}).
 *
 * <p>Users: {@code u1} and {@code u2} requesters (Item/Read, Item/Build, BatchControl/Request),
 * {@code a1} and {@code a2} approvers (Item/Read, BatchControl/Approve). Run control on; approvers
 * a1, a2; {@code batch-x} and {@code other-y} are approval-required with a string parameter
 * {@code DATE} (default {@value #DEFAULT_DATE}).
 *
 * <p>Written from docs/SPEC.md items 3, 5 and 6 (usability), docs/DECISIONS.md D-53 and
 * docs/TEST-MATRIX.md, with the legacy drivers read only to learn what each looked at; no src/main
 * knowledge.
 */
@WithJenkins
public class RequestScreenGuardTest {

    private static final String DEFAULT_DATE = "2026-01-01";
    private static final String TYPED_DATE = "2026-12-31";
    private static final String FLAG = "new-job-page.flag";
    private static final Pattern NO_APPROVERS = Pattern.compile("(?i)no approvers are configured");

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1", "u2")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2"));
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();
        job = approvalRequired("batch-x");
    }

    /**
     * T-03-95 (SPEC 3: the requester picks approvers from the configured list; D-53: an empty list is
     * refused on the form, so it is reachable only by a direct setter (JCasC, script console); SPEC 6
     * usability: a refusal says why and what to do instead, and a button is shown only to a user who
     * can use it). Guard first: with approvers a1 and a2 configured, u1's Request Run form offers both
     * as checkboxes and shows no "no approvers" alert. With the global list emptied by the setter, the
     * same page shows a {@code .jenkins-alert} in the main panel saying "No approvers are configured"
     * and naming an administrator, offers no approver checkbox and no Submit button.
     */
    @Test
    public void t_03_95_emptyApproverListShowsTheNoApproversAlert() throws Exception {
        HtmlPage withApprovers = formPage("u1", job);
        HtmlForm form = requestForm(withApprovers, job);
        assertEquals(List.of("a1", "a2"), UsabilityFixtures.approverChoices(form),
                "guard: with approvers configured the form offers each of them as a checkbox");
        assertTrue(noApproverAlerts(withApprovers).isEmpty(), "guard: no 'no approvers' alert while approvers are configured: "
                + excerpt(main(withApprovers).asNormalizedText()));

        cfg.setApprovers(new ArrayList<>());
        assertTrue(cfg.getApprovers() == null || cfg.getApprovers().isEmpty(),
                "premise: the direct setter emptied the global approver list (D-53 refuses it only on the form)");

        HtmlPage empty = formPage("u1", job);
        List<DomElement> alerts = noApproverAlerts(empty);
        assertEquals(1, alerts.size(), "the Request Run form must say in one .jenkins-alert that no approvers are"
                + " configured: " + excerpt(main(empty).asNormalizedText()));
        assertTrue(alerts.get(0).asNormalizedText().toLowerCase(java.util.Locale.ROOT).contains("administrator"),
                "the alert must say what to do instead (ask an administrator): " + alerts.get(0).asNormalizedText());
        assertTrue(empty.querySelectorAll("input[name=approvers]").isEmpty(),
                "no approver checkbox may be offered when none is configured: " + excerpt(main(empty).asXml()));
        for (DomNode n : main(empty).querySelectorAll("button, input[type=submit]")) {
            DomElement control = (DomElement) n;
            assertFalse(control.asNormalizedText().contains("Submit") || "Submit".equals(control.getAttribute("name")),
                    "no Submit control may be offered when the request cannot be made: " + control.asXml());
        }
    }

    /**
     * T-05-208 (SPEC 5: the approver decides on the request; SPEC 6 usability: recorded history names
     * who did what; T-E2E-05, e2e script rest-screens.sh): the designated approver a1 opens u1's PENDING
     * request page; its summary table has one row headed "Requester" whose cell reads {@code u1}. Guard:
     * on u2's request, viewed by the same a1, the cell reads {@code u2}, so it is the request's
     * requester and not the viewer or a constant.
     */
    @Test
    public void t_05_208_requestPageNamesTheRequester() throws Exception {
        RunRequest byU1 = create("u1", "a1", "month-end batch by u1");
        RunRequest byU2 = create("u2", "a1", "month-end batch by u2");
        assertEquals("u1", requesterCell("a1", byU1).asNormalizedText().trim(),
                "the Requester row of u1's request must name u1");
        assertEquals("u2", requesterCell("a1", byU2).asNormalizedText().trim(),
                "guard: the Requester row of u2's request must name u2");
    }

    /**
     * T-UI-298 (screen contract, e2e/r6/s4d.py and e2e/r7/n_classic.py): u1's Request Run form of
     * {@code batch-x} is titled {@code Request Run - batch-x} (core's " - Jenkins" suffix removed); its
     * {@code h1} headings in the main panel name the form ("Request Run") and the job ({@code batch-x});
     * its last breadcrumb reads "Request Run" and the one before it links the job. Checked on the
     * classic page and, with {@code new-job-page.flag} on, on the new job page. Guard: the form of
     * {@code other-y} is titled with {@code other-y} and names no {@code batch-x}, so the title is the
     * job's and not a constant.
     */
    @Test
    public void t_ui_298_requestRunFormNamesTheFormAndTheJob() throws Exception {
        FreeStyleProject other = approvalRequired("other-y");
        assertFormTitled("classic", formPage("u1", job), job);
        assertFormTitled("classic (guard)", formPage("u1", other), other);
        User.getById("u1", true).addProperty(new UserExperimentalFlagsProperty(Map.of(FLAG, "true")));
        assertFormTitled("new job page", formPage("u1", job), job);
        assertFormTitled("new job page (guard)", formPage("u1", other), other);
    }

    /**
     * T-05-209 (SPEC 5: a reason is required; SPEC 6 usability: "invalid input is refused with a message
     * next to the field and the user's input is kept"; the grant form's counterpart is T-GAP-344;
     * e2e/r11/s_rundlg_err.py): in a browser u1 opens the Request Run form of {@code batch-x}, leaves the
     * reason blank, ticks a2 only and types {@value #TYPED_DATE} into DATE (default
     * {@value #DEFAULT_DATE}), and submits. The answer is HTTP 400 with nothing stored, and the
     * re-rendered form has a2 ticked, a1 not ticked and DATE holding {@value #TYPED_DATE}. Guard: u1
     * then types a reason into that re-rendered form and submits it unchanged otherwise, which stores one
     * PENDING request designated to a2 with DATE {@value #TYPED_DATE}, so the kept state is the form's
     * state and not only text on the page.
     */
    @Test
    public void t_05_209_refusedRequestKeepsTheTickedApproverAndTheParameterValue() throws Exception {
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm form = TypedParameterFixtures.requestRunForm(j, wc, job);
        assertEquals(DEFAULT_DATE, TypedParameterFixtures.valueOf(form, "DATE"), "premise: DATE starts at its default");
        UsabilityFixtures.tickApprovers(form, "a2");
        TypedParameterFixtures.setValue(form, "DATE", TYPED_DATE);

        Set<String> before = runRequestIds();
        Page refused = HtmlFormUtil.submit(form);
        wc.waitForBackgroundJavaScript(5000);
        assertEquals(400, refused.getWebResponse().getStatusCode(), "a submission without a reason must be refused with"
                + " HTTP 400: " + excerpt(refused.getWebResponse().getContentAsString()));
        assertEquals(before, runRequestIds(), "a refused submission stores no request");
        assertTrue(refused instanceof HtmlPage, "the refusal must re-render the form as an HTML page");
        HtmlForm again = requestForm((HtmlPage) refused, job);
        assertEquals(List.of("a2"), ticked(again), "the re-rendered form must keep a2 ticked and nothing else");
        assertEquals(TYPED_DATE, TypedParameterFixtures.valueOf(again, "DATE"),
                "the re-rendered form must keep the typed DATE, not fall back to the default");

        UsabilityFixtures.setField(again, "reason", "month-end rerun after the fix");
        Page accepted = HtmlFormUtil.submit(again);
        wc.waitForBackgroundJavaScript(5000);
        assertTrue(accepted.getWebResponse().getStatusCode() < 400, "guard: the re-rendered form with a reason is accepted,"
                + " got HTTP " + accepted.getWebResponse().getStatusCode() + ": " + excerpt(accepted.getWebResponse().getContentAsString()));
        Set<String> added = runRequestIds();
        added.removeAll(before);
        assertEquals(1, added.size(), "guard: one request stored, got " + added);
        RunRequest stored = RunRequestService.get().load(added.iterator().next());
        assertEquals(RequestStatus.PENDING, stored.getStatus(), "guard: the stored request is PENDING");
        assertEquals(List.of("a2"), stored.getApprovers(), "guard: the kept tick is what was submitted");
        assertEquals(TYPED_DATE, stored.getParameters().get("DATE"), "guard: the kept DATE is what was submitted; stored "
                + stored.getParameters());
    }

    // ---------------------------------------------------------------- helpers

    private FreeStyleProject approvalRequired(String name) throws Exception {
        FreeStyleProject created = j.createFreeStyleProject(name);
        created.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("DATE", DEFAULT_DATE)));
        setBatchControl(created, new BatchControlJobProperty(true));
        return created;
    }

    private RunRequest create(String requester, String approver, String reason) {
        try (ACLContext ignored = ACL.as2(User.getById(requester, true).impersonate2())) {
            return RunRequestService.get().create(job, new LinkedHashMap<>(), reason, approver);
        }
    }

    private HtmlPage formPage(String user, FreeStyleProject target) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(j, user, target.getUrl() + "batch-control/");
        assertEquals(200, page.getWebResponse().getStatusCode(), "premise: " + user + " opens the Request Run form of "
                + target.getFullName());
        return page;
    }

    private static HtmlForm requestForm(HtmlPage page, FreeStyleProject target) throws Exception {
        HtmlForm form = UsabilityFixtures.formsEndingWith(page, target.getUrl() + "batch-control/submit").stream()
                .findFirst().orElse(null);
        assertNotNull(form, "the page must carry the Request Run form of " + target.getFullName() + "; forms: "
                + UsabilityFixtures.formActions(page));
        return form;
    }

    private static DomElement main(HtmlPage page) {
        DomElement main = page.getElementById("main-panel");
        assertNotNull(main, "the page must have a main panel");
        return main;
    }

    /** The {@code .jenkins-alert} elements of the main panel that say no approvers are configured. */
    private static List<DomElement> noApproverAlerts(HtmlPage page) {
        List<DomElement> out = new ArrayList<>();
        for (DomNode n : main(page).querySelectorAll(".jenkins-alert")) {
            if (NO_APPROVERS.matcher(n.asNormalizedText()).find()) {
                out.add((DomElement) n);
            }
        }
        return out;
    }

    /** The values of the ticked {@code approvers} checkboxes, in page order. */
    private static List<String> ticked(HtmlForm form) {
        List<String> out = new ArrayList<>();
        for (DomElement e : form.getHtmlElementDescendants()) {
            if (e instanceof HtmlCheckBoxInput box && "approvers".equals(box.getAttribute("name")) && box.isChecked()) {
                out.add(box.getValue());
            }
        }
        return out;
    }

    /** The cell of the request page's summary row headed exactly "Requester", as {@code viewer} sees it. */
    private DomElement requesterCell(String viewer, RunRequest request) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(j, viewer, "batch-control/requests/" + request.getId() + "/");
        assertEquals(200, page.getWebResponse().getStatusCode(), "premise: " + viewer + " opens the request page");
        List<DomElement> cells = new ArrayList<>();
        for (DomNode th : main(page).querySelectorAll("table tr > th")) {
            if ("Requester".equals(th.asNormalizedText().trim())) {
                DomNode td = th.getParentNode().querySelector("td");
                assertNotNull(td, "the Requester row must have a value cell");
                cells.add((DomElement) td);
            }
        }
        assertEquals(1, cells.size(), "the request page must have exactly one row headed 'Requester': "
                + excerpt(main(page).asNormalizedText()));
        return cells.get(0);
    }

    private void assertFormTitled(String where, HtmlPage page, FreeStyleProject target) throws Exception {
        String name = target.getFullName();
        String title = page.getTitleText() == null ? "" : page.getTitleText().trim();
        if (title.endsWith(" - Jenkins")) {
            title = title.substring(0, title.length() - " - Jenkins".length());
        }
        assertEquals("Request Run - " + name, title, where + ": the <title> must name the form and the job");
        List<String> headings = new ArrayList<>();
        for (DomNode h : main(page).querySelectorAll("h1")) {
            headings.add(h.asNormalizedText().trim());
        }
        assertTrue(headings.stream().anyMatch(h -> h.contains("Request Run")), where + ": an h1 must name the form: " + headings);
        assertTrue(headings.stream().anyMatch(h -> Pattern.compile("(?<![\\w-])" + Pattern.quote(name) + "(?![\\w-])")
                .matcher(h).find()), where + ": an h1 must name the job " + name + ": " + headings);
        for (String other : new String[] {"batch-x", "other-y"}) {
            if (!other.equals(name)) {
                assertFalse(title.contains(other) || headings.stream().anyMatch(h -> h.contains(other)),
                        where + ": the form of " + name + " must not name " + other + ": " + title + " " + headings);
            }
        }
        List<DomElement> crumbs = page.getByXPath("//li[contains(concat(' ', normalize-space(@class), ' '),"
                + " ' jenkins-breadcrumbs__list-item ')]");
        assertTrue(crumbs.size() >= 2, where + ": the page must render breadcrumbs");
        assertEquals("Request Run", crumbs.get(crumbs.size() - 1).asNormalizedText().trim(),
                where + ": the last breadcrumb must read 'Request Run'");
        DomNode jobCrumb = crumbs.get(crumbs.size() - 2).querySelector("a[href]");
        assertNotNull(jobCrumb, where + ": the breadcrumb before 'Request Run' must link the job");
        URL linked = page.getFullyQualifiedUrl(((DomElement) jobCrumb).getAttribute("href"));
        assertEquals(new URL(j.getURL(), target.getUrl()).getPath(), linked.getPath(),
                where + ": the breadcrumb before 'Request Run' must link " + name);
    }
}
