#!/usr/bin/env python3
"""Self-test of the H1 fail rules (ci/h1_rules.py), no Jenkins, no browser.

    python3 e2e/ci/selftest_h1.py                        the recorded rows in ci/fixtures/h1-baseline.json.gz
    python3 e2e/ci/selftest_h1.py --baseline <dir>       the e2e-shard-1..7 artefact directories of a CI run instead
    python3 e2e/ci/selftest_h1.py --make-fixture <dir>   (re)write the fixture from such artefacts (main c347803c,
                                                         run 39612236057, for this commit)

1. Baseline replay. The rows the drivers recorded on main are judged unit by unit, as each driver judges its own run.
   They were recorded before the H1 driver fixes, so upgrade() first applies, row by row, what each fix changes in what
   is recorded (each step names its triage id); rows a fix makes unknowable are left out and listed. The replay must
   give no FAIL line except the first-row job chevron (triage U-1, crawl C-17): the old driver clicked it without
   hovering its link, and owner instruction 3 keeps it a FAIL until the fixed driver shows otherwise in CI.
2. Synthetic findings, each of which must give a FAIL line, and allowlist look-alikes (another URL, status, role,
   message or stack) that must not be hidden by an entry.
3. r15/check.py's duplicate check (h1.r15_duplicates) through the runner's verdict (shard.r15_problems): repeats
   inside core's side-panel widgets pass, repeats among task links or in the app bar fail.
4. STALE: an empty run reports the applicable allowlist entries as stale.
Exit 0 when every expectation holds; prints PASS/FAIL per check."""
import argparse
import copy
import glob
import gzip
import importlib.util
import json
import os
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("h1_rules", HERE / "h1_rules.py")
h1 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(h1)
FIXTURE = HERE / "fixtures" / "h1-baseline.json.gz"
PREFIX = "/jenkins"  # CI runs with compose.prefix.yml


# ------------------------------------------------------------------ reading CI artefacts
def _jsonl(f):
    out = []
    for line in Path(f).read_text().splitlines():
        try:
            out.append(json.loads(line))
        except ValueError:
            pass
    return out


def _split(rows, key):
    """Rows of consecutive driver invocations, split where the key changes (crawl: role and UI)."""
    out = []
    for r in rows:
        k = key(r)
        if not out or out[-1][0] != k:
            out.append((k, []))
        out[-1][1].append(r)
    return out


def read_artefacts(base):
    """The units of a CI run: [{"driver", "ctx", "rows", "shard"}] in the order the drivers ran."""
    units = []
    for d in sorted(glob.glob(os.path.join(base, "e2e-shard-*"))):
        shard = d.rsplit("-", 1)[1]
        out = os.path.join(d, "driver", "r14", "out")
        ids = json.loads(Path(out, "ids.json").read_text()) if Path(out, "ids.json").exists() else {}
        for log, profile in (("crawl.jsonl", "matrix"), ("crawl-role.jsonl", "role")):
            if Path(out, log).exists():
                for (role, ui), rows in _split(_jsonl(Path(out, log)), lambda r: (r.get("role"), r.get("ui"))):
                    units.append({"driver": "crawl", "shard": shard, "rows": rows,
                                  "ctx": {"role": role, "ui": ui, "ids": ids, "prefix": PREFIX, "profile": profile}})
        if Path(out, "actions.jsonl").exists():
            units.append({"driver": "actions", "shard": shard, "rows": _jsonl(Path(out, "actions.jsonl")), "ctx": {}})
        if Path(out, "jobui.jsonl").exists():
            for (ui,), rows in _split(_jsonl(Path(out, "jobui.jsonl")), lambda r: (r.get("ui"),)):
                units.append({"driver": "jobui", "shard": shard, "rows": rows,
                              "ctx": {"ui": ui, "roles": sorted({r.get("role") for r in rows}), "prefix": PREFIX}})
        for name, driver in (("misc", "misc"), ("errpages", "errpages"), ("listpager", "listpager"),
                             ("incident", "incident"), ("help", "help"), ("monitor", "monitor")):
            if Path(out, name + ".jsonl").exists():
                units.append({"driver": driver, "shard": shard, "rows": _jsonl(Path(out, name + ".jsonl")), "ctx": {}})
        logs = sorted(glob.glob(os.path.join(d, "logs", "*-targeted-helpcheck2.log")))
        if logs:  # helpcheck2.py printed "<page> <help> <before> -> <after>" and logged nothing before H1
            rows = [{"page": m[0], "help": m[1], "before": int(m[2]), "after": int(m[3])}
                    for m in re.findall(r"^(\S+) (\S+) (\d+) -> (\d+)$", Path(logs[0]).read_text(), re.M)]
            units.append({"driver": "help2", "shard": shard, "rows": rows, "ctx": {}})
        for name in ("grant_overlay", "assign_roles", "manage_roles", "manage_edit", "endpoints"):
            f = Path(out, "role", name + ".jsonl")
            if f.exists():
                units.append({"driver": "role/" + name, "shard": shard, "rows": _jsonl(f)[-1:], "ctx": {}})
        r15 = os.path.join(d, "driver", "r15", "out")
        for name in ("side_dialog", "folder_compare", "menu"):
            f = Path(r15, name + ".jsonl")
            if f.exists():
                units.append({"driver": "r15/" + name, "shard": shard, "rows": _jsonl(f), "ctx": {}})
    return units


def compact(units):
    """The fixture: what the rules read. Crawl rows the rules never judge (OK links, the content INFO rows, repeated
    clicks) are left out, and the remaining crawl rows are kept once per unit and URL pattern."""
    out = []
    for u in units:
        rows = u["rows"]
        if u["driver"] == "crawl":
            seen, keep = set(), []
            for r in rows:
                if r.get("kind") in ("content", "advanced", "select", "get-form") or r.get("result") in (
                        "OK", "external", "handler-only", "same-as-checked", "OK(405 on GET)", "checked-in-actions"):
                    continue
                if r.get("kind") == "click" and r.get("result") in ("menu", "navigated", "dom-changed") \
                        and not (r.get("detail") or {}).get("console"):
                    continue
                r = {k: v for k, v in r.items() if k not in ("h1", "final", "visible", "region", "bad_subrequests")}
                k = json.dumps([h1.page_key(r.get("page"), u["ctx"]["ids"], PREFIX), r.get("kind"), r.get("result"),
                                r.get("label"), r.get("target"), r.get("status"), (r.get("data") or {}).get("href"),
                                json.dumps(r.get("detail"), sort_keys=True)[:300] if r.get("kind") == "click" else None,
                                r.get("console")], sort_keys=True)
                if k not in seen:
                    seen.add(k)
                    keep.append(r)
            rows = keep
            ctx = dict(u["ctx"], ids={k: v for k, v in u["ctx"]["ids"].items() if isinstance(v, str)})
            u = dict(u, ctx=ctx)
        out.append(dict(u, rows=rows))
    return out


# ------------------------------------------------------------------ the driver fixes, applied to recorded rows
SKIPPED = []  # (unit, why) the replay leaves out
FIRST_WORD = re.compile(r"^(Pending|Approved|Executed|Rejected|Cancelled|Acknowledged|Open|Resolved)\b")


def upgrade(unit):
    """What each H1 driver fix changes in the recorded rows (main c347803c was recorded before the fixes)."""
    d, rows = unit["driver"], copy.deepcopy(unit["rows"])
    out = []
    for r in rows:
        if d == "crawl":
            if r.get("kind") == "page" and (r.get("status") or 0) >= 400:
                own = f"error: Failed to load resource: the server responded with a status of {r['status']} "
                hit = next((c for c in r.get("console") or [] if c.startswith(own)), None)
                if hit:  # C-12: the document's own status line is dropped by the driver
                    r["console"].remove(hit)
            if r.get("result") == "DEAD-HREF" and r.get("label") == "?":
                r.update(result="help-button", status=200)  # C-20: core's help "?"; helpcheck: every help URL answers 200
            if r.get("result") == "BROKEN" and r.get("status") == 405 and re.search(r"/(build|rebuild)(/|\?|$)", r.get("target") or "") \
                    and ((r.get("data") or {}).get("callback") or (r.get("data") or {}).get("taskPost") == "true"
                         or (r.get("label") or "").startswith("Schedule a Build")):
                r["result"] = "post-link"  # C-01/C-02 (the list view's build button: the old rows have no class; its label)
            if r.get("kind") == "click" and r.get("result") == "click-failed" and r.get("label") == "Newer builds":
                r["result"] = "inert"  # C-16: core's disabled "Newer builds" (pointer-events:none) is not clicked
            if r.get("kind") == "click" and r.get("result") == "not-visible-on-reload":
                SKIPPED.append((f"crawl {r.get('role')}/{r.get('ui')} {r.get('page')}",
                                "C-18: the old driver found the control again by its document index; what the signature "
                                "finds is only known from a new run"))
                continue
            det = r.get("detail") or {}
            if r.get("result") == "dialog" and "cancel_button" in det:
                # C-19: Cancel and the title close button counted apart; core's parameters dialog has only the latter
                core = r.get("label") in h1.CORE_PARAMS_DIALOG and not det["cancel_button"]
                det["close_button"] = 1 if core else 0
                if core:
                    det["cancel_closes"] = True
        elif d == "actions" and "probe_status" not in r:
            r["post_status"] = [r.get("status") or 302]  # A-03: the old rows have no POST status; the state changed
            if r.get("item") == "window" or (r.get("item") == "grant" and r.get("control") == "Approve"):
                # A-02: the server state is now the State row (the window), as on the page
                r["server_after"] = [r["server_after"][0], r.get("state_after_page")]
                for k in ("after_cancel_server", "after_escape_server"):
                    if r.get(k):
                        r[k] = [r[k][0], r.get("state_before")]
            else:
                for k in ("server_after", "after_cancel_server", "after_escape_server"):
                    if r.get(k):
                        m = FIRST_WORD.match(r[k][1] or "")
                        r[k] = [r[k][0], m.group(1) if m else r[k][1]]  # the row's text, not 90 characters of the page
        elif d == "jobui" and r.get("result") == "NOTHING" and r.get("notifications"):
            r["result"] = "notified"  # J-03
        elif d == "misc":
            if r.get("sec") == "C" and r.get("role") == "admin":
                r["link_visible"] = 1  # M-01: the old driver counted on the next page (assumed here; CI decides)
            if r.get("sec") == "C" and r.get("inline_validation"):
                r["inline_validation"] = list(dict.fromkeys(r["inline_validation"]))  # M-03
            if r.get("sec") in ("C", "B") and r.get("console"):
                # M-02/M-04: the 403 line came from an earlier page of the same session (/manage/ for manager,
                # requester's own /batch-control/history/)
                r["console"] = [c for c in r["console"] if h1.http_status(c) != 403]
            if r.get("sec") == "R" and r.get("role") == "nobc" and r.get("ui") == "new" and not r.get("build_entries"):
                # M-08: the new job page's entry is in the main panel; jobui shows its 400 refusal staying in the dialog
                r["build_entries"] = ["Direct Build (needs approval)"]
                r["after"] = {"dialog_open": 1}
            if r.get("sec") == "R" and r.get("case") == "move refusal" and not r.get("destinations"):
                SKIPPED.append(("misc R move refusal", "M-10: never reached on main (no destination); the premise is "
                                                       "new, only a new run shows the refusal page"))
                continue
        elif d == "help":
            for h in r.get("helps") or []:
                if h.get("expands") == "no-help-area":
                    h["expands"] = True  # T-01: helpcheck2 shows each of these clicks adds one visible help block
        out.append(r)
    if d == "crawl":
        SKIPPED.append(("crawl design references", "#31 (r6/s6.py): the recorded crawl rows carry no design-reference scan "
                                                   "(crawl.py records it since #31), so the replay waives the required "
                                                   "scan (ctx pre31_design_ref); the synthetic cases cover it"))
    if d == "help":
        SKIPPED.append(("help layout", "#31 E2E-D4: the recorded help rows carry no layout (helpcheck.py measures it since "
                                       "#31), so the replay waives the required layout (ctx pre31_layout); the synthetic "
                                       "cases cover it"))
    if d == "monitor":
        SKIPPED.append(("monitor", "T-05: the old step never had its premise (no Install control); the new step sets up "
                                   "the global matrix itself, only a new run has its rows"))
        return []
    return out


def is_u1(line):
    if not line.startswith("FAIL crawl click "):
        return False
    o = json.loads(line[len("FAIL crawl click "):].split(" example=", 1)[0])
    return (o.get("result") == "click-failed" and o.get("label") == "" and re.fullmatch(r"/job/[^/]+/", o.get("target") or "")
            and re.fullmatch(r"/batch-control/(dashboard/|incidents/|history/(\?kind=[a-z]+(&from=\{d\}&to=\{d\})?)?)", o.get("page") or ""))


def replay(units):
    fails, u1, stale, matched = [], [], set(), set()
    for u in units:
        rows = upgrade(u)
        if not rows and u["driver"] == "monitor":
            continue
        ctx = u["ctx"]
        if u["driver"] == "help":  # #31: the recorded help rows predate the E2E-D4 layout; it cannot be replayed
            ctx = dict(ctx or {}, pre31_layout=True)
        if u["driver"] == "crawl":  # #31: the recorded crawl rows predate the design-reference scan
            ctx = dict(ctx or {}, pre31_design_ref=True)
        lines = h1.judge(u["driver"], rows, ctx).lines()
        for line in lines:
            if line.startswith("FAIL "):
                (u1 if is_u1(line) else fails).append(f"[shard {u['shard']}] {line}")
            elif line.startswith("STALE allow "):
                stale.add(line.split()[2])
            elif line.startswith("ALLOW "):
                matched.add(line.split()[1])
    return fails, u1, sorted(stale - matched), sorted(matched)


