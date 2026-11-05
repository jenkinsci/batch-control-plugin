package io.jenkins.plugins.batchcontrol;

import hudson.security.HudsonPrivateSecurityRealm;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.MONITOR_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code batch-control-strategy} monitor's {@code migrate}, {@code revert} and the stale-entry
 * {@code markReviewed} never redirect to a foreign Referer (issue #31, note 370, gap X-45, retired
 * drivers browser/verify/c11.mjs #1 and c4.mjs #3). Matrix row T-SEC-118.
 *
 * <p>Basis: CLAUDE.md ("anywhere user input ends up in ... HTML output" is validated; a Referer is user
 * input that ends up in a redirect), SPEC section 6 security line (state changes are POST with a permission
 * check), SPEC item 8 (the monitor offers to install the matching strategy and to revert it) and SPEC
 * item 2's D-58b line (Mark as reviewed). T-GAP-243 covers {@code markReviewed} of a listed, existing job;
 * this row covers the two strategy actions and a listed name whose item no longer exists (T-02-191,
 * T-GAP-245), with the protocol-relative form {@code //evil.example/x} and a foreign host whose path lies
 * under the Jenkins context ({@code http://evil.example/jenkins/manage/}) added. The Location is judged as a
 * browser follows it (resolved against the request URL), so its scheme, host and port are checked, not
 * only its path. A deleted job leaves the list, so the stale names are made the way T-02-191 and
 * T-GAP-245 make them: the job directories are removed while Jenkins is down.
 *
 * <p>The guard (a same-origin Referer {@code <Jenkins URL>manage/} is followed exactly, origin included) is a premise from the gap
 * design, not a SPEC sentence: it shows that each action reads the Referer at all, so the refusals of the
 * foreign forms are not the result of a fixed redirect.
 *
 * <p>Written from docs/SPEC.md items 2 and 8 and section 6, CLAUDE.md and docs/TEST-MATRIX.md (T-GAP-241,
 * T-GAP-243, T-GAP-245, T-02-191); the retired drivers were read only to learn what each looked at. No
 * src/main knowledge.
 */
public class MonitorRefererRedirectTest {

    private static final String BASE = "manage/administrativeMonitor/" + MONITOR_ID + "/";
    /** Foreign Referers; the fourth is built per session: a foreign host with a path under the Jenkins context. */
    private static final String[] FOREIGN = {"http://evil.example/steal", "//evil.example/x", "http://x/ y"};
    private static final String[] STALE = {"stale-1", "stale-2", "stale-3", "stale-4", "stale-5"};

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    /**
     * T-SEC-118 (CLAUDE.md input rule, SPEC 8, SPEC 2 D-58b; #31 gap X-45): with change control on, a
     * build authenticator configured and the plain project matrix installed, the administrator POSTs
     * (crumb, redirects not followed) {@code migrate} and then {@code revert} once with each Referer
     * {@code http://evil.example/steal}, {@code //evil.example/x}, {@code http://x/ y} and
     * {@code http://evil.example<context root>manage/} (a foreign host whose path lies under the Jenkins
     * context): each answer is a redirect whose Location, resolved against the request URL, has Jenkins' own
     * scheme, host and port and a path under the Jenkins root path, and contains neither
     * {@code evil.example} nor {@code x/ y}; each action takes effect (the Batch Control matrix after
     * migrate, the plain project matrix after revert). With the Batch Control matrix, five jobs bob changed
     * under his CONFIGURE window are listed; their directories are removed while Jenkins is down. After
     * the restart (premise: Manage Jenkins still names them) {@code markReviewed?item=<name>} with each of
     * the four foreign Referers answers the same way, and every reviewed name leaves the list. Guards: with
     * the Referer {@code <Jenkins URL>manage/} each of {@code migrate}, {@code revert} and the stale
     * {@code markReviewed} redirects to exactly that page: Jenkins' own scheme, host and port and the path
     * {@code <root>manage/}.
     */
    @Test
    public void t_sec_118_monitorActionsNeverRedirectToAForeignReferer() throws Throwable {
        session.then(r -> {
            HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
            for (String id : new String[] {"admin", "alice", "bob", "carol", "a1", "m1", "c1"}) {
                realm.createAccount(id, id);
            }
            r.jenkins.setSecurityRealm(realm);
            StrategyFixtures.changeControlOn();
            StrategyFixtures.configureBuildAuthenticator();
            r.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new ProjectMatrixAuthorizationStrategy()));
            URL own = r.getURL();
            String sameOrigin = own + "manage/";

            for (String referer : foreign(r)) {
                assertStaysInside(post(r, "migrate", null, referer), "migrate", referer, own);
                assertSame(BatchControlMatrixAuthorizationStrategy.class, r.jenkins.getAuthorizationStrategy().getClass(),
                        "migrate with Referer '" + referer + "' must install the Batch Control matrix");
                assertStaysInside(post(r, "revert", null, referer), "revert", referer, own);
                assertSame(ProjectMatrixAuthorizationStrategy.class, r.jenkins.getAuthorizationStrategy().getClass(),
                        "revert with Referer '" + referer + "' must restore the plain project matrix");
            }
            assertFollowsOwnPage(post(r, "migrate", null, sameOrigin), "guard: migrate", own);
            assertSame(BatchControlMatrixAuthorizationStrategy.class, r.jenkins.getAuthorizationStrategy().getClass(), "guard: migrated");
            assertFollowsOwnPage(post(r, "revert", null, sameOrigin), "guard: revert", own);
            assertSame(ProjectMatrixAuthorizationStrategy.class, r.jenkins.getAuthorizationStrategy().getClass(), "guard: reverted");

            r.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
            r.jenkins.save();
            for (String name : STALE) {
                r.createFreeStyleProject(name);
                StrategyFixtures.grant("bob", name, Arrays.asList(GrantAction.CONFIGURE));
                editAsBob(r, "job/" + name + "/");
            }
            String listed = manageText(r);
            for (String name : STALE) {
                assertTrue(listed.contains(name), "fixture: " + name + " is listed: " + UsabilityFixtures.excerpt(listed));
            }
        });
        for (String name : STALE) {
            Path dir = session.getHome().toPath().resolve("jobs/" + name);
            assertTrue(Files.isDirectory(dir), "premise: the job directory is " + dir);
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        session.then(r -> {
            URL own = r.getURL();
            String listed = manageText(r);
            for (String name : STALE) {
                assertNull(r.jenkins.getItemByFullName(name), "premise: " + name + " no longer exists");
                assertTrue(listed.contains(name), "premise (T-02-191): Manage Jenkins still lists the vanished " + name + ": "
                        + UsabilityFixtures.excerpt(listed));
            }
            List<String> foreign = foreign(r);
            for (int i = 0; i < foreign.size(); i++) {
                assertStaysInside(post(r, "markReviewed", "item=" + STALE[i], foreign.get(i)), "markReviewed of " + STALE[i],
                        foreign.get(i), own);
            }
            assertFollowsOwnPage(post(r, "markReviewed", "item=" + STALE[4], own + "manage/"), "guard: the stale markReviewed", own);
            String after = manageText(r);
            for (String name : STALE) {
                assertFalse(after.contains(name), "the review must remove " + name + " from the list: " + UsabilityFixtures.excerpt(after));
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    /** The four foreign Referers; the last has a foreign host and a path under the Jenkins context path. */
    private static List<String> foreign(JenkinsRule r) throws Exception {
        List<String> out = new ArrayList<>(Arrays.asList(FOREIGN));
        String underContext = "http://evil.example" + r.getURL().getPath() + "manage/";
        assertTrue(underContext.contains(r.contextPath + "/manage/"), "premise: the foreign Referer's path lies under the context path");
        out.add(underContext);
        return out;
    }

    private static void assertStaysInside(WebResponse answer, String what, String referer, URL own) throws Exception {
        int code = answer.getStatusCode();
        assertTrue(code >= 300 && code < 400, what + " with Referer '" + referer + "' must redirect, got " + code);
        String to = answer.getResponseHeaderValue("Location");
        assertNotNull(to, what + ": the redirect must carry a Location");
        assertFalse(to.contains("evil.example") || to.contains("x/ y") || to.contains("x/%20y") || to.startsWith("http://x/")
                || to.startsWith("//"), what + " must not follow the Referer '" + referer + "': " + to);
        URL target = resolve(answer, to);
        assertOwnOrigin(target, own, what + " with Referer '" + referer + "' (Location " + to + ")");
        assertTrue(target.getPath().startsWith(own.getPath()), what + " with Referer '" + referer + "' must stay inside Jenkins: " + to);
    }

    /** The guard: the redirect goes to exactly {@code <Jenkins URL>manage/}, origin included. */
    private static void assertFollowsOwnPage(WebResponse answer, String what, URL own) throws Exception {
        int code = answer.getStatusCode();
        assertTrue(code >= 300 && code < 400, what + " must redirect, got " + code);
        String to = answer.getResponseHeaderValue("Location");
        assertNotNull(to, what + ": the redirect must carry a Location");
        URL target = resolve(answer, to);
        assertOwnOrigin(target, own, what + " (Location " + to + ")");
        assertEquals(own.getPath() + "manage/", target.getPath(), what + " follows a same-origin Referer exactly: " + to);
    }

    /** The Location as a browser resolves it: a bare or relative path against the request URL. */
    private static URL resolve(WebResponse answer, String location) throws Exception {
        return new URL(answer.getWebRequest().getUrl(), location);
    }

    private static void assertOwnOrigin(URL target, URL own, String what) {
        assertEquals(own.getProtocol(), target.getProtocol(), what + ": the redirect must keep Jenkins' scheme");
        assertTrue(own.getHost().equalsIgnoreCase(target.getHost()), what + ": the redirect must go to Jenkins' own host "
                + own.getHost() + ", got " + target.getHost());
        assertEquals(port(own), port(target), what + ": the redirect must go to Jenkins' own port");
    }

    private static int port(URL url) {
        return url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
    }

    private static WebResponse post(JenkinsRule r, String action, String query, String referer) throws Exception {
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin", "admin");
        wc.getOptions().setRedirectEnabled(false);
        URL url = new URL(wc.createCrumbedUrl(BASE + action).toExternalForm() + (query == null ? "" : "&" + query));
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        req.setAdditionalHeader("Referer", referer);
        return wc.getPage(req).getWebResponse();
    }

    private static String manageText(JenkinsRule r) throws Exception {
        return r.createWebClient().login("admin", "admin").goTo("manage/").asNormalizedText();
    }

    /** bob saves a description through config.xml under his CONFIGURE window, so the job is listed. */
    private static void editAsBob(JenkinsRule r, String itemUrl) throws Exception {
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("bob", "bob");
        String xml = wc.goTo(itemUrl + "config.xml", "application/xml").getWebResponse().getContentAsString();
        String edited = xml.contains("<description/>") ? xml.replace("<description/>", "<description>edited by bob</description>")
                : xml.replaceFirst("(<project[^>]*>)", "$1<description>edited by bob</description>");
        assertFalse(edited.equals(xml), "fixture: the edit must change " + itemUrl);
        WebRequest req = new WebRequest(wc.createCrumbedUrl(itemUrl + "config.xml"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody(edited);
        int code = wc.getPage(req).getWebResponse().getStatusCode();
        assertTrue(code < 400, "fixture: bob's edit of " + itemUrl + " must be saved, got " + code);
    }
}
