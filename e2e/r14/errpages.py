"""e2e-12: pages re-rendered after a refused submit (HTTP 400 with the form and its errors). Every link and
form on them must still work: the URL is now .../<endpoint>, so a relative link would point below it.
Cases: run request Change Approvers with none ticked, incident Request Rerun with no approver, activation page
without reason, Request Run page without approver, grants/new without reason, grants/create via the full page.

H1 (2026-10-24): judged at the end (ci/h1_rules.py judge_targeted "errpages"): a broken link, a form whose action does
not answer 405 to a GET, a missing error message, a console error. Each case's console holds only that case's
messages (it held the whole session's), and the browser's line for the re-rendered document's own 400 is dropped (the
refusal is the case's premise); the substring filters "MIME" and "400" are gone (D-70 is allowlisted by exact
message)."""
import re, json
from lib import Session, close, api, BASE
import lib
from actions import f_run, f_incident, f_grant

ROWS = []
MARK = {"n": 0, "bad": 0}


def mark(s):
    """Start of a case: its console and responses begin here."""
    MARK["n"], MARK["bad"] = len(s.console), len(s.bad)


def check_page(s, case):
    row = {"case": case, "url": s.page.url.replace(BASE, ""), "errors": [t.strip()[:100] for t in s.page.locator("#main-panel .error, #main-panel .jenkins-alert-danger").all_inner_texts() if t.strip()][:2]}
    bad = []
    n = 0
    for a in s.page.locator("#main-panel a[href], #side-panel a[href], .jenkins-breadcrumbs a[href], #breadcrumbBar a[href]").all():
        h = a.get_attribute("href")
        if not h or h.startswith("#") or h.startswith("javascript") or "logout" in h:
            continue
        u = a.evaluate("e => e.href")
        if not u.startswith(BASE):
            continue
        n += 1
        st = s.context.request.get(u, max_redirects=5).status
        if st >= 400 and st != 405:
            bad.append((a.inner_text().strip()[:30], h[:80], u.replace(BASE, ""), st))
    forms = []
    for f in s.page.locator("#main-panel form").all():
        act = f.evaluate("e => e.action")
        m = (f.get_attribute("method") or "get").lower()
        st = s.context.request.get(act, max_redirects=5).status
        forms.append((f.get_attribute("name"), m, act.replace(BASE, ""), st))
    cons = list(s.console[MARK["n"]:])
    doc = s.page.url
    if any(b.startswith("400 POST ") and b.split(" ", 2)[2].split("?")[0] == doc.split("?")[0] for b in s.bad[MARK["bad"]:]):
        # the re-rendered refusal page itself answered 400
        own = next((c for c in cons if c.startswith("error: Failed to load resource: the server responded with a status of 400 ")), None)
        if own:
            cons.remove(own)
    row.update(links_checked=n, broken=bad, forms=forms, console=cons[:10])
    ROWS.append(row)
    lib.log("errpages", row)
    print(json.dumps(row)[:900])


s = Session("requester")
_, rid = f_run()
s.go(f"/batch-control/requests/{rid}/")
mark(s)
f = s.page.locator("form[name=changeApprover]")
for b in f.locator("input[type=checkbox]").all():
    if b.is_checked():
        b.locator("xpath=following-sibling::label").first.click()
with s.page.expect_navigation():
    f.locator("button[type=submit], input[type=submit], button:not([type])").first.click()
check_page(s, "run request changeApprover none")
s.go("/job/batch-daily/batch-control/")
mark(s)
s.page.locator("#main-panel textarea[name=reason]").fill("x")
with s.page.expect_navigation():
    s.page.locator("#main-panel button[name=Submit]").first.click()
check_page(s, "Request Run full page, no approver")
s.go("/job/batch-cron/batch-control-activation/")
mark(s)
with s.page.expect_navigation():
    s.page.locator("#main-panel button[name=Submit]").first.click()
check_page(s, "activation page, nothing filled")
s.go("/batch-control/grants/new?scopeFullName=batch-daily")
mark(s)
with s.page.expect_navigation():
    s.page.locator("#main-panel button[name=Submit]").last.click()
check_page(s, "grants/new, nothing filled")
s.go("/view/batch-view/job/batch-daily/batch-control/")
mark(s)
s.page.locator("#main-panel textarea[name=reason]").fill("x")
with s.page.expect_navigation():
    s.page.locator("#main-panel button[name=Submit]").first.click()
check_page(s, "Request Run via view, no approver")
s.done()
s = Session("admin")
_, iid = f_incident()
s.go(f"/batch-control/incidents/{iid}/")
mark(s)
with s.page.expect_navigation():
    s.page.locator("form[name=rerun] button[type=submit], form[name=rerun] input[type=submit], form[name=rerun] button:not([type])").first.click()
check_page(s, "incident rerun no approver")
s.done()
close()
lib.h1.report("errpages", ROWS)
