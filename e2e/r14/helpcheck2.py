"""e2e-12: checkbox help buttons: a click must make one more help block visible (core places it after the checkbox).
H1 (2026-10-24): each click is a row in out/help2.jsonl, judged at the end (ci/h1_rules.py judge_targeted "help2"):
the number of visible help blocks must grow."""
from lib import Session, close
import lib
SHOWN = "() => [...document.querySelectorAll('.help')].filter(e => e.offsetHeight > 0 && e.innerText.trim().length > 20).length"
ROWS = []
s = Session("admin")
for page in ["/manage/batch-control-configuration/", "/job/batch-daily/configure"]:
    s.go(page)
    for h in s.page.locator("a.jenkins-help-button[helpurl*='batchcontrol']").all():
        if not h.is_visible():
            h.scroll_into_view_if_needed()
        before = s.page.evaluate(SHOWN)
        h.click()
        lib.until(s.page, lambda: s.page.evaluate(SHOWN) > before, 2000)
        after = s.page.evaluate(SHOWN)
        row = {"page": page, "help": h.get_attribute("helpurl").split("/")[-1], "before": before, "after": after}
        ROWS.append(row)
        lib.log("help2", row)
        print(page, row["help"], before, "->", after)
s.done(); close()
lib.h1.report("help2", ROWS)
