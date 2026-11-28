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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.FormEncodingType;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlFormUtil;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextArea;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.runRequestIds;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Bug hunt B, R4-03 (matrix rows T-05-139 .. T-05-141, note 295): non-ASCII text typed into the
 * Request Run form survives a refusal and is stored exactly as typed, whatever the form encoding.
 *
 * <p>Basis: SPEC 5 (the request keeps the reason and the submitted parameter values), SPEC 6
 * usability ("invalid input is refused with a message next to the field and the user's input is
 * kept") and D-72 (the Request Run form posts {@code multipart/form-data}), and the frozen bug-hunt
 * contract: on the multipart Request Run form every plain text field the server reads (the reason,
 * and the raw string parameters and approvers when no {@code json} field is sent) is read as UTF-8.
 * A refused submission re-renders exactly the text typed and the stored reason equals the submitted
 * text, for (a) the browser flow, (b) multipart with the {@code json} field and (c) multipart
 * without it. The url-encoded path is unchanged (guards).
 *
 * <p>The refusal used throughout is a submission with no approver ticked (SPEC 3: a request needs a
 * designated approver). Text parts carry no part charset, as a browser sends them; the bytes are
 * UTF-8 ({@link ApproverFormFixtures} conventions, T-03-32).
 *
 * <p>Users: {@code u1} requester, {@code a1}..{@code a3} approvers. Written from docs/SPEC.md items 3,
 * 5 and 6, DECISIONS D-72 and the bug-hunt B contract only (no src/main knowledge).
 */
@WithJenkins
public class RequestFormEncodingTest {

    /** The reason of the real-Jenkins reproduction: Hangul, an em dash and Latin-1 letters with diacritics. */
    private static final String REASON = "월말 정산 재실행 — ünïcödé";

    /** A non-ASCII string parameter value for the raw (no {@code json}) path. */
    private static final String NOTE_VALUE = "정산 메모 — çàé";

    private JenkinsRule j;
    private FreeStyleProject job;
    private FreeStyleProject paramJob;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2", "a3"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2", "a3"));
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
        paramJob = j.createFreeStyleProject("param-x");
        paramJob.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("NOTE", "n-default")));
        setBatchControl(paramJob, new BatchControlJobProperty(true));
    }

    /**
     * T-05-139 (a, browser flow): u1 opens the Request Run form of {@code batch-x} in HtmlUnit
     * (JavaScript on, core's form script posts it), types {@link #REASON}, ticks no approver and
     * submits: refused (4xx), nothing stored, and the re-rendered reason field holds exactly the text
     * typed. u1 then ticks a1 on the re-rendered form and submits: one PENDING request whose stored
     * reason is exactly {@link #REASON}. Guard first: the same reason submitted correctly the first
     * time (a1 ticked) is stored exactly.
     */
    @Test
    public void t_05_139_browserRefusalKeepsAndStoresNonAsciiReason() throws Exception {
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm direct = TypedParameterFixtures.requestRunForm(j, wc, job);
        assertEquals("multipart/form-data", direct.getEnctypeAttribute().toLowerCase(Locale.ROOT),
                "premise: the Request Run form posts multipart/form-data (D-72)");
        String directId = submitForm(wc, direct, REASON, "a1");
        assertEquals(REASON, RunRequestService.get().load(directId).getReason(),
                "guard: a first-time browser submission stores the reason as typed");
        cancelGuardRequest(directId); // D-88: the resubmission below is identical to it (note 359)

        Set<String> before = runRequestIds();
        HtmlForm form = TypedParameterFixtures.requestRunForm(j, wc, job);
        UsabilityFixtures.setField(form, "reason", REASON);
        UsabilityFixtures.tickApprovers(form);
        Page refused = HtmlFormUtil.submit(form);
        PageScriptFixtures.settle(wc);
        assertRefusedNothingStored("the browser submission with no approver ticked", refused, before);
        assertEquals(REASON, keptReason(refused, "the re-rendered Request Run form"),
                "a refused multipart Request Run submission must re-render the reason exactly as typed" + garbledHint());

        HtmlForm again = UsabilityFixtures.formsEndingWith((HtmlPage) refused, job.getUrl() + "batch-control/submit")
                .stream().findFirst().orElseThrow(() -> new AssertionError("the refusal must re-render the Request Run form"));
        String id = submitForm(wc, again, null, "a1"); // what the re-rendered form holds, as the user resubmits it
        RunRequest stored = RunRequestService.get().load(id);
        assertEquals(RequestStatus.PENDING, stored.getStatus());
        assertEquals(REASON, stored.getReason(),
                "the request resubmitted from the re-rendered form must store the reason as typed" + garbledHint());
    }

    /**
     * T-05-140 (b, multipart with the {@code json} field): a multipart {@code submit} shaped as the
     * form posts it (raw parts plus core's {@code json} field, as T-03-28) with {@link #REASON} and no
     * approver is refused (4xx), stores nothing and re-renders the reason exactly; the same with a1
     * stores exactly {@link #REASON}. Guards first: the url-encoded path is unchanged, its refusal
     * re-renders {@link #REASON} exactly and its accepted submission stores it exactly.
     */
    @Test
    public void t_05_140_multipartWithJsonRefusalKeepsAndStoresNonAsciiReason() throws Exception {
        Set<String> before = runRequestIds();
        Page urlencodedRefused = post(job, false, fields(REASON, null, false));
        assertRefusedNothingStored("guard: the url-encoded submission with no approver", urlencodedRefused, before);
        assertEquals(REASON, keptReason(urlencodedRefused, "the url-encoded refusal"),
                "guard: the url-encoded refusal re-renders the reason exactly");
        String urlencodedId = accepted(job, false, fields(REASON, "a1", false), "guard: the url-encoded submission with a1");
        assertEquals(REASON, RunRequestService.get().load(urlencodedId).getReason(),
                "guard: the url-encoded submission stores the reason exactly");
        cancelGuardRequest(urlencodedId); // D-88: the multipart submission below is identical to it (note 359)

        Set<String> beforeMultipart = runRequestIds();
        Page refused = post(job, true, withJson(fields(REASON, null, false), REASON));
        assertRefusedNothingStored("the multipart submission with the json field and no approver", refused, beforeMultipart);
        String id = accepted(job, true, withJson(fields(REASON, "a1", false), REASON, "a1"),
                "the multipart submission with the json field and a1");
        String stored = RunRequestService.get().load(id).getReason();
        assertAll("multipart Request Run submission with the json field",
                () -> assertEquals(REASON, keptReason(refused, "the multipart (json) refusal"),
                        "a refused multipart submission must re-render the reason exactly as sent" + garbledHint()),
                () -> assertEquals(REASON, stored,
                        "an accepted multipart submission must store the reason exactly as sent" + garbledHint()));
    }

    /**
     * T-05-141 (c, multipart without the {@code json} field, a script's shape): a multipart
     * {@code submit} of {@code param-x} with the raw parts {@code reason}={@link #REASON} and
     * {@code NOTE}={@link #NOTE_VALUE} and no approver is refused (4xx), stores nothing and re-renders
     * the reason exactly; the same with {@code approvers}=a1 stores exactly that reason and NOTE value.
     * Guard first: the same raw fields url-encoded store both exactly (T-SEC-44 shape).
     */
    @Test
    public void t_05_141_multipartWithoutJsonRefusalKeepsAndStoresNonAsciiText() throws Exception {
        String urlencodedId = accepted(paramJob, false, fields(REASON, "a1", true), "guard: the url-encoded raw submission");
        RunRequest urlencoded = RunRequestService.get().load(urlencodedId);
        assertEquals(REASON, urlencoded.getReason(), "guard: the url-encoded raw submission stores the reason exactly");
        assertEquals(NOTE_VALUE, urlencoded.getParameters().get("NOTE"),
                "guard: the url-encoded raw submission stores NOTE exactly; stored " + urlencoded.getParameters());
        cancelGuardRequest(urlencodedId); // D-88: the multipart submission below is identical to it (note 359)

        Set<String> before = runRequestIds();
        Page refused = post(paramJob, true, fields(REASON, null, true));
        assertRefusedNothingStored("the multipart submission without json and no approver", refused, before);
        String id = accepted(paramJob, true, fields(REASON, "a1", true), "the multipart submission without json and a1");
        RunRequest stored = RunRequestService.get().load(id);
        assertAll("multipart Request Run submission without the json field",
                () -> assertEquals(REASON, keptReason(refused, "the multipart (no json) refusal"),
                        "a refused multipart submission without json must re-render the reason exactly as sent" + garbledHint()),
                () -> assertEquals(REASON, stored.getReason(),
                        "a multipart submission without json must store the reason exactly as sent" + garbledHint()),
                () -> assertEquals(NOTE_VALUE, stored.getParameters().get("NOTE"),
                        "a multipart submission without json must store the raw string parameter exactly as sent; stored "
                                + stored.getParameters()));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Amended for D-88 (note 359): an identical pending run request is not created twice, so the
     * guard's request (same job, reason, parameters and approver as the later submission) is
     * cancelled by its requester first; the later submission still sends exactly the same text.
     */
    private static void cancelGuardRequest(String id) {
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            RunRequestService.get().cancel(id);
        }
        assertEquals(RequestStatus.CANCELLED, RunRequestService.get().load(id).getStatus(),
                "fixture: the guard's request is cancelled before the identical submission (D-88)");
    }

    /** reason, optionally one approvers part, optionally the raw NOTE part (parameter named like buildWithParameters). */
    private static List<NameValuePair> fields(String reason, String approver, boolean note) {
        List<NameValuePair> out = new ArrayList<>();
        out.add(new NameValuePair("reason", reason));
        if (approver != null) {
            out.add(new NameValuePair("approvers", approver));
        }
        if (note) {
            out.add(new NameValuePair("NOTE", NOTE_VALUE));
        }
        return out;
    }

    /** Appends core's structured {@code json} field as the form posts it: reason and the approvers array (T-03-28). */
    private static List<NameValuePair> withJson(List<NameValuePair> fields, String reason, String... approvers) {
        JSONObject json = new JSONObject();
        json.put("reason", reason);
        json.put("approvers", JSONArray.fromObject(approvers));
        List<NameValuePair> out = new ArrayList<>(fields);
        out.add(new NameValuePair("json", json.toString()));
        return out;
    }

    /** u1 POSTs {@code fields} to the job's {@code batch-control/submit} (crumb in the query, UTF-8 bytes, redirects off). */
    private Page post(FreeStyleProject target, boolean multipart, List<NameValuePair> fields) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");
        WebRequest request = new WebRequest(wc.createCrumbedUrl(target.getUrl() + "batch-control/submit"), HttpMethod.POST);
        if (multipart) {
            request.setEncodingType(FormEncodingType.MULTIPART);
        }
        request.setCharset(StandardCharsets.UTF_8);
        request.setRequestParameters(new ArrayList<>(fields));
        return wc.getPage(request);
    }

    /** {@link #post} that must be accepted and create exactly one request; returns its id. */
    private String accepted(FreeStyleProject target, boolean multipart, List<NameValuePair> fields, String what) throws Exception {
        Set<String> before = runRequestIds();
        Page answer = post(target, multipart, fields);
        ApproverFormFixtures.assertSuccess(answer.getWebResponse(), "fixture: " + what);
        Set<String> after = runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "fixture: " + what + " must create exactly one run request, got " + after);
        return after.iterator().next();
    }

    /**
     * Types {@code reason} (null: leaves the field as the page rendered it), ticks exactly
     * {@code approvers} and submits as a browser does; returns the one request created.
     */
    private String submitForm(JenkinsRule.WebClient wc, HtmlForm form, String reason, String... approvers) throws Exception {
        Set<String> before = runRequestIds();
        if (reason != null) {
            UsabilityFixtures.setField(form, "reason", reason);
        }
        UsabilityFixtures.tickApprovers(form, approvers);
        Page answer = HtmlFormUtil.submit(form);
        PageScriptFixtures.settle(wc);
        assertTrue(answer.getWebResponse().getStatusCode() < 400, "fixture: the Request Run submission with "
                + Arrays.toString(approvers) + " must succeed, got HTTP " + answer.getWebResponse().getStatusCode()
                + ApproverFormFixtures.alerts(answer.getWebResponse().getContentAsString()) + ": "
                + UsabilityFixtures.excerpt(answer.getWebResponse().getContentAsString()));
        Set<String> after = runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "fixture: the submission must create exactly one run request, got " + after);
        return after.iterator().next();
    }

    private static void assertRefusedNothingStored(String what, Page answer, Set<String> before) {
        int code = answer.getWebResponse().getStatusCode();
        assertTrue(code >= 400 && code < 500, "premise: " + what + " is refused with HTTP 4xx, got HTTP " + code + ": "
                + UsabilityFixtures.excerpt(answer.getWebResponse().getContentAsString()));
        assertEquals(before, runRequestIds(), "premise: " + what + " stores no run request");
    }

    /** The value of the re-rendered {@code reason} field of a refusal page. */
    private static String keptReason(Page answer, String what) {
        assertTrue(answer instanceof HtmlPage, what + " must be an HTML page re-rendering the form, got "
                + answer.getWebResponse().getContentType() + ": " + UsabilityFixtures.excerpt(answer.getWebResponse().getContentAsString()));
        List<String> values = new ArrayList<>();
        for (DomElement element : ((HtmlPage) answer).getElementsByTagName("textarea")) {
            String name = element.getAttribute("name");
            if (element instanceof HtmlTextArea && ("reason".equals(name) || name.endsWith(".reason"))) {
                values.add(((HtmlTextArea) element).getText());
            }
        }
        assertFalse(values.isEmpty(), what + " must carry the reason field: "
                + UsabilityFixtures.excerpt(answer.getWebResponse().getContentAsString()));
        if (values.size() > 1 && !values.stream().allMatch(values.get(0)::equals)) {
            fail(what + " carries several differing reason fields: " + values);
        }
        return values.get(0);
    }

    private static String garbledHint() {
        return " (the same bytes read as ISO-8859-1 would be "
                + new String(REASON.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1) + ")";
    }
}
