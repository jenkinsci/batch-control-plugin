package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import hudson.security.HudsonPrivateSecurityRealm;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextArea;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Screen-contract rows from the hosting review's UI polish (2026-10-02) for the Batch Control
 * configuration under Manage Jenkins ({@code manage/batch-control-configuration/}): the page
 * offers Save and no Apply button (T-UI-27), and a refused save (HTTP 400) shows the
 * "Nothing was saved." alert with the typed values, in the same settings-subpage layout as the
 * page itself (T-UI-28). Note 186. The refusal banner shows an apostrophe once escaped (T-CFG-13,
 * e2e-04 FD-14) and a refused number keeps the typed value with its message (T-CFG-14, e2e-04
 * FD-15), note 366.
 *
 * <p>Same realm and strategy as {@link ConfigAuditTest} (Jenkins' own user database, so an unknown
 * approver id is really unknown): {@code manager} holds Overall/Read, Job/Read and
 * BatchControl/Manage; {@code admin} Administer.
 *
 * Written from docs/SPEC.md, docs/DECISIONS.md D-53 and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 *
 * <p><b>Five groups</b> (rows T-UI-288, T-UI-289, note 362 point 9; Wave D D3, owner decision of
 * 2026-10-14, grouping of 2026-10-15). Inside the Batch Control section of the global configuration
 * the settings are grouped under five headings, in this order: (1) switches: run control, change
 * control; (2) approval: approvers, administrator self-approval, pending request timeout,
 * approved-but-not-run timeout; (3) permission windows: grant duration options, maximum grant
 * duration, notify before expiry; (4) incidents and retention: results that open an incident,
 * retention period; (5) notifications and strategy: e-mail notifications, then the Batch Control
 * strategy (the administrator's Advanced control with the revert; for the manager the sentence "Only
 * a Jenkins administrator can change the authorization strategy."). Field names, stored values and
 * the save do not change (T-UI-289). "The Batch Control section" is the outermost {@code section}
 * around {@code _.runControlEnabled}. A group heading is an element inside it that is an
 * {@code h2}..{@code h6}, a {@code legend}, an element with {@code role="heading"} or a
 * {@code .jenkins-section__title} (a nested core {@code f:section}), except the section's own title
 * and a heading inside another heading; a field belongs to the last heading before it in document
 * order. The heading texts (whole text, white space collapsed) are "Switches", "Approval",
 * "Permission windows", "Incidents and retention" and "Notifications and strategy" (owner decision of
 * 2026-10-15). Pages:
 * {@code manage/configure} (administrator; the manager is refused it) and
 * {@code manage/batch-control-configuration/} (administrator and manager). Written from the owner
 * decision and pages rendered at a791e2a1; no src/main knowledge.
 */
@WithJenkins
public class ConfigPagePolishTest {

    private static final String CONFIG = "manage/batch-control-configuration/";
    /** The five groups' fields, in order (names unchanged). */
    private static final List<List<String>> GROUPS = List.of(
            List.of("_.runControlEnabled", "_.changeControlEnabled"),
            List.of("_.approversText", "_.allowAdminSelfApproval", "_.pendingTimeoutHours", "_.approvedRunTimeoutMinutes"),
            List.of("_.grantDurationOptions", "_.maxGrantMinutes", "_.notifyBeforeExpiryMinutes"),
            List.of("_.incidentResults", "_.retentionMonths"),
            List.of("_.emailNotifications"));
    /** The five group headings, in order (owner decision of 2026-10-15). */
    private static final List<String> HEADINGS =
            List.of("Switches", "Approval", "Permission windows", "Incidents and retention", "Notifications and strategy");
    private static final String STRATEGY_LABEL = "Batch Control strategy";
    private static final String ADMINISTRATOR_ONLY = "Only a Jenkins administrator can change the authorization strategy.";
    private static final String REVERT = "administrativeMonitor/batch-control-strategy/revert";

    private static final String NOTHING_SAVED = "Nothing was saved.";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        for (String id : new String[] {"admin", "manager", "a1"}) {
            realm.createAccount(id, id);
        }
        j.jenkins.setSecurityRealm(realm);
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        strategy.add(Jenkins.READ, PermissionEntry.user("manager"));
        strategy.add(Item.READ, PermissionEntry.user("manager"));
        strategy.add(BatchControlPermissions.MANAGE, PermissionEntry.user("manager"));
        j.jenkins.setAuthorizationStrategy(strategy);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-UI-27: for the administrator and for the manager the configuration page carries the
     * settings form with a Save button (premise) and no Apply button anywhere on the page.
     */
    @Test
    public void t_ui_27_configurationPageHasSaveAndNoApply() throws Exception {
        for (String userId : new String[] {"admin", "manager"}) {
            HtmlPage page = configPage(userId);
            HtmlForm form = settingsForm(page);
            assertFalse(buttonsCaptioned(form, "save").isEmpty(), "premise: " + userId + " is offered a Save button: "
                    + UsabilityFixtures.excerpt(page.asNormalizedText()));
            assertTrue(buttonsCaptioned(page.getDocumentElement(), "apply").isEmpty(), userId + ": the configuration page"
                    + " must not offer an Apply button, found " + buttonsCaptioned(page.getDocumentElement(), "apply"));
        }
    }

    /**
     * T-UI-28 (D-53): the manager saves the unknown approver id {@code no-such-user} with a timeout of
     * 24. The answer is HTTP 400 and shows the alert "Nothing was saved.", the settings form again
     * with both typed values kept, in the page's settings-subpage layout (same app bar and side panel
     * presence as the page itself, breadcrumb under Manage Jenkins). Nothing is stored.
     */
    @Test
    public void t_ui_28_refusedSaveShowsNothingSavedWithTypedValuesInTheSubpageLayout() throws Exception {
        HtmlPage original = configPage("manager");
        HtmlForm form = settingsForm(original);
        setApprovers(form, "no-such-user");
        UsabilityFixtures.setField(form, "pendingTimeoutHours", "24");
        Page answer = j.submit(form);

        assertEquals(400, answer.getWebResponse().getStatusCode(), "a refused save must answer 400");
        assertEquals(Arrays.asList("a1"), BatchControlGlobalConfiguration.get().getApprovers(), "nothing may be saved");
        assertEquals(72, BatchControlGlobalConfiguration.get().getPendingTimeoutHours(), "nothing may be saved");
        assertTrue(answer instanceof HtmlPage, "the refusal must be an HTML page");
        HtmlPage refused = (HtmlPage) answer;
        String text = refused.asNormalizedText();

        List<DomElement> alerts = refused.getByXPath("//*[contains(concat(' ', normalize-space(@class), ' '), ' jenkins-alert ')"
                + " or @role='alert']");
        assertTrue(alerts.stream().anyMatch(a -> a.asNormalizedText().contains(NOTHING_SAVED)),
                "the refusal must show the alert \"" + NOTHING_SAVED + "\": " + UsabilityFixtures.excerpt(text));
        assertTrue(text.contains("no-such-user"), "the refusal must name the unknown id: " + UsabilityFixtures.excerpt(text));

        HtmlForm again = settingsForm(refused);
        assertNotNull(again);
        assertTrue(UsabilityFixtures.pageKeepsValue(refused, "no-such-user"), "the typed approver id must be kept");
        assertTrue(UsabilityFixtures.pageKeepsValue(refused, "24"), "the typed timeout must be kept");

        assertEquals(hasClass(original, "jenkins-app-bar"), hasClass(refused, "jenkins-app-bar"),
                "the refusal must use the page's settings-subpage layout (app bar)");
        assertEquals(original.getElementById("side-panel") != null, refused.getElementById("side-panel") != null,
                "the refusal must use the page's settings-subpage layout (side panel)");
        assertTrue(breadcrumbText(refused).contains("Manage Jenkins"), "the refusal must sit under Manage Jenkins in the"
                + " breadcrumb like the page itself: " + breadcrumbText(refused));
        assertTrue(breadcrumbText(original).contains("Manage Jenkins"), "premise: the page sits under Manage Jenkins: "
                + breadcrumbText(original));
    }

    /**
     * T-UI-288: on {@code manage/configure} (administrator) and on
     * {@code manage/batch-control-configuration/} (administrator and manager) the Batch Control section
     * holds exactly five group headings reading Switches, Approval, Permission windows, Incidents and
     * retention, Notifications and strategy; the fields follow in the order of the five
     * groups and each sits under its group's heading; the Batch Control strategy entry comes after
     * the e-mail option under the fifth heading, with the administrator's Advanced button and revert
     * there, and the manager's "Only a Jenkins administrator ..." sentence there. Premise: the manager is refused
     * {@code manage/configure}.
     */
    @Test
    public void t_ui_288_settingsAreGroupedUnderFiveHeadings() throws Exception {
        installStrategyEntry();
        assertTrue(get("manager", "manage/configure", false).getWebResponse().getStatusCode() >= 400,
                "premise: a BatchControl/Manage holder may not open manage/configure, so it is checked for the administrator");
        Map<String, String> views = new LinkedHashMap<>();
        views.put("admin manage/configure", "manage/configure");
        views.put("admin manage/batch-control-configuration/", "manage/batch-control-configuration/");
        views.put("manager manage/batch-control-configuration/", "manage/batch-control-configuration/");
        for (Map.Entry<String, String> view : views.entrySet()) {
            String where = view.getKey();
            boolean admin = where.startsWith("admin ");
            HtmlPage page = groupsPage(admin ? "admin" : "manager", view.getValue());
            DomElement section = batchControlSection(page, where);
            List<DomElement> headings = headings(section);

            List<String> expectedFields = new ArrayList<>();
            GROUPS.forEach(expectedFields::addAll);
            List<String> fields = new ArrayList<>();
            for (DomNode n : section.querySelectorAll("input[name], select[name], textarea[name]")) {
                DomElement f = (DomElement) n;
                if (f.getAttribute("name").startsWith("_.") && !"hidden".equals(f.getAttribute("type"))) {
                    fields.add(f.getAttribute("name"));
                }
            }
            assertEquals(new java.util.TreeSet<>(expectedFields), new java.util.TreeSet<>(fields), where
                    + ": premise: the field names are unchanged");

            List<String> texts = new ArrayList<>();
            for (DomElement h : headings) {
                texts.add(collapse(h.getTextContent()));
            }
            assertEquals(5, headings.size(), where + ": the Batch Control settings are grouped under exactly five headings,"
                    + " found " + texts);
            assertEquals(HEADINGS, texts, where + ": the five headings read, in order, " + HEADINGS
                    + " (owner decision of 2026-10-15)");
            assertEquals(expectedFields, fields, where + ": the fields follow in the order of the five groups");
            for (int g = 0; g < GROUPS.size(); g++) {
                for (String name : GROUPS.get(g)) {
                    DomElement field = (DomElement) section.querySelector("[name='" + name + "']");
                    assertEquals(g, groupOf(field, headings), where + ": " + name + " sits under heading " + (g + 1)
                            + " of " + texts);
                }
            }

            DomElement strategy = elementIn(section, STRATEGY_LABEL);
            assertNotNull(strategy, where + ": premise: the Batch Control strategy entry is rendered");
            assertEquals(4, groupOf(strategy, headings), where + ": the Batch Control strategy sits under the fifth heading");
            assertTrue(follows(strategy, (DomElement) section.querySelector("[name='_.emailNotifications']")), where
                    + ": the Batch Control strategy comes after the e-mail option");
            if (admin) {
                DomElement advanced = (DomElement) section.querySelector("button.advanced-button");
                assertNotNull(advanced, where + ": premise (T-UI-234): the administrator's Advanced control is there");
                assertEquals(4, groupOf(advanced, headings), where + ": the Advanced control sits under the fifth heading");
                DomElement revert = null;
                for (DomNode n : section.querySelectorAll("[data-url], [formaction], a[href]")) {
                    DomElement e = (DomElement) n;
                    if ((e.getAttribute("data-url") + e.getAttribute("formaction") + e.getAttribute("href")).contains(REVERT)) {
                        revert = e;
                    }
                }
                assertNotNull(revert, where + ": premise (T-UI-234): the revert is in the section");
                assertEquals(4, groupOf(revert, headings), where + ": the revert sits under the fifth heading");
            } else {
                DomElement sentence = elementIn(section, ADMINISTRATOR_ONLY);
                assertNotNull(sentence, where + ": premise (T-UI-239): the manager reads '" + ADMINISTRATOR_ONLY + "'");
                assertEquals(4, groupOf(sentence, headings), where + ": the manager's sentence sits under the fifth heading");
            }
        }
    }

    /**
     * T-UI-289 (guard, the grouping changes no field name, stored value or save): with every setting
     * at a value other than its default, the administrator saves {@code manage/configure} and
     * {@code manage/batch-control-configuration/} unchanged, and the manager saves the latter unchanged; after
     * each save every value reads back as it was.
     */
    @Test
    public void t_ui_289_aSaveRoundTripsEveryValue() throws Exception {
        installStrategyEntry();
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "manager"));
        cfg.setAllowAdminSelfApproval(false);
        cfg.setPendingTimeoutHours(24);
        cfg.setApprovedRunTimeoutMinutes(30);
        cfg.setGrantDurationOptions(Arrays.asList(10, 20, 45));
        cfg.setMaxGrantMinutes(120);
        cfg.setNotifyBeforeExpiryMinutes(5);
        cfg.setIncidentResults(Arrays.asList("FAILURE", "UNSTABLE", "ABORTED"));
        cfg.setRetentionMonths(12);
        cfg.setEmailNotifications(true);
        cfg.save();
        Map<String, Object> before = values();
        assertEquals(Boolean.TRUE, before.get("emailNotifications"), "fixture: the e-mail option is stored");

        for (String[] save : new String[][] {{"admin", "manage/configure"}, {"admin", "manage/batch-control-configuration/"},
                {"manager", "manage/batch-control-configuration/"}}) {
            String where = save[0] + " saves " + save[1] + " unchanged";
            HtmlPage page = (HtmlPage) get(save[0], save[1], true);
            assertEquals(200, page.getWebResponse().getStatusCode(), where + ": premise: the page opens");
            HtmlForm form = null;
            for (HtmlForm f : page.getForms()) {
                if (!f.querySelectorAll("[name='_.pendingTimeoutHours']").isEmpty()) {
                    form = f;
                }
            }
            assertNotNull(form, where + ": premise: the settings form");
            Page answer = j.submit(form);
            assertTrue(answer.getWebResponse().getStatusCode() < 400, where + ": the save goes through, got HTTP "
                    + answer.getWebResponse().getStatusCode());
            assertEquals(before, values(), where + ": every value reads back as it was");
        }
    }

    /**
     * T-CFG-13 (e2e-04 FD-14, note 366): the refusal banner of a refused save shows an apostrophe
     * as {@code '}, not as the literal entity {@code &#039;} (no double escaping), and stays escaped
     * against markup. The manager saves {@code manage/batch-control-configuration/} with the unknown
     * approver ids {@code no-such-user} (the report's case), {@code o'brien-ghost} (an apostrophe in
     * the input) and {@code <b>bold-ghost</b>} (markup in the input), one save each. Each answer is
     * the 400 refusal; its "Nothing was saved." alert names the id between plain apostrophes
     * ({@code 'no-such-user'}, as the fixed banner reads in e2e-04's verification), contains no
     * {@code &#039;}, {@code &#39;}, {@code &amp;} or {@code &lt;} as visible text, and contains no
     * {@code b} element. The administrator's save of {@code ghost-user} on {@code manage/configure}
     * (core's error page) reads {@code 'ghost-user'} the same way. Nothing is stored.
     */
    @Test
    public void t_cfg_13_refusalBannerShowsApostrophesOnceEscaped() throws Exception {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("no-such-user", "'no-such-user'");
        inputs.put("o'brien-ghost", "o'brien-ghost");
        inputs.put("<b>bold-ghost</b>", "bold-ghost");
        for (Map.Entry<String, String> input : inputs.entrySet()) {
            String typed = input.getKey();
            HtmlForm form = settingsForm(configPage("manager"));
            setApprovers(form, typed);
            Page answer = j.submit(form);
            assertEquals(400, answer.getWebResponse().getStatusCode(), typed + ": premise: the save is refused");
            assertEquals(Arrays.asList("a1"), BatchControlGlobalConfiguration.get().getApprovers(), typed + ": nothing may be saved");
            HtmlPage refused = (HtmlPage) answer;
            List<DomElement> banners = new ArrayList<>();
            for (Object o : refused.getByXPath("//*[contains(concat(' ', normalize-space(@class), ' '), ' jenkins-alert ')"
                    + " or @role='alert']")) {
                DomElement e = (DomElement) o;
                if (e.asNormalizedText().contains(NOTHING_SAVED)) {
                    banners.add(e);
                }
            }
            assertFalse(banners.isEmpty(), typed + ": premise: the \"" + NOTHING_SAVED + "\" banner is shown: "
                    + UsabilityFixtures.excerpt(refused.asNormalizedText()));
            for (DomElement banner : banners) {
                String text = banner.asNormalizedText();
                assertTrue(text.contains(input.getValue()), typed + ": the banner must show " + input.getValue()
                        + " with plain apostrophes, read: " + text);
                for (String entity : new String[] {"&#039;", "&#39;", "&apos;", "&amp;", "&lt;", "&gt;", "&quot;"}) {
                    assertFalse(text.contains(entity), typed + ": the banner must not show the entity " + entity
                            + " (escaped twice), read: " + text);
                }
                assertTrue(banner.getElementsByTagName("b").isEmpty(), typed + ": markup in the input must not render as"
                        + " an element in the banner: " + banner.asXml());
            }
        }

        HtmlPage system = (HtmlPage) get("admin", "manage/configure", true);
        HtmlForm systemForm = null;
        for (HtmlForm f : system.getForms()) {
            if (!f.querySelectorAll("[name='_.pendingTimeoutHours']").isEmpty()) {
                systemForm = f;
            }
        }
        assertNotNull(systemForm, "premise: manage/configure carries the Batch Control settings");
        setApprovers(systemForm, "ghost-user");
        Page systemAnswer = j.submit(systemForm);
        assertTrue(systemAnswer.getWebResponse().getStatusCode() >= 400, "premise: the System page save is refused, got HTTP "
                + systemAnswer.getWebResponse().getStatusCode());
        String systemText = UsabilityFixtures.text(systemAnswer);
        assertTrue(systemText.contains("'ghost-user'"), "the System page refusal must read 'ghost-user' with plain apostrophes: "
                + UsabilityFixtures.excerpt(systemText));
        assertFalse(systemText.contains("&#039;") || systemText.contains("&#39;"), "the System page refusal must not show"
                + " &#039;: " + UsabilityFixtures.excerpt(systemText));
        assertEquals(Arrays.asList("a1"), BatchControlGlobalConfiguration.get().getApprovers(), "nothing may be saved");
    }

    /**
     * T-CFG-14 (e2e-04 FD-15, D-53 "the form keeps the input", note 366): the manager saves Retention
     * period {@code 0}, Pending request timeout {@code 0} and Maximum grant duration {@code 123}. The
     * answer is the 400 refusal; the form comes back with {@code 0} in the retention field and in the
     * pending timeout field (not the stored values), with the message {@code '0' is not allowed}
     * (e2e-04's verified wording "'0' is not allowed; enter a whole number of 1 or more.") inside each
     * refused field's form item, and with {@code 123} kept in the maximum grant duration field. Nothing
     * is stored.
     */
    @Test
    public void t_cfg_14_refusedNumberKeepsTheTypedValue() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        int retention = cfg.getRetentionMonths();
        int pending = cfg.getPendingTimeoutHours();
        int maxGrant = cfg.getMaxGrantMinutes();
        assertFalse(retention == 0 || pending == 0 || maxGrant == 123, "premise: the stored values differ from the typed ones");

        HtmlForm form = settingsForm(configPage("manager"));
        UsabilityFixtures.setField(form, "retentionMonths", "0");
        UsabilityFixtures.setField(form, "pendingTimeoutHours", "0");
        UsabilityFixtures.setField(form, "maxGrantMinutes", "123");
        Page answer = j.submit(form);

        assertEquals(400, answer.getWebResponse().getStatusCode(), "premise: the save is refused");
        assertEquals(retention, cfg.getRetentionMonths(), "nothing may be saved (retention)");
        assertEquals(pending, cfg.getPendingTimeoutHours(), "nothing may be saved (pending timeout)");
        assertEquals(maxGrant, cfg.getMaxGrantMinutes(), "nothing may be saved (maximum grant duration)");
        HtmlPage refused = (HtmlPage) answer;
        settingsForm(refused);
        for (String field : new String[] {"retentionMonths", "pendingTimeoutHours"}) {
            HtmlInput input = fieldNamed(refused, "_." + field);
            assertEquals("0", input.getValue(), field + ": the refused value must stay in its field, not be reset to the stored"
                    + " value");
            DomElement item = formItem(input);
            assertTrue(item.querySelectorAll("[name='_.maxGrantMinutes']").isEmpty(), field
                    + ": premise: the form item holds this field only, not the whole form");
            String text = item.asNormalizedText();
            assertTrue(text.contains("'0' is not allowed"), field + ": the refusal message must be shown next to the field: "
                    + UsabilityFixtures.excerpt(text));
        }
        assertEquals("123", fieldNamed(refused, "_.maxGrantMinutes").getValue(), "the valid typed value must be kept");
    }

    // ---------------------------------------------------------------- helpers

    private static HtmlInput fieldNamed(HtmlPage page, String name) {
        DomNode node = page.querySelector("input[name='" + name + "']");
        assertTrue(node instanceof HtmlInput, "the page must carry the field " + name);
        return (HtmlInput) node;
    }

    /** The field's core form item ({@code .jenkins-form-item}), or its grandparent when there is none. */
    private static DomElement formItem(DomElement field) {
        for (DomNode n = field.getParentNode(); n instanceof DomElement e; n = n.getParentNode()) {
            if (classes(e).contains("jenkins-form-item")) {
                return e;
            }
        }
        return (DomElement) field.getParentNode().getParentNode();
    }

    /**
     * The D3 rows' fixture on top of this class's: change control on and a build authenticator, so
     * that the "Batch Control strategy" entry (the Batch Control matrix strategy is installed by
     * {@link #setUp}) is rendered with the administrator's revert.
     */
    private static void installStrategyEntry() {
        StrategyFixtures.changeControlOn();
        StrategyFixtures.configureBuildAuthenticator();
    }

    private HtmlPage groupsPage(String user, String path) throws Exception {
        Page answer = get(user, path, false);
        assertEquals(200, answer.getWebResponse().getStatusCode(), user + " GET " + path);
        return (HtmlPage) answer;
    }

    private Page get(String user, String path, boolean javaScript) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        wc.getOptions().setJavaScriptEnabled(javaScript);
        return wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET));
    }

    private static Map<String, Object> values() {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runControlEnabled", cfg.isRunControlEnabled());
        out.put("changeControlEnabled", cfg.isChangeControlEnabled());
        out.put("approvers", new ArrayList<>(cfg.getApprovers()));
        out.put("allowAdminSelfApproval", cfg.isAllowAdminSelfApproval());
        out.put("pendingTimeoutHours", cfg.getPendingTimeoutHours());
        out.put("approvedRunTimeoutMinutes", cfg.getApprovedRunTimeoutMinutes());
        out.put("grantDurationOptions", new ArrayList<>(cfg.getGrantDurationOptions()));
        out.put("maxGrantMinutes", cfg.getMaxGrantMinutes());
        out.put("notifyBeforeExpiryMinutes", cfg.getNotifyBeforeExpiryMinutes());
        out.put("incidentResults", new ArrayList<>(cfg.getIncidentResults()));
        out.put("retentionMonths", cfg.getRetentionMonths());
        out.put("emailNotifications", cfg.isEmailNotifications());
        return out;
    }

    /** The outermost {@code section} around {@code _.runControlEnabled}. */
    private static DomElement batchControlSection(HtmlPage page, String where) {
        DomNode field = page.querySelector("[name='_.runControlEnabled']");
        assertNotNull(field, where + ": premise: the Batch Control settings are on the page");
        DomElement section = null;
        for (DomNode n = field.getParentNode(); n instanceof DomElement e; n = n.getParentNode()) {
            if ("section".equals(e.getTagName())) {
                section = e;
            }
        }
        assertNotNull(section, where + ": premise: the settings are inside a section");
        return section;
    }

    /** The group headings of the section, in document order (see the class comment). */
    private static List<DomElement> headings(DomElement section) {
        DomElement ownTitle = null;
        for (DomElement child : section.getChildElements()) {
            if (classes(child).contains("jenkins-section__title")) {
                ownTitle = child;
                break;
            }
        }
        List<DomElement> out = new ArrayList<>();
        for (DomNode n : section.querySelectorAll("h2, h3, h4, h5, h6, legend, [role='heading'], .jenkins-section__title")) {
            DomElement e = (DomElement) n;
            if (e == ownTitle || out.stream().anyMatch(h -> h.isAncestorOf(e))) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    /** The index of the last heading before {@code e} in document order, -1 if none. */
    private static int groupOf(DomElement e, List<DomElement> headings) {
        int group = -1;
        for (int i = 0; i < headings.size(); i++) {
            if (follows(e, headings.get(i))) {
                group = i;
            }
        }
        return group;
    }

    /** {@code candidate} comes after {@code reference} in document order and is not inside it. */
    private static boolean follows(DomNode candidate, DomNode reference) {
        int position = reference.compareDocumentPosition(candidate);
        return (position & DomNode.DOCUMENT_POSITION_FOLLOWING) != 0 && (position & DomNode.DOCUMENT_POSITION_CONTAINED_BY) == 0;
    }

    /** The innermost element of {@code root} whose text holds {@code text}, or null. */
    private static DomElement elementIn(DomElement root, String text) {
        DomElement found = null;
        for (DomElement e : root.getDomElementDescendants()) {
            if (!"script".equals(e.getTagName()) && collapse(e.getTextContent()).contains(text)) {
                found = e;
            }
        }
        return found;
    }

    private static java.util.Set<String> classes(DomElement e) {
        return new java.util.HashSet<>(Arrays.asList(e.getAttribute("class").trim().split("\\s+")));
    }

    private static String collapse(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }


    private HtmlPage configPage(String userId) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        Page page = wc.getPage(new WebRequest(new URL(j.getURL(), CONFIG), HttpMethod.GET));
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: " + userId + " must open " + CONFIG);
        return (HtmlPage) page;
    }

    private static HtmlForm settingsForm(HtmlPage page) {
        for (HtmlForm form : page.getForms()) {
            if (UsabilityFixtures.hasField(form, "pendingTimeoutHours")) {
                return form;
            }
        }
        throw new AssertionError("the page must carry the settings form; forms: " + UsabilityFixtures.formActions(page));
    }

    /** Buttons and submit/button inputs whose caption, value or name is {@code word} (case-insensitive). */
    private static List<String> buttonsCaptioned(DomElement root, String word) {
        List<String> out = new ArrayList<>();
        for (HtmlElement e : root.getHtmlElementDescendants()) {
            String tag = e.getTagName();
            boolean button = "button".equals(tag) || ("input".equals(tag)
                    && ("submit".equalsIgnoreCase(e.getAttribute("type")) || "button".equalsIgnoreCase(e.getAttribute("type"))));
            if (!button) {
                continue;
            }
            for (String caption : new String[] {e.asNormalizedText(), e.getAttribute("value"), e.getAttribute("name")}) {
                if (caption != null && caption.trim().toLowerCase(Locale.ROOT).equals(word)) {
                    out.add(e.asXml().trim());
                    break;
                }
            }
        }
        return out;
    }

    private static boolean hasClass(HtmlPage page, String cssClass) {
        return !page.getByXPath("//*[contains(concat(' ', normalize-space(@class), ' '), ' " + cssClass + " ')]").isEmpty();
    }

    private static String breadcrumbText(HtmlPage page) {
        StringBuilder sb = new StringBuilder();
        for (Object o : page.getByXPath("//*[@id='breadcrumbs' or contains(@class, 'jenkins-breadcrumbs')]")) {
            sb.append(((DomElement) o).asNormalizedText()).append(' ');
        }
        return sb.toString();
    }

    private static void setApprovers(HtmlForm form, String value) {
        for (String tag : new String[] {"textarea", "input"}) {
            for (DomElement element : form.getElementsByTagName(tag)) {
                String name = element.getAttribute("name");
                if (name == null || !name.toLowerCase(Locale.ROOT).contains("approver")) {
                    continue;
                }
                if (element instanceof HtmlTextArea) {
                    ((HtmlTextArea) element).setText(value);
                    return;
                }
                if (element instanceof HtmlInput && !"checkbox".equals(element.getAttribute("type"))
                        && !"hidden".equals(element.getAttribute("type"))) {
                    ((HtmlInput) element).setValue(value);
                    return;
                }
            }
        }
        throw new AssertionError("fixture: the settings form must carry an approver text field");
    }
}
