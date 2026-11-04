"""e2e-15: the activation page of a multibranch project (computed folder, classic layout kept by the DEF-08 fix) and
of its branch jobs, plus the DEF-08 job pages, for a new-job-page user and a classic user.

Per (account, flag, page): every link and button in the side panel, app bar, breadcrumbs, tab bar and main panel,
including dropdown templates (overflow menu, breadcrumb menus). Checks: no anchor with an empty/missing href, no
duplicate visible entry in the side panel or app bar (outside core's build history, Build Queue and Build Executor
Status widgets, whose entries repeat by design: ci/h1_rules.py r15_duplicates), every same-origin href answers < 400
to a GET with the account's own session (core's stop buttons in those widgets may answer 405, ci/h1_rules.py
r15_core_stop; POST-only links are recorded, not fetched; /logout is never fetched), no console error, no
response >= 400 while loading. Anchors with an empty href are clicked to see whether they do anything.

Usage: check.py crawl            (requester, admin x flag true/false; approver-1 404 check)
       check.py submit <flag> <page> <user>
Writes out/check.jsonl, screenshots run-15/C-<flag>-<user>-<page>.png."""
import json
import re
import sys
from urllib.parse import urljoin, urlparse

import lib
from lib import Session, close, log, api, groovy, BASE, opened, gone, react

PAGES = ["/job/team-mb/batch-control-activation/",
         "/job/team-mb/job/main/batch-control-activation/",
         "/job/team-mb/job/feature-1/batch-control-activation/",
         "/job/batch-pipeline/batch-control-activation/",
         "/job/batch-daily/batch-control-activation/"]

COLLECT = r"""() => {
  const region = el => el.closest('#side-panel') ? 'side' : el.closest('.jenkins-app-bar, .app-build-bar') ? 'appbar'
    : el.closest('#breadcrumbBar, .jenkins-breadcrumbs') ? 'crumb' : el.closest('.jenkins-tab-bar, .tabBar, .jenkins-tabs') ? 'tabs'
    : el.closest('#main-panel') ? 'main' : el.closest('footer') ? 'footer' : el.closest('header, #page-header') ? 'header' : 'other';
  const vis = el => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
  const out = [];
  document.querySelectorAll('a, button').forEach(el => {
    out.push({tag: el.tagName.toLowerCase(), text: (el.innerText || el.getAttribute('aria-label') || el.title || '').trim().replace(/\s+/g, ' '),
      href: el.tagName === 'A' ? el.getAttribute('href') : (el.dataset.href ? (el.dataset.baseUrl && !/^[\/]|:/.test(el.dataset.href) ? el.dataset.baseUrl + el.dataset.href : el.dataset.href) : null),
      region: region(el), visible: vis(el), id: el.id || null, help: el.classList.contains('jenkins-help-button'),
      buildNow: el.dataset.type === 'build-now',
      cls: el.className && el.className.baseVal === undefined ? el.className : '', post: el.dataset.post || el.dataset.callback || null,
      confirm: el.dataset.confirmationType || el.getAttribute('data-message') || null, type: el.getAttribute('type'),
      inForm: !!el.closest('form'),
      widget: (w => w ? w.id : null)(el.closest('#side-panel #jenkins-build-history, #side-panel #jenkins-builds, #side-panel div.pane-frame#buildQueue, #side-panel div.pane-frame#executors'))});
  });
  document.querySelectorAll('template').forEach(t => {
    const host = t.parentElement;
    const r = host ? region(host) : 'other';
    t.content.querySelectorAll('[data-dropdown-href], [data-dropdown-text]').forEach(d => out.push({tag: 'menu', text: d.dataset.dropdownText || '',
      href: d.dataset.dropdownHref || null, region: r + '-menu', visible: false, cls: d.dataset.dropdownType || '',
      post: d.dataset.dropdownPost || null, confirm: d.dataset.dropdownConfirmationTitle || null}));
  });
  return out;
}"""

