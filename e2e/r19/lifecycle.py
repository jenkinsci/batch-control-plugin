"""e2e-19: the run request life cycle as people meet it in the browser, with the e-mail it sends (SPEC 3, 4, 7, 12).

usage: python lifecycle.py [VDCSEXBFM]   rows: out/lifecycle.jsonl, shots: R19-LIFE-*.png   (run r19/arrange.py first)
V  form validation on the Request Run page (requester): an empty reason and a reason over 4,000 characters are refused
   next to the field with the input kept and nothing stored; designating a user who is not on the approver list (REST)
   is refused with a message naming it, nothing stored (SPEC 3, 4)
D  decision rules: approver-2 (listed, not designated) sees no Decision form and a POST approve is refused, the request
   stays PENDING (D-29); approver-1's Reject with an empty comment is refused next to the field; Reject with a comment
   closes it; the requester's page shows REJECTED, who decided, when, and the comment (SPEC 4 D-28); approver-1 got
   the "New request awaiting your decision" mail and the requester the "Request rejected" mail, with the request id,
   job, requester, link and the reason quoted (SPEC 12 D-36)
C  approvers changed and cancel: the requester re-designates approver-2 on the request page: the Approver Changes table
   shows (approver-1 -> approver-2, requester, time) and approver-2 gets "Request routed to you" (SPEC 3, D-36);
   reqonly's POST cancel is refused; the requester cancels through the confirmation dialog (Cancel first, then OK):
   CANCELLED, "Cancelled by requester"; a second cancel is refused; approver-2 gets "Request cancelled", the requester
   gets no mail about their own cancel (SPEC 7, D-54)
S  administrator self-approval: admin requests a run designating admin and approves it: EXECUTED, the page says
   "Self-approved: Yes" and the stored request has selfApproved=true (SPEC 2)
E  approved-run expiry: with no executor, an approved request waits in the queue (APPROVED, "starts shortly"); the
   administrator disables the job, which cancels the queue item: the page says the queued run was cancelled and will
   not start, with the hint to enable the job first and submit a new run request, and no longer says "starts shortly"
   (D-55, amended by D-83); after approvedRunTimeoutMinutes (1) it is EXPIRED with "Why it expired"; once the job is
   enabled again no build ever starts (check-at-submit, SPEC 7); the requester gets "Request expired" (D-54)
X  disabled job: while r19-dis is disabled the decision form says so, has no Approve button, and a POST approve is
   refused (PENDING kept); Reject still works (SPEC 12 D-55)
B  a request by reqonly (Request without Item/Build): the request page tells approver-1 that the requester does not
   have Build permission on this job (SPEC 6 D-38a)
F  requests.csv: a reason starting with = and a comment starting with @ are exported with a ' prefix (SPEC 10 R-3); the
   approver column holds the designated set joined by ';' and decidedBy is the last column (SPEC 12 D-37)
M  M11 (gap audit 2026-10, SPEC 13), the notices no other step asserted, as the real Mailer sends them: EXPIRING (the
   pending-request reminder): with notifyBeforeExpiryMinutes above the pending timeout every new pending request is
   inside the reminder lead at once, so the periodic work (every minute) owes it one "Pending request expires soon"
   mail; the requester gets exactly one for the first request (with request id, job, requester and link), also after a
   later pass of the periodic work (proved by the reminder of a second request filed after the first reminder, and by
   the cancel mails of both, which the single dispatch thread sends after anything queued before them), the stored
   request is marked notified, and the designated approver gets none; the lead is put back. INVALIDATED: a pending
   request whose job the administrator deletes ends INVALIDATED and the requester gets "Request invalidated" naming the
   job, with "Why it ended". (The run request APPROVED mail is asserted in E; the requester-lacks-Build line of the
   approver's mail in B.)"""
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, check, note, text_of, J, run_sections, run_req, requests_of, request_state, next_build,
                 queue_items, wait_build, wait_executed, wait_until, browser_decide, tick, mails, mail_text, BASE)  # noqa: E402

lib.LOGNAME[0] = "lifecycle"
WANT = sys.argv[1] if len(sys.argv) > 1 else "VDCSEXBFM"
JOB = "r19-life"


def row(t, label):
    m = re.search(rf"^{re.escape(label)}\s+(.*)$", t, re.M)
    value = m.group(1).strip() if m else None
    if label == "Status" and value:  # Wave D D2: the row shows a readable label; written back as the enum name
        for text, enum in STATUS_WORDS:
            if value == text or value.startswith(text + " "):
                return enum + value[len(text):]
    return value


