"""e2e-18: what changed in src/main after the e2e-17 build (bd449cd..d696d5d), checked in the browser.

usage: python final.py [KMDAOS] (D runs E)   rows: out/final.jsonl, shots: R18-*.png
Switches the global switches, revokes every window (change control off) and replaces the authorization strategy for a
moment (S restores it), so ci/shard.py runs it as a `last` unit. Run r18/arrange.py first.

K  Creation-time saves (D-76 (2), 59a716e): the administrator copies r18/src-a to r18/copy-b on the New Item page
   ("Copy from"). The copy writes CREATE and no CONFIGURE record (store and History page). A real change afterwards (the
   administrator saves the copy's configure page with another description) is recorded as CONFIGURE with a diff
   against the creation baseline: the description lines only.
M  Notices of windows near their end (D-75 (1) b459904, expiry notice isolation 46fe0b5): w18 holds three 15-minute
   CONFIGURE windows (approved in this order): r18/iso-a, r18/vis-y, r18/iso-b. The administrator moves r18/vis-y into
   r18-vault (Move page; w18 cannot read the vault). The change request file of the iso-a window is made unreadable
   (fault injected; precondition: the JVM cannot read it). The administrator raises "Notify before expiry" to 30 minutes
   on the configuration page, so the next run of the periodic work (every minute) owes all three GRANT_EXPIRING notices.
   w18 gets the notices of vis-y and iso-b (one unreadable request no longer stops the others). The vis-y notice names
   the approved name r18/vis-y with the fixed "moved; its new location is not visible to you" line and never r18-vault.
   iso-a gets none, and Jenkins logs why. Then the file is made readable and the setting put back to 1 minute.
D  A refused approval while change control is off (3a934c8, LIMITATIONS 29): w18 files a request for r18/blk;
   approver-1 opens its page (Approve form shown); the administrator turns change control off (run control stays on,
   so records are written); approver-1 presses Approve on the page opened before. The answer is HTTP 400, the request's
   own page "Grant request: r18/blk" (Wave D D1, #18.4: titled by its subject, the id in the ID row) with the
   explanation "Change control is off, so permission windows cannot be approved. ..." and no form, no stack trace. The request is still PENDING, no window exists, and a
   GRANT_REQUEST_BLOCKED record by approver-1 is listed on approver-1's History page. Control: a GET of the page while
   off is the closed screen. Then change control is turned on again.
E  The APPROVED notice of a normal approval is unchanged (bf3e473 adds a line only when the window ended at
   registration): approver-1 approves the same request on its page; w18's APPROVED mail has exactly the lines of the
   format before the change (title, Request, Scope, Item kind, Actions, Duration, Requester, Link, Reason) and no
   "Window:" line; the window confers Configure.
A  Change-recording baselines (D-76 (1), 14d4532, e66a852): the administrator turns both switches off on the
   configuration page; while recording is off the administrator changes the descriptions of r18/old-a and r18/old-b and
   deletes and re-creates r18/rec-c with another description; no record is written. Both switches are turned on again
   (every snapshot is refreshed). configurer (standing Configure) saves r18/old-b's configure page without a change:
   no record. configurer changes r18/old-a's description: one CONFIGURE record with a diff from the description set
   while recording was off to the new one (never the one from before), shown on the History page (approver-1) and with
   its diff on the Changes tab. configurer changes the re-created r18/rec-c: its diff starts from the re-created item,
   not from the deleted one.
O  Switches off (gap H2, SPEC item 1; r18/switches_off.py, which documents each check): with run control off the job
   page offers core's Build Now (classic and new job page) and no Batch Control run UI, and a build by the button or a
   direct POST runs; with change control off (run control on) no grant entry is offered on job and folder pages and
   core configure/delete work, while run control still gates. Both switches are on again at its end.
S  Strategy refusals as plain pages (8bfbe00, T-GAP-241): the administrator has the Batch Control configuration page
   open (Revert shown); in another browser context the administrator reverts (allowed). Pressing Revert on the page
   opened before answers HTTP 400 with "... is not a Batch Control strategy; there is nothing to revert." on core's
   plain error page, without a stack trace. With the plain matrix installed the /manage/ page offers "Install the Batch
   Control variant"; meanwhile the strategy becomes "Logged-in users can do anything" (script console, arrangement:
   another administrator's change on the Security page); pressing Install on the page opened before answers HTTP 400
   with "... has no Batch Control variant ..." and no stack trace. The original strategy object is put back."""
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, check, note, J, BASE, run_sections, revoke_all, grant_req, decide, st, exists, grant_xml,
                 log_since, now_millis, mails, mail_text, wait_mail, set_switches, switches, records_for, history_changes,
                 configure_save, flat, console_ok, U, H, STACK_MARKERS, CONFIG)  # noqa: E402

