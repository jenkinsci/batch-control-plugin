package io.jenkins.plugins.batchcontrol;

import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Batch Control configuration page says which changes are recorded and that a strategy change on the
 * Security page is not (issue #31, retired legacy driver fresh/v/va-config.mjs #2, gap X-53). Matrix row
 * T-UI-314 (note 371).
 *
 * <p>Basis: D-52 (e2e-04 FD-02: every save of the Batch Control configuration that changes something writes a
 * CONFIG_CHANGE record, the switches their CONFIG_TOGGLE; installing or reverting a Batch Control strategy
 * writes a STRATEGY_CHANGE) and docs/LIMITATIONS.md 43 (a change of the authorization strategy on Manage
 * Jenkins, Security writes no Batch Control record). No SPEC sentence fixes the page's wording and the
 * owner's wording decision is pending (gap list, X-53), so each statement is read with a sentence-level
 * pattern only: one sentence says that saved changes are recorded; one says that installing or reverting a
 * strategy is; one names the Security page with a negation and the record (or history).
 *
 * <p>Written from docs/DECISIONS.md D-52, docs/LIMITATIONS.md 43 and docs/TEST-MATRIX.md (T-CFG-*, T-UI-289);
 * the retired driver was read only to learn what it looked at. No src/main knowledge.
 */
@WithJenkins
public class ConfigPageRecordingNoteTest {

    private static final String CONFIG = "manage/batch-control-configuration/";
    /** The words for being recorded (wording not pinned). */
    private static final String RECORDED = "(record|history|audit|trail|logged)";
    /** A statement, not a label: at least this many words. */
    private static final int MIN_WORDS = 6;
    private static final Pattern SAVES_RECORDED = Pattern.compile("(?is)\\b(sav(e|es|ed|ing)|changes?)\\b.*" + RECORDED
            + "|" + RECORDED + ".*\\b(sav(e|es|ed|ing)|changes?)\\b");
    private static final Pattern STRATEGY_ACTIONS_RECORDED = Pattern.compile("(?is)(?=.*\\bstrateg(y|ies)\\b)"
            + "(?=.*\\b(install(s|ed|ing)?|revert(s|ed|ing)?)\\b)(?=.*" + RECORDED + ")");
    private static final Pattern SECURITY_PAGE_NOT_RECORDED = Pattern.compile("(?s)(?=.*\\bSecurity\\b)"
            + "(?=.*(?i:\\b(not|no|never|isn't|aren't|without)\\b))(?=.*(?i:" + RECORDED + "))");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.ADMINISTER).everywhere().to("admin"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.save();
    }

    /**
     * T-UI-314 (D-52, LIMITATIONS 43, screen contract; #31 gap X-53): on the administrator's
     * {@code manage/batch-control-configuration/} the main panel (premise: it carries the settings form with a
     * Save control) has a sentence (of at least six words, so a link label does not count) saying that saved changes are recorded, a
     * sentence saying that installing or reverting a (Batch Control) strategy is recorded, and a sentence
     * naming the Security page with a negation and the record or history (its strategy changes are not
     * recorded). Wording not pinned.
     */
    @Test
    public void t_ui_314_configPageSaysWhatIsRecordedAndThatSecurityPageChangesAreNot() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        Page answer = wc.getPage(new WebRequest(new URL(j.getURL(), CONFIG), HttpMethod.GET));
        assertEquals(200, answer.getWebResponse().getStatusCode(), "premise: the administrator opens " + CONFIG);
        HtmlPage page = (HtmlPage) answer;
        HtmlForm settings = null;
        for (HtmlForm form : page.getForms()) {
            if (!form.querySelectorAll("[name='_.pendingTimeoutHours']").isEmpty()) {
                settings = form;
            }
        }
        assertNotNull(settings, "premise: the page carries the settings form");
        String formText = settings.asNormalizedText();
        assertTrue(formText.contains("Save") || !settings.querySelectorAll("[name='Submit'], button[type=submit]").isEmpty(),
                "premise: the settings form has a Save control: " + excerpt(formText));

        DomElement main = page.getElementById("main-panel");
        assertNotNull(main, "premise: the page has a main panel");
        List<String> sentences = sentences(main.asNormalizedText());
        String shown = excerpt(main.asNormalizedText());
        assertTrue(sentences.stream().anyMatch(s -> SAVES_RECORDED.matcher(s).find()), "D-52: the page must say that saved"
                + " configuration changes are recorded: " + shown);
        assertTrue(sentences.stream().anyMatch(s -> STRATEGY_ACTIONS_RECORDED.matcher(s).find()), "D-52: the page must say"
                + " that installing or reverting a Batch Control strategy is recorded: " + shown);
        assertTrue(sentences.stream().anyMatch(s -> SECURITY_PAGE_NOT_RECORDED.matcher(s).find()), "LIMITATIONS 43: the page"
                + " must say that a strategy change on the Security page is not recorded: " + shown);
    }

    /** The statements of the text: split at a full stop or line end, at least {@link #MIN_WORDS} words each. */
    private static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("(?<=\\.)\\s+|\\R")) {
            if (s.trim().split("\\s+").length >= MIN_WORDS) {
                out.add(s.trim());
            }
        }
        return out;
    }
}
