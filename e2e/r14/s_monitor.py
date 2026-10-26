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
Judged at the end by ci/h1_rules.py (judge_targeted "monitor"). -> out/monitor.jsonl"""
import re
from lib import Session, close, groovy, BASE, opened, gone, react
import lib


def strategy():
    return groovy("return jenkins.model.Jenkins.get().getAuthorizationStrategy().getClass().simpleName").replace("Result: ", "")


row = {"before": strategy()}
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
lib.log("monitor", row); print(row)
lib.h1.report("monitor", [row])