import switches_off  # noqa: E402  (section O, gap H2)

lib.LOGNAME[0] = "final"
WANT = sys.argv[1] if len(sys.argv) > 1 else "KMDAOS"
STATE = {}


def diff_lines(diff, sign):
    return [l[1:] for l in (diff or "").splitlines() if l.startswith(sign) and not l.startswith(sign * 3)]


def stack_free(text):
    return [m for m in STACK_MARKERS if m in text]


def ensure_job(full, description=None):
    if not exists(full):
        parent, _, name = full.rpartition("/")
        api("admin", (J(parent) if parent else "") + f"/createItem?name={name}&mode=hudson.model.FreeStyleProject", "POST", headers=H)
    if description is not None:
        api("admin", J(full) + "/submitDescription", "POST", data={"description": description})


def store_status(kind_dir, rid):
    """Status and the decision fields of a stored request (requests/<kind_dir>/<id>.xml), read as the controller."""
    return gv(f"""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/requests/{kind_dir}/{rid}.xml')
if (!f.exists()) return 'missing'
def x = new XmlSlurper().parse(f); return x.status.text() + '|' + x.decidedBy.text()""")


def grant_exists(gid):
    return gv(f"return new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/grants/{gid}.xml').exists()") == "true"


# ---------------------------------------------------------------- K
def sec_K():
    src, dst = "r18/src-a", "r18/copy-b"
    if exists(dst):
        api("admin", J(dst) + "/doDelete", "POST")
    ensure_job(src)
    # The History page shows seconds: let the DELETE of an earlier run's copy fall into an earlier second than t0.
    __import__("time").sleep(1.2)
    t0 = now_millis()
    s = Session("admin")
    s.go(J("r18") + "/newJob")
    s.page.locator("input#name").fill("copy-b")
    # Jenkins 2.568: "Duplicate an existing item" is an entry of the item type list; choosing it shows the "Copy from" field.
    dup = s.page.locator(".jenkins-choice-list__item", has_text="Duplicate an existing item").first
    dup.scroll_into_view_if_needed()
    dup.locator("label").first.click() if dup.locator("label").count() else dup.click()
    frm = s.page.locator("input#from").first
    frm.wait_for(state="visible")
    frm.fill("src-a")
    frm.press("Tab")
    s.page.wait_for_timeout(600)
    s.shot([s.page.locator("input#name").first, frm], "R18-K-1-new-item-copy")
    with s.page.expect_navigation() as nav:
        s.page.locator("#ok-button").first.click()
    s.page.wait_for_load_state("load")
    landing = s.page.url.replace(BASE, "")
    copy_status = nav.value.status if nav.value else None
    s.shot("#main-panel", "R18-K-2-copy-configure")
    recs = records_for(dst, t0)
    types = [r.get("type") for r in recs]
    check("K", "the copy through the New Item page (Copy from r18/src-a) creates r18/copy-b and lands on its configure page",
          exists(dst) and copy_status == 200 and landing.endswith("/job/r18/job/copy-b/configure"), status=copy_status, landing=landing)
    check("K", "the copy writes exactly one CREATE record (by admin) and no CONFIGURE record (D-76 (2): the saves made while the "
          "item is created are part of its CREATE)", types == ["CREATE"] and recs[0].get("user") == "admin",
          types=types, records=[{k: r.get(k) for k in ("type", "user", "detail")} for r in recs])
    rows, _ = history_changes("approver-1", dst, "K-3", since=t0)
    check("K", "approver-1's History page (change records of r18/copy-b) lists the CREATE by admin and no CONFIGURE",
          [r["type"] for r in rows] == ["CREATE"] and rows[0]["user"] == "admin", rows=rows)
    # A real change afterwards, on the configure page the copy landed on.
    s.page.locator("textarea[name=description]").first.fill("e2e-18 changed after the copy")
    with s.page.expect_navigation():
        s.page.locator("button[name=Submit]").first.click()
    s.page.wait_for_load_state("load")
    s.done()
    recs = records_for(dst, t0)
    conf = [r for r in recs if r.get("type") == "CONFIGURE"]
    diff = conf[-1].get("diff") if conf else ""
    added, removed = diff_lines(diff, "+"), diff_lines(diff, "-")
    check("K", "a real change after the creation (Save on the copy's configure page) is one CONFIGURE record by admin whose diff "
          "starts from the copied configuration (the creation baseline): the only removed line is the copied description",
          len(conf) == 1 and conf[0].get("user") == "admin" and any("e2e-18 changed after the copy" in l for l in added)
          and len(removed) == 1 and "e2e-18 v0 src-a" in removed[0],
          types=[r.get("type") for r in recs], added=added[:12], removed=removed[:5])
    note("K", "lines the configure form adds besides the description (the job was created by the script console, so the first "
         "browser save adds the installed plugins' job properties); core and plugin behaviour, recorded as part of the change",
         added_besides_description=[l.strip() for l in added if "description" not in l][:12])


