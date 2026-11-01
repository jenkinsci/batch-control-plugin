"""e2e-14 G2b: the key e2e-11 round-3 checks on the merged main, with explicit assertions.

usage: python round3.py [ABCDEFGHI]   rows: out/round3.jsonl, shots: R3-<sec>-*.png
A one-item window on a folder (D-71, replaces the D-65 folder-only check): folder-page dialog as fonly (no scope
  type selector, the folder's kind shown), Delete on the folder refused next to the field, Create+Configure approved
  in the browser; then the window configures ops only (not ops/a, ops/sub, ops/mb), creates directly in ops only,
  deletes nothing (a job's own Delete window does), and a browser save/refusal
B overview badges only (R4-6/D-67): no table/alert, badge counts equal the list rows
C tab look (R4-7): our tab bar vs core's new build page tabs
D model-link (R4-11): dashboard, request detail, activation list/detail; chevron opens core's menu
E dashboard 50 + History links (R4-13)
F Pending/Active/Ended lists (R4-8/D-66) on requests, activations, grants; footers; no horizontal scroll at 1280
  M5 (#101, #103): every listed id carries the full id as tooltip and text, no title, cut by CSS; a hover shows it
G revoke on the window's detail page (R4-12): holder sees no Revoke, manager revokes, holder loses access
H request dialogs from the job page (new: requester, classic: classic) and the folder page (prod); UUID landing
I UUID ids (D-68): new ids are UUIDs, unknown ids 404"""
import json, re, sys
from lib import Session, close, api, groovy, BASE, clean, gone, opened, submit_and_wait
import lib

WANT = sys.argv[1] if len(sys.argv) > 1 else "ABCDEFGHI"
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
J = lambda full: "".join(f"/job/{p}" for p in full.split("/"))  # noqa
KNOWN = ("MIME type ('text/html')", "Jumplist request failed: TypeError: Failed to fetch")
# e2e-20: "no h-scroll at 1280" compares the document's scroll width with its client width (the 1280 px viewport less any
# vertical scroll bar) and allows this many pixels for sub-pixel rounding of text widths. It does not absorb a real
# overflow: on the GitHub runner's default fonts (DejaVu Sans, wider than the macOS system font) admin's grants list was
# 1295 px wide (CI runs of 2026-10-06/08); the workflow now installs and selects a fixed metric-compatible font set
# (fonts-liberation, .github/workflows/e2e.yml) so that local and CI layouts measure alike.
SCROLL_TOLERANCE_PX = 2


def no_hscroll(lay):
    return lay["scrollW"] <= lay["clientW"] + SCROLL_TOLERANCE_PX
N = {"pass": 0, "fail": 0}
_init = Session.__init__


def _init_with_stack(self, *a, **kw):
    """Records the stack of an uncaught page error as well (e2e-13 U-1: core header.js breadcrumb overflow)."""
    _init(self, *a, **kw)
    self.stacks = []
    self.page.on("pageerror", lambda e: self.stacks.append((e.stack or "")[:400]))


Session.__init__ = _init_with_stack


def check(sec, step, ok, **kw):
    row = dict(sec=sec, step=step, ok=bool(ok), **kw)
    N["pass" if ok else "fail"] += 1
    lib.log("round3", row)
    print(("PASS " if ok else "FAIL ") + json.dumps(row, default=str)[:700])


def console_ok(sec, s, what, expected=()):
    """expected: substrings of responses that the step provoked on purpose (a refused empty submit answers 400).
    A page error whose stack is core's header.js breadcrumb overflow (xmlEscape <- menuItem) is e2e-13 U-1 (core):
    it is recorded as known_core, not as a failure."""
    bad = [b for b in s.bad if not any(e in b for e in expected)]
    codes = {b.split()[0] for b in s.bad if b not in bad}
    stacks = getattr(s, "stacks", [])
    u1 = [st for st in stacks if "xmlEscape" in st and "header.js" in st]
    cons = [c for c in s.console if not any(k in c for k in KNOWN)
            and not any(f"status of {code}" in c for code in codes)
            and not (u1 and "reading 'replace'" in c)]
    check(sec, f"{what}: no console error, no HTTP>=400" + (" (besides the provoked responses)" if expected else ""), not cons and not bad,
          console=cons[:4], bad=bad[:4], provoked=[b for b in s.bad if b not in bad][:2], known_core=u1[:1])



def tick(d, name, value):
    box = d.locator(f"input[name={name}][value={value}]")
    if not box.is_checked():
        box.locator("xpath=following-sibling::label").first.click()


def submit_dialog(s, d, label):
    d.get_by_role("button", name=label).click()
    s.page.wait_for_url(re.compile(r"/batch-control/(grants|requests|activations)/" + UUID + "/$"), timeout=15000)
    s.page.wait_for_load_state("load")
    return s.page.url


def approve_in_browser(user, kind, rid, sec, tag):
    a = Session(user)
    a.go(f"/batch-control/{kind}/{rid}/")
    f = a.page.locator("form[action$='approve'], form[name=approve]").first
    if f.locator("textarea").count():
        f.locator("textarea").first.fill("e2e-14 approve")
    with a.page.expect_navigation():
        f.locator("button[name=Submit], button[type=submit], input[type=submit]").first.click()
    txt = re.sub(r"\s+", " ", a.text())
    a.shot("#main-panel", f"R3-{sec}-{tag}-approved")
    a.done()
    return txt


# ---------------------------------------------------------------- A
KIND_RE = {"Folder": r"\.Folder$", "Freestyle": r"FreeStyleProject$", "Pipeline": r"WorkflowJob$",
           "Multibranch": r"WorkflowMultiBranchProject$"}


