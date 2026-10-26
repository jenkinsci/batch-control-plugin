"""e2e-12 part 2: every state-changing control on the detail pages, clicked in the browser, per role.

For each item kind a factory makes a FRESH item (through the plugin's POST endpoints, as the real requester).
For each role the detail page is opened, its visible state-changing controls are listed (POST forms by
submit label, confirmation links by label), and each control is exercised on its own fresh item:
  * confirmation link: Cancel first (state must not change), Escape (must close), then OK (state must change);
  * POST form: a comment is typed if there is a textarea, approvers changed for "Change Approvers",
    then submitted; landing status/URL, error text and the State row before/after are recorded.
A control visible to a role that answers 4xx is a defect. Rows: out/actions.jsonl.

H1 (2026-10-24): the rows are judged at the end (ci/h1_rules.py judge_actions): each role's detail page status and
control list against ACTION_PROBES, every POST a control makes below 400, the confirmation's Cancel and Escape closing
without a state change, and the state the control leaves on the page and on the server against ACTION_STATE; console
errors only through the allowlist. Recorded so that they mean that: the server state is the same row the page shows
(the State row, else Status; the window's state, not its grant request's status, A-02), and post_status holds the status
of every POST to a Batch Control URL the control made (the #93 confirmation dialog navigates without a response
object, A-03)."""
import json, re, sys, time
from lib import Session, close, api, groovy, BASE, clean, gone, opened
import lib

UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
ROLES = sys.argv[2:] or ["admin", "requester", "reqonly", "approver-1", "manager", "nobc"]
KINDS = sys.argv[1].split(",") if len(sys.argv) > 1 and sys.argv[1] != "all" else ["run", "run_reqonly", "act", "hold", "grant", "window", "incident"]


def loc_id(r):
    m = re.search(UUID, r.headers.get("Location", ""))
    return m.group(0) if m else None


def jp(full):
    return "/job/" + "/job/".join(full.split("/")) + "/"


def f_run(user="requester"):
    r = api(user, "/job/batch-daily/batch-control/submit", "POST",
            data=[("reason", "e2e-12 action test"), ("approvers", "approver-1"), ("approvers", "admin"), ("DATE", "2026-10-04"), ("MODE", "full")])
    return "requests", loc_id(r)


ACT_JOBS = ["batch-pt-source", "batch-unstable", "prod/mv-job", "prod/adm-job", "prod/mvf/inner-job", "prod/admf/adm-inner",
            "batch-failing", "ops/cron-a", "batch-upstream", "fast", "batch-authz", "batch-cbn", "batch-jch", "batch-lock"]


def f_act(action="ACTIVATE"):
    for j in ACT_JOBS:
        r = api("requester", jp(j) + "batch-control-activation/submit", "POST",
                data=[("action", action), ("reason", "e2e-12 action test"), ("approvers", "approver-1"), ("approvers", "admin")])
        if r.status_code == 302:
            return "activations", loc_id(r)
    raise RuntimeError("no job accepted an activation request " + action)


def f_hold():
    # HOLD needs an activated job: activate one, then request HOLD on it
    k, aid = f_act("ACTIVATE")
    api("approver-1", f"/batch-control/activations/{aid}/approve", "POST", data={"comment": "for hold"})
    return f_act("HOLD")


GJOBS = iter(["batch-rebuild", "batch-nag", "batch-throttle", "batch-token", "batch-up-target", "batch-self", "prod/ok-move",
              "team/secret-job", "ops/job-a", "side/job-b", "batch-lock", "batch-jch", "batch-cbn", "batch-authz"] * 6)


def f_grant():
    r = api("requester", "/batch-control/grants/create", "POST",
            data=[("scopeFullName", next(GJOBS)), ("actions", "CONFIGURE"), ("durationMinutes", "15"),
                  ("reason", "e2e-12 action test"), ("approvers", "approver-1"), ("approvers", "admin")])
    return "grants", loc_id(r)


def f_window():
    k, gid = f_grant()
    api("approver-1", f"/batch-control/grants/{gid}/approve", "POST", data={"comment": "window"})
    return k, gid


def f_incident():
    before = set(re.findall(r"href=\"(\d{8}-\d{6}-[a-z0-9]{6})/\"", api("admin", "/batch-control/incidents/").text))
    api("admin", "/job/batch-failing/buildWithParameters?delay=0sec", "POST")
    for _ in range(120):
        time.sleep(1)
        now = set(re.findall(r"href=\"(\d{8}-\d{6}-[a-z0-9]{6})/\"", api("admin", "/batch-control/incidents/").text))
        if now - before:
            return "incidents", sorted(now - before)[0]
    raise RuntimeError("no incident")


FACT = {"run": f_run, "run_reqonly": lambda: f_run("reqonly"), "act": f_act, "hold": f_hold, "grant": f_grant,
        "window": f_window, "incident": f_incident}


def state_of(page):
    for label in ("State", "Status"):
        c = page.locator(f"#main-panel tr:has(th:text-is('{label}')) td").first
        if c.count():
            return re.sub(r"\s+", " ", c.inner_text()).strip()[:120]
    return None


def server_state(kind, iid):
    """The state as the page shows it (state_of: the State row, else the Status row), read over REST as admin."""
    r = api("admin", f"/batch-control/{kind}/{iid}/")
    html = re.sub(r"<!--.*?-->", "", r.text, flags=re.S)
    for label in ("State", "Status"):
        m = re.search(r"<tr[^>]*>\s*<th[^>]*>\s*" + label + r"\s*</th>\s*<td[^>]*>(.*?)</td>", html, re.S)
        if m:
            return r.status_code, re.sub(r"\s+", " ", clean(m.group(1))).strip()[:120]
    return r.status_code, ""


