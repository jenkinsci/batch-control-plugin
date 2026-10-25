package io.jenkins.plugins.batchcontrol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.htmlunit.Page;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextArea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The Decision section of the run request page (Wave D D3, #18.5), read without assuming whether
 * it is one form with {@code formaction} on Reject or two forms side by side: the section is
 * everything after the {@code h2} reading "Decision" up to the next {@code h2}; its comment boxes
 * are the {@code textarea}/{@code input} named {@code comment} there; Approve and Reject are the
 * buttons (or submit inputs) there captioned so; a button's target is its {@code formaction}, else
 * its form's action.
 *
 * <p>Written from the Wave D contract (D3) and pages rendered on origin/main; no src/main knowledge.
 */
final class DecisionAreaFixtures {

    private static final String IN_DECISION = "[preceding::h2[1][normalize-space(.)='Decision']]";

    private DecisionAreaFixtures() {
    }

    static List<HtmlElement> commentBoxes(HtmlPage page) {
        List<HtmlElement> out = new ArrayList<>();
        for (Object o : page.getByXPath("//*[(self::textarea or self::input) and @name='comment']" + IN_DECISION)) {
            HtmlElement e = (HtmlElement) o;
            if (!"hidden".equals(e.getAttribute("type"))) {
                out.add(e);
            }
        }
        return out;
    }

    /** The button captioned {@code caption} (case-insensitive) in the Decision section, or null. */
    static HtmlElement button(HtmlPage page, String caption) {
        for (Object o : page.getByXPath("//*[self::button or self::input[@type='submit']]" + IN_DECISION)) {
            HtmlElement e = (HtmlElement) o;
            String text = e instanceof HtmlInput i ? i.getValue() : e.asNormalizedText();
            if (text.trim().toLowerCase(Locale.ROOT).equals(caption.toLowerCase(Locale.ROOT))) {
                return e;
            }
        }
        return null;
    }

    /** The resolved path the button submits to: its formaction, else its form's action. */
    static String target(HtmlPage page, HtmlElement button) throws Exception {
        String action = button.getAttribute("formaction");
        if (action == null || action.isEmpty()) {
            HtmlForm form = button.getEnclosingForm();
            assertNotNull(form, "the decision button must submit a form: " + button.asXml());
            action = form.getActionAttribute();
        }
        return page.getFullyQualifiedUrl(action).getPath();
    }

    /** The one comment box of the Decision section (asserted). */
    static HtmlElement theCommentBox(HtmlPage page) {
        List<HtmlElement> boxes = commentBoxes(page);
        assertEquals(1, boxes.size(), "the Decision section must have exactly one comment box, found " + boxes.size());
        return boxes.get(0);
    }

    static String value(HtmlElement box) {
        return box instanceof HtmlTextArea t ? t.getText() : ((HtmlInput) box).getValue();
    }

    /** Types {@code comment} into the one comment box and presses the button captioned {@code caption}. */
    static Page decide(HtmlPage page, String caption, String comment) throws Exception {
        HtmlElement box = theCommentBox(page);
        if (box instanceof HtmlTextArea t) {
            t.setText(comment);
        } else {
            ((HtmlInput) box).setValue(comment);
        }
        HtmlElement button = button(page, caption);
        assertNotNull(button, "the Decision section must offer " + caption);
        // #93: the Reject button asks for confirmation through core's dialog before the form
        // posts. Auto-confirm it so the form posts as the test intends: a native confirm handler
        // (should the page ever use one) plus a stub that resolves core's window.dialog.confirm.
        // A blank comment skips the dialog (the server refuses), so this is a no-op there.
        page.getWebClient().setConfirmHandler((p, message) -> true);
        page.executeJavaScript("if (!window.dialog) { window.dialog = {}; }"
                + "if (typeof window.dialog.confirm === 'function' && !window.dialog.confirm.__bcAutoConfirm) {"
                + "  window.dialog.confirm = (function (original) {"
                + "    var stub = function () { return Promise.resolve(); };"
                + "    stub.__bcAutoConfirm = true;"
                + "    return stub;"
                + "  })(window.dialog.confirm);"
                + "}");
        Page next = button.click();
        page.getWebClient().waitForBackgroundJavaScript(2000);
        Page current = page.getWebClient().getCurrentWindow().getEnclosedPage();
        return current != null ? current : next;
    }

    /** The texts of the main panel's h2 headings, in document order. */
    static List<String> headings(HtmlPage page) {
        List<String> out = new ArrayList<>();
        for (DomNode n : page.querySelectorAll("#main-panel h2")) {
            out.add(n.asNormalizedText().trim());
        }
        return out;
    }

    /** True if {@code error} is next to {@code box}: inside the box's parent or grandparent element. */
    static boolean nextTo(DomElement box, DomElement error) {
        DomNode parent = box.getParentNode();
        for (int i = 0; i < 2 && parent != null; i++, parent = parent.getParentNode()) {
            if (parent.isAncestorOf(error)) {
                return true;
            }
        }
        return false;
    }
}
