package io.jenkins.plugins.batchcontrol;

import hudson.model.FileParameterDefinition;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.PasswordParameterValue;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.file_parameters.StashedFileParameterDefinition;
import io.jenkins.plugins.file_parameters.StashedFileParameterValue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.FormEncodingType;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.KeyDataPair;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.MASK;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.added;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.deleteValuesFile;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.duplicateTypedValue;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.editRequestFile;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.editValuesFile;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileDisplay;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileItem;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.holdsEncrypted;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.markReason;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.renameTypedValue;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.requestDirListing;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.requestXml;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.retypeTypedValue;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.tempFiles;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.valuesFile;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.valuesFileName;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.valuesPath;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.valuesXml;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 5, D-72b (1)-(3) (security-35 S-35-01, S-35-03, S-35-04; spec-review-S7 m-4): each
 * parameter name appears at most once in a run request, and a repeated name is refused before
 * anything is stored; approval refuses a request whose stored values do not match the displayed
 * ones (repeated or differing names, a value that cannot be loaded) and a request stored before
 * D-72; every stored value obeys the display length limit and contains only characters XML can
 * store; a failed save leaves nothing behind. Matrix rows T-05-70 .. T-05-81 (note 265), and
 * T-05-136/137 (XML-illegal characters in a decision comment and in a parameter name, LIMITATIONS
 * 31; note 273).
 *
 * <p>Form rows post the Request Run endpoint the way the rendered form does (core's structured
 * {@code json} field; multipart when files are involved; raw fields for the plain channel). A field
 * error is pinned as HTTP 400 with an HTML page that is not core's bare error page, shows no crash
 * text, carries the form again (T-05-18, T-UI-117) and adds an explanation that names the field or
 * sits in the field's own parameter block (T-UI-07 precedent). Wording is not pinned.
 *
 * <p>Rows about stored values edit the request's values file {@code requests/run/<id>.values.xml}
 * on disk (D-74 (1); note 268), as an administrator's edit or a damaged disk would leave it, or
 * delete it (a request whose stored values are missing), and first prove that the plugin reads the
 * request from disk (a marker appended to the reason in {@code requests/run/<id>.xml} is read
 * back). Each refusal is paired with an unedited twin request of the same job that is approved and
 * runs with its own value.
 *
 * <p>Written from docs/SPEC.md item 5, docs/DECISIONS.md D-72, D-72b and D-74, docs/LIMITATIONS.md
 * item 31, and the Given/When/Then of docs/reports/security-35.md, docs/reports/spec-review-S7.md
 * and docs/reports/spec-review-r6.md only (no src/main knowledge).
 */
@WithJenkins
public class TypedParameterIntegrityTest {

    private static final String REASON = "month-end batch with typed values";
    private static final String EDIT_MARK = " [edited on disk d72b]";

    private JenkinsRule j;