# ---------------------------------------------------------------- M
def admin_move(item, dest, tag):
    s = Session("admin")
    r = s.go(J(item) + "/move/")
    status = None
    if r and r.status == 200:
        sel = s.page.locator("select[name=destination]").first
        val = [o.get_attribute("value") for o in sel.locator("option").all()
               if (o.get_attribute("value") or "").rstrip("/").endswith(dest)]
        sel.select_option(val[0] if val else "/" + dest)
        s.shot("#main-panel", f"R18-{tag}-move-page")
        with s.page.expect_navigation() as nav:
            s.page.locator("#main-panel button[name=Submit], #main-panel button.jenkins-button--primary, #main-panel input[type=submit]").first.click()
        status = nav.value.status if nav.value else None
    s.done()
    return (r.status if r else None), status


def readable(rel, on):
    return gv(f"""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/{rel}')
f.setReadable({'true' if on else 'false'}, false)
if (!{'true' if on else 'false'}) f.setReadable(false, true)
def can = true
try {{ new FileInputStream(f).close() }} catch (IOException e) {{ can = false }}
return "${{f.exists()}},${{can}}".toString()""")


def sec_M():
    revoke_all(U)
    if exists("r18-vault/vis-y"):
        api("admin", "/job/r18-vault/job/vis-y/doDelete", "POST")
    for n in ("r18/iso-a", "r18/vis-y", "r18/iso-b"):
        ensure_job(n)
    ids = {}
    for n in ("r18/iso-a", "r18/vis-y", "r18/iso-b"):
        r, gid = grant_req(U, n, ["CONFIGURE"], 15, reason=f"e2e-18 notice near the end: configure {n}")
        ids[n] = gid
        ids[n + ":approve"] = decide("approver-1", "grants", gid, "approve", "e2e-18")
    g_iso, g_vis, g_ok = ids["r18/iso-a"], ids["r18/vis-y"], ids["r18/iso-b"]
    page, moved = admin_move("r18/vis-y", "r18-vault", "M-1")
    g = grant_xml(g_vis)
    check("M", "precondition: three open 15-minute windows of w18 (approved iso-a, vis-y, iso-b in that order); the administrator "
          "moved r18/vis-y into r18-vault on the Move page and its window followed", all(ids[n + ":approve"] in (200, 302) for n in
          ("r18/iso-a", "r18/vis-y", "r18/iso-b")) and g.get("scope") == "r18-vault/vis-y" and not g.get("revokedAtMillis")
          and st(U, "/job/r18/job/iso-b/configure") == 200, ids=ids, move=(page, moved), stored=g)
    fault = readable(f"requests/grant/{g_iso}.xml", False)
    check("M", "precondition (fault injected): the change request file of the iso-a window exists and the JVM cannot read it",
          fault == "true,false", file=f"requests/grant/{g_iso}.xml", exists_readable=fault)
    t0 = now_millis()
    before = switches()
    try:
        saved = set_switches(True, True, "M-2-lead", extra={"notifyBeforeExpiryMinutes": 30})
        after = switches()
        subj = "Change window ends soon"
        got = {}
        t_wait = time.time()
        while time.time() - t_wait < 200 and not (got.get(g_vis) and got.get(g_ok)):
            for gid in (g_vis, g_ok):
                if not got.get(gid):
                    m = mails("w18@e2e.local", gid)
                    m = [x for x in m if subj in (x["Subject"] or "")]
                    if m:
                        got[gid] = (m[0], mail_text(m[0]["ID"]), round(time.time() - t_wait))
            time.sleep(3)
        iso_mail = [x for x in mails("w18@e2e.local", g_iso) if subj in (x["Subject"] or "")]
        logs = log_since(t0, ["cannot be read; the GRANT_EXPIRING"])
    finally:
        restored = readable(f"requests/grant/{g_iso}.xml", True)
        back = set_switches(True, True, "M-5-lead-back", extra={"notifyBeforeExpiryMinutes": int(before.get("notifyBeforeExpiryMinutes") or 1)})
    check("M", "the administrator raised 'Notify before expiry' to 30 minutes on the configuration page", saved == 200 and
          after.get("notifyBeforeExpiryMinutes") == "30", status=saved, before=before, after=after)
    vis = got.get(g_vis)
    ok = got.get(g_ok)
    check("M", "one unreadable change request no longer stops the other notices: w18 gets the GRANT_EXPIRING mails of the vis-y "
          "and iso-b windows (after the iso-a window, approved first) within the periodic work", bool(vis) and bool(ok),
          vis_seconds=vis[2] if vis else None, ok_seconds=ok[2] if ok else None)
    check("M", "the iso-a window gets no GRANT_EXPIRING mail, and Jenkins logs that its change request cannot be read",
          not iso_mail and any(g_iso in (row[2] if len(row) > 2 else "") for row in logs), mails=iso_mail[:2], log=logs[:3])
    if vis:
        m, body, _ = vis
        lines = [l for l in body.splitlines()]
        check("M", "D-75 (1) as the recipient: the vis-y notice names the approved name r18/vis-y with the fixed line \"The window's "
              "item was moved; its new location is not visible to you.\" and never r18-vault (subject and body)",
              "r18-vault" not in body and "r18-vault" not in (m["Subject"] or "") and "Scope: r18/vis-y" in lines
              and "The window's item was moved; its new location is not visible to you." in lines and "(r18/vis-y)" in (m["Subject"] or ""),
              subject=m["Subject"], body=body[:700])
    if ok:
        m, body, _ = ok
        check("M", "control: the iso-b notice names r18/iso-b and has no 'moved' line", "Scope: r18/iso-b" in body.splitlines()
              and "moved" not in body, subject=m["Subject"], body=body[:500])
    check("M", "after the check the file is readable again and the setting is back", restored == "true,true" and back == 200
          and switches().get("notifyBeforeExpiryMinutes") == str(before.get("notifyBeforeExpiryMinutes")), restored=restored, status=back)
    s = Session(U)
    s.go("/batch-control/grants/")
    html = s.page.content()
    s.shot("#main-panel", "R18-M-6-holder-grants")
    check("M", "w18's grants list (after the file is readable again) does not name r18-vault", "r18-vault" not in html)
    s.done()
    revoke_all(U)


