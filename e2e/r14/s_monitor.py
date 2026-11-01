"""e2e-12: the strategy monitor's Install control and Mark as reviewed on /manage/.

H1 (2026-10-24, triage T-05): this step used to expect a reverted strategy, but it runs after misc S, which installs the
Batch Control strategy again, so Install was never there and only Mark as reviewed ran. It now sets up its own premise,
and the one misc S cannot reach: matrix-auth's *global* matrix (SPEC 8, D-35d: the monitor warns before its install
action that converting makes per-item authorization properties effective, and the install action asks for
confirmation; misc S covers the project matrix, which installs without a dialog). The step
  1. keeps the installed strategy object aside (as r18/final.py S does) and installs a GlobalMatrixAuthorizationStrategy
     with every entry of the current one (BatchControlMatrixAuthorizationStrategy.copyEntries does the same), so every
     account keeps its global permissions;
  2. on /manage/: the warning ("Before you install:"), Install -> the confirmation dialog -> Cancel (the strategy stays
     the global matrix) -> Install -> OK (the Batch Control matrix strategy is installed, Install is gone);
  3. puts the kept strategy object back (finally), so the following steps see the arrangement unchanged;
  4. Mark as reviewed from the monitor, if one is listed: Cancel keeps it, OK removes one entry, the landing page's
     links answer below 400.
#31 (DEF-04, docs/reports/e2e-06.md): the monitor's "Mark as reviewed" buttons were forms that core floats to the right
edge, each on its own staggered line away from its item, and "Before you install" showed the monitor's icon twice (core's
alert icon plus an inline one). The step first arranges an item of its own in the "changed under a grant" list (R31_ITEM:
the requester saves it under an approved Configure window, the window is then revoked), so a button is always listed,
and measures the monitor (MONITOR JS) on both /manage/ visits (global matrix with "Before you install", and the Batch
Control strategy): each Mark as reviewed button sits on the last line of its item's text (vertical centres within 2 px)
and starts right after it (gap 0..24 px), the controls' buttons share one top (2 px), the alert shows one icon outside
its buttons and each button at most one. The arranged item is marked reviewed again at the end (REST) if still listed.
Judged at the end by ci/h1_rules.py (judge_targeted "monitor"). -> out/monitor.jsonl"""
import re
import urllib.parse
from lib import Session, close, groovy, api, BASE, opened, gone, react
import lib

R31_ITEM = "r31-monitor-layout"
MONITOR = r"""() => {
  const a = document.querySelector('[data-monitor-id="batch-control-strategy"]');
  if (!a) return null;
  const r1 = b => [b.left, b.top, b.right, b.bottom].map(Math.round);
  const mid = b => (b.top + b.bottom) / 2;
  const out = {before_install: a.innerText.includes('Before you install:'), marks: [], controls: [], icons: 0, button_icons: []};
  // icons of the alert itself: every svg/img/icon outside a button, a link styled as a button and the controls slot
  const icons = [...a.querySelectorAll('svg, img, .icon-sm, .icon-md')].filter(e => !e.closest('.jenkins-button, button, .app-adminmonitor-controls')
      && !(e.parentElement && e.parentElement.closest('svg')));
  out.icons = icons.length;
  out.icon_classes = icons.map(e => (e.getAttribute('class') || e.tagName).slice(0, 60));
  for (const b of a.querySelectorAll('.jenkins-button, button')) {
    const n = [...b.querySelectorAll('svg, img')].filter(e => !(e.parentElement && e.parentElement.closest('svg'))).length;
    if (n > 1) out.button_icons.push({text: b.innerText.trim().slice(0, 40), icons: n});
  }
  for (const b of a.querySelectorAll('.app-adminmonitor-controls > .jenkins-button, .app-adminmonitor-controls > button, .app-adminmonitor-controls > a, .app-adminmonitor-controls form .jenkins-button'))
    out.controls.push({text: b.innerText.trim().slice(0, 40) || b.getAttribute('title'), box: r1(b.getBoundingClientRect())});
  for (const li of a.querySelectorAll('li')) {
    const btn = [...li.children].find(e => /Mark as reviewed/.test(e.innerText || '')) || li.querySelector(':scope > form');
    if (!btn) continue;
    const button = btn.matches('form') ? btn.querySelector('button') || btn : btn;
    // the item's own text before the button (its link and the "changed by" note); a nested runs list moves the button
    // below by design and is not measured
    const prev = [...li.children].filter(e => e !== btn && e.compareDocumentPosition(btn) & Node.DOCUMENT_POSITION_FOLLOWING);
    const nested = prev.some(e => e.matches('div, ul'));
    const last = prev.filter(e => e.matches('a, span, code')).pop();
    const rects = last ? [...last.getClientRects()] : [];
    const line = rects.length ? rects[rects.length - 1] : null;
    const bb = button.getBoundingClientRect();
    out.marks.push({item: (li.querySelector('code') || li).innerText.trim().slice(0, 60), nested: nested,
                    button: r1(bb), line: line ? r1(line) : null,
                    dy: line ? Math.round(Math.abs(mid(bb) - mid(line)) * 10) / 10 : null,
                    gap: line ? Math.round(bb.left - line.right) : null});
  }
  return out;
}"""


