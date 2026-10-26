"""e2e-27 D3 (Wave D, issue #18 items 5 and 1, issue #6; TEST-MATRIX T-UI-270..290, note 362): on the run request page
the Decision comes right after Parameters with ONE comment box and two buttons, Approve and Reject, on the unchanged
endpoints; the run tables (Run Dashboard, History > Runs) have 7 columns and the incident tables (Incidents list,
History > Incidents) 4, each with a Run cell holding core's status icon, the job link, the "#n" run link and the D2
result label as visible text, in that order; the Parameters cell folds and cuts; the Batch Control settings are grouped
under five headings on both configuration pages.

usage: python decision_tables.py [AOPJBREXTLICSDQZ]   rows: out/decision_tables.jsonl, shots: screenshots/run-27/R27-D3-*.png
Items (approval required): r27-d3-batch (DAY, MODE; succeeds), r27-d3-off (DAY; disabled in X), r27-d3-ten (P01..P10:
P01 holds a line break, P02 is 300 characters, a's then b's then z's, P03 is markup), r27-d3-fail (fails: an incident),
r27-d3-abort (sleeps; aborted by admin), and a job with a long name and twelve parameters for the screenshots
(r27-d3-settlement-reconciliation-for-the-regional-ledger-nightly). A run record of r27-d3-ghost#7 with the unknown
result WEIRD is appended once through the store's own method from the script console (arrangement: no job can produce
an unknown result). The requester files the requests over REST; approver-1 decides them in the browser; the server
state is read from the store and Jenkins REST. A check named "D3 ..." fails on a plugin without D3; "guard" holds on
both.

A  arrangement: the items; sweeps the requester's pending r27-d3 requests; one approved run each of r27-d3-batch,
   r27-d3-off, r27-d3-ten, r27-d3-fail, the long-named job (so the request pages have recent runs) and r27-d3-abort
   (stopped by admin while it runs); the ghost record once.
O  #18.5 (approver-1): with Approver Changes shown (the requester re-designated approver-1 and approver-2), the heading
   right after Parameters is Decision, before Recent Runs of This Job and Approver Changes; the Decision section has
   exactly one comment box and one Approve and one Reject button, posting to .../approve and .../reject; the form's own
   action is .../approve. Guards: GET of both endpoints is refused, a POST without the crumb answers 403, the requester
   and approver-unlisted are refused (4xx), and the request stays PENDING.
P  #18.5 approve through the one box (browser): the store says APPROVED/EXECUTED by approver-1 with that comment, the
   build runs (GET /job/r27-d3-batch/<n>/api/json 200) with the requested DAY and MODE.
J  #18.5 reject through the one box (browser): REJECTED by approver-1 with that comment; no build runs.
B  #18.5 a reject with only blanks in the box is refused: PENDING; the page still shows the one box holding the
   blanks, an error naming the comment next to it, and both buttons; the form's action now follows the refused verb
   (.../reject) while Approve and Reject keep their own targets.
E  #18.5 an approval refused by the service (a comment holding U+000B, a character that cannot be stored): PENDING;
   the page shows the one box with the typed text, the message next to it, both buttons, and the form's action is
   .../approve, the refused verb.
X  #18.5 / D-55 with r27-d3-off disabled: Decision right after Parameters, the page says disabled, only Reject is
   offered and the form posts to .../reject; Reject through the one box works. Guard: a direct POST approve is refused
   and the request stays PENDING.
T  #6/#18.1 (administrator) the Run Dashboard and History > Runs (job=r27-d3-): exactly the columns Run, Cause, User,
   Parameters, Started, Duration, Request id, every row 7 cells; the Run cell of r27-d3-ten and r27-d3-fail holds, in
   this order (DOM and left to right), core's status icon, the job link, the "#n" link to the run's own URL and the D2
   label ("Success", "Failed") as an element's visible whole text, nothing in it is a badge, and the raw result word is
   not in the row; the Cause cell reads "Approved request" (not a badge); the ghost's unknown result is text with no
   icon and no link; r27-d3-ten's Parameters cell shows P01..P03 with the other seven in one <details> "7 more", P02 cut
   at 120 characters ("P02=" + 100 a's + 20 b's + "…"), P01's line break shown as one line, P03's markup as text;
   r27-d3-abort's Run cell says "Aborted by admin" on a line of its own below the run, with no Aborted by column; the
   Cause, User, Started and Duration cells of r27-d3-ten stay on one line at 1280 px. Guards: the full P02 and P01 (with
   its line break) in runs.csv, on the request page and on the build's parameters page.
L  #6 link rule (D-44): auditor (ViewHistory, no Item/Read) gets r27-d3-fail's row with the job name and "#n" as text
   and no link to the job or the run on the four tables; approver-1 (Item/Read) gets the job and run links.
I  #6 (administrator) the Incidents list and History > Incidents: exactly the columns Run, Incident, Status, Created;
   the Run cell of r27-d3-fail's incident as in T ("Failed"); the Status cell is the incident's badge (OPEN, tone
   danger, "Open").
C  #18.1 the Run cell with a long job name (administrator, History > Runs, 1280 and 768 px): every part stays inside the
   cell; a NOTE says how many lines it takes, the icon's size and the cell's width.
S  #6 the Run cell's text and layout (T-UI-291, owner decisions of 2026-10-15): its text content, white space collapsed,
   reads exactly "<job> #<n> <label>" (the aborted run adds " Aborted by admin") on the four tables, for admin (links)
   and auditor (no links), and for runs that have no link for anyone (the WEIRD record; r27-d3-gone, deleted after its
   failed run: its run and incident stay). At 1280 and 768 px (admin, History > Runs and the Incidents list): the short
   names render the job, "#n" and the label on one line, separated by one space's width, with "#n" and the label each
   whole; the 58-character name wraps inside the Run column, whose content is the cell's minimum width (16em) and no
   wider, and nothing overflows the cell (the widths are in the row).
D  #18.5 the space under the decision buttons (owner decision of 2026-10-15): the gap between the Approve/Reject row and
   the next heading equals the gap between the Parameters table and the Decision heading, pending, refused and with
   the job disabled, in the light and the dark theme (approver-1).
Q  #18 global configuration (owner decisions of 2026-10-14, grouping 2026-10-15): in the Batch Control section of
   Manage Jenkins > System and of the Batch Control configuration page (admin) and of the latter (manager, BatchControl
   /Manage): exactly five group headings, in order Switches, Approval, Permission windows, Incidents and retention,
   Notifications and strategy, each followed by its fields (names unchanged), the fifth by the e-mail switch and the
   "Batch Control strategy" entry (admin: inside Advanced with the revert; manager: the strategy is installed and only a
   Jenkins administrator can change it). The group headings are visibly smaller than the section's title (computed font
   size; NOTE). Save round-trip through the real form: with every setting at a value other than the default the
   administrator saves both pages unchanged and the manager the Batch Control page, and every value reads back as it
   was; then the administrator changes one field in each group but Switches on the Batch Control page and the store
   holds the new values. With the plain strategy installed the fifth group holds only the e-mail switch. Everything is
   put back.
Z  screenshots for the owner (light and dark theme, 1280 and 768 px): the dashboard, History > Runs with the long job
   name and its parameters, the Incidents list, the Decision area pending, refused and with the job disabled, and the
   global configuration as admin and as manager.
cleanup: r27-d3-off enabled again; the settings and the strategy as they were; the accounts' theme back to the default;
the requester's pending r27-d3 requests cancelled."""
import json
import re
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, Session, norm  # noqa: E402

lib.LOGNAME[0] = "decision_tables"
P = "r27-d3-"
BATCH, OFF, TEN, FAIL, ABORT, GHOST, GONE = (P + x for x in ("batch", "off", "ten", "fail", "abort", "ghost", "gone"))
LONGJOB = P + "settlement-reconciliation-for-the-regional-ledger-nightly"
COLUMNS = ["Run", "Cause", "User", "Parameters", "Started", "Duration", "Request id"]
INCIDENT_COLUMNS = ["Run", "Incident", "Status", "Created"]
LABEL = {"SUCCESS": "Success", "UNSTABLE": "Unstable", "FAILURE": "Failed", "NOT_BUILT": "Not built", "ABORTED": "Aborted"}
LONG = "a" * 100 + "b" * 100 + "z" * 100
CUT = "P02=" + "a" * 100 + "b" * 20 + "…"
TWO_LINES = "first line\nsecond line"
MARKUP = "<img src=x onerror=alert(1)>"
TEN_VALUES = {f"P{i:02d}": (TWO_LINES if i == 1 else LONG if i == 2 else MARKUP if i == 3 else f"v{i}") for i in range(1, 11)}
LONG_VALUES = {k: f"{k.lower()}-value-{i}" for i, k in enumerate(("DAY", "MODE", "REGION", "LEDGER", "BATCH_SIZE", "CURRENCY",
                                                                   "CUTOFF", "RETRIES", "NOTIFY", "OWNER", "WINDOW", "TAG"), 1)}
GROUPS = ["Switches", "Approval", "Permission windows", "Incidents and retention", "Notifications and strategy"]
GROUP_FIELDS = [["_.runControlEnabled", "_.changeControlEnabled"],
                ["_.approversText", "_.allowAdminSelfApproval", "_.pendingTimeoutHours", "_.approvedRunTimeoutMinutes"],
                ["_.grantDurationOptions", "_.maxGrantMinutes", "_.notifyBeforeExpiryMinutes"],
                ["_.incidentResults", "_.retentionMonths"],
                ["_.emailNotifications"]]
ASK_ADMIN = "Only a Jenkins administrator can change the authorization strategy."
INSTALLED = "A Batch Control authorization strategy is installed."
REVERTING = "Reverting stops active grants"
CONFIGS = (("/manage/configure", "system"), ("/manage/batch-control-configuration/", "bc-config"))
S = {}