def dialog_kind(d, timeout=6000):
    """D-71: the grant form's name check answers with the item's kind ([data-batch-control-item-kind], icon + name)."""
    k = d.locator("[data-batch-control-item-kind]")
    try:
        k.first.wait_for(timeout=timeout)
    except Exception:
        return None, None, False
    return (k.first.get_attribute("data-batch-control-item-kind"), re.sub(r"\s+", " ", k.first.inner_text()).strip(),
            k.first.locator("svg").count() > 0)


def untick(d, name, value):
    box = d.locator(f"input[name={name}][value={value}]")
    if box.is_checked():
        box.locator("xpath=following-sibling::label").first.click()


def sec_A():
    groovy("""def ops = jenkins.model.Jenkins.get().getItemByFullName('ops')
if (ops.getItem('del-me') == null) ops.createProject(hudson.model.FreeStyleProject, 'del-me')
['fo-new'].each { n -> def i = ops.getItem(n); if (i) i.delete() }
return ops.items*.name""")
    s = Session("fonly")
    s.go("/job/ops/")
    s.page.locator("#tasks a, #tasks button, .jenkins-app-bar a, .jenkins-app-bar button", has_text="Request Change Permission").first.click()
    s.page.wait_for_selector("dialog[open] input[name=scopeFullName]")
    d = s.page.locator("dialog[open]").first
    url_open = s.page.url
    prefill = d.locator("input[name=scopeFullName]").input_value()
    selector = d.locator("select[name=scopeType]").count()
    kind, kind_text, icon = dialog_kind(d)
    for a in ("CREATE", "CONFIGURE", "DELETE"):
        tick(d, "actions", a)
    d.locator("select[name=durationMinutes]").select_option("60")
    d.locator("textarea[name=reason]").fill("e2e-16 window on the folder ops")
    tick(d, "approvers", "approver-1")
    s.shot("dialog[open]", "R3-A-01-folder-dialog")
    # D-71: Delete applies only to a job, so the submission is refused next to the actions, input kept
    submit_and_wait(s.page, d.get_by_role("button", name="Request Grant").click)  # e2e-20: was a fixed 2.5 s
    d = s.page.locator("dialog[open]").first
    refusal = {"dialog_open": d.count(), "url": s.page.url.replace(BASE, ""),
               "errors": [t.strip()[:200] for t in s.page.locator(
                   "dialog[open] [data-batch-control-field-error], dialog[open] [data-batch-control-form-error], "
                   "dialog[open] .error, dialog[open] .jenkins-alert-danger").all_inner_texts() if t.strip()][:3]}
    if d.count():
        refusal["kept"] = (d.locator("input[name=scopeFullName]").input_value(),
                           [b.get_attribute("value") for b in d.locator("input[name=actions]").all() if b.is_checked()])
        s.shot("dialog[open]", "R3-A-01b-folder-delete-refused")
    check("A", "folder page dialog opens in place pre-filled 'ops', no scope type selector, kind Folder with its icon; "
          "Delete on the folder is refused next to the field with the input kept",
          url_open.rstrip("/").endswith("/job/ops") and prefill == "ops" and selector == 0 and kind and re.search(KIND_RE["Folder"], kind)
          and icon and refusal["dialog_open"] == 1 and any("applies only to a job" in e for e in refusal["errors"])
          and refusal.get("kept", (None, []))[0] == "ops" and "DELETE" in refusal.get("kept", (None, []))[1],
          url_open=url_open.replace(BASE, ""), prefill=prefill, scope_type_selector=selector, kind=kind, kind_text=kind_text, icon=icon,
          refusal=refusal)
    untick(d, "actions", "DELETE")
    tick(d, "approvers", "approver-1")
    if not d.locator("textarea[name=reason]").input_value():
        d.locator("textarea[name=reason]").fill("e2e-16 window on the folder ops")
    landing = submit_dialog(s, d, "Request Grant")
    gid = re.search(UUID, landing).group(0)
    s.shot("#main-panel", "R3-A-02-pending-detail")
    detail_kind = s.page.locator("#main-panel [data-batch-control-item-kind]").first
    check("A", "Create+Configure on the folder lands on the UUID detail page, which shows the item with its kind",
          gid and detail_kind.count() and re.search(KIND_RE["Folder"], detail_kind.get_attribute("data-batch-control-item-kind") or ""),
          landing=landing.replace(BASE, ""), detail_kind=detail_kind.inner_text() if detail_kind.count() else None)
    console_ok("A", s, "folder page + dialog", expected=("400 POST",))
    s.done()
    txt = approve_in_browser("approver-1", "grants", gid, "A", "02b")
    check("A", "approver-1 approves in the browser", "Approved" in txt or "Open" in txt or "APPROVED" in txt, text=txt[:300])
    st = lambda r: r.status_code  # noqa
    res = {}
    for item in ("ops", "ops/a", "ops/sub", "ops/mb", "ops/sub/b"):
        res[f"configure {item}"] = st(api("fonly", J(item) + "/configure"))
    check("A", "configure (D-71): the folder ops 200; ops/a, ops/sub, ops/mb, ops/sub/b 403 (a folder window covers the folder only)",
          [res[f"configure {i}"] for i in ("ops", "ops/a", "ops/sub", "ops/mb", "ops/sub/b")] == [200, 403, 403, 403, 403], **res)
    cr = {}
    for parent, name in (("ops", "fo-new"), ("ops/sub", "fo-nested"), ("", "fo-root")):
        base = J(parent) if parent else ""
        r = api("fonly", base + f"/createItem?name={name}&mode=hudson.model.FreeStyleProject", "POST",
                headers={"Content-Type": "application/x-www-form-urlencoded"})
        cr[f"{parent or '(root)'}/{name}"] = (st(r), st(api("admin", base + f"/job/{name}/api/json")))
    cr["configure ops/fo-new (D-35c, created through the window)"] = st(api("fonly", J("ops/fo-new") + "/configure"))
    check("A", "create: ops/fo-new allowed and configurable by its creator (D-35c); ops/sub/fo-nested and root refused",
          cr["ops/fo-new"] == (302, 200) and cr["ops/sub/fo-nested"][1] == 404 and cr["(root)/fo-root"][1] == 404
          and cr["ops/sub/fo-nested"][0] == 403 and cr["(root)/fo-root"][0] == 403
          and cr["configure ops/fo-new (D-35c, created through the window)"] == 200, **cr)
    de = {}
    for item in ("ops/del-me", "ops/sub", "ops/mb", "ops", "ops/sub/b"):
        r = api("fonly", J(item) + "/doDelete", "POST")
        de[item] = (st(r), st(api("admin", J(item) + "/api/json")), clean(r.text)[:160] if st(r) >= 400 else "")
    check("A", "delete under the folder window: nothing (ops/del-me, ops/sub, ops/mb, ops, ops/sub/b refused and still present)",
          all(de[i][0] == 403 and de[i][1] == 200 for i in ("ops/del-me", "ops/sub", "ops/mb", "ops", "ops/sub/b")), **de)
    # the job's own Delete window (D-71: Delete applies to a job)
    r = api("fonly", "/batch-control/grants/create", "POST", data=[("scopeFullName", "ops/del-me"), ("actions", "DELETE"), ("durationMinutes", "15"),
                                                                   ("reason", "e2e-16 delete ops/del-me"), ("approvers", "approver-1")])
    gdel = re.search(UUID, r.headers.get("Location", "") or "")
    gdel = gdel.group(0) if gdel else None
    if gdel:
        api("approver-1", f"/batch-control/grants/{gdel}/approve", "POST", data={"comment": "ok"})
    r = api("fonly", J("ops/del-me") + "/doDelete", "POST")
    dj = (st(r), st(api("admin", J("ops/del-me") + "/api/json")))
    check("A", "a Delete window on the job ops/del-me deletes it", gdel and dj == (302, 404), grant=gdel, delete=dj)
    # browser: fonly saves the folder ops (its own configuration), opens ops/sub configure (refused)
    b = Session("fonly")
    r = b.go("/job/ops/configure")
    saved = None
    if r.status == 200:
        b.page.locator("textarea[name=description], textarea[name='_.description']").first.fill("e2e-16 save of the folder ops under its window")
        with b.page.expect_navigation() as nav:
            b.page.locator("button[name=Submit]").first.click()
        saved = (nav.value.status, b.page.url.replace(BASE, ""))
    r2 = b.go("/job/ops/job/sub/configure")
    b.shot("#main-panel" if b.page.locator("#main-panel").count() else "body", "R3-A-03-nested-configure-refused")
    desc = api("admin", "/job/ops/api/json?tree=description").json().get("description")
    check("A", "browser: the folder ops saved under its window; ops/sub configure refused",
          saved and saved[0] == 200 and desc == "e2e-16 save of the folder ops under its window" and r2.status == 403,
          saved=saved, nested_status=r2.status, desc=desc)
    # browser: no Delete Folder on ops/sub for fonly (no window confers Delete on a folder)
    b.go("/job/ops/job/sub/")
    dl = b.page.locator("#tasks a, #tasks button, a, button", has_text=re.compile(r"^\s*Delete Folder\s*$"))
    info = {"delete_entry": dl.count()}
    if dl.count():
        dl.first.click()
        opened(b.page)  # e2e-20: was a fixed 0.8 s
        dd = b.page.locator("dialog[open]")
        info["confirm"] = re.sub(r"\s+", " ", dd.first.inner_text())[:160] if dd.count() else None
        if dd.count():
            with b.page.expect_navigation():
                dd.first.locator("button[data-id=ok], button.jenkins-button--primary, button:has-text('Yes'), button:has-text('Delete')").first.click()
            info["landing"] = b.page.url.replace(BASE, "")
            info["text"] = re.sub(r"\s+", " ", b.text())[:300]
            b.shot("#main-panel" if b.page.locator("#main-panel").count() else "body", "R3-A-04-nested-delete-refused")
    info["still_present"] = api("admin", "/job/ops/job/sub/api/json").status_code
    check("A", "browser: Delete Folder on ops/sub not offered (or refused), folder still present",
          info["still_present"] == 200 and (info["delete_entry"] == 0 or "Delete" in info.get("text", "") or "denied" in info.get("text", "").lower()
                                           or "permission" in info.get("text", "").lower()), **info)
    b.done()
    recs = groovy("""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/changes/%s.jsonl')
return f.readLines().findAll{ it.contains('ops/fo-new') || it.contains('ops/del-me') }.join('\\n')""" % lib.month())
    lines = [json.loads(x) for x in re.findall(r"\{.*?\}", recs)]
    by_fonly = [x for x in lines if x.get("user") == "fonly"]
    newest = {t: max((x for x in by_fonly if x["type"] == t), key=lambda x: x["at"], default=None) for t in ("CREATE", "DELETE")}
    covering = groovy("""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/grants')
return d.listFiles().findAll{ it.name.endsWith('.xml') }.collect{ new XmlSlurper().parse(it) }.findAll{ g ->
  g.user.text() == 'fonly' && g.scope.type.text() == 'ITEM' && g.scope.fullName.text() in ['ops', 'ops/del-me'] }.collect{ g ->
  g.id.text() + ':' + g.grantedAtMillis.text() + ':' + g.expiresAtMillis.text() + ':' + g.scope.fullName.text() }.join(',')""").replace("Result: ", "")
    win = {c.split(":")[0]: (int(c.split(":")[1]), int(c.split(":")[2]), c.split(":")[3]) for c in covering.split(",") if c}
    want = {"CREATE": "ops", "DELETE": "ops/del-me"}
    ok = all(v and v.get("grantId") in win and win[v["grantId"]][0] <= v["at"] <= win[v["grantId"]][1] and win[v["grantId"]][2] == want[t]
             for t, v in newest.items())
    check("A", "this run's change records: CREATE ops/fo-new by fonly carries fonly's ITEM window on the folder ops, DELETE ops/del-me "
          "the window on the job itself, each open at that moment",
          ok, newest=newest, new_grant=gid, delete_grant=gdel, fonly_item_windows=win)


