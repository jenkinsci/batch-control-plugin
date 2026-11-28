package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6, "when {@code blockTimer} or {@code blockUpstream} is on for a job, the job page
 * shows a notice naming the switch that blocks it and how to clear it (the job configuration,
 * which is change-controlled), to users who can read the job. The notice is absent when both are
 * off or when run control is off. (#21)". Matrix rows T-06-49, T-06-50 (note 86) and T-06-114
 * (issue #152, note 381).
 *
 * <p>The switch is recognised by its name, either the field name ({@code blockTimer},
 * {@code blockUpstream}) or its label ("Block timer", "Block upstream"), case-insensitively.
 * The viewer is a plain reader (Jenkins/Read + Item/Read), who has no Configure link in the
 * sidebar, so the "how to clear it" half is measured inside the notice itself. T-06-114 also
 * reads the page as a user who may configure the job, to check that the how-to-clear sentence
 * still links the configure page.
 *
 * Written from docs/SPEC.md, issues #21 and #152, and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class TriggerLockNoticeTest {

    private static final Pattern TIMER = Pattern.compile("(?i)block\\s*timer");
    private static final Pattern UPSTREAM = Pattern.compile("(?i)block\\s*upstream");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ).everywhere().to("reader"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.save();
    }

    /**
     * T-06-49 (#21, #152): a reader of a job with blockTimer on sees a notice naming blockTimer
     * (and not blockUpstream) that points at the job configuration; with blockUpstream on, the
     * notice names blockUpstream (and not blockTimer); with both on, both are named.
     *
     * <p>The "on / not off" half is read from the status part of the notice (the text before how
     * to clear it). The how-to-clear sentence may name both field titles (T-06-114).
     */
    @Test
    public void t_06_49_jobPageNoticeNamesTheBlockingSwitch() throws Exception {
        job("notice-timer", true, false);
        job("notice-upstream", false, true);
        job("notice-both", true, true);

        String timer = jobText("notice-timer");
        String timerStatus = statusText(timer);
        assertTrue(TIMER.matcher(timerStatus).find(), "the job page must name blockTimer while it is on: " + excerpt(timer));
        assertFalse(UPSTREAM.matcher(timerStatus).find(), "blockUpstream is off and must not be named as on: " + excerpt(timer));
        assertNoticeExplainsHowToClear("notice-timer", TIMER);

        String upstream = jobText("notice-upstream");
        String upstreamStatus = statusText(upstream);
        assertTrue(UPSTREAM.matcher(upstreamStatus).find(), "the job page must name blockUpstream while it is on: " + excerpt(upstream));
        assertFalse(TIMER.matcher(upstreamStatus).find(), "blockTimer is off and must not be named as on: " + excerpt(upstream));
        assertNoticeExplainsHowToClear("notice-upstream", UPSTREAM);

        String both = jobText("notice-both");
        assertTrue(TIMER.matcher(statusText(both)).find() && UPSTREAM.matcher(statusText(both)).find(),
                "with both switches on the notice must name both: " + excerpt(both));
    }

    /**
     * T-06-50 (#21, negative twin): no notice when both switches are off, and none when run
     * control is off even though the job's switches are on. The premise is read back so that an
     * always-absent notice cannot pass: the same job shows it again once run control is on.
     */
    @Test
    public void t_06_50_noNoticeWhenBothOffOrRunControlOff() throws Exception {
        job("notice-off", false, false);
        job("notice-rc-off", true, true);

        String off = jobText("notice-off");
        assertFalse(TIMER.matcher(off).find() || UPSTREAM.matcher(off).find(),
                "no switch may be named when both are off: " + excerpt(off));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(false);
        cfg.save();
        assertFalse(cfg.isRunControlEnabled(), "fixture: run control off");
        BatchControlJobProperty property = j.jenkins.getItemByFullName("notice-rc-off", FreeStyleProject.class)
                .getProperty(BatchControlJobProperty.class);
        assertTrue(property.isBlockTimer() && property.isBlockUpstream(), "fixture: the switches are still on");
        String rcOff = jobText("notice-rc-off");
        assertFalse(TIMER.matcher(rcOff).find() || UPSTREAM.matcher(rcOff).find(),
                "no notice while run control is off: " + excerpt(rcOff));

        cfg.setRunControlEnabled(true);
        cfg.save();
        String rcOn = jobText("notice-rc-off");
        assertTrue(TIMER.matcher(rcOn).find() && UPSTREAM.matcher(rcOn).find(),
                "control: the same job shows the notice once run control is on: " + excerpt(rcOn));
    }

    /**
     * T-06-114 (#152): the how-to-clear sentence names the two job-configuration field titles
     * ("Block cron (timer) triggers" and "Block upstream triggers") and does not point at a
     * "Batch Control section". A reader is not linked to the configure page; a user who may
     * configure the job is linked to it from that sentence.
     *
     * <p>The fixture is a job with only blockTimer on, so both titles can appear together only
     * in the how-to-clear sentence (the status list names only the switch that is on).
     */
    @Test
    public void t_06_114_noticeNamesTheJobConfigurationFieldTitles() throws Exception {
        job("notice-titles", true, false);

        HtmlPage readerPage = jobPage("notice-titles");
        assertHowToClearNamesFieldTitles(readerPage);
        assertFalse(howToClearLinksToConfigure(readerPage, "notice-titles"),
                "a reader must not be linked to the configure page from the how-to-clear sentence: "
                        + excerpt(readerPage.asNormalizedText()));

        HtmlPage configurerPage = jobPage("notice-titles", "admin");
        assertHowToClearNamesFieldTitles(configurerPage);
        assertTrue(howToClearLinksToConfigure(configurerPage, "notice-titles"),
                "a user who may configure the job must be linked to the configure page from the how-to-clear sentence: "
                        + excerpt(configurerPage.asNormalizedText()));
    }

    // ---------------------------------------------------------------- helpers

    private void job(String name, boolean blockTimer, boolean blockUpstream) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(blockTimer);
        property.setBlockUpstream(blockUpstream);
        setBatchControl(job, property);
        assertEquals(blockTimer, property.isBlockTimer(), "fixture: blockTimer");
        assertEquals(blockUpstream, property.isBlockUpstream(), "fixture: blockUpstream");
    }

    private HtmlPage jobPage(String name) throws Exception {
        return jobPage(name, "reader");
    }

    private HtmlPage jobPage(String name, String user) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        HtmlPage page = (HtmlPage) wc.getPage(new WebRequest(new URL(j.getURL(), "job/" + name + "/"), HttpMethod.GET));
        assertEquals(200, page.getWebResponse().getStatusCode(), user + " must reach job/" + name + "/");
        return page;
    }

    private String jobText(String name) throws Exception {
        return jobPage(name).asNormalizedText();
    }

    /**
     * The status part of the notice: the visible text before how to clear it. If that sentence
     * is missing, the whole page is used, so an always-absent how-to-clear sentence cannot hide
     * an off switch being named as on.
     */
    private static String statusText(String pageText) {
        int cut = pageText.toLowerCase(Locale.ROOT).indexOf("to clear it");
        return cut < 0 ? pageText : pageText.substring(0, cut);
    }

    private static void assertHowToClearNamesFieldTitles(HtmlPage page) {
        String howToClear = howToClearText(page);
        assertTrue(howToClear.contains("Block cron (timer) triggers"),
                "the how-to-clear sentence must name 'Block cron (timer) triggers': " + excerpt(howToClear));
        assertTrue(howToClear.contains("Block upstream triggers"),
                "the how-to-clear sentence must name 'Block upstream triggers': " + excerpt(howToClear));
        assertFalse(howToClear.contains("Batch Control section"),
                "the how-to-clear sentence must not point at a 'Batch Control section': " + excerpt(howToClear));
    }

    private static String howToClearText(HtmlPage page) {
        DomElement element = howToClearElement(page);
        assertTrue(element != null, "the notice must say how to clear the block: " + excerpt(page.asNormalizedText()));
        return element.getTextContent();
    }

    /** The innermost element whose visible text contains "To clear it". */
    private static DomElement howToClearElement(HtmlPage page) {
        List<DomElement> candidates = page.getByXPath("//body//*[not(self::script) and not(self::style)]");
        for (DomElement element : candidates) {
            String own = element.getTextContent();
            if (own == null || !own.toLowerCase(Locale.ROOT).contains("to clear it")) {
                continue;
            }
            if (hasChildContaining(element, "to clear it")) {
                continue;
            }
            return element;
        }
        return null;
    }

    private static boolean howToClearLinksToConfigure(HtmlPage page, String name) throws Exception {
        DomElement element = howToClearElement(page);
        if (element == null) {
            return false;
        }
        String marker = "/job/" + name + "/configure";
        for (DomElement child : element.getElementsByTagName("a")) {
            if (!(child instanceof HtmlAnchor)) {
                continue;
            }
            String href = ((HtmlAnchor) child).getHrefAttribute();
            if (href != null && page.getFullyQualifiedUrl(href).getPath().contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasChildContaining(DomElement element, String phrase) {
        String needle = phrase.toLowerCase(Locale.ROOT);
        for (DomElement child : element.getChildElements()) {
            String text = child.getTextContent();
            if (text != null && text.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** The element naming the switch, or one of its three nearest ancestors, points at the job configuration. */
    private void assertNoticeExplainsHowToClear(String name, Pattern switchName) throws Exception {
        HtmlPage page = jobPage(name);
        List<DomElement> candidates = page.getByXPath("//body//*[not(self::script) and not(self::style)]");
        boolean found = false;
        for (DomElement element : candidates) {
            String own = element.getTextContent();
            if (own == null || !switchName.matcher(own).find() || hasChildNaming(element, switchName)) {
                continue;
            }
            DomNode current = element;
            for (int level = 0; level < 4 && current != null; level++) {
                String text = current.getTextContent().toLowerCase(Locale.ROOT);
                if (text.contains("configur")) {
                    found = true;
                    break;
                }
                current = current.getParentNode();
            }
        }
        assertTrue(found, "the notice naming the switch must say how to clear it (the job configuration): "
                + excerpt(page.asNormalizedText()));
    }

    private static boolean hasChildNaming(DomElement element, Pattern switchName) {
        for (DomElement child : element.getChildElements()) {
            if (switchName.matcher(child.getTextContent()).find()) {
                return true;
            }
        }
        return false;
    }

    private static String excerpt(String text) {
        return text.length() > 1500 ? text.substring(0, 1500) + "..." : text;
    }
}
