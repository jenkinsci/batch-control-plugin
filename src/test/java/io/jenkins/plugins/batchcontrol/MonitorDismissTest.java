package io.jenkins.plugins.batchcontrol;

import hudson.model.AdministrativeMonitor;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import java.net.URL;
import java.util.List;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Batch Control administrative monitor can be dismissed through core's {@code disable} and shown again
 * from core's "Administrative monitors" setting (issue #31, note 370, gap X-15, retired drivers
 * browser/a04.mjs #5, browser/audit/a03.mjs #7, a03b.mjs #2 and a04c.mjs #1). Matrix row T-02-201.
 *
 * <p>Basis: no SPEC sentence; T-02-41 (the {@code batch-control-strategy} monitor warns about Authorize
 * Project while change control is on and no build authenticator is configured) and the usability line of
 * SPEC section 6 (a control is offered only when it works). The monitor uses core's dismiss and re-enable
 * paths, so the row asserts what an administrator sees on {@code manage/}, not how the plugin implements
 * it.
 *
 * <p>Written from docs/SPEC.md items 1 and 2 and section 6, docs/DECISIONS.md D-35d and docs/TEST-MATRIX.md
 * (T-02-41); the retired drivers were read only to learn what each looked at. No src/main knowledge.
 */
@WithJenkins
public class MonitorDismissTest {

    private static final String ID = StrategyFixtures.MONITOR_ID;

    /**
     * T-02-201 (T-02-41's monitor, SPEC 6 usability; #31 gap X-15): with the Batch Control matrix
     * strategy, change control on and no build authenticator, the {@code batch-control-strategy} monitor is
     * active (premise) and admin's {@code manage/} shows its warning (it mentions Authorize Project) with a
     * dismiss control targeting {@code administrativeMonitor/batch-control-strategy/disable}. admin POSTs
     * that URL with the crumb: the answer is below 400, the monitor is disabled and {@code manage/} shows
     * neither the warning nor the control. admin then ticks the monitor in {@code manage/configure}'s
     * Administrative monitors setting (checkbox {@code administrativeMonitor} for the id, unticked after the
     * dismissal) and saves: the monitor is enabled and {@code manage/} shows the warning again.
     */
    @Test
    public void t_02_201_monitorCanBeDismissedAndShownAgain(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
        StrategyFixtures.changeControlOn();
        AdministrativeMonitor monitor = StrategyFixtures.strategyMonitor();
        assertTrue(monitor.isActivated() && monitor.isEnabled(), "premise (T-02-41): the monitor is active and enabled");

        HtmlPage manage = manage(j);
        assertTrue(manage.getWebResponse().getContentAsString().contains("Authorize Project"),
                "guard: manage/ shows the monitor's warning before the dismissal");
        assertEquals(1, dismissControls(manage).size(), "guard: manage/ offers one dismiss control for the monitor");

        int code = ApproverFormFixtures.post(j, "admin", "administrativeMonitor/" + ID + "/disable", List.of()).getStatusCode();
        assertTrue(code < 400, "the administrator's dismissal must succeed, got HTTP " + code);
        assertFalse(monitor.isEnabled(), "the dismissed monitor must be disabled");
        HtmlPage dismissed = manage(j);
        assertFalse(dismissed.getWebResponse().getContentAsString().contains("Authorize Project"),
                "manage/ must no longer show the dismissed monitor's warning");
        assertTrue(dismissControls(dismissed).isEmpty(), "manage/ must no longer offer the dismissed monitor's control");

        JenkinsRule.WebClient wc = j.createWebClient().login("admin");
        HtmlForm form = wc.goTo("manage/configure").getFormByName("config");
        HtmlCheckBoxInput box = null;
        for (DomNode n : form.querySelectorAll("input[type=checkbox][name=administrativeMonitor]")) {
            if (ID.equals(((DomElement) n).getAttribute("json"))) {
                box = (HtmlCheckBoxInput) n;
            }
        }
        assertTrue(box != null, "manage/configure must list the monitor under Administrative monitors");
        assertFalse(box.isChecked(), "the dismissed monitor is unticked in Administrative monitors");
        box.setChecked(true);
        j.submit(form);
        assertTrue(monitor.isEnabled(), "ticking the monitor in Administrative monitors must enable it again");
        assertTrue(manage(j).getWebResponse().getContentAsString().contains("Authorize Project"),
                "manage/ must show the re-enabled monitor's warning again");
    }

    private static HtmlPage manage(JenkinsRule j) throws Exception {
        return j.createWebClient().login("admin").goTo("manage/");
    }

    private static List<URL> dismissControls(HtmlPage page) throws Exception {
        return RequestPageFixtures.controlsEndingWith(page, "administrativeMonitor/" + ID + "/disable");
    }
}