# ---------------------------------------------------------------- B
def badge_counts(s):
    out = {}
    for a in s.page.locator("nav[data-batch-control-tabs] a").all():
        t = re.sub(r"\s+", " ", a.inner_text()).strip()
        m = re.match(r"(.*?)\s+(\d+)$", t)
        out[m.group(1) if m else t] = int(m.group(2)) if m else None
    return out


def pending_rows(s, section):
    s.go(f"/batch-control/{section}/")
    foot = s.page.locator("#main-panel").inner_text()
    m = re.search(r"Pending Requests.*?Page \d+ \((\d+) (?:request|item)", foot, re.S)
    if m:
        return int(m.group(1))
    t = s.page.locator("#main-panel table").first
    return t.locator("tbody tr").count() if t.count() else 0


def badge_oracle(user, kind):
    """D-61: pending requests awaiting the viewer's decision (designated approver), else the viewer's own pending
    requests; read from the store files (requests/run, requests/grant, activation-requests)."""
    d = {"run": "requests/run", "grant": "requests/grant", "activation": "activation-requests"}[kind]
    out = groovy("""def dir = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/%s')
def appr = 0, own = 0
dir.listFiles().findAll{ it.name.endsWith('.xml') }.each { f ->
  def x = new XmlSlurper().parse(f)
  if (x.status.text() != 'PENDING') return
  if (x.approvers.string*.text().contains('%s')) appr++
  if (x.requester.text() == '%s') own++
}
return appr + ',' + own""" % (d, user, user)).replace("Result: ", "")
    appr, own = (int(x) for x in out.split(","))
    return appr if appr else (own or None)