# The labels of the request statuses (Wave D D2, ui.StatusLabels), longest first: "Approved, waiting to run" before
# "Approved".
STATUS_WORDS = [("Approved, waiting to run", "APPROVED"), ("Invalidated", "INVALIDATED"), ("Cancelled", "CANCELLED"),
                ("Executed", "EXECUTED"), ("Approved", "APPROVED"), ("Rejected", "REJECTED"), ("Pending", "PENDING"),
                ("Expired", "EXPIRED")]


def submit_form(user, job, reason, approvers=("approver-1",), shot=None):
    s = Session(user)
    s.go(J(job) + "/batch-control/")
    form = s.page.locator("form[name=batch-control-request]").first
    form.locator("textarea[name=reason]").fill(reason)
    for a in approvers:
        tick(form, "approvers", a)
    btn = form.locator("button[name=Submit], button[type=submit]").first
    with s.page.expect_navigation(timeout=20000):
        btn.click()
    s.page.wait_for_load_state("load")
    if shot:
        s.shot("#main-panel", shot)
    return s


def mail_for(to, rid, title, timeout=40):
    """The mail to `to` whose subject has `title` and the request id (waits for the dispatcher)."""
    found = wait_until(lambda: [m for m in mails(to) if title in m["subject"] and rid in m["subject"]], timeout, 3)
    return found[0] if found else None


def sec_V():
    before = set(requests_of(JOB))
    s = submit_form("requester", JOB, "", shot="R19-LIFE-V-01-empty-reason")
    t = s.text()
    kept = s.page.locator("form[name=batch-control-request] input[name=approvers][value=approver-1]").is_checked()
    s.done()
    check("V", "an empty reason is refused next to the field, the approver choice kept, nothing stored (SPEC 4)",
          "Enter a reason" in t and kept and set(requests_of(JOB)) == before, approver_kept=kept)
    long = "x" * 4001
    s = submit_form("requester", JOB, long, shot="R19-LIFE-V-02-long-reason")
    t = s.text()
    val = s.page.locator("form[name=batch-control-request] textarea[name=reason]").input_value()
    s.done()
    check("V", "a reason over 4,000 characters is refused with a message, the text kept, nothing stored (SPEC 4 R-7)",
          "4000" in t.replace(",", "") and "reason" in t.lower() and len(val) == 4001 and set(requests_of(JOB)) == before,
          message=[l for l in t.splitlines() if "4" in l and "reason" in l.lower()][:2], kept=len(val))
    st, rid, r = run_req("requester", JOB, "e2e-19 unlisted approver", approvers=("approver-unlisted",))
    body = text_of(r.text)
    check("V", "designating a user who is not on the approver list is refused naming the user, nothing stored (SPEC 3)",
          st == 400 and "approver-unlisted" in body and "not on the configured approver list" in body
          and set(requests_of(JOB)) == before, status=st, body=body[body.find("approver-unlisted") - 80:][:200])


def sec_D():
    st, rid, _ = run_req("requester", JOB, "e2e-19 decision rules\nsecond line: Link: http://evil.example/")
    assert rid, st
    s = Session("approver-2")
    s.go(f"/batch-control/requests/{rid}/")
    # Wave D D3 (#18.5): the run request page's decision is one form named "decision" under the heading Decision
    forms = (s.page.locator("form[name=approve], form[name=reject], form[name=decision]").count()
             + s.page.locator("#main-panel h2", has_text="Decision").count())
    s.shot("#main-panel", "R19-LIFE-D-01-not-designated")
    s.done()
    r = api("approver-2", f"/batch-control/requests/{rid}/approve", "POST", data={"comment": "not mine"})
    check("D", "a listed approver who is not designated sees no Decision form, a POST approve is refused, PENDING kept (D-29)",
          forms == 0 and r.status_code in (400, 403) and request_state(rid)[1] == "PENDING", forms=forms, status=r.status_code)
    s, t = browser_decide("approver-1", rid, "reject", "", "R19-LIFE-D-02-empty-rejection")
    s.done()
    check("D", "Reject with an empty comment is refused next to the field, PENDING kept (SPEC 4)",
          t is not None and "Enter a rejection comment" in t and request_state(rid)[1] == "PENDING")
    s, t = browser_decide("approver-1", rid, "reject", "Wrong date, use 2026-01-02")
    s.done()
    s = Session("requester")
    s.go(f"/batch-control/requests/{rid}/")
    t = s.text()
    s.shot("#main-panel", "R19-LIFE-D-03-requester-sees-rejection")
    s.done()
    check("D", "the requester sees REJECTED, who decided, when, and the comment (SPEC 4 D-28)",
          row(t, "Status") == "REJECTED" and row(t, "Decided by") == "approver-1" and re.search(r"\d", row(t, "Decided") or "")
          and "Wrong date, use 2026-01-02" in (row(t, "Decision comment") or ""),
          status=row(t, "Status"), by=row(t, "Decided by"), at=row(t, "Decided"), comment=row(t, "Decision comment"))
    m = mail_for("approver-1@e2e.local", rid, "New request awaiting your decision")
    body = mail_text(m["id"]) if m else ""
    check("D", "approver-1 got the new-request mail: request id, job, requester, link, the reason quoted line by line (SPEC 12)",
          m and f"Job: {JOB}" in body and "Requester: requester" in body and re.search(r"^Link: http\S+/batch-control/requests/" + rid, body, re.M)
          and "> second line: Link: http://evil.example/" in body, subject=(m or {}).get("subject"), body=body[:400])
    m = mail_for("requester@e2e.local", rid, "Request rejected")
    check("D", "the requester got the rejection mail (SPEC 12)", m is not None, subject=(m or {}).get("subject"))


