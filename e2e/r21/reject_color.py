"""e2e-21: the Reject buttons render in core's destructive colour (71d267b, T-UI-52b in a real browser).

usage: python reject_color.py [RGAD]    rows: out/reject_color.jsonl, shots: screenshots/run-21/R21-<R|G|A>-buttons.png, R21-D-*.png
The requester files, over REST and naming approver-1, a pending run request (batch-daily), a pending permission window
request (CONFIGURE on r21-reject) and a pending activation request (ACTIVATE r21-reject); the server lists each as
pending (admin GET of its detail URL). Then approver-1 opens each detail page in a new browser context, and the page's
own computed styles decide:
  - expected red: the computed `color` of a probe element inserted next to the Reject button with
    style="color: var(--destructive-color)", so the check follows the theme rather than a hard-coded RGB;
  - guard: the probe resolves to a colour of its own (it differs from the text colour around it; an undefined
    --destructive-color would make it inherit that colour and the check vacuous);
  - guard: the Reject button of the reject form exists (missing is a FAIL, never a skip);
  - the Reject button's computed `color` equals the probe's;
  - guard: the Approve button of the approve form ("Approve", on the activation page "Approve Activation" or
    "Approve Hold") exists and its computed `color` is not the probe's.
One viewport screenshot per page, scrolled to the bottom where both buttons are.
R = run request, G = permission window (grant) request, A = activation request. The requester cancels the three
requests at the end (a NOTE, no verdict). Light theme only (the default of the seeded accounts).

D (#31, the check of the retired r6/s5c.py): the other destructive controls and their confirmation dialogs. The requester
opens the three pending requests R, G and A filed above, and admin opens the grants list and the detail page of a
window the requester holds (CONFIGURE on r21-reject, approved over REST for this section). On each page the control
(core's l:confirmationLink: "Cancel Request", "Revoke") is measured against the same var(--destructive-color) probe,
then clicked: core's confirmation dialog must open, its OK button ("Cancel request", "Revoke") must be red (its
computed colour or background is the probe's: core's destructive dialog type) and its Cancel button not, and Cancel
must close the dialog and change nothing (the request still pending, the window still conferring Configure). The rows
are judged by ci/h1_rules.py (driver "destructive": destructive-button, destructive-dialog, destructive-measured; a
control in DESTRUCTIVE_CONTROLS that was not measured fails). The window is revoked in the clean-up."""
import json
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import Session, api, check, note, run_sections, J, JOB  # noqa: E402

lib.LOGNAME[0] = "reject_color"
WANT = sys.argv[1] if len(sys.argv) > 1 else "RGAD"
APPROVER = "approver-1"
REASON = "e2e-21 Reject colour"

MEASURE = r"""() => {
  // Wave D D3 (#18.5): the run request page has one form named "decision" holding both buttons; the permission window
  // and activation request pages keep a form "approve" and a form "reject"
  const pick = (form, re) => {
    const f = document.querySelector(`form[name="${form}"]`) || document.querySelector('form[name="decision"]');
    if (!f) return null;
    return [...f.querySelectorAll('button')].find(b => re.test(b.textContent.trim())) || null;
  };
  const reject = pick('reject', /^Reject$/);
  const approve = pick('approve', /^Approve( Activation| Hold)?$/);
  const host = (reject && reject.parentElement) || document.querySelector('#main-panel') || document.body;
  const probe = document.createElement('span');
  probe.setAttribute('style', 'color: var(--destructive-color)');
  probe.textContent = 'probe';
  host.appendChild(probe);
  const probeColor = getComputedStyle(probe).color;
  const hostColor = getComputedStyle(host).color;
  probe.remove();
  const info = b => b && {label: b.textContent.trim(), color: getComputedStyle(b).color, classes: b.className,
                          varColor: getComputedStyle(b).getPropertyValue('--color').trim(),
                          visible: !!(b.offsetWidth || b.offsetHeight)};
  return {probe: probeColor, around: hostColor,
          destructiveVar: getComputedStyle(document.documentElement).getPropertyValue('--destructive-color').trim(),
          theme: document.documentElement.getAttribute('data-theme'),
          reject: info(reject), approve: info(approve),
          rejectForms: document.querySelectorAll('form[name="reject"], form[name="decision"]').length};
}"""

CREATED = {}


def file_request(kind):
    """The requester files one pending request naming approver-1; returns (detail kind, id, HTTP status)."""
    if kind == "R":
        r = api("requester", J("batch-daily") + "/batch-control/submit", "POST",
                data=[("reason", REASON + " (run)"), ("approvers", APPROVER), ("DATE", "2026-10-08"), ("MODE", "full"),
                      ("SECRET", "r21-not-shown")])
        return "requests", lib.loc_id(r), r.status_code
    if kind == "G":
        r, gid = lib.grant_req("requester", JOB, ["CONFIGURE"], 15, REASON + " (window)", approvers=(APPROVER,))
        return "grants", gid, r.status_code
    r = api("requester", J(JOB) + "/batch-control-activation/submit", "POST",
            data=[("action", "ACTIVATE"), ("reason", REASON + " (activation)"), ("approvers", APPROVER)])
    return "activations", lib.loc_id(r), r.status_code


