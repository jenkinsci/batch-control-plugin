package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.TopLevelItem;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.post;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An administrator's self-approval of a permission window request is recorded (issue #182). Matrix
 * rows T-02-208 .. T-02-214 (note 378).
 *
 * <p>Basis: SPEC 2 line 41, "관리자 자가 결재 시 ApprovalRecord에 {@code selfApproved=true}가 기록된다", with
 * D-05 (administrator self-approval is allowed because it is recorded) and the option
 * {@code allowAdminSelfApproval} (default true, SPEC 5); SPEC 3 line 64 (D-37: any one designated
 * approver decides, and the administrator exception applies to a set); LIMITATIONS 29 (with change
 * control off, approving a window is refused and the request stays PENDING); SPEC 8 line 176 (approval
 * is refused when the item's kind has changed); ARCHITECTURE 5 (a grant request is stored as
 * {@code $JENKINS_HOME/batch-control/requests/grant/<id>.xml}). The owner decided in #182
 * (2026-11-26) that a grant request records the flag exactly as a run request does, with frozen
 * names: the stored element {@code selfApproved} ({@code true} after an administrator's
 * self-approval) and the row "Self-approved" with the value "Yes" on {@code batch-control/grants/<id>/},
 * shown only when it is true, like the run request page's row.
 *
 * <p>The rows read the result only: the stored file (the element {@code selfApproved} directly under
 * the root) and the page as the administrator sees it, never a model getter. T-02-208 first reads a
 * self-approved run request's file and page with the same two helpers (premise), so a red row here
 * cannot come from a reading that would also miss the run request's flag. The guards (T-02-210 ..
 * T-02-214) fail if the flag were recorded for every approval, for every decision by an
 * administrator, for every request filed by an administrator, or by a self-approval that was refused.
 *
 * <p>Batch Control matrix strategy; change control on; the approver list is [a1, admin]; u1 holds
 * Overall/Read, Item/Read and RequestGrant; a1 holds Overall/Read, Item/Read and Approve; admin holds
 * Overall/Administer. Every test runs in a {@link JenkinsSessionExtension} session so that T-02-208
 * can restart Jenkins and read the request back from disk.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md, docs/LIMITATIONS.md, docs/ARCHITECTURE.md
 * section 5, docs/TEST-MATRIX.md and the frozen names of issue #182 only (no src/main knowledge).
 */
public class GrantSelfApprovalTest {

    private static final String JOB = "batch-x";
    private static final String ROW_LABEL = "Self-approved";
    /** The row as the visible text renders it: the label, then its value (a table row or a label/value pair). */
    private static final Pattern ROW_YES = Pattern.compile("Self-approved\\s*:?\\s+Yes\\b");

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    private String requestId;

    /**
     * T-02-208: with allowAdminSelfApproval on (the default) and change control on, the administrator
     * files a CONFIGURE window request on {@code batch-x} designating only themself and approves it.
     * The request is APPROVED and decided by admin, admin's window is open, the stored file has
     * {@code selfApproved} true and the page shows "Self-approved" "Yes". After a restart (the request
     * read back from disk) the file and the page still say so.
     */
    @Test
    @Tag("core")
    public void t_02_208_administratorSelfApprovalOfAWindowIsRecordedAndSurvivesARestart() throws Throwable {
        session.then(r -> {
            prepare(r);
            premiseTheReadingsFindTheRunRequestsFlag(r);

            requestId = submitGrantOk(r, "admin", JOB, List.of("CONFIGURE"), 30,
                    "T-02-208 emergency fix of the nightly job", null, "admin");
            assertStatus(RequestStatus.PENDING, requestId, "premise: the administrator's own request is filed");
            assertSuccess(decideGrant(r, "admin", requestId, "approve", "self approving as admin"),
                    "the administrator's self-approval (allowAdminSelfApproval on)");
            assertDecidedWithWindow(requestId, "admin", "admin");

            assertAll("an administrator's self-approval of a window must be recorded (SPEC 2 line 41, #182)",
                    () -> assertStoredSelfApprovedTrue(r, requestId, "after the self-approval"),
                    () -> assertPageShowsSelfApproved(r, requestId, "after the self-approval"));
        });

        session.then(r -> {
            assertStatus(RequestStatus.APPROVED, requestId, "premise: the request is read back APPROVED after the restart");
            assertAll("the recorded self-approval must survive a restart (SPEC 4, #182)",
                    () -> assertStoredSelfApprovedTrue(r, requestId, "after the restart"),
                    () -> assertPageShowsSelfApproved(r, requestId, "after the restart"));
        });
    }