# The Decision section, read without assuming one form or two: everything whose nearest preceding h2 reads "Decision".
DECISION_JS = """() => { const out = {headings: [], boxes: [], buttons: [], forms: []}; let h = null;
  const w = document.createTreeWalker(document.querySelector('#main-panel'), NodeFilter.SHOW_ELEMENT);
  for (let n = w.currentNode; n; n = w.nextNode()) {
    if (n.tagName === 'H2') { h = n.textContent.trim(); out.headings.push(h); continue; }
    if (h !== 'Decision') continue;
    if (n.tagName === 'FORM') out.forms.push({name: n.getAttribute('name'), action: n.getAttribute('action') ? new URL(n.getAttribute('action'), location.href).pathname : null});
    if ((n.tagName === 'TEXTAREA' || n.tagName === 'INPUT') && n.name === 'comment' && n.type !== 'hidden')
      out.boxes.push({tag: n.tagName.toLowerCase(), value: n.value, visible: !!n.getClientRects().length});
    if (n.tagName === 'BUTTON' || (n.tagName === 'INPUT' && n.type === 'submit')) {
      const t = (n.tagName === 'INPUT' ? n.value : n.textContent).trim();
      const action = n.getAttribute('formaction') || (n.form ? n.form.getAttribute('action') : null);
      out.buttons.push({text: t, target: action ? new URL(action, location.href).pathname : null,
                        visible: !!n.getClientRects().length}); } }
  return out; }"""
# Marks the Decision section's comment box and buttons so Playwright can type and click like a person.
MARK_JS = """() => { let h = null; let i = 0;
  const w = document.createTreeWalker(document.querySelector('#main-panel'), NodeFilter.SHOW_ELEMENT);
  for (let n = w.currentNode; n; n = w.nextNode()) {
    if (n.tagName === 'H2') { h = n.textContent.trim(); continue; }
    if (h !== 'Decision') continue;
    if ((n.tagName === 'TEXTAREA' || n.tagName === 'INPUT') && n.name === 'comment' && n.type !== 'hidden')
      n.setAttribute('data-r27-box', String(i++));
    if (n.tagName === 'BUTTON' || (n.tagName === 'INPUT' && n.type === 'submit'))
      n.setAttribute('data-r27-btn', (n.tagName === 'INPUT' ? n.value : n.textContent).trim().toLowerCase()); } }"""
ERROR_JS = """(word) => { const box = document.querySelector('[data-r27-box="0"]'); if (!box) return null;
  const near = [box.parentElement, box.parentElement && box.parentElement.parentElement].filter(Boolean);
  const errs = [...document.querySelectorAll('[data-batch-control-field-error="comment"], .error, [role=alert]')]
    .filter(e => new RegExp(word, 'i').test(e.textContent) && near.some(p => p.contains(e)));
  return errs.map(e => ({text: e.textContent.replace(/\\s+/g, ' ').trim().slice(0, 160), visible: !!e.getClientRects().length})); }"""


def run_once(job, params=None, after=None):
    n = lib.next_build(job)
    st, rid = lib.submit_run("requester", job, f"e2e-27 D3 {job}", params=params)
    dec = lib.decide("approver-1", "requests", rid, "approve", "e2e-27 D3") if rid else None
    if after:
        after(n)
    result = lib.wait_build(job, n, timeout=240)[0]
    return {"id": rid, "submit": st, "approve": dec, "number": n, "result": result}


def stop_when_running(job):
    def stop(n):
        lib.wait_until(lambda: (api("admin", f"{lib.jpath(job)}/{n}/api/json?tree=building").json() or {}).get("building")
                       if api("admin", f"{lib.jpath(job)}/{n}/api/json").status_code == 200 else False, 120, 1)
        S["stop"] = api("admin", f"{lib.jpath(job)}/{n}/stop", "POST").status_code
    return stop


def sec_A():
    two = lib.job_xml("fs", shell='echo "DAY=$DAY MODE=$MODE"',
                      params=lib.string_p("DAY", "2026-10-01") + lib.string_p("MODE", "full"))
    ten = lib.job_xml("fs", shell="echo e2e-27 D3 ten", params="".join(lib.string_p(k, "d") for k in TEN_VALUES))
    many = lib.job_xml("fs", shell="echo e2e-27 D3 long name", params="".join(lib.string_p(k, "d") for k in LONG_VALUES))
    created = {n: lib.ensure_item(n, x) for n, x in ((BATCH, two), (OFF, two), (TEN, ten), (LONGJOB, many),
                                                     (FAIL, lib.job_xml("fs", shell="echo e2e-27 D3; exit 1")),
                                                     (ABORT, lib.job_xml("fs", shell="sleep 300")))}
    props = {n: lib.set_property(n, approval=True) for n in (BATCH, OFF, TEN, LONGJOB, FAIL, ABORT)}
    S["enable"] = api("admin", lib.jpath(OFF) + "/enable", "POST").status_code
    S["swept"] = lib.sweep("requester", P)
    S["batch"] = run_once(BATCH, {"DAY": "2026-10-02", "MODE": "full"})
    S["off"] = run_once(OFF, {"DAY": "2026-10-02"})
    S["ten"] = run_once(TEN, TEN_VALUES)
    S["long"] = run_once(LONGJOB, LONG_VALUES)
    S["fail"] = run_once(FAIL)
    S["abort"] = run_once(ABORT, after=stop_when_running(ABORT))
    # a failed run (and its incident) whose job is deleted afterwards: the record stays, without links for anyone
    if api("admin", lib.jpath(GONE) + "/api/json").status_code == 200:
        api("admin", lib.jpath(GONE) + "/doDelete", "POST")
    lib.ensure_item(GONE, lib.job_xml("fs", shell="echo e2e-27 D3 gone; exit 1"))
    lib.set_property(GONE, approval=True)
    S["gone"] = run_once(GONE)
    S["gone"]["deleted"] = api("admin", lib.jpath(GONE) + "/doDelete", "POST").status_code
    ghost = lib.gv(f"""def csv = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/runs')
def seen = csv.exists() && csv.listFiles().any {{ it.isFile() && it.getText('UTF-8').contains('{GHOST}#7') }}
if (seen) return 'present'
{lib.BC}.store.FileStore.get().appendRunRecord(new {lib.BC}.model.RunRecord('{GHOST}#7', '{GHOST}', 7,
  {lib.BC}.model.CauseType.OTHER, 'WEIRD', {lib.BC}.store.BatchClock.clock().instant(), 10L))
return 'appended'""")
    ok = (all(p.startswith("approvalRequired=true") for p in props.values())
          and [S[k]["result"] for k in ("batch", "off", "ten", "long", "fail", "abort", "gone")]
          == ["SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS", "FAILURE", "ABORTED", "FAILURE"] and ghost in ("present", "appended")
          and S["gone"]["deleted"] in (200, 302) and api("admin", lib.jpath(GONE) + "/api/json").status_code == 404)
    check("A", "arrangement: one approved run each of r27-d3-batch, -off, -ten, the long-named job (SUCCESS), -fail "
          "(FAILURE), -abort (ABORTED by admin) and -gone (FAILURE, then the job deleted); the WEIRD run record of "
          "r27-d3-ghost#7", ok, created=created,
          swept=S["swept"], runs={k: S[k] for k in ("batch", "off", "ten", "long", "fail", "abort", "gone")}, stop=S.get("stop"),
          ghost=ghost, enable=S["enable"])


def decision(s):
    return s.page.evaluate(DECISION_JS)


def one_box(sec, d, who):
    return check(sec, f"D3 #18.5 the Decision section has ONE comment box ({who})", len(d["boxes"]) == 1,
                 boxes=d["boxes"], headings=d["headings"])


def press(s, caption, comment):
    """Types `comment` into the Decision section's one comment box and presses the button captioned `caption`."""
    s.page.evaluate(MARK_JS)
    s.page.locator("[data-r27-box='0']").fill(comment)
    btn = s.page.locator(f"[data-r27-btn='{caption.lower()}']").first
    btn.scroll_into_view_if_needed()
    try:  # a form submission always navigates (to the request page after a decision, or the refusal's page)
        with s.page.expect_navigation(timeout=20000) as nav:
            btn.click()
            # #93: Reject asks for confirmation first (skipped for a blank comment, which the
            # server refuses anyway); confirm core's dialog so the form posts.
            if caption.lower() == "reject" and comment.strip() and lib.opened(s.page, timeout=5000):
                s.page.locator("dialog[open] button[data-id=ok]").first.click()
        return f"navigated ({nav.value.status if nav.value else None})"
    except Exception as e:  # noqa: nothing navigated
        return None if "Timeout" in repr(e) else f"error: {e!r}"[:120]


def targets(rid):
    base = f"{lib.PREFIX}/batch-control/requests/{rid}/"
    return base + "approve", base + "reject"


def sec_O():
    st, rid = lib.submit_run("requester", BATCH, "e2e-27 D3 order", params={"DAY": "2026-10-09", "MODE": "delta"})
    chg = api("requester", f"/batch-control/requests/{rid}/changeApprover", "POST",
              data=[("approvers", "approver-1"), ("approvers", "approver-2")]).status_code
    S["order"] = rid
    s = Session("approver-1")
    s.go(f"/batch-control/requests/{rid}/")
    d = decision(s)
    lib.page_shot(s, "R27-D3-O-01-request-page-approver-1")
    s.done()
    h = d["headings"]
    idx = {k: (h.index(k) if k in h else -1) for k in ("Parameters", "Decision", "Recent Runs of This Job",
                                                        "Approver Changes")}
    check("O", "guard: the request page shows Parameters, Recent Runs of This Job and Approver Changes (the requester "
          "re-designated the approvers)", min(idx["Parameters"], idx["Recent Runs of This Job"],
                                              idx["Approver Changes"]) >= 0, headings=h, change=chg)
    check("O", "D3 #18.5 the heading right after Parameters is Decision, before Recent Runs of This Job and Approver "
          "Changes", idx["Decision"] == idx["Parameters"] + 1 and idx["Decision"] < idx["Recent Runs of This Job"]
          and idx["Decision"] < idx["Approver Changes"], headings=h)
    one_box("O", d, "approver-1")
    caps = [b["text"].lower() for b in d["buttons"]]
    approve, reject = targets(rid)
    tgt = {b["text"].lower(): b["target"] for b in d["buttons"]}
    check("O", "D3 #18.5 the Decision section offers one Approve and one Reject button in one form, posting to the "
          "existing .../approve and .../reject endpoints; the form's own action is .../approve",
          caps.count("approve") == 1 and caps.count("reject") == 1 and tgt.get("approve") == approve
          and tgt.get("reject") == reject and len(d["forms"]) == 1 and d["forms"][0]["action"] == approve,
          buttons=d["buttons"], forms=d["forms"])
    codes = {}
    for verb in ("approve", "reject"):
        codes[f"GET {verb}"] = api("approver-1", f"/batch-control/requests/{rid}/{verb}").status_code
        codes[f"POST {verb} no crumb"] = api("approver-1", f"/batch-control/requests/{rid}/{verb}", "POST", crumb=False,
                                             data={"comment": "no crumb"}).status_code
        for who in ("requester", "approver-unlisted"):
            codes[f"{who} {verb}"] = lib.decide(who, "requests", rid, verb, "not mine")
    state = lib.stored("run", rid).get("status")
    check("O", "guard: GET approve/reject refused, a POST without the crumb answers 403, the requester and "
          "approver-unlisted are refused (4xx); the request stays PENDING",
          all(v >= 400 for k, v in codes.items() if k.startswith("GET")) and all(
              v == 403 for k, v in codes.items() if "no crumb" in k) and all(
              400 <= v < 500 for k, v in codes.items() if k.split()[0] in ("requester", "approver-unlisted"))
          and state == "PENDING", codes=codes, state=state)