    /** The descriptor of the Secret-carrying parameter type used by T-05-74. */
    @TestExtension
    public static final class TokenDescriptor extends CustomParameterFixtures.TokenDescriptorBase {
    }

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        TypedParameterFixtures.CaptureEnv.SEEN.clear();
    }

    // ================================================================ a parameter name at most once

    /**
     * T-05-70 (S-35-01): u1, holding only Request and Item/Read, posts the form's {@code json} with
     * TARGET twice ({@code staging}, then {@code production}): 400 field error naming TARGET; no
     * request, no file of any name under {@code requests/run/}, nothing queued. Guard: the same
     * post with distinct names (TARGET, DATE) is accepted and shows exactly those values.
     */
    @Test
    public void t_05_70_repeatedNameInTheFormJsonIsRefusedBeforeAnythingIsStored() throws Exception {
        FreeStyleProject job = job("dup-json", new StringParameterDefinition("TARGET", "default-target"),
                new StringParameterDefinition("DATE", "2000-01-01"));
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);

        Page refused = postJson("u1", job, form(REASON, value("TARGET", "staging"), value("TARGET", "production")));
        assertFieldRefusal("a repeated parameter name", refused, job, "TARGET");
        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "a repeated name must store no request");
        assertEquals(listing, requestDirListing(j), "a repeated name must leave no file of any name under requests/run/");
        assertNothingRan(job);

        String id = acceptedOne("distinct names",
                postJson("u1", job, form(REASON, value("TARGET", "staging"), value("DATE", "2026-10-01"))), ids);
        assertEquals(Map.of("TARGET", "staging", "DATE", "2026-10-01"), RunRequestService.get().load(id).getParameters(),
                "guard: distinct names are accepted with exactly the submitted values");
    }

    /**
     * T-05-71 (S-35-01, files): the form's {@code json} names the core file parameter UPLOAD twice,
     * each pointing at its own uploaded part (multipart): 400 field error naming UPLOAD; no request;
     * neither upload is kept under the documented temporary directories. Guard: one UPLOAD part
     * posted the same way is accepted and shown as {@code [file] first.csv}, and its file is held.
     */
    @Test
    public void t_05_71_repeatedFileParameterIsRefusedAndKeepsNoUpload() throws Exception {
        FreeStyleProject job = job("dup-file", new FileParameterDefinition("UPLOAD", "core file"),
                new StringParameterDefinition("DATE", "2000-01-01"));
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<Path> temp = tempFiles(j);

        List<NameValuePair> twice = new ArrayList<>();
        twice.add(new NameValuePair("json", form(REASON, fileRef("UPLOAD", "file0"), fileRef("UPLOAD", "file1"),
                value("DATE", "2026-10-01")).toString()));
        twice.add(filePart("file0", "first.csv", payload("dup-first-marker-Qa71", 1500)));
        twice.add(filePart("file1", "second.csv", payload("dup-second-marker-Qb71", 1500)));
        Page refused = postMultipart("u1", job, twice);
        assertFieldRefusal("a repeated file parameter", refused, job, "UPLOAD");
        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "a repeated file parameter must store no request");
        assertEquals(Set.of(), added(temp, tempFiles(j)), "a refused submission must keep neither upload");
        assertNothingRan(job);

        List<NameValuePair> once = new ArrayList<>();
        once.add(new NameValuePair("json", form(REASON, fileRef("UPLOAD", "file0"), value("DATE", "2026-10-01")).toString()));
        once.add(filePart("file0", "first.csv", payload("single-first-marker-Qc71", 1500)));
        String id = acceptedOne("one file part", postMultipart("u1", job, once), ids);
        assertEquals(fileDisplay("first.csv"), RunRequestService.get().load(id).getParameters().get("UPLOAD"),
                "guard: the same channel with one UPLOAD is accepted");
        assertFalse(added(temp, tempFiles(j)).isEmpty(), "guard: the accepted request holds its file, so the detector above sees uploads");
    }

    /**
     * T-05-72 (S-35-01, raw fields): a url-encoded post without {@code json} that carries the field
     * TARGET twice is refused with a 400 field error naming TARGET and stores nothing. Guard: the
     * same post with TARGET once is accepted and stores {@code staging}.
     */
    @Test
    public void t_05_72_repeatedRawFieldIsRefused() throws Exception {
        FreeStyleProject job = job("dup-raw", new StringParameterDefinition("TARGET", "default-target"));
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);

        List<NameValuePair> twice = rawFields();
        twice.add(new NameValuePair("TARGET", "staging"));
        twice.add(new NameValuePair("TARGET", "production"));
        assertFieldRefusal("a repeated raw field", postFields("u1", job, twice), job, "TARGET");
        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "a repeated raw field must store no request");
        assertEquals(listing, requestDirListing(j), "nothing may be written under requests/run/");
        assertNothingRan(job);

        List<NameValuePair> once = rawFields();
        once.add(new NameValuePair("TARGET", "staging"));
        String id = acceptedOne("one raw TARGET", postFields("u1", job, once), ids);
        assertEquals("staging", RunRequestService.get().load(id).getParameters().get("TARGET"), "guard: the raw channel stores the value");
    }

    /**
     * T-05-73 (S-35-01, service): {@code RunRequestService.create(job, [TARGET=staging,
     * TARGET=production], ...)} and a create with two stashed-file values named DATA each throw
     * {@code IllegalArgumentException}; no request and no file of any name under
     * {@code requests/run/}. Guard: distinct names are accepted (PENDING, exactly those values).
     */
    @Test
    public void t_05_73_serviceRefusesARepeatedNameWithIllegalArgumentException() throws Exception {
        FreeStyleProject job = job("dup-svc", new StringParameterDefinition("TARGET", "default-target"),
                new StringParameterDefinition("DATE", "2000-01-01"), new StashedFileParameterDefinition("DATA"));
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);

        List<ParameterValue> strings = new ArrayList<>();
        strings.add(new StringParameterValue("TARGET", "staging"));
        strings.add(new StringParameterValue("TARGET", "production"));
        assertThrows(IllegalArgumentException.class, () -> createTyped(job, strings), "a repeated string name must be refused");

        List<ParameterValue> files = new ArrayList<>();
        files.add(new StashedFileParameterValue("DATA", fileItem("a.bin", payload("dup-svc-a-marker", 600))));
        files.add(new StashedFileParameterValue("DATA", fileItem("b.bin", payload("dup-svc-b-marker", 600))));
        files.add(new StringParameterValue("DATE", "2026-10-01"));
        assertThrows(IllegalArgumentException.class, () -> createTyped(job, files), "a repeated file name must be refused");

        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "nothing may be stored from the refused calls");
        assertEquals(listing, requestDirListing(j), "no file of any name may be left under requests/run/");
        assertNothingRan(job);

        List<ParameterValue> distinct = new ArrayList<>();
        distinct.add(new StringParameterValue("TARGET", "staging"));
        distinct.add(new StringParameterValue("DATE", "2026-10-01"));
        RunRequest ok = createTyped(job, distinct);
        assertEquals(RequestStatus.PENDING, ok.getStatus());
        assertEquals("staging", RunRequestService.get().load(ok.getId()).getParameters().get("TARGET"), "guard: distinct names are accepted");
    }

    // ================================================================ the display length limit for every stored value

    /**
     * T-05-74 (S-35-01/02): through the service, a Password value of 10,001 characters and a
     * Secret-carrying value of another type ({@code TokenParameterValue}, shown as a mask) of
     * 10,001 characters are each refused (IllegalArgumentException or Failure) and nothing is
     * stored. Boundary guard: both at exactly 10,000 characters are accepted, and the password is
     * held encrypted in the request's values file (D-74).
     */
    @Test
    public void t_05_74_nonDisplayedValuesOverTheLimitAreRefusedByTheService() throws Exception {
        FreeStyleProject job = job("len-svc",
                new PasswordParameterDefinition("TOKEN", Secret.fromString("len-default"), "token"),
                new CustomParameterFixtures.TokenParameterDefinition("KEY"),
                new StringParameterDefinition("PLAIN", "plain-default"));
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);

        assertRefusedAsInvalid("a password over 10,000 characters must be refused",
                () -> createTyped(job, List.of(new PasswordParameterValue("TOKEN", "p".repeat(10_001)), new StringParameterValue("PLAIN", "x"))));
        assertRefusedAsInvalid("a Secret-carrying value over 10,000 characters must be refused",
                () -> createTyped(job, List.of(new CustomParameterFixtures.TokenParameterValue("KEY", Secret.fromString("k".repeat(10_001))),
                        new StringParameterValue("PLAIN", "x"))));
        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "nothing may be stored from the refused calls");
        assertEquals(listing, requestDirListing(j), "no file of any name may be left under requests/run/");

        List<ParameterValue> atLimit = new ArrayList<>();
        atLimit.add(new PasswordParameterValue("TOKEN", "p".repeat(10_000)));
        atLimit.add(new CustomParameterFixtures.TokenParameterValue("KEY", Secret.fromString("k".repeat(10_000))));
        atLimit.add(new StringParameterValue("PLAIN", "x"));
        RunRequest ok = createTyped(job, atLimit);
        assertEquals(RequestStatus.PENDING, ok.getStatus(), "boundary: 10,000 characters are accepted");
        assertEquals(MASK, RunRequestService.get().load(ok.getId()).getParameters().get("TOKEN"));
        assertTrue(holdsEncrypted(valuesFile(j, ok.getId()), "p".repeat(10_000)),
                "boundary: the password is held encrypted in the values file (D-74)");
    }

    /**
     * T-05-75 (S-35-01/02, form): u1 posts the form with a 10,001-character password TOKEN: 400
     * field error naming TOKEN, nothing stored. Boundary guard: 10,000 characters are accepted and
     * shown as {@code ********}.
     */
    @Test
    public void t_05_75_passwordOverTheLimitIsRefusedOnTheForm() throws Exception {
        FreeStyleProject job = job("len-form", new PasswordParameterDefinition("TOKEN", Secret.fromString("len-default"), "token"),
                new StringParameterDefinition("PLAIN", "plain-default"));
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);

        Page refused = postJson("u1", job, form(REASON, value("TOKEN", "p".repeat(10_001)), value("PLAIN", "x")));
        assertFieldRefusal("a password over 10,000 characters", refused, job, "TOKEN");
        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "nothing may be stored");
        assertEquals(listing, requestDirListing(j), "no file of any name may be left under requests/run/");

        String id = acceptedOne("a 10,000-character password",
                postJson("u1", job, form(REASON, value("TOKEN", "p".repeat(10_000)), value("PLAIN", "x"))), ids);
        assertEquals(MASK, RunRequestService.get().load(id).getParameters().get("TOKEN"), "boundary: accepted and masked");
    }

    // ================================================================ characters XML can store

    /**
     * T-05-76 (S-35-03, form): u1 posts the form with a TARGET value containing U+0000, then with a
     * reason containing U+0000: each answers a 400 field error (not 500) naming the field; no
     * request, no file of any name (no temporary file) under {@code requests/run/}, no upload kept.
     * Guard: the same value without the character is accepted.
     */
    @Test
    public void t_05_76_xmlIllegalCharacterIsAFieldErrorOnTheForm() throws Exception {
        FreeStyleProject job = job("xml-form", new StringParameterDefinition("TARGET", "default-target"));
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);
        Set<Path> temp = tempFiles(j);

        assertFieldRefusal("a value with U+0000", postJson("u1", job, form(REASON, value("TARGET", "bad\u0000value"))), job, "TARGET");
        assertFieldRefusal("a reason with U+0000", postJson("u1", job, form("month-end\u0000batch", value("TARGET", "fine"))), job, "reason");
        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "nothing may be stored");
        assertEquals(listing, requestDirListing(j), "a failed save must leave no file of any name under requests/run/");
        assertEquals(temp, tempFiles(j), "nothing may be kept under the temporary directories");
        assertNothingRan(job);

        String id = acceptedOne("the value without U+0000", postJson("u1", job, form(REASON, value("TARGET", "badvalue"))), ids);
        assertEquals("badvalue", RunRequestService.get().load(id).getParameters().get("TARGET"));
    }

    /**
     * T-05-77 (S-35-03, service): {@code create} with a value containing U+0000 after a valid one,
     * with a value containing U+FFFF, and with a reason containing U+0000 is each refused
     * (IllegalArgumentException or Failure) and leaves the {@code requests/run/} listing unchanged
     * (no temporary file). Guard: valid values add exactly {@code <id>.xml} and
     * {@code <id>.values.xml} (D-74).
     */
    @Test
    public void t_05_77_xmlIllegalCharacterIsRefusedByTheServiceWithoutLeftovers() throws Exception {
        FreeStyleProject job = job("xml-svc", new StringParameterDefinition("X", "x-default"), new StringParameterDefinition("Y", "y-default"));
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);

        assertRefusedAsInvalid("a value with U+0000 must be refused",
                () -> createTyped(job, List.of(new StringParameterValue("X", "x".repeat(5_000)), new StringParameterValue("Y", "bad\u0000value"))));
        assertEquals(listing, requestDirListing(j), "a refused value must leave no file of any name under requests/run/");
        assertRefusedAsInvalid("a value with U+FFFF must be refused",
                () -> createTyped(job, List.of(new StringParameterValue("Y", "bad￿value"))));
        assertEquals(listing, requestDirListing(j), "a refused value must leave no file of any name under requests/run/");
        assertRefusedAsInvalid("a reason with U+0000 must be refused", () -> {
            try (ACLContext ignored = as("u1")) {
                RunRequestService.get().create(job, List.of(new StringParameterValue("X", "fine")), "month-end\u0000batch", "a1");
            }
        });
        assertEquals(listing, requestDirListing(j), "a refused reason must leave no file of any name under requests/run/");
        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "nothing may be stored");

        RunRequest ok = createTyped(job, List.of(new StringParameterValue("X", "fine"), new StringParameterValue("Y", "also fine")));
        Set<String> after = requestDirListing(j);
        after.removeAll(listing);
        assertEquals(Set.of(ok.getId() + ".xml", valuesFileName(ok.getId())), after,
                "guard: a valid request adds exactly its own two files, the request and its values (D-74)");
    }

    /**
     * T-05-136 (SPEC 5 D-72b line: "Every stored value ... contains only characters XML can store;
     * a failed save leaves nothing behind"; SPEC 6 usability line; LIMITATIONS 31: a character XML
     * 1.0 cannot store is refused "in an approve or reject comment", and "a refused approval or
     * rejection leaves the request pending"; spec-review-r6 m-11 (c); note 273): a1, the designated
     * approver, posts the request screen's approve endpoint with a comment holding U+0001 (storable
     * in XML 1.1, not in XML 1.0) and with one holding U+FFFE, and the reject endpoint with U+0000
     * and with U+001F; then calls the service's approve and reject with such comments. Each attempt
     * is refused without a server error: the answer is an HTML page that is not core's bare error
     * page, shows no crash text, carries the decision form again and says something the plain
     * request screen does not; the service call throws. The request stays PENDING with no decider
     * and no comment, its file is byte-for-byte unchanged, nothing new appears under
     * {@code requests/run/}, nothing runs. Guards: a second request of the same job is rejected with
     * the comment {@code wrong date} (REJECTED, comment stored), and the first is approved with
     * {@code looks fine} and runs once with its own value.
     */
    @Test
    public void t_05_136_xmlIllegalCharacterInADecisionCommentIsRefusedAndLeavesTheRequestPending() throws Exception {
        FreeStyleProject job = storedJob("xml-comment");
        String id = createTyped(job, List.of(new StringParameterValue("TARGET", "staging"))).getId();
        String other = createTyped(job, List.of(new StringParameterValue("TARGET", "second"))).getId();
        String file = requestXml(j, id);
        Set<String> listing = requestDirListing(j);

        String[][] attempts = {
            {"approve", "looks\u0001fine", "an approve comment with U+0001"},
            {"approve", "looks\uFFFEfine", "an approve comment with U+FFFE"},
            {"reject", "wrong\u0000date", "a reject comment with U+0000"},
            {"reject", "wrong\u001Fdate", "a reject comment with U+001F"},
        };
        for (String[] attempt : attempts) {
            assertDecisionRefused(attempt[2], id, attempt[0], attempt[1]);
            assertUndecided(attempt[2], id, file, listing);
        }
        assertRefusedDecision("the service approve with a U+0001 comment", () -> {
            try (ACLContext ignored = as("a1")) {
                RunRequestService.get().approve(id, "looks\u0001fine");
            }
        });
        assertUndecided("the service approve with a U+0001 comment", id, file, listing);
        assertRefusedDecision("the service reject with a U+0000 comment", () -> {
            try (ACLContext ignored = as("a1")) {
                RunRequestService.get().reject(id, "wrong\u0000date");
            }
        });
        assertUndecided("the service reject with a U+0000 comment", id, file, listing);
        assertNothingRan(job);

        WebResponse rejected = ApproverFormFixtures.decideRun(j, "a1", other, "reject", "wrong date");
        assertTrue(rejected.getStatusCode() < 400, "guard: a plain reject comment is accepted, got HTTP " + rejected.getStatusCode() + ": "
                + UsabilityFixtures.excerpt(rejected.getContentAsString()));
        RunRequest otherNow = RunRequestService.get().load(other);
        assertEquals(RequestStatus.REJECTED, otherNow.getStatus(), "guard: the second request is rejected");
        assertEquals("wrong date", otherNow.getDecisionComment(), "guard: the plain reject comment is stored");

        WebResponse approved = ApproverFormFixtures.decideRun(j, "a1", id, "approve", "looks fine");
        assertTrue(approved.getStatusCode() < 400, "guard: a plain approve comment is accepted, got HTTP " + approved.getStatusCode() + ": "
                + UsabilityFixtures.excerpt(approved.getContentAsString()));
        j.waitUntilNoActivity();
        assertNotNull(job.getBuildByNumber(1), "guard: the approved request runs");
        assertEquals("staging", TypedParameterFixtures.CaptureEnv.seen(job.getFullName(), 1, "TARGET"), "guard: the run receives its own value");
        assertEquals(1, job.getBuilds().size(), "guard: exactly one run");
        assertEquals("looks fine", RunRequestService.get().load(id).getDecisionComment(), "guard: the plain approve comment is stored");
    }

    /**
     * T-05-137 (SPEC 5 D-72b line: "each parameter name appears at most once ... Every stored value
     * ... contains only characters XML can store; a failed save leaves nothing behind"; LIMITATIONS
     * 31: such a character is refused "in a parameter name"; spec-review-r6 m-11 (c); note 273): the
     * Freestyle job {@code xml-name} defines the string parameter {@code BAD<U+0001>NAME} (a name
     * the job's own XML 1.1 configuration can hold, XML 1.0 cannot). u1 posts the Request Run form's
     * {@code json} with that parameter: refused with 4xx (not 5xx), an HTML page that is not core's
     * bare error page, no crash text, the form again and an explanation the plain form does not
     * have; then the service's {@code create} with that name: IllegalArgumentException or Failure.
     * No request, the {@code requests/run/} listing unchanged (no temporary file), nothing under the
     * temporary directories, nothing queued. Guard: the job {@code xml-name-ok} with
     * {@code BAD_NAME} accepts the same post (the value is stored) and the same service call.
     */
    @Test
    public void t_05_137_xmlIllegalCharacterInAParameterNameIsRefusedBeforeAnythingIsStored() throws Exception {
        String bad = "BAD\u0001NAME";
        FreeStyleProject job = job("xml-name", new StringParameterDefinition(bad, "name-default"));
        FreeStyleProject twin = job("xml-name-ok", new StringParameterDefinition("BAD_NAME", "name-default"));
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);
        Set<Path> temp = tempFiles(j);

        assertFormRefusal("a parameter name with U+0001", postJson("u1", job, form(REASON, value(bad, "fine"))), job);
        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "the refused form post must store no request");
        assertEquals(listing, requestDirListing(j), "the refused form post must leave no file of any name under requests/run/");
        assertRefusedAsInvalid("the service create with a parameter name holding U+0001 must be refused",
                () -> createTyped(job, List.of(new StringParameterValue(bad, "fine"))));
        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "the refused service call must store no request");
        assertEquals(listing, requestDirListing(j), "the refused service call must leave no file of any name under requests/run/");
        assertEquals(temp, tempFiles(j), "nothing may be kept under the temporary directories");
        assertNothingRan(job);

        String id = acceptedOne("the name without U+0001", postJson("u1", twin, form(REASON, value("BAD_NAME", "fine"))), ids);
        assertEquals("fine", RunRequestService.get().load(id).getParameters().get("BAD_NAME"), "guard: the plain name is accepted with its value");
        // amended for D-88 (note 359): another value, so the service request is not identical to the pending form one
        RunRequest viaService = createTyped(twin, List.of(new StringParameterValue("BAD_NAME", "fine, via the service")));
        assertEquals(RequestStatus.PENDING, viaService.getStatus(), "guard: the service accepts the plain name");
    }

    // ================================================================ approval fails closed

    /**
     * T-05-78 (S-35-01 defence in depth): a PENDING request's values file is edited to hold a second
     * typed TARGET ({@code production}) while the display still shows {@code staging}: approval is
     * refused (service and HTTP, plain message), the request is neither APPROVED nor EXECUTED,
     * nothing runs and no build ever sees {@code production}. Guard: the unedited twin is approved
     * and runs with its own value.
     */
    @Test
    public void t_05_78_storedRepeatedNameCannotBeApproved() throws Exception {
        FreeStyleProject job = storedJob("stored-dup");
        String id = createTyped(job, List.of(new StringParameterValue("TARGET", "staging"))).getId();
        String twin = createTyped(job, List.of(new StringParameterValue("TARGET", "guarded"))).getId();
        editStored(id, xml -> duplicateTypedValue(xml, "TARGET", "production"));
        assertTrue(valuesXml(j, id).contains("production"), "premise: the second typed value is in the values file");
        assertEquals(Map.of("TARGET", "staging"), RunRequestService.get().load(id).getParameters(), "premise: the approver sees staging only");

        assertApprovalRefused("a request whose typed values repeat TARGET", job, id);
        assertTwinRuns(job, twin, "guarded");
        assertFalse(TypedParameterFixtures.CaptureEnv.SEEN.containsValue("production"), "no build may ever have run with the unseen value");
    }

    /**
     * T-05-79 (S-35-01): the typed value TARGET in the values file is renamed to OTHER while the display still
     * names TARGET (with MODE in both): approval is refused, nothing runs. Guard: the unedited twin
     * runs with its own value.
     */
    @Test
    public void t_05_79_storedNamesDifferingFromTheDisplayCannotBeApproved() throws Exception {
        FreeStyleProject job = storedJob("stored-differ", new StringParameterDefinition("MODE", "partial"));
        String id = createTyped(job, List.of(new StringParameterValue("TARGET", "staging"), new StringParameterValue("MODE", "full"))).getId();
        String twin = createTyped(job, List.of(new StringParameterValue("TARGET", "guarded"), new StringParameterValue("MODE", "full"))).getId();
        editStored(id, xml -> renameTypedValue(xml, "TARGET", "OTHER"));
        assertEquals(Map.of("TARGET", "staging", "MODE", "full"), RunRequestService.get().load(id).getParameters(),
                "premise: the display still names TARGET");

        assertApprovalRefused("a request whose typed names differ from the displayed ones", job, id);
        assertTwinRuns(job, twin, "guarded");
    }

    /**
     * T-05-80 (S-35-04 (a)): the typed value TARGET in the values file names a class that cannot be loaded (its
     * plugin removed or the class renamed): approval is refused (fail closed) and nothing runs, so
     * the build never falls back to the default. Guard: the unedited twin runs with its own value.
     */
    @Test
    public void t_05_80_storedValueOfAnUnloadableClassCannotBeApproved() throws Exception {
        FreeStyleProject job = storedJob("stored-class");
        String id = createTyped(job, List.of(new StringParameterValue("TARGET", "staging"))).getId();
        String twin = createTyped(job, List.of(new StringParameterValue("TARGET", "guarded"))).getId();
        editStored(id, xml -> retypeTypedValue(xml, "TARGET", "io.jenkins.plugins.batchcontrol.NoSuchParameterValueD72b"));
        assertTrue(valuesXml(j, id).contains("NoSuchParameterValueD72b"), "premise: the values file names the unloadable class");

        assertApprovalRefused("a request with a typed value that cannot be loaded", job, id);
        assertTwinRuns(job, twin, "guarded");
        assertFalse(TypedParameterFixtures.CaptureEnv.SEEN.containsValue("default-target"), "no build may have run with the job's default");
    }

    /**
     * T-05-81 (D-72b (3), D-74 (1), S7 m-4, S-35-04 (b)): a request with parameters whose values file
     * is missing (the shape a request stored before D-72 has: the display map, no typed values; here
     * {@code <id>.values.xml} is deleted): approval is refused and nothing runs (no build with the
     * job's default). Guard: the unedited twin runs with its own value.
     */
    @Test
    public void t_05_81_requestStoredBeforeD72CannotBeApproved() throws Exception {
        FreeStyleProject job = storedJob("stored-pre72");
        String id = createTyped(job, List.of(new StringParameterValue("TARGET", "staging"))).getId();
        String twin = createTyped(job, List.of(new StringParameterValue("TARGET", "guarded"))).getId();
        deleteStoredValues(id);
        assertFalse(Files.exists(valuesPath(j, id)), "premise: the request has no values file");
        assertEquals(Map.of("TARGET", "staging"), RunRequestService.get().load(id).getParameters(), "premise: the display map stays");

        assertApprovalRefused("a request stored before D-72", job, id);
        assertTwinRuns(job, twin, "guarded");
        assertFalse(TypedParameterFixtures.CaptureEnv.SEEN.containsValue("default-target"), "no build may have run with the job's default");
    }

    // ---------------------------------------------------------------- helpers

    private FreeStyleProject job(String name, ParameterDefinition... definitions) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        job.addProperty(new ParametersDefinitionProperty(definitions));
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    /** An approval-required Freestyle job with TARGET (default {@code default-target}) whose build records TARGET. */
    private FreeStyleProject storedJob(String name, ParameterDefinition... more) throws Exception {
        List<ParameterDefinition> definitions = new ArrayList<>();
        definitions.add(new StringParameterDefinition("TARGET", "default-target"));
        definitions.addAll(Arrays.asList(more));
        FreeStyleProject job = job(name, definitions.toArray(new ParameterDefinition[0]));
        job.getBuildersList().add(new TypedParameterFixtures.CaptureEnv(false, "TARGET"));
        return job;
    }

    /** Edits request {@code id}'s values file and proves the plugin reads the request from disk (the reason marker). */
    private void editStored(String id, UnaryOperator<String> edit) throws Exception {
        editValuesFile(j, id, edit);
        proveReread(id);
    }

    /** Deletes request {@code id}'s values file and proves the plugin reads the request from disk (the reason marker). */
    private void deleteStoredValues(String id) throws Exception {
        deleteValuesFile(j, id);
        proveReread(id);
    }

    /** Marks the reason in {@code <id>.xml} and reads it back, so the request is not served from a stale copy. */
    private void proveReread(String id) throws Exception {
        editRequestFile(j, id, xml -> markReason(xml, EDIT_MARK));
        RunRequest reread = RunRequestService.get().load(id);
        assertNotNull(reread, "premise: the edited request still loads");
        assertTrue(reread.getReason().endsWith(EDIT_MARK), "premise: the plugin reads the edited file (reason was '"
                + reread.getReason() + "')");
        assertEquals(RequestStatus.PENDING, reread.getStatus(), "premise: the edited request is PENDING");
    }

    /** Approval by the designated approver is refused through the service and over HTTP; nothing runs. */
    private void assertApprovalRefused(String what, FreeStyleProject job, String id) throws Exception {
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "looks fine");
        } catch (RuntimeException refused) {
            // a refusal; the state below is what counts
        }
        assertNotApproved(what + " (service approve)", id);
        WebResponse http = ApproverFormFixtures.decideRun(j, "a1", id, "approve", "looks fine");
        assertTrue(http.getStatusCode() < 500, what + ": the HTTP approval must be refused without a server error, got HTTP "
                + http.getStatusCode() + ": " + UsabilityFixtures.excerpt(http.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal(what + " (HTTP approve)", http.getContentAsString(), null);
        assertNotApproved(what + " (HTTP approve)", id);
        assertNothingRan(job);
    }

    private static void assertNotApproved(String what, String id) {
        RequestStatus status = RunRequestService.get().load(id).getStatus();
        assertNotEquals(RequestStatus.APPROVED, status, what + ": the approval must be refused");
        assertNotEquals(RequestStatus.EXECUTED, status, what + ": the request must never run");
    }

    /** The unedited twin is approved and runs as build #1 with {@code expected} as TARGET. */
    private void assertTwinRuns(FreeStyleProject job, String twin, String expected) throws Exception {
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(twin, "the unedited twin");
        }
        j.waitUntilNoActivity();
        assertNotNull(job.getBuildByNumber(1), "guard: the unedited twin must run");
        assertEquals(expected, TypedParameterFixtures.CaptureEnv.seen(job.getFullName(), 1, "TARGET"),
                "guard: the twin's run receives its own value");
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(twin).getStatus());
        assertEquals(1, job.getBuilds().size(), "exactly one run: the twin's");
    }

    private void assertNothingRan(Job<?, ?> job) throws Exception {
        j.waitUntilNoActivity();
        assertTrue(j.jenkins.getQueue().isEmpty(), "the queue must be empty");
        assertEquals(1, job.getNextBuildNumber(), "no build number may have been consumed");
    }

    private RunRequest createTyped(Job<?, ?> job, List<ParameterValue> values) {
        try (ACLContext ignored = as("u1")) {
            return RunRequestService.get().create(job, values, REASON, "a1");
        }
    }

    private static JSONObject form(String reason, JSONObject... parameters) {
        JSONObject form = new JSONObject();
        form.put("reason", reason);
        form.put("approvers", "a1");
        JSONArray list = new JSONArray();
        for (JSONObject p : parameters) {
            list.add(p);
        }
        form.put("parameter", list);
        return form;
    }

    private static JSONObject value(String name, String value) {
        JSONObject p = new JSONObject();
        p.put("name", name);
        p.put("value", value);
        return p;
    }

    /** A file parameter entry of core's form: the value names the multipart part that holds the file. */
    private static JSONObject fileRef(String name, String part) {
        JSONObject p = new JSONObject();
        p.put("name", name);
        p.put("file", part);
        return p;
    }

    private static KeyDataPair filePart(String part, String fileName, byte[] content) throws Exception {
        return new KeyDataPair(part, uploadFile(fileName, content), fileName, "application/octet-stream", StandardCharsets.UTF_8);
    }

    private static List<NameValuePair> rawFields() {
        List<NameValuePair> fields = new ArrayList<>();
        fields.add(new NameValuePair("reason", REASON));
        fields.add(new NameValuePair("approvers", "a1"));
        return fields;
    }

    private Page postJson(String userId, Job<?, ?> job, JSONObject form) throws Exception {
        return postFields(userId, job, List.of(new NameValuePair("json", form.toString())));
    }

    private Page postFields(String userId, Job<?, ?> job, List<NameValuePair> fields) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "batch-control/submit"), HttpMethod.POST);
        request.setRequestParameters(new ArrayList<>(fields));
        return wc.getPage(request);
    }

    private Page postMultipart(String userId, Job<?, ?> job, List<NameValuePair> fields) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "batch-control/submit"), HttpMethod.POST);
        request.setEncodingType(FormEncodingType.MULTIPART);
        request.setRequestParameters(new ArrayList<>(fields));
        return wc.getPage(request);
    }

    /**
     * A field error: 400, an HTML page that is not core's bare error page, no crash text, the form
     * again, and an explanation the plain form does not have (re-entry notices excluded) that names
     * the field, or that sits inside the field's own parameter block, or an {@code X-Error} header
     * naming the field. Wording is not pinned.
     */
    private void assertFieldRefusal(String what, Page answer, Job<?, ?> job, String field) throws Exception {
        WebResponse response = answer.getWebResponse();
        assertEquals(400, response.getStatusCode(), what + " must be refused as a field error (HTTP 400): "
                + UsabilityFixtures.excerpt(response.getContentAsString()));
        assertTrue(answer instanceof HtmlPage, what + ": the refusal must be an HTML page, got " + response.getContentType());
        HtmlPage page = PageScriptFixtures.settled((HtmlPage) answer);
        UsabilityFixtures.assertNotBareErrorPage(what, page);
        String text = page.asNormalizedText();
        UsabilityFixtures.assertPlainRefusal(what, text, null);
        assertFalse(UsabilityFixtures.formsEndingWith(page, job.getUrl() + "batch-control/submit").isEmpty(),
                what + ": the refusal must show the form again: " + UsabilityFixtures.formActions(page));

        String header = response.getResponseHeaderValue("X-Error");
        if (header != null && lower(header).contains(lower(field))) {
            return;
        }
        Set<String> plain = lines(UsabilityFixtures.htmlPage(j, "u1", job.getUrl() + "batch-control/").asNormalizedText());
        Set<String> fresh = lines(text);
        fresh.removeAll(plain);
        for (Object notice : page.querySelectorAll("[data-batch-control-notice]")) {
            fresh.removeAll(lines(((org.htmlunit.html.DomNode) notice).asNormalizedText()));
        }
        boolean named = fresh.stream().anyMatch(l -> lower(l).contains(lower(field)));
        boolean nextTo = false;
        List<org.htmlunit.html.HtmlElement> blocks = page.getByXPath(
                "//*[@name='parameter'][.//input[@name='name' and @value='" + field + "']]");
        for (org.htmlunit.html.HtmlElement block : blocks) {
            Set<String> inBlock = lines(block.asNormalizedText());
            inBlock.retainAll(fresh);
            nextTo |= !inBlock.isEmpty();
        }
        assertTrue(named || nextTo, what + ": the refusal must explain itself next to or naming the field " + field
                + "; lines the plain form does not have: " + fresh);
    }

    /**
     * A form refusal whose field is not pinned (T-05-137): 4xx, an HTML page that is not core's bare
     * error page, no crash text, the form again, and a line the plain form does not have (re-entry
     * notices excluded).
     */
    private void assertFormRefusal(String what, Page answer, Job<?, ?> job) throws Exception {
        WebResponse response = answer.getWebResponse();
        int code = response.getStatusCode();
        assertTrue(code >= 400 && code < 500, what + " must be refused with 4xx (not a server error), got HTTP " + code + ": "
                + UsabilityFixtures.excerpt(response.getContentAsString()));
        assertTrue(answer instanceof HtmlPage, what + ": the refusal must be an HTML page, got " + response.getContentType());
        HtmlPage page = (HtmlPage) answer;
        UsabilityFixtures.assertNotBareErrorPage(what, page);
        String text = page.asNormalizedText();
        UsabilityFixtures.assertPlainRefusal(what, text, null);
        assertFalse(UsabilityFixtures.formsEndingWith(page, job.getUrl() + "batch-control/submit").isEmpty(),
                what + ": the refusal must show the form again: " + UsabilityFixtures.formActions(page));
        Set<String> fresh = lines(text);
        fresh.removeAll(lines(UsabilityFixtures.htmlPage(j, "u1", job.getUrl() + "batch-control/").asNormalizedText()));
        for (Object notice : page.querySelectorAll("[data-batch-control-notice]")) {
            fresh.removeAll(lines(((org.htmlunit.html.DomNode) notice).asNormalizedText()));
        }
        assertFalse(fresh.isEmpty(), what + ": the refusal must explain itself (no line differs from the plain form)");
    }

    /**
     * a1 posts {@code batch-control/requests/<id>/<verb>} with {@code comment} as UTF-8 (redirects followed):
     * no server error, an HTML page that is not core's bare error page, no crash text, the decision
     * form again, and a line the plain request screen does not have.
     */
    private void assertDecisionRefused(String what, String id, String verb, String comment) throws Exception {
        String detail = "batch-control/requests/" + id + "/";
        Set<String> plain = lines(UsabilityFixtures.htmlPage(j, "a1", detail).asNormalizedText());
        JenkinsRule.WebClient wc = UsabilityFixtures.clientNoJs(j, "a1");
        WebRequest request = new WebRequest(wc.createCrumbedUrl(detail + verb), HttpMethod.POST);
        // the body is percent-encoded here: HtmlUnit's own parameter encoding sends U+FFFE as '?',
        // so the character would never reach Jenkins (a browser sends its UTF-8 bytes)
        request.setAdditionalHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        request.setRequestBody("comment=" + java.net.URLEncoder.encode(comment, StandardCharsets.UTF_8));
        Page answer = wc.getPage(request);
        int code = answer.getWebResponse().getStatusCode();
        assertTrue(code < 500, what + ": the refusal must not be a server error, got HTTP " + code + ": "
                + UsabilityFixtures.excerpt(answer.getWebResponse().getContentAsString()));
        assertTrue(answer instanceof HtmlPage, what + ": the refusal must be an HTML page, got " + answer.getWebResponse().getContentType());
        HtmlPage page = (HtmlPage) answer;
        UsabilityFixtures.assertNotBareErrorPage(what, page);
        String text = page.asNormalizedText();
        UsabilityFixtures.assertPlainRefusal(what, text, null);
        assertFalse(UsabilityFixtures.formsEndingWith(page, detail + verb).isEmpty(),
                what + ": the refusal must show the " + verb + " form again; HTTP " + code + " at " + page.getUrl() + ", forms "
                        + UsabilityFixtures.formActions(page) + ", status now " + RunRequestService.get().load(id).getStatus() + ": "
                        + UsabilityFixtures.excerpt(text));
        Set<String> fresh = lines(text);
        fresh.removeAll(plain);
        for (Object notice : page.querySelectorAll("[data-batch-control-notice]")) {
            fresh.removeAll(lines(((org.htmlunit.html.DomNode) notice).asNormalizedText()));
        }
        assertFalse(fresh.isEmpty(), what + ": the refusal must explain itself (no line differs from the plain request screen)");
    }

    /** The request is still PENDING, undecided and without a comment; its file and the request directory are unchanged. */
    private void assertUndecided(String what, String id, String file, Set<String> listing) throws Exception {
        RunRequest now = RunRequestService.get().load(id);
        assertEquals(RequestStatus.PENDING, now.getStatus(), what + ": the request must stay PENDING");
        assertEquals(null, now.getDecidedBy(), what + ": no decider may be stored");
        String comment = now.getDecisionComment();
        assertTrue(comment == null || comment.isEmpty(), what + ": no comment may be stored, was '" + comment + "'");
        assertEquals(file, requestXml(j, id), what + ": the request file must be unchanged (nothing stored)");
        assertEquals(listing, requestDirListing(j), what + ": nothing may be added under requests/run/ (no temporary file)");
    }

    /** A refused decision throws; the type is not pinned (SPEC names none), the state after it is what counts. */
    private static void assertRefusedDecision(String what, Executable action) {
        try {
            action.execute();
        } catch (RuntimeException refused) {
            return;
        } catch (Throwable other) {
            throw new AssertionError(what + " - expected a refusal (a runtime exception), got " + other, other);
        }
        throw new AssertionError(what + " must be refused");
    }

    private static Set<String> lines(String text) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                out.add(line.trim());
            }
        }
        return out;
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** The submission was accepted and created exactly one request; returns its id. */
    private static String acceptedOne(String what, Page answer, Set<String> before) {
        int code = answer.getWebResponse().getStatusCode();
        assertTrue(code < 400, "guard (" + what + "): the submission must be accepted, got HTTP " + code + ": "
                + UsabilityFixtures.excerpt(answer.getWebResponse().getContentAsString()));
        Set<String> created = ApproverFormFixtures.runRequestIds();
        created.removeAll(before);
        assertEquals(1, created.size(), "guard (" + what + "): exactly one request must be created, got " + created);
        return created.iterator().next();
    }

    private static void assertRefusedAsInvalid(String message, Executable action) {
        boolean refused = false;
        try {
            action.execute();
        } catch (IllegalArgumentException | hudson.model.Failure expected) {
            refused = true;
        } catch (Throwable other) {
            throw new AssertionError(message + " - expected IllegalArgumentException or Failure, got " + other, other);
        }
        assertTrue(refused, message);
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
