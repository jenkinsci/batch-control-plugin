package io.jenkins.plugins.batchcontrol;

import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Batch Control configuration page says which changes are recorded and that a strategy change on the
 * Security page is not (issue #31, retired legacy driver fresh/v/va-config.mjs #2, gap X-53). Matrix row
 * T-UI-314 (note 371).
 *
 * <p>Basis: D-52 (e2e-04 FD-02: every save of the Batch Control configuration that changes something writes a
 * CONFIG_CHANGE record, the switches their CONFIG_TOGGLE; installing or reverting a Batch Control strategy
 * writes a STRATEGY_CHANGE) and docs/LIMITATIONS.md 43 (changing the authorization strategy on Manage Jenkins,
 * Security writes no Batch Control record). No SPEC sentence fixes the page's wording and the owner's wording
 * decision is pending (gap list, X-53), so the wording is not pinned. Each statement is found by its own
 * subject, in D-52's and LIMITATIONS 43's terms, so that words another statement also uses ("change",
 * "history", "strategy") cannot stand in for it:
 * <ul>
 *   <li>saves: a form of "save" within a few words of "settings" or "configuration", in a positive clause with
 *       a record word;</li>
 *   <li>strategy actions: "install" or "revert" within a few words of "strategy", in a positive clause with a
 *       record word;</li>
 *   <li>the Security page: "Security" and "strategy" in a statement with a negation and a record word.</li>
 * </ul>
 * A record word is record(ed), history, audit, trail or logged. A clause is a statement split at a semicolon
 * or at "but", "while" or "whereas", so that a positive and a negative statement joined in one sentence are
 * read apart.
 *
 * <p>Written from docs/DECISIONS.md D-52, docs/LIMITATIONS.md 43 and docs/TEST-MATRIX.md (T-CFG-06, T-UI-289);
 * the retired driver was read only to learn what it looked at. No src/main knowledge.
 */
@WithJenkins
public class ConfigPageRecordingNoteTest {

    private static final String CONFIG = "manage/batch-control-configuration/";
    /** A statement, not a label: at least this many words. */
    private static final int MIN_WORDS = 6;
    private static final Pattern RECORD_WORD = Pattern.compile("(?i)\\b(record\\w*|history|audit\\w*|trail|logged)\\b");
    private static final Pattern NEGATION = Pattern.compile("(?i)\\b(not|no|never|isn't|aren't|without)\\b");
    /** The saves' own subject: a form of "save" within five words of "settings" or "configuration". */
    private static final Pattern SAVE_SUBJECT = Pattern.compile("(?i)\\bsav(e|es|ed|ing)\\b(\\W+\\w+){0,5}?\\W+"
            + "(settings|configuration)\\b|\\b(settings|configuration)\\b(\\W+\\w+){0,5}?\\W+sav(e|es|ed|ing)\\b");
    /** The strategy actions' own subject: "install" or "revert" within six words of "strategy". */
    private static final Pattern STRATEGY_ACTION_SUBJECT = Pattern.compile("(?i)\\b(install|revert)(s|ed|ing)?\\b"
            + "(\\W+\\w+){0,6}?\\W+strateg(y|ies)\\b|\\bstrateg(y|ies)\\b(\\W+\\w+){0,6}?\\W+(install|revert)(s|ed|ing)?\\b");
    /** The Security page statement's own subject: the page's name (as written in Jenkins, capitalised). */
    private static final Pattern SECURITY_SUBJECT = Pattern.compile("\\bSecurity\\b");
    private static final Pattern STRATEGY_WORD = Pattern.compile("(?i)\\bstrateg(y|ies)\\b");

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
     * Save control) states, each found by its own subject (class comment): that saves of the settings are
     * recorded; that installing or reverting a strategy is recorded; and that a strategy change on the Security
     * page is not recorded. Statements have at least six words, so a link label does not count. Guard: on the
     * page's own text with one statement's subject words deleted (for the saves, for example, the page's
     * "saved ... settings" phrase, leaving any "change history" elsewhere), that statement is no longer found
     * and the other two still are, for each of the three.
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
        String text = main.asNormalizedText();
        String shown = excerpt(text);
        assertTrue(saysSavesRecorded(text), "D-52: the page must say that saves of the settings are recorded: " + shown);
        assertTrue(saysStrategyActionsRecorded(text), "D-52: the page must say that installing or reverting a strategy is"
                + " recorded: " + shown);
        assertTrue(saysSecurityPageNotRecorded(text), "LIMITATIONS 43: the page must say that a strategy change on the"
                + " Security page is not recorded: " + shown);

        Map<String, Pattern> subjects = new LinkedHashMap<>();
        subjects.put("saves", SAVE_SUBJECT);
        subjects.put("strategy actions", STRATEGY_ACTION_SUBJECT);
        subjects.put("Security page", SECURITY_SUBJECT);
        for (Map.Entry<String, Pattern> removed : subjects.entrySet()) {
            String without = removed.getValue().matcher(text).replaceAll(" ");
            Map<String, Boolean> found = found(without);
            for (String kind : subjects.keySet()) {
                if (kind.equals(removed.getKey())) {
                    assertFalse(found.get(kind), "guard: with its subject words deleted, the " + kind + " statement must no"
                            + " longer be found: " + excerpt(without));
                } else {
                    assertTrue(found.get(kind), "guard: with the " + removed.getKey() + " subject words deleted, the " + kind
                            + " statement must still be found on its own subject: " + excerpt(without));
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Boolean> found(String text) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        out.put("saves", saysSavesRecorded(text));
        out.put("strategy actions", saysStrategyActionsRecorded(text));
        out.put("Security page", saysSecurityPageNotRecorded(text));
        return out;
    }

    private static boolean saysSavesRecorded(String text) {
        return clauses(text).stream().anyMatch(c -> SAVE_SUBJECT.matcher(c).find() && positiveRecord(c));
    }

    private static boolean saysStrategyActionsRecorded(String text) {
        return clauses(text).stream().anyMatch(c -> STRATEGY_ACTION_SUBJECT.matcher(c).find() && positiveRecord(c));
    }

    private static boolean saysSecurityPageNotRecorded(String text) {
        return statements(text).stream().anyMatch(s -> SECURITY_SUBJECT.matcher(s).find() && STRATEGY_WORD.matcher(s).find()
                && NEGATION.matcher(s).find() && RECORD_WORD.matcher(s).find());
    }

    private static boolean positiveRecord(String clause) {
        return RECORD_WORD.matcher(clause).find() && !NEGATION.matcher(clause).find();
    }

    /** The statements of the text: split at a full stop or line end, at least {@link #MIN_WORDS} words each. */
    private static List<String> statements(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("(?<=\\.)\\s+|\\R")) {
            if (s.trim().split("\\s+").length >= MIN_WORDS) {
                out.add(s.trim());
            }
        }
        return out;
    }

    /** The clauses of the statements: split at a semicolon or before "but", "while" or "whereas". */
    private static List<String> clauses(String text) {
        List<String> out = new ArrayList<>();
        for (String statement : statements(text)) {
            for (String clause : statement.split("(?i);|,?\\s+(?=(but|while|whereas)\\b)")) {
                if (!clause.isBlank()) {
                    out.add(clause.trim());
                }
            }
        }
        return out;
    }
}