# ------------------------------------------------------------------ synthetic findings
IDS = {"req_pending": "fe816659-ad2d-4b38-8475-72839699e461"}


def crawl_ctx(role="admin", ui="new", profile="matrix"):
    return {"role": role, "ui": ui, "ids": IDS, "prefix": PREFIX, "profile": profile}


# #31: the design-reference scan r14/crawl.py puts on every page row below 400 (a clean page as main renders it)
DESIGN_OK = {"texts": 120, "skipped": 0, "refs": [], "type": "text/html"}


def page(path, status=200, role="admin", ui="new", console=(), design="clean"):
    r = {"kind": "page", "page": path, "status": status, "role": role, "ui": ui, "console": list(console)}
    if status < 400 and design is not None:
        r["design"] = dict(DESIGN_OK) if design == "clean" else design
    return r


def link(p, target, status, role="admin", ui="new", label="x", data=None):
    return {"kind": "link", "page": p, "target": target, "status": status, "role": role, "ui": ui, "label": label,
            "data": data or {}, "result": "OK" if 200 <= status < 400 else "BROKEN"}


def click(p, result, role="admin", ui="new", label="Approve", detail=None, data=None):
    return {"kind": "click", "page": p, "result": result, "role": role, "ui": ui, "label": label, "data": data or {},
            "detail": detail or {}}


U1_ENTRY = ("pageerror: Cannot read properties of undefined (reading 'replace')\nstack: TypeError: Cannot read properties of "
            "undefined (reading 'replace')\n    at xmlEscape (http://localhost:18080/jenkins/static/373ca7e8/jsbundles/header.js:37:14)\n"
            "    at Object.menuItem (http://localhost:18080/jenkins/static/373ca7e8/jsbundles/header.js:173:17)\n"
            "url: http://localhost:18080/jenkins/batch-control/requests/")
U1_OTHER_STACK = ("pageerror: Cannot read properties of undefined (reading 'replace')\nstack: TypeError: x\n"
                  "    at render (http://localhost:18080/jenkins/plugin/batch-control/ui/dialogOpener.js:12:3)\n"
                  "url: http://localhost:18080/jenkins/batch-control/requests/")
ACT_FAST = "/job/fast/batch-control-activation/"
# the two history pages of CI run 40158487092 (trace of crawl-reqonly-classic, buildHistory/ajax of job fast)
HIST_P1 = {"newest": "-9223372036854775744", "oldest": "-9223372036854775773", "has_up": "false", "has_down": "true",
           "count": 30, "first": 64, "last": 35}
HIST_P2 = {"newest": "-9223372036854775774", "oldest": "-9223372036854775803", "has_up": "true", "has_down": "true",
           "count": 30, "first": 34, "last": 5}
TOGGLE = "/job/ops/job/mb/batch-control-activation/"
WS_PAGE = "/job/ops/job/job-a/batch-control/"
D70_LINE = ("error: Refused to execute script from 'http://localhost:18080/jenkins/static/b1c10f94/' because its MIME type "
            "('text/html') is not executable, and strict MIME type checking is enabled.")


def _manage_edit(change=None, **parts):
    """manage_roles.py's #31 res (judge_role "manage_edit") as a green run logs it; `change` replaces top-level keys,
    `parts` updates the nested dialog dicts (pattern_dialog, delete_dialog, template_add_dialog, template_delete_dialog)."""
    new, old, perms, tperms = "prod(/.*)?", "prod/.*", "[hudson.model.Item.Read]", "[hudson.model.Item.Read, hudson.model.Item.Workspace]"
    items = ["prod", "prod » adm-job", "prod » x"]
    res = {"arrange addRole": 200, "server role before": f"{old} {perms}", "card before": [1, old],
           "edit dialog": "Edit role: e2e31-prod", "save enabled": True, "edit closed": True, "card after reload": [1, new],
           "server role after edit": f"{new} {perms}", "strategy after edit": h1.RS_STRATEGY, "server matching": items,
           "pattern dialog": {"opened": True, "title": f'Items matching "{new}"', "items": items, "closed": True},
           "delete dialog": {"opened": True, "title": 'Delete role "e2e31-prod"?', "closed on cancel": True,
                             "listed after cancel": 1, "server after cancel": f"{new} {perms}", "closed on ok": True},
           "listed after delete": 0, "server role after delete": "none", "strategy after delete": h1.RS_STRATEGY,
           "console": [], "bad": [],
           "template add dialog": {"opened": True, "title": "Add permission template", "closed": True},
           "template listed after reload": 1, "server template after add": tperms, "strategy after template add": h1.RS_STRATEGY,
           "template delete dialog": {"opened": True, "title": 'Delete template "e2e31-tpl"?', "closed": True},
           "template listed after delete": 0, "server template after delete": "none",
           "strategy after template delete": h1.RS_STRATEGY, "template console": [], "template bad": [],
           "requests": ["200 POST /role-strategy/strategy/addRole", "200 GET /role-strategy/strategy/getMatchingJobs?pattern=x"]}
    for k, v in parts.items():
        res[k.replace("_", " ")] = dict(res[k.replace("_", " ")], **v)
    res.update(change or {})
    return res


def _endpoints(change=None):
    """endpoints.py's res as the script holds it when it calls report(): RS-7 values are tuples, not JSON lists."""
    rs7 = {k: tuple(v) if isinstance(v, list) else v for k, v in h1.RS7.items()}
    rs7.update(change or {})
    return {"RS-6 admin forwarded == role-strategy": {"checkPattern 'team('": (200, True, "x")}, "RS-7 refusals": rs7,
            "strategy": h1.RS_STRATEGY}


def _act(role, item, control, page_state, server_state):
    return {"role": role, "item": item, "control": control, "ctype": "form", "state_before": "Pending", "post_status": [302],
            "state_after_page": page_state, "server_after": [200, server_state], "console": []}


def _widget(p, widget, tag, label, href, role="admin", result="gone-on-reload"):
    """A crawl click row of an entry of one of core's side-panel panes as r14/crawl.py records it."""
    return dict(click(p, result, role=role, label=label, data={"href": href}, detail={"found": 0, "occ": 0}),
                tag=tag, region="side", widget=widget, cls="jenkins-menu-dropdown-chevron")


def _paged(p, restored, at, first="2026-10-27 12:00:00 UTC", above=None, changed=True):
    """A misc.py P row: page 1's first row came back at index `at` below the rows whose Started times are `above`
    (default: `at` rows each one second newer than `first`)."""
    if above is None:
        above = [f"2026-10-27 12:00:{at - i:02d} UTC" for i in range(at)] if at else []
    return {"sec": "P", "page": p, "next_links": 1, "next": {"url": p + "&page=2", "first_row_changed": changed},
            "prev": {"url": p + "&page=1", "first_row_restored": restored, "first_row_at": at,
                     "time_col": "Started", "first_row_time": first, "above_times": above}}


PL4 = [{"text": f"{t}\n#2", "tag": "a", "href": h, "cls": "jenkins-dropdown__item"} for t, h in
       (("Last build", "lastBuild"), ("Last stable build", "lastStableBuild"), ("Last successful build", "lastSuccessfulBuild"),
        ("Last completed build", "lastCompletedBuild"))]


def _menu(p, hover_items, click_items, boxes_after=2, legacy=None):
    """A r15/menu.py row (requester) with its Permalinks click; legacy: the old driver's count difference instead."""
    c = {"dialog": 0, "boxes_before": 1, "boxes_after": boxes_after, "url": "/jenkins" + p,
         "hover": {"boxes": 2 if hover_items else 1, "items": hover_items, "settled": True}}
    if legacy is not None:
        c["submenu_items"] = legacy
    else:
        c.update(settled=True, submenu_items=len(click_items), submenu=click_items)
    return {"user": "requester", "page": p, "items": [{"text": "Permalinks", "tag": "button", "href": None}], "status": {},
            "duplicates": [], "empty_href_anchors": [], "clicks": {"Permalinks": c}, "console": [], "bad": []}


def _incident(change=None, role="admin"):
    """A row of r14/s_incident.py with the M4 fields (2026-10-31) as a correct run records it; `change` replaces fields."""
    c = f"comment by {role} e2e-12"
    r = {"role": role, "id": "20261031-120000-abc123", "state_before": "Open", "forms_before": ["acknowledge", "addComment", "rerun"],
         "stored_before": "OPEN", "comment_status": 200, "comment_visible": True,
         "history_after_comment": [["Open", "SYSTEM", ""], ["Open", role, c]], "state_after_comment": "Open", "ack_status": 200,
         "state_after_ack": "Acknowledged", "after_ack_forms": ["resolve", "addComment", "rerun"], "stored_after_ack": "ACKNOWLEDGED",
         "resolve_status": 200, "state_after_resolve": "Resolved", "forms_after_resolve": ["addComment", "rerun"],
         "history_after_resolve": [["Open", "SYSTEM", ""], ["Open", role, c], ["Acknowledged", role, "ack"],
                                   ["Resolved", role, "resolved e2e-12"]], "stored_after_resolve": "RESOLVED"}
    r.update(change or {})
    return r


def _rerun_refusal(change=None):
    r = {"case": "rerun validation", "id": "20261031-120100-def456", "status": 400, "errors": ["Check at least one approver."],
         "reruns_before": [], "state_after_refusal": "Open", "forms_after_refusal": ["acknowledge", "addComment", "rerun"],
         "reruns_after": []}
    r.update(change or {})
    return r


# #31 E2E-D4: helpcheck.py's "layout" of a global configuration page as main renders it
_LAY = {"sections": 1, "labels": 12, "pairs": 12, "overlap": [], "runin": []}


def _help_pages(drop=None):
    """#31 E2E-D4: helpcheck.py's rows of the two global configuration pages as main renders them, one page left out."""
    return [{"page": p, "helps": [], "console": [], "layout": dict(_LAY)}
            for p in ("/manage/batch-control-configuration/", "/manage/configure") if p != drop]


def _monitor_def04(listed=True, **visit):
    """#31 DEF-04: an s_monitor.py row as main renders it (Install and Mark as reviewed passing), with `visit` changed."""
    lay = {"before_install": True, "marks": [{"item": "team", "nested": False, "button": [817, 510, 962, 548],
                                              "line": [153, 521, 811, 538], "dy": 0, "gap": 6}],
           "controls": [{"text": "Install the Batch Control variant", "box": [970, 201, 1200, 237]},
                        {"text": "Dismiss", "box": [1208, 201, 1244, 237]}],
           "icons": 1, "button_icons": [], "icon_classes": ["app-adminmonitor-icon"]}
    return {"before": "BatchControlMatrixAuthorizationStrategy", "plain": "GlobalMatrixAuthorizationStrategy", "warned": True,
            "dialog": True, "after_cancel": "GlobalMatrixAuthorizationStrategy", "after_ok": "BatchControlMatrixAuthorizationStrategy",
            "install_gone": True, "restored": "BatchControlMatrixAuthorizationStrategy", "console": [],
            "r31": {"listed": listed}, "layout_global": dict(lay, **visit), "layout_bc": dict(lay, **visit, before_install=False)}


# #31 (r6/s5c.py): reject_color.py D rows as main renders them. Light theme colours: the destructive red (the probe),
# the text colour, the primary (accent) colour of a non-destructive dialog's OK button.
# Values as Chromium reported them on main (2026-11-02): computed colours in oklch, canvas-normalised "r,g,b,a"; core
# paints the button background on ::before.
RED, TEXT, ACCENT = "oklch(0.6 0.2671 30)", "oklch(0.05 0.075 256.91)", "oklch(0.55 0.2 260)"
WHITE, NONE, ITEM = "rgb(255, 255, 255)", "rgba(0, 0, 0, 0)", "oklch(0.97 0.005 256.91)"
NORM = {RED: "248,0,0,255", TEXT: "0,8,35,255", ACCENT: "0,92,230,255", WHITE: "255,255,255,255", NONE: "0,0,0,0",
        ITEM: "243,245,248,255"}


def _btn(label, color, before, classes):
    return {"label": label, "color": color, "bg": NONE, "before_bg": before, "classes": classes, "visible": True,
            "n": {"color": NORM[color], "bg": NORM[NONE], "before_bg": NORM[before]}}


def _destructive(where, control, **change):
    ok_text = {"Cancel Request": "Cancel request", "Revoke": "Revoke", "Revert to the plain strategy": "Revert to plain strategy"}[control]
    r = {"where": where, "control": control, "user": "requester" if control == "Cancel Request" else "admin", "page": "/batch-control/x/",
         "http": 200, "probe": RED, "around": TEXT, "count": 1,
         "button": {"label": control, "color": RED, "bg": NONE, "visible": True,
                    "classes": "jenkins-button jenkins-!-destructive-color confirmation-link"},
         "dialog": {"probe": RED, "probe_n": NORM[RED], "title": "Cancel this run request?", "text": "...",
                    "buttons": ["Cancel", ok_text],
                    "ok": _btn(ok_text, WHITE, RED, "jenkins-button jenkins-button--primary jenkins-!-destructive-color"),
                    "cancel": _btn("Cancel", TEXT, ITEM, "jenkins-button")},
         "closed_on_cancel": True, "url_after_cancel": "/batch-control/x/", "state_before": True, "state_after": True}
    for k, v in change.items():
        if isinstance(v, dict) and isinstance(r.get(k), dict):
            r[k] = dict(r[k], **v)
        else:
            r[k] = v
    return r