def sec_B():
    for user in ("admin", "requester", "approver-1", "manager", "reqonly"):
        s = Session(user)
        s.go("/batch-control/")
        tabs = badge_counts(s)
        ov = {"tables": s.page.locator("#main-panel table").count(),
              "alerts": s.page.locator("#main-panel .jenkins-alert, #main-panel .alert, #main-panel .jenkins-notice").count(),
              "text": re.sub(r"\s+", " ", s.text())[:220]}
        s.shot("#main-panel", f"R3-B-{user}-overview")
        cmp = {}
        for tab, kind in (("Run Requests", "run"), ("Activations", "activation"), ("Grants", "grant")):
            if tab in tabs:
                cmp[tab] = (tabs[tab], badge_oracle(user, kind))
        check("B", f"{user}: overview has no table/alert; badges equal the D-61 count", ov["tables"] == 0 and ov["alerts"] == 0
              and all(a == b for a, b in cmp.values()), tabs=tabs, badge_vs_expected=cmp, **ov)
        console_ok("B", s, f"{user} overview + lists")
        s.done()


# ---------------------------------------------------------------- C
CS = """sel => { const n = document.querySelector(sel); if (!n) return null;
  const cur = n.querySelector('[aria-current=page], [aria-selected=true], .active') || [...n.querySelectorAll('a.jenkins-button')].find(a => !a.classList.contains('jenkins-button--tertiary')); const other = [...n.querySelectorAll('a')].find(a => a !== cur);
  const cs = e => { const c = getComputedStyle(e); const b = getComputedStyle(e, '::before'); return {bg: c.backgroundColor, color: c.color, radius: c.borderRadius, h: Math.round(e.getBoundingClientRect().height), pad: c.padding, font: c.fontSize + '/' + c.fontWeight, beforeBg: b.backgroundColor}; };
  return {cls: n.className, items: [...n.querySelectorAll('a')].map(a => a.innerText.trim()), current: cur && cur.innerText.trim(), curStyle: cur && cs(cur), otherStyle: other && cs(other), navH: Math.round(n.getBoundingClientRect().height), scrollW: document.documentElement.scrollWidth,
  clientW: document.documentElement.clientWidth}; }"""


def sec_C():
    s = Session("admin")
    s.go("/user/admin/experiments/")
    s.page.locator("tr", has_text="new-build-page.flag").locator("select").select_option(label="Enabled")
    s.page.click("button[name=Submit]"); s.page.wait_for_load_state("load")
    s.go("/job/fast/1/")
    try:
        s.page.wait_for_selector(".app-build-tabs", timeout=8000)
    except Exception:
        s.go("/job/fast/1/")
    core = s.page.evaluate(CS, ".app-build-tabs")
    if core:
        s.shot(".app-build-tabs", "R3-C-core-build-tabs")
    s.go("/batch-control/history/")
    ours = s.page.evaluate(CS, "nav[data-batch-control-tabs]")
    s.shot("nav[data-batch-control-tabs]", "R3-C-ours-history")
    same = core and ours and core["curStyle"] and all(core["curStyle"][k] == ours["curStyle"][k] for k in ("radius", "h", "font", "beforeBg", "color"))
    other = core and ours and core["otherStyle"] and all(core["otherStyle"][k] == ours["otherStyle"][k] for k in ("radius", "h", "beforeBg"))
    check("C", "current and other tabs look like core's new build page tabs (radius, height, font, colour, ::before); "
          "inline padding 12px vs core's 16px is the documented tabs.jelly tweak (DEF-05 fix, one row at 1280)",
          same and other and ours["current"] == "History" and ours["navH"] <= 40 and no_hscroll(ours), core=core, ours=ours)
    s.go("/user/admin/experiments/")
    s.page.locator("tr", has_text="new-build-page.flag").locator("select").select_option(index=0)
    s.page.click("button[name=Submit]"); s.page.wait_for_load_state("load")
    s.done()