# e2e-11 UX 2: the same line appears on the core job page /job/<job>/ itself for a new-job-page user (probe.py)
KNOWN_CONSOLE = "Refused to execute script from"
ROOT_PATH = urlparse(BASE).path.rstrip("/")  # the context path (/jenkins); stop URLs are matched relative to it
KNOWN_GET_405 = re.compile(r"/(build|toggleCollapse|doDelete|reload|disable|enable)(\?.*)?$")


def slug(p):
    return p.strip("/").replace("job/", "").replace("/", "_")


def check_page(s, user, flag, path):
    s.console.clear(); s.bad.clear()
    r = s.go(path)
    row = {"user": user, "flag": flag, "page": path, "status": r.status if r else None}
    if not r or r.status != 200:
        log("check", row); return row
    p = s.page
    p.wait_for_timeout(800)
    els = p.evaluate(COLLECT)
    row["layout"] = "job-subpage" if p.locator(".jenkins-app-bar .jenkins-build-caption, #main-panel .jenkins-tab-bar, .jenkins-tab-bar").count() and not p.locator("#side-panel #tasks").count() else (
        "classic-sidepanel" if p.locator("#side-panel #tasks, #side-panel .task").count() else "other")
    row["side_tasks"] = [e["text"] for e in els if e["region"] == "side" and e["text"]]
    row["appbar"] = [e["text"] for e in els if e["region"] == "appbar" and e["text"]]
    row["menus"] = [(e["region"], e["text"], e["href"]) for e in els if e["tag"] == "menu"]
    problems = []
    # empty href
    for e in els:
        if e["region"] in ("footer",):
            continue
        if e["tag"] == "a" and (e["href"] is None or e["href"].strip() in ("", "#")) and e["visible"]:
            if e.get("id") == "skip2content" or e.get("help"):
                continue  # core's skip-link target and help buttons (script-driven, href "#")
            if e["post"] and e["confirm"]:
                row.setdefault("confirmation_links", []).append(e["text"])  # core confirmation link, clicked below
            else:
                problems.append({"kind": "empty_href", **e})
        if e["tag"] == "menu" and e["cls"] in ("ITEM", "") and not e["href"] and e["text"] and not e["post"]:
            problems.append({"kind": "menu_no_href", **e})
    # duplicates in side panel / app bar, outside core's repeating widgets (ci/h1_rules.py r15_duplicates)
    dups, row["widget_duplicates"] = lib.h1.r15_duplicates(els)
    problems += dups
    # fetch every same-origin href
    fetched = {}
    base_host = urlparse(BASE).netloc
    for e in els:
        h = e["href"]
        if not h or h.startswith(("javascript:", "mailto:", "#")) or e["region"] == "footer":
            continue
        u = urljoin(p.url, h)
        if urlparse(u).netloc != base_host or "/logout" in u:
            continue
        if e["post"] or e.get("buildNow") or (e["confirm"] and e["tag"] != "menu"):
            fetched.setdefault(u, "post-only")
            continue
        if u in fetched:
            continue
        try:
            resp = p.request.get(u, max_redirects=5)
            fetched[u] = resp.status
        except Exception as ex:  # noqa: BLE001
            fetched[u] = f"error {ex}"
        st = fetched[u]
        if isinstance(st, int) and st >= 400:
            path_ = urlparse(u).path
            pu = urlparse(u)
            root = pu.path[len(ROOT_PATH):] if pu.path.startswith(ROOT_PATH + "/") else pu.path
            stop = lib.h1.r15_core_stop(e, root + ("?" + pu.query if pu.query else ""), st)  # core's widget stop buttons
            kind = stop or ("known_core_get_405" if st == 405 and KNOWN_GET_405.search(path_) else
                    "known_core" if (st == 404 and (path_.endswith("/toggleCollapse") or path_.endswith("/ws/"))) else "link_status")
            problems.append({"kind": kind, "text": e["text"], "region": e["region"], "url": u, "status": st})
    row["links_fetched"] = len(fetched)
    row["fetched"] = fetched
    # click dead anchors (side/appbar) to see whether anything happens
    # core confirmation links: clicking opens the confirmation dialog (then cancelled)
    for t in row.get("confirmation_links", []):
        loc = p.locator("#side-panel a.confirmation-link").filter(has_text=t)
        if loc.count():
            loc.first.click(); opened(p)  # e2e-20: dialog waits instead of fixed 0.8 / 0.4 s
            row.setdefault("confirmation_click", {})[t] = p.locator("dialog[open]").count()
            p.keyboard.press("Escape"); gone(p)
    # app-bar "More actions" dropdown (new job page): items must have a target, no duplicates, targets < 400
    more = p.get_by_test_id("app-bar-overflow-button")
    if more.count():
        more.first.click()
        try:  # e2e-20: the menu's entries (was a fixed 1.5 s)
            p.locator(".tippy-box .jenkins-dropdown__item").first.wait_for(state="visible", timeout=5000)
        except Exception:
            pass
        items = p.evaluate("""() => [...document.querySelectorAll('.tippy-box .jenkins-dropdown__item')].map(i => ({text: i.innerText.trim(),
            tag: i.tagName.toLowerCase(), href: i.getAttribute('href'), post: i.dataset.post || null}))""")
        row["more_actions"] = items
        texts = [i["text"] for i in items]
        for t in set(texts):
            if texts.count(t) > 1:
                problems.append({"kind": "duplicate", "region": "more-actions", "text": t, "count": texts.count(t)})
        for i in items:
            if i["tag"] == "a" and not (i["href"] or "").strip():
                problems.append({"kind": "empty_href", "region": "more-actions", **i})
            elif i["href"] and not i["post"]:
                u = urljoin(p.url, i["href"])
                if u not in fetched and "/logout" not in u:
                    fetched[u] = p.request.get(u, max_redirects=5).status
                    if fetched[u] >= 400:
                        problems.append({"kind": "link_status", "region": "more-actions", "text": i["text"], "url": u, "status": fetched[u]})
        s.shot(".tippy-box", f"C-{flag}-{user}-{slug(path)}-more")
        p.keyboard.press("Escape"); gone(p, ".tippy-box")
    for i, pr in enumerate([x for x in problems if x["kind"] == "empty_href" and x["region"] in ("side", "appbar")]):
        loc = p.locator(f"#side-panel a, .jenkins-app-bar a").filter(has_text=pr["text"])
        before = p.url
        try:
            loc.first.click(timeout=3000); react(p, before, timeout=1200, menus=".tippy-box", menus_before=0)
            pr["click"] = {"url_changed": p.url != before, "dialog": p.locator("dialog[open]").count(), "menu": p.locator(".tippy-box").count()}
        except Exception as ex:  # noqa: BLE001
            pr["click"] = f"error {ex}"[:200]
        if p.url != before:
            s.go(path)
    row["problems"] = problems
    row["console"] = list(s.console)
    row["bad_responses"] = list(s.bad)
    row["form"] = p.locator('form[name="batch-control-activation"]').count()
    row["shot"] = s.shot(["#side-panel", "#main-panel"] if p.locator("#side-panel").count() else ["#main-panel"],
                         f"C-{flag}-{user}-{slug(path)}")
    row["console_known"] = [c for c in row["console"] if KNOWN_CONSOLE in c]
    row["console"] = [c for c in row["console"] if KNOWN_CONSOLE not in c]
    row["ok"] = not [x for x in problems if not x["kind"].startswith("known_core")] and not row["console"] and not row["bad_responses"]
    log("check", row)
    return row