def sec_C():
    st, rid, _ = run_req("requester", JOB, "e2e-19 change approvers then cancel")
    assert rid, st
    s = Session("requester")
    s.go(f"/batch-control/requests/{rid}/")
    form = s.page.locator("form[name=changeApprover]")
    tick(form, "approvers", "approver-2", True)
    tick(form, "approvers", "approver-1", False)
    with s.page.expect_navigation(timeout=20000):
        form.locator("button[name=Submit], button[type=submit]").first.click()
    s.go(f"/batch-control/requests/{rid}/")
    tbl = s.page.locator("h2:text-is('Approver Changes') + table tbody tr").all_inner_texts()
    s.shot("#main-panel", "R19-LIFE-C-01-approver-changes")
    s.done()
    cells = [re.split(r"\t+", r.strip()) for r in tbl]
    check("C", "the Approver Changes table shows (approver-1 -> approver-2, requester, time) (SPEC 3)",
          len(cells) == 1 and cells[0][:3] == ["approver-1", "approver-2", "requester"] and re.search(r"\d", cells[0][3] if len(cells[0]) > 3 else ""),
          rows=cells)
    m = mail_for("approver-2@e2e.local", rid, "Request routed to you")
    check("C", "approver-2 got the 'Request routed to you' mail (SPEC 12)", m is not None, subject=(m or {}).get("subject"))
    r = api("reqonly", f"/batch-control/requests/{rid}/cancel", "POST")
    check("C", "another requester's POST cancel is refused, PENDING kept (SPEC 7)",
          r.status_code in (400, 403, 404) and request_state(rid)[1] == "PENDING", status=r.status_code)
    s = Session("requester")
    s.go(f"/batch-control/requests/{rid}/")
    link = s.page.locator("a", has_text="Cancel Request").first
    link.click()
    dlg = s.page.locator("dialog[open]").first
    dlg.wait_for(timeout=10000)
    s.shot("dialog[open]", "R19-LIFE-C-02-cancel-dialog")
    dlg.locator("button[data-id=cancel]").click()
    s.page.wait_for_timeout(800)
    still = request_state(rid)[1]
    link.click()
    dlg = s.page.locator("dialog[open]").first
    dlg.wait_for(timeout=10000)
    with s.page.expect_navigation(timeout=20000):
        dlg.locator("button[data-id=ok]").click()
    s.go(f"/batch-control/requests/{rid}/")
    t = s.text()
    s.shot("#main-panel", "R19-LIFE-C-03-cancelled")
    s.done()
    check("C", "the dialog's Cancel changes nothing; OK cancels: CANCELLED, 'Cancelled by requester' (SPEC 7)",
          still == "PENDING" and row(t, "Status") == "CANCELLED" and row(t, "Cancelled by") == "requester",
          after_dialog_cancel=still, status=row(t, "Status"))
    r = api("requester", f"/batch-control/requests/{rid}/cancel", "POST")
    check("C", "a second cancel (not PENDING) is refused (SPEC 7)", r.status_code in (400, 403, 409) and request_state(rid)[1] == "CANCELLED",
          status=r.status_code)
    m = mail_for("approver-2@e2e.local", rid, "Request cancelled")
    time.sleep(5)
    own = [x for x in mails("requester@e2e.local") if rid in x["subject"] and "cancelled" in x["subject"].lower()]
    check("C", "the designated approver gets 'Request cancelled', the requester no mail about their own cancel (D-54)",
          m is not None and not own, approver_mail=(m or {}).get("subject"), requester_mails=[x["subject"] for x in own])