def _destructive_rows(drop=None, **changes):
    """All six D rows; `changes` maps a row's where (spaces as _) to its changes."""
    return [_destructive(w, c, **changes.get(w.replace(" ", "_"), {})) for w, c in h1.DESTRUCTIVE_CONTROLS if w != drop]


# #31 (r8/rebuild.py): gate.py L's row as main renders it
def _rebuild(**change):
    r = {"user": "nobc", "job": "r19-gate", "build": 1, "approved_build": 1, "flag": "true", "overflow_button": 1,
         "menu": [{"text": "Rebuild Last", "tag": "a", "href": "/jenkins/job/r19-gate/lastCompletedBuild/rebuild/parameterized"},
                  {"text": "Rename", "tag": "a", "href": "/jenkins/job/r19-gate/confirm-rename"},
                  {"text": "Permalinks", "tag": "button", "href": None}],
         "menu_console": [], "entry_status": 200, "entry_url": "/job/r19-gate/lastCompletedBuild/rebuild/parameterized", "form": 1,
         "submit_status": 400, "submit_url": "/job/r19-gate/lastCompletedBuild/rebuild/configSubmit",
         "submit_text": "Approval required. This build was not started. Job r19-gate only runs through an approved Batch Control run request.",
         "queue": [], "next_build_before": 2, "next_build": 2,
         "records": [{"type": "TRIGGER_BLOCKED", "user": "nobc", "detail": "Blocked a Rebuild of job 'r19-gate' build #1 by 'nobc' - "
                      "a re-run does not reuse an earlier approval; submit a new run request"}], "flag_restored": "true"}
    r.update(change)
    return r


# #31 (r9/s76.py): round3.py B rows. A designated approver sees decisions (WARNING), a requester their own pending
# requests (INFO), an auditor no badge; the wording is not judged, only the tooltip and its number.
def _badge(n, tip="", **kw):
    return dict({"text": str(n), "visible": True, "cls": "jenkins-badge jenkins-!-warning-color", "tooltip": tip,
                 "data_tooltip": None, "title": None}, **kw)


def _tab(tab, *badges, others=()):
    return {"tab": tab, "label": tab.title() + "".join(f" {b['text']}" for b in badges), "badges": list(badges),
            "others": list(others)}


def _badge_row(user, *tabs, status=200):
    return {"user": user, "status": status, "tabs": [_tab("overview")] + list(tabs)}


def _badge_rows(**change):
    """One row per h1.BADGE_ROLES role; `change` maps a role (- as _) to its replacement row (None drops it)."""
    rows = {u: _badge_row(u) for u in h1.BADGE_ROLES}
    rows["approver-1"] = _badge_row("approver-1", _tab("requests", _badge(3, "3 run requests are awaiting your decision")),
                                    _tab("activations"), _tab("grants", _badge(1, "1 grant request is awaiting your decision")))
    rows["requester"] = _badge_row("requester", _tab("requests", _badge(12, "12 of your run requests are pending")),
                                   _tab("grants"))
    for k, v in change.items():
        rows[k.replace("_", "-")] = v
    return [r for r in rows.values() if r is not None]


# #31 (r11/s_tabs.py #2): round3.py C rows of the tab bar at 1024 and 800 px as main renders them (2026-11-06: admin's
# eight tabs on one row at 1024 and two rows at 800, the page as wide as the viewport).
NARROW_TABS = ("overview", "requests", "activations", "grants", "changes", "dashboard", "incidents", "history")


def _narrow(user, width, **change):
    rights = [135, 301, 426, 521, 631, 754, 866, 966] if width == 1024 else [129, 295, 420, 515, 625, 748, 128, 228]
    tabs = [{"tab": t, "left": r - 100, "right": r, "top": 126 if width == 1024 or i < 6 else 168, "visible": True}
            for i, (t, r) in enumerate(zip(NARROW_TABS, rights))]
    r = {"user": user, "width": width, "status": 200, "nav": True, "scrollW": width, "clientW": width, "navLeft": 20,
         "navRight": width - 20, "navH": 38 if width == 1024 else 80, "navScrollW": width - 40, "navClientW": width - 40,
         "overflowX": "visible", "rows": 1 if width == 1024 else 2, "tabs": tabs}
    r.update(change)
    return r


def _narrow_rows(drop=None, **changes):
    """One row per user and width; `changes` maps "<user>_<width>" (dashes as _) to its changes."""
    return [_narrow(u, w, **changes.get(f"{u}_{w}".replace("-", "_"), {})) for u in h1.TAB_BAR_NARROW_USERS
            for w in h1.TAB_BAR_NARROW_WIDTHS if (u, w) != drop]


# #31 (r9/s89.py #4, r10/s89.py): round3.py F rows of the grants page at 1280 px as main renders them (2026-11-06): the
# headers on one line; only cells that wrap between words are listed (Scope between kind and name, two approvers, two
# actions, a date between date and time, a How of four words).
GRANT_HEADS = {"pending": ["ID", "Scope", "Actions", "Duration", "Reason", "Requester", "Approvers", "Created"],
               "active": ["ID", "User", "Scope", "Actions", "Names", "Granted", "Expires", "Remaining", ""],
               "ended": ["ID", "User", "Scope", "Actions", "Names", "Ended", "How", ""]}


def _wrap_cell(col, text, lines=2, broken=(), row=0):
    return {"row": row, "col": col, "text": text, "lines": lines, "broken": list(broken)}


def _wrap_table(lst, cells=None, rows=3, heads=None):
    heads = heads or [{"text": h, "lines": 1 if h else 0} for h in GRANT_HEADS[lst]]
    if cells is None:
        cells = {"pending": [_wrap_cell("Scope", "Pipeline batch-pipeline"), _wrap_cell("Approvers", "approver-1, approver-2"),
                             _wrap_cell("Created", "2026-11-06 00:46:19 KST"), _wrap_cell("Reason", "e2e-14 new grant dialog")],
                 "active": [_wrap_cell("Actions", "CREATE, CONFIGURE"), _wrap_cell("Expires", "2026-11-06 01:47:51 KST")],
                 "ended": [_wrap_cell("How", "Revoked (its item was deleted) by fonly"),
                           _wrap_cell("Scope", "Freestyle project ops/del-me")]}[lst]
    return {"list": lst, "rows": rows, "width": 1036, "heads": heads, "cells": cells, "read": rows * len(GRANT_HEADS[lst])}


def _wrap_row(user, **tables):
    """A grants page row; `tables` replaces a list's table (None drops it)."""
    out = []
    for lst in ("pending", "active", "ended"):
        t = tables.get(lst, _wrap_table(lst))
        if t is not None:
            out.append(t)
    return {"user": user, "status": 200, "viewport": 1280, "clientW": 1280, "tables": out}


def _wrap_rows(**by_user):
    return [by_user.get(u.replace("-", "_"), _wrap_row(u)) for u in ("admin", "approver-1", "requester", "fonly")]


# #31 (r7/e_dark.py #3, r11/s_dark_monitor.py #2): misc.py K rows of the dark palette as main renders them (2026-11-06):
# the theme's variables read by probes (raw computed value and canvas-normalised "r,g,b,a"), the light ones read before
# the switch.
def _col(raw, n):
    return {"raw": raw, "n": n}


DK_VARS = {"background": _col("oklch(0.175 0.013 265)", "14,16,22,255"), "text": _col("oklch(0.98 0.015 265)", "243,249,255,255"),
           "table": _col("oklch(0.214375 0.014875 265)", "22,25,32,255"), "body": _col("oklch(0.175 0.013 265)", "14,16,22,255"),
           "head": _col("oklch(0.98 0.015 265)", "243,249,255,255"), "button": _col("oklch(0.7 0.038 265 / 0.075)", "148,161,188,19"),
           "alert_info_bg": _col("color(srgb 0.323101 0.524981 1.03104 / 0.04)", "77,128,255,10"),
           "alert_info_text": _col("oklch(0.6513 0.1987 265)", "82,134,255,255"),
           "alert_warning_bg": _col("color(srgb 1.06081 0.61102 -0.0705559 / 0.04)", "255,153,0,10"),
           "alert_warning_text": _col("color(srgb 1.03958 0.683781 0.14674)", "255,174,37,255"),
           "alert_danger_bg": _col("color(srgb 1.03122 0.235419 0.163984 / 0.04)", "255,51,51,10"),
           "alert_danger_text": _col("oklch(0.6682 0.2393 29.84)", "255,60,42,255")}
LT_VARS = dict(DK_VARS, background=_col("rgb(254, 254, 254)", "254,254,254,255"), text=_col("oklch(0.05 0.075 256.91)", "1,0,16,255"),
               table=_col("oklch(0.969994 0.00567106 256.91)", "243,245,249,255"), body=_col("rgb(254, 254, 254)", "254,254,254,255"),
               head=_col("oklch(0.05 0.075 256.91)", "1,0,16,255"), button=_col("oklch(0.6 0.075 256.91 / 0.075)", "72,96,138,19"))
WHITE_C, DARK_TEXT_C, NONE_C = _col("rgb(255, 255, 255)", "255,255,255,255"), _col("rgb(26, 26, 26)", "26,26,26,255"), _col("rgba(0, 0, 0, 0)", "0,0,0,0")
SECONDARY_C = _col("color(srgb 0.670653 0.710117 0.791742)", "171,181,202,255")


def _dk_table(lst="pending", **change):
    v = DK_VARS
    t = {"list": lst, "bg": v["table"], "cells": [{"tag": "td", "cls": "batch-control-nowrap", "bg": v["body"], "color": v["text"]},
                                                  {"tag": "td", "cls": "", "bg": v["body"], "color": v["text"]}],
         "heads": [{"text": "ID", "color": v["head"]}, {"text": "Scope", "color": v["head"]}]}
    t.update(change)
    return t


def _dk_page(page, tables=(), alerts=(), tab="requests", **change):
    v = DK_VARS
    r = {"page": page, "phase": "dark", "status": 200, "theme": "dark", "vars": v, "tables": list(tables), "alerts": list(alerts),
         "tab": {"current": {"tab": tab, "color": v["text"], "before": v["button"]},
                 "other": {"tab": "overview", "color": SECONDARY_C, "before": NONE_C}} if tab else None}
    r.update(change)
    return r


def _dk_alert(variant="warning", **change):
    a = {"variant": variant, "cls": f"jenkins-alert jenkins-alert-{variant}", "text": "Batch Control: ...",
         "bg": DK_VARS[f"alert_{variant}_bg"], "color": DK_VARS[f"alert_{variant}_text"]}
    a.update(change)
    return a


def _dark_rows(**pages):
    """The light row and three dark pages (a list, a detail page with a warning, core's job page with two alerts);
    `pages` replaces a page's row by its key (list, detail, job; None drops it)."""
    rows = {"list": _dk_page("/batch-control/requests/", [_dk_table("pending"), _dk_table("ended")]),
            "detail": _dk_page("/batch-control/grants/2a126f3e-4d2b-4a56-a088-9b8b82705480/",
                               [_dk_table(None, cells=[{"tag": "th", "cls": "", "bg": NONE_C, "color": DK_VARS["text"]},
                                                       {"tag": "td", "cls": "", "bg": DK_VARS["body"], "color": DK_VARS["text"]}],
                                          heads=[])], [_dk_alert("warning")], tab="grants"),
            "job": _dk_page("/job/batch-daily/", [], [_dk_alert("info"), _dk_alert("warning")], tab=None)}
    rows.update(pages)
    light = {"page": "/user/admin/appearance/", "phase": "light", "theme": "none", "vars": LT_VARS, "tables": [], "alerts": [], "tab": None}
    return [light] + [r for r in rows.values() if r is not None]


INCIDENT_M4_BREAKS = [  # (what broke, the fields a run would record then)
    ("the Resolve form offered before the acknowledgement", {"forms_before": ["acknowledge", "resolve", "addComment", "rerun"]}),
    ("no Acknowledge form on an open incident", {"forms_before": ["addComment", "rerun"]}),
    ("the Status row not Open before anything is pressed", {"state_before": "Acknowledged"}),
    ("the store not OPEN before anything is pressed", {"stored_before": "ACKNOWLEDGED"}),
    ("Add Comment answered 403", {"comment_status": 403}),
    ("the comment changed the status", {"state_after_comment": "Acknowledged"}),
    ("the comment not in the History by the role", {"history_after_comment": [["Open", "SYSTEM", ""]]}),
    ("Acknowledge answered 500", {"ack_status": 500}),
    ("the Status row not Acknowledged after the acknowledgement", {"state_after_ack": "Open"}),
    ("Acknowledge still offered after the acknowledgement", {"after_ack_forms": ["acknowledge", "resolve", "addComment"]}),
    ("the store not ACKNOWLEDGED after the acknowledgement", {"stored_after_ack": "OPEN"}),
    ("Resolve answered 400", {"resolve_status": 400}),
    ("the store not RESOLVED after the resolution", {"stored_after_resolve": "ACKNOWLEDGED"}),
    ("the History lacks the Resolved row with its comment", {"history_after_resolve": [["Acknowledged", "admin", "ack"],
                                                                                         ["Resolved", "admin", ""]]}),
]


# ---- #31 legacy gaps: rows as a green run on the CI stack records them (2026-11-06)
CH, IN = "/batch-control/changes/", "/batch-control/incidents/"


def _set(row, path, value):
    """Set a nested key ("prev.month") of a fixture row; value None removes it."""
    *outer, last = path.split(".")
    d = row
    for k in outer:
        d = d[k]
    if value is None:
        d.pop(last, None)
    else:
        d[last] = value