# ---------------------------------------------------------------- D, E
def chevron_menu(s, link):
    link.hover()
    try:  # e2e-20: core shows the chevron on hover; wait for it (was a fixed 0.7 s)
        s.page.locator(".jenkins-menu-dropdown-chevron").locator("visible=true").first.wait_for(state="visible", timeout=3000)
    except Exception:
        pass
    chev = [c for c in s.page.locator(".jenkins-menu-dropdown-chevron").all() if c.is_visible()]
    if not chev:
        return None
    chev[0].click()
    try:  # the model-link menu fetches its entries; wait for them (was a fixed 1.2 s)
        s.page.locator(".tippy-box").first.locator("a, button").first.wait_for(state="visible", timeout=5000)
    except Exception:
        pass
    m = [t.strip()[:160] for t in s.page.locator(".tippy-box").all_inner_texts()][:1]
    s.page.keyboard.press("Escape")
    return m


def sec_D():
    ids = json.loads((lib.HERE / "out" / "ids.json").read_text())
    s = Session("admin")
    for p, name in (("/batch-control/dashboard/", "dashboard"), (f"/batch-control/requests/{ids['req_executed']}/", "request-detail"),
                    ("/batch-control/activations/", "activation-list"), (f"/batch-control/activations/{ids['act_approved']}/", "activation-detail"),
                    ("/batch-control/requests/", "requests-list"), ("/batch-control/grants/", "grants-list")):
        s.go(p)
        jl = s.page.locator("#main-panel a[href*='/job/']")
        ml = s.page.locator("#main-panel a.model-link[href*='/job/']")
        menu = chevron_menu(s, ml.first) if ml.count() else None
        if ml.count():
            s.shot(ml.first.locator("xpath=.."), f"R3-D-{name}")
        expect_links = name not in ("requests-list", "grants-list")
        check("D", f"{name}: job/run links carry model-link and the chevron opens core's menu",
              (jl.count() == ml.count() and ml.count() > 0 and menu) if expect_links else jl.count() == ml.count(),
              job_links=jl.count(), model_links=ml.count(), menu=menu, note="" if expect_links else "list without job links is UX 5 of e2e-11")
    console_ok("D", s, "model-link pages")
    s.done()


def sec_E():
    s = Session("approver-1")
    s.go("/batch-control/dashboard/")
    t = s.page.locator("#main-panel table").first
    rows = t.locator("tbody tr").count()
    first = [x.strip() for x in t.locator("tbody tr td:first-child").all_inner_texts()][:4]
    hist = [(a.inner_text().strip(), a.get_attribute("href")) for a in s.page.locator("#main-panel a", has_text=re.compile("History"))
            .all() if "history" in (a.get_attribute("href") or "")]
    intro = re.sub(r"\s+", " ", s.text())[:260]
    s.shot("#main-panel", "R3-E-dashboard")
    landing = None
    if hist:
        with s.page.expect_navigation() as nav:
            s.page.locator("#main-panel a[href*='history']").first.click()
        landing = (nav.value.status, s.page.url.replace(BASE, ""))
    # the previous and the current month of the controller (the original literal: 2026-09-01..2026-10-31)
    total = api("admin", f"/batch-control/history/runs.csv?from={lib.month(-1)}-01&to={lib.jenkins_today()}").text.count("\n") - 1
    check("E", "dashboard shows exactly 50 runs (more exist), 2 History links, link lands on History", rows == 50 and total > 50 and len(hist) == 2
          and landing and landing[0] == 200 and "/batch-control/history/" in landing[1], rows=rows, runs_total=total, first=first, history_links=hist,
          landing=landing, intro=intro)
    console_ok("E", s, "dashboard")
    s.done()


# ---------------------------------------------------------------- F
LAYOUT = """() => { const mp = document.querySelector('#main-panel');
  const firstH2 = mp.querySelector('h2');
  const formsBefore = [...mp.querySelectorAll('form')].filter(f => firstH2 && (f.compareDocumentPosition(firstH2) & Node.DOCUMENT_POSITION_FOLLOWING)).length;
  const footers = [...new Set([...mp.querySelectorAll('*')].filter(e => e.childElementCount < 4 && /^Page \\d+ \\(/.test((e.innerText || '').trim())).map(e => e.innerText.trim()))];
  return {formsBefore, footers, scrollW: document.documentElement.scrollWidth, clientW: document.documentElement.clientWidth}; }"""
LIST_ORDER = ["pending", "active", "ended"]