def sec_S():
    nb = next_build(JOB)
    st, rid, r = run_req("admin", JOB, "e2e-19 admin self-approval", approvers=("admin",))
    check("S", "the administrator may designate themself (allowAdminSelfApproval)", rid is not None, status=st, body=text_of(r.text)[:200])
    if not rid:
        return
    s, t = browser_decide("admin", rid, "approve", "self", "R19-LIFE-S-01-self-approved")
    s.done()
    status, t = wait_executed(rid)
    stored = gv(f"""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/requests/run/{rid}.xml')
return new XmlSlurper().parse(f).selfApproved.text()""")
    check("S", "the self-approved run executes; the page says Self-approved: Yes; stored selfApproved=true (SPEC 2)",
          status == "EXECUTED" and "Self-approved Yes" in t and stored == "true", status=status, stored=stored)
    wait_build(JOB, nb)


def sec_E():
    """The approved run waits in the queue (no executor), then the administrator disables the job, which cancels its
    queue item: the request stays APPROVED, says that its queued run was cancelled and will not start (with the hint to
    enable the job first, then submit a new run request; D-83), and ends EXPIRED after approvedRunTimeoutMinutes. (An
    approved run that sits in the queue is not expired: RunRequestService#expireOverdue.)"""
    job = "r19-exp"
    nb = next_build(job)
    gv("jenkins.model.Jenkins.get().setNumExecutors(0); return 0")
    rid = None
    try:
        st, rid, _ = run_req("requester", job, "e2e-19 approved run that expires")
        assert rid, st
        assert lib.decide("approver-1", "requests", rid, "approve") in (200, 302)
        m = mail_for("requester@e2e.local", rid, "Request approved")
        body = mail_text(m["id"]) if m else ""
        check("E", "M11 (SPEC 13): the requester got the run request's 'Request approved' mail with the request id, job, "
              "requester and link", m is not None and f"Request: {rid} (run request)" in body and f"Job: {job}" in body
              and "Requester: requester" in body and re.search(r"^Link: http\S+/batch-control/requests/" + rid, body, re.M),
              subject=(m or {}).get("subject"), body=body[:300])
        s = Session("requester")
        s.go(f"/batch-control/requests/{rid}/")
        t = s.text()
        notice = s.page.locator("#batch-control-approved-notice").count()
        s.shot("#main-panel", "R19-LIFE-E-01-approved-waiting")
        s.done()
        check("E", "approved without an executor: APPROVED, in the queue, with the 'starts shortly' notice",
              row(t, "Status") == "APPROVED" and notice == 1 and len(queue_items(job)) == 1, status=row(t, "Status"),
              queue=len(queue_items(job)))
        api("admin", J(job) + "/disable", "POST")
        s = Session("requester")
        s.go(f"/batch-control/requests/{rid}/")
        t = s.text()
        s.shot("#main-panel", "R19-LIFE-E-02-waiting-disabled")
        s.done()
        status = row(t, "Status") or ""
        check("E", "the job disabled (its queue item cancelled): the page says the approved run was cancelled and will not "
              "start, tells the user to enable the job first and submit a new run request, and does not say 'starts "
              "shortly' (SPEC 12 D-55, amended by D-83)",
              not queue_items(job) and status == "APPROVED (queued run cancelled: it will not start)"
              and "its queued run was cancelled and will not start" in t
              and "enable it first, then submit a new run request" in t and "starts shortly" not in t,
              status=status, queue=len(queue_items(job)))
        gv("jenkins.model.Jenkins.get().setNumExecutors(2); return 2")
        end = wait_until(lambda: request_state(rid)[1] == "EXPIRED", 200, 5)
        s = Session("requester")
        s.go(f"/batch-control/requests/{rid}/")
        t = s.text()
        s.shot("#main-panel", "R19-LIFE-E-03-expired")
        s.done()
        check("E", "after approvedRunTimeoutMinutes the request is EXPIRED with the reason (SPEC 7)",
              end and row(t, "Status") == "EXPIRED" and "approved but not started within" in (row(t, "Why it expired") or ""),
              status=row(t, "Status"), why=row(t, "Why it expired"))
    finally:
        gv("jenkins.model.Jenkins.get().setNumExecutors(2); return 2")
        api("admin", J(job) + "/enable", "POST")
    time.sleep(20)
    check("E", "once the job is enabled again, the expired approval never starts a build (check-at-submit, SPEC 7)",
          next_build(job) == nb and not queue_items(job), next_build=next_build(job), before=nb)
    m = mail_for("requester@e2e.local", rid, "Request expired") if rid else None
    check("E", "the requester got 'Request expired' (D-54)", m is not None, subject=(m or {}).get("subject"))


