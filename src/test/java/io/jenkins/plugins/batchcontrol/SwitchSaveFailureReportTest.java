package io.jenkins.plugins.batchcontrol;

import hudson.XmlFile;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a failed save of the global switches is reported (issue #31, note 370): a direct setter logs the
 * failed write at SEVERE (T-01-21, gap X-03, retired drivers browser/b1-21.mjs #4 and
 * browser/audit/b1d.mjs #5), and the form says in plain words that the configuration was not saved
 * (T-01-22, gap X-04, browser/audit/b1d.mjs #2).
 *
 * <p>The fault is the one of T-01-07..12: the global configuration's file
 * ({@code $JENKINS_HOME/<descriptor id>.xml}, ARCHITECTURE section 5) is replaced by a non-empty
 * directory, proven unwritable through core's {@link XmlFile}.
 *
 * <p>Written from docs/SPEC.md item 1 and the usability line of section 6, docs/DECISIONS.md D-42,
 * docs/ARCHITECTURE.md section 5 and docs/TEST-MATRIX.md (T-01-07..12, T-01-17/18); the retired drivers
 * were read only to learn what each looked at. No src/main knowledge.
 */
@WithJenkins
public class SwitchSaveFailureReportTest {

    private static final String LOGGERS = "io.jenkins.plugins.batchcontrol";

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.ADMINISTER).everywhere().to("admin"));
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-01-21 (D-42: a direct setter applies the value and "the failure is logged at SEVERE and does not
     * throw"; SPEC 1: "it applies the value and logs a failed write"; #31 gap X-03): guard first, with the
     * file writable, {@code setRunControlEnabled(true)} then {@code (false)} log nothing at SEVERE from
     * Batch Control's loggers. With the file unwritable, {@code setChangeControlEnabled(true)} and
     * {@code setRunControlEnabled(true)} each do not throw, apply the value (T-01-11) and each logs at
     * least one SEVERE record from a logger under {@code io.jenkins.plugins.batchcontrol}. The message
     * text is not pinned (D-42 fixes the level only).
     */
    @Test
    public void t_01_21_directSetterLogsTheFailedWriteAtSevere() throws Exception {
        try (LogRecorder log = new LogRecorder().record(LOGGERS, Level.WARNING).capture(200)) {
            cfg.setRunControlEnabled(true);
            cfg.setRunControlEnabled(false);
            assertTrue(severe(log).isEmpty(), "guard: with the file writable the setters log nothing at SEVERE: " + severe(log));
        }
        assertFalse(cfg.isRunControlEnabled() || cfg.isChangeControlEnabled(), "premise: both switches off");
        breakConfigFile();

        try (LogRecorder log = new LogRecorder().record(LOGGERS, Level.WARNING).capture(200)) {
            assertDoesNotThrow(() -> BatchControlGlobalConfiguration.get().setChangeControlEnabled(true),
                    "D-42: the direct setter must not throw");
            assertTrue(BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "premise (T-01-11): the value is applied");
            List<String> logged = severe(log);
            assertFalse(logged.isEmpty(), "D-42: the failed write of setChangeControlEnabled must be logged at SEVERE; logged at"
                    + " WARNING or above: " + all(log));
        }
        try (LogRecorder log = new LogRecorder().record(LOGGERS, Level.WARNING).capture(200)) {
            assertDoesNotThrow(() -> BatchControlGlobalConfiguration.get().setRunControlEnabled(true),
                    "D-42: the direct setter must not throw");
            assertTrue(BatchControlGlobalConfiguration.get().isRunControlEnabled(), "premise (D-42): the value is applied");
            List<String> logged = severe(log);
            assertFalse(logged.isEmpty(), "D-42: the failed write of setRunControlEnabled must be logged at SEVERE; logged at"
                    + " WARNING or above: " + all(log));
        }
    }

    /**
     * T-01-22 (SPEC 1: on the form a switch change takes effect only once saved; SPEC 6 usability: a
     * refusal tells the user in plain words why, with no stack trace or "Oops!" page; #31 gap X-04):
     * with the file unwritable, admin ticks run control on the global form and submits: the answer is
     * an error (premise, T-01-09) whose text says the configuration was not saved (wording not pinned
     * beyond "not ... saved" / "failed to save": SPEC fixes no sentence), with no stack trace, "Oops!"
     * page or "Caused by:". Guard: the same submission with the file writable succeeds and its answer
     * says nothing of a failed save. Not asserted: that the answer omits the file path and the I/O
     * error text. The retired driver expected that, but it is the open usability observation U-25 of
     * docs/reports/e2e-03.md, which SPEC does not decide (the answer shows both today).
     */
    @Test
    public void t_01_22_failedFormSaveSaysSoInPlainWords() throws Exception {
        Pattern notSaved = Pattern.compile("(?is)(\\bnot\\b|n't\\b|\\bfailed to\\b|\\bunable to\\b).{0,40}\\bsav(e|ed|ing)\\b");
        Page saved = submitSwitch(true);
        assertTrue(saved.getWebResponse().getStatusCode() < 400, "guard: with the file writable the save succeeds, got HTTP "
                + saved.getWebResponse().getStatusCode());
        assertFalse(notSaved.matcher(UsabilityFixtures.text(saved)).find(), "guard: a successful save says nothing of a failed"
                + " save: " + UsabilityFixtures.excerpt(UsabilityFixtures.text(saved)));
        cfg.setRunControlEnabled(false);
        cfg.save();

        breakConfigFile();
        Page failed = submitSwitch(true);
        int code = failed.getWebResponse().getStatusCode();
        assertTrue(code >= 400, "premise (T-01-09): the failed save answers an error, got HTTP " + code);
        assertFalse(BatchControlGlobalConfiguration.get().isRunControlEnabled(), "premise (T-01-09): nothing applied");
        String text = UsabilityFixtures.text(failed);
        UsabilityFixtures.assertPlainRefusal("the failed configuration save", text, notSaved);
    }

    // ---------------------------------------------------------------- helpers

    private void breakConfigFile() throws IOException {
        File file = new File(j.jenkins.getRootDir(), cfg.getId() + ".xml");
        assertTrue(file.isFile(), "fixture: the saved configuration must live at " + file + " (ARCHITECTURE section 5)");
        Files.delete(file.toPath());
        assertTrue(file.mkdir(), "fixture: could not create the blocking directory " + file);
        Files.writeString(new File(file, "keep").toPath(), "blocks the atomic replace", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> new XmlFile(file).write("probe"), "fixture: a write to " + file + " must now fail");
    }

    /** admin opens the global configure page, sets the run control checkbox and submits; returns the answer. */
    private Page submitSwitch(boolean value) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        HtmlForm form = wc.goTo("configure").getFormByName("config");
        List<HtmlCheckBoxInput> boxes = form.getByXPath(".//input[@type='checkbox' and (@name='_.runControlEnabled'"
                + " or @name='runControlEnabled')]");
        assertEquals(1, boxes.size(), "fixture: the global form must render exactly one runControlEnabled checkbox");
        boxes.get(0).setChecked(value);
        return j.submit(form);
    }

    private static List<String> severe(LogRecorder log) {
        return log.getRecords().stream().filter(r -> r.getLevel().intValue() >= Level.SEVERE.intValue())
                .map(r -> r.getLevel() + " " + r.getLoggerName() + ": " + r.getMessage()).collect(Collectors.toList());
    }

    private static List<String> all(LogRecorder log) {
        return log.getRecords().stream().map(r -> r.getLevel() + " " + r.getLoggerName() + ": " + r.getMessage())
                .collect(Collectors.toList());
    }
}