def sec_P():
    rid = S["order"]
    n = lib.next_build(BATCH)
    s = Session("approver-1")
    s.go(f"/batch-control/requests/{rid}/")
    d = decision(s)
    if one_box("P", d, "approver-1"):
        how = press(s, "Approve", "e2e-27 D3 ship it")
        s.page.wait_for_load_state("load")
        lib.page_shot(s, "R27-D3-P-01-after-approve")
        landed = s.page.url
    else:
        how = landed = None
    s.done()
    st = lib.stored("run", rid)
    result, _ = lib.wait_build(BATCH, n, timeout=180) if st.get("status") in ("APPROVED", "EXECUTED") else (None, None)
    b = api("admin", f"{lib.jpath(BATCH)}/{n}/api/json?tree=result,actions[parameters[name,value]]")
    params = {p["name"]: p["value"] for a in (b.json().get("actions", []) if b.status_code == 200 else [])
              for p in (a or {}).get("parameters", []) or []}
    check("P", "D3 #18.5 approve through the one box: the store says APPROVED/EXECUTED by approver-1 with the typed "
          "comment", st.get("status") in ("APPROVED", "EXECUTED") and st.get("decidedBy") == "approver-1"
          and st.get("decisionComment") == "e2e-27 D3 ship it", stored=st, reaction=how, landed=landed)
    check("P", "D3 #18.5 the approved run executed: GET /job/r27-d3-batch/<n>/api/json answers 200, SUCCESS, with the "
          "requested DAY and MODE", b.status_code == 200 and result == "SUCCESS"
          and params.get("DAY") == "2026-10-09" and params.get("MODE") == "delta", build=n, status=b.status_code,
          result=result, params=params)


def sec_J():
    st, rid = lib.submit_run("requester", BATCH, "e2e-27 D3 reject", params={"DAY": "2026-10-09", "MODE": "delta"})
    n = lib.next_build(BATCH)
    s = Session("approver-1")
    s.go(f"/batch-control/requests/{rid}/")
    d = decision(s)
    how = None
    if one_box("J", d, "approver-1"):
        how = press(s, "Reject", "e2e-27 D3 wrong day")
        s.page.wait_for_load_state("load")
        lib.page_shot(s, "R27-D3-J-01-after-reject")
    s.done()
    stored = lib.stored("run", rid)
    lib.wait_until(lambda: not lib.L25.queue_items(BATCH), 10, 1)
    check("J", "D3 #18.5 reject through the one box: REJECTED by approver-1 with the typed comment, and no build runs",
          stored.get("status") == "REJECTED" and stored.get("decidedBy") == "approver-1"
          and stored.get("decisionComment") == "e2e-27 D3 wrong day" and lib.next_build(BATCH) == n,
          stored=stored, reaction=how, next_build=lib.next_build(BATCH), expected_next=n)


def refused(sec, rid, caption, comment, word, verb, shot):
    """Presses `caption` with `comment` in the one box, expects a refusal re-rendered on the request page. Returns the
    Decision section after it, the errors next to the box naming `word`, and whether the page has its chrome."""
    s = Session("approver-1")
    s.go(f"/batch-control/requests/{rid}/")
    d = decision(s)
    after, errors, chrome, how, url = None, None, None, None, None
    if one_box(sec, d, "approver-1"):
        how = press(s, caption, comment)
        s.page.wait_for_load_state("load")
        s.page.evaluate(MARK_JS)
        after = decision(s)
        errors = s.page.evaluate(ERROR_JS, word)
        chrome = s.page.locator("#main-panel").count() and s.page.locator(".jenkins-breadcrumbs__list-item").count()
        url = s.page.url.replace(lib.BASE, "")
        lib.page_shot(s, f"R27-D3-{sec}-01-{shot}")
        s.shot("[data-r27-box='0']", f"R27-D3-{sec}-02-{shot}-box", pad=120)
        S.setdefault("refused_pages", {})[verb] = (rid, comment)
    s.done()
    return after, errors, chrome, how, url


def sec_B():
    st, rid = lib.submit_run("requester", BATCH, "e2e-27 D3 blank reject", params={"DAY": "2026-10-09", "MODE": "x"})
    after, errors, chrome, how, url = refused("B", rid, "Reject", "   ", "comment", "reject", "blank-reject-refused")
    stored = lib.stored("run", rid)
    check("B", "D3 #18.5 a reject with only blanks is refused: the store keeps PENDING", stored.get("status") == "PENDING"
          and how is not None, stored=stored, reaction=how, url=url)
    caps = [b["text"].lower() for b in (after or {}).get("buttons", [])]
    check("B", "D3 #18.5 after the refused reject the page (not a bare error page) still shows the one box holding the "
          "blanks, and both Approve and Reject", bool(chrome) and after is not None and len(after["boxes"]) == 1
          and after["boxes"][0]["value"] == "   " and "approve" in caps and "reject" in caps, after=after,
          page_chrome=chrome)
    check("B", "D3 #18.5 the error naming the comment is shown next to the box", bool(errors)
          and any(e["visible"] for e in errors), errors=errors)
    approve, reject = targets(rid)
    tgt = {b["text"].lower(): b["target"] for b in (after or {}).get("buttons", [])}
    check("B", "D3 #18.5 after the refused reject the form's action follows the refused verb (.../reject), and Approve "
          "and Reject still post to .../approve and .../reject", after is not None and len(after["forms"]) == 1
          and after["forms"][0]["action"] == reject and tgt.get("approve") == approve and tgt.get("reject") == reject,
          forms=(after or {}).get("forms"), buttons=(after or {}).get("buttons"))


def sec_R():
    """#93 (owner review of PR #102): rejecting a run request asks for confirmation first, in a real browser."""
    st, rid = lib.submit_run("requester", BATCH, "e2e-27 R reject", params={"DAY": "2026-10-09", "MODE": "delta"})
    s = Session("approver-1")
    s.go(f"/batch-control/requests/{rid}/")
    s.page.evaluate(MARK_JS)
    s.page.locator("[data-r27-box='0']").fill("e2e-27 R wrong day")
    btn = s.page.locator("[data-r27-btn='reject']").first
    btn.scroll_into_view_if_needed()
    posts = []
    s.page.on("response", lambda r: posts.append(r.url) if r.request.method == "POST" else None)
    btn.click()
    opened = lib.opened(s.page, timeout=8000)
    dlg_text = norm(s.page.locator("dialog[open]").first.inner_text()) if opened else None
    lib.page_shot(s, "R27-R-01-reject-confirmation")
    check("R", "#93 pressing Reject opens core's confirmation carrying the page's sentence",
          opened and "Rejection is final for this request" in (dlg_text or ""), dialog=dlg_text)
    closed = False
    if opened:
        s.page.locator("dialog[open] button[data-id=cancel]").first.click()
        closed = lib.gone(s.page)
    check("R", "#93 Cancel posts nothing and the request stays PENDING",
          closed and not posts and lib.stored("run", rid).get("status") == "PENDING",
          closed=closed, posts=list(posts), stored=lib.stored("run", rid).get("status"))
    btn.click()
    opened2 = lib.opened(s.page, timeout=8000)
    closed2 = False
    if opened2:
        s.page.keyboard.press("Escape")
        closed2 = lib.gone(s.page)
    check("R", "#93 Escape posts nothing and the request stays PENDING",
          closed2 and not posts and lib.stored("run", rid).get("status") == "PENDING",
          closed=closed2, posts=list(posts), stored=lib.stored("run", rid).get("status"))
    btn.click()
    opened3 = lib.opened(s.page, timeout=8000)
    if opened3:
        with s.page.expect_navigation(timeout=20000):
            s.page.locator("dialog[open] button[data-id=ok]").first.click()
        s.page.wait_for_load_state("load")
    stored = lib.stored("run", rid)
    check("R", "#93 confirming the dialog rejects the request with the typed comment",
          stored.get("status") == "REJECTED" and stored.get("decidedBy") == "approver-1"
          and stored.get("decisionComment") == "e2e-27 R wrong day", stored=stored)
    s.done()
    st2, rid2 = lib.submit_run("requester", BATCH, "e2e-27 R approve", params={"DAY": "2026-10-09", "MODE": "delta"})
    s2 = Session("approver-1")
    s2.go(f"/batch-control/requests/{rid2}/")
    s2.page.evaluate(MARK_JS)
    s2.page.locator("[data-r27-box='0']").fill("e2e-27 R ok")
    abtn = s2.page.locator("[data-r27-btn='approve']").first
    abtn.scroll_into_view_if_needed()
    with s2.page.expect_navigation(timeout=20000):
        abtn.click()
    s2.page.wait_for_load_state("load")
    stored2 = lib.stored("run", rid2)
    # The completed expect_navigation already proves no dialog blocked the post.
    # EXECUTED is accepted because the approved run starts right away.
    check("R", "#93 Approve still posts with a single click (no confirmation)",
          stored2.get("status") in ("APPROVED", "EXECUTED") and stored2.get("decidedBy") == "approver-1"
          and stored2.get("decisionComment") == "e2e-27 R ok", stored=stored2)
    s2.done()