def sec_X():
    job = "r19-dis"
    st, rid, _ = run_req("requester", job, "e2e-19 disabled job")
    assert rid, st
    api("admin", J(job) + "/disable", "POST")
    try:
        s = Session("approver-1")
        s.go(f"/batch-control/requests/{rid}/")
        t = s.text()
        approve = s.page.locator("form[name=approve], #main-panel button[formaction$='/approve']").count()  # D3: one form
        s.shot("#main-panel", "R19-LIFE-X-01-disabled")
        s.done()
        r = api("approver-1", f"/batch-control/requests/{rid}/approve", "POST", data={"comment": "try"})
        check("X", "the decision form says the job is disabled, offers no Approve, and a POST approve is refused (D-55)",
              "This job is disabled. Enable it first, then approve." in t and approve == 0 and r.status_code >= 400
              and request_state(rid)[1] == "PENDING", status=r.status_code, approve_forms=approve)
        s, t = browser_decide("approver-1", rid, "reject", "disabled for maintenance")
        s.done()
        check("X", "Reject stays possible on a disabled job (D-55)", request_state(rid)[1] == "REJECTED")
    finally:
        api("admin", J(job) + "/enable", "POST")


def sec_B():
    st, rid, r = run_req("reqonly", JOB, "e2e-19 request without Item/Build")
    assert rid, (st, text_of(r.text)[:200])
    s = Session("approver-1")
    s.go(f"/batch-control/requests/{rid}/")
    t = s.text()
    s.shot("#main-panel", "R19-LIFE-B-01-lacks-build")
    s.done()
    check("B", "the request page tells the approver that the requester lacks Build permission (SPEC 6 D-38a)",
          "The requester does not have Build permission on this job." in t)
    m = mail_for("approver-1@e2e.local", rid, "New request awaiting your decision")
    body = mail_text(m["id"]) if m else ""
    check("B", "the approver's mail carries the same notice (SPEC 6 D-38a)", "does not have Build permission" in body, body=body[:300])
    lib.decide("reqonly", "requests", rid, "cancel")


def sec_F():
    reason = "=HYPERLINK(\"http://evil.example\",\"x\") e2e-19"
    st, rid, _ = run_req("requester", JOB, reason, approvers=("approver-1", "approver-2"))
    assert rid, st
    lib.decide("approver-2", "requests", rid, "reject", "@SUM(1+1) e2e-19")
    csv = api("approver-1", "/batch-control/history/requests.csv")
    import csv as _csv
    import io
    rows = list(_csv.reader(io.StringIO(csv.text)))
    head = rows[0] if rows else []
    mine = [r for r in rows if rid in ",".join(r)]
    cells = mine[0] if mine else []
    def col(name):
        return cells[head.index(name)] if name in head and len(cells) > head.index(name) else None
    formula_cells = [c for c in cells if c.lstrip("'").startswith(("=HYPERLINK", "@SUM"))]
    check("F", "requests.csv neutralises cells that start with = or @ with a ' prefix (SPEC 10 R-3)",
          csv.status_code == 200 and formula_cells and all(c.startswith("'") for c in formula_cells),
          cells=formula_cells)
    check("F", "requests.csv: the approver column holds the designated set joined by ';' and decidedBy is the last column (D-37)",
          head and head[-1] == "decidedBy" and col("approver") == "approver-1;approver-2" and col("decidedBy") == "approver-2",
          header=head, approver=col("approver"), decidedBy=col("decidedBy"))


def lead(minutes=None):
    """Arrangement (script console): notifyBeforeExpiryMinutes, set and saved when `minutes` is given, and
    pendingTimeoutHours; returns (lead minutes, pending timeout hours) afterwards."""
    out = gv("def cfg = io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get()\n" + (f"cfg.setNotifyBeforeExpiryMinutes({int(minutes)}); cfg.save()\n" if minutes else "")
             + "return cfg.notifyBeforeExpiryMinutes + ',' + cfg.pendingTimeoutHours")
    a, b = out.split(",")
    return int(a), int(b)