def layout(s):
    """e2e-20: headings by role, the lists by the plugin's data-batch-control-list and their ID cells by
    data-batch-control-id (was: every h2, every table, the first cell's first link)."""
    mp = s.page.locator("#main-panel")
    lay = s.page.evaluate(LAYOUT)
    lay["heads"] = [h.strip() for h in mp.get_by_role("heading", level=2).all_inner_texts()]
    lay["tables"] = []
    for t in mp.locator("table[data-batch-control-list]").all():
        rows = t.locator("tbody tr")
        ids = []
        for r in rows.all()[:3]:
            a = r.locator("td").first.locator("a[data-batch-control-id]")
            ids.append(a.first.get_attribute("href") if a.count() else None)
        lay["tables"].append({"list": t.get_attribute("data-batch-control-list"), "rows": rows.count(), "idLinks": ids})
    return lay


# M5 (gap audit 2026-10; #101, #103): every id the lists show (tags/idLink.jelly: [data-batch-control-id]) carries the
# full id as core's tooltip attribute and holds the full id as its text, is served without a native title (core's
# tooltip script itself sets title = tooltip on the element it registers and removes it while its tooltip shows, so the
# rendered DOM is judged by that: core registered it (element._tippy) and any title equals the id), and is cut by CSS:
# computed text-overflow ellipsis with overflow hidden, and a rendered box narrower than the full text (clientWidth below
# scrollWidth). Read once per list page, after load (the ids are server-rendered, not transient).
SHORT_IDS = """() => [...document.querySelectorAll('#main-panel [data-batch-control-id]')].map(e => {
  const cs = getComputedStyle(e); const id = e.getAttribute('data-batch-control-id');
  return {id, tag: e.tagName.toLowerCase(), tooltip: e.getAttribute('tooltip'), title: e.getAttribute('title'), core: !!e._tippy,
    text: (e.textContent || '').trim(), overflow: cs.textOverflow, hidden: cs.overflowX, rendered: e.getClientRects().length > 0,
    width: e.clientWidth, full: e.scrollWidth}; })"""
SERVED_ID_TAG = re.compile(r"<(?:a|span)\b[^>]*\bdata-batch-control-id=[^>]*>")


def short_ids_bad(ids, html):
    """The ids that break #101/#103 (empty list: all hold): in the DOM, and in the served page (`html`)."""
    bad = [i for i in ids if not (re.fullmatch(UUID, i["id"] or "") and i["tooltip"] == i["id"] and i["core"]
                                  and i["title"] in (None, i["id"]) and i["text"] == i["id"] and i["overflow"] == "ellipsis"
                                  and i["hidden"] == "hidden" and i["rendered"] and 0 < i["width"] < i["full"])]
    served = SERVED_ID_TAG.findall(html or "")
    bad += [{"served": t[:200]} for t in served if re.search(r"\stitle=", t)]
    if len(served) != len(ids):
        bad.append({"served_tags": len(served), "dom": len(ids)})
    return bad


def tooltip_on_hover(s, el_id):
    """#101: hovering the id shows core's tooltip (tippy, attached by core's JavaScript from the tooltip attribute)
    with the full id, and no native title meanwhile (core removes it while its tooltip shows). Returns (the texts of
    the tooltip boxes, the element's title while hovered); waits up to 5 s for a box with the id."""
    el = s.page.locator(f"#main-panel [data-batch-control-id='{el_id}']").first
    el.scroll_into_view_if_needed()
    el.hover()
    box = s.page.locator(".tippy-box", has_text=el_id)
    try:
        box.first.wait_for(state="visible", timeout=5000)
    except Exception:  # noqa: judged by the caller (no box with the id)
        pass
    texts = [t.strip() for t in s.page.locator(".tippy-box").all_inner_texts()]
    title = el.get_attribute("title")
    s.page.mouse.move(0, 0)
    return texts, title


def sec_F():
    hovered = set()
    for user in ("admin", "approver-1", "requester", "fonly"):
        s = Session(user)
        for sec in ("requests", "activations", "grants"):
            r = s.go(f"/batch-control/{sec}/")
            if r.status != 200:
                check("F", f"{user} {sec}: refused as expected", (user, sec) in (("fonly", "requests"),), status=r.status)
                continue
            lay = layout(s)
            if not lay["tables"] and not lay["heads"]:
                empty = re.sub(r"\s+", " ", s.text())
                check("F", f"{user} {sec}: nothing to list, the page says so and how to file one", "No " in empty and "To file one" in empty,
                      text=empty[:260])
                continue
            s.page.screenshot(path=str(lib.SHOTS / f"R3-F-{user}-{sec}.png"), full_page=True)
            order_ok = [h for h in lay["heads"] if h in ("Pending Requests", "Active", "Approved", "Ended")]
            ids_ok = all(h is None or re.search(UUID + "/$", h) for t in lay["tables"] for h in t["idLinks"])
            lists = [t["list"] for t in lay["tables"]]
            lists_ok = all(x in LIST_ORDER for x in lists) and lists == sorted(lists, key=LIST_ORDER.index)
            check("F", f"{user} {sec}: H2 Pending -> Active -> Ended, no form above, ID links to UUID detail, no h-scroll at 1280",
                  len(order_ok) >= 3 and order_ok[0] == "Pending Requests" and order_ok[-1] == "Ended" and lay["formsBefore"] == 0
                  and ids_ok and lists_ok and no_hscroll(lay), lists=lists, **lay)
            ids = s.page.evaluate(SHORT_IDS)
            bad = short_ids_bad(ids, r.text())
            listed = sum(t["rows"] for t in lay["tables"])
            check("F", f"{user} {sec}: M5 (#101, #103) every listed id has the full id as its tooltip attribute (registered "
                  "by core's tooltip script) and text, is served without a title, and is cut by CSS (text-overflow "
                  "ellipsis, overflow hidden, rendered narrower than its text)",
                  ids and not bad and len(ids) >= listed, ids=len(ids), rows=listed, bad=bad[:3],
                  sample=ids[0] if ids else None)
            if ids and sec not in hovered:  # one hover per list kind (the tooltip is core's, attached the same everywhere)
                hovered.add(sec)
                texts, title = tooltip_on_hover(s, ids[0]["id"])
                check("F", f"{user} {sec}: M5 (#101) hovering an id shows core's tooltip (.tippy-box) with the full id and "
                      "no native title meanwhile", ids[0]["id"] in texts and title is None, id=ids[0]["id"], tippy=texts[:3],
                      title_while_shown=title)
        console_ok("F", s, f"{user} lists", expected=(("403 GET " + BASE + "/batch-control/requests/"),) if user == "fonly" else ())
        s.done()