def arrange_r31():
    """#31 DEF-04 premise: R31_ITEM in the monitor's "changed under a grant" list (requester saves it under a window)."""
    groovy(f"""def j = jenkins.model.Jenkins.get(); if (j.getItem('{R31_ITEM}') == null) j.createProject(hudson.model.FreeStyleProject, '{R31_ITEM}')
return 'ok'""")
    data = [("scopeFullName", R31_ITEM), ("durationMinutes", "15"), ("reason", "#31 monitor layout"), ("actions", "CONFIGURE"),
            ("approvers", "approver-1")]
    r = api("requester", "/batch-control/grants/create", "POST", data=data)
    m = re.search(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", r.headers.get("Location", ""))
    out = {"grant_status": r.status_code, "grant": m.group(0) if m else None}
    if out["grant"]:
        out["approve"] = api("approver-1", f"/batch-control/grants/{out['grant']}/approve", "POST", data={"comment": "#31"}).status_code
        out["save"] = api("requester", f"/job/{R31_ITEM}/submitDescription", "POST",
                          data={"description": "edited under a window (#31 DEF-04)"}).status_code
        out["revoke"] = api("manager", f"/batch-control/grants/{out['grant']}/revoke", "POST", data={"comment": "#31"}).status_code
    out["listed"] = listed_r31()
    return out


def listed_r31():
    return groovy("""def m = jenkins.model.Jenkins.get().administrativeMonitors.find { it.class.simpleName == 'BatchControlStrategyMonitor' }
return m != null && m.itemsChangedUnderGrant.contains('%s')""" % R31_ITEM).replace("Result: ", "") == "true"


def strategy():
    return groovy("return jenkins.model.Jenkins.get().getAuthorizationStrategy().getClass().simpleName").replace("Result: ", "")


row = {"before": strategy()}
row["r31"] = arrange_r31()
row["stash"] = groovy("System.getProperties().put('e2e12.monitor.strategy', jenkins.model.Jenkins.get().authorizationStrategy); return 'ok'").replace("Result: ", "")
try:
    row["plain"] = groovy("""def j = jenkins.model.Jenkins.get(); def cur = j.authorizationStrategy
def g = new hudson.security.GlobalMatrixAuthorizationStrategy()
cur.getGrantedPermissionEntries().each { p, entries -> entries.each { e -> g.add(p, e) } }
j.setAuthorizationStrategy(g); j.save(); return j.authorizationStrategy.getClass().simpleName""").replace("Result: ", "")
    s = Session("admin")
    nc = len(s.console)
    s.go("/manage/")
    al = s.page.locator(".jenkins-alert", has_text="Batch Control")
    row["alerts"] = [re.sub(r"\s+", " ", t)[:300] for t in al.all_inner_texts()]
    row["warned"] = any("Before you install:" in t for t in row["alerts"])
    ctl = s.page.locator("#main-panel form button, #main-panel a[data-url], #main-panel button", has_text=re.compile("Install|Mark as reviewed"))
    row["controls"] = [re.sub(r"\s+", " ", t).strip() for t in ctl.all_inner_texts()]
    s.shot(".jenkins-alert", "S-monitor-global-matrix")
    row["layout_global"] = s.page.evaluate(MONITOR)
    s.shot("[data-monitor-id='batch-control-strategy']", "S-monitor-layout-global-matrix")
    inst = s.page.locator("#main-panel button, #main-panel a", has_text=re.compile(r"^\s*Install the Batch Control variant"))
    row["install"] = inst.count()
    if inst.count():
        inst.first.click()
        row["dialog"] = opened(s.page)
        d = s.page.locator("dialog[open]")
        row["dialog_text"] = re.sub(r"\s+", " ", d.first.inner_text())[:300] if d.count() else None
        if d.count():
            d.first.locator("button[data-id=cancel]").click()
            row["cancel_closes"] = gone(s.page)
            row["after_cancel"] = strategy()
            inst.first.click()
            opened(s.page)
            before = s.page.url
            s.page.locator("dialog[open] button[data-id=ok]").click()
            react(s.page, before, timeout=10000)
        s.page.wait_for_load_state("load")
        row["landing"] = s.page.url.replace(BASE, "")
        row["after_ok"] = strategy()
        s.go("/manage/")
        row["install_gone"] = s.page.locator("#main-panel button, #main-panel a", has_text=re.compile(r"^\s*Install the Batch Control variant")).count() == 0
        s.shot("#main-panel", "S-after-install-global")
    row["console"] = s.console[nc:][:10]
    s.done()
finally:
    row["restored"] = groovy("""def j = jenkins.model.Jenkins.get(); def s = System.getProperties().get('e2e12.monitor.strategy')
if (s == null) return 'none'
j.setAuthorizationStrategy(s); j.save(); System.getProperties().remove('e2e12.monitor.strategy'); return j.authorizationStrategy.getClass().simpleName""").replace("Result: ", "")

# Mark as reviewed from the monitor, if listed (a new context)
s = Session("admin")
nc = len(s.console)
s.go("/manage/")
row["layout_bc"] = s.page.evaluate(MONITOR)
s.shot("[data-monitor-id='batch-control-strategy']", "S-monitor-layout-batch-control")
mr = s.page.locator("a[data-url*='markReviewed']")
row["mark_links"] = mr.count()
if mr.count():
    mr.first.click(); opened(s.page)
    s.page.locator("dialog[open] button[data-id=cancel]").click(); gone(s.page)
    s.go("/manage/"); row["after_mark_cancel"] = s.page.locator("a[data-url*='markReviewed']").count()
    s.page.locator("a[data-url*='markReviewed']").first.click(); opened(s.page)
    before = s.page.url
    s.page.locator("dialog[open] button[data-id=ok]").click(); react(s.page, before, timeout=10000)
    s.page.wait_for_load_state("load")
    row["mark_landing"] = s.page.url.replace(BASE, "")
    row["mark_text"] = re.sub(r"\s+", " ", s.text())[:220]
    row["mark_links_on_page"] = [(a.inner_text().strip()[:30], s.context.request.get(a.evaluate("e=>e.href")).status) for a in s.page.locator("#main-panel a[href]").all() if not (a.get_attribute("href") or "#").startswith("#")][:6]
    s.go("/manage/"); row["after_mark_ok"] = s.page.locator("a[data-url*='markReviewed']").count()
row["console"] = row.get("console", []) + s.console[nc:][:10]
s.done(); close()
if listed_r31():  # #31: leave the list as the arrangement found it
    row["r31_cleanup"] = api("admin", "/administrativeMonitor/batch-control-strategy/markReviewed?item="
                             + urllib.parse.quote(R31_ITEM, safe=""), "POST").status_code
row["r31_listed_after"] = listed_r31()
lib.log("monitor", row); print(row)
lib.h1.report("monitor", [row])
