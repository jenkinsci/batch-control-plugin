"""e2e-15: side-panel entries of the folder's own page /job/team-mb/ vs its activation page, per account
(the new job page flag does not apply to folders). -> out/folder_compare.jsonl
H1 (2026-10-24): judged at the end by ci/h1_rules.py (judge_r15 "folder_compare"): the lists must be identical."""
import json
import lib
from lib import Session, close, log
ROWS = []
JS = "() => [...document.querySelectorAll('#side-panel #tasks a, #side-panel #tasks button')].map(a => [a.innerText.trim(), a.getAttribute('href')])"
for user in ("requester", "admin"):
    s = Session(user)
    s.go("/job/team-mb/"); own = s.page.evaluate(JS)
    s.go("/job/team-mb/batch-control-activation/"); act = s.page.evaluate(JS)
    row = {"user": user, "own": own, "activation": act, "identical": own == act}
    log("folder_compare", row); print(json.dumps(row)); ROWS.append(row)
    s.done()
close()
lib.h1.report("r15/folder_compare", ROWS)