# ---------------------------------------------------------------- G
def sec_G():
    r = api("requester", "/batch-control/grants/create", "POST", data=[("scopeFullName", "prod/ok-move"), ("actions", "CONFIGURE"),
                                                                     ("durationMinutes", "15"), ("reason", "e2e-14 revoke test"), ("approvers", "approver-1")])
    gid = re.search(UUID, r.headers.get("Location", "")).group(0)
    api("approver-1", f"/batch-control/grants/{gid}/approve", "POST", data={"comment": "ok"})
    before = api("requester", "/job/prod/job/ok-move/configure").status_code
    h = Session("requester")
    h.go(f"/batch-control/grants/{gid}/")
    hb = [b.inner_text().strip() for b in h.page.locator("#main-panel button, #main-panel a.jenkins-button").all() if b.inner_text().strip()]
    h.shot("#main-panel", "R3-G-01-holder-detail")
    h.done()
    m = Session("manager")
    m.go(f"/batch-control/grants/{gid}/")
    rv = m.page.locator("#main-panel a, #main-panel button", has_text=re.compile(r"^\s*Revoke\s*$"))
    info = {"holder_buttons": hb, "manager_revoke": rv.count(), "before": before}
    if rv.count():
        rv.first.click(); opened(m.page)  # e2e-20: fixed 0.7 s / 0.5 s waits replaced by the dialog's state
        d = m.page.locator("dialog[open]")
        info["confirm"] = re.sub(r"\s+", " ", d.first.inner_text())[:200] if d.count() else None
        m.shot("dialog[open]", "R3-G-02-confirm")
        d.first.get_by_role("button", name="Cancel").click(); gone(m.page)
        info["after_cancel"] = api("requester", "/job/prod/job/ok-move/configure").status_code
        rv.first.click(); opened(m.page)
        with m.page.expect_navigation():
            m.page.locator("dialog[open]").first.locator("button[data-id=ok], button.jenkins-button--primary, button.jenkins-button--destructive").first.click()
        info["state_text"] = re.search(r"State (.{0,40})", re.sub(r"\s+", " ", m.text())).group(0)
        m.shot("#main-panel", "R3-G-03-revoked")
    info["after"] = api("requester", "/job/prod/job/ok-move/configure").status_code
    console_ok("G", m, "manager revoke")
    m.done()
    check("G", "holder sees no Revoke; manager's Cancel keeps the window; Revoke ends it; configure 200 -> 403",
          "Revoke" not in hb and info["manager_revoke"] == 1 and info.get("after_cancel") == 200 and before == 200 and info["after"] == 403
          and "Revoked by manager" in info.get("state_text", ""), **info)
    r = api("requester", f"/batch-control/grants/{gid}/revoke", "POST")
    check("G", "holder's direct POST to revoke refused", r.status_code in (403, 404), status=r.status_code)


# ---------------------------------------------------------------- H
def run_dialog(s, where, tag):
    s.page.locator(where, has_text="Request Run").first.click()
    s.page.wait_for_selector("dialog[open] textarea[name=reason]")
    url_open = s.page.url
    d = s.page.locator("dialog[open]").first
    submit_and_wait(s.page, d.get_by_role("button", name="Submit Request").click)  # e2e-20: was a fixed 1.5 s
    stays = s.page.locator("dialog[open]").count() == 1
    errs = [t.strip() for t in s.page.locator("dialog[open] .error, dialog[open] .jenkins-alert").all_inner_texts() if t.strip()][:3]
    d = s.page.locator("dialog[open]").first
    d.locator("textarea[name=reason]").fill(f"e2e-14 {tag} run dialog")
    tick(d, "approvers", "approver-1")
    s.shot("dialog[open]", f"R3-H-{tag}-run-dialog")
    landing = submit_dialog(s, d, "Submit Request")
    return url_open, stays, errs, landing


def grant_dialog(s, where, tag):
    """D-71: the prefill is the item's full name; the kind comes from the name check (no scope type selector)."""
    s.page.locator(where, has_text="Request Change Permission").first.click()
    s.page.wait_for_selector("dialog[open] input[name=scopeFullName]")
    url_open = s.page.url
    d = s.page.locator("dialog[open]").first
    kind, _, icon = dialog_kind(d)
    kind_short = next((k for k, rx in KIND_RE.items() if kind and re.search(rx, kind)), kind)
    pre = (kind_short if icon and d.locator("select[name=scopeType]").count() == 0 else f"{kind_short} (selector or no icon)",
           d.locator("input[name=scopeFullName]").input_value())
    tick(d, "actions", "CONFIGURE")
    d.locator("select[name=durationMinutes]").select_option("15")
    d.locator("textarea[name=reason]").fill(f"e2e-14 {tag} grant dialog")
    tick(d, "approvers", "approver-2")
    s.shot("dialog[open]", f"R3-H-{tag}-grant-dialog")
    landing = submit_dialog(s, d, "Request Grant")
    return url_open, pre, landing