def page(kind, label):
    path, rid, status = file_request(kind)
    server = api("admin", f"/batch-control/{path}/{rid}/") if rid else None
    pending = bool(server is not None and server.status_code == 200 and ("Pending" in server.text or "PENDING" in server.text))
    if not check(kind, f"precondition: the requester files a pending {label} naming {APPROVER} (server lists it as pending)",
                 rid and pending, id=rid, submit_status=status, detail_status=server.status_code if server is not None else None):
        return
    CREATED[path] = rid
    s = Session(APPROVER)
    resp = s.go(f"/batch-control/{path}/{rid}/")
    p = s.page
    p.mouse.move(0, 0)  # nothing hovered
    m = p.evaluate(MEASURE)
    # A viewport screenshot at the bottom of the page, as the approver sees the two buttons: a full-page clip misplaces
    # core's sticky bottom button bars, and a red box around a red button would only confuse the reader.
    p.evaluate("() => window.scrollTo(0, document.documentElement.scrollHeight)")
    p.wait_for_timeout(300)
    shot = f"R21-{kind}-buttons.png"
    p.screenshot(path=str(lib.SHOTS / shot))
    rej, app, probe = m["reject"], m["approve"], m["probe"]
    colours = {"reject": rej and rej["color"], "approve": app and app["color"], "probe": probe}
    common = dict(page=f"/batch-control/{path}/{rid}/", http=resp.status if resp else None, shot=shot, **colours)
    check(kind, f"guard: the probe color: var(--destructive-color) resolves to its own colour on the {label} page "
          "(differs from the text around it)", probe and probe != m["around"], around=m["around"],
          destructive_var=m["destructiveVar"], theme=m["theme"], **common)
    if not check(kind, f"guard: the {label} page shows a Reject button in its reject form", rej is not None and rej["visible"],
                 reject_forms=m["rejectForms"], **common):
        s.done()
        return
    check(kind, f"the Reject button's computed colour on the {label} page equals the destructive colour (probe)",
          rej["color"] == probe, reject_classes=rej["classes"], reject_var_color=rej["varColor"], **common)
    check(kind, f"guard: the {label} page shows an Approve button whose computed colour is not the destructive colour",
          app is not None and app["visible"] and app["color"] != probe,
          approve_label=app and app["label"], approve_classes=app and app["classes"], **common)
    s.done()


# ---------------------------------------------------------------- D (#31, r6/s5c.py)
CONTROL = r"""(sel) => {
  const ctl = document.querySelector(sel);
  const host = (ctl && ctl.parentElement) || document.querySelector('#main-panel') || document.body;
  const probe = document.createElement('span');
  probe.setAttribute('style', 'color: var(--destructive-color)');
  probe.textContent = 'probe';
  host.appendChild(probe);
  const probeColor = getComputedStyle(probe).color, around = getComputedStyle(host).color;
  probe.remove();
  const cs = ctl && getComputedStyle(ctl);
  return {probe: probeColor, around: around, count: document.querySelectorAll(sel).length,
          button: ctl && {label: ctl.textContent.trim(), color: cs.color, bg: cs.backgroundColor, classes: ctl.className,
                           visible: !!(ctl.offsetWidth || ctl.offsetHeight)}};
}"""
DIALOG = r"""() => {
  const d = document.querySelector('dialog[open]');
  if (!d) return null;
  const probe = document.createElement('span');
  probe.setAttribute('style', 'color: var(--destructive-color)');
  probe.textContent = 'probe';
  d.appendChild(probe);
  const probeColor = getComputedStyle(probe).color;
  probe.remove();
  const shown = b => !!(b.offsetWidth || b.offsetHeight);
  const buttons = [...d.querySelectorAll('button')].filter(shown);
  const ok = d.querySelector('button[data-id="ok"]') || buttons.find(b => b.classList.contains('jenkins-button--primary'));
  const cancel = d.querySelector('button[data-id="cancel"]') || buttons.find(b => /^Cancel$/.test(b.textContent.trim()));
  // core paints a button's background on ::before (.jenkins-button:before{background-color:var(--item-background)});
  // colours are also normalised through a canvas, so oklch(...) and rgb(...) of the same colour compare equal
  const cx = document.createElement('canvas').getContext('2d', {willReadFrequently: true});
  const norm = c => { if (!c) return null; cx.clearRect(0, 0, 1, 1); cx.fillStyle = '#000'; cx.fillStyle = c;
    cx.fillRect(0, 0, 1, 1); const [r, g, b, a] = cx.getImageData(0, 0, 1, 1).data; return `${r},${g},${b},${a}`; };
  const info = b => { if (!b) return null; const cs = getComputedStyle(b), pb = getComputedStyle(b, '::before');
    return {label: b.textContent.trim(), color: cs.color, bg: cs.backgroundColor, before_bg: pb.backgroundColor,
            n: {color: norm(cs.color), bg: norm(cs.backgroundColor), before_bg: norm(pb.backgroundColor)},
            classes: b.className, visible: shown(b)}; };
  return {probe: probeColor, probe_n: norm(probeColor), title: ((d.querySelector('.jenkins-dialog__title') || {}).textContent || '').trim(),
          text: d.innerText.replace(/\s+/g, ' ').trim().slice(0, 200), buttons: buttons.map(b => b.textContent.trim()),
          ok: info(ok), cancel: info(cancel)};
}"""
ROWS = []


