package io.jenkins.plugins.batchcontrol;

import org.htmlunit.Page;
import org.htmlunit.WebClient;
import org.htmlunit.WebWindow;
import org.htmlunit.javascript.background.JavaScriptJobManager;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Makes a test's DOM reads safe from the page's own background JavaScript (#189).
 *
 * <p>HtmlUnit runs timers and asynchronous requests ({@code setTimeout}, {@code fetch},
 * {@code XMLHttpRequest}) as jobs on a separate thread, the client's JavaScript executor, while
 * the test reads the DOM on its own thread. Nothing locks the DOM between the two, so an XPath
 * walk that meets a subtree a job is replacing fails inside {@code DOM2DTM} ("Could not retrieve
 * XPath", {@code node is null}). On the job pages these fixtures open, core's Builds card
 * ({@code jsbundles/pages/project/builds-card.js}) is such a job: it fetches the builds list when
 * the page loads and again every 5 seconds, and each time replaces its contents with
 * {@code innerHTML}. That refresh re-arms itself, so {@code waitForBackgroundJavaScript(5000)} can
 * never see an empty queue: it always waits the full 5 seconds and returns at the moment the next
 * refresh starts.
 *
 * <p>{@link #settled} waits for a state instead of a time: every job that is due now (within
 * {@link #DUE_NOW_MS}) has run to the end, including what it started in turn, such as a fetch and
 * its continuation; the timers that are left (the Builds card's next refresh) are cancelled; and
 * no job is running. The job manager takes a job off its queue and marks it running under one
 * lock, so a job count of 0 means that nothing is queued and nothing is running. From then on only
 * the test thread can add a job (a script it triggers itself), so no background script can change
 * the DOM while the test reads it. The wait is therefore enough however slow the machine is; the
 * time bound only turns a page that keeps scheduling short timers into a loud failure.
 *
 * <p>Test code only; it changes nothing the server renders.
 */
final class PageScriptFixtures {

    /** Jobs due within this many milliseconds are run to the end; later timers are cancelled. */
    static final long DUE_NOW_MS = 1000;

    /** Upper bound for the whole wait; reached only by a page that never stops scheduling jobs. */
    static final long TIMEOUT_MS = 60_000;

    private PageScriptFixtures() {
        // utility class
    }

    /**
     * Returns {@code page} once no background JavaScript of its client is queued or running (see
     * the class comment). A page without JavaScript returns at once.
     */
    static <P extends Page> P settled(P page) {
        if (page != null) {
            settle(page.getEnclosingWindow().getWebClient());
        }
        return page;
    }

    /** Waits until no window of {@code wc} has a queued or running background JavaScript job. */
    static void settle(WebClient wc) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (true) {
            boolean idle = true;
            StringBuilder busy = new StringBuilder();
            for (WebWindow window : wc.getWebWindows()) {
                JavaScriptJobManager jobs = window.getJobManager();
                if (jobs == null || jobs.getJobCount() == 0) {
                    continue;
                }
                idle = false;
                busy.append(jobs.jobStatusDump(null));
                // Run everything that is due now, and what that starts in turn, to the end.
                jobs.waitForJobsStartingBefore(DUE_NOW_MS);
                // Cancel the timers that are left (the Builds card's next refresh). A job that
                // started in the meantime keeps the count above 0, so the loop checks again.
                jobs.removeAllJobs();
            }
            if (idle) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                fail("fixture: the page's background JavaScript did not settle within " + TIMEOUT_MS
                        + " ms; jobs: " + busy);
            }
        }
    }
}