def _month_row(page, month="2026-11", prev="2026-10", total=173, change=None):
    """misc.py H's month-nav row of one page; `change` sets nested keys ("prev.rows": 3)."""
    sentence = h1.MONTH_NAV_PAGES[page][0]
    changes = page == CH
    r = {"page": page, "status": 200, "rows": 50 if changes else 2, "month": month, "empty": None,
         "footer": f"Page 1 ({total} records in {month})" if changes else f"Page 1 (2 incidents in {month})",
         "prev_href": f"?month={prev}",
         "prev": {"month": prev, "rows": 0, "footer": None, "empty": sentence.format(m=prev), "url": f"{page}?month={prev}"},
         "empty_month": {"month": "2016-01", "rows": 0, "footer": None, "empty": sentence.format(m="2016-01"),
                         "url": f"{page}?month=2016-01", "asked": "2016-01", "status": 200}, "seconds": 0.5}
    if changes:
        r["paging"] = {"first_row": "CONFIG_CHANGE batch-control-configuration manager", "older_href": f"?month={month}&page=2",
                       "page2": {"month": month, "rows": min(50, total - 50), "footer": f"Page 2 ({total} records in {month})",
                                 "empty": None, "url": f"{page}?month={month}&page=2", "first_row": "CONFIGURE ops/cron-a admin",
                                 "newer": 1}}
    for k, v in (change or {}).items():
        _set(r, k, v)
    return r


def _month_rows(changes=None, incidents=None, drop=None, **kw):
    rows = {CH: _month_row(CH, change=changes, **kw), IN: _month_row(IN, change=incidents, **kw)}
    rows.pop(drop, None)
    return list(rows.values())