def window_confers():
    """Server state of D's window: the requester opens r21-reject's configure page (200 only through the window)."""
    return api("requester", J(JOB) + "/configure").status_code == 200


def pending(path, rid):
    r = api("admin", f"/batch-control/{path}/{rid}/")
    return r.status_code == 200 and ("Pending" in r.text or "PENDING" in r.text)


def destructive(user, where, url, sel, control, state):
    """One control of section D: measured, clicked, its dialog measured and dismissed with Cancel; `state()` reads the
    server state before and after as a boolean (the request pending, the window conferring): True both times."""
    row = {"where": where, "control": control, "user": user, "page": url}
    s = Session(user)
    resp = s.go(url)
    p = s.page
    p.mouse.move(0, 0)
    row["http"] = resp.status if resp else None
    row.update(p.evaluate(CONTROL, sel))
    row["state_before"] = state()
    if row.get("button"):
        ctl = p.locator(sel).first
        ctl.scroll_into_view_if_needed()
        p.mouse.move(0, 0)
        tag = f"R21-D-{where.replace(' ', '-')}"
        s.shot(ctl, tag + "-control")
        ctl.click()
        row["dialog"] = p.evaluate(DIALOG) if lib.L16._l.opened(p) else None
        if row["dialog"]:
            # the open dialog sits in the viewport (top layer) and shot() clips in page coordinates
            p.evaluate("window.scrollTo(0, 0)")
            s.shot("dialog[open]", tag + "-dialog")
            cancel = p.locator("dialog[open] button[data-id=cancel]")
            if not cancel.count():
                cancel = p.locator("dialog[open] button", has_text="Cancel")
            if cancel.count():
                cancel.first.click()
            row["closed_on_cancel"] = lib.L16._l.gone(p)
            row["url_after_cancel"] = p.url.replace(lib.BASE, "")
    row["state_after"] = state()
    s.done()
    ROWS.append(row)
    lib.L16.log(dict(row, sec="D"))
    print("ROW " + json.dumps(row, default=str)[:900], flush=True)


def destructive_controls():
    for kind, path, where in (("R", "requests", "run request"), ("G", "grants", "permission window request"),
                              ("A", "activations", "activation request")):
        rid = CREATED.get(path)
        if not rid:
            note("D", f"no pending {where} from section {kind}: its Cancel Request is not measured (h1 fails it)")
            continue
        destructive("requester", where, f"/batch-control/{path}/{rid}/",
                    f"#main-panel a.confirmation-link[data-url$='/{path}/{rid}/cancel']", "Cancel Request",
                    lambda path=path, rid=rid: pending(path, rid))
    r, gid = lib.grant_req("requester", JOB, ["CONFIGURE"], 15, REASON + " (window to revoke)", approvers=(APPROVER,))
    approved = lib.decide(APPROVER, "grants", gid, "approve", "e2e-21 D") if gid else None
    if gid:
        CREATED["window"] = gid
    note("D", "the requester's CONFIGURE window on r21-reject for the Revoke controls", id=gid, request=r.status_code,
         approve=approved, confers=window_confers() if gid else None)
    if gid:
        destructive("admin", "grants list", "/batch-control/grants/",
                    f"#main-panel a.confirmation-link[data-url$='active/{gid}/revoke']", "Revoke", window_confers)
        destructive("admin", "window", f"/batch-control/grants/{gid}/",
                    f"#main-panel a.confirmation-link[data-url$='/grants/{gid}/revoke']", "Revoke", window_confers)
    lib.h1.report("destructive", ROWS)


def cleanup():
    window = CREATED.pop("window", None)
    out = {path: lib.decide("requester", path, rid, "cancel", "e2e-21 clean-up") for path, rid in CREATED.items()}
    if window:
        out["window"] = lib.decide("admin", "grants", window, "revoke", "e2e-21 clean-up")
    note("cleanup", "the requester cancels the requests this driver filed (admin revokes D's window)", statuses=out)


if __name__ == "__main__":
    table = {"R": lambda: page("R", "run request"), "G": lambda: page("G", "permission window request"),
             "A": lambda: page("A", "activation request"), "D": destructive_controls, "C": cleanup}
    run_sections(WANT + "C", table)
