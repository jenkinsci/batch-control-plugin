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
        for name in ("grant_overlay", "assign_roles", "manage_roles", "endpoints"):
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
        lines = h1.judge(u["driver"], rows, u["ctx"]).lines()
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


def page(path, status=200, role="admin", ui="new", console=()):
    return {"kind": "page", "page": path, "status": status, "role": role, "ui": ui, "console": list(console)}


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
           "mark_links_on_page": [["team", 200]], "console": []}], {}, "pass", None),
        ("misc: the move refusal page answers 403 with its links", "misc",
         [{"sec": "R", "case": "move refusal", "role": "mover1", "window": IDS["req_pending"], "move_page": 200,
           "destinations": ["/ops", "/team"], "status": 403, "links": [["z", "/job/prod/job/z/", 200]], "still_in_prod": 200}], {}, "pass", None),
        ("misc: the move refusal never reached (no destination)", "misc",
         [{"sec": "R", "case": "move refusal", "role": "mover1", "move_page": 200, "destinations": [], "still_in_prod": 200}], {}, "fail", "R-move"),
        ("help: a help block that does not open", "help2", [{"page": "/x", "help": "y", "before": 3, "after": 3}], {}, "fail", "help-click"),
        ("role: Manage Roles save loses the window (200 -> 403)", "role/grant_overlay",
         [{"0 before: requester configure": 403, "1 requested (pending): requester configure": 403,
           "2 approved (window open): requester configure": 200, "3 after a Manage Roles save during the window: requester configure": 403,
           "4 revoked: requester configure": 403, "browser configure after revoke": 403}], {}, "fail", "overlay"),
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
