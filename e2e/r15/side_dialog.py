"""e2e-15: the side-panel "Request Change Permission" entry on the computed-folder activation page
(/job/team-mb/batch-control-activation/) for requester with the new job page on and off: the dialog opens in place,
pre-filled with the folder, and Escape closes it. -> out/side_dialog.jsonl, run-15/D-<flag>-requester.png
H1 (2026-10-24): judged at the end by ci/h1_rules.py (judge_r15 "side_dialog"); the console keeps every message (D-70
is allowlisted by its exact message, no substring filter)."""
import json
import lib
from lib import Session, close, log, groovy, gone, react
ROWS = []
for flag in ("true", "false"):
    groovy("""import jenkins.model.experimentalflags.*
def u = hudson.model.User.getById('requester', true); def m = new HashMap(); m.put('new-job-page.flag', '%s')
u.addProperty(new UserExperimentalFlagsProperty(m)); u.save(); return 'ok'""" % flag)
    s = Session("requester")
    s.go("/job/team-mb/batch-control-activation/")
    p = s.page
    link = p.locator("#side-panel a, #side-panel button").filter(has_text="Request Change Permission")
    row = {"flag": flag, "entries": link.count()}
    before = p.url
    link.first.click(); react(p, before, timeout=3000)  # e2e-20: was a fixed 1.5 s
    dlg = p.locator("dialog[open]")
    row["dialog"] = dlg.count(); row["url_unchanged"] = p.url == before
    if dlg.count():
        row["dialog_text"] = dlg.first.inner_text()[:300]
        row["prefill"] = p.evaluate("""() => [...document.querySelectorAll('dialog[open] input, dialog[open] select')]
            .filter(i => i.type !== 'hidden' || /scope|target|item/i.test(i.name)).map(i => [i.name, i.type === 'checkbox' || i.type === 'radio' ? i.checked : i.value]).slice(0, 12)""")
        s.shot("dialog[open]", f"D-{flag}-requester")
        p.keyboard.press("Escape")
        row["closed_by_escape"] = gone(p)
    row["console"] = list(s.console); row["bad"] = list(s.bad)
    log("side_dialog", row); print(json.dumps(row)); ROWS.append(row)
    s.done()
close()
lib.h1.report("r15/side_dialog", ROWS)
