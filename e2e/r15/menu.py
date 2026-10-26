"""e2e-15: the new job page app-bar "More actions" menu on the job-subpage activation pages vs the same menu on the
job's own page (requester, admin; new job page on). Items: text, tag, href; duplicates; empty-href anchors; each
URL-less item clicked (Permalinks must open its submenu, Request Change Permission its dialog).
Usage: menu.py [tag]   -> out/menu.jsonl, run-15/M-<tag>-<user>-<page>.png
H1 (2026-10-24): judged at the end by ci/h1_rules.py (judge_r15 "menu"): every linked entry answers 200, no duplicate
or empty-href entry, Request Change Permission opens its dialog, Permalinks its submenu; console errors only through
the allowlist (D-70, exact message: triage X-01).
Submenus (CI run 40183795439): a submenu entry is hovered, then clicked; after each step the menu state (boxes and the
submenu's entries, by name: the dropdown items that are not main-menu entries) is read once it has not changed for
0.4 s. Core opens a submenu on hover; a click on an entry whose submenu is already open may toggle it closed, so the
judge takes the submenu as opened when the hover or the click showed its entries, and compares the activation page's
Permalinks entries with the job page's (same user, same job)."""
import json, sys, time
from urllib.parse import urljoin
import lib
from lib import Session, close, log, groovy, gone, react, until
ROWS = []
TAG = sys.argv[1] if len(sys.argv) > 1 else "a"
JOBS = ["/job/team-mb/job/main/", "/job/team-mb/job/feature-1/", "/job/batch-pipeline/", "/job/batch-daily/"]
ITEMS = """() => [...document.querySelectorAll('.tippy-box .jenkins-dropdown__item')].map(i => ({text: i.innerText.trim(),
  tag: i.tagName.toLowerCase(), href: i.getAttribute('href'), cls: i.className}))"""
print(groovy("""import jenkins.model.experimentalflags.*
['requester','admin'].each { id -> def u = hudson.model.User.getById(id, true); def m = new HashMap(); m.put('new-job-page.flag', 'true')
u.addProperty(new UserExperimentalFlagsProperty(m)); u.save() }; return 'ok'"""))


def submenu(p, main_texts):
    """The entries of an open submenu: the dropdown items now shown that are not entries of the main menu."""
    return [i for i in p.evaluate(ITEMS) if i["text"] not in main_texts]


def settle(p, read, quiet=400, total=3000):
    """Core's menus are tippy boxes that animate in and out (and a submenu may still be opening or closing right after a
    hover or a click): poll read() every 50 ms until its value has not changed for `quiet` ms, at most `total` ms.
    Returns (value, settled)."""
    t0 = last_change = time.monotonic()
    v = read()
    while True:
        p.wait_for_timeout(50)  # poll interval of a condition wait
        try:
            w = read()
        except Exception:  # noqa: BLE001 (navigated away)
            return v, False
        now = time.monotonic()
        if w != v:
            v, last_change = w, now
        elif (now - last_change) * 1000 >= quiet:
            return v, True
        if (now - t0) * 1000 >= total:
            return v, False


def open_menu(s):
    p = s.page
    btn = p.get_by_test_id("app-bar-overflow-button")
    try:
        btn.first.wait_for(state="visible", timeout=10000)
    except Exception:  # noqa: BLE001
        return None
    p.wait_for_load_state("networkidle")
    btn.first.click()
    try:
        p.wait_for_selector(".tippy-box .jenkins-dropdown__item", timeout=6000)
    except Exception:  # noqa: BLE001
        return []
    return p.evaluate(ITEMS)


for user in ("requester", "admin"):
    s = Session(user)
    for job in JOBS:
        for page in (job, job + "batch-control-activation/"):
            s.console.clear(); s.bad.clear()
            s.go(page)  # e2e-20: open_menu() waits for the button and the network (a fixed 0.6 s was here)
            items = open_menu(s)
            row = {"user": user, "page": page, "items": items}
            if items:
                texts = [i["text"] for i in items]
                row["duplicates"] = sorted({t for t in texts if texts.count(t) > 1})
                row["empty_href_anchors"] = [i["text"] for i in items if i["tag"] == "a" and not (i["href"] or "").strip()]
                row["status"] = {}
                for i in items:
                    if i["href"]:
                        row["status"][i["text"]] = s.page.request.get(urljoin(s.page.url, i["href"]), max_redirects=5).status
                s.shot(".tippy-box", f"M-{TAG}-{user}-{page.strip('/').replace('job/', '').replace('/', '_')}")
                row["clicks"] = {}
                for t in [i["text"] for i in items if not i["href"]]:
                    s.page.keyboard.press("Escape"); gone(s.page, ".tippy-box")
                    s.go(page)
                    again = open_menu(s)
                    before_boxes = s.page.locator(".tippy-box").count()
                    hover = None
                    main_texts = {i["text"] for i in (again or items)}
                    state = lambda: (s.page.locator(".tippy-box").count(),  # noqa: E731
                                     tuple(i["text"] for i in submenu(s.page, main_texts)))
                    try:
                        s.page.locator(".tippy-box .jenkins-dropdown__item").filter(has_text=t).first.hover(timeout=5000)
                        until(s.page, lambda: s.page.locator(".tippy-box").count() > before_boxes, 1000)  # a submenu, if any
                        # CI run 40183795439: read the submenu once it stopped changing, not at the first box
                        (boxes, _), steady = settle(s.page, state)
                        hover = {"boxes": boxes, "items": submenu(s.page, main_texts), "settled": steady}
                    except Exception:  # noqa: BLE001
                        pass
                    try:
                        s.page.locator(".tippy-box .jenkins-dropdown__item").filter(has_text=t).first.click(timeout=5000)
                    except Exception:  # noqa: BLE001
                        row["clicks"][t] = {"not_found_on_reopen": [i["text"] for i in (again or [])]}
                        continue
                    react(s.page, timeout=1200, menus=".tippy-box", menus_before=before_boxes)
                    # a click on a submenu entry whose submenu the hover already opened may toggle core's tippy submenu
                    # closed, and the whole menu with it (CI run 40183795439: 2 boxes, 1 at +30 ms, 0 at +270 ms); the
                    # state is read once it settled, and the submenu entries are counted by name, not as a difference
                    (boxes_after, _), steady = settle(s.page, state) if not s.page.locator("dialog[open]").count() else ((None, ()), True)
                    sub = submenu(s.page, main_texts) if boxes_after else []
                    row["clicks"][t] = {"dialog": s.page.locator("dialog[open]").count(), "boxes_before": before_boxes,
                                        "boxes_after": s.page.locator(".tippy-box").count(), "settled": steady,
                                        "submenu_items": len(sub), "submenu": sub,
                                        "url": s.page.url.replace(s.page.url.split('/jenkins')[0], ''), "hover": hover}
            row["console"] = list(s.console); row["bad"] = list(s.bad)
            log("menu", row)
            ROWS.append(row)
            print(row['user'], row['page'], len(row['items'] or []))
    s.done()
close()
lib.h1.report("r15/menu", ROWS)
