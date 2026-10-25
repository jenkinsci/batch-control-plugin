"""e2e-12: every help '?' button on the Batch Control forms: its helpURL answers 200 with text, and a click
expands the help area with that text. Pages: configuration, job configure (job property), request forms.

H1 (2026-10-24): judged at the end (ci/h1_rules.py judge_targeted "help"): status 200, at least 20 characters, expands.
"expands" is measured the way helpcheck2.py does (triage T-01): the click must make one more help block visible on the
page (.help with text and a height). The old check looked for the help block in the button's form-item ancestor, which
does not reach core's help block of a checkbox, and called seven working checkbox helps "no-help-area". Each page's
console holds only that page's messages (T-02); warnings are recorded and never judged."""
import re
from lib import Session, close, BASE
import lib
PAGES = ["/manage/batch-control-configuration/", "/job/batch-daily/configure", "/job/batch-daily/batch-control/",
         "/job/batch-cron/batch-control-activation/", "/batch-control/grants/new", "/job/ops/job/mb/configure"]
SHOWN = "() => [...document.querySelectorAll('.help')].filter(e => e.offsetHeight > 0 && e.innerText.trim().length > 20).length"
ROWS = []
s = Session("admin")
for page in PAGES:
    nc = len(s.console)
    s.go(page)
    helps = s.page.locator("a.jenkins-help-button, a[helpurl], .jenkins-help-button")
    rows = []
    for i in range(helps.count()):
        h = helps.nth(i)
        url = h.get_attribute("helpurl") or h.get_attribute("helpURL")
        if not url or "batchcontrol" not in url.lower() and "batch" not in url.lower():
            continue
        r = s.context.request.get(BASE.split("/jenkins")[0] + url if url.startswith("/") else url)
        body = re.sub(r"<[^>]+>", " ", r.text())
        clicked = None
        try:
            if not h.is_visible():
                h.scroll_into_view_if_needed()
            before = s.page.evaluate(SHOWN)
            h.click()
            lib.until(s.page, lambda: s.page.evaluate(SHOWN) > before, 2000)
            clicked = s.page.evaluate(SHOWN) > before
        except Exception as e:  # noqa
            clicked = "click-error " + str(e)[:200]
        rows.append({"url": url.replace("/jenkins", ""), "status": r.status, "chars": len(body.strip()), "expands": clicked})
    row = {"page": page, "helps": rows, "console": s.console[nc:][:10]}
    ROWS.append(row)
    lib.log("help", row)
    print(page, len(rows), [x for x in rows if x["status"] != 200 or x["chars"] < 20 or x["expands"] not in (True,)])
s.done(); close()
lib.h1.report("help", ROWS)