def sec_E():
    st, rid = lib.submit_run("requester", BATCH, "e2e-27 D3 refused approval", params={"DAY": "2026-10-09", "MODE": "y"})
    typed = "e2e-27 D3 vertical tab \u000b here"
    rest = lib.decide("approver-1", "requests", rid, "approve", typed)
    check("E", "guard: an approval whose comment holds U+000B (a character that cannot be stored) is refused (REST, 4xx): "
          "the store keeps PENDING", rest is not None and 400 <= rest < 500 and lib.stored("run", rid).get("status") == "PENDING",
          http=rest, stored=lib.stored("run", rid))
    after, errors, chrome, how, url = refused("E", rid, "Approve", typed, "comment", "approve", "approve-refused")
    stored = lib.stored("run", rid)
    approve, reject = targets(rid)
    tgt = {b["text"].lower(): b["target"] for b in (after or {}).get("buttons", [])}
    check("E", "D3 #18.5 after the refused approval the page shows the one box with the typed text, the message about "
          "the comment next to it, Approve and Reject, and the form's action is the refused verb (.../approve)",
          stored.get("status") == "PENDING" and bool(chrome) and after is not None and len(after["boxes"]) == 1
          and after["boxes"][0]["value"] in (typed, typed.replace("\u000b", "\ufffd"))
          and bool(errors) and len(after["forms"]) == 1 and after["forms"][0]["action"] == approve
          and tgt.get("approve") == approve and tgt.get("reject") == reject, after=after, errors=errors)


def sec_X():
    api("admin", lib.jpath(OFF) + "/enable", "POST")
    st, rid = lib.submit_run("requester", OFF, "e2e-27 D3 disabled job", params={"DAY": "2026-10-09"})
    dis = api("admin", lib.jpath(OFF) + "/disable", "POST").status_code
    direct = lib.decide("approver-1", "requests", rid, "approve", "e2e-27 D3 direct")
    state = lib.stored("run", rid).get("status")
    check("X", "guard: approving while the job is disabled is refused (4xx) and the request stays PENDING (D-55)",
          direct is not None and 400 <= direct < 500 and state == "PENDING", direct=direct, state=state, disable=dis)
    S["disabled_rid"] = rid
    s = Session("approver-1")
    s.go(f"/batch-control/requests/{rid}/")
    d = decision(s)
    text = s.text()
    lib.page_shot(s, "R27-D3-X-01-disabled-job-decision")
    h = d["headings"]
    caps = [b["text"].lower() for b in d["buttons"]]
    check("X", "D3 #18.5 with the job disabled the Decision section comes right after Parameters",
          "Decision" in h and "Parameters" in h and h.index("Decision") == h.index("Parameters") + 1, headings=h)
    approve, reject = targets(rid)
    check("X", "guard (D-55, unchanged): with the job disabled the page says so, offers only Reject next to the one box, and the "
          "form posts to .../reject", "disabled" in text.lower() and "approve" not in caps and caps.count("reject") == 1
          and len(d["boxes"]) == 1 and len(d["forms"]) == 1 and d["forms"][0]["action"] == reject,
          buttons=d["buttons"], boxes=d["boxes"], forms=d["forms"])
    how = None
    if len(d["boxes"]) == 1 and "reject" in caps:
        how = press(s, "Reject", "e2e-27 D3 job is retired")
        s.page.wait_for_load_state("load")
        lib.page_shot(s, "R27-D3-X-02-disabled-job-rejected")
    s.done()
    stored = lib.stored("run", rid)
    check("X", "guard (D-55, unchanged): Reject through the one box works while the job is disabled: REJECTED with the typed comment",
          stored.get("status") == "REJECTED" and stored.get("decisionComment") == "e2e-27 D3 job is retired",
          stored=stored, reaction=how)
    api("admin", lib.jpath(OFF) + "/enable", "POST")


# One row of a run or incident table, found by its run link (or, for a viewer without links, by the job name and "#n"
# as text), with its Run cell taken apart: the parts in DOM order with their left edge, the visible texts outside the
# icon, the badges, and the Cause and Status cells' status elements.
ROW_JS = """([jobPath, number, job]) => { const n = t => (t || '').replace(/\\s+/g, ' ').trim();
  const vis = e => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
  const rows = [...document.querySelectorAll('#main-panel table tbody tr')];
  const row = rows.find(tr => [...tr.querySelectorAll('a[href]')].some(a => new URL(a.href).pathname === jobPath + number + '/'))
    || rows.find(tr => { const t = n(tr.textContent); return t.includes(job) && new RegExp('#' + number + '(?!\\\\d)').test(t); });
  if (!row) return null; const table = row.closest('table');
  const cells = [...row.children].filter(c => c.tagName === 'TD' || c.tagName === 'TH');
  const headers = [...table.querySelectorAll('thead th')].map(th => n(th.textContent));
  const cellOf = h => cells[headers.indexOf(h)] || null;
  const run = cellOf('Run');
  const parts = []; if (run) { const w = document.createTreeWalker(run, NodeFilter.SHOW_ELEMENT);
    for (let e = w.nextNode(); e; e = w.nextNode()) {
      if (e.closest('svg') && e.tagName.toLowerCase() !== 'svg') continue;
      const r = e.getBoundingClientRect();
      if (e.tagName === 'svg' || e.tagName === 'IMG' || e.tagName.toLowerCase() === 'svg')
        parts.push({kind: 'icon', left: r.left, top: r.top, w: r.width, h: r.height});
      else if (e.tagName === 'A' && e.hasAttribute('href'))
        parts.push({kind: 'link', path: new URL(e.href).pathname, text: n(e.textContent), left: r.left, top: r.top, bottom: r.bottom});
      else if (!e.querySelector('*') && n(e.textContent) && vis(e) && !e.closest('a'))
        parts.push({kind: 'text', text: n(e.textContent), left: r.left, top: r.top, bottom: r.bottom,
                    display: getComputedStyle(e).display, tag: e.tagName.toLowerCase()}); } }
  const hooks = c => c ? [...c.querySelectorAll('[data-batch-control-status]')].map(e => ({enum: e.getAttribute('data-batch-control-status'),
      text: n(e.textContent), badge: e.classList.contains('batch-control-status'), tone: e.getAttribute('data-batch-control-tone')})) : [];
  const tips = run ? [...run.querySelectorAll('*')].flatMap(e => ['tooltip', 'title', 'data-tooltip', 'aria-label']
    .map(a => e.getAttribute(a) || '')).filter(Boolean) : [];
  const box = run ? run.getBoundingClientRect() : null;
  return {headers, cellCount: cells.length, rowText: n(row.innerText),
    rowCells: [...table.querySelectorAll('tbody > tr')].map(tr => [...tr.children].filter(c => c.tagName === 'TD' || c.tagName === 'TH').length),
    parts, tips, runBox: box ? {left: box.left, right: box.right, top: box.top, bottom: box.bottom, w: box.width} : null,
    runBadges: run ? run.querySelectorAll('.batch-control-status, [data-batch-control-tone]').length : null,
    hrefs: [...row.querySelectorAll('a[href]')].map(a => new URL(a.href).pathname),
    cause: hooks(cellOf('Cause')), status: hooks(cellOf('Status'))}; }"""


def run_row(s, job, number):
    return s.page.evaluate(ROW_JS, [f"{lib.PREFIX}{lib.jpath(job)}/", str(number), job])


def run_cell_ok(sec, where, row, job, number, result, columns, who="admin"):
    """The D3 Run cell of `job#number` on one table as `row` read it; `columns` the table's expected headings."""
    jp = f"{lib.PREFIX}{lib.jpath(job)}/"
    check(sec, f"D3 #6/#18.1 {where}: the table has exactly the columns {columns} and every row {len(columns)} cells "
          f"({who})", bool(row) and row["headers"] == columns and set(row["rowCells"]) == {len(columns)},
          headers=(row or {}).get("headers"), cells=sorted(set((row or {}).get("rowCells") or [])))
    parts = (row or {}).get("parts") or []
    kinds = []
    for x in parts:  # the first icon, job link, run link and label, in DOM order
        if x["kind"] == "icon" and "icon" not in kinds:
            kinds.append("icon")
        elif x["kind"] == "link" and x["path"] == jp and "job" not in kinds:
            kinds.append("job")
        elif x["kind"] == "link" and x["path"] == f"{jp}{number}/" and x["text"] == f"#{number}" and "run" not in kinds:
            kinds.append("run")
        elif x["kind"] == "text" and x["text"] == LABEL[result] and "label" not in kinds:
            kinds.append("label")
    lefts = {}
    for x in parts:
        k = ("icon" if x["kind"] == "icon" else "job" if x["kind"] == "link" and x["path"] == jp else
             "run" if x["kind"] == "link" and x["path"] == f"{jp}{number}/" else
             "label" if x["kind"] == "text" and x["text"] == LABEL[result] else None)
        if k and k not in lefts:
            lefts[k] = (round(x["left"], 1), round(x["top"], 1))
    same_line = len({v[1] for v in lefts.values()}) == 1
    check(sec, f"D3 #6 {where}: the Run cell of {job}#{number} holds core's status icon, the job link, the '#{number}' link "
          f"to the run's own URL and the D2 label '{LABEL[result]}' as an element's visible whole text, in that order "
          f"({who})", kinds == ["icon", "job", "run", "label"] and (not same_line or [lefts[k][0] for k in kinds]
                                                                    == sorted(lefts[k][0] for k in kinds)),
          order=kinds, positions=lefts, parts=[{k: v for k, v in x.items() if k in ("kind", "text", "path")} for x in parts][:8])
    check(sec, f"guard (D2 kept) {where}: nothing in {job}#{number}'s Run cell is a badge and the raw word {result} is not in the "
          f"row ({who})", bool(row) and row["runBadges"] == 0 and result not in row["rowText"],
          badges=(row or {}).get("runBadges"), row=(row or {}).get("rowText", "")[:200], tooltips=(row or {}).get("tips"))