# ---------------------------------------------------------------- D and E
def sec_D():
    revoke_all(U)
    ensure_job("r18/blk")
    sw = switches()
    if not (sw["run"] and sw["change"]):
        set_switches(True, True, "D-0-on")
    r, rid = grant_req(U, "r18/blk", ["CONFIGURE"], 15, reason="e2e-18 refused approval while change control is off")
    STATE["rid"] = rid
    s = Session("approver-1")
    s.go(f"/batch-control/grants/{rid}/")
    form = s.page.locator("form[name=approve]")
    shown = form.count()
    s.shot([s.page.locator("#main-panel table").first, form.first], "R18-D-1-request-page")
    off = set_switches(True, False, "D-2-change-off")
    t0 = now_millis()
    with s.page.expect_navigation() as nav:
        form.locator("button[name=Submit], button.jenkins-button--primary").first.click()
    s.page.wait_for_load_state("load")
    status = nav.value.status if nav.value else None
    body = s.page.locator("body").inner_text()
    text = flat(s.text())
    title = s.page.title()
    heading = flat(s.page.locator("#main-panel h1").first.inner_text()) if s.page.locator("#main-panel h1").count() else None
    forms = {n: s.page.locator(f"form[name={n}]").count() for n in ("approve", "reject", "changeApprover", "cancel")}
    s.shot("#main-panel", "R18-D-3-refused")
    console_ok("D", s, "approver-1's refused approval", expected=("400 POST",))
    s.done()
    check("D", "precondition: approver-1 had the request page with the Approve form open, then the administrator turned change "
          "control off on the configuration page (run control stays on)", shown == 1 and off == 200 and switches() ==
          {"run": True, "change": False, "notifyBeforeExpiryMinutes": sw["notifyBeforeExpiryMinutes"]}, form=shown, save=off)
    check("D", "pressing Approve answers HTTP 400 on the request's own page 'Grant request: r18/blk' (its id in the ID row) with "
          "the explanation \"Change control is off, so permission windows cannot be approved.\", no form and no stack trace",
          status == 400 and title.startswith("Grant request: r18/blk") and heading == "Grant request: r18/blk" and rid in text
          and "Change control is off, so permission windows cannot be approved."
          in text and not any(forms.values()) and not stack_free(body), status=status, title=title, heading=heading, forms=forms,
          stack=stack_free(body), text=text[:600])
    recs = [x for x in records_for("r18/blk", t0) if x.get("type") == "GRANT_REQUEST_BLOCKED"]
    stored = store_status("grant", rid)
    check("D", "server state: the request is still PENDING, no window exists, and one GRANT_REQUEST_BLOCKED record by approver-1 says "
          "the window could not be approved", stored.startswith("PENDING|") and not grant_exists(rid) and len(recs) == 1
          and recs[0].get("user") == "approver-1" and "could not be approved: change control is off" in (recs[0].get("detail") or ""),
          stored=stored, records=[{k: x.get(k) for k in ("type", "user", "detail")} for x in recs])
    rows, _ = history_changes("approver-1", "r18/blk", "D-4", since=t0)
    blocked = [x for x in rows if x["type"] == "GRANT_REQUEST_BLOCKED"]
    check("D", "approver-1's History page (change records of r18/blk) lists the GRANT_REQUEST_BLOCKED record by approver-1",
          len(blocked) == 1 and blocked[0]["user"] == "approver-1" and "could not be approved" in blocked[0]["detail"], rows=rows)
    s = Session("approver-1")
    r = s.go(f"/batch-control/grants/{rid}/")
    gstatus = r.status if r else None
    gtext = flat(s.text())
    gforms = s.page.locator("#main-panel form[name=approve], #main-panel form[name=reject]").count()
    s.shot("#main-panel", "R18-D-5-get-while-off")
    s.done()
    check("D", "control: a GET of the request page while change control is off shows the closed screen's explanation, no form",
          "Change control is off" in gtext and gforms == 0, status=gstatus, forms=gforms, text=gtext[:300])
    on = set_switches(True, True, "D-6-change-on")
    check("D", "change control is on again", on == 200 and switches()["change"] is True)
    sec_E()