    /**
     * T-02-209: D-37 set. The administrator designates [a1, admin] and approves the request themself:
     * decided by admin, the stored file has {@code selfApproved} true and the page shows the row.
     */
    @Test
    public void t_02_209_administratorSelfApprovalInADesignatedSetIsRecorded() throws Throwable {
        session.then(r -> {
            prepare(r);
            String id = submitGrantOk(r, "admin", JOB, List.of("CONFIGURE"), 30,
                    "T-02-209 fix with a second approver", null, "a1", "admin");
            assertEquals(Arrays.asList("a1", "admin"), GrantRequestService.get().load(id).getApprovers(),
                    "premise: the request designates the set [a1, admin]");
            assertSuccess(decideGrant(r, "admin", id, "approve", "self approving within the set"),
                    "the administrator's self-approval within a set (D-37)");
            assertDecidedWithWindow(id, "admin", "admin");

            assertAll("an administrator's self-approval within a set must be recorded (SPEC 2 line 41, D-37, #182)",
                    () -> assertStoredSelfApprovedTrue(r, id, "after the self-approval within the set"),
                    () -> assertPageShowsSelfApproved(r, id, "after the self-approval within the set"));
        });
    }

    /**
     * T-02-210 (guard): u1 files a window request designating admin, and the administrator approves
     * it. That is not a self-approval: the stored file has no {@code selfApproved} true and the page
     * has no "Self-approved" row. Fails if the flag were recorded for every decision of an administrator.
     */
    @Test
    public void t_02_210_administratorApprovingAnotherUsersRequestIsNotASelfApproval() throws Throwable {
        session.then(r -> {
            prepare(r);
            String id = submitGrantOk(r, "u1", JOB, List.of("CONFIGURE"), 30,
                    "T-02-210 u1 asks the administrator", null, "admin");
            assertSuccess(decideGrant(r, "admin", id, "approve", "approved by the administrator"),
                    "the administrator's approval of u1's request");
            assertDecidedWithWindow(id, "admin", "u1");

            assertNotSelfApproved(r, id, "an administrator approving u1's request");
        });
    }

    /**
     * T-02-211 (guard): u1 files a window request designating a1, and a1 approves it: no
     * {@code selfApproved} true in the file, no "Self-approved" row. Fails if the flag were recorded
     * for every approval.
     */
    @Test
    public void t_02_211_listedApproverApprovingARequestIsNotASelfApproval() throws Throwable {
        session.then(r -> {
            prepare(r);
            String id = submitGrantOk(r, "u1", JOB, List.of("CONFIGURE"), 30,
                    "T-02-211 u1 asks a1", null, "a1");
            assertSuccess(decideGrant(r, "a1", id, "approve", "approved by a1"),
                    "the listed approver a1's approval of u1's request");
            assertDecidedWithWindow(id, "a1", "u1");

            assertNotSelfApproved(r, id, "a1 approving u1's request");
        });
    }

    /**
     * T-02-212 (guard): the administrator designates [a1, admin] as in T-02-209, and the other member
     * a1 decides: decided by a1, no {@code selfApproved} true, no "Self-approved" row. Fails if the
     * flag followed the requester being an administrator, or the requester being in the set.
     */
    @Test
    public void t_02_212_otherMemberDecidingTheAdministratorsRequestIsNotASelfApproval() throws Throwable {
        session.then(r -> {
            prepare(r);
            String id = submitGrantOk(r, "admin", JOB, List.of("CONFIGURE"), 30,
                    "T-02-212 fix with a second approver", null, "a1", "admin");
            assertSuccess(decideGrant(r, "a1", id, "approve", "approved by the other member"),
                    "a1's approval of the administrator's request (D-37: any member decides)");
            assertDecidedWithWindow(id, "a1", "admin");

            assertNotSelfApproved(r, id, "a1 deciding the administrator's request designating [a1, admin]");
        });
    }

