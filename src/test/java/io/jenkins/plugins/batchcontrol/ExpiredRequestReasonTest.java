package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.tasks.Mailer;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import jakarta.mail.Message;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.mock_javamail.Mailbox;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detail pages of expired activation and grant requests say why they expired, and the EXPIRED mails carry
 * the reason (issue #31, retired legacy driver fresh/v/vh-expiry.mjs #2 and #3, gap X-50). Matrix row T-07-18
 * (note 371).
 *
 * <p>Basis: SPEC 7 ("승인 대기 요청은 설정된 기간이 지나면 자동 만료"; "만료 시 상태가 EXPIRED로 바뀌고 이력에 남는다"),
 * SPEC 13 / D-54 ("The requester receives EXPIRED ... with the reason"), SPEC 6 usability (recorded history
 * names what happened) and TEST-MATRIX T-07-10 / T-13-21, which cover run requests only. The reason is read
 * with T-07-10's reading, narrowed to the one cause here: the request was not decided within the pending
 * timeout of 1 hour (the wording is not pinned).
 *
 * <p>Users: {@code u1} (Item/Read, BatchControl/Request and RequestGrant) with a Mailer address, {@code a1}
 * approver. Run control and change control on; e-mail notifications on (mock-javamail). Time moves through
 * {@link BatchClock} and the expiry work is run directly (matrix note 2).
 *
 * <p>Written from docs/SPEC.md items 7, 13 and 6, docs/DECISIONS.md D-54 and docs/TEST-MATRIX.md (T-07-10,
 * T-13-21, note 168); the retired driver was read only to learn what it looked at. No src/main knowledge.
 */
@WithJenkins
public class ExpiredRequestReasonTest {

    private static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");
    private static final String U1_MAIL = "requester.one@example.com";
    /** The pending timeout as the reason names it: not decided, within 1 hour. */
    private static final Pattern NOT_DECIDED = Pattern.compile("(?is)(decid|decision).*\\b(1\\s*hour|60\\s*min)");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        Mailbox.clearAll();
        JenkinsLocationConfiguration.get().setAdminAddress("batch-control@example.com");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST, BatchControlPermissions.REQUEST_GRANT)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        User.getById("u1", true).addProperty(new Mailer.UserProperty(U1_MAIL));
        User.getById("a1", true).addProperty(new Mailer.UserProperty("alpha.one@example.com"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.setPendingTimeoutHours(1);
        cfg.setEmailNotifications(true);
        cfg.save();
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
        Mailbox.clearAll();
    }

    /**
     * T-07-18 (SPEC 7, SPEC 13 D-54, SPEC 6; #31 gap X-50): pendingTimeoutHours 1. At T0 u1 submits an
     * ACTIVATE request for the not-activated {@code act-x} and a CONFIGURE window request for {@code win-x}.
     * Guard first: at T0 + 30 min, after the expiry work, both are PENDING and neither detail page says
     * "expired". At T0 + 61 min the expiry work ends both as EXPIRED; each detail page (as u1) then says it
     * expired, and the text after "expired" names the missing decision within 1 hour. u1 receives, for each
     * request, a mail naming its id and "expired" whose text names the same reason.
     */
    @Test
    public void t_07_18_expiredActivationAndGrantRequestsSayWhy() throws Exception {
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        FreeStyleProject actX = j.createFreeStyleProject("act-x");
        setBatchControl(actX, new BatchControlJobProperty(true));
        j.createFreeStyleProject("win-x");
        String activation = submitActivationOk(j, "u1", actX, "ACTIVATE", "put act-x into service", "a1");
        String grant = submitGrantOk(j, "u1", "win-x", List.of("CONFIGURE"), 30, "adjust the win-x schedule", null, "a1");
        String activationPage = "batch-control/activations/" + activation + "/";
        String grantPage = "batch-control/grants/" + grant + "/";

        expiryWorkAt(T0.plus(Duration.ofMinutes(30)));
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(activation).getStatus(), "guard premise: still PENDING");
        assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(grant).getStatus(), "guard premise: still PENDING");
        for (String page : new String[] {activationPage, grantPage}) {
            String text = mainText(page);
            assertFalse(text.toLowerCase(Locale.ROOT).contains("expired"), "guard: before the timeout " + page + " must not say"
                    + " the request expired: " + excerpt(text));
        }
        Mailbox.clearAll();

        expiryWorkAt(T0.plus(Duration.ofMinutes(61)));
        assertEquals(RequestStatus.EXPIRED, ActivationService.get().load(activation).getStatus(), "premise: the activation request EXPIRED");
        assertEquals(RequestStatus.EXPIRED, GrantRequestService.get().load(grant).getStatus(), "premise: the grant request EXPIRED");
        for (String page : new String[] {activationPage, grantPage}) {
            String reason = afterExpired(mainText(page), page);
            assertTrue(NOT_DECIDED.matcher(reason).find(), "SPEC 7 / D-54: the expired request's page must say why it expired"
                    + " (not decided within 1 hour): " + reason);
        }

        for (String id : new String[] {activation, grant}) {
            List<String> mails = awaitMails(id);
            assertFalse(mails.isEmpty(), "D-54: u1 must receive an EXPIRED mail naming " + id + "; mailbox held "
                    + Mailbox.get(U1_MAIL).size() + " message(s)");
            assertTrue(mails.stream().anyMatch(m -> NOT_DECIDED.matcher(m).find()), "D-54: u1's EXPIRED mail about " + id
                    + " must carry the reason (not decided within 1 hour): " + mails);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void expiryWorkAt(Instant at) throws Exception {
        BatchClock.setForTest(Clock.fixed(at, ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
    }

    private String mainText(String page) throws Exception {
        return UsabilityFixtures.htmlPage(j, "u1", page).getElementById("main-panel").asNormalizedText();
    }

    /** The text after the first "expired" on the page (as T-07-10 reads it). */
    private static String afterExpired(String text, String page) {
        int at = text.toLowerCase(Locale.ROOT).indexOf("expired");
        assertTrue(at >= 0, page + " must say the request expired: " + excerpt(text));
        String tail = text.substring(at);
        return tail.length() > 600 ? tail.substring(0, 600) : tail;
    }

    /** u1's mails naming {@code id} and "expired" (subject and body), polled for the asynchronous delivery. */
    private static List<String> awaitMails(String id) throws Exception {
        long deadline = System.currentTimeMillis() + NotificationCapture.DELIVERY_TIMEOUT_MS;
        List<String> found = mails(id);
        while (found.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50); // polling for asynchronous delivery
            found = mails(id);
        }
        return found;
    }

    private static List<String> mails(String id) throws Exception {
        List<String> out = new ArrayList<>();
        for (Message message : new ArrayList<>(Mailbox.get(U1_MAIL))) {
            String text = message.getSubject() + "\n" + message.getContent();
            if (text.contains(id) && text.toLowerCase(Locale.ROOT).contains("expired")) {
                out.add(text);
            }
        }
        return out;
    }
}