def sec_E():
    rid = STATE.get("rid")
    if not rid:
        check("E", "a pending request from section D", False)
        return
    s = Session("approver-1")
    s.go(f"/batch-control/grants/{rid}/")
    form = s.page.locator("form[name=approve]")
    s.shot([s.page.locator("#main-panel table").first, form.first], "R18-E-1-request-page")
    with s.page.expect_navigation() as nav:
        form.locator("button[name=Submit], button.jenkins-button--primary").first.click()
    s.page.wait_for_load_state("load")
    status = nav.value.status if nav.value else None
    s.shot("#main-panel", "R18-E-2-approved")
    s.done()
    stored = store_status("grant", rid)
    conf = st(U, "/job/r18/job/blk/configure")
    check("E", "approver-1 approves the request on its page: APPROVED by approver-1, and the window confers Configure on r18/blk",
          status == 200 and stored == "APPROVED|approver-1" and conf == 200, status=status, stored=stored, configure=conf)
    m, body, secs = wait_mail("w18@e2e.local", rid, timeout=60)
    approved = [x for x in mails("w18@e2e.local", rid) if "Request approved" in (x["Subject"] or "")]
    if approved:
        m, body = approved[0], mail_text(approved[0]["ID"])
    lines = body.splitlines()
    reason = "e2e-18 refused approval while change control is off"
    expected = ["Request approved", "", f"Request: {rid} (change request)", "Scope: r18/blk", "Item kind: Freestyle project",
                "Actions: CONFIGURE", "Duration: 15 minutes", "Requester: w18"]
    link = next((l for l in lines if l.startswith("Link: ")), "")
    check("E", "w18's APPROVED mail has the format of the notice before bf3e473: subject, title, Request, Scope, Item kind, Actions, "
          "Duration, Requester, Link, Reason, and no 'Window:' line",
          bool(approved) and (m["Subject"] or "") == f"[Batch Control] Request approved: change request {rid} (r18/blk)"
          and lines[:len(expected)] == expected and lines[len(expected)].startswith("Link: ") and link.endswith(f"/batch-control/grants/{rid}/")
          and lines[len(expected) + 1:len(expected) + 3] == ["Reason:", f"> {reason}"] and not any(l.startswith("Window:") for l in lines),
          subject=m["Subject"] if m else None, body=body[:700])
    revoke_all(U)