def expiring_mails(to, rid):
    return [m for m in mails(to) if "Pending request expires soon" in m["subject"] and rid in m["subject"]]


def sec_M():
    before, hours = lead()
    rids = []
    try:
        set_to, _ = lead(hours * 60 + 10)
        st, r1, _ = run_req("requester", JOB, "e2e-19 M11 pending reminder (first)")
        assert r1, st
        rids.append(r1)
        m1 = mail_for("requester@e2e.local", r1, "Pending request expires soon", timeout=150)
        st, r2, _ = run_req("requester", JOB, "e2e-19 M11 pending reminder (second, after the first reminder)")
        assert r2, st
        rids.append(r2)
        m2 = mail_for("requester@e2e.local", r2, "Pending request expires soon", timeout=150)
    finally:
        back = lead(before)
    body = mail_text(m1["id"]) if m1 else ""
    flagged = gv(f"""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/requests/run/{r1}.xml')
return new XmlSlurper().parse(f).expiringNotified.text()""")
    check("M", "M11 (SPEC 13 EXPIRING): with notifyBeforeExpiryMinutes above the pending timeout the requester gets the "
          "'Pending request expires soon' mail for a pending request, with the request id, job, requester and link, and "
          "the stored request is marked notified; the lead is put back",
          set_to == hours * 60 + 10 and m1 is not None and "Pending request expires soon" in body
          and f"Request: {r1} (run request)" in body and f"Job: {JOB}" in body and "Requester: requester" in body
          and re.search(r"^Link: http\S+/batch-control/requests/" + r1, body, re.M) and flagged == "true"
          and back == (before, hours), lead=set_to, pending_hours=hours, restored=back, before=before, flagged=flagged,
          subject=(m1 or {}).get("subject"), body=body[:300])
    # a later pass of the periodic work has run (the second request's reminder) with the first request still PENDING;
    # then both are cancelled: their cancel mails to the approver leave the single dispatch thread after anything that
    # pass queued
    cancels = {r: lib.decide("requester", "requests", r, "cancel") for r in rids}
    sentinels = {r: mail_for("approver-1@e2e.local", r, "Request cancelled", timeout=60) is not None for r in rids}
    counts = {r: len(expiring_mails("requester@e2e.local", r)) for r in rids}
    approver = {r: len(expiring_mails("approver-1@e2e.local", r)) for r in rids}
    check("M", "M11 (SPEC 13 'EXPIRING fires once for a pending request'): after a later pass of the periodic work (the "
          "second request's reminder) and the cancel mails of both, the requester holds exactly one reminder per request "
          "and the designated approver none", m2 is not None and all(sentinels.values()) and counts == {r: 1 for r in rids}
          and approver == {r: 0 for r in rids}, second=(m2 or {}).get("subject"), cancels=cancels, sentinels=sentinels,
          requester_reminders=counts, approver_reminders=approver)
    # INVALIDATED: the job of a pending request is deleted
    job = "r19-life-inv"
    lib.ensure_job(job, lib.job_xml("fs", shell="echo e2e-19 M11"))
    prop = lib.set_property(job, approval=True)
    st, rid, _ = run_req("requester", job, "e2e-19 M11 invalidated by the deletion")
    assert rid, st
    deleted = api("admin", J(job) + "/doDelete", "POST").status_code
    end = wait_until(lambda: request_state(rid)[1] == "INVALIDATED", 30, 2)
    m = mail_for("requester@e2e.local", rid, "Request invalidated")
    body = mail_text(m["id"]) if m else ""
    why = re.search(r"^Why it ended: (.+)$", body, re.M)
    check("M", "M11 (SPEC 13 INVALIDATED, D-54): the administrator deletes the job of a pending request: it is INVALIDATED "
          "and the requester gets 'Request invalidated' with the request id, the job and why it ended",
          "approvalRequired=true" in prop and deleted in (200, 302) and end and m is not None
          and f"Request: {rid} (run request)" in body and f"Job: {job}" in body and why is not None,
          deleted=deleted, status=request_state(rid)[1], subject=(m or {}).get("subject"), body=body[:400])


run_sections(WANT, {"F": sec_F, "V": sec_V, "D": sec_D, "C": sec_C, "S": sec_S, "E": sec_E, "X": sec_X, "B": sec_B, "M": sec_M})
