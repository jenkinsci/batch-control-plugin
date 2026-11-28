package io.jenkins.plugins.batchcontrol;

import hudson.FilePath;
import hudson.model.BooleanParameterDefinition;
import hudson.model.BooleanParameterValue;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.FileParameterDefinition;
import hudson.model.FileParameterValue;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.PasswordParameterValue;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.TextParameterDefinition;
import hudson.model.TextParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.file_parameters.Base64FileParameterDefinition;
import io.jenkins.plugins.file_parameters.StashedFileParameterDefinition;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.MASK;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.assertAbsent;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.bytes;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileDisplay;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.readable;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.serverPaths;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 5 (D-72: every parameter type, core {@code file}, {@code stashedFile} and
 * {@code base64File} included, reaches the approved build exactly) through the request dialog of
 * SPEC item 8 (D-66: "requesting a run (the Request Run action) open[s] a dialog on the current
 * page; submitting it creates the request and leads to its detail page"). Coverage inventory
 * G-H1 (JenkinsRule part) and G-M12; matrix rows T-05-102 .. T-05-104, T-05-108 and T-05-111 (note 269).
 *
 * <p>The dialog is driven by {@link DialogFixtures}: the fragment
 * {@code job/<name>/batch-control/dialog} is inserted into the job page as core's dialog does and
 * its own form is filled in and submitted. The browser dialog itself (both job UIs) stays with
 * e2e (G-H7).
 *
 * <p>Users: {@code u1} requester (Item/Read, BatchControl/Request; no Item/Build, D-38a),
 * {@code a1} approver, {@code viewer} (ViewHistory), {@code admin}.
 *
 * <p>Written from docs/SPEC.md items 5 and 8, docs/DECISIONS.md D-66, D-70 and D-72 and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class DialogTypedParameterTest {

    private static final String CORE_MARKER = "dialog-core-marker-Gh41";
    private static final String STASH_MARKER = "dialog-stash-marker-Gh42";
    private static final String B64_MARKER = "dialog-b64-marker-Gh43";
    private static final String PLAIN = "dialog-plain-value-Gh44";
    private static final String SECRET = "dialog-typed-s3cr3t-Gm12";
    private static final String SECRET_DEFAULT = "dialog-d3fault-s3cr3t-Gm12";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        TypedParameterFixtures.CaptureEnv.SEEN.clear();
    }

    /**
     * T-05-111 (G-H1): the dialog fragment of a job with a core file UPLOAD, a stashed file DATA, a
     * Base64 file B64 and a string DATE carries one form that posts {@code multipart/form-data} to
     * the job's submit endpoint, with exactly one file control in the block of each file parameter
     * (three in all) and none in DATE's block. Guard: the fragment of a job with only a string
     * parameter has no file control, so the count measures the parameters.
     */
    @Test
    public void t_05_111_dialogFragmentFormIsMultipartWithOneFileControlPerFileParameter() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("dialog-shape");
        addParameters(job, new FileParameterDefinition("UPLOAD", "core file"), new StashedFileParameterDefinition("DATA"),
                new Base64FileParameterDefinition("B64"), new StringParameterDefinition("DATE", "2000-01-01"));
        setBatchControl(job, new BatchControlJobProperty(true));

        HtmlForm form = DialogFixtures.fragmentForm(DialogFixtures.fragmentPage(j, "u1", job), job);
        assertEquals("multipart/form-data", form.getEnctypeAttribute().toLowerCase(Locale.ROOT),
                "the dialog form must post multipart/form-data, or no browser sends the chosen files");
        assertEquals("post", form.getMethodAttribute().toLowerCase(Locale.ROOT), "the dialog form must POST");
        for (String fileParameter : new String[] {"UPLOAD", "DATA", "B64"}) {
            assertEquals(1, fileControls(TypedParameterFixtures.parameterBlock(form, fileParameter)).size(),
                    "the block of the file parameter " + fileParameter + " must offer exactly one file control: "
                            + UsabilityFixtures.excerpt(TypedParameterFixtures.parameterBlock(form, fileParameter).asXml()));
        }
        assertEquals(0, fileControls(TypedParameterFixtures.parameterBlock(form, "DATE")).size(),
                "the string parameter's block must not offer a file control");
        assertEquals(3, fileControls(form).size(), "the dialog form must offer one file control per file parameter, three in all");

        FreeStyleProject plain = j.createFreeStyleProject("dialog-plain");
        addParameters(plain, new StringParameterDefinition("DATE", "2000-01-01"));
        setBatchControl(plain, new BatchControlJobProperty(true));
        assertEquals(0, fileControls(DialogFixtures.fragmentForm(DialogFixtures.fragmentPage(j, "u1", plain), plain)).size(),
                "guard: the fragment of a job without file parameters offers no file control");
    }

    /**
     * T-05-102 (G-H1): u1 chooses a core file for UPLOAD and types DATE in the dialog form and
     * submits it: one PENDING request showing {@code [file] data.csv} and DATE, the answer is its
     * detail page, nothing runs before the approval; once a1 approves, build #1 receives exactly
     * the uploaded bytes (workspace and core's copy), a core file value with the original name, the
     * typed DATE, and the request is EXECUTED.
     */
    @Test
    public void t_05_102_coreFileThroughTheDialogReachesTheApprovedBuildByteForByte() throws Exception {
        byte[] content = payload(CORE_MARKER, 6000);
        FreeStyleProject job = j.createFreeStyleProject("dialog-core");
        addParameters(job, new FileParameterDefinition("UPLOAD", "the input file"),
                new StringParameterDefinition("DATE", "2000-01-01", "the batch date"));
        setBatchControl(job, new BatchControlJobProperty(true));

        String id = DialogFixtures.submitThroughDialog(j, "u1", job, form -> {
            TypedParameterFixtures.setValue(form, "DATE", PLAIN);
            TypedParameterFixtures.setFile(form, "UPLOAD", uploadFile("data.csv", content));
        });
        RunRequest pending = RunRequestService.get().load(id);
        assertEquals(RequestStatus.PENDING, pending.getStatus());
        assertEquals("u1", pending.getRequester());
        assertEquals(fileDisplay("data.csv"), pending.getParameters().get("UPLOAD"), "the stored display form of the file is [file] <name>");
        assertEquals(PLAIN, pending.getParameters().get("DATE"));
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "nothing may run before the approval");

        approve(id);
        j.waitUntilNoActivity();
        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build, "the approved request must have run as build #1");
        j.assertBuildStatusSuccess(build);
        assertArrayEquals(content, bytes(build.getWorkspace().child("UPLOAD")),
                "the workspace file at the parameter's location must hold exactly the bytes chosen in the dialog");
        assertArrayEquals(content, Files.readAllBytes(build.getRootDir().toPath().resolve("fileParameters").resolve("UPLOAD")),
                "core's copy of the build's file parameter must hold exactly the bytes chosen in the dialog");
        ParameterValue upload = build.getAction(ParametersAction.class).getParameter("UPLOAD");
        assertTrue(upload instanceof FileParameterValue, "the build must receive a core file value, got " + upload);
        assertEquals("data.csv", ((FileParameterValue) upload).getOriginalFileName());
        assertEquals(PLAIN, ((StringParameterValue) build.getAction(ParametersAction.class).getParameter("DATE")).getValue(),
                "guard: the typed string travels next to the file");
        assertEquals(id, build.getCause(ApprovedCause.class).getRequestId());
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(id).getStatus());
        assertEquals(1, job.getBuilds().size(), "the approved request runs exactly once");
    }

    /**
     * T-05-103 (G-H1, the reviewer's stashedFile case through the primary entry point): u1 chooses
     * a file for the stashed file DATA of a Pipeline in the dialog form; the request shows
     * {@code [file] report.bin}; after a1's approval {@code unstash} and {@code withFileParameter}
     * read exactly the chosen bytes and {@code DATA_FILENAME} is the original name.
     */
    @Test
    public void t_05_103_stashedFileThroughTheDialogReachesTheApprovedPipelineByteForByte() throws Exception {
        byte[] content = payload(STASH_MARKER, 5000);
        WorkflowJob job = pipeline("dialog-stash",
                "node {\n"
                + "  unstash 'DATA'\n"
                + "  withFileParameter('DATA') {\n"
                + "    writeFile file: 'via-wrapper.bin', text: readFile(file: env.DATA, encoding: 'Base64'), encoding: 'Base64'\n"
                + "  }\n"
                + "  writeFile file: 'filename.txt', text: env.DATA_FILENAME\n"
                + "}\n",
                new StashedFileParameterDefinition("DATA"), new StringParameterDefinition("DATE", "2000-01-01"));

        String id = DialogFixtures.submitThroughDialog(j, "u1", job, form -> {
            TypedParameterFixtures.setValue(form, "DATE", PLAIN);
            TypedParameterFixtures.setFile(form, "DATA", uploadFile("report.bin", content));
        });
        assertEquals(fileDisplay("report.bin"), RunRequestService.get().load(id).getParameters().get("DATA"));
        assertEquals(PLAIN, RunRequestService.get().load(id).getParameters().get("DATE"));

        approve(id);
        j.waitUntilNoActivity();
        WorkflowRun run = job.getBuildByNumber(1);
        assertNotNull(run, "the approved request must have run as #1");
        j.assertBuildStatusSuccess(run);
        FilePath ws = j.jenkins.getWorkspaceFor(job);
        assertArrayEquals(content, bytes(ws.child("DATA")), "unstash 'DATA' must restore exactly the bytes chosen in the dialog");
        assertArrayEquals(content, bytes(ws.child("via-wrapper.bin")), "withFileParameter must expose exactly the bytes chosen in the dialog");
        assertEquals("report.bin", ws.child("filename.txt").readToString(), "DATA_FILENAME must be the original file name");
        assertEquals(id, run.getCause(ApprovedCause.class).getRequestId());
    }

    /**
     * T-05-104 (G-H1): u1 chooses a file for the Base64 file B64 of a Pipeline in the dialog form;
     * after a1's approval {@code withFileParameter} reads exactly the chosen bytes and
     * {@code B64_FILENAME} is the original name; the request detail page (u1, a1, admin), the
     * dashboard, {@code requests.csv} and {@code runs.csv} show {@code [file] payload.bin} and never
     * the Base64 text, the content or a server path. (Where the Base64 is kept in the store is the
     * D-74 values file and not asserted here.)
     */
    @Test
    public void t_05_104_base64FileThroughTheDialogReachesTheRunAndIsNeverShownAsText() throws Exception {
        byte[] content = payload(B64_MARKER, 3000);
        String base64 = Base64.getEncoder().encodeToString(content);
        WorkflowJob job = pipeline("dialog-b64",
                "node {\n"
                + "  withFileParameter('B64') {\n"
                + "    writeFile file: 'b64-copy.bin', text: readFile(file: env.B64, encoding: 'Base64'), encoding: 'Base64'\n"
                + "  }\n"
                + "  writeFile file: 'filename.txt', text: env.B64_FILENAME\n"
                + "}\n",
                new Base64FileParameterDefinition("B64"), new StringParameterDefinition("DATE", "2000-01-01"));

        String id = DialogFixtures.submitThroughDialog(j, "u1", job, form -> {
            TypedParameterFixtures.setValue(form, "DATE", PLAIN);
            TypedParameterFixtures.setFile(form, "B64", uploadFile("payload.bin", content));
        });
        assertEquals(fileDisplay("payload.bin"), RunRequestService.get().load(id).getParameters().get("B64"));

        approve(id);
        j.waitUntilNoActivity();
        WorkflowRun run = job.getBuildByNumber(1);
        assertNotNull(run, "the approved request must have run as #1");
        j.assertBuildStatusSuccess(run);
        FilePath ws = j.jenkins.getWorkspaceFor(job);
        assertArrayEquals(content, bytes(ws.child("b64-copy.bin")), "the run must receive exactly the bytes chosen in the dialog");
        assertEquals("payload.bin", ws.child("filename.txt").readToString(), "B64_FILENAME must be the original file name");

        List<String> forbidden = new ArrayList<>(serverPaths(j));
        forbidden.add(base64.substring(0, 32));
        forbidden.add(B64_MARKER);
        String shown = fileDisplay("payload.bin");
        for (String user : new String[] {"u1", "a1", "admin"}) {
            String detail = readable(j, user, "batch-control/requests/" + id + "/");
            assertTrue(detail.contains(shown), "the request detail seen by " + user + " must show " + shown);
            assertTrue(detail.contains(PLAIN), "guard: the request detail seen by " + user + " shows the plain value");
            assertAbsent("the request detail seen by " + user, detail, forbidden);
        }
        for (String surface : new String[] {"batch-control/dashboard/", "batch-control/history/requests.csv",
                "batch-control/history/runs.csv"}) {
            String body = readable(j, "viewer", surface);
            assertTrue(body.contains(shown), surface + " must show the file value as " + shown);
            assertAbsent(surface, body, forbidden);
        }
    }

    /**
     * T-05-108 (G-M12): through the dialog form u1 types a password TOKEN (the definition has a
     * default secret), picks the choice MODE=third, ticks the boolean FLAG and types the text NOTE.
     * The stored display shows {@code ********} for TOKEN and the other values verbatim; the detail
     * page shows neither secret. Once a1 approves, the build receives the typed secret (a sensitive
     * Password value), MODE third, FLAG true (a boolean value) and NOTE (a text value).
     */
    @Test
    public void t_05_108_passwordChoiceBooleanAndTextThroughTheDialogReachTheBuild() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("dialog-mixed");
        addParameters(job, new PasswordParameterDefinition("TOKEN", Secret.fromString(SECRET_DEFAULT), "token"),
                new ChoiceParameterDefinition("MODE", new String[] {"first", "second", "third"}, "a choice"),
                new BooleanParameterDefinition("FLAG", false, "a boolean"),
                new TextParameterDefinition("NOTE", "note-default", "a text"));
        job.getBuildersList().add(new TypedParameterFixtures.CaptureEnv(false, "TOKEN", "MODE", "FLAG", "NOTE"));
        setBatchControl(job, new BatchControlJobProperty(true));

        String id = DialogFixtures.submitThroughDialog(j, "u1", job, form -> {
            TypedParameterFixtures.setValue(form, "TOKEN", SECRET);
            TypedParameterFixtures.setValue(form, "MODE", "third");
            TypedParameterFixtures.setValue(form, "FLAG", "true");
            TypedParameterFixtures.setValue(form, "NOTE", "typed note");
        });
        Map<String, String> shown = RunRequestService.get().load(id).getParameters();
        assertEquals(MASK, shown.get("TOKEN"), "a password is shown as " + MASK);
        assertEquals("third", shown.get("MODE"));
        assertEquals("true", shown.get("FLAG"));
        assertEquals("typed note", shown.get("NOTE"));
        String detail = readable(j, "a1", "batch-control/requests/" + id + "/");
        assertTrue(detail.contains("third") && detail.contains("typed note"), "guard: the detail page shows the plain values");
        assertAbsent("the request detail", detail, List.of(SECRET, SECRET_DEFAULT));

        approve(id);
        j.waitUntilNoActivity();
        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build, "the approved request must have run as #1");
        j.assertBuildStatusSuccess(build);
        assertEquals(SECRET, TypedParameterFixtures.CaptureEnv.seen("dialog-mixed", 1, "TOKEN"),
                "the build must receive the secret typed in the dialog (D-72)");
        assertEquals("third", TypedParameterFixtures.CaptureEnv.seen("dialog-mixed", 1, "MODE"));
        assertEquals("true", TypedParameterFixtures.CaptureEnv.seen("dialog-mixed", 1, "FLAG"));
        assertEquals("typed note", TypedParameterFixtures.CaptureEnv.seen("dialog-mixed", 1, "NOTE"));
        ParametersAction parameters = build.getAction(ParametersAction.class);
        ParameterValue token = parameters.getParameter("TOKEN");
        assertTrue(token instanceof PasswordParameterValue && token.isSensitive(), "the secret stays a sensitive Password value: " + token);
        assertSame(BooleanParameterValue.class, parameters.getParameter("FLAG").getClass());
        assertEquals(Boolean.TRUE, parameters.getParameter("FLAG").getValue());
        assertSame(TextParameterValue.class, parameters.getParameter("NOTE").getClass());
        assertEquals("third", parameters.getParameter("MODE").getValue());
        assertFalse(TypedParameterFixtures.storeFilesContaining(j, SECRET).stream().findAny().isPresent(),
                "no store file may hold the typed secret in plaintext");
    }

    // ---------------------------------------------------------------- helpers

    private static List<HtmlElement> fileControls(HtmlElement scope) {
        PageScriptFixtures.settled(scope.getPage());
        return scope.getByXPath(".//input[translate(@type,'FILE','file')='file']");
    }

    private WorkflowJob pipeline(String name, String script, ParameterDefinition... definitions) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, true));
        addParameters(job, definitions);
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void addParameters(Job<?, ?> job, ParameterDefinition... definitions) throws Exception {
        ((Job) job).addProperty(new ParametersDefinitionProperty(definitions));
    }

    static void approve(String id) {
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            RunRequestService.get().approve(id, "checked the parameters");
        }
    }
}