CONTROLS_JS = r"""
() => {
  const vis = el => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
  const out = [];
  document.querySelectorAll('#main-panel form').forEach(f => {
    if ((f.getAttribute('method') || 'get').toLowerCase() !== 'post') return;
    // one control per visible submit button (Wave D D3: the run request page's one form "decision" holds Approve and
    // Reject, each with its formaction; every other form has one button)
    [...f.querySelectorAll('button[type=submit], input[type=submit], button:not([type])')].forEach((b, i) => {
      if (vis(b)) out.push({type: 'form', label: (b.innerText || b.value).trim(), name: f.getAttribute('name'), index: i,
                            action: b.getAttribute('formaction') ? b.formAction : f.action}); });
  });
  document.querySelectorAll('#main-panel a[data-url][data-post]').forEach(a => {
    if (vis(a)) out.push({type: 'confirm', label: a.innerText.trim(), url: a.dataset.url});
  });
  return out;
}"""



ROWS = []


def emit(row):
    ROWS.append(row)
    lib.log("actions", row)
    print(json.dumps(row)[:300])


def exercise(role, kindkey, control):
    kind, iid = FACT[kindkey]()
    path = f"/batch-control/{kind}/{iid}/"
    s = Session(role)
    s.go(path)
    before = state_of(s.page)
    row = {"role": role, "item": kindkey, "control": control["label"], "ctype": control["type"], "id": iid, "state_before": before}
    p = s.page
    posts = []  # A-03: the status of every POST to a Batch Control URL
    p.on("response", lambda r: posts.append(r.status) if r.request.method == "POST" and "/batch-control/" in r.url else None)
    try:
        if control["type"] == "confirm":
            a = p.locator("#main-panel a[data-url][data-post]", has_text=control["label"]).first
            # e2e-20: wait for core's confirmation dialog and for it to close instead of fixed 0.5-0.7 s
            a.click(); opened(p)
            d = p.locator("dialog[open]")
            row["dialog_text"] = re.sub(r"\s+", " ", d.first.inner_text())[:150] if d.count() else None
            if d.count():
                d.first.locator("button[data-id=cancel]").click()
                row["cancel_closed"] = gone(p)
                row["after_cancel_server"] = server_state(kind, iid)
                a.click(); opened(p)
                p.keyboard.press("Escape")
                row["escape_closed"] = gone(p)
                row["after_escape_server"] = server_state(kind, iid)
                a.click(); opened(p)
                d = p.locator("dialog[open]").first
                ok = d.locator("button[data-id=ok]").first
                with p.expect_navigation(timeout=15000) as nav:
                    ok.click()
                row["status"] = nav.value.status if nav.value else None
        else:
            f = p.locator(f"#main-panel form[name='{control['name']}']").first
            ta = f.locator("textarea")
            if ta.count():
                ta.first.fill(f"e2e-12 {control['label']} by {role}")
            if control["name"] == "changeApprover":
                boxes = f.locator("input[type=checkbox]")
                for i in range(boxes.count()):
                    b = boxes.nth(i)
                    if b.get_attribute("value") == "approver-2" and not b.is_checked():
                        b.locator("xpath=following-sibling::label").first.click()
            if control["name"] == "rerun":
                r_ta = p.locator("form[name=rerun] textarea[name=reason]")
                if r_ta.count():
                    r_ta.fill("e2e-12 rerun")
                for b in f.locator("input[name=approvers]").all()[:1]:
                    b.locator("xpath=following-sibling::label").first.click()
            btn = f.locator("button[type=submit], input[type=submit], button:not([type])").nth(control.get("index", 0))
            with p.expect_navigation(timeout=15000) as nav:
                btn.click()
                # #93: the Reject button asks for confirmation first (skipped for a blank comment,
                # which the server refuses anyway); confirm core's dialog so the form posts.
                if control["label"].lower() == "reject" and opened(p, timeout=5000):
                    p.locator("dialog[open] button[data-id=ok]").first.click()
            row["status"] = nav.value.status if nav.value else None
        p.wait_for_load_state("load")
        row["landing"] = p.url.replace(BASE, "")
        row["landing_h1"] = p.locator("h1").first.inner_text()[:80] if p.locator("h1").count() else None
        errs = [t.strip() for t in p.locator(".jenkins-alert-danger, .error, .validation-error-area .error").all_inner_texts() if t.strip()]
        row["errors"] = errs[:3]
        if (row.get("status") or 0) >= 400:
            row["error_text"] = re.sub(r"\s+", " ", s.text())[:300]
        row["state_after_page"] = state_of(p) if "/batch-control/" in p.url else None
    except Exception as e:  # noqa
        row["exception"] = str(e)[:200]
    row["server_after"] = server_state(kind, iid)
    row["post_status"] = posts
    row["console"] = s.console[:10]
    s.done()
    emit(row)


def controls_for(role, kindkey):
    kind, iid = FACT[kindkey]()
    s = Session(role)
    r = s.go(f"/batch-control/{kind}/{iid}/")
    st = r.status if r else None
    ctl = s.page.evaluate(CONTROLS_JS) if st == 200 else []
    s.done()
    # leave the probe item for the lists (realistic clutter)
    return st, ctl


if __name__ == "__main__":
    for kindkey in KINDS:
        for role in ROLES:
            st, ctls = controls_for(role, kindkey)
            emit({"role": role, "item": kindkey, "probe_status": st, "controls": [c["label"] for c in ctls]})
            for c in ctls:
                exercise(role, kindkey, c)
    close()
    lib.h1.report("actions", ROWS, {"roles": ROLES})