def sec_T():
    for path, tag in (("/batch-control/dashboard/", "dashboard"), (f"/batch-control/history/?kind=runs&job={P}", "history")):
        s = Session("admin")
        s.go(path)
        lib.page_shot(s, f"R27-D3-T-{tag}-01-page")
        ten = run_row(s, TEN, S["ten"]["number"])
        run_cell_ok("T", tag, ten, TEN, S["ten"]["number"], "SUCCESS", COLUMNS)
        fail = run_row(s, FAIL, S["fail"]["number"])
        run_cell_ok("T", tag, fail, FAIL, S["fail"]["number"], "FAILURE", COLUMNS)
        cause = (ten or {}).get("cause") or []
        check("T", f"guard (D2 kept) {tag}: r27-d3-ten's Cause cell reads 'Approved request' in its one data-batch-control-status="
              "APPROVED_REQUEST element, not a badge (D2 kept)", [(c["enum"], c["text"], c["badge"], c["tone"]) for c in cause]
              == [("APPROVED_REQUEST", "Approved request", False, None)], cause=cause)
        ghost = s.page.evaluate("""(g) => { const tr = [...document.querySelectorAll('#main-panel table tbody tr')]
            .find(r => r.textContent.includes(g)); if (!tr) return null;
          const cells = [...tr.children].filter(c => c.tagName === 'TD' || c.tagName === 'TH');
          return {cells: cells.length, run: cells[0].textContent.replace(/\\s+/g, ' ').trim(),
                  icon: cells[0].querySelectorAll('svg, img').length,
                  ghostLinks: [...tr.querySelectorAll('a[href]')].filter(a => a.getAttribute('href').includes(g)).length}; }""",
                                GHOST)
        check("T", f"D3 #6 {tag}: the unknown result WEIRD of r27-d3-ghost#7 is text in its Run cell, with no icon and no "
              "link to the missing job, in a 7-cell row", bool(ghost) and ghost["cells"] == 7 and "WEIRD" in ghost["run"]
              and ghost["icon"] == 0 and ghost["ghostLinks"] == 0, ghost=ghost)
        pr = s.page.evaluate(PARAMS_JS, [f"{lib.PREFIX}{lib.jpath(TEN)}/", str(S["ten"]["number"])]) or {}
        outside, inside = pr.get("outside") or "", pr.get("inside") or ""
        check("T", f"D3 #6/#18.1 {tag}: r27-d3-ten's Parameters cell shows P01..P03 and puts the other seven in one "
              "<details> whose summary reads '7 more'", pr.get("details") == 1 and pr.get("summary") == "7 more"
              and all(f"P0{i}=" in outside for i in (1, 2, 3))
              and not any(k + "=" in outside for k in [f"P{i:02d}" for i in range(4, 11)])
              and all(f"P{i:02d}=" in inside for i in range(4, 11)), details=pr.get("details"),
              summary=pr.get("summary"), outside=outside[:300])
        lines = pr.get("lines") or []
        p02 = [x for x in lines if x["text"].startswith("P02=")]
        p01 = [x for x in lines if x["text"].startswith("P01=")]
        check("T", f"D3 #18.1 {tag}: the 300-character P02 is cut at 120 characters with an ellipsis: its line reads "
              "'P02=' + 100 a's + 20 b's + '…'", [x["text"] for x in p02] == [CUT], p02=[x["text"][:200] for x in p02])
        check("T", f"D3 #18.1 {tag}: P01's line break is not kept in the table: its line reads 'P01=first line second "
              "line' as one line of text (owner accepted)", [x["inner"] for x in p01] == ["P01=first line second line"],
              p01=p01)
        check("T", f"D3 #6 escaping {tag}: P03's markup is text, not an element", pr.get("imgs") == 0
              and any(x["text"] == "P03=" + MARKUP for x in lines), imgs=pr.get("imgs"))
        check("T", f"D3 #6 {tag}: the Request id cell names r27-d3-ten's request",
              S["ten"]["id"][:8] in (pr.get("requestCell") or ""), cell=pr.get("requestCell"))
        wrap = pr.get("wrap") or {}
        check("T", f"D3 #6/#18.1 {tag}: the Cause, User, Started and Duration cells of r27-d3-ten do not wrap: computed "
              "white-space nowrap (or pre) on the cell or around all its text, and on one line at 1280 px",
              wrap and all(v and v["nowrap"] and v["spread"] is not None and v["spread"] < 0.5 * v["lineHeight"]
                           for v in wrap.values()), wrap=wrap)
        ab = run_row(s, ABORT, S["abort"]["number"])
        texts = [x for x in (ab or {}).get("parts", []) if x["kind"] == "text"]
        runlink = [x for x in (ab or {}).get("parts", []) if x["kind"] == "link" and x["text"] == f"#{S['abort']['number']}"]
        line = [x for x in texts if re.fullmatch(r"Aborted by admin", x["text"])]
        check("T", f"D3 #6 {tag}: with no Aborted by column, r27-d3-abort's Run cell says 'Aborted by admin' on a line of "
              "its own, below the run", bool(ab) and "Aborted by" not in ab["headers"] and len(line) == 1 and bool(runlink)
              and line[0]["top"] >= runlink[0]["bottom"] - 1, headers=(ab or {}).get("headers"), line=line,
              run=runlink[:1])
        det = s.page.locator(f"#main-panel tr:has(a[href$='{lib.jpath(TEN)}/{S['ten']['number']}/']) details summary")
        if det.count():
            det.first.click()
        s.shot(f"#main-panel tr:has(a[href$='{lib.jpath(TEN)}/{S['ten']['number']}/'])", f"R27-D3-T-{tag}-02-ten-row")
        width = s.page.evaluate("() => { const t = document.querySelector('#main-panel table'); "
                                "return t ? [t.scrollWidth, t.parentElement.clientWidth, innerWidth] : null; }")
        note("T", f"{tag}: table scroll width vs container and viewport at 1280 px", widths=width)
        s.done()
    csv = api("admin", f"/batch-control/history/runs.csv?job={TEN}").content.decode("utf-8-sig", "replace")
    req = api("admin", f"/batch-control/requests/{S['ten']['id']}/")
    build = api("admin", f"{lib.jpath(TEN)}/{S['ten']['number']}/parameters/")
    check("T", "guard: the full 300-character P02 stays whole in runs.csv, on the request page and on the build's "
          "parameters page", LONG in csv and LONG in req.text and LONG in build.text, request=req.status_code,
          build=build.status_code)
    check("T", "guard: P01 keeps its line break in runs.csv and on the request page", TWO_LINES in csv
          and ("first line\nsecond line" in req.text or "first line&#10;second line" in req.text),
          in_csv=TWO_LINES in csv)


PARAMS_JS = """(arg) => { const [jobPath, number] = arg;
  const row = [...document.querySelectorAll('#main-panel table tbody tr')].find(tr =>
    [...tr.querySelectorAll('a[href]')].some(a => new URL(a.href).pathname === jobPath + number + '/'));
  if (!row) return null; const table = row.closest('table');
  const headers = [...table.querySelectorAll('thead th')].map(th => th.textContent.replace(/\\s+/g, ' ').trim());
  const cells = [...row.children].filter(c => c.tagName === 'TD' || c.tagName === 'TH');
  const cell = cells[headers.indexOf('Parameters')]; if (!cell) return {headers};
  const outside = n => [...n.childNodes].map(c => c.nodeType === 3 ? c.data : (c.tagName === 'DETAILS' ? '' : outside(c))).join(' ');
  const lines = [...cell.querySelectorAll('*')].filter(e => !e.querySelector('*') && /^P\\d\\d=/.test(e.textContent))
    .map(e => ({text: e.textContent, inner: e.innerText, inDetails: !!e.closest('details')}));
  const spread = col => { const c = cells[headers.indexOf(col)]; if (!c) return null;
    const tops = []; const w = document.createTreeWalker(c, NodeFilter.SHOW_TEXT);
    for (let t = w.nextNode(); t; t = w.nextNode()) { if (!t.data.trim()) continue; const r = document.createRange();
      r.selectNodeContents(t); for (const b of r.getClientRects()) tops.push(b.top); }
    const lh = parseFloat(getComputedStyle(c).lineHeight) || 16;
    const keeps = e => { for (let p = e; p && c.contains(p); p = p.parentElement) if (/^(nowrap|pre)$/.test(getComputedStyle(p).whiteSpace)) return true; return false; };
    const texts = []; const w2 = document.createTreeWalker(c, NodeFilter.SHOW_TEXT);
    for (let t = w2.nextNode(); t; t = w2.nextNode()) if (t.data.trim()) texts.push(t.parentElement);
    return {text: c.textContent.replace(/\\s+/g, ' ').trim(), spread: tops.length ? Math.max(...tops) - Math.min(...tops) : null,
            lineHeight: lh, whiteSpace: getComputedStyle(c).whiteSpace, nowrap: texts.length > 0 && texts.every(keeps)}; };
  const sum = cell.querySelector('details > summary');
  return {headers, details: cell.querySelectorAll('details').length, summary: sum ? sum.textContent.replace(/\\s+/g, ' ').trim() : null,
    outside: outside(cell), inside: [...cell.querySelectorAll('details')].map(d => d.textContent).join(' '), lines,
    imgs: cell.querySelectorAll('img').length, requestCell: (cells[headers.indexOf('Request id')] || {}).textContent || null,
    wrap: Object.fromEntries(['Cause', 'User', 'Started', 'Duration'].map(c => [c, spread(c)]))}; }"""


def sec_L():
    pages = [("/batch-control/dashboard/", "dashboard"), (f"/batch-control/history/?kind=runs&job={P}", "history-runs"),
             ("/batch-control/incidents/", "incidents"), (f"/batch-control/history/?kind=incidents&job={P}",
                                                          "history-incidents")]
    n = S["fail"]["number"]
    jp = f"{lib.PREFIX}{lib.jpath(FAIL)}/"
    for path, tag in pages:
        s = Session("auditor")
        r = s.go(path)
        row = run_row(s, FAIL, n)
        s.shot("#main-panel table", f"R27-D3-L-auditor-{tag}")
        s.done()
        texts = [x["text"] for x in (row or {}).get("parts", []) if x["kind"] == "text"]
        check("L", f"D3 #6 link rule (D-44) {tag}: auditor (no Item/Read) sees r27-d3-fail's Run cell with the job name and "
              f"'#{n}' as text and no link to the job or the run", r is not None and r.status == 200 and bool(row)
              and FAIL in texts and f"#{n}" in texts and not any(h.startswith(jp) for h in row["hrefs"]),
              texts=texts[:6], hrefs=(row or {}).get("hrefs"), status=r.status if r else None)
        s = Session("approver-1")
        s.go(path)
        rr = run_row(s, FAIL, n)
        s.done()
        links = [x for x in (rr or {}).get("parts", []) if x["kind"] == "link"]
        check("L", f"D3 #6 link rule {tag}: approver-1 (Item/Read) gets the job link and the '#{n}' run link in the Run "
              "cell", bool(rr) and any(x["path"] == jp for x in links)
              and any(x["path"] == f"{jp}{n}/" and x["text"] == f"#{n}" for x in links), links=links)


def sec_I():
    n = S["fail"]["number"]
    for path, tag in (("/batch-control/incidents/", "incidents"),
                      (f"/batch-control/history/?kind=incidents&job={P}", "history-incidents")):
        s = Session("admin")
        s.go(path)
        row = run_row(s, FAIL, n)
        lib.page_shot(s, f"R27-D3-I-{tag}-01-page")
        s.done()
        run_cell_ok("I", tag, row, FAIL, n, "FAILURE", INCIDENT_COLUMNS)
        st = (row or {}).get("status") or []
        check("I", f"guard (D2 kept) {tag}: the Status cell of r27-d3-fail's incident is its one badge: OPEN, tone danger, 'Open' "
              "(D2 kept)", [(x["enum"], x["text"], x["badge"], x["tone"]) for x in st] == [("OPEN", "Open", True, "danger")],
              status=st)


