"""e2e-12: incident Resolve (shown once acknowledged) and Add Comment (the comment appears on the page), as admin and
approver-1; Request Rerun with no approver ticked stays on the page with its error.

H1 (2026-10-24): judged at the end (ci/h1_rules.py judge_targeted "incident"). The incident rerun form has no reason
field (it carries the failed run's values, SPEC 11), so "reason kept" is not recorded any more (triage T-04): the
refusal must answer 400 with "Check at least one approver.".

M4 (gap audit 2026-10, SPEC 11 OPEN -> ACKNOWLEDGED -> RESOLVED through the browser forms): each step also records
the Status row and the post forms before anything is pressed (Open; Acknowledge offered, Resolve not), the Status row
after the acknowledgement (Acknowledged), the History table's rows (status, by, comment) and the stored status
(server state, read from the incident's file) after each form, and for the refused rerun that the page is the
incident's own (its Status row and the rerun form shown again) and that no rerun request was linked. The rules are in
judge_targeted "incident"; ci/selftest_h1.py replays a passing row and one synthetic breakage per field."""
import re
from lib import Session, close, api, groovy, BASE
import lib
from actions import f_incident


ROWS = []
POST_FORMS = "#main-panel form[method=post]"
STATUS_CELL = "#main-panel tr:has(th:text-is('Status')) td"


def emit(row):
    ROWS.append(row)
    lib.log("incident", row)
    print(row)


def status_row(p):
    c = p.locator(STATUS_CELL)
    return re.sub(r"\s+", " ", c.first.inner_text()).strip() if c.count() else None


def forms(p):
    return [x.get_attribute("name") for x in p.locator(POST_FORMS).all()]


def history(p):
    """The History table's rows as [status, by, comment] (the At column left out)."""
    out = []
    for tr in p.locator("#main-panel h2:text-is('History') + table tbody tr").all():
        cells = [re.sub(r"\s+", " ", t).strip() for t in tr.locator("td").all_inner_texts()]
        out.append([cells[0], cells[1], cells[3]] if len(cells) >= 4 else cells)
    return out


def stored(iid):
    """Server state: the incident's stored status and its linked rerun request ids."""
    out = groovy(f"""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/incidents/{iid}.xml')
if (!f.exists()) return 'missing|'
def x = new XmlSlurper().parse(f)
return x.status.text() + '|' + x.rerunRequestIds.children()*.text().join(',')""")
    out = out.replace("Result:", "").strip()
    status, _, reruns = out.partition("|")
    return {"status": status, "reruns": [r for r in reruns.split(",") if r]}


def press(p, f):
    with p.expect_navigation() as nav:
        f.locator("button[type=submit], input[type=submit], button:not([type]), button.jenkins-button--primary").first.click()
    p.wait_for_load_state("load")
    return nav.value.status


for role in ["admin", "approver-1"]:
    _, iid = f_incident()
    s = Session(role)
    s.go(f"/batch-control/incidents/{iid}/")
    p = s.page
    row = {"role": role, "id": iid, "state_before": status_row(p), "forms_before": forms(p), "stored_before": stored(iid)["status"]}
    comment = f"comment by {role} e2e-12"
    f = p.locator("form[name=addComment]")
    f.locator("textarea").fill(comment)
    row["comment_status"] = press(p, f)
    row["comment_visible"] = comment in s.text()
    row["history_after_comment"] = history(p)
    row["state_after_comment"] = status_row(p)
    f = p.locator("form[name=acknowledge]")
    f.locator("textarea").fill("ack")
    row["ack_status"] = press(p, f)
    row["state_after_ack"] = status_row(p)
    row["after_ack_forms"] = forms(p)
    row["stored_after_ack"] = stored(iid)["status"]
    s.shot("#main-panel", f"I-{role}-acknowledged")
    f = p.locator("form[name=resolve]")
    if f.count():
        f.locator("textarea").fill("resolved e2e-12")
        row["resolve_status"] = press(p, f)
        row["state_after_resolve"] = status_row(p) or re.sub(r"\s+", " ", s.text())[:200]
        row["forms_after_resolve"] = forms(p)
        row["history_after_resolve"] = history(p)
        row["stored_after_resolve"] = stored(iid)["status"]
    s.shot("#main-panel", f"I-{role}-resolved")
    s.done()
    emit(row)
# rerun validation (admin)
_, iid = f_incident()
s = Session("admin")
s.go(f"/batch-control/incidents/{iid}/")
f = s.page.locator("form[name=rerun]")
row = {"case": "rerun validation", "id": iid, "fields": [x.get_attribute("name") for x in f.locator("input, textarea, select").all()][:12],
       "reruns_before": stored(iid)["reruns"]}
for b in f.locator("input[name=approvers]").all():
    if b.is_checked():
        b.locator("xpath=following-sibling::label").first.click()
with s.page.expect_navigation() as nav:
    f.locator("button[type=submit], input[type=submit], button:not([type]), button.jenkins-button--primary").first.click()
row["status"] = nav.value.status
row["url"] = s.page.url.replace(BASE, "")
row["errors"] = [t.strip()[:120] for t in s.page.locator(".error, .jenkins-alert-danger").all_inner_texts() if t.strip()][:3]
# the refusal is rendered as the incident's own page (its Status row, the rerun form again), nothing linked
row["state_after_refusal"] = status_row(s.page)
row["forms_after_refusal"] = forms(s.page)
row["reruns_after"] = stored(iid)["reruns"]
s.shot("#main-panel", "I-rerun-validation")
s.done(); close()
emit(row)
lib.h1.report("incident", ROWS)