    /**
     * T-02-213 (guard): a refused self-approval leaves nothing. Run control on (as T-GAP-230); the
     * administrator's request designating admin is filed while change control is on, then change
     * control is turned off. The administrator's approval through the page endpoint is refused (4xx,
     * LIMITATIONS 29), and so is the same approval through the service: each time the request stays
     * PENDING, no window exists and the file has no {@code selfApproved} true (the page is not read while
     * change control is off: it may answer LIMITATIONS 29's explanation). Change control is turned back
     * on and the administrator cancels the request: CANCELLED, and still no flag in the file and no row
     * on the page (a refused attempt must not leave the flag for a later save to write).
     */
    @Test
    public void t_02_213_selfApprovalRefusedWhileChangeControlIsOffLeavesNoFlag() throws Throwable {
        session.then(r -> {
            prepare(r);
            BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
            cfg.setRunControlEnabled(true);
            cfg.save();
            String id = submitGrantOk(r, "admin", JOB, List.of("CONFIGURE"), 30,
                    "T-02-213 filed before change control went off", null, "admin");
            assertStatus(RequestStatus.PENDING, id, "premise: the administrator's own request is filed");

            cfg.setChangeControlEnabled(false);
            cfg.save();
            assertFalse(BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "premise: change control is off");

            assertClientError(decideGrant(r, "admin", id, "approve", "self approving while off"),
                    "the administrator's self-approval while change control is off (LIMITATIONS 29)");
            assertRefusedLeftNothingStored(r, id, "the refused web self-approval");

            assertThrows(RuntimeException.class, () -> {
                try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
                    GrantRequestService.get().approve(id, "self approving while off, through the service");
                }
            }, "the service must refuse the self-approval while change control is off (LIMITATIONS 29)");
            assertRefusedLeftNothingStored(r, id, "the refused service self-approval");

            cfg.setChangeControlEnabled(true);
            cfg.save();
            assertStatus(RequestStatus.PENDING, id, "premise: the request is still PENDING once change control is back on");
            assertSuccess(post(r, "admin", "batch-control/grants/" + id + "/cancel", List.of()),
                    "the administrator's cancel of their own pending request");
            assertStatus(RequestStatus.CANCELLED, id, "premise: the cancel took effect");
            assertNotSelfApproved(r, id, "the request cancelled after two refused self-approvals");
        });
    }

    /**
     * T-02-214 (guard): a refused self-approval leaves nothing, refused at the decision for a reason
     * other than the approver. The administrator's request designating admin names the Freestyle job
     * {@code kind-x}; the job is then replaced by a folder of the same name (SPEC 8 line 176: approval
     * is refused when the item's kind has changed). The administrator's approval through the page
     * endpoint (its HTTP answer is not pinned: SPEC fixes the refusal, the row reads its outcome) and
     * through the service are refused: PENDING, no window, no {@code selfApproved} true, no row. The
     * administrator then cancels it: CANCELLED, still no flag and no row.
     */
    @Test
    public void t_02_214_selfApprovalRefusedForAReplacedItemLeavesNoFlag() throws Throwable {
        session.then(r -> {
            prepare(r);
            FreeStyleProject kind = r.createFreeStyleProject("kind-x");
            String id = submitGrantOk(r, "admin", "kind-x", List.of("CONFIGURE"), 30,
                    "T-02-214 configure kind-x", null, "admin");
            assertStatus(RequestStatus.PENDING, id, "premise: the administrator's own request is filed");

            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator replaces the item
                kind.delete();
                r.jenkins.createProject(Folder.class, "kind-x");
            }
            assertEquals("com.cloudbees.hudson.plugins.folder.Folder",
                    ((TopLevelItem) r.jenkins.getItemByFullName("kind-x")).getDescriptor().getId(),
                    "premise: kind-x is now a folder");

            // SPEC 8 line 176 fixes the refusal, not its HTTP answer: the row reads the outcome only
            WebResponse refused = decideGrant(r, "admin", id, "approve", "self approving a replaced item");
            String answer = "the web self-approval of a replaced item (HTTP " + refused.getStatusCode() + ")";
            assertRefusedLeftNothingStored(r, id, answer);
            assertNotSelfApproved(r, id, answer);

            assertThrows(RuntimeException.class, () -> {
                try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
                    GrantRequestService.get().approve(id, "self approving a replaced item, through the service");
                }
            }, "the service must refuse the self-approval of a request whose item changed kind (SPEC 8 line 176)");
            assertRefusedLeftNothingStored(r, id, "the refused service self-approval of a replaced item");
            assertNotSelfApproved(r, id, "the refused service self-approval of a replaced item");

            assertSuccess(post(r, "admin", "batch-control/grants/" + id + "/cancel", List.of()),
                    "the administrator's cancel of their own pending request");
            assertStatus(RequestStatus.CANCELLED, id, "premise: the cancel took effect");
            assertNotSelfApproved(r, id, "the request cancelled after two refused self-approvals");
        });
    }

    // ---------------------------------------------------------------- fixture

    /** The Batch Control matrix strategy (D-35a), change control on, approvers [a1, admin], job {@code batch-x}. */
    private static void prepare(JenkinsRule r) throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        r.jenkins.setAuthorizationStrategy(strategy);
        r.jenkins.save(); // fixture: the security configuration must survive the restart of T-02-208

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "admin"));
        cfg.save();
        assertTrue(cfg.isAllowAdminSelfApproval(), "premise: allowAdminSelfApproval is on (the default, SPEC 5)");
        assertTrue(cfg.isChangeControlEnabled(), "premise: change control is on");

        r.createFreeStyleProject(JOB);
    }

    /**
     * Premise of the readings: an administrator's self-approved run request, which already records
     * the flag (T-02-03), is found by both helpers: its stored file {@code requests/run/<id>.xml} has
     * {@code selfApproved} true and its page shows the row. Run control is switched on for it and off
     * again, so the grant rows that follow see the class's fixture unchanged.
     */
    private static void premiseTheReadingsFindTheRunRequestsFlag(JenkinsRule r) throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.save();
        FreeStyleProject job = r.createFreeStyleProject("run-x");
        setBatchControl(job, new BatchControlJobProperty(true));
        String runId = submitRunOk(r, "admin", job, "T-02-208 premise: a self-approved run request", "admin");
        assertSuccess(decideRun(r, "admin", runId, "approve", "self approving the run"),
                "premise: the administrator's self-approval of a run request");
        r.waitUntilNoActivity();

        assertEquals("true", storedChild(r, "requests/run/" + runId + ".xml", runId, "selfApproved"),
                "premise: the reading of the stored file finds a self-approved run request's selfApproved=true");
        String text = pageText(r, "batch-control/requests/" + runId + "/", runId);
        assertTrue(ROW_YES.matcher(text).find(), "premise: the reading of the page finds the run request page's"
                + " \"Self-approved\" \"Yes\" row; the page read: " + excerpt(text));

        cfg.setRunControlEnabled(false);
        cfg.save();
    }

    // ---------------------------------------------------------------- assertions

    private static void assertStatus(RequestStatus expected, String id, String what) {
        assertEquals(expected, GrantRequestService.get().load(id).getStatus(), what);
    }

    /** The approval took effect: APPROVED, decided by {@code decider}, and the requester's window is open. */
    private static void assertDecidedWithWindow(String id, String decider, String requester) {
        GrantRequest decided = GrantRequestService.get().load(id);
        assertEquals(RequestStatus.APPROVED, decided.getStatus(), "premise: the request is APPROVED");
        assertEquals(decider, decided.getDecidedBy(), "premise: the record names who decided (D-37)");
        assertEquals(1, activeWindowsOf(id).size(), "premise: exactly one window is open for the request");
        assertEquals(requester, activeWindowsOf(id).get(0).getUser(), "premise: the window is the requester's");
        assertTrue(GrantService.get().hasActiveGrant(requester, JOB, Item.CONFIGURE),
                "premise: " + requester + " holds the CONFIGURE window on " + JOB);
    }

    private static void assertStoredSelfApprovedTrue(JenkinsRule r, String id, String when) throws Exception {
        assertEquals("true", storedChild(r, "requests/grant/" + id + ".xml", id, "selfApproved"),
                "the stored grant request batch-control/requests/grant/" + id + ".xml must carry selfApproved=true "
                        + when + " (SPEC 2 line 41, #182)");
    }

    private static void assertPageShowsSelfApproved(JenkinsRule r, String id, String when) throws Exception {
        String text = pageText(r, "batch-control/grants/" + id + "/", id);
        assertTrue(ROW_YES.matcher(text).find(), "the grant request page must show the row \"Self-approved\" \"Yes\" "
                + when + ", as the run request page does (#182); the page read: " + excerpt(text));
    }

    /** Not a self-approval: no {@code selfApproved} true in the stored file (absent or false) and no row on the page. */
    private static void assertNotSelfApproved(JenkinsRule r, String id, String what) throws Exception {
        String stored = storedChild(r, "requests/grant/" + id + ".xml", id, "selfApproved");
        String text = pageText(r, "batch-control/grants/" + id + "/", id);
        assertAll(what + " is not a self-approval",
                () -> assertTrue(stored == null || "false".equals(stored), "the stored grant request must not carry"
                        + " selfApproved=true (absent or false) after " + what + ", was <" + stored + ">"),
                () -> assertFalse(text.contains(ROW_LABEL), "the grant request page must not show a \"Self-approved\""
                        + " row after " + what + " (shown only when true, #182); the page read: " + excerpt(text)));
    }

    /**
     * After a refused self-approval: still PENDING, no window, and no {@code selfApproved} true in the
     * stored file (absent or false). The page is not read here: with change control off the request's
     * page may answer LIMITATIONS 29's explanation instead; callers read it where the page is reachable.
     */
    private static void assertRefusedLeftNothingStored(JenkinsRule r, String id, String what) throws Exception {
        assertStatus(RequestStatus.PENDING, id, what + " must leave the request PENDING");
        assertTrue(activeWindowsOf(id).isEmpty(), what + " must open no window");
        assertTrue(storedWindowsOf(r, id).isEmpty(), what + " must store no window");
        String stored = storedChild(r, "requests/grant/" + id + ".xml", id, "selfApproved");
        assertTrue(stored == null || "false".equals(stored), "the stored grant request must not carry"
                + " selfApproved=true (absent or false) after " + what + ", was <" + stored + ">");
    }

    // ---------------------------------------------------------------- readings

    private static List<Grant> activeWindowsOf(String requestId) {
        return GrantService.get().listActive().stream()
                .filter(g -> requestId.equals(g.getGrantRequestId()))
                .toList();
    }

    /** The stored windows ({@code batch-control/grants/*.xml}, ARCHITECTURE 5) whose request is {@code requestId}. */
    private static List<Grant> storedWindowsOf(JenkinsRule r, String requestId) throws Exception {
        Path dir = r.jenkins.getRootDir().toPath().resolve("batch-control/grants");
        List<Grant> found = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return found;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".xml")).toList()) {
                Object read = Jenkins.XSTREAM2.fromXML(Files.readString(file, StandardCharsets.UTF_8));
                if (read instanceof Grant g && requestId.equals(g.getGrantRequestId())) {
                    found.add(g);
                }
            }
        }
        return found;
    }

    /**
     * The text of the element {@code name} directly under the root of the stored request file
     * {@code batch-control/<relative>} (ARCHITECTURE 5), or null when there is none. Premise: the file
     * exists and is the request {@code id}'s, and holds at most one such element.
     */
    private static String storedChild(JenkinsRule r, String relative, String id, String name) throws Exception {
        File file = new File(r.jenkins.getRootDir(), "batch-control/" + relative);
        assertTrue(file.isFile(), "premise (ARCHITECTURE 5): the request is stored as batch-control/" + relative);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element root = factory.newDocumentBuilder().parse(file).getDocumentElement();
        assertEquals(id, childText(root, "id"), "premise: batch-control/" + relative + " is the request " + id);
        return childText(root, name);
    }

    private static String childText(Element root, String name) {
        List<String> found = new ArrayList<>();
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getTagName())) {
                found.add(e.getTextContent().trim());
            }
        }
        assertTrue(found.size() <= 1, "premise: the stored request holds at most one <" + name + ">, found " + found);
        return found.isEmpty() ? null : found.get(0);
    }

    /** The visible text of {@code path} as the administrator sees it (JavaScript off). Premise: 200 and the request's own page. */
    private static String pageText(JenkinsRule r, String path, String id) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(r, "admin", path);
        assertEquals(200, page.getWebResponse().getStatusCode(), "premise: the administrator opens " + path);
        String text = page.asNormalizedText();
        assertTrue(text.contains(id), "premise: " + path + " is the page of the request " + id);
        return text;
    }
}
