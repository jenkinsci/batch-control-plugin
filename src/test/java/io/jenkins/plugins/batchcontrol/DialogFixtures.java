package io.jenkins.plugins.batchcontrol;

import hudson.model.Job;
import java.net.URL;
import java.util.List;
import java.util.Set;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextArea;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request dialog of the Request Run action (D-66, D-70), driven the way a browser drives it,
 * for the round-6 coverage rows (matrix note 269).
 *
 * <p>Both job UIs open the dialog with core's dialog "wizard": the entry names the fragment URL
 * {@code job/<name>/batch-control/dialog}; core fetches it, inserts the fragment's form into the
 * current page, runs the fragment's scripts, and on submit builds core's structured {@code json}
 * field ({@code buildFormTree}) and posts the form's own fields as {@code multipart/form-data} when
 * the form has a file control, with the crumb. HtmlUnit cannot open core's dialog, so this class
 * does the same steps on the job page: it fetches the fragment with the user's session, inserts
 * the fragment's markup into the job page, runs the fragment's scripts with core's
 * {@code evalInnerHtmlScripts} and applies core's behaviours to it (which adds the crumb and the
 * {@code buildFormTree} submit handler, as for any form Jenkins renders on demand). The test then
 * fills the fragment's own controls and submits that form; nothing of the fragment is rewritten.
 *
 * <p>Written from docs/SPEC.md items 5 and 8 (D-66), docs/DECISIONS.md D-66/D-70 and core's public
 * page scripts only (no src/main knowledge).
 */
final class DialogFixtures {

    private static final String HOST = "bc-dialog-host";
    private static final String SOURCE = "bc-dialog-source";

    private DialogFixtures() {
        // utility class
    }

    /** {@code job/<name>/batch-control/dialog} (the fragment D-66 / T-UI-104 name). */
    static String fragmentPath(Job<?, ?> job) {
        return job.getUrl() + "batch-control/dialog";
    }

    /** The fragment as {@code userId} receives it (status 200 asserted), parsed on its own, without scripts. */
    static HtmlPage fragmentPage(JenkinsRule j, String userId, Job<?, ?> job) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(j, userId, fragmentPath(job));
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: " + userId + " must open the dialog fragment of "
                + job.getFullName());
        return page;
    }

    /** The fragment's request form: the one form of the fragment posting to {@code <job>/batch-control/submit}. */
    static HtmlForm fragmentForm(HtmlPage fragment, Job<?, ?> job) throws Exception {
        List<HtmlForm> forms = UsabilityFixtures.formsEndingWith(fragment, job.getUrl() + "batch-control/submit");
        assertEquals(1, forms.size(), "the dialog fragment must carry exactly one Request Run form; forms: "
                + UsabilityFixtures.formActions(fragment));
        return forms.get(0);
    }

    /**
     * Opens {@code job}'s page in {@code wc} (JavaScript on), inserts the dialog fragment's markup
     * as core's dialog does, and returns the inserted request form, ready to be filled in.
     */
    static HtmlForm openDialog(JenkinsRule j, JenkinsRule.WebClient wc, Job<?, ?> job) throws Exception {
        WebResponse fragment = wc.loadWebResponse(new WebRequest(new URL(j.getURL(), fragmentPath(job))));
        assertEquals(200, fragment.getStatusCode(), "fixture: the dialog fragment of " + job.getFullName() + " must open");
        Page jobPage = wc.getPage(new URL(j.getURL(), job.getUrl()));
        assertEquals(200, jobPage.getWebResponse().getStatusCode(), "fixture: the job page of " + job.getFullName() + " must open");
        assertTrue(jobPage instanceof HtmlPage, "fixture: the job page must be HTML");
        HtmlPage page = (HtmlPage) jobPage;
        HtmlTextArea source = (HtmlTextArea) page.createElement("textarea");
        source.setId(SOURCE);
        source.setText(fragment.getContentAsString());
        page.getBody().appendChild(source);
        page.executeJavaScript("(function () {"
                + " var source = document.getElementById('" + SOURCE + "');"
                + " var text = source.value;"
                + " source.parentNode.removeChild(source);"
                + " var host = document.createElement('div');"
                + " host.id = '" + HOST + "';"
                + " host.innerHTML = text;"
                + " document.body.appendChild(host);"
                + " evalInnerHtmlScripts(text, function () { Behaviour.applySubtree(host, true); });"
                + "})();");
        wc.waitForBackgroundJavaScript(5000);
        DomElement host = page.getElementById(HOST);
        assertNotNull(host, "fixture: the fragment must have been inserted into the job page");
        List<HtmlForm> forms = host.getByXPath(".//form");
        assertEquals(1, forms.size(), "fixture: the inserted fragment must hold exactly one form: "
                + UsabilityFixtures.excerpt(host.asXml()));
        HtmlForm form = forms.get(0);
        String action = page.getFullyQualifiedUrl(form.getActionAttribute()).toExternalForm();
        assertTrue(UsabilityFixtures.stripQueryAndSlash(action).endsWith(job.getUrl() + "batch-control/submit"),
                "fixture: the dialog form must post to the job's submit endpoint, was " + action);
        return form;
    }

    /**
     * Fills in reason and approver and submits the inserted dialog form (redirects followed, as
     * core's dialog follows a redirected answer); returns the answer.
     */
    static Page submit(JenkinsRule.WebClient wc, HtmlForm form, String reason, String approver) throws Exception {
        return TypedParameterFixtures.submit(wc, form, reason, approver);
    }

    /**
     * Opens the dialog as {@code userId}, lets {@code fill} set the parameter controls, submits,
     * and returns the id of the one request the submission created (asserted), after checking
     * that the answer landed on that request's detail page.
     */
    static String submitThroughDialog(JenkinsRule j, String userId, Job<?, ?> job, FormFiller fill) throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, userId);
        HtmlForm form = openDialog(j, wc, job);
        fill.fill(form);
        Page answer = submit(wc, form, "month-end batch through the request dialog", "a1");
        assertTrue(answer.getWebResponse().getStatusCode() < 400, "the dialog submission by " + userId + " must succeed, got HTTP "
                + answer.getWebResponse().getStatusCode() + ": " + UsabilityFixtures.excerpt(answer.getWebResponse().getContentAsString()));
        Set<String> after = ApproverFormFixtures.runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "the dialog submission must create exactly one run request, got " + after);
        String id = after.iterator().next();
        assertEquals(UsabilityFixtures.stripQueryAndSlash(new URL(j.getURL(), "batch-control/requests/" + id).toExternalForm()),
                UsabilityFixtures.stripQueryAndSlash(answer.getUrl().toExternalForm()),
                "the dialog submission must lead to the new request's detail page (D-66)");
        return id;
    }

    /** Sets parameter controls of the inserted dialog form. */
    @FunctionalInterface
    interface FormFiller {
        void fill(HtmlForm form) throws Exception;
    }
}