# ---------------------------------------------------------------- A
def sec_A():
    a, b, c = "r18/old-a", "r18/old-b", "r18/rec-c"
    for n in (a, b, c):
        ensure_job(n)
    # Every configuration below is saved through the configure page, so that a later browser save adds nothing else (the
    # first browser save of a job created by the script console adds the installed plugins' job properties).
    pre = [configure_save("admin", a, "A-0a", description="e2e-18 v0 old-a"),
           configure_save("admin", b, "A-0b", description="e2e-18 v0 old-b"),
           configure_save("admin", c, "A-0c", description="e2e-18 v0 deleted rec-c")]
    off = set_switches(False, False, "A-1-off")
    sw_off = switches()
    t_off = now_millis()
    pre += [configure_save("admin", a, "A-1a", description="e2e-18 v1 changed while recording was off"),
            configure_save("admin", b, "A-1b", description="e2e-18 v1 changed while recording was off")]
    api("admin", J(c) + "/doDelete", "POST")
    api("admin", J("r18") + "/createItem?name=rec-c&mode=hudson.model.FreeStyleProject", "POST", headers=H)
    pre.append(configure_save("admin", c, "A-1c", description="e2e-18 re-created while recording was off"))
    written = [x for n in (a, b, c) for x in records_for(n, t_off)]
    # D-45 / S-13-10 (SPEC 6a): a job created while run control is off is recorded as activated at creation, with an
    # ACTIVATED record by "(uncontrolled)", whatever the change control switch says; it is not a change record of item 9.
    quiet = [x for x in written if not (x.get("type") == "ACTIVATED" and x.get("target") == c)]
    note("A", "records written while both switches were off (the ACTIVATED record of the job re-created then is D-45 behaviour)",
         written=[{k: x.get(k) for k in ("type", "target", "user", "detail")} for x in written])
    on = set_switches(True, True, "A-2-on")
    sw_on = switches()
    check("A", "precondition: the administrator turned both switches off on the configuration page, changed r18/old-a and r18/old-b "
          "and re-created r18/rec-c while recording was off (no record written), and turned both on again",
          off == 200 and on == 200 and not sw_off["run"] and not sw_off["change"] and sw_on["run"] and sw_on["change"] and not quiet
          and all(p == (200, 200) for p in pre), off=sw_off, on=sw_on, saves=pre,
          records_while_off=[(x.get("type"), x.get("target")) for x in quiet])
    t_on = now_millis()
    pb = configure_save("configurer", b, "A-3-nochange")
    rb = records_for(b, t_on)
    check("A", "configurer saves r18/old-b's configure page without a change: no record (the snapshot was refreshed when recording "
          "turned on, so the change made while it was off is not attributed to configurer)", pb == (200, 200) and not rb,
          configure=pb, records=[{k: x.get(k) for k in ("type", "user", "detail")} for x in rb])
    pa = configure_save("configurer", a, "A-4-change", description="e2e-18 v2 first change after recording turned on")
    ra = records_for(a, t_on)
    conf = [x for x in ra if x.get("type") == "CONFIGURE"]
    diff = conf[0].get("diff") if conf else ""
    added, removed = diff_lines(diff, "+"), diff_lines(diff, "-")
    check("A", "configurer's first change of r18/old-a after recording turned on is one CONFIGURE record by configurer with a diff "
          "from the description set while recording was off to the new one, and nothing from before",
          pa == (200, 200) and len(conf) == 1 and conf[0].get("user") == "configurer" and bool(diff)
          and any("e2e-18 v2 first change after recording turned on" in l for l in added)
          and any("e2e-18 v1 changed while recording was off" in l for l in removed) and "v0 old-a" not in diff
          and len(added) == 1 and len(removed) == 1 and "No diff" not in (conf[0].get("detail") or ""),
          configure=pa, types=[x.get("type") for x in ra], detail=conf[0].get("detail") if conf else None, added=added, removed=removed)
    rows, _ = history_changes("approver-1", a, "A-5", since=t_on)
    check("A", "approver-1's History page (change records of r18/old-a) lists that CONFIGURE by configurer",
          [x["type"] for x in rows].count("CONFIGURE") == 1 and any(x["type"] == "CONFIGURE" and x["user"] == "configurer" for x in rows),
          rows=rows)
    s = Session("approver-1")
    s.go("/batch-control/changes/")
    row = s.page.locator("#main-panel table tbody tr", has=s.page.locator("td:nth-child(2)", has_text=re.compile(r"^r18/old-a$"))).first
    shown = {}
    if row.count():
        summ = row.locator("details summary").first
        shown["summary"] = flat(summ.inner_text()) if summ.count() else None
        if summ.count():
            summ.click()
            s.page.wait_for_timeout(300)
        pre = row.locator("details pre").first
        shown["diff"] = pre.inner_text() if pre.count() else ""
        shown["cells"] = [flat(td.inner_text())[:80] for td in row.locator("td").all()[:4]]
        row.scroll_into_view_if_needed()
        s.shot(row, "R18-A-6-changes-diff")
    else:
        s.shot("#main-panel", "R18-A-6-changes-diff")
    s.done()
    check("A", "the Changes tab shows the record with its diff (summary, then the description lines when expanded)",
          bool(shown) and (shown.get("summary") or "").startswith("Diff")
          and "e2e-18 v2 first change after recording turned on" in shown.get("diff", "")
          and "e2e-18 v1 changed while recording was off" in shown.get("diff", ""), **shown)
    pc = configure_save("configurer", c, "A-7-recreated", description="e2e-18 v2 first change of the re-created item")
    rc = [x for x in records_for(c, t_on) if x.get("type") == "CONFIGURE"]
    dc = rc[0].get("diff") if rc else ""
    check("A", "configurer's change of the re-created r18/rec-c has a diff from the re-created item (its description set while "
          "recording was off), not from the deleted item's configuration",
          pc == (200, 200) and len(rc) == 1 and any("e2e-18 re-created while recording was off" in l for l in diff_lines(dc, "-"))
          and "deleted rec-c" not in dc and any("first change of the re-created item" in l for l in diff_lines(dc, "+")),
          configure=pc, added=diff_lines(dc, "+"), removed=diff_lines(dc, "-"))