def set_flag(users, val):
    return groovy("""import jenkins.model.experimentalflags.*
def out = []
%s.each { id -> def u = hudson.model.User.getById(id, true); def m = new HashMap(); m.put('new-job-page.flag', '%s')
u.addProperty(new UserExperimentalFlagsProperty(m)); u.save(); out << id + '=' + new NewJobPageUserExperimentalFlag().getFlagValue(u) }
return out""" % (json.dumps(users), val))


def crawl():
    for flag in ("true", "false"):
        print(set_flag(["requester", "admin"], flag))
        for user in ("requester", "admin"):
            s = Session(user)
            for path in PAGES:
                row = check_page(s, user, flag, path)
                print(json.dumps({k: row.get(k) for k in ("user", "flag", "page", "status", "layout", "side_tasks", "appbar", "more_actions", "confirmation_click", "links_fetched", "console_known", "ok")}))
                for pr in row.get("problems", []):
                    print("   ", json.dumps(pr)[:400])
                for c in row.get("console", []) + row.get("bad_responses", []):
                    print("    !", c[:300])
            s.done()
    s = Session("approver-1")
    for path in PAGES:
        r = s.go(path)
        row = {"user": "approver-1", "page": path, "status": r.status, "expected": 404}
        log("check", row); print(json.dumps(row))
    s.done()