RUNBOX_JS = """(jobPath) => { const row = [...document.querySelectorAll('#main-panel table tbody tr')].find(tr =>
    [...tr.querySelectorAll('a[href]')].some(a => new URL(a.href).pathname === jobPath)); if (!row) return null;
  const cell = row.children[0]; const c = cell.getBoundingClientRect();
  const inside = [...cell.querySelectorAll('svg, a, span, div')].map(e => e.getBoundingClientRect())
    .filter(r => r.width && r.height).every(r => r.left >= c.left - 0.5 && r.right <= c.right + 0.5);
  const tops = new Set(); const w = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT);
  for (let t = w.nextNode(); t; t = w.nextNode()) { if (!t.data.trim()) continue; const r = document.createRange();
    r.selectNodeContents(t); for (const b of r.getClientRects()) tops.add(Math.round(b.top)); }
  const svg = cell.querySelector('svg'); const s = svg ? svg.getBoundingClientRect() : null;
  const font = parseFloat(getComputedStyle(cell).fontSize);
  return {cellWidth: Math.round(c.width), cellHeight: Math.round(c.height), lines: tops.size, inside,
          icon: s ? [Math.round(s.width * 10) / 10, Math.round(s.height * 10) / 10] : null, fontSize: font,
          tableWidth: Math.round(row.closest('table').getBoundingClientRect().width), doc: [document.documentElement.scrollWidth, innerWidth]}; }"""


def sec_C():
    s = Session("admin")
    measured = {}
    for width in (1280, 768):
        s.page.set_viewport_size({"width": width, "height": 900})
        s.go(f"/batch-control/history/?kind=runs&job={P}")
        measured[width] = s.page.evaluate(RUNBOX_JS, f"{lib.PREFIX}{lib.jpath(LONGJOB)}/")
        loc = s.page.locator(f"#main-panel tr:has(a[href$='{lib.jpath(LONGJOB)}/'])").first
        if loc.count():
            loc.evaluate("e => e.scrollIntoView({block: 'center'})")
        s.page.screenshot(path=str(lib.SHOTS / f"R27-D3-C-long-job-name-{width}.png"))
    s.done()
    check("C", "D3 #18.1 the Run cell with a long job name keeps every part inside the cell at 1280 and 768 px",
          all(v and v["inside"] for v in measured.values()), measured=measured)
    note("C", "the long-named job's Run cell: lines of text, cell width and height, icon size (px) against the font size, "
         "table and page width", measured=measured)


# ---------------------------------------------------------------- S: the Run cell's text and layout (T-UI-291)
# The row of job#number on the page's first run or incident table, found by the Run cell's text (with or without
# whitespace between the parts), with the Run cell's whole text, and its parts measured: the job, the "#n" and the
# label elements (the element children of the cell's first span, the icon left out), their line boxes, the gaps
# between them, the width of a space in their font, and the cell's and the span's widths.
CELL_JS = """([job, number]) => { const n = t => (t || '').replace(/\\s+/g, ' ').trim();
  const esc = s => s.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&');
  const want = new RegExp('(^|[\\\\s/])' + esc(job) + '\\\\s*#' + number + '(?!\\\\d)');
  for (const table of document.querySelectorAll('#main-panel table')) {
    const heads = [...table.querySelectorAll('thead th')].map(h => n(h.textContent)); const ri = heads.indexOf('Run');
    if (ri < 0) continue;
    for (const tr of table.querySelectorAll('tbody tr')) { const cell = tr.children[ri]; if (!cell || !want.test(n(cell.textContent))) continue;
      const span = cell.querySelector(':scope > span') || cell;
      const parts = [...span.children].filter(e => e.tagName.toLowerCase() !== 'svg' && n(e.textContent));
      const rects = e => [...e.getClientRects()].map(r => ({left: r.left, right: r.right, top: r.top, bottom: r.bottom}));
      const cs = getComputedStyle(parts[0] || span); const cv = document.createElement('canvas').getContext('2d');
      cv.font = cs.font; const space = cv.measureText(' ').width;
      const c = cell.getBoundingClientRect(), s = span.getBoundingClientRect(), cc = getComputedStyle(cell);
      const all = [...cell.querySelectorAll('*')].map(e => e.getBoundingClientRect()).filter(r => r.width && r.height);
      // every element child of the span, also those without text (what takes room between the parts)
      const kids = [...span.children].map(e => { const r = e.getBoundingClientRect();
        return {tag: e.tagName.toLowerCase(), cls: (e.getAttribute('class') || '').slice(0, 60), text: n(e.textContent).slice(0, 30),
                w: Math.round(r.width * 10) / 10, left: Math.round(r.left), visible: getComputedStyle(e).visibility}; });
      return {text: n(cell.textContent), heads, parts: parts.map(e => ({text: n(e.textContent), rects: rects(e)})), space, kids,
        fontSize: parseFloat(cs.fontSize), cell: {w: c.width, padL: parseFloat(cc.paddingLeft), padR: parseFloat(cc.paddingRight)},
        span: {w: s.width, display: getComputedStyle(span).display, minWidth: getComputedStyle(span).minWidth},
        inside: all.every(r => r.left >= c.left - 0.5 && r.right <= c.right + 0.5)}; } }
  return null; }"""
SHORT = (("fail", "FAILURE"), ("batch", "SUCCESS"), ("abort", "ABORTED"), ("off", "SUCCESS"), ("ten", "SUCCESS"))


def cell_of(s, job, number):
    return s.page.evaluate(CELL_JS, [job, str(number)])


def sec_S():
    n_fail, n_gone, n_abort = S["fail"]["number"], S["gone"]["number"], S["abort"]["number"]
    cases = {"run": [(FAIL, n_fail, "FAILURE", ""), (ABORT, n_abort, "ABORTED", " Aborted by admin"), (GONE, n_gone, "FAILURE", ""),
                     (GHOST, 7, None, "")],
             "incident": [(FAIL, n_fail, "FAILURE", ""), (GONE, n_gone, "FAILURE", "")]}
    pages = (("/batch-control/dashboard/", "dashboard", "run"), (f"/batch-control/history/?kind=runs&job={P}", "history-runs", "run"),
             ("/batch-control/incidents/", "incidents", "incident"),
             (f"/batch-control/history/?kind=incidents&job={P}", "history-incidents", "incident"))
    for who in ("admin", "auditor"):
        s = Session(who)
        for path, tag, kind in pages:
            s.go(path)
            for job, num, result, tail in cases[kind]:
                c = cell_of(s, job, num)
                want = f"{job} #{num} {LABEL[result] if result else 'WEIRD'}{tail}"
                linked = who == "admin" and job not in (GONE, GHOST)
                check("S", f"D3 #6 (T-UI-291) {tag}: the Run cell of {job}#{num} ({'linked' if linked else 'no links'}) "
                      f"reads exactly '{want}' as its text, the parts separated by whitespace ({who})",
                      bool(c) and c["text"] == want, shown=(c or {}).get("text"), found=bool(c))
        s.done()
    s = Session("admin")
    measured = {}
    for width in (1280, 768):
        s.page.set_viewport_size({"width": width, "height": 900})
        for path, tag in ((f"/batch-control/history/?kind=runs&job={P}", "history-runs"), ("/batch-control/incidents/", "incidents")):
            s.go(path)
            for short, result in SHORT if tag == "history-runs" else (("fail", "FAILURE"),):
                job, num = P + short, S[short]["number"]
                c = cell_of(s, job, num) or {}
                parts = c.get("parts") or []
                trio = parts[:3]
                tops = {round(r["top"]) for x in trio for r in x["rects"]}
                whole = len(trio) == 3 and all(len(x["rects"]) == 1 for x in trio[1:])
                gaps = ([round(trio[1]["rects"][0]["left"] - trio[0]["rects"][-1]["right"], 1),
                         round(trio[2]["rects"][0]["left"] - trio[1]["rects"][-1]["right"], 1)] if len(trio) == 3 and all(x["rects"] for x in trio) else None)
                sp = c.get("space")
                measured[f"{tag} {width} {short}"] = {"lines": len(tops), "gaps": gaps, "space": sp and round(sp, 1),
                                                       "parts": [x["text"] for x in trio], "cell": c.get("cell"),
                                                       "children": c.get("kids")}
                check("S", f"D3 #6 (owner decision 2026-10-15) {tag} at {width} px: {job}#{num}'s job, '#{num}' and label are on "
                      "one line, '#n' and the label each whole, and each gap between them is one space wide (no CSS gap)",
                      whole and [x["text"] for x in trio] == [job, f"#{num}", LABEL[result]] and len(tops) == 1 and gaps
                      and sp and all(abs(g - sp) <= 1.0 for g in gaps), **measured[f"{tag} {width} {short}"])
        s.go(f"/batch-control/history/?kind=runs&job={P}")
        c = cell_of(s, LONGJOB, S["long"]["number"]) or {}
        parts = c.get("parts") or []
        em = c.get("fontSize") or 0
        span, cell = c.get("span") or {}, c.get("cell") or {}
        content = (cell.get("w") or 0) - (cell.get("padL") or 0) - (cell.get("padR") or 0)
        measured[f"long {width}"] = {"jobLines": len(parts[0]["rects"]) if parts else None, "span": span, "cell": cell,
                                     "content": round(content, 1), "16em": 16 * em, "inside": c.get("inside")}
        check("S", f"D3 #18.1 (owner decision 2026-10-15) History > Runs at {width} px: the 58-character job name wraps inside "
              "the Run column, whose content is the Run cell's minimum width, 16em, and no wider (measured), and nothing "
              "overflows the cell", bool(parts) and len(parts[0]["rects"]) > 1 and c.get("inside")
              and span.get("minWidth") == f"{16 * em:g}px" and abs(span.get("w", 0) - 16 * em) <= 2 and content <= 16 * em + 2,
              **measured[f"long {width}"])
    hover(s)
    s.done()
    note("S", "the Run cell measured (lines of the job/#n/label, the gaps against one space, the cell; the long name's "
         "span, cell and content width against 16em)", measured=measured)