# ---------------------------------------------------------------- S
def strategy():
    return gv("return jenkins.model.Jenkins.get().authorizationStrategy.getClass().name")


def posts(s):
    """Records the status of every POST the page makes (core's confirmationLink posts through a script-built form)."""
    seen = []
    s.page.on("response", lambda r: seen.append((r.status, r.url.replace(BASE, ""))) if r.request.method == "POST" else None)
    return seen


def status_of(seen, path):
    hits = [code for code, url in seen if url.split("?")[0].endswith(path)]
    return hits[-1] if hits else None


def confirm_ok(s):
    s.page.wait_for_timeout(700)
    with s.page.expect_navigation():
        s.page.locator("dialog[open] button[data-id=ok]").click()
    s.page.wait_for_load_state("load")


def reveal_revert(page):
    """Wave D D1 (#18.7): the revert entry sits inside the Advanced control at the end of the Batch Control section, so
    a person presses Advanced first. Returns the entry's locator."""
    rv = page.locator("a[data-url*='/revert']")
    if rv.count() and not rv.first.is_visible():
        page.locator("section:has([name='_.runControlEnabled']) button.advanced-button").last.click()
        rv.first.wait_for(state="visible", timeout=5000)
    return rv


def sec_S():
    original = strategy()
    stash = gv("""System.getProperties().put('e2e18.strategy', jenkins.model.Jenkins.get().authorizationStrategy); return 'ok'""")
    try:
        a = Session("admin")
        a_posts = posts(a)
        a.go(CONFIG)
        rv = reveal_revert(a.page)
        shown = rv.count()
        a.shot(rv.first if shown else "#main-panel", "R18-S-1-config-revert")
        # another administrator (own browser context) reverts on the same page
        b = Session("admin")
        b_posts = posts(b)
        b.go(CONFIG)
        reveal_revert(b.page).first.click()
        confirm_ok(b)
        b_status = status_of(b_posts, "/revert")
        b.done()
        plain = strategy()
        check("S", "precondition: Revert shown on the configuration page; the revert in another browser context installs the plain "
              "parent strategy", shown == 1 and b_status in (200, 302) and "batchcontrol" not in plain.lower(), original=original,
              after=plain, status=b_status)
        # the stale page: Revert again
        rv.first.click()
        confirm_ok(a)
        r_status = status_of(a_posts, "/revert")
        body = a.page.locator("body").inner_text()
        a.shot("#main-panel", "R18-S-2-revert-refused")
        check("S", "Revert on the page opened before answers HTTP 400 with \"The installed authorization strategy is not a Batch Control "
              "strategy; there is nothing to revert.\" on a plain error page, without a stack trace",
              r_status == 400 and "The installed authorization strategy is not a Batch Control strategy; there is nothing to revert." in flat(body)
              and not stack_free(body), status=r_status, stack=stack_free(body), text=flat(body)[:500])
        a.done()
        # migrate: /manage/ offers Install while the plain matrix is installed
        m = Session("admin")
        m_posts = posts(m)
        m.go("/manage/")
        inst = m.page.locator("#main-panel button, #main-panel a", has_text=re.compile("Install the Batch Control variant"))
        offered = inst.count()
        m.shot(inst.first if offered else "#main-panel", "R18-S-3-manage-install")
        gv("""def j = jenkins.model.Jenkins.get(); j.setAuthorizationStrategy(new hudson.security.FullControlOnceLoggedInAuthorizationStrategy()); j.save(); return 'ok'""")
        changed = strategy()
        if offered:
            inst.first.click()
            m.page.wait_for_timeout(800)
            if m.page.locator("dialog[open]").count():  # the global matrix asks first (D-35d (3)); the project matrix does not
                with m.page.expect_navigation():
                    m.page.locator("dialog[open] button[data-id=ok], dialog[open] button.jenkins-button--primary").first.click()
            m.page.wait_for_load_state("load")
        status = status_of(m_posts, "/migrate")
        body = m.page.locator("body").inner_text()
        m.shot("#main-panel", "R18-S-4-install-refused")
        m.done()
        check("S", "precondition: /manage/ offered 'Install the Batch Control variant' for the plain matrix; meanwhile the strategy became "
              "'Logged-in users can do anything'", offered >= 1 and changed == "hudson.security.FullControlOnceLoggedInAuthorizationStrategy",
              offered=offered, changed=changed)
        check("S", "Install on the page opened before answers HTTP 400 with \"... has no Batch Control variant, or its plugin is not "
              "installed ...\" on a plain error page, without a stack trace",
              status == 400 and "(hudson.security.FullControlOnceLoggedInAuthorizationStrategy) has no Batch Control variant, or its plugin is not installed."
              in flat(body) and not stack_free(body), status=status, stack=stack_free(body), text=flat(body)[:500])
    finally:
        back = gv("""def j = jenkins.model.Jenkins.get(); def s = System.getProperties().get('e2e18.strategy')
if (s == null) return 'none'
j.setAuthorizationStrategy(s); j.save(); System.getProperties().remove('e2e18.strategy'); return j.authorizationStrategy.getClass().name""")
    check("S", "the original strategy object is back (with every entry the arrangement added)", stash == "ok" and back == original
          and st("requester", "/job/batch-daily/batch-control/") == 200 and st(U, "/job/r18/job/blk/") == 200
          and st("configurer", "/job/r18/job/blk/configure") == 200, original=original, back=back)


# ------------------------------------------------- the move into the vault needs the vault folder (arrange.py)
if __name__ == "__main__":
    run_sections(WANT, {"K": sec_K, "M": sec_M, "D": sec_D, "E": sec_E, "A": sec_A, "O": switches_off.sec_O, "S": sec_S})