def synthetic():
    """(name, driver, rows, ctx, expected: "fail" | "pass", check that must (not) fail)"""
    req = "/batch-control/requests/"
    return [
        # the five findings of the brief
        ("new Batch Control link answering 404", "crawl", [link(req, "/batch-control/requests/nowhere/", 404)], crawl_ctx(), "fail", "link"),
        ("new console error on a Batch Control page", "crawl",
         [page("/batch-control/grants/", console=["error: Uncaught ReferenceError: bcFoo is not defined"])], crawl_ctx(), "fail", "page-console"),
        ("new page error (no stack) on a Batch Control page", "crawl",
         [page("/batch-control/grants/", console=["pageerror: Cannot read properties of null (reading 'x')"])], crawl_ctx(), "fail", "page-console"),
        ("click timeout on a Batch Control control", "crawl",
         [click(req + IDS["req_pending"] + "/", "click-failed", detail={"err": "Locator.click: Timeout 5000ms exceeded."})], crawl_ctx(), "fail", "click"),
        ("a control that does nothing", "crawl", [click(req, "NOTHING")], crawl_ctx(), "fail", "click"),
        ("a control gone on its fresh load", "crawl", [click(req, "gone-on-reload", label="Revoke")], crawl_ctx(), "fail", "click"),
        ("a build entry that left the history on reload is not a failure", "crawl",
         [click("/job/batch-cron/", "gone-on-reload", label="", data={"href": "/jenkins/job/batch-cron/1234/"})], crawl_ctx(), "pass", None),
        # core's build-history pager (CI run 40158487092: its in-place page swap was read as NOTHING)
        ("core's Older builds pages the history in place, Newer builds brings it back", "crawl",
         [dict(click(ACT_FAST, "paged", role="reqonly", ui="classic", label="Older builds",
                     detail={"pager": "down", "history_before": HIST_P1, "history_after": HIST_P2,
                             "newer": {"result": "paged", "history_after": HIST_P1}, "console": []}),
               tag="button", region="side")], crawl_ctx("reqonly", "classic"), "pass", None),
        ("core's Older builds on a job with exactly two pages (no Newer step recorded)", "crawl",
         [dict(click("/job/fast/", "paged", role="admin", ui="classic", label="Older builds",
                     detail={"pager": "down", "history_before": HIST_P1, "history_after": HIST_P2, "console": []}),
               tag="button", region="side")], crawl_ctx("admin", "classic"), "pass", None),
        ("not hidden: core's Older builds whose list does not page", "crawl",
         [dict(click(ACT_FAST, "NOTHING", role="reqonly", ui="classic", label="Older builds",
                     detail={"pager": "down", "history_before": HIST_P1, "history_after": HIST_P1, "console": []}),
               tag="button", region="side")], crawl_ctx("reqonly", "classic"), "fail", "click"),
        ("not hidden: Newer builds on the older page does nothing", "crawl",
         [dict(click(ACT_FAST, "paged", role="reqonly", ui="classic", label="Older builds",
                     detail={"pager": "down", "history_before": HIST_P1, "history_after": HIST_P2,
                             "newer": {"result": "NOTHING", "history_after": HIST_P2}, "console": []}),
               tag="button", region="side")], crawl_ctx("reqonly", "classic"), "fail", "pager"),
        ("not hidden: a paged row without core's pager markup", "crawl",
         [dict(click(ACT_FAST, "paged", role="reqonly", ui="classic", label="Older builds", detail={"console": []}),
               tag="button", region="side")], crawl_ctx("reqonly", "classic"), "fail", "pager"),
        ("not hidden: a plugin side-panel button that does nothing", "crawl",
         [dict(click(ACT_FAST, "NOTHING", role="reqonly", ui="classic", label="Request Change Permission", detail={"console": []}),
               tag="button", region="side")], crawl_ctx("reqonly", "classic"), "fail", "click"),
        ("role profile: 200 where the requester must get 404 outside team", "crawl",
         [page("/job/batch-daily/", 200, role="requester")], crawl_ctx("requester", profile="role"), "fail", "page-status"),
        ("role profile: the requester reads team's jobs", "crawl",
         [page("/job/team/job/sub/job/deep-job/", 200, role="requester")], crawl_ctx("requester", profile="role"), "pass", None),
        ("200 where nobc must get 404", "crawl", [page(req, 200, role="nobc")], crawl_ctx("nobc"), "fail", "page-status"),
        ("200 where the requester must get 403 (History)", "crawl", [page("/batch-control/history/", 200, role="requester")],
         crawl_ctx("requester"), "fail", "page-status"),
        ("404 where the requester must get 200", "crawl", [page(req, 404, role="requester")], crawl_ctx("requester"), "fail", "page-status"),
        ("200 where approver-1 must get 404 (job action)", "crawl", [page("/job/batch-daily/batch-control/", 200, role="approver-1")],
         crawl_ctx("approver-1"), "fail", "page-status"),
        ("a dialog without Cancel and close button", "crawl",
         [click(req, "dialog", label="Request again", detail={"escape_closes": True, "cancel_button": 0, "close_button": 0, "url_unchanged": True})],
         crawl_ctx(), "fail", "dialog"),
        ("a Batch Control dialog with only the close button", "crawl",
         [click(req, "dialog", label="Request Run", detail={"escape_closes": True, "cancel_button": 0, "close_button": 1,
                                                           "cancel_closes": True, "url_unchanged": True})], crawl_ctx(), "fail", "dialog"),
        ("a dialog Escape does not close", "crawl",
         [click(req, "dialog", label="Revoke", detail={"escape_closes": False, "url_unchanged": True})], crawl_ctx(), "fail", "dialog"),
        ("a dead link", "crawl", [{"kind": "link", "page": req, "result": "DEAD-HREF", "role": "admin", "ui": "new", "label": "More"}],
         crawl_ctx(), "fail", "dead-href"),
        ("a help '?' whose help URL answers 404", "crawl",
         [{"kind": "link", "page": req, "result": "help-button", "status": 404, "target": "/jenkins/plugin/batch-control/help/x.html",
           "role": "admin", "ui": "new", "label": "?"}], crawl_ctx(), "fail", "help-button"),
        ("jobui: a request dialog without Cancel", "jobui",
         [{"kind": "entry", "role": "requester", "ui": "new", "page": "/job/batch-daily/", "where": "main", "label": "Request Run",
           "result": "dialog", "escape_closes": True, "has_cancel": False, "close_x_closes": True, "url_unchanged": True,
           "request_form": True, "empty_submit_stays": True, "empty_submit_errors": ["x"],
           "submit_landing": "/batch-control/requests/" + IDS["req_pending"] + "/", "server_detail_status": 200, "console": []}],
         {"ui": "new", "roles": ["requester"], "prefix": PREFIX}, "fail", "dialog"),
        ("jobui: an entry that does nothing", "jobui",
         [{"kind": "entry", "role": "requester", "ui": "new", "page": "/job/batch-daily/", "where": "main", "label": "Request Run",
           "result": "NOTHING", "console": []}], {"ui": "new", "roles": ["requester"], "prefix": PREFIX}, "fail", "nothing"),
        ("jobui: an unknown notification", "jobui",
         [{"kind": "entry", "role": "requester", "ui": "new", "page": "/job/batch-cron/", "where": "main", "label": "Build Now",
           "result": "notified", "notifications": ["Failed to schedule build. Reload the page and try again."], "console": []}],
         {"ui": "new", "roles": ["requester"], "prefix": PREFIX}, "fail", "notified"),
        ("jobui: three 400s in a request dialog (only two are provoked)", "jobui",
         [{"kind": "entry", "role": "requester", "ui": "new", "page": "/job/batch-daily/", "where": "main", "label": "Request Run",
           "result": "dialog", "escape_closes": True, "has_cancel": True, "cancel_closes": True, "close_x_closes": True,
           "url_unchanged": True, "request_form": True, "empty_submit_stays": True, "empty_submit_errors": ["x"],
           "enter_in_text": {"dialog_open": 1}, "submit_landing": "/batch-control/requests/" + IDS["req_pending"] + "/",
           "server_detail_status": 200,
           "console": ["error: Failed to load resource: the server responded with a status of 400 (Bad Request)"] * 3}],
         {"ui": "new", "roles": ["requester"], "prefix": PREFIX}, "fail", "console"),
        ("actions: a control answering 403", "actions",
         [{"role": "approver-1", "item": "run", "control": "Approve", "ctype": "form", "state_before": "Pending", "post_status": [403],
           "state_after_page": "Pending", "server_after": [200, "Pending"], "console": []}], {}, "fail", "control-status"),
        # states that move on by themselves (CI run 40175641627: the approved run request already read Executed)
        ("actions: an approved run request already Executed (page and server)", "actions",
         [_act("admin", "run_reqonly", "Approve", "Executed", "Executed")], {}, "pass", None),
        ("actions: an approved run request still waiting to run", "actions",
         [_act("approver-1", "run", "Approve", "Approved, waiting to run", "Executed")], {}, "pass", None),
        ("not hidden: Rejected after Approve on a run request", "actions",
         [_act("admin", "run_reqonly", "Approve", "Rejected", "Executed")], {}, "fail", "state-page"),
        ("not hidden: Pending after Approve on a run request (server)", "actions",
         [_act("admin", "run", "Approve", "Approved, waiting to run", "Pending")], {}, "fail", "state-server"),
        ("not hidden: an approved activation never reads Executed", "actions",
         [_act("admin", "act", "Approve Activation", "Executed", "Approved")], {}, "fail", "state-page"),
        ("not hidden: Approve on a grant that leaves it Approved, not an open window", "actions",
         [_act("admin", "grant", "Approve", "Approved", "Approved")], {}, "fail", "state-page"),
        # core's transient side-panel widgets (CI run 40175641627: the Build Queue's batch-cron entry, gone on reload)
        ("a Build Queue entry's chevron gone on the fresh load (the build left the queue)", "crawl",
         [_widget(TOGGLE, "buildQueue", "button", "", "http://localhost:18080/jenkins/job/batch-cron/", role="manager")],
         crawl_ctx("manager"), "pass", None),
        ("an executor entry gone on the fresh load (the build finished)", "crawl",
         [_widget(TOGGLE, "executors", "button", "", "http://localhost:18080/jenkins/job/batch-cron/1234/")], crawl_ctx(), "pass", None),
        ("not hidden: a plugin side-panel control gone on the fresh load", "crawl",
         [dict(click(TOGGLE, "gone-on-reload", label="Request Change Permission",
                     data={"dialogUrl": "/jenkins/batch-control/grants/dialog?scopeFullName=ops%2Fmb"}), tag="a", region="side")],
         crawl_ctx(), "fail", "click"),
        ("not hidden: a Batch Control target inside the queue pane", "crawl",
         [_widget(TOGGLE, "buildQueue", "button", "", "http://localhost:18080/jenkins/batch-control/requests/")], crawl_ctx(), "fail", "click"),
        ("not hidden: a pane id outside core's two transient panes", "crawl",
         [_widget(TOGGLE, "batch-control-pane", "button", "", "http://localhost:18080/jenkins/job/batch-cron/")], crawl_ctx(), "fail", "click"),
        ("not hidden: a queue entry that does nothing when clicked", "crawl",
         [dict(_widget(TOGGLE, "buildQueue", "button", "", "http://localhost:18080/jenkins/job/batch-cron/"), result="NOTHING")],
         crawl_ctx(), "fail", "click"),
        # r15 menu: core's Permalinks submenu (CI run 40183795439: the hover showed it, the click toggled the menu closed)
        ("r15: Permalinks shown on hover, the click toggled the menu closed", "r15/menu",
         [_menu("/job/batch-daily/batch-control-activation/", PL4, [], boxes_after=0)], {}, "pass", None),
        ("r15: the failing row of CI run 40183795439 as the old driver recorded it", "r15/menu",
         [_menu("/job/batch-daily/batch-control-activation/", PL4, None, boxes_after=0, legacy=-4)], {}, "pass", None),
        ("r15: Permalinks shown by the click only", "r15/menu",
         [_menu("/job/batch-daily/", [], PL4)], {}, "pass", None),
        ("r15: job page and activation page list the same permalinks (build numbers moved on)", "r15/menu",
         [_menu("/job/batch-daily/", PL4, PL4),
          _menu("/job/batch-daily/batch-control-activation/", [dict(i, text=i["text"].replace("#2", "#3")) for i in PL4], [])],
         {}, "pass", None),
        ("not hidden: a Permalinks submenu neither the hover nor the click showed", "r15/menu",
         [_menu("/job/batch-daily/batch-control-activation/", [], [], boxes_after=1)], {}, "fail", "menu"),
        ("not hidden: the activation page's Permalinks submenu lost an entry", "r15/menu",
         [_menu("/job/batch-daily/", PL4, PL4), _menu("/job/batch-daily/batch-control-activation/", PL4[:3], PL4[:3])],
         {}, "fail", "menu"),
        # misc P: the newest-first run history gains timer runs while its pager is walked
        ("misc: Previous on the run history brings page 1 back with two new runs on top", "misc",
         [_paged("/batch-control/history/?kind=runs", False, 2)], {}, "pass", None),
        ("misc: Previous on the run history brings page 1 back moved down 6 rows, all newer (run 40326682006)", "misc",
         [_paged("/batch-control/history/?kind=runs", False, 6)], {}, "pass", None),
        ("misc: Previous on the run history restores page 1 exactly", "misc",
         [_paged("/batch-control/history/?kind=runs", True, 0, above=[])], {}, "pass", None),
        ("not hidden: the run history's first row moved down 6 rows with an older row above it", "misc",
         [_paged("/batch-control/history/?kind=runs", False, 6, above=["2026-10-27 12:00:06 UTC", "2026-10-27 12:00:05 UTC", "2026-10-27 11:59:59 UTC",
                                       "2026-10-27 12:00:03 UTC", "2026-10-27 12:00:02 UTC", "2026-10-27 12:00:01 UTC"])],
         {}, "fail", "P-paging"),
        ("misc: the run history's first row moved down below a row of the same second (equal is sorted correctly)", "misc",
         [_paged("/batch-control/history/?kind=runs", False, 1, above=["2026-10-27 12:00:00 UTC"])], {}, "pass", None),
        ("not hidden: the run history's first row moved down below a row of the same second and an older row", "misc",
         [_paged("/batch-control/history/?kind=runs", False, 2, above=["2026-10-27 12:00:00 UTC", "2026-10-27 11:59:59 UTC"])], {}, "fail", "P-paging"),
        ("not hidden: the run history's first row moved down below a same-second row in another zone", "misc",
         [_paged("/batch-control/history/?kind=runs", False, 1, above=["2026-10-27 12:00:00 KST"])], {}, "fail", "P-paging"),
        ("not hidden: the run history's first row moved down below a row whose time does not parse", "misc",
         [_paged("/batch-control/history/?kind=runs", False, 1, above=["yesterday"])], {}, "fail", "P-paging"),
        ("not hidden: the run history's first row moved down with no times recorded above it", "misc",
         [_paged("/batch-control/history/?kind=runs", False, 2, above=[])], {}, "fail", "P-paging"),
        ("not hidden: run 40326682006's row as the old driver recorded it (moved 6, no times to prove them newer)", "misc",
         [{"sec": "P", "page": "/batch-control/history/?kind=runs", "next_links": 1,
           "next": {"url": "/batch-control/history/?kind=runs&page=2", "first_row_changed": True},
           "prev": {"url": "/batch-control/history/?kind=runs&page=1", "first_row_restored": False, "first_row_at": 6}}],
         {}, "fail", "P-paging"),
        ("not hidden: Previous on the run history lands on a page without page 1's first row", "misc",
         [_paged("/batch-control/history/?kind=runs", False, None)], {}, "fail", "P-paging"),
        ("not hidden: Next on the run history did not change the first row (moved-down Previous otherwise fine)", "misc",
         [_paged("/batch-control/history/?kind=runs", False, 6, changed=False)], {}, "fail", "P-paging"),
        ("not hidden: a moved first row on a list no timer writes to (changes), newer rows above", "misc",
         [_paged("/batch-control/changes/", False, 6)], {}, "fail", "P-paging"),
        ("misc: a list no timer writes to (changes) restored exactly", "misc",
         [_paged("/batch-control/changes/", True, 0, above=[])], {}, "pass", None),
        # incident M4 (2026-10-31): the state machine through the forms; the refused rerun stays on the incident
        ("incident: Open -> comment -> Acknowledged -> Resolved through the forms", "incident", [_incident()], {}, "pass", None),
        ("incident: the same for approver-1", "incident", [_incident(role="approver-1")], {}, "pass", None),
        ("incident: a row recorded before M4 (no M4 fields) is judged as before", "incident",
         [{k: v for k, v in _incident().items() if k in ("role", "comment_visible", "after_ack_forms", "state_after_resolve",
                                                          "forms_after_resolve")}], {}, "pass", None),
    ] + [(f"not hidden: incident M4, {what}", "incident", [_incident(change)], {}, "fail", "incident-m4")
         for what, change in INCIDENT_M4_BREAKS] + [
        ("incident: the refused rerun stays on the incident page, nothing linked", "incident", [_rerun_refusal()], {}, "pass", None),
        ("not hidden: the refused rerun linked a rerun request", "incident", [_rerun_refusal({"reruns_after": ["x"]})], {}, "fail",
         "rerun-refusal-state"),
        ("not hidden: the refused rerun's page lost the rerun form", "incident",
         [_rerun_refusal({"forms_after_refusal": ["acknowledge", "addComment"]})], {}, "fail", "rerun-refusal-state"),
        ("not hidden: the refused rerun's page is not the incident (no Status row)", "incident",
         [_rerun_refusal({"state_after_refusal": None})], {}, "fail", "rerun-refusal-state"),
        ("actions: nobc may open a run request (200 where 404)", "actions",
         [{"role": "nobc", "item": "run", "probe_status": 200, "controls": []}], {}, "fail", "probe"),
        ("misc: nobc reads the History (200 where 404)", "misc", [{"sec": "H", "role": "nobc", "status": 200}], {}, "fail", "H-status"),
        ("monitor: Install did not install the Batch Control variant", "monitor",
         [{"before": "BatchControlMatrixAuthorizationStrategy", "plain": "GlobalMatrixAuthorizationStrategy", "warned": True, "dialog": True,
           "after_cancel": "GlobalMatrixAuthorizationStrategy", "after_ok": "GlobalMatrixAuthorizationStrategy", "install_gone": False,
           "restored": "BatchControlMatrixAuthorizationStrategy", "console": []}], {}, "fail", "install"),
        ("monitor: the global matrix premise, Cancel then OK", "monitor",
         [{"before": "BatchControlMatrixAuthorizationStrategy", "plain": "GlobalMatrixAuthorizationStrategy", "warned": True, "dialog": True,
           "after_cancel": "GlobalMatrixAuthorizationStrategy", "after_ok": "BatchControlMatrixAuthorizationStrategy", "install_gone": True,
           "restored": "BatchControlMatrixAuthorizationStrategy", "mark_links": 1, "after_mark_cancel": 1, "after_mark_ok": 0,
           "mark_links_on_page": [["team", 200]], "console": [],
           **{k: v for k, v in _monitor_def04().items() if k in ("r31", "layout_global", "layout_bc")}}], {}, "pass", None),
        ("misc: the move refusal page answers 403 with its links", "misc",
         [{"sec": "R", "case": "move refusal", "role": "mover1", "window": IDS["req_pending"], "move_page": 200,
           "destinations": ["/ops", "/team"], "status": 403, "links": [["z", "/job/prod/job/z/", 200]], "still_in_prod": 200}], {}, "pass", None),
        ("misc: the move refusal never reached (no destination)", "misc",
         [{"sec": "R", "case": "move refusal", "role": "mover1", "move_page": 200, "destinations": [], "still_in_prod": 200}], {}, "fail", "R-move"),
        ("help: #31 E2E-D4 description on the checkbox caption's line", "help",
         [{"page": "/manage/batch-control-configuration/", "helps": [], "console": [],
           "layout": dict(_LAY, runin=[{"label": "Enable run control", "labelBox": [10, 100, 130, 118], "descBox": [130, 100, 600, 118]}])}], {}, "fail", "label-description-same-line"),
        ("help: #31 E2E-D4 label and description boxes overlap", "help",
         [{"page": "/manage/configure", "helps": [], "console": [],
           "layout": dict(_LAY, overlap=[{"label": "Enable run control", "labelBox": [10, 100, 130, 118], "descBox": [100, 110, 600, 130]}])}],
         {}, "fail", "label-description-overlap"),
        ("help: #31 E2E-D4 no Batch Control section measured", "help",
         [{"page": "/manage/configure", "helps": [], "console": [], "layout": dict(_LAY, sections=0, labels=0, pairs=0)}], {}, "fail",
         "label-layout-premise"),
        ("help: #31 E2E-D4 every label above its text", "help", _help_pages(), {}, "pass", None),
        ("help: #31 E2E-D4 measurement missing on Manage Jenkins > System", "help", _help_pages(drop="/manage/configure"), {},
         "fail", "label-layout-premise"),
        ("help: #31 E2E-D4 measurement missing on the Batch Control page", "help",
         _help_pages(drop="/manage/batch-control-configuration/"), {}, "fail", "label-layout-premise"),
        ("help: #31 E2E-D4 no layout on any row (the driver stopped measuring)", "help",
         [{"page": p, "helps": [], "console": []} for p in ("/manage/batch-control-configuration/", "/manage/configure")], {},
         "fail", "label-layout-premise"),
        ("monitor: #31 DEF-04 measurement missing (no layout_global / layout_bc)", "monitor",
         [{k: v for k, v in _monitor_def04().items() if k not in ("layout_global", "layout_bc")}], {}, "fail", "def04-monitor-shown"),
        ("monitor: #31 DEF-04 measurement missing on the Batch Control strategy visit", "monitor",
         [{k: v for k, v in _monitor_def04().items() if k != "layout_bc"}], {}, "fail", "def04-monitor-shown"),
        ("monitor: #31 DEF-04 no monitor row at all", "monitor", [], {}, "fail", "def04-monitor-shown"),
        ("monitor: #31 DEF-04 Mark as reviewed floated to the right edge on its own line", "monitor",
         [_monitor_def04(marks=[{"item": "r31-monitor-layout", "nested": False, "button": [1090, 520, 1235, 558],
                                 "line": [271, 483, 1027, 500], "dy": 47.5, "gap": 63}])], {}, "fail", "def04-mark-beside-item"),
        ("monitor: #31 DEF-04 Before you install shows the icon twice", "monitor",
         [_monitor_def04(icons=2, icon_classes=["app-adminmonitor-icon", "icon-sm"])], {}, "fail", "def04-alert-icons"),
        ("monitor: #31 DEF-04 the arranged item never listed", "monitor", [_monitor_def04(listed=False)], {}, "fail", "def04-premise"),
        ("monitor: #31 DEF-04 buttons beside their items, one icon", "monitor", [_monitor_def04()], {}, "pass", None),
        ("help: a help block that does not open", "help2", [{"page": "/x", "help": "y", "before": 3, "after": 3}], {}, "fail", "help-click"),
        ("role: Manage Roles save loses the window (200 -> 403)", "role/grant_overlay",
         [{"0 before: requester configure": 403, "1 requested (pending): requester configure": 403,
           "2 approved (window open): requester configure": 200, "3 after a Manage Roles save during the window: requester configure": 403,
           "4 revoked: requester configure": 403, "browser configure after revoke": 403}], {}, "fail", "overlay"),
        ("role: #31 edit, pattern check, delete and templates as a green run logs them", "role/manage_edit", [_manage_edit()],
         {}, "pass", None),
        ("role: #31 Save stays disabled for a valid pattern (the forwarded checkPattern refuses it)", "role/manage_edit",
         [_manage_edit({"save enabled": False, "edit closed": None, "card after reload": [1, "prod/.*"],
                        "server role after edit": "prod/.* [hudson.model.Item.Read]"})], {}, "fail", "role-edit"),
        ("role: #31 the edit saved the pattern but dropped the permissions", "role/manage_edit",
         [_manage_edit({"server role after edit": "prod(/.*)? []"})], {}, "fail", "role-edit"),
        ("role: #31 the edit installed the plain role-strategy class", "role/manage_edit",
         [_manage_edit({"strategy after edit": "RoleBasedAuthorizationStrategy"})], {}, "fail", "role-edit"),
        ("role: #31 the pattern check lists fewer items than the server matches", "role/manage_edit",
         [_manage_edit(pattern_dialog={"items": ["prod", "prod » adm-job"]})], {}, "fail", "role-pattern-check"),
        ("role: #31 more matches than role-strategy asks for: the dialog lists 15 of them", "role/manage_edit",
         [_manage_edit({"server matching": [f"prod » j{i:02}" for i in range(20)]},
                       pattern_dialog={"items": [f"prod » j{i:02}" for i in range(15)]})], {}, "pass", None),
        ("role: #31 more matches than role-strategy asks for, but the dialog lists 3", "role/manage_edit",
         [_manage_edit({"server matching": [f"prod » j{i:02}" for i in range(20)]},
                       pattern_dialog={"items": [f"prod » j{i:02}" for i in range(3)]})], {}, "fail", "role-pattern-check"),
        ("role: #31 the pattern check never opens", "role/manage_edit",
         [_manage_edit(pattern_dialog={"opened": False, "items": None, "title": None})], {}, "fail", "role-pattern-check"),
        ("role: #31 the pattern check titled with another pattern", "role/manage_edit",
         [_manage_edit(pattern_dialog={"title": 'Items matching "prod/.*"'})], {}, "fail", "role-pattern-check"),
        ("role: #31 the server matches nothing (an empty list proves nothing)", "role/manage_edit",
         [_manage_edit({"server matching": []}, pattern_dialog={"items": []})], {}, "fail", "role-pattern-check"),
        ("role: #31 Delete role removes without asking", "role/manage_edit",
         [_manage_edit(delete_dialog={"opened": False, "title": None, "closed on cancel": None, "listed after cancel": 0,
                                      "server after cancel": "none"})], {}, "fail", "role-delete"),
        ("role: #31 Cancel on Delete role removes the role", "role/manage_edit",
         [_manage_edit(delete_dialog={"listed after cancel": 0, "server after cancel": "none"})], {}, "fail", "role-delete"),
        ("role: #31 OK on Delete role keeps the role", "role/manage_edit",
         [_manage_edit({"listed after delete": 1, "server role after delete": "prod(/.*)? [hudson.model.Item.Read]"})], {},
         "fail", "role-delete"),
        ("role: #31 Add Template stores other permissions", "role/manage_edit",
         [_manage_edit({"server template after add": "[hudson.model.Item.Read]"})], {}, "fail", "template-add"),
        ("role: #31 Add Template's dialog never opens", "role/manage_edit",
         [_manage_edit({"template listed after reload": 0, "server template after add": "none"},
                       template_add_dialog={"opened": False, "closed": None})], {}, "fail", "template-add"),
        ("role: #31 the template delete keeps the template", "role/manage_edit",
         [_manage_edit({"template listed after delete": 1, "server template after delete": "[hudson.model.Item.Read]"})], {},
         "fail", "template-delete"),
        ("role: #31 the template delete swapped the strategy class", "role/manage_edit",
         [_manage_edit({"strategy after template delete": "RoleBasedAuthorizationStrategy"})], {}, "fail", "template-delete"),
        ("role: #31 the arrangement failed (addRole 403)", "role/manage_edit",
         [_manage_edit({"arrange addRole": 403})], {}, "fail", "role-edit-measured"),
        ("role: #31 the driver raised after the edit", "role/manage_edit",
         [_manage_edit({"error": "TimeoutError('Locator.click')"})], {}, "fail", "role-edit-measured"),
        ("role: #31 no manage_edit row at all", "role/manage_edit", [], {}, "fail", "role-edit-measured"),
        ("role: #31 removeTemplates answered 500", "role/manage_edit",
         [_manage_edit({"requests": ["500 POST /role-strategy/strategy/removeTemplates"]})], {}, "fail", "role-edit-requests"),
        ("role: RS-7 as the script builds it (tuples) equals the table", "role/endpoints", [_endpoints()], {}, "pass", None),
        ("role: RS-7 with one refusal turned into 200", "role/endpoints", [_endpoints({"checkSidName POST nobc": (200, "")})],
         {}, "fail", "RS-7"),
        ("r15: a menu entry answering 404", "r15/menu",
         [{"user": "admin", "page": "/job/batch-daily/", "items": [{"text": "Move"}], "status": {"Move": 404}, "duplicates": [],
           "empty_href_anchors": [], "clicks": {}, "console": [], "bad": []}], {}, "fail", "menu"),
        # the allowlist explains exactly its own finding ...
        ("allowed: core's collapse link 404 (C-03)", "crawl", [link(TOGGLE, "/toggleCollapse?paneId=executors", 404, role="requester")],
         crawl_ctx("requester"), "pass", None),
        ("allowed: Workspace 404 for admin, classic (C-04)", "crawl", [link(WS_PAGE, "/job/ops/job/job-a/ws/", 404, ui="classic", label="Workspace")],
         crawl_ctx(ui="classic"), "pass", None),
        # #31 (r6/s5c.py): Cancel Request and Revoke are red and open red confirmation dialogs (reject_color.py D)
        ("destructive: #31 every control red, every dialog's OK red, Cancel changes nothing", "destructive", _destructive_rows(),
         {}, "pass", None),
        ("destructive: #31 Cancel Request without the destructive class (text colour)", "destructive",
         _destructive_rows(run_request={"button": {"color": TEXT, "classes": "jenkins-button confirmation-link"}}), {}, "fail",
         "destructive-button"),
        ("destructive: #31 the window page shows no Revoke", "destructive", _destructive_rows(window={"button": None, "count": 0,
                                                                                                    "dialog": None}),
         {}, "fail", "destructive-button"),
        ("destructive: #31 Revoke's dialog not destructive (OK in the accent colour)", "destructive",
         _destructive_rows(grants_list={"dialog": {"ok": _btn("Revoke", WHITE, ACCENT, "jenkins-button jenkins-button--primary")}}),
         {}, "fail", "destructive-dialog"),
        ("destructive: #31 the OK button's red only matches after normalisation (rgb vs oklch)", "destructive",
         _destructive_rows(window={"dialog": {"ok": dict(_btn("Revoke", WHITE, RED, "jenkins-button jenkins-button--primary"),
                                                         before_bg="rgb(248, 0, 0)")}}), {}, "pass", None),
        ("destructive: #31 the click opens no dialog", "destructive", _destructive_rows(activation_request={"dialog": None}), {},
         "fail", "destructive-dialog"),
        ("destructive: #31 the dialog's Cancel is red too", "destructive",
         _destructive_rows(permission_window_request={"dialog": {"cancel": _btn("Cancel", RED, ITEM, "jenkins-button")}}), {},
         "fail", "destructive-dialog"),
        ("destructive: #31 Cancel in the dialog cancelled the request", "destructive",
         _destructive_rows(run_request={"state_after": False}), {}, "fail", "destructive-dialog"),
        ("destructive: #31 the window never conferred (no premise for 'nothing changed')", "destructive",
         _destructive_rows(window={"state_before": False, "state_after": False}), {}, "fail", "destructive-dialog"),
        ("destructive: #31 the probe does not resolve (same colour as the text)", "destructive",
         _destructive_rows(run_request={"probe": TEXT, "button": {"color": TEXT}}), {}, "fail", "destructive-measured"),
        ("destructive: #31 the grants list's Revoke not measured", "destructive", _destructive_rows(drop="grants list"), {}, "fail",
         "destructive-measured"),
        ("destructive: #31 no row at all (the section did not run)", "destructive", [], {}, "fail", "destructive-measured"),
        # #31 (r6/s5a.py #5): the configuration page's "Revert to the plain strategy", revealed behind Advanced
        ("destructive: #31 the revert's dialog not destructive (OK in the accent colour)", "destructive",
         _destructive_rows(configuration_page={"dialog": {"ok": _btn("Revert to plain strategy", WHITE, ACCENT,
                                                                     "jenkins-button jenkins-button--primary")}}),
         {}, "fail", "destructive-dialog"),
        ("destructive: #31 the revert entry still hidden behind Advanced (not shown, no click)", "destructive",
         _destructive_rows(configuration_page={"button": {"visible": False}, "dialog": None}), {}, "fail", "destructive-button"),
        ("destructive: #31 Cancel in the revert dialog reverted the strategy", "destructive",
         _destructive_rows(configuration_page={"state_after": False}), {}, "fail", "destructive-dialog"),
        ("destructive: #31 the revert entry not measured", "destructive", _destructive_rows(drop="configuration page"), {},
         "fail", "destructive-measured"),
        # #31 (r8/rebuild.py): Rebuild Last from the new job page's More actions (gate.py L)
        ("rebuild-menu: #31 offered with its URL, refused, nothing queued, recorded", "rebuild-menu", [_rebuild()], {}, "pass", None),
        ("rebuild-menu: #31 DEF-06: the menu stays empty", "rebuild-menu",
         [_rebuild(menu=[], menu_console=["pageerror: Cannot read properties of null (reading 'startsWith')"], entry_status=None,
                   entry_url=None, form=None, submit_status=None, submit_text=None, records=[])], {}, "fail", "rebuild-menu-offered"),
        ("rebuild-menu: #31 DEF-06: Rebuild Last with a null URL", "rebuild-menu",
         [_rebuild(menu=[{"text": "Rebuild Last", "tag": "a", "href": None}])], {}, "fail", "rebuild-menu-offered"),
        ("rebuild-menu: #31 the entry does not open the rebuild form", "rebuild-menu",
         [_rebuild(entry_status=404, form=0)], {}, "fail", "rebuild-menu-offered"),
        ("rebuild-menu: #31 no row (the section did not run)", "rebuild-menu", [], {}, "fail", "rebuild-menu-offered"),
        ("rebuild-menu: #31 the rebuild was accepted and queued", "rebuild-menu",
         [_rebuild(submit_status=200, submit_text="r19-gate #2", queue=[{"id": 7}], next_build=3, records=[])], {}, "fail",
         "rebuild-menu-refused"),
        ("rebuild-menu: #31 refused but not recorded", "rebuild-menu", [_rebuild(records=[])], {}, "fail", "rebuild-menu-refused"),
        ("rebuild-menu: #31 recorded for another build", "rebuild-menu",
         [_rebuild(records=[{"type": "TRIGGER_BLOCKED", "user": "nobc", "detail": "Blocked a Rebuild of job 'r19-gate' build #7 by 'nobc'"}])],
         {}, "fail", "rebuild-menu-refused"),
        # #31 (r9/s76.py): the tab badges carry a tooltip with their number (round3.py B)
        ("badges: #31 every badge has a tooltip with its number, no badge no tooltip", "badges", _badge_rows(), {}, "pass", None),
        ("badges: #31 the tooltip only as title (core's tooltip script)", "badges",
         _badge_rows(approver_2=_badge_row("approver-2", _tab("requests", _badge(2, None, title="2 waiting for you")))), {},
         "pass", None),
        ("badges: #31 a badge without a tooltip", "badges",
         _badge_rows(approver_2=_badge_row("approver-2", _tab("requests", _badge(2, None)))), {}, "fail", "badge-tooltip"),
        ("badges: #31 an empty tooltip", "badges",
         _badge_rows(requester=_badge_row("requester", _tab("requests", _badge(4, "  ")))), {}, "fail", "badge-tooltip"),
        ("badges: #31 a tooltip with another number", "badges",
         _badge_rows(approver_1=_badge_row("approver-1", _tab("requests", _badge(3, "4 run requests are awaiting your decision")))),
         {}, "fail", "badge-tooltip"),
        ("badges: #31 '3' inside '13' is not the badge's number", "badges",
         _badge_rows(approver_1=_badge_row("approver-1", _tab("requests", _badge(3, "13 run requests are awaiting your decision")))),
         {}, "fail", "badge-tooltip"),
        ("badges: #31 a badge element without a number", "badges",
         _badge_rows(manager=_badge_row("manager", _tab("grants", _badge("", "grant requests are pending")))), {}, "fail",
         "badge-tooltip"),
        ("badges: #31 a hidden badge", "badges",
         _badge_rows(manager=_badge_row("manager", _tab("grants", _badge(2, "2 grant requests are pending", visible=False)))), {},
         "fail", "badge-tooltip"),
        ("badges: #31 a tab without a badge announces a count in a tooltip", "badges",
         _badge_rows(auditor=_badge_row("auditor", _tab("requests", others=[{"tag": "span", "tooltip": "2 run requests are pending",
                                                                             "data_tooltip": None, "title": None}]))), {},
         "fail", "badge-tooltip-absent"),
        ("badges: #31 the approver-unlisted row is missing", "badges", _badge_rows(approver_unlisted=None), {}, "fail",
         "badge-tooltip-measured"),
        ("badges: #31 auditor's overview not measured (403, no tabs)", "badges",
         _badge_rows(auditor={"user": "auditor", "status": 403, "tabs": None}), {}, "fail", "badge-tooltip-measured"),
        ("badges: #31 no role shows a badge (the check would pass on nothing)", "badges",
         [_badge_row(u) for u in h1.BADGE_ROLES], {}, "fail", "badge-tooltip-measured"),
        ("badges: #31 no row at all", "badges", [], {}, "fail", "badge-tooltip-measured"),
        # #31 (r11/s_tabs.py #2): the tab bar at 1024 and 800 px (round3.py C)
        ("tab-bar-narrow: #31 the bar wraps at 800 px, the page is as wide as the viewport", "tab-bar-narrow", _narrow_rows(),
         {}, "pass", None),
        ("tab-bar-narrow: #31 one pixel of sub-pixel rounding is no scroll", "tab-bar-narrow",
         _narrow_rows(admin_800={"scrollW": 801}), {}, "pass", None),
        ("tab-bar-narrow: #31 the bar does not wrap and scrolls the page sideways at 800 px", "tab-bar-narrow",
         _narrow_rows(admin_800={"scrollW": 1066, "navRight": 1046, "rows": 1,
                                 "tabs": [dict(t, right=t["right"] + 300 * (i // 6)) for i, t in enumerate(_narrow("admin", 800)["tabs"])]}),
         {}, "fail", "tab-bar-narrow-scroll"),
        ("tab-bar-narrow: #31 the bar scrolls inside itself (overflow-x auto): tabs out of reach, no page scroll",
         "tab-bar-narrow", _narrow_rows(approver_1_800={"navScrollW": 1006, "overflowX": "auto"}), {}, "fail",
         "tab-bar-narrow-reach"),
        ("tab-bar-narrow: #31 a tab hidden at 1024 px", "tab-bar-narrow",
         _narrow_rows(admin_1024={"tabs": [dict(t, visible=t["tab"] != "history") for t in _narrow("admin", 1024)["tabs"]]}),
         {}, "fail", "tab-bar-narrow-reach"),
        ("tab-bar-narrow: #31 the 1024 px row of approver-1 missing", "tab-bar-narrow", _narrow_rows(drop=("approver-1", 1024)),
         {}, "fail", "tab-bar-narrow-measured"),
        ("tab-bar-narrow: #31 no tab bar on the overview", "tab-bar-narrow", _narrow_rows(admin_800={"nav": False, "tabs": []}),
         {}, "fail", "tab-bar-narrow-measured"),
        ("tab-bar-narrow: #31 fewer tabs at 800 px than at 1024 px", "tab-bar-narrow",
         _narrow_rows(admin_800={"tabs": _narrow("admin", 800)["tabs"][:6]}), {}, "fail", "tab-bar-narrow-measured"),
        ("tab-bar-narrow: #31 no row at all (section C did not reach the check)", "tab-bar-narrow", [], {}, "fail",
         "tab-bar-narrow-measured"),
        # #31 (r9/s89.py #4, r10/s89.py): the grants page's headers and short cells at 1280 px (round3.py F)
        ("grants-nowrap: #31 cells wrap only between words, headers and short cells on one line", "grants-nowrap",
         _wrap_rows(), {}, "pass", None),
        ("grants-nowrap: #31 the free-text Reason splits a long word (allowed)", "grants-nowrap",
         _wrap_rows(admin=_wrap_row("admin", pending=_wrap_table("pending", [_wrap_cell("Reason", "see https://x/a-very-long-url",
                                                                                      broken=["https://x/a-very-long-url"])]))),
         {}, "pass", None),
        ("grants-nowrap: #31 DEF-01: approver-2 breaks as approver- / 2", "grants-nowrap",
         _wrap_rows(admin=_wrap_row("admin", pending=_wrap_table("pending", [_wrap_cell("Approvers", "approver-2",
                                                                                      broken=["approver-2"])]))),
         {}, "fail", "grants-nowrap-word"),
        ("grants-nowrap: #31 DEF-01: a header on two lines", "grants-nowrap",
         _wrap_rows(requester=_wrap_row("requester", active=_wrap_table("active", heads=[
             {"text": h, "lines": 2 if h == "Remaining" else 1} for h in GRANT_HEADS["active"]]))),
         {}, "fail", "grants-nowrap-header"),
        ("grants-nowrap: #31 a duration '30 min' on two lines (a short cell wrapping between words)", "grants-nowrap",
         _wrap_rows(admin=_wrap_row("admin", pending=_wrap_table("pending", [_wrap_cell("Duration", "30 min")]))),
         {}, "fail", "grants-nowrap-short"),
        ("grants-nowrap: #31 Request again on two lines in the button column", "grants-nowrap",
         _wrap_rows(requester=_wrap_row("requester", ended=_wrap_table("ended", [_wrap_cell("", "Request again")]))),
         {}, "fail", "grants-nowrap-short"),
        ("grants-nowrap: #31 an item name split inside the Scope cell", "grants-nowrap",
         _wrap_rows(fonly=_wrap_row("fonly", ended=_wrap_table("ended", [_wrap_cell("Scope", "Freestyle project ops/del-me",
                                                                                  lines=3, broken=["ops/del-me"])]))),
         {}, "fail", "grants-nowrap-word"),
        ("grants-nowrap: #31 a date split inside its date part", "grants-nowrap",
         _wrap_rows(approver_1=_wrap_row("approver-1", active=_wrap_table("active", [_wrap_cell("Granted", "2026-11-06 00:47:51 KST",
                                                                                            lines=3, broken=["2026-11-06"])]))),
         {}, "fail", "grants-nowrap-word"),
        ("grants-nowrap: #31 requester's page without ended entries (premise of 'both tables populated')", "grants-nowrap",
         _wrap_rows(requester=_wrap_row("requester", ended=None)), {}, "fail", "grants-nowrap-measured"),
        ("grants-nowrap: #31 admin's active list empty", "grants-nowrap",
         _wrap_rows(admin=_wrap_row("admin", active=_wrap_table("active", [], rows=0))), {}, "fail", "grants-nowrap-measured"),
        ("grants-nowrap: #31 measured at another width", "grants-nowrap",
         _wrap_rows(admin=dict(_wrap_row("admin"), viewport=1024, clientW=1024)), {}, "fail", "grants-nowrap-measured"),
        ("grants-nowrap: #31 the admin row missing", "grants-nowrap", _wrap_rows()[1:], {}, "fail", "grants-nowrap-measured"),
        ("grants-nowrap: #31 no row at all (section F did not reach the grants page)", "grants-nowrap", [], {}, "fail",
         "grants-nowrap-measured"),
        # #31 (r7/e_dark.py #3, r11/s_dark_monitor.py #2): the dark palette (misc.py K)
        ("dark: #31 tables (a detail page's row header left transparent), alerts and the current tab in the dark variables",
         "dark", _dark_rows(), {"pages": 3},
         "pass", None),
        ("dark: #31 a list table with a light background of its own", "dark",
         _dark_rows(list=_dk_page("/batch-control/requests/", [_dk_table("pending", bg=WHITE_C)])), {"pages": 3}, "fail", "dark-table"),
        ("dark: #31 a table cell painted light (the light theme's --table-body-background kept)", "dark",
         _dark_rows(list=_dk_page("/batch-control/requests/", [_dk_table("pending", cells=[
             {"tag": "td", "cls": "", "bg": LT_VARS["body"], "color": DK_VARS["text"]}])])), {"pages": 3}, "fail", "dark-table"),
        ("dark: #31 cell text in a dark colour of its own (unreadable on the dark body)", "dark",
         _dark_rows(list=_dk_page("/batch-control/requests/", [_dk_table("pending", cells=[
             {"tag": "td", "cls": "", "bg": DK_VARS["body"], "color": DARK_TEXT_C}])])), {"pages": 3}, "fail", "dark-table"),
        ("dark: #31 a column header in the light theme's text colour", "dark",
         _dark_rows(list=_dk_page("/batch-control/requests/", [_dk_table("pending", heads=[{"text": "ID", "color": LT_VARS["head"]}])])),
         {"pages": 3}, "fail", "dark-table"),
        ("dark: #31 an alert with a white background", "dark",
         _dark_rows(detail=_dk_page("/batch-control/grants/x/", [], [_dk_alert("warning", bg=WHITE_C)], tab="grants")), {"pages": 3},
         "fail", "dark-alert"),
        ("dark: #31 an alert's text in a colour of its own", "dark",
         _dark_rows(job=_dk_page("/job/batch-daily/", [], [_dk_alert("info", color=DARK_TEXT_C)], tab=None)), {"pages": 3},
         "fail", "dark-alert"),
        ("dark: #31 an alert without a variant", "dark",
         _dark_rows(job=_dk_page("/job/batch-daily/", [], [dict(_dk_alert("info"), variant=None, cls="jenkins-alert")], tab=None)),
         {"pages": 3}, "fail", "dark-alert"),
        ("dark: #31 the current tab painted white", "dark",
         _dark_rows(list=dict(_dk_page("/batch-control/requests/", [_dk_table()]), tab={
             "current": {"tab": "requests", "color": DARK_TEXT_C, "before": WHITE_C},
             "other": {"tab": "overview", "color": SECONDARY_C, "before": NONE_C}})), {"pages": 3}, "fail", "dark-tab"),
        ("dark: #31 the current tab looks like the others (not marked)", "dark",
         _dark_rows(list=dict(_dk_page("/batch-control/requests/", [_dk_table()]), tab={
             "current": {"tab": "requests", "color": DK_VARS["text"], "before": DK_VARS["button"]},
             "other": {"tab": "overview", "color": DK_VARS["text"], "before": DK_VARS["button"]}})), {"pages": 3}, "fail", "dark-tab"),
        ("dark: #31 the bar shows no current tab", "dark",
         _dark_rows(list=dict(_dk_page("/batch-control/requests/", [_dk_table()]), tab={"current": None, "other": None})),
         {"pages": 3}, "fail", "dark-tab"),
        ("dark: #31 the page stayed light (data-theme none, the light variables)", "dark",
         _dark_rows(list=_dk_page("/batch-control/requests/", [_dk_table()], theme="none", vars=LT_VARS)), {"pages": 3},
         "fail", "dark-measured"),
        ("dark: #31 no light variables read before the switch", "dark", _dark_rows()[1:], {"pages": 3}, "fail", "dark-measured"),
        ("dark: #31 a page of K not measured", "dark", _dark_rows(job=None), {"pages": 3}, "fail", "dark-measured"),
        ("dark: #31 no alert on any page (alerts never measured)", "dark",
         _dark_rows(detail=_dk_page("/batch-control/grants/x/", [_dk_table()], [], tab="grants"),
                    job=_dk_page("/job/batch-daily/", [], [], tab=None)), {"pages": 3}, "fail", "dark-measured"),
        ("dark: #31 no row at all (section K did not run)", "dark", [], {"pages": 16}, "fail", "dark-measured"),
        # #31 (r6/s6.py): internal design references on a page a user sees
        ("crawl: #31 a design reference (D-61) in a page's text", "crawl",
         [page(req, design={"texts": 80, "skipped": 0, "refs": [{"match": "D-61", "text": "counts as badges (D-61)", "el": "p"}]})],
         crawl_ctx(), "fail", "design-ref"),
        ("crawl: #31 'SPEC item' in a tooltip behind Advanced", "crawl",
         [{"kind": "advanced", "page": "/manage/batch-control-configuration/", "role": "admin", "ui": "new", "result": "expanded",
           "design": {"texts": 90, "skipped": 0, "refs": [{"match": "SPEC item", "text": "see SPEC item 2", "el": "a@title"}]}}],
         crawl_ctx(), "fail", "design-ref"),
        ("crawl: #31 driver-typed text with a D-number is skipped, nothing else found", "crawl",
         [page(req, design={"texts": 80, "skipped": 1, "refs": []})], crawl_ctx(), "pass", None),
        ("crawl: #31 a page below 400 without the scan (the driver stopped measuring)", "crawl",
         [page(req, design=None)], crawl_ctx(), "fail", "design-ref-measured"),
        ("crawl: #31 a scan that read no text", "crawl",
         [page(req, design={"texts": 0, "skipped": 0, "refs": []})], crawl_ctx(), "fail", "design-ref-measured"),
        ("crawl: #31 an HTML page whose scan read no text", "crawl",
         [page(req, design={"texts": 0, "skipped": 0, "refs": [], "type": "text/html"})], crawl_ctx(), "fail",
         "design-ref-measured"),
        ("crawl: #31 a JSON document (shown in a <pre>) needs no scanned text", "crawl",
         [page("/batch-control/history/summary", design={"texts": 0, "skipped": 0, "refs": [], "type": "application/json"})],
         crawl_ctx(), "pass", None),
        ("crawl: #31 an Advanced row without the scan", "crawl",
         [page("/manage/batch-control-configuration/"),
          {"kind": "advanced", "page": "/manage/batch-control-configuration/", "role": "admin", "ui": "new", "result": "expanded"}],
         crawl_ctx(), "fail", "design-ref-measured"),
        ("crawl: #31 a 403 page is not scanned and needs no scan", "crawl",
         [page("/batch-control/dashboard/", 403, role="requester", design=None)], crawl_ctx("requester"), "pass", None),
        ("allowed: the header.js page error by its stack (U-1)", "crawl", [page(req, console=[U1_ENTRY])], crawl_ctx(), "pass", None),
        ("allowed: D-70 by its exact message", "crawl", [page("/job/batch-daily/", console=[D70_LINE])], crawl_ctx(), "pass", None),
        ("allowed: warnings are never judged", "crawl", [page(req, console=["warning: .behavior-loading is deprecated"])], crawl_ctx(), "pass", None),
        ("allowed: a 403 page the SPEC requires (requester, Dashboard)", "crawl", [page("/batch-control/dashboard/", 403, role="requester")],
         crawl_ctx("requester"), "pass", None),
        # ... and nothing else
        ("not hidden: collapse link with another status (500)", "crawl", [link(TOGGLE, "/toggleCollapse?paneId=executors", 500)],
         crawl_ctx(), "fail", "link"),
        ("not hidden: collapse link with another URL (anchored)", "crawl", [link(TOGGLE, "/toggleCollapse?paneId=executors&x=1", 404)],
         crawl_ctx(), "fail", "link"),
        ("not hidden: a 404 on a Batch Control page that only looks like C-03", "crawl",
         [link("/batch-control/activations/", "/toggleCollapse?paneId=executors", 404)], crawl_ctx(), "fail", "link"),
        ("not hidden: Workspace 404 for another role", "crawl",
         [link(WS_PAGE, "/job/ops/job/job-a/ws/", 404, role="requester", ui="classic", label="Workspace")],
         crawl_ctx("requester", "classic"), "fail", "link"),
        ("not hidden: Workspace 404 on the new job page (entry is classic only)", "crawl",
         [link(WS_PAGE, "/job/ops/job/job-a/ws/", 404, label="Workspace")], crawl_ctx(), "fail", "link"),
        ("not hidden: the same page error message with another stack", "crawl", [page(req, console=[U1_OTHER_STACK])], crawl_ctx(), "fail", "page-console"),
        ("not hidden: D-70's message with one word changed", "crawl", [page(req, console=[D70_LINE.replace("text/html", "text/plain")])],
         crawl_ctx(), "fail", "page-console"),
        ("not hidden: C-14's 400 on another job", "crawl",
         [click("/job/batch-daily/", "navigated", label="Direct Build (needs approval)",
                detail={"console": ["error: Failed to load resource: the server responded with a status of 400 (Bad Request)"]})],
         crawl_ctx(), "fail", "click-console"),
        ("not hidden: C-15's 405 with status 500", "crawl",
         [click("/job/batch-daily/", "navigated", ui="classic", label="Direct Build (needs approval)",
                detail={"console": ["error: Failed to load resource: the server responded with a status of 500 (Server Error)"]})],
         crawl_ctx(ui="classic"), "fail", "click-console"),
        ("not hidden: J-03's toast text on a controlled job", "jobui",
         [{"kind": "entry", "role": "requester", "ui": "new", "page": "/job/batch-daily/", "where": "main", "label": "Build Now",
           "result": "notified", "notifications": ["Build scheduled"], "console": []}],
         {"ui": "new", "roles": ["requester"], "prefix": PREFIX}, "fail", "notified"),
        # #31 X-23 (misc.py H "month-nav"): Changes and Incidents month navigation, Changes paging
        ("month-nav: #31 « to an empty October, an empty month long ago, Changes Older and Newer", "month-nav", _month_rows(),
         None, "pass", None),
        ("month-nav: #31 the previous month has records (no empty sentence)", "month-nav",
         _month_rows(changes={"prev.rows": 12, "prev.empty": None}, incidents={"prev.rows": 1, "prev.empty": None}), None, "pass", None),
        ("month-nav: #31 « from January goes to December of the year before", "month-nav", _month_rows(month="2027-01", prev="2026-12"),
         None, "pass", None),
        ("month-nav: #31 « on Changes skips a month", "month-nav",
         _month_rows(changes={"prev_href": "?month=2026-09", "prev.url": CH + "?month=2026-09", "prev.month": "2026-09",
                              "prev.empty": "No change records in 2026-09."}), None, "fail", "month-nav-prev"),
        ("month-nav: #31 « on Incidents lands on the shown month again", "month-nav",
         _month_rows(incidents={"prev.url": IN + "?month=2026-10", "prev.month": "2026-11", "prev.empty": "No incidents in 2026-11."}),
         None, "fail", "month-nav-prev"),
        ("month-nav: #31 an empty previous month without the empty sentence", "month-nav", _month_rows(incidents={"prev.empty": None}),
         None, "fail", "month-nav-prev"),
        ("month-nav: #31 the previous month lists records and still says it has none", "month-nav",
         _month_rows(changes={"prev.rows": 3}), None, "fail", "month-nav-prev"),
        ("month-nav: #31 Incidents' empty month says another sentence", "month-nav",
         _month_rows(incidents={"empty_month.empty": "No incident records in 2016-01."}), None, "fail", "month-nav-empty"),
        ("month-nav: #31 an empty month shows the current month instead of the asked one", "month-nav",
         _month_rows(changes={"empty_month.month": "2026-11", "empty_month.rows": 50, "empty_month.empty": None}), None, "fail",
         "month-nav-empty"),
        ("month-nav: #31 an empty month with a footer counting nothing", "month-nav",
         _month_rows(incidents={"empty_month.footer": "Page 1 (0 incidents in 2016-01)"}), None, "fail", "month-nav-empty"),
        ("month-nav: #31 Changes page 1 of 173 records without Older", "month-nav", _month_rows(changes={"paging.older_href": None}),
         None, "fail", "month-nav-paging"),
        ("month-nav: #31 page 2 repeats page 1", "month-nav",
         _month_rows(changes={"paging.page2.first_row": "CONFIG_CHANGE batch-control-configuration manager"}), None, "fail",
         "month-nav-paging"),
        ("month-nav: #31 page 2's footer says page 1", "month-nav",
         _month_rows(changes={"paging.page2.footer": "Page 1 (173 records in 2026-11)"}), None, "fail", "month-nav-paging"),
        ("month-nav: #31 page 2 without Newer", "month-nav", _month_rows(changes={"paging.page2.newer": 0}), None, "fail",
         "month-nav-paging"),
        ("month-nav: #31 page 1 lists 49 of 173", "month-nav", _month_rows(changes={"rows": 49}), None, "fail", "month-nav-paging"),
        ("month-nav: #31 the shown month of Changes holds 40 records (no Older to judge)", "month-nav",
         _month_rows(changes={"rows": 40, "footer": "Page 1 (40 records in 2026-11)", "paging": None}), None, "fail",
         "month-nav-measured"),
        ("month-nav: #31 the Incidents row missing", "month-nav", _month_rows(drop=IN), None, "fail", "month-nav-measured"),
        ("month-nav: #31 « not followed", "month-nav", _month_rows(changes={"prev": None}), None, "fail", "month-nav-measured"),
        ("month-nav: #31 no row at all", "month-nav", [], None, "fail", "month-nav-measured"),
    ]


def run_synthetic():
    results = []
    for name, driver, rows, ctx, want, check in synthetic():
        lines = h1.judge(driver, rows, ctx).lines()
        fails = [x for x in lines if x.startswith("FAIL ")]
        if want == "fail":
            ok = any(x.startswith(f"FAIL {driver.split('/')[0]} {check} ") for x in fails)
        else:
            ok = not fails
        results.append((ok, name, want, fails[:2]))
    return results


def _el(text, region="side", widget=None, tag="a", href="x/"):
    """An element as r15/check.py's COLLECT records it."""
    return {"tag": tag, "text": text, "href": href, "region": region, "visible": True, "widget": widget}


def r15_check_cases():
    """r15/check.py's duplicate check (h1.r15_duplicates) and the runner's verdict on the row (shard.r15_problems):
    (name, collected elements, want "fail" | "pass")."""
    tasks = [_el("Status"), _el("Changes"), _el("Build with Parameters"), _el("Request Run"), _el("Configure")]
    history = [_el("Builds", widget="jenkins-builds"), _el("Success", widget="jenkins-build-history"),
               _el("#2 10:41 PM", widget="jenkins-build-history"), _el("Success", widget="jenkins-build-history"),
               _el("#1 10:40 PM", widget="jenkins-build-history"), _el("Newer builds", tag="button", widget="jenkins-builds"),
               _el("Older builds", tag="button", widget="jenkins-builds")]
    return [
        # CI run 40504779419 (admin, classic, /job/batch-daily/batch-control-activation/): two builds' status badges
        ("r15 check: two \"Success\" badges inside the build history widget", tasks + history, "pass"),
        ("r15 check: two identical queue / executor entries (batch-cron) inside their panes", tasks + [
            _el("batch-cron", widget="buildQueue"), _el("batch-cron", widget="buildQueue"),
            _el("batch-cron #21", widget="executors"), _el("Cancel", widget="executors"),
            _el("batch-cron #22", widget="executors"), _el("Cancel", widget="executors")], "pass"),
        ("r15 check: a task link and a widget entry with the same text", tasks + [_el("Status", widget="executors")], "pass"),
        ("not hidden: two \"Request Run\" task links in the side panel outside the widgets",
         tasks + [_el("Request Run")] + history, "fail"),
        ("not hidden: two \"Success\" entries in the side panel outside the widgets", tasks + [_el("Success"), _el("Success")], "fail"),
        ("not hidden: a duplicate app-bar entry", tasks + [_el("Request Run", "appbar"), _el("Request Run", "appbar")], "fail"),
        ("not hidden: a widget id that is not one of core's repeating widgets",
         tasks + [_el("Request Run", widget="tasks"), _el("Request Run", widget="tasks")], "fail"),
    ]


STOP_CI = "/computer/(built-in)/executors/1/stopBuild?runExtId=batch-cron%2321"


def r15_stop_cases():
    """r15/check.py's fetched links (h1.r15_core_stop, then shard.r15_problems): (name, element, path, status, want)."""
    stop = lambda w: {"tag": "a", "text": "Cancel", "region": "side", "cls": "stop-button-link", "widget": w}  # noqa: E731
    return [
        # CI run 40504779419 (admin, new job page, /job/team-mb/batch-control-activation/)
        ("r15 check: the executors pane's stop button of CI run 40504779419, GET 405", stop("executors"), STOP_CI, 405, "pass"),
        ("r15 check: a one-off executor's stop button whose build ended, GET 404", stop("executors"),
         "/computer/(built-in)/oneOffExecutors/0/stopBuild?runExtId=batch-pipeline%237", 404, "pass"),
        ("r15 check: the Build Queue's cancel button, GET 405", stop("buildQueue"), "/queue/cancelItem?id=42", 405, "pass"),
        ("r15 check: the build history's stop button of a running build, GET 405", stop("jenkins-build-history"),
         "/job/team-mb/job/main/3/stop", 405, "pass"),
        ("not hidden: the same stop URL outside a widget", stop(None), STOP_CI, 405, "fail"),
        ("not hidden: the same stop URL answering 500", stop("executors"), STOP_CI, 500, "fail"),
        ("not hidden: a regular executor's stopBuild answering 404", stop("executors"), STOP_CI, 404, "fail"),
        ("not hidden: a plugin link in the executors pane answering 405, not a stop path", stop("executors"),
         "/job/batch-daily/batch-control-activation/submit", 405, "fail"),
        ("not hidden: the stop URL without core's stop-button-link class", dict(stop("executors"), cls="task-link"),
         STOP_CI, 405, "fail"),
        ("not hidden: a stop URL of another pane (queue cancel in the executors pane)", stop("executors"),
         "/queue/cancelItem?id=42", 405, "fail"),
        ("not hidden: a stop URL with a trailing extra (no full match)", stop("executors"), STOP_CI + "&x=1", 405, "fail"),
    ]


def run_r15_check():
    spec = importlib.util.spec_from_file_location("bc_shard", HERE / "shard.py")
    shard = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(shard)
    results = []
    for name, e, path, st, want in r15_stop_cases():
        kind = h1.r15_core_stop(e, path, st) or "link_status"  # r15/check.py's fallback for a status >= 400
        left = shard.r15_problems({"problems": [{"kind": kind, "text": e["text"], "region": e["region"], "url": path,
                                                 "status": st}], "console": [], "bad_responses": []})
        ok = bool(left) if want == "fail" else not left
        results.append((ok, name, want, left[:2]))
    for name, els, want in r15_check_cases():
        dups, _ = h1.r15_duplicates(els)
        left = shard.r15_problems({"user": "admin", "flag": "false", "page": "/job/batch-daily/batch-control-activation/",
                                   "problems": dups, "console": [], "bad_responses": []})
        ok = bool(left) if want == "fail" else not left
        results.append((ok, name, want, left[:2]))
    return results


def run_stale():
    lines = h1.judge("crawl", [], crawl_ctx("admin", "classic")).lines()
    stale = {x.split()[2] for x in lines if x.startswith("STALE allow ")}
    want = {"C-03", "C-04", "C-13b", "C-14", "C-15", "D-70", "U-1-header"}  # C-13a is new-UI only
    return stale == want, stale, want


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--baseline", help="directory holding the e2e-shard-<k> artefact directories of a CI run")
    ap.add_argument("--make-fixture", metavar="DIR", help="write ci/fixtures/h1-baseline.json.gz from such a directory")
    a = ap.parse_args()
    if a.make_fixture:
        units = compact(read_artefacts(a.make_fixture))
        FIXTURE.parent.mkdir(parents=True, exist_ok=True)
        with gzip.open(FIXTURE, "wt") as f:
            json.dump(units, f, separators=(",", ":"), sort_keys=True)
        print(f"wrote {FIXTURE} ({FIXTURE.stat().st_size} bytes, {len(units)} units, {sum(len(u['rows']) for u in units)} rows)")
        return 0
    good, report = check_all(a.baseline)
    print("\n".join(report))
    return 0 if good else 1


def check_all(baseline=None):
    """Runs every check; returns (good, report lines). Used by main() and ci/test_selftest_content.py."""
    SKIPPED.clear()
    if baseline:
        units, src = read_artefacts(baseline), baseline
    else:
        with gzip.open(FIXTURE, "rt") as f:
            units, src = json.load(f), str(FIXTURE.relative_to(HERE.parent.parent))
    fails, u1, stale, matched = replay(units)
    report = [f"{'PASS' if not fails else 'FAIL'} selftest-h1 baseline replay ({src}: {len(units)} units, "
              f"{sum(len(u['rows']) for u in units)} rows): {len(fails)} FAIL line(s) besides U-1"]
    report += ["   " + x[:400] for x in fails[:40]]
    report.append(f"INFO selftest-h1 U-1 (first-row job chevron, clicked without hover by the old driver): {len(u1)} FAIL "
                  "line(s), kept failing by design until a run of the fixed driver")
    report += ["   " + x[:300] for x in u1[:12]]
    report.append(f"INFO selftest-h1 allowlist entries matched by the baseline: {', '.join(matched)}")
    report.append(f"INFO selftest-h1 allowlist entries stale in the baseline: {', '.join(stale) or 'none'}")
    report += [f"INFO selftest-h1 left out of the replay: {u}: {why}" for u, why in dict(SKIPPED).items()]
    syn = run_synthetic()
    for ok, name, want, fl in syn:
        report.append(f"{'PASS' if ok else 'FAIL'} selftest-h1 synthetic ({want}): {name}" + ("" if ok else f" -> {fl}"))
    r15c = run_r15_check()
    for ok, name, want, left in r15c:
        report.append(f"{'PASS' if ok else 'FAIL'} selftest-h1 r15 check ({want}): {name}" + ("" if ok else f" -> {left}"))
    st_ok, stale_ids, want_ids = run_stale()
    report.append(f"{'PASS' if st_ok else 'FAIL'} selftest-h1 stale: an empty admin classic crawl reports {sorted(stale_ids)}"
                  + ("" if st_ok else f", expected {sorted(want_ids)}"))
    good = not fails and all(ok for ok, *_ in syn) and all(ok for ok, *_ in r15c) and st_ok
    return good, report


if __name__ == "__main__":
    sys.exit(main())