POS_JS = """(path) => { const a = [...document.querySelectorAll('#main-panel a[href]')].find(x => new URL(x.href).pathname === path);
  if (!a) return null; const span = a.closest('.batch-control-run') || a.parentElement;
  const o = span.getBoundingClientRect();  // relative to the cell's span: a scroll moves nothing
  const kids = [...span.children].filter(e => e.tagName.toLowerCase() !== 'svg').map(e => { const r = e.getBoundingClientRect();
    return [Math.round((r.left - o.left) * 10) / 10, Math.round((r.top - o.top) * 10) / 10]; });
  const tops = new Set(); [...span.children].forEach(e => [...e.getClientRects()].forEach(r => tops.add(Math.round(r.top))));
  const ch = a.querySelector('.jenkins-menu-dropdown-chevron');
  return {kids, lines: tops.size, chevron: !!ch, chevronShown: ch ? getComputedStyle(ch, '::after').opacity : null}; }"""


def hover(s):
    """Core's model-link chevron on the job and the "#n" link of r27-d3-fail's Run cell (History > Runs, 1280 px): hovering
    shows it, clicking it opens the context menu, and the hover moves no part of the cell nor changes its line count.
    Core's hover background may cover the next part (its float behaviour): recorded in a screenshot, not judged."""
    s.page.set_viewport_size({"width": 1280, "height": 900})
    s.go(f"/batch-control/history/?kind=runs&job={P}")
    jp = f"{lib.PREFIX}{lib.jpath(FAIL)}/"
    seen = {}
    for name, path in (("job", jp), ("run", f"{jp}{S['fail']['number']}/")):
        s.page.mouse.move(5, 5)
        before = s.page.evaluate(POS_JS, path)
        link = s.page.locator(f"#main-panel a[href$='{path[len(lib.PREFIX):]}']").first
        link.hover()
        s.page.wait_for_timeout(400)  # core's hover transition (var(--standard-transition)) before measuring
        during = s.page.evaluate(POS_JS, path)
        if name == "job":
            s.page.screenshot(path=str(lib.SHOTS / "R27-D3-S-hover-job-link.png"),
                              clip={"x": 100, "y": max(0, link.bounding_box()["y"] - 40), "width": 700, "height": 120})
        menu = False
        chev = link.locator(".jenkins-menu-dropdown-chevron")
        if chev.count():
            chev.first.click()
            menu = bool(lib._r6.until(s.page, lambda: s.page.locator(".tippy-box, .jenkins-dropdown").filter(visible=True).count(), 3000))
            s.page.keyboard.press("Escape")
        seen[name] = {"before": before, "hovered": during, "menu": menu}
    ok = all(v["before"] and v["hovered"] and v["hovered"]["chevron"] and v["menu"] and v["before"]["kids"] == v["hovered"]["kids"]
             and v["before"]["lines"] == v["hovered"]["lines"] for v in seen.values())
    check("S", "guard (core's model-link, float since 2026-10-16) hovering the job link and the '#n' link of r27-d3-fail shows "
          "core's chevron, a click on it opens the context menu, and the hover moves no part of the Run cell nor changes its "
          "line count (History > Runs, 1280 px, admin)", ok, **seen)


# ---------------------------------------------------------------- D: the space under the decision buttons
DGAP_JS = """() => { const n = t => (t || '').replace(/\\s+/g, ' ').trim();
  const hs = [...document.querySelectorAll('#main-panel h2')]; const dec = hs.find(h => n(h.textContent) === 'Decision');
  if (!dec) return null; const next = hs[hs.indexOf(dec) + 1] || null;
  const btn = [...document.querySelectorAll('#main-panel button')].find(b => n(b.textContent) === 'Reject');
  const row = btn ? (btn.closest('.jenkins-buttons-row') || btn.parentElement) : null;
  let prev = dec.previousElementSibling; while (prev && !prev.getBoundingClientRect().height) prev = prev.previousElementSibling;
  return {next: next ? n(next.textContent) : null, prev: prev ? prev.tagName.toLowerCase() + '.' + (prev.className || '') : null,
    buttons: next && row ? Math.round((next.getBoundingClientRect().top - row.getBoundingClientRect().bottom) * 10) / 10 : null,
    parameters: prev ? Math.round((dec.getBoundingClientRect().top - prev.getBoundingClientRect().bottom) * 10) / 10 : null,
    sectionPadding: getComputedStyle(document.documentElement).getPropertyValue('--section-padding').trim(),
    theme: document.documentElement.getAttribute('data-theme')}; }"""


def sec_D():
    st, pend = lib.submit_run("requester", BATCH, "e2e-27 D3 decision spacing", params={"DAY": "2026-10-15", "MODE": "full"})
    api("admin", lib.jpath(OFF) + "/enable", "POST")
    st2, drid = lib.submit_run("requester", OFF, "e2e-27 D3 decision spacing (disabled)", params={"DAY": "2026-10-15"})
    seen = {}
    try:
        api("admin", lib.jpath(OFF) + "/disable", "POST")
        for theme, radio in (("light", "none"), ("dark", "dark")):
            set_theme("approver-1", radio)
            s = Session("approver-1")
            for state, rid, action in (("pending", pend, None), ("refused", pend, ("Reject", "   ")), ("disabled", drid, None)):
                s.go(f"/batch-control/requests/{rid}/")
                if action:
                    press(s, *action)
                    s.page.wait_for_load_state("load")
                g = s.page.evaluate(DGAP_JS) or {}
                seen[f"{theme} {state}"] = g
                check("D", f"D3 #18.5 (owner decision 2026-10-15) {state}, {theme} theme: the gap between the decision buttons "
                      f"and the next heading ('Recent Runs of This Job') equals the gap between the Parameters table and the "
                      "Decision heading (approver-1)", g.get("next") == "Recent Runs of This Job" and g.get("buttons") is not None
                      and g.get("parameters") is not None and abs(g["buttons"] - g["parameters"]) <= 2 and g["buttons"] > 8, **g)
            s.done()
    finally:
        set_theme("approver-1", "none")
        api("admin", lib.jpath(OFF) + "/enable", "POST")
    note("D", "the gaps measured (px)", gaps=seen)

# ---------------------------------------------------------------- Q: the global configuration
CONFIG_JS = """() => { const n = t => (t || '').replace(/\\s+/g, ' ').trim();
  const vis = e => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
  const field = document.querySelector('[name="_.runControlEnabled"]'); if (!field) return null;
  let sec = field.closest('section'); while (sec && sec.parentElement && sec.parentElement.closest('section')) sec = sec.parentElement.closest('section');
  const own = sec.querySelector(':scope > .jenkins-section__title, :scope > h2, :scope > legend, :scope > div > .jenkins-section__title');
  const headings = [...sec.querySelectorAll('h2, h3, h4, h5, h6, legend, [role=heading], .jenkins-section__title')]
    .filter((e, i, all) => e !== own && !all.some(o => o !== e && o.contains(e)));
  const order = [...headings, ...sec.querySelectorAll('[name^="_."]')].sort((a, b) => a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1);
  const under = {}; let cur = null;
  for (const e of order) { if (headings.includes(e)) { cur = n(e.textContent); under[cur] = []; continue; }
    if (cur && !under[cur].includes(e.name) && e.type !== 'hidden') under[cur].push(e.name); }
  const last = headings[headings.length - 1];
  const tail = last ? (() => { const r = document.createRange(); r.setStartAfter(last); r.setEndAfter(sec.lastElementChild || sec);
    return n(r.cloneContents().textContent); })() : '';
  const fs = e => e ? [parseFloat(getComputedStyle(e).fontSize), getComputedStyle(e).fontWeight, Math.round(e.getBoundingClientRect().height)] : null;
  return {title: own ? n(own.textContent) : null, titleSize: fs(own), headings: headings.map(h => n(h.textContent)),
    headingSizes: headings.map(fs), headingTags: headings.map(h => h.tagName.toLowerCase()), under, tail,
    advanced: [...sec.querySelectorAll('button.advanced-button')].map(b => ({visible: vis(b), inLast: !!(last && last.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)})),
    revertInLast: [...sec.querySelectorAll('[data-url$="revert"], [href$="revert"]')].map(e => ({inAdvancedBody: !!e.closest('.advancedBody'),
      afterLast: !!(last && last.compareDocumentPosition(e) & Node.DOCUMENT_POSITION_FOLLOWING)}))}; }"""


def settings():
    return lib.facts(lib.L25.CFG + """return groovy.json.JsonOutput.toJson([run: cfg.runControlEnabled,
  change: cfg.changeControlEnabled, approvers: cfg.approversText, self: cfg.allowAdminSelfApproval,
  pending: cfg.pendingTimeoutHours, approvedRun: cfg.approvedRunTimeoutMinutes, grant: cfg.grantDurationOptionsText,
  maxGrant: cfg.maxGrantMinutes, notify: cfg.notifyBeforeExpiryMinutes, incidents: cfg.incidentResultsText,
  retention: cfg.retentionMonths, email: cfg.emailNotifications])""")


def put_settings(v):
    """Arrangement and restore (script console): every setting but the two switches, as `v` holds it."""
    return lib.gv(lib.L25.CFG + f"""cfg.setApproversText({json.dumps(v['approvers'])}); cfg.setAllowAdminSelfApproval({str(v['self']).lower()})
cfg.setPendingTimeoutHours({v['pending']}); cfg.setApprovedRunTimeoutMinutes({v['approvedRun']})
cfg.setGrantDurationOptionsText({json.dumps(v['grant'])}); cfg.setMaxGrantMinutes({v['maxGrant']})
cfg.setNotifyBeforeExpiryMinutes({v['notify']}); cfg.setIncidentResultsText({json.dumps(v['incidents'])})
cfg.setRetentionMonths({v['retention']}); cfg.setEmailNotifications({str(v['email']).lower()}); cfg.save(); return 'ok'""")


def save_page(who, path, edits=None):
    """`who` opens `path`, optionally changes fields (name -> value; a bool ticks or unticks a checkbox), and presses
    Save. Returns the HTTP status of the save."""
    s = Session(who)
    s.go(path)
    for name, value in (edits or {}).items():
        loc = s.page.locator(f"[name='{name}']").first
        if isinstance(value, bool):
            loc.set_checked(value, force=True)
        else:
            loc.fill(str(value))
    with s.page.expect_response(lambda r: r.request.method == "POST" and ("configSubmit" in r.url or "configure" in r.url)) as ri:
        s.page.locator("button[name=Submit]").first.click()
    s.page.wait_for_load_state("load")
    status = ri.value.status
    s.done()
    return status