def sec_H():
    # new job page: requester, app bar Request Run, overflow menu Request Change Permission
    s = Session("requester")
    s.go("/job/batch-daily/")
    u, stays, errs, landing = run_dialog(s, ".jenkins-app-bar a, .jenkins-app-bar button, [data-testid] button, a, button", "new")
    st = api("requester", re.sub(r"^.*?/batch-control/", "/batch-control/", landing)).status_code
    check("H", "new job page: Request Run dialog in place, empty submit stays with errors, full submit lands on UUID detail (200)",
          u.endswith("/job/batch-daily/") and stays and errs and re.search(UUID, landing) and st == 200, url_open=u.replace(BASE, ""), errors=errs,
          landing=landing.replace(BASE, ""), server=st)
    s.go("/job/batch-pipeline/")
    # the app bar's overflow by its test id: a role locator "More actions" resolves to the header's own menu button first
    # (title="More actions"; found from the e2e-20 trace of this step)
    ov = s.page.get_by_test_id("app-bar-overflow-button")
    info = {"overflow": ov.count()}
    if ov.count():
        ov.first.click()
        s.page.locator(".tippy-box a, .tippy-box button, .jenkins-dropdown a, .jenkins-dropdown button").first.wait_for(state="visible")
        u, pre, landing = grant_dialog(s, ".tippy-box a, .tippy-box button, .jenkins-dropdown a, .jenkins-dropdown button", "new")
        info.update(url_open=u.replace(BASE, ""), prefill=pre, landing=landing.replace(BASE, ""))
    check("H", "new job page: More actions -> Request Change Permission dialog pre-filled batch-pipeline, kind Pipeline with icon, lands on UUID grant",
          info.get("prefill") == ("Pipeline", "batch-pipeline") and re.search("/grants/" + UUID, info.get("landing", "")), **info)
    console_ok("H", s, "new job page dialogs", expected=("400 POST",))
    s.done()
    # classic: user classic
    groovy("""def u = hudson.model.User.getById('classic', true); def m = new HashMap(); m.put('new-job-page.flag', 'false')
u.addProperty(new jenkins.model.experimentalflags.UserExperimentalFlagsProperty(m)); u.save(); return 'ok'""")
    s = Session("classic")
    s.go("/job/batch-pipeline/")
    u, stays, errs, landing = run_dialog(s, "#tasks a, #tasks button", "classic")
    check("H", "classic side panel: Request Run dialog, validation, UUID landing", stays and errs and re.search("/requests/" + UUID, landing),
          url_open=u.replace(BASE, ""), errors=errs, landing=landing.replace(BASE, ""))
    s.go("/job/batch-daily/")
    u, pre, landing = grant_dialog(s, "#tasks a, #tasks button", "classic")
    check("H", "classic side panel: Request Change Permission pre-filled batch-daily, kind Freestyle with icon, UUID landing",
          pre == ("Freestyle", "batch-daily") and re.search("/grants/" + UUID, landing), url_open=u.replace(BASE, ""), prefill=pre, landing=landing.replace(BASE, ""))
    console_ok("H", s, "classic dialogs", expected=("400 POST",))
    s.done()
    # folder page: requester on the folder prod (D-71: the window names the folder itself). Not team: the seed gave
    # requester Configure and Create windows on team, and a user who holds every permission a window can carry on a
    # folder is offered no entry there (FolderGrantRequestAction)
    s = Session("requester")
    s.go("/job/prod/")
    u, pre, landing = grant_dialog(s, "#tasks a, #tasks button, .jenkins-app-bar a, .jenkins-app-bar button", "folder")
    check("H", "folder page prod: Request Change Permission pre-filled prod, kind Folder with icon, UUID landing",
          pre == ("Folder", "prod") and re.search("/grants/" + UUID, landing), url_open=u.replace(BASE, ""), prefill=pre, landing=landing.replace(BASE, ""))
    console_ok("H", s, "folder dialog")
    s.done()


# ---------------------------------------------------------------- I
def sec_I():
    ids = json.loads((lib.HERE / "out" / "ids.json").read_text())
    uu = {k: v for k, v in ids.items() if isinstance(v, str) and k[0] in "rag" and "_" in k}
    check("I", "every seeded request/activation/grant id is a UUID", all(re.fullmatch(UUID, v) for v in uu.values()), n=len(uu))
    res = {}
    for p in ("/batch-control/grants/00000000-0000-4000-8000-000000000000/", "/batch-control/grants/no-such-id/",
              "/batch-control/requests/00000000-0000-4000-8000-000000000000/", "/batch-control/activations/00000000-0000-4000-8000-000000000000/",
              f"/batch-control/requests/{ids['g_active_job']}/", f"/batch-control/grants/{ids['req_executed']}/",
              "/batch-control/grants/..%2F..%2Fconfig.xml/", "/batch-control/requests/20261004-000000-abcdef/"):
        res[p] = api("admin", p).status_code
    check("I", "unknown ids, an id under the wrong kind, traversal and the old timestamp format: 404", all(v in (404, 400) for v in res.values()), **res)
    xml = groovy("""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control')
return ['grants','requests/run','requests/grant','requests/activation'].collect{ s -> def f = new File(d, s); s + '=' + (f.exists() ? f.list().findAll{it.endsWith('.xml')}.take(2) : 'missing') }.join('; ')""")
    check("I", "store file names are UUIDs (ARCHITECTURE storage layout)", re.search(UUID + r"\.xml", xml) is not None, files=xml)


for sec in WANT:
    try:
        globals()["sec_" + sec]()
    except Exception as e:  # noqa
        check(sec, "section raised", False, error=repr(e)[:400])
close()
print("SUMMARY round3", N)
