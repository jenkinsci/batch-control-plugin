package io.jenkins.plugins.batchcontrol;

import hudson.security.HudsonPrivateSecurityRealm;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.MONITOR_ID;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code batch-control-strategy} monitor's "Mark as reviewed" control for a listed item whose name no
 * longer resolves (matrix row T-UI-304, second method; note 369 (13)). The state is the one T-GAP-245
 * describes: a job listed as changed under a grant whose directory disappears while Jenkins is down stays
 * listed after the restart and can still be reviewed. T-UI-304's first method
 * ({@code StrategyMonitorReviewAccessTest}) renders only rows whose item resolves.
 *
 * <p>Basis: SPEC item 2 (D-58b: "The state ends only through the explicit 'Mark as reviewed' action"),
 * SPEC 6 usability (the list must not keep an entry that no action can clear, note 277), the e2e-08
 * screen contract ("Mark as reviewed" rendered as a {@code jenkins-button}) and docs/TEST-MATRIX.md
 * T-GAP-245; no src/main knowledge.
 */
public class StrategyMonitorUnresolvedItemTest {

    private static final String GONE = "gone-b";
    private static final String KEPT = "kept-b";

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    /**
     * T-UI-304 (unresolved branch): bob changes {@code gone-b} and {@code kept-b} under his CONFIGURE
     * windows, so both are listed (premise). While Jenkins is down {@code jobs/gone-b} is removed. After
     * the restart {@code gone-b} no longer resolves (premise) and Manage Jenkins offers exactly one review
     * control targeting it (premise: its {@code data-url} or form names {@code markReviewed} with
     * {@code item=gone-b}, and it reads "Mark as reviewed"); that control carries core's
     * {@code jenkins-button} class. Guard: the resolving {@code kept-b}'s control on the same page is
     * held to the same rule.
     */
    @Test
    public void t_ui_304_unresolvedItemsMarkAsReviewedIsAButton() throws Throwable {
        session.then(r -> {
            HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
            for (String id : new String[] {"admin", "alice", "bob", "carol", "a1", "m1", "c1"}) {
                realm.createAccount(id, id);
            }
            r.jenkins.setSecurityRealm(realm);
            r.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
            r.jenkins.save();
            StrategyFixtures.changeControlOn();
            for (String name : new String[] {GONE, KEPT}) {
                r.createFreeStyleProject(name);
                StrategyFixtures.grant("bob", name, Arrays.asList(GrantAction.CONFIGURE));
                editAsBob(r, "job/" + name + "/");
            }
            DomNode monitor = monitor(manage(r));
            for (String name : new String[] {GONE, KEPT}) {
                assertEquals(1, reviewControls(monitor, name).size(), "premise: " + name + " is listed with one review control: "
                        + excerpt(monitor.asNormalizedText()));
            }
        });
        Path dir = session.getHome().toPath().resolve("jobs/" + GONE);
        assertTrue(Files.isDirectory(dir), "premise: the job directory is " + dir);
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        session.then(r -> {
            assertNull(r.jenkins.getItemByFullName(GONE), "premise: " + GONE + " no longer resolves");
            assertNotNull(r.jenkins.getItemByFullName(KEPT), "premise: " + KEPT + " still resolves");
            DomNode monitor = monitor(manage(r));
            for (String name : new String[] {GONE, KEPT}) {
                List<DomElement> controls = reviewControls(monitor, name);
                assertEquals(1, controls.size(), "premise: after the restart " + name + " is listed with one review control: "
                        + excerpt(monitor.asNormalizedText()));
                DomElement control = controls.get(0);
                String caption = control.asNormalizedText() + " " + control.getAttribute("value");
                assertTrue(caption.contains("Mark as reviewed"), "premise: the control of " + name + " reads 'Mark as reviewed': "
                        + control.asXml());
                assertTrue((" " + control.getAttribute("class") + " ").replaceAll("\\s+", " ").contains(" jenkins-button "),
                        "the Mark as reviewed control of " + name + (GONE.equals(name) ? " (no longer resolves)" : "")
                                + " must be core's jenkins-button, not a bare link: " + control.asXml());
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The review controls of the monitor that target {@code name}: an element whose {@code data-url}
     * names {@code markReviewed} with {@code item=<name>}, or the submit buttons of a form posting to
     * {@code markReviewed} for {@code name} (in its action or an {@code item} field).
     */
    private static List<DomElement> reviewControls(DomNode monitor, String name) {
        Pattern item = Pattern.compile("[?&]item=" + Pattern.quote(name) + "(?:$|&)");
        List<DomElement> out = new ArrayList<>();
        for (DomNode n : monitor.querySelectorAll("[data-url]")) {
            String url = URLDecoder.decode(((DomElement) n).getAttribute("data-url"), StandardCharsets.UTF_8);
            if (url.contains("markReviewed") && item.matcher(url).find()) {
                out.add((DomElement) n);
            }
        }
        for (DomNode n : monitor.querySelectorAll("form")) {
            DomElement form = (DomElement) n;
            String action = URLDecoder.decode(form.getAttribute("action"), StandardCharsets.UTF_8);
            if (!action.contains("markReviewed")) {
                continue;
            }
            boolean targets = item.matcher(action).find();
            for (DomNode field : form.querySelectorAll("input[name=item]")) {
                targets |= name.equals(((DomElement) field).getAttribute("value"));
            }
            if (targets) {
                for (DomNode b : form.querySelectorAll("button, input[type=submit]")) {
                    out.add((DomElement) b);
                }
            }
        }
        return out;
    }

    private static DomNode monitor(HtmlPage manage) {
        DomNode monitor = manage.querySelector("div[data-monitor-id=" + MONITOR_ID + "]");
        assertNotNull(monitor, "premise: Manage Jenkins shows the " + MONITOR_ID + " monitor: " + excerpt(manage.asNormalizedText()));
        return monitor;
    }

    private static HtmlPage manage(JenkinsRule r) throws Exception {
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin", "admin");
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage manage = wc.goTo("manage/");
        assertEquals(200, manage.getWebResponse().getStatusCode(), "Manage Jenkins must render");
        return manage;
    }

    /** bob saves an edited {@code config.xml} under his window (an HTTP save), as T-GAP-245 does. */
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
