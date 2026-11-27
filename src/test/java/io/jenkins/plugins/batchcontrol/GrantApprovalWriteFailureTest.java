package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.BatchControlNotifier;
import io.jenkins.plugins.batchcontrol.ops.Notification;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.function.Executable;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.post;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An approval of a permission window request is all or nothing (issue #184). Matrix rows T-02-215 ..
 * T-02-219 (note 379).
 *
 * <p>Basis: SPEC 4's state machine ("GrantRequest: PENDING -> APPROVED(=Grant 생성)": an approved request
 * is one whose window exists), SPEC 8 line 154 ("승인 즉시 요청자가 지정 범위에서 지정 행위의 Jenkins 권한을
 * 얻는다"), SPEC 4 (requests and valid grants are kept in {@code $JENKINS_HOME/batch-control/} and survive a
 * restart), SPEC 3's data model (a GrantRequest's {@code decidedBy}, {@code decidedAt},
 * {@code decisionComment}, and {@code selfApproved} since #182), SPEC 2 line 41 (an administrator's
 * self-approval records {@code selfApproved=true}), SPEC 7 (the requester cancels a PENDING request), SPEC 5
 * (a rejection needs a comment), SPEC 13 (APPROVED and REJECTED go to the requester), ARCHITECTURE 5
 * ({@code requests/grant/<id>.xml}, {@code grants/<id>.xml}) and the "Expected" of issue #184 that the
 * maintainer accepted: either the request is APPROVED and its window exists, or the request is still PENDING
 * and nothing of the decision was stored; after a failed approval the request is exactly as before (PENDING,
 * no {@code decidedBy}/{@code decidedAt}, not the comment of the failed approval, no {@code selfApproved}),
 * the approver is told it failed and no APPROVED notice is sent; once the directory accepts writes again, the
 * same request can be approved and the window works.
 *
 * <p>The fault: the directory {@code batch-control/grants/} (T-02-215 .. 217, 219) or
 * {@code batch-control/requests/grant/} (T-02-218) and every file in it refuse writes (directory
 * {@code r-xr-xr-x}, files {@code r--r--r--}), proven from outside the plugin by a refused file creation and
 * refused appends; where the platform cannot refuse its owner a write (Windows, root) the row is skipped
 * (note 291). Every permission is restored in a {@code finally} (the fault's {@code close}).
 *
 * <p>The rows read results only: the HTTP answer, whether {@code GrantRequestService#approve} threw, the
 * stored request file (the root's direct child elements, parsed with a DOM parser), the request page's
 * status badges ({@code data-batch-control-status}, T-UI-256) and text, the files under {@code grants/}, the
 * requester's {@code job/batch-x/configure} (403 without the window, 200 with it) and the requester's ACL,
 * and the notices a test notifier receives. The positive readings are shown to work in the same rows: the
 * successful approval after the fault finds APPROVED in the file and on the page, one window file and the
 * open configure page, and the APPROVED notice arrives.
 *
 * <p>Batch Control matrix strategy; change control on; the approver list is [a1, admin]; u1 holds
 * Overall/Read, Item/Read and RequestGrant; a1 holds Overall/Read, Item/Read and Approve; admin holds
 * Overall/Administer. Every row runs in a {@link JenkinsSessionExtension} session so that T-02-215 can
 * restart Jenkins.
 *
 * <p>Written from docs/SPEC.md, docs/ARCHITECTURE.md sections 2 and 5, docs/TEST-MATRIX.md and issue #184
 * only (no src/main knowledge).
 */
public class GrantApprovalWriteFailureTest {

    private static final String JOB = "batch-x";
    private static final String CONFIGURE_PAGE = "job/" + JOB + "/configure";
    private static final String ROW_LABEL = "Self-approved";
    /** The #182 row as the visible text renders it: the label, then its value. */
    private static final Pattern ROW_YES = Pattern.compile("Self-approved\\s*:?\\s+Yes\\b");

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    /** Records every notification for the rows of this class. */
    @TestExtension
    public static class CapturingNotifier extends BatchControlNotifier {
        @Override
        public void notify(NotificationEvent event, Notification notification) {
            NotificationCapture.record(event, notification);
        }
    }

    private String requestId;
    private StoredRequest storedBefore;

    /**
     * T-02-215: u1's CONFIGURE window request on {@code batch-x} designating a1 is PENDING. While
     * {@code grants/} refuses writes, a1 POSTs {@code grants/<id>/approve}. The answer is not a success
     * (HTTP 400 or above), the service still holds the request PENDING, the stored request is exactly as
     * before (status PENDING, no {@code decidedBy}, the same {@code decidedAt}, not the comment of the
     * failed approval, no {@code selfApproved} true), its page shows PENDING and not APPROVED, no window
     * exists in memory or under {@code grants/}, u1 is refused the configure page and holds no Configure,
     * and no APPROVED notice is sent. Jenkins restarts with {@code grants/} writable again: the request is
     * read back PENDING with none of the decision, still no window; a1 approves it, and it is APPROVED by
     * a1 with its own comment, its window exists in memory and on disk, u1 opens the configure page and the
     * APPROVED notice reaches u1.
     */
    @Test
    @Tag("core")
    public void t_02_215_approvalWhoseWindowCannotBeWrittenLeavesTheRequestPendingAcrossARestart() throws Throwable {
        String failed = "t-02-215 approval while grants refuses writes";
        session.then(r -> {
            prepare(r);
            requestId = submitGrantOk(r, "u1", JOB, List.of("CONFIGURE"), 30, "T-02-215 fix the nightly job", null, "a1");
            premiseFiledByU1(r, requestId);
            storedBefore = stored(r, requestId);

            try (Fault ignored = refuseWrites(grantsDir(r))) {
                WebResponse answer = decideGrant(r, "a1", requestId, "approve", failed);
                String what = "a1's web approval while grants/ refuses writes (HTTP " + answer.getStatusCode() + ")";
                assertLeftAsBefore(r, requestId, "u1", List.of(failed), storedBefore, what,
                        () -> assertNotSuccess(answer, what));
            }
        });

        session.then(r -> {
            assertAll("after the restart the request read back from disk is still undecided (SPEC 4, issue #184)",
                    () -> assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(requestId).getStatus(),
                            "the request must be read back PENDING after the restart"),
                    () -> assertStoredUndecided(r, requestId, storedBefore, List.of(failed), "after the restart"),
                    () -> assertPagePending(r, "u1", requestId, List.of(failed), "after the restart"),
                    () -> assertNoWindow(r, requestId, "u1", "after the restart"),
                    () -> assertTrue(NotificationCapture.of(NotificationEvent.APPROVED, requestId).isEmpty(),
                            "no APPROVED notice may have been sent for the request; delivered: " + NotificationCapture.describeAll()));

            String comment = "T-02-215 approved after the restart";
            assertSuccess(decideGrant(r, "a1", requestId, "approve", comment),
                    "a1's approval of the same request once grants/ accepts writes again");
            assertApprovedWithWindow(r, requestId, "a1", "u1", comment, List.of(failed));
        });
    }

    /**
     * T-02-216: as T-02-215 through the service. While {@code grants/} refuses writes, a1 calls
     * {@code GrantRequestService#approve}: it throws, and the request is exactly as before, its page shows
     * PENDING, no window exists, u1 is refused Configure and no APPROVED notice is sent. With {@code grants/}
     * writable again (same session, no restart) a1 approves the same request through the service: it returns
     * the request's window, the request is APPROVED by a1 with its own comment and the window confers
     * Configure on u1.
     */
    @Test
    public void t_02_216_serviceApprovalWhoseWindowCannotBeWrittenLeavesTheRequestPending() throws Throwable {
        session.then(r -> {
            prepare(r);
            String id = submitGrantOk(r, "u1", JOB, List.of("CONFIGURE"), 30, "T-02-216 fix the nightly job", null, "a1");
            premiseFiledByU1(r, id);
            StoredRequest before = stored(r, id);
            String failed = "t-02-216 service approval while grants refuses writes";

            try (Fault ignored = refuseWrites(grantsDir(r))) {
                Throwable thrown = thrownBy("a1", () -> GrantRequestService.get().approve(id, failed));
                assertLeftAsBefore(r, id, "u1", List.of(failed), before, "a1's service approval while grants/ refuses writes",
                        () -> assertNotNull(thrown, "GrantRequestService#approve must throw when the window cannot be"
                                + " written (issue #184: the approver is told it failed)"));
            }

            String comment = "T-02-216 approved once grants accepts writes";
            Grant window = as("a1", () -> GrantRequestService.get().approve(id, comment));
            assertNotNull(window, "the service approval once grants/ accepts writes again returns the window");
            assertEquals(id, window.getGrantRequestId(), "the returned window is the request's");
            assertApprovedWithWindow(r, id, "a1", "u1", comment, List.of(failed));
        });
    }

    /**
     * T-02-217: the #182 case. The administrator files a CONFIGURE window request on {@code batch-x}
     * designating only admin. While {@code grants/} refuses writes the administrator approves it, first by
     * POSTing {@code grants/<id>/approve}, then through {@code GrantRequestService#approve}. After each: the
     * answer is not a success / the service throws, the request is exactly as before, so the file has no
     * {@code selfApproved} true, its page shows PENDING and no "Self-approved" row, and no window exists. With
     * {@code grants/} writable again the administrator's self-approval succeeds: APPROVED by admin, the
     * window exists, {@code selfApproved} is {@code true} in the file and the page shows "Self-approved" "Yes".
     */
    @Test
    public void t_02_217_administratorSelfApprovalWhoseWindowCannotBeWrittenLeavesNoFlag() throws Throwable {
        session.then(r -> {
            prepare(r);
            String id = submitGrantOk(r, "admin", JOB, List.of("CONFIGURE"), 30, "T-02-217 emergency fix", null, "admin");
            assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(id).getStatus(),
                    "premise: the administrator's own request is filed PENDING");
            StoredRequest before = stored(r, id);
            String failedWeb = "t-02-217 web self approval while grants refuses writes";
            String failedService = "t-02-217 service self approval while grants refuses writes";

            try (Fault ignored = refuseWrites(grantsDir(r))) {
                WebResponse answer = decideGrant(r, "admin", id, "approve", failedWeb);
                String what = "the administrator's web self-approval while grants/ refuses writes (HTTP "
                        + answer.getStatusCode() + ")";
                assertLeftAsBefore(r, id, "admin", List.of(failedWeb), before, what, () -> assertNotSuccess(answer, what));

                Throwable thrown = thrownBy("admin", () -> GrantRequestService.get().approve(id, failedService));
                assertLeftAsBefore(r, id, "admin", List.of(failedWeb, failedService), before,
                        "the administrator's service self-approval while grants/ refuses writes",
                        () -> assertNotNull(thrown, "GrantRequestService#approve must throw when the window cannot be written"));
            }

            String comment = "T-02-217 self approved once grants accepts writes";
            assertSuccess(decideGrant(r, "admin", id, "approve", comment),
                    "the administrator's self-approval once grants/ accepts writes again");
            assertApprovedWithWindow(r, id, "admin", "admin", comment, List.of(failedWeb, failedService));
            assertEquals("true", stored(r, id).get("selfApproved"),
                    "the successful self-approval stores selfApproved=true (SPEC 2 line 41, #182)");
            String text = pageText(r, "admin", id);
            assertTrue(ROW_YES.matcher(text).find(), "the page shows \"Self-approved\" \"Yes\" after the successful"
                    + " self-approval (#182); the page read: " + excerpt(text));
        });
    }

    /**
     * T-02-218 (guard): the other directory. While {@code requests/grant/} (and the request's file) refuses
     * writes, a1 POSTs {@code grants/<id>/approve}, then calls {@code GrantRequestService#approve}. After
     * each: not a success / the service throws, the stored request is byte for byte unchanged, the service
     * still holds it PENDING, its page shows PENDING, no window exists in memory or under {@code grants/}
     * (which accepts writes), u1 is refused Configure and no APPROVED notice is sent. With
     * {@code requests/grant/} writable again a1's approval succeeds and the window works. Fails if a fix
     * wrote the window first and kept it when the request could not be written.
     */
    @Test
    public void t_02_218_approvalWhoseRequestCannotBeWrittenLeavesNoWindow() throws Throwable {
        session.then(r -> {
            prepare(r);
            String id = submitGrantOk(r, "u1", JOB, List.of("CONFIGURE"), 30, "T-02-218 fix the nightly job", null, "a1");
            premiseFiledByU1(r, id);
            StoredRequest before = stored(r, id);
            byte[] bytesBefore = Files.readAllBytes(requestFile(r, id));
            String failedWeb = "t-02-218 web approval while requests grant refuses writes";
            String failedService = "t-02-218 service approval while requests grant refuses writes";

            try (Fault ignored = refuseWrites(requestFile(r, id).getParent())) {
                WebResponse answer = decideGrant(r, "a1", id, "approve", failedWeb);
                String what = "a1's web approval while requests/grant/ refuses writes (HTTP " + answer.getStatusCode() + ")";
                assertLeftAsBefore(r, id, "u1", List.of(failedWeb), before, what,
                        () -> assertNotSuccess(answer, what),
                        () -> assertTrue(Arrays.equals(bytesBefore, Files.readAllBytes(requestFile(r, id))),
                                "the stored request must be byte for byte unchanged"));

                Throwable thrown = thrownBy("a1", () -> GrantRequestService.get().approve(id, failedService));
                assertLeftAsBefore(r, id, "u1", List.of(failedWeb, failedService), before,
                        "a1's service approval while requests/grant/ refuses writes",
                        () -> assertNotNull(thrown, "GrantRequestService#approve must throw when the request cannot be written"),
                        () -> assertTrue(Arrays.equals(bytesBefore, Files.readAllBytes(requestFile(r, id))),
                                "the stored request must be byte for byte unchanged"));
            }

            String comment = "T-02-218 approved once requests grant accepts writes";
            assertSuccess(decideGrant(r, "a1", id, "approve", comment),
                    "a1's approval once requests/grant/ accepts writes again");
            assertApprovedWithWindow(r, id, "a1", "u1", comment, List.of(failedWeb, failedService));
        });
    }

    /**
     * T-02-219 (guard): a failed approval leaves the request decidable. u1 files two window requests on
     * {@code batch-x} designating a1 (CONFIGURE, and DELETE). While {@code grants/} refuses writes a1's web
     * approval of the first and service approval of the second fail. With {@code grants/} writable again,
     * a1 rejects the first through {@code GrantRequestService#reject} with a comment, and u1 POSTs
     * {@code grants/<id>/cancel} for the second. Both succeed: the first is REJECTED by a1 with the
     * rejection's comment (in the service and in the file) and the REJECTED notice reaches u1; the second is
     * CANCELLED (in the service and in the file); neither file carries a failed approval's comment, neither
     * has a window, u1 holds no Configure and no APPROVED notice is sent. Fails if the request were only
     * rewritten PENDING on disk while the service still held it decided.
     */
    @Test
    public void t_02_219_failedApprovalLeavesTheRequestDecidable() throws Throwable {
        session.then(r -> {
            prepare(r);
            String toReject = submitGrantOk(r, "u1", JOB, List.of("CONFIGURE"), 30, "T-02-219 to be rejected", null, "a1");
            String toCancel = submitGrantOk(r, "u1", JOB, List.of("DELETE"), 30, "T-02-219 to be cancelled", null, "a1");
            premiseFiledByU1(r, toReject);
            premiseFiledByU1(r, toCancel);
            String failedWeb = "t-02-219 web approval while grants refuses writes";
            String failedService = "t-02-219 service approval while grants refuses writes";

            try (Fault ignored = refuseWrites(grantsDir(r))) {
                assertNotSuccess(decideGrant(r, "a1", toReject, "approve", failedWeb),
                        "a1's web approval while grants/ refuses writes");
                assertNotNull(thrownBy("a1", () -> GrantRequestService.get().approve(toCancel, failedService)),
                        "a1's service approval while grants/ refuses writes must throw");
            }

            String rejection = "T-02-219 rejected after the failed approval";
            Throwable rejectThrew = thrownBy("a1", () -> {
                GrantRequestService.get().reject(toReject, rejection);
                return null;
            });
            assertNull(rejectThrew, "after a failed approval the request must still be decidable: a1's rejection through"
                    + " the service must succeed (issue #184; the service reads it "
                    + GrantRequestService.get().load(toReject).getStatus() + ")");
            WebResponse cancel = post(r, "u1", "batch-control/grants/" + toCancel + "/cancel", List.of());
            assertSuccess(cancel, "after a failed approval the request must still be decidable: u1's cancel (SPEC 7;"
                    + " the service reads it " + GrantRequestService.get().load(toCancel).getStatus() + ")");

            StoredRequest rejected = stored(r, toReject);
            StoredRequest cancelled = stored(r, toCancel);
            List<String> failedComments = List.of(failedWeb, failedService);
            assertAll("the decisions after a failed approval are the ones stored (issue #184)",
                    () -> assertEquals(RequestStatus.REJECTED, GrantRequestService.get().load(toReject).getStatus(),
                            "the service holds the rejected request REJECTED"),
                    () -> assertEquals("REJECTED", rejected.get("status"), "the stored request is REJECTED"),
                    () -> assertEquals("a1", rejected.get("decidedBy"), "the stored rejection names a1 (SPEC 3)"),
                    () -> assertEquals(rejection, rejected.get("decisionComment"), "the stored comment is the rejection's"),
                    () -> assertEquals(RequestStatus.CANCELLED, GrantRequestService.get().load(toCancel).getStatus(),
                            "the service holds the cancelled request CANCELLED"),
                    () -> assertEquals("CANCELLED", cancelled.get("status"), "the stored request is CANCELLED"),
                    () -> assertNoFailedComment(rejected, failedComments, "the rejected request"),
                    () -> assertNoFailedComment(cancelled, failedComments, "the cancelled request"),
                    () -> assertNoWindow(r, toReject, "u1", "after the rejection"),
                    () -> assertNoWindow(r, toCancel, "u1", "after the cancel"),
                    () -> assertEquals(List.of("u1"), NotificationCapture.await(NotificationEvent.REJECTED, toReject).get(0).recipients,
                            "the REJECTED notice goes to u1 (SPEC 13)"),
                    () -> assertTrue(NotificationCapture.afterQuietPeriod(NotificationEvent.APPROVED, toReject).isEmpty()
                                    && NotificationCapture.of(NotificationEvent.APPROVED, toCancel).isEmpty(),
                            "no APPROVED notice may be sent for either request; delivered: " + NotificationCapture.describeAll()));
        });
    }

    // ---------------------------------------------------------------- fixture

    /** The Batch Control matrix strategy (D-35a), change control on, approvers [a1, admin], job {@code batch-x}. */
    private static void prepare(JenkinsRule r) throws Exception {
        NotificationCapture.clear();
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
        r.jenkins.save(); // fixture: the security configuration must survive the restart of T-02-215

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "admin"));
        cfg.save();
        assertTrue(cfg.isChangeControlEnabled(), "premise: change control is on");

        r.createFreeStyleProject(JOB);
    }

    /**
     * Premise of a request u1 filed designating a1: PENDING, the REQUEST_CREATED notice for it reached a1 (the
     * test notifier sees this request), and u1 is refused the configure page and holds no Configure.
     */
    private static void premiseFiledByU1(JenkinsRule r, String id) throws Exception {
        assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(id).getStatus(), "premise: u1's request is filed PENDING");
        assertEquals(List.of("a1"), NotificationCapture.await(NotificationEvent.REQUEST_CREATED, id).get(0).recipients,
                "premise: the test notifier receives the REQUEST_CREATED notice of the request (SPEC 13)");
        assertEquals(403, ApproverFormFixtures.get(r, "u1", CONFIGURE_PAGE).getStatusCode(),
                "premise: before any window u1 is refused " + CONFIGURE_PAGE);
        assertFalse(can(r, "u1", Item.CONFIGURE), "premise: before any window u1 holds no Configure on " + JOB);
    }

    private static Path grantsDir(JenkinsRule r) {
        return r.jenkins.getRootDir().toPath().resolve("batch-control").resolve("grants");
    }

    private static Path requestFile(JenkinsRule r, String id) {
        return r.jenkins.getRootDir().toPath().resolve("batch-control/requests/grant/" + id + ".xml");
    }

    /** The restore handle of one injected fault: puts back the POSIX permissions it found, directory first. */
    private static final class Fault implements AutoCloseable {
        private final Map<Path, Set<PosixFilePermission>> original;

        private Fault(Map<Path, Set<PosixFilePermission>> original) {
            this.original = original;
        }

        @Override
        public void close() throws IOException {
            for (Map.Entry<Path, Set<PosixFilePermission>> e : original.entrySet()) {
                Files.setPosixFilePermissions(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * Makes {@code dir} and every file in it refuse writes ({@code r-xr-xr-x} / {@code r--r--r--}), proves it
     * (a new file cannot be created there and no existing file can be appended to) and returns the handle that
     * undoes it. The directory is created first if it does not exist yet (fixture: ARCHITECTURE 5 names it).
     * Skipped where the platform cannot refuse its owner a write (note 291).
     */
    private static Fault refuseWrites(Path dir) throws IOException {
        Files.createDirectories(dir);
        PlatformFixtures.assumeCanMakeUnwritable();
        List<Path> all = new ArrayList<>();
        all.add(dir);
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile).forEach(all::add);
        }
        Map<Path, Set<PosixFilePermission>> original = new LinkedHashMap<>();
        for (Path p : all) {
            try {
                original.put(p, Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS));
            } catch (UnsupportedOperationException e) {
                Assumptions.assumeTrue(false, "POSIX permissions are needed to make " + dir + " refuse writes");
            }
        }
        Fault fault = new Fault(original);
        try {
            List<Path> filesFirst = new ArrayList<>(all);
            Collections.reverse(filesFirst);
            for (Path p : filesFirst) {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(p.equals(dir) ? "r-xr-xr-x" : "r--r--r--"));
            }
            Assumptions.assumeTrue(writesRefused(dir, all.subList(1, all.size())),
                    "the platform did not refuse writes to " + dir + " (root?), so the fault cannot be built here");
            return fault;
        } catch (IOException | RuntimeException e) {
            fault.close();
            throw e;
        }
    }

    private static boolean writesRefused(Path dir, List<Path> files) {
        Path probe = dir.resolve("probe-" + System.nanoTime() + ".tmp");
        try {
            Files.writeString(probe, "probe", StandardCharsets.UTF_8);
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // a left-over probe names no request, so no row reads it as a window
            }
            return false;
        } catch (IOException expected) {
            // refused, as the fault needs
        }
        for (Path file : files) {
            try {
                Files.writeString(file, "", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                return false;
            } catch (IOException expected) {
                // refused, as the fault needs
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- assertions

    private static void assertNotSuccess(WebResponse response, String what) {
        int code = response.getStatusCode();
        assertTrue(code >= 400, what + " must not answer as a success (redirects are not followed, so a success is"
                + " below 400; issue #184: the approver is told it failed), got HTTP " + code + ": "
                + excerpt(response.getContentAsString()));
    }

    /**
     * After a failed approval (issue #184): the {@code answer} checks (the HTTP answer or the thrown
     * exception), the service still holds the request PENDING, the stored request is undecided and every
     * stored field is as {@code before}, the page shows PENDING, no window exists and no APPROVED notice is
     * sent. All are reported together.
     */
    private static void assertLeftAsBefore(JenkinsRule r, String id, String requester, List<String> failedComments,
                                           StoredRequest before, String what, Executable... answer) {
        List<Executable> checks = new ArrayList<>(Arrays.asList(answer));
        checks.add(() -> assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(id).getStatus(),
                "the service must still hold the request PENDING after " + what));
        checks.add(() -> assertStoredUndecided(r, id, before, failedComments, "after " + what));
        checks.add(() -> assertEquals(before.fields(), stored(r, id).fields(), "the stored request"
                + " batch-control/requests/grant/" + id + ".xml must be exactly as before " + what));
        checks.add(() -> assertPagePending(r, requester, id, failedComments, "after " + what));
        checks.add(() -> assertNoWindow(r, id, requester, "after " + what));
        checks.add(() -> assertTrue(NotificationCapture.afterQuietPeriod(NotificationEvent.APPROVED, id).isEmpty(),
                "no APPROVED notice may be sent after " + what + "; delivered: " + NotificationCapture.describeAll()));
        assertAll(what + " must leave the request exactly as before (issue #184: an approval is all or nothing)",
                checks.toArray(new Executable[0]));
    }

    /**
     * The stored request says PENDING and holds none of a decision: no decidedBy, its decision fields (every
     * element named {@code decided*} or {@code decision*}: SPEC 3's {@code decidedBy}, {@code decidedAt} and
     * {@code decisionComment}, whatever the stored spelling of the time) are those of {@code before}, no
     * selfApproved true and no failed comment.
     */
    private static void assertStoredUndecided(JenkinsRule r, String id, StoredRequest before, List<String> failedComments,
                                              String when) throws Exception {
        StoredRequest now = stored(r, id);
        String decidedBy = now.get("decidedBy");
        String selfApproved = now.get("selfApproved");
        assertAll("the stored request batch-control/requests/grant/" + id + ".xml " + when,
                () -> assertEquals("PENDING", now.get("status"), "its status must be PENDING " + when),
                () -> assertTrue(decidedBy == null || decidedBy.isBlank(),
                        "it must name no decider " + when + ", was <" + decidedBy + ">"),
                () -> assertEquals(before.decisionFields(), now.decisionFields(),
                        "its decision fields must be those it had before the failed approval " + when),
                () -> assertTrue(selfApproved == null || "false".equals(selfApproved),
                        "it must not carry selfApproved=true " + when + ", was <" + selfApproved + ">"),
                () -> assertNoFailedComment(now, failedComments, "the stored request " + when));
    }

    private static void assertNoFailedComment(StoredRequest stored, List<String> failedComments, String what) {
        for (String failed : failedComments) {
            assertFalse(stored.raw().contains(failed), what + " must not carry the comment of a failed approval <"
                    + failed + ">: " + excerpt(stored.raw()));
        }
    }

    /** The requester's view of the request page shows PENDING, not APPROVED, no failed comment and no "Self-approved". */
    private static void assertPagePending(JenkinsRule r, String viewer, String id, List<String> failedComments, String when)
            throws Exception {
        HtmlPage page = page(r, viewer, id);
        Set<String> statuses = statusesOn(page);
        String text = page.asNormalizedText();
        assertAll("the request page as " + viewer + " sees it " + when,
                () -> assertTrue(statuses.contains("PENDING"), "it must show the request PENDING " + when
                        + ", statuses shown " + statuses + "; the page read: " + excerpt(text)),
                () -> assertFalse(statuses.contains("APPROVED"), "it must not show the request APPROVED " + when
                        + ", statuses shown " + statuses + "; the page read: " + excerpt(text)),
                () -> assertFalse(text.contains(ROW_LABEL), "it must show no \"Self-approved\" row " + when
                        + "; the page read: " + excerpt(text)),
                () -> {
                    for (String failed : failedComments) {
                        assertFalse(text.contains(failed), "it must not show the comment of a failed approval <" + failed
                                + "> " + when + "; the page read: " + excerpt(text));
                    }
                });
    }

    /** No window for the request in memory or under {@code grants/}, and (a non-administrator) requester holds no Configure. */
    private static void assertNoWindow(JenkinsRule r, String id, String requester, String when) throws Exception {
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertTrue(activeWindowsOf(id).isEmpty(), "no window may be open for the request " + when
                + ", open: " + activeWindowsOf(id)));
        checks.add(() -> assertTrue(storedWindowFiles(r, id).isEmpty(), "no file under batch-control/grants/ may hold a"
                + " window of the request " + when + ", found " + storedWindowFiles(r, id)));
        if (!"admin".equals(requester)) {
            checks.add(() -> assertEquals(403, ApproverFormFixtures.get(r, requester, CONFIGURE_PAGE).getStatusCode(),
                    requester + " must be refused " + CONFIGURE_PAGE + " " + when + " (no window confers Configure)"));
            checks.add(() -> assertFalse(can(r, requester, Item.CONFIGURE), requester + " must hold no Configure on "
                    + JOB + " " + when));
        }
        assertAll("no window exists " + when, checks.toArray(new Executable[0]));
    }

    /**
     * The approval took effect: APPROVED by {@code decider} with {@code comment} in the service and the file
     * (no failed comment), the page shows APPROVED, exactly one window for the requester in memory and one
     * window file, and a non-administrator requester opens the configure page, holds Configure and receives
     * the APPROVED notice.
     */
    private static void assertApprovedWithWindow(JenkinsRule r, String id, String decider, String requester, String comment,
                                                 List<String> failedComments) throws Exception {
        StoredRequest now = stored(r, id);
        Set<String> statuses = statusesOn(page(r, requester, id));
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertEquals(RequestStatus.APPROVED, GrantRequestService.get().load(id).getStatus(),
                "the service holds the request APPROVED"));
        checks.add(() -> assertEquals(decider, GrantRequestService.get().load(id).getDecidedBy(),
                "the service names " + decider + " as the decider (D-37)"));
        checks.add(() -> assertEquals("APPROVED", now.get("status"), "the stored request is APPROVED"));
        checks.add(() -> assertEquals(decider, now.get("decidedBy"), "the stored request names " + decider + " (SPEC 3)"));
        checks.add(() -> assertEquals(comment, now.get("decisionComment"), "the stored comment is the successful approval's"));
        checks.add(() -> assertNoFailedComment(now, failedComments, "the approved request"));
        checks.add(() -> assertTrue(statuses.contains("APPROVED"), "the page shows the request APPROVED (premise of the"
                + " page reading), statuses shown " + statuses));
        checks.add(() -> assertEquals(1, activeWindowsOf(id).size(), "exactly one window is open for the request"));
        checks.add(() -> assertEquals(requester, activeWindowsOf(id).get(0).getUser(), "the window is " + requester + "'s"));
        checks.add(() -> assertEquals(1, storedWindowFiles(r, id).size(), "exactly one file under batch-control/grants/"
                + " holds the request's window (ARCHITECTURE 5), found " + storedWindowFiles(r, id)));
        if (!"admin".equals(requester)) {
            checks.add(() -> assertEquals(200, ApproverFormFixtures.get(r, requester, CONFIGURE_PAGE).getStatusCode(),
                    "the window lets " + requester + " open " + CONFIGURE_PAGE + " (SPEC 8 line 154)"));
            checks.add(() -> assertTrue(can(r, requester, Item.CONFIGURE), "the window confers Configure on " + JOB));
            checks.add(() -> assertEquals(List.of(requester), NotificationCapture.await(NotificationEvent.APPROVED, id).get(0).recipients,
                    "the APPROVED notice goes to " + requester + " (SPEC 13)"));
        }
        assertAll("the approval once the store accepts writes again took effect (issue #184)", checks.toArray(new Executable[0]));
    }

    // ---------------------------------------------------------------- readings

    /** The stored request file: its raw text, its root's direct child elements in order, and those elements by name. */
    private record StoredRequest(String raw, List<String> fields, Map<String, List<String>> byName) {
        /** The text of the one child element {@code name}, or null when there is none. */
        String get(String name) {
            List<String> found = byName.getOrDefault(name, List.of());
            assertTrue(found.size() <= 1, "premise: the stored request holds at most one <" + name + ">, found " + found);
            return found.isEmpty() ? null : found.get(0);
        }

        /** The fields named {@code decided*} or {@code decision*}, in order. */
        List<String> decisionFields() {
            return fields.stream().filter(f -> f.startsWith("decided") || f.startsWith("decision")).toList();
        }
    }

    /** Reads {@code batch-control/requests/grant/<id>.xml} (ARCHITECTURE 5). Premise: it exists and is the request {@code id}'s. */
    private static StoredRequest stored(JenkinsRule r, String id) throws Exception {
        File file = requestFile(r, id).toFile();
        assertTrue(file.isFile(), "premise (ARCHITECTURE 5): the request is stored as batch-control/requests/grant/" + id + ".xml");
        String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element root = factory.newDocumentBuilder().parse(file).getDocumentElement();
        List<String> fields = new ArrayList<>();
        fields.add("<root>=" + root.getTagName());
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                String text = e.getTextContent().replaceAll("\\s+", " ").trim();
                fields.add(e.getTagName() + "=" + text);
                byName.computeIfAbsent(e.getTagName(), k -> new ArrayList<>()).add(text);
            }
        }
        StoredRequest stored = new StoredRequest(raw, fields, byName);
        assertEquals(id, stored.get("id"), "premise: batch-control/requests/grant/" + id + ".xml is the request " + id);
        return stored;
    }

    private static List<Grant> activeWindowsOf(String requestId) {
        return GrantService.get().listActive().stream()
                .filter(g -> requestId.equals(g.getGrantRequestId()))
                .toList();
    }

    /** The files under {@code batch-control/grants/} (ARCHITECTURE 5: {@code grants/<id>.xml}) whose text names the request. */
    private static List<String> storedWindowFiles(JenkinsRule r, String requestId) throws IOException {
        Path dir = grantsDir(r);
        List<String> found = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return found;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains(requestId)) {
                    found.add(file.getFileName().toString());
                }
            }
        }
        return found;
    }

    /** {@code batch-control/grants/<id>/} as {@code viewer} sees it (JavaScript off). Premise: 200 and the request's own page. */
    private static HtmlPage page(JenkinsRule r, String viewer, String id) throws Exception {
        String path = "batch-control/grants/" + id + "/";
        HtmlPage page = UsabilityFixtures.htmlPage(r, viewer, path);
        assertEquals(200, page.getWebResponse().getStatusCode(), "premise: " + viewer + " opens " + path);
        assertTrue(page.asNormalizedText().contains(id), "premise: " + path + " is the page of the request " + id);
        return page;
    }

    private static String pageText(JenkinsRule r, String viewer, String id) throws Exception {
        return page(r, viewer, id).asNormalizedText();
    }

    /** The statuses the page's badges carry in {@code data-batch-control-status} (T-UI-256). */
    private static Set<String> statusesOn(HtmlPage page) {
        Set<String> out = new LinkedHashSet<>();
        for (Object o : page.getByXPath("//*[@data-batch-control-status]")) {
            out.add(((DomElement) o).getAttribute("data-batch-control-status"));
        }
        return out;
    }

    private static boolean can(JenkinsRule r, String userId, hudson.security.Permission permission) {
        return r.jenkins.getItemByFullName(JOB).getACL().hasPermission2(User.getById(userId, true).impersonate2(), permission);
    }

    private interface Body<T> {
        T run() throws Exception;
    }

    private static <T> T as(String userId, Body<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(userId, true).impersonate2())) {
            return body.run();
        }
    }

    /** Runs {@code body} as {@code userId}; returns what it threw, or null when it returned. */
    private static Throwable thrownBy(String userId, Body<?> body) {
        try {
            as(userId, body);
            return null;
        } catch (Exception e) {
            return e;
        }
    }
}