def sec_Q():
    S["settings_before"] = settings()
    for who, path, tag in (("admin", CONFIGS[0][0], "system"), ("admin", CONFIGS[1][0], "bc-config"),
                           ("manager", CONFIGS[1][0], "bc-config")):
        s = Session(who)
        r = s.go(path)
        c = s.page.evaluate(CONFIG_JS)
        flat = norm(s.page.content())
        lib.page_shot(s, f"R27-D3-Q-{who}-{tag}")
        s.done()
        c = c or {}
        check("Q", f"D3 #18 {path} ({who}): the Batch Control section's settings are grouped under exactly five headings, "
              f"in order {GROUPS}", r is not None and r.status == 200 and c.get("headings") == GROUPS
              and c.get("title") == "Batch Control", title=c.get("title"), headings=c.get("headings"), tags=c.get("headingTags"))
        under = c.get("under") or {}
        check("Q", f"D3 #18 {path} ({who}): each group holds its fields, names unchanged, in this order: "
              + "; ".join(f"{g}: {', '.join(f)}" for g, f in zip(GROUPS, GROUP_FIELDS)),
              [under.get(g) for g in GROUPS] == GROUP_FIELDS, under=under)
        tail = c.get("tail") or ""
        if who == "admin":
            adv = c.get("advanced") or []
            rv = c.get("revertInLast") or []
            check("Q", f"D3 #18 {path} (admin): the fifth group ends with the 'Batch Control strategy' entry: its Advanced "
                  "button and, inside Advanced, the revert", "Batch Control strategy" in tail and len(adv) == 1
                  and adv[0]["inLast"] and len(rv) == 1 and rv[0]["inAdvancedBody"] and rv[0]["afterLast"],
                  advanced=adv, revert=rv, tail=tail[-200:])
        else:
            check("Q", f"D3 #18 {path} (manager): the fifth group says '{INSTALLED}' and '{ASK_ADMIN}', offers no "
                  f"Advanced and no revert, and '{REVERTING} ...' is nowhere in the HTML",
                  "Batch Control strategy" in tail and INSTALLED in tail and ASK_ADMIN in tail and not c.get("advanced")
                  and not c.get("revertInLast") and REVERTING not in flat, tail=tail[-240:])
        sizes = c.get("headingSizes") or []
        title = c.get("titleSize") or [None, None, None]
        note("Q", f"{path} ({who}): for the owner, no verdict: are the group headings visibly smaller than the section "
             "title? [computed font size px, font weight, rendered height px] of the title and of each group heading",
             title=title, groups=dict(zip(c.get("headings") or [], sizes)), tags=c.get("headingTags"),
             smaller=bool(sizes) and title[0] is not None and all(x[0] < title[0] for x in sizes))
    s = Session("manager")
    r = s.go(CONFIGS[0][0])
    s.done()
    check("Q", "guard: manager (BatchControl/Manage) cannot open Manage Jenkins > System", r is not None and r.status >= 400,
          status=r.status if r else None)
    # ---- the save round-trip through the real form
    before = S["settings_before"]
    odd = dict(before, approvers="approver-1\nmanager", self=not before["self"], approvedRun=31, grant="10, 20, 45",
               maxGrant=121, notify=6, incidents="FAILURE, UNSTABLE, ABORTED", retention=11, email=not before["email"])
    try:
        put_settings(odd)
        arranged = settings()
        codes = {}
        for who, path in (("admin", CONFIGS[0][0]), ("admin", CONFIGS[1][0]), ("manager", CONFIGS[1][0])):
            codes[f"{who} {path}"] = save_page(who, path)
            codes[f"{who} {path} read back"] = settings() == arranged
        check("Q", "guard (T-UI-289): with every setting at a value other than its default, the administrator saves both "
              "configuration pages and the manager the Batch Control page unchanged: each save answers below 400 and "
              "every value reads back as it was", all(v < 400 for k, v in codes.items() if not k.endswith("back"))
              and all(v for k, v in codes.items() if k.endswith("back")), codes=codes, arranged=arranged, now=settings())
        edits = {"_.pendingTimeoutHours": arranged["pending"] + 1, "_.notifyBeforeExpiryMinutes": 7,
                 "_.retentionMonths": 13, "_.emailNotifications": not arranged["email"]}
        code = save_page("admin", CONFIGS[1][0], edits)
        now = settings()
        check("Q", "guard (field names unchanged): a field changed in each group but Switches (pending timeout in Approval, notify before expiry in "
              "Permission windows, retention in Incidents and retention, e-mail in Notifications and strategy) and saved "
              "through the Batch Control page reaches the store; nothing else changes", code < 400
              and now == dict(arranged, pending=arranged["pending"] + 1, notify=7, retention=13, email=not arranged["email"]),
              save=code, now=now, arranged=arranged)
    finally:
        put_settings(before)
    back = settings()
    check("Q", "arrangement: every setting is back to what it was", back == before, before=before, after=back)
    # ---- the fifth group with the plain strategy installed
    rv = api("admin", f"/manage/administrativeMonitor/{lib.STRATEGY_MONITOR}/revert", "POST").status_code
    try:
        s = Session("admin")
        s.go(CONFIGS[1][0])
        c = s.page.evaluate(CONFIG_JS) or {}
        s.done()
        check("Q", "D3 #18 with the plain strategy installed (Mailer present) the fifth group is still shown and holds only "
              "the e-mail switch: no 'Batch Control strategy' entry, no Advanced, no revert", rv < 400
              and c.get("headings") == GROUPS and (c.get("under") or {}).get(GROUPS[4]) == GROUP_FIELDS[4]
              and "Batch Control strategy" not in (c.get("tail") or "") and not c.get("advanced"),
              revert=rv, headings=c.get("headings"), tail=(c.get("tail") or "")[-200:])
    finally:
        restored = lib.restore_strategy()
    check("Q", "arrangement: the Batch Control strategy is installed again", restored[1].startswith("BatchControl"),
          restored=restored)


# ---------------------------------------------------------------- Z: screenshots for the owner
def set_theme(who, theme):
    """`who` chooses the theme on the account's Appearance page ('none' is the default, light)."""
    s = Session(who)
    s.go(f"/user/{who}/appearance/")
    s.page.locator(f"input[data-theme={theme}]").check(force=True)
    with s.page.expect_navigation(timeout=20000):
        s.page.locator("button[name=Submit]").first.click()
    s.page.wait_for_load_state("load")
    s.done()


def sec_Z():
    rid = S.get("refused_pages", {}).get("reject", (None,))[0]
    st, pend = lib.submit_run("requester", BATCH, "e2e-27 D3 screenshot", params={"DAY": "2026-10-15", "MODE": "full"})
    shots = []
    try:
        for theme, radio in (("light", "none"), ("dark", "dark")):
            for who in ("admin", "approver-1", "manager"):
                set_theme(who, radio)
            for width in (1280, 768):
                s = Session("admin")
                s.page.set_viewport_size({"width": width, "height": 900})
                for path, name in (("/batch-control/dashboard/", "dashboard"),
                                   (f"/batch-control/history/?kind=runs&job={P}", "history-runs"),
                                   ("/batch-control/incidents/", "incidents"), (CONFIGS[1][0], "config-admin"),
                                   (CONFIGS[0][0], "system-config-admin")):
                    s.go(path)
                    if name.endswith("config-admin"):
                        b = s.page.locator("button.advanced-button")
                        if b.count():
                            b.last.click()
                    f = f"R27-D3-Z-{theme}-{width}-{name}"
                    lib.page_shot(s, f)
                    shots.append(f)
                    if name == "history-runs":
                        row = s.page.locator(f"#main-panel tr:has(a[href$='{lib.jpath(LONGJOB)}/'])").first
                        if row.count():
                            row.evaluate("e => e.scrollIntoView({block: 'center'})")
                        f = f"R27-D3-Z-{theme}-{width}-long-job-name"
                        s.page.screenshot(path=str(lib.SHOTS / f"{f}.png"))
                        shots.append(f)
                s.done()
                s = Session("approver-1")
                s.page.set_viewport_size({"width": width, "height": 900})
                for name, path, action in (("decision-pending", f"/batch-control/requests/{pend}/", None),
                                           ("decision-refused", f"/batch-control/requests/{pend}/", ("Reject", "   ")),
                                           ("decision-disabled", None, None)):
                    if name == "decision-disabled":
                        api("admin", lib.jpath(OFF) + "/enable", "POST")
                        _, drid = lib.submit_run("requester", OFF, "e2e-27 D3 screenshot (disabled)", params={"DAY": "2026-10-15"})
                        api("admin", lib.jpath(OFF) + "/disable", "POST")
                        path = f"/batch-control/requests/{drid}/"
                    s.go(path)
                    if action:
                        press(s, *action)
                        s.page.wait_for_load_state("load")
                    s.page.locator("#main-panel h2", has_text="Decision").first.evaluate("e => e.scrollIntoView({block: 'start'})")
                    f = f"R27-D3-Z-{theme}-{width}-{name}"
                    s.page.screenshot(path=str(lib.SHOTS / f"{f}.png"))
                    shots.append(f)
                    if name == "decision-disabled":
                        api("admin", lib.jpath(OFF) + "/enable", "POST")
                s.done()
                s = Session("manager")
                s.page.set_viewport_size({"width": width, "height": 900})
                s.go(CONFIGS[1][0])
                f = f"R27-D3-Z-{theme}-{width}-config-manager"
                lib.page_shot(s, f)
                shots.append(f)
                s.done()
    finally:
        for who in ("admin", "approver-1", "manager"):
            set_theme(who, "none")
        api("admin", lib.jpath(OFF) + "/enable", "POST")
    note("Z", "screenshots for the owner (screenshots/run-27)", shots=shots, refused_page=rid)
    check("Z", "arrangement: 2 themes x 2 widths x 10 screens were captured", len(shots) == 40, count=len(shots))


def cleanup():
    note("cleanup", "restore", enable=api("admin", lib.jpath(OFF) + "/enable", "POST").status_code,
         settings=put_settings(S["settings_before"]) if "settings_before" in S else None,
         strategy=lib.restore_strategy(), swept=lib.sweep("requester", P))


lib.run(sys.argv, {"A": sec_A, "O": sec_O, "P": sec_P, "J": sec_J, "B": sec_B, "R": sec_R, "E": sec_E, "X": sec_X, "T": sec_T,
                   "L": sec_L, "I": sec_I, "C": sec_C, "S": sec_S, "D": sec_D, "Q": sec_Q, "Z": sec_Z}, cleanup)