def submit(flag, path, user):
    print(set_flag([user], flag))
    s = Session(user)
    s.console.clear(); s.bad.clear()
    s.go(path)
    p = s.page
    row = {"user": user, "flag": flag, "page": path, "step": "submit"}
    form = p.locator('form[name="batch-control-activation"]')
    row["form"] = form.count()
    row["action"] = form.first.get_attribute("action") if form.count() else None
    if not form.count():
        row["main"] = s.text()[:600]
        row["main_links"] = s.page.evaluate("() => [...document.querySelectorAll('#main-panel a[href]')].map(a => [a.innerText.trim(), a.getAttribute('href')])")
        row["shot"] = s.shot("#main-panel", f"S-{flag}-{user}-{slug(path)}-carried")
        log("check", row); print(json.dumps(row)); s.done(); return
    # empty submit first: validation message, still on the form
    with p.expect_navigation():  # e2e-20: the submit's navigation (was load + a fixed 0.5 s)
        form.locator('button[type="submit"], button[name="Submit"]').first.click()
    p.wait_for_load_state("load")
    row["empty_submit_text"] = re.findall(r"(Enter a reason[^.]*\.|Check at least one approver\.)", s.text())
    form = p.locator('form[name="batch-control-activation"]')
    form.locator('textarea[name="reason"]').fill(f"e2e-15 submit from {path} (new job page {flag})")
    form.locator('input[name="approvers"] ~ label, label.attach-previous').first.click()  # the label covers the box
    row["approver_checked"] = form.locator('input[name="approvers"]').first.is_checked()
    s.shot('form[name="batch-control-activation"]', f"S-{flag}-{user}-{slug(path)}-filled")
    with p.expect_navigation():  # e2e-20: the submit's navigation (was load + a fixed 0.8 s)
        form.locator('button[type="submit"], button[name="Submit"]').first.click()
    p.wait_for_load_state("load")
    row["landed"] = p.url
    m = re.search(r"/batch-control/activations/([0-9a-f-]{36})/", p.url)
    row["uuid"] = m.group(1) if m else None
    row["landed_text"] = s.text()[:400]
    row["shot"] = s.shot("#main-panel", f"S-{flag}-{user}-{slug(path)}-landed")
    if m:
        r = api("admin", f"/batch-control/activations/{m.group(1)}/")
        row["server_detail_status"] = r.status_code
        row["server_detail_pending"] = "Pending" in r.text or "PENDING" in r.text
    row["console"] = list(s.console); row["bad_responses"] = list(s.bad)
    row["ok"] = bool(m) and row.get("server_detail_status") == 200 and not row["console"]
    log("check", row)
    print(json.dumps(row)[:1500])
    s.done()


if __name__ == "__main__":
    try:
        if sys.argv[1] == "crawl":
            crawl()
        else:
            submit(sys.argv[2], sys.argv[3], sys.argv[4])
    finally:
        close()
