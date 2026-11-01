"""H1 (e2e gap audit): the fail rules of the observational e2e drivers, with one keyed allowlist.

The drivers (r14/crawl.py, actions.py, jobui.py, misc.py, the targeted scripts, r14/role/*, r15/side_dialog.py,
folder_compare.py, menu.py) collect their rows as before and, at their end, call report(driver, rows, ctx). It prints

    FAIL <driver> <check> <json>      a finding nothing explains (ci/shard.py fails the step on any "FAIL " line)
    ALLOW <id> matched <n>            an allowlist entry that explained n findings of this run
    STALE allow <id> <class> <source> an applicable allowlist entry that matched nothing (a warning, listed in the
                                      shard summary; it may mean the core issue was fixed and the entry should go)

Rules (the H1 triage of E2E run 39612236057 on c347803c, section 5; the ids C-nn, A-nn, J-nn, M-nn, T-nn, U-n, X-nn
in the sources and the drivers' comments are that triage's finding ids):
  * Only console entries of type error and pageerror count; warnings are never judged.
  * An allowlist entry (ALLOW) names the driver, the check, a role list ("*" only where the rule does not depend on the
    role), an anchored regex on the page (the URL pattern of page_key()), optionally an anchored regex on the target,
    the exact label, the exact HTTP status and/or the exact normalised message (URLs, ids and digits replaced), and for
    a page error the leading stack frames ("function@file"). Nothing is matched as a substring. Every entry carries its
    triage class (EXPECTED, KNOWN-CORE) and its source (SPEC line, e2e report, LIMITATIONS item, core issue).
  * Refusals the SPEC requires are expectation tables (PAGE_STATUS, ACTION_PROBES, MISC_STATUS), not allowlists: a
    status that differs FAILS in both directions, so "200 where 404 is required" is a failure too.
  * Findings are deduplicated by their key (check, role, UI, page pattern, target, label, status, message); a line
    carries the number of rows it stands for and one example.

Standard library only: ci/selftest_h1.py feeds it recorded rows without Jenkins or a browser."""
import json
import re
from collections import Counter
from datetime import datetime

URL = re.compile(r"https?://[^\s'\"<>)]+")
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
CID = re.compile(r"\d{8}-\d{6}-[a-z0-9]{6}")
FRAME = re.compile(r"^\s*at (?:async )?(?:([^\s(]+) \()?(?:[^()\s]*/)?([^/():\s]+):\d+:\d+\)?\s*$")


# ------------------------------------------------------------------ normalisation
def first_line(entry):
    return (entry or "").split("\n", 1)[0]


def is_error(entry):
    """Only these console entries count (r6/lib.py records error, warning and pageerror)."""
    return isinstance(entry, str) and (entry.startswith("error: ") or entry.startswith("pageerror: "))


def norm_message(entry):
    m = URL.sub("{url}", first_line(entry))
    m = UUID.sub("{id}", m)
    return re.sub(r"\d+", "{n}", m)


def http_status(entry):
    m = re.search(r"\bstatus of (\d{3})\b", first_line(entry))
    return int(m.group(1)) if m else None


def stack_frames(entry):
    """The stack frames of a console entry as "function@file" (r6/lib.py writes a page error's stack after "stack: ";
    core's console.error of an Error carries it in the message's next lines)."""
    out = []
    for line in (entry or "").split("\n")[1:]:
        if line.startswith("url: "):
            break
        m = FRAME.match(line)
        if m:
            out.append(f"{(m.group(1) or '<anonymous>').split('.')[-1]}@{m.group(2)}")
    return out


def strip_base(url, prefix=""):
    """A URL or path as the drivers write paths: without scheme, host and the context path."""
    p = re.sub(r"^https?://[^/]+", "", url or "")
    if prefix and (p == prefix or p.startswith(prefix + "/")):
        p = p[len(prefix):]
    return p


def page_key(path, ids=None, prefix=""):
    """The URL pattern the allowlist and the tables match: the seeded record ids by their ids.json name ({req_pending}),
    other record ids as {id}/{cid}, build numbers {n}, page numbers, months and dates."""
    p = strip_base(path, prefix)
    for k, v in (ids or {}).items():
        if isinstance(v, str) and UUID.fullmatch(v):
            p = p.replace(v, "{" + k + "}")
    p = UUID.sub("{id}", p)
    p = CID.sub("{cid}", p)
    for _ in range(2):
        p = re.sub(r"/\d+(/|$)", r"/{n}\1", p)
    p = re.sub(r"([?&][A-Za-z]*[pP]age)=\d+", r"\1={n}", p)
    p = re.sub(r"month=[\d-]+", "month={m}", p)
    return re.sub(r"\d{4}-\d{2}-\d{2}", "{d}", p)


# ------------------------------------------------------------------ allowlist
class Allow:
    def __init__(self, id, driver, check, roles, page, cls, source, ui=None, profile=None, target=None, label=None,
                 status=None, message=None, stack=None, where=None):
        assert cls in ("EXPECTED", "KNOWN-CORE"), cls
        assert roles == "*" or isinstance(roles, (list, tuple)), roles
        self.id, self.driver, self.check, self.roles, self.cls, self.source = id, driver, check, roles, cls, source
        self.page = re.compile(page)
        self.target = re.compile(target) if target is not None else None
        self.ui, self.profile, self.where = ui, profile, where
        self.label = (label,) if isinstance(label, str) else label
        self.status, self.message, self.stack = status, message, stack

    def matches(self, f):
        if self.roles != "*" and f.get("role") not in self.roles:
            return False
        if self.ui is not None and f.get("ui") != self.ui:
            return False
        if self.profile is not None and f.get("profile", "matrix") != self.profile:
            return False
        if self.where is not None and f.get("where") != self.where:
            return False
        if not self.page.fullmatch(f.get("page") or ""):
            return False
        if self.target is not None and not self.target.fullmatch(f.get("target") or ""):
            return False
        if self.label is not None and f.get("label") not in self.label:
            return False
        if self.status is not None and f.get("status") != self.status:
            return False
        if self.message is not None and f.get("message") != self.message:
            return False
        if self.stack is not None and (f.get("stack") or [])[:len(self.stack)] != list(self.stack):
            return False
        return True

    def applies(self, driver, ctx):
        if self.driver not in ("*", driver):
            return False
        roles = ctx.get("roles") or ([ctx["role"]] if ctx.get("role") else None)
        if roles and self.roles != "*" and not set(roles) & set(self.roles):
            return False
        if ctx.get("ui") and self.ui and ctx["ui"] != self.ui:
            return False
        if self.profile and ctx.get("profile", "matrix") != self.profile:
            return False
        return True


JOBS_A_B_DEEP = r"(ops/job/job-a|side/job/job-b|team/job/sub/job/deep-job)"
JOB_SUBPAGE = r"(batch-control/?|batch-control-activation/)?"
D70 = ("error: Refused to execute script from '{url}' because its MIME type ('text/html') is not executable, and strict "
       "MIME type checking is enabled.")
U1_MSG = "pageerror: Cannot read properties of undefined (reading 'replace')"
U1_STACK = ["xmlEscape@header.js", "menuItem@header.js"]
C13_MSG = "error: Jumplist request failed: TypeError: Cannot read properties of null (reading 'startsWith')"
R400 = "error: Failed to load resource: the server responded with a status of {n} (Bad Request)"
R405 = "error: Failed to load resource: the server responded with a status of {n} (Method Not Allowed)"
SRC_D70 = "D-70, e2e-11 UX 2 (core DialogEvent loads a script URL that answers text/html)"
SRC_U1 = "e2e-13 U-1, e2e-14 (core header.js breadcrumb overflow: xmlEscape <- menuItem)"


CONSOLE_CHECKS = ("console", "page-console", "click-console")
ALLOW = [
    # ---------------------------------------------------------------- crawl (r14/crawl.py)
    Allow("C-03", "crawl", "link", "*", r"(/job/[^/]+)+/batch-control-activation/", "EXPECTED",
          "triage C-03; e2e-04, e2e-12 Known 4: core's executor/queue collapse links answer 404 to a GET",
          target=r"/toggleCollapse\?paneId=(executors|buildQueue)", status=404),
    Allow("C-04", "crawl", "link", ["admin"], rf"/job/{JOBS_A_B_DEEP}/{JOB_SUBPAGE}", "EXPECTED",
          "triage C-04; e2e-12 Known 4: core's Workspace link answers 404 until the job's first build",
          ui="classic", target=rf"/job/{JOBS_A_B_DEEP}/ws/", label="Workspace", status=404),
    Allow("C-13a", "crawl", "click-console", "*", rf"/job/{JOBS_A_B_DEEP}/(batch-control/|batch-control-activation/)",
          "KNOWN-CORE", "triage C-13; LIMITATIONS 45, GitHub issue #3, e2e-12 Known 2 (rebuild's Rebuild Last with a null URL)",
          ui="new", label="More actions", message=C13_MSG, stack=["menuItem@app.js"]),
    Allow("C-13b", "crawl", "click-console", "*", r"/batch-control/requests/\{[a-z_]+\}/", "KNOWN-CORE",
          "triage C-13 (the deep-job chevron on a request page); LIMITATIONS 45, GitHub issue #3",
          target=r"/job/team/job/sub/job/deep-job/", label="", message=C13_MSG, stack=["menuItem@app.js"]),
    Allow("C-14", "crawl", "click-console", "*", rf"/job/{JOBS_A_B_DEEP}/{JOB_SUBPAGE}", "KNOWN-CORE",
          "triage C-14; LIMITATIONS 41 (second paragraph), e2e-12 Known 1: core posts the parameterless build, the "
          "plugin refuses it with 400 and core shows its generic toast", label="Direct Build (needs approval)",
          status=400, message=R400),
    Allow("C-15", "crawl", "click-console", "*", rf"(/view/batch-view)?/job/(batch-daily|batch-pipeline|batch-failing)/{JOB_SUBPAGE}",
          "EXPECTED", "triage C-15; e2e-12 Known 4: core serves the parameters page with status 405 and the page works",
          ui="classic", label=("Direct Build (needs approval)", "Build with Parameters"), status=405, message=R405),
    # ---------------------------------------------------------------- every driver's console (exact message; the page
    # error also by its leading stack frames): core issues that can show on any page for any role
    Allow("D-70", "*", CONSOLE_CHECKS, "*", r"/.*", "KNOWN-CORE", SRC_D70, message=D70),
    Allow("U-1-header", "*", CONSOLE_CHECKS, "*", r"/.*", "KNOWN-CORE", SRC_U1 + "; misc B (M-05), role grant_overlay (R-01)",
          message=U1_MSG, stack=U1_STACK),
    # ---------------------------------------------------------------- jobui (r14/jobui.py)
    Allow("J-02", "jobui", "notified", "*", rf"/job/{JOBS_A_B_DEEP}/", "KNOWN-CORE",
          "triage J-02; LIMITATIONS 41, e2e-12 Known 1", label="Direct Build (needs approval)",
          message="Failed to schedule build. Reload the page and try again."),
    Allow("J-02c", "jobui", "console", "*", rf"/job/{JOBS_A_B_DEEP}/", "KNOWN-CORE",
          "triage J-02 (the refused POST behind the toast); LIMITATIONS 41", label="Direct Build (needs approval)",
          status=400, message=R400),
    Allow("J-03a", "jobui", "notified", "*", r"/job/(batch-cron|batch-upstream)/", "EXPECTED",
          "triage J-03: core's Build Now on a job without approval schedules the build and says so",
          label="Build Now", message="Build scheduled"),
    Allow("J-03b", "jobui", "notified", "*", r"/job/batch-cron/", "EXPECTED",
          "triage J-03: the rebuild plugin's classic Rebuild Last posts and says Done.", ui="classic", where="side",
          label="Rebuild Last", message="Done."),
    Allow("J-04", "jobui", "nav", "*", r"/job/batch-cron/", "KNOWN-CORE",
          "triage J-04; e2e-12 Known 3, e2e-14: the rebuild plugin's overflow Rebuild Last does a GET on a POST URL",
          ui="new", where="overflow", label="Rebuild Last", target=r"/job/batch-cron/lastCompletedBuild/rebuild/", status=405),
    Allow("J-04c", "jobui", "console", "*", r"/job/batch-cron/", "KNOWN-CORE", "triage J-04 (its console line)",
          ui="new", label="Rebuild Last", status=405, message=R405),
    Allow("J-05", "jobui", "nav", "*", r"(/view/batch-view)?/job/(batch-daily|batch-pipeline)/", "EXPECTED",
          "triage J-05/C-15; e2e-12 Known 4: core's parameters page answers 405 and works", ui="classic", where="side",
          label="Direct Build (needs approval)", target=r"(/view/batch-view)?/job/(batch-daily|batch-pipeline)/build\?delay=0sec",
          status=405),
    Allow("J-05c", "jobui", "console", "*", r"(/view/batch-view)?/job/(batch-daily|batch-pipeline)/", "EXPECTED",
          "triage J-05 (its console line)", ui="classic", label="Direct Build (needs approval)", status=405, message=R405),
    Allow("J-07", "jobui", "console", ["nobc"], r"(/view/batch-view)?/job/(batch-daily|batch-pipeline)/", "EXPECTED",
          "triage J-07; SPEC 6 D-60, TEST-MATRIX note 185, e2e-06: a user who may not request keeps the 400 refusal in "
          "the dialog", ui="new", label="Direct Build (needs approval)", status=400, message=R400),
] 
_ids = [a.id for a in ALLOW]
assert len(_ids) == len(set(_ids)), "allowlist ids must be unique"


# ------------------------------------------------------------------ expectation tables
# (id, profile or None, roles, anchored page regex, status, source); first match wins, default 200.
BC_ROOT = r"/batch-control/.*"
VIEW_HISTORY = r"/batch-control/((changes|dashboard|incidents)/|history/(\?kind=[a-z]+)?|history/summary)"
PAGE_STATUS = [
    ("nobc-bc", None, ["nobc"], BC_ROOT, 404, "triage C-05; SPEC 2 line 51 (no Batch Control permission: 404 below /batch-control/)"),
    ("nobc-job", None, ["nobc"], r"/job/batch-daily/batch-control/|/job/batch-cron/batch-control-activation/", 404,
     "triage C-05; SPEC 2 line 54 (the per-job action is absent), D-64"),
    ("manage", None, ["manager", "requester", "approver-1", "reqonly", "nobc"], r"/manage/", 403,
     "triage C-10: core, /manage/ needs Overall/Administer or Manage"),
    ("rs-outside-team", "role", ["requester"], r"/job/(?!team/).*|/view/batch-view/job/.*", 404,
     "triage C-11: e2e/casc/profile-role.yaml gives Job/Read only on team/* (core's no-Item/Read 404)"),
    ("viewhistory", None, ["requester", "reqonly"], VIEW_HISTORY, 403,
     "triage C-06; SPEC 2 line 51, SPEC line 223 (without ViewHistory every view answers 403)"),
    ("reqonly-grants", None, ["reqonly"], r"/batch-control/grants/(new|\{[a-z_0-9]+\}/)?", 403,
     "triage C-07; SPEC 2 line 51 (reqonly lacks RequestGrant)"),
    ("reqonly-activations", None, ["reqonly"], r"/batch-control/activations/\{[a-z_0-9]+\}/", 404,
     "triage C-09; P-09 (DECISIONS line 231): a record hidden from the viewer renders as 404"),
    ("requester-fonly-grant", None, ["requester"], r"/batch-control/grants/\{g_active_fonly\}/", 404,
     "triage C-09; P-09, SPEC 2 line 52"),
    ("rs-requester-hidden", "role", ["requester"], r"/batch-control/requests/\{(req_pending_reqonly|req_pending_opsreq)\}/", 404,
     "triage C-09 (role profile); P-09, SPEC 2 line 52"),
    ("approver-job", None, ["approver-1"], r"/job/batch-daily/batch-control/|/job/batch-cron/batch-control-activation/", 404,
     "triage C-08; SPEC 2 lines 52 and 54, D-64 (approver-1 has no Request)"),
]

# actions.py: the detail page status and the state-changing controls per role and item kind (triage A-01 and the
# control lists seen on c347803c, which follow SPEC 2 line 51, SPEC 3/4 (decide, cancel, re-designate), SPEC 8 (revoke),
# SPEC 11 line 211 (incident actions), P-09).
_ADM = {"run": (200, ["Approve", "Reject", "Cancel Request"]), "act": (200, ["Approve Activation", "Reject", "Cancel Request"]),
        "hold": (200, ["Approve Hold", "Reject", "Cancel Request"]), "grant": (200, ["Approve", "Reject", "Cancel Request"]),
        "window": (200, ["Revoke"]), "incident": (200, ["Acknowledge", "Add Comment", "Request Rerun"])}
ACTION_PROBES = {
    "admin": dict(_ADM, run_reqonly=_ADM["run"]),
    "requester": {"run": (200, ["Change Approvers", "Cancel Request"]), "run_reqonly": (200, []),
                  "act": (200, ["Change Approvers", "Cancel Request"]), "hold": (200, ["Change Approvers", "Cancel Request"]),
                  "grant": (200, ["Change Approvers", "Cancel Request"]), "window": (200, []), "incident": (403, [])},
    "reqonly": {"run": (200, []), "run_reqonly": (200, ["Change Approvers", "Cancel Request"]), "act": (404, []),
                "hold": (404, []), "grant": (403, []), "window": (403, []), "incident": (403, [])},
    "approver-1": {"run": (200, ["Approve", "Reject"]), "run_reqonly": (200, ["Approve", "Reject"]),
                   "act": (200, ["Approve Activation", "Reject"]), "hold": (200, ["Approve Hold", "Reject"]),
                   "grant": (200, ["Approve", "Reject"]), "window": (200, []), "incident": (200, ["Acknowledge", "Add Comment"])},
    "manager": {"run": (200, ["Cancel Request"]), "run_reqonly": (200, ["Cancel Request"]), "act": (200, ["Cancel Request"]),
                "hold": (200, ["Cancel Request"]), "grant": (200, ["Cancel Request"]), "window": (200, ["Revoke"]),
                "incident": (200, ["Acknowledge", "Add Comment", "Request Rerun"])},
    "nobc": {k: (404, []) for k in ("run", "run_reqonly", "act", "hold", "grant", "window", "incident")},
}
# the state a control leaves, on the page (State row, else Status) and on the server (the same row read over REST);
# (control, item kinds or None for any, page, server). A state the SPEC moves on from by itself is accepted in each
# state it legitimately passes through, on the page as on the server, and nothing else: an approved run request is
# scheduled at once and becomes EXECUTED when its build starts (SPEC 4: PENDING -> APPROVED -> EXECUTED; CI run
# 40175641627: admin's page already read "Executed", as the server did in #102); an approved activation or hold stays
# APPROVED and an approved grant opens its window for its whole duration (15 minutes here), so these keep one state.
ACTION_STATE = [
    (r"Approve", ("grant",), r"Open, .*", r"Open, .*"),
    (r"Approve", ("run", "run_reqonly"), r"(Approved|Executed)\b.*", r"(Approved|Executed)\b.*"),
    (r"Approve Activation", ("act",), r"Approved\b.*", r"Approved\b.*"),
    (r"Approve Hold", ("hold",), r"Approved\b.*", r"Approved\b.*"),
    (r"Reject", None, r"Rejected", r"Rejected"),
    (r"Cancel Request", None, r"Cancelled", r"Cancelled"),
    (r"Revoke", None, r"Revoked by {role}", r"Revoked by {role}"),
    (r"Acknowledge", None, r"Acknowledged", r"Acknowledged"),
    (r"Add Comment", None, r"Open", r"Open"),
    (r"Change Approvers", None, r"Pending", r"Pending"),
    (r"Request Rerun", None, r"Pending", r"Open"),
]

# misc.py: status per role (SPEC 6 line 308, SPEC 2 line 51; triage M-09, M-11, M-14)
MISC_STATUS = {
    ("C", "page"): {"admin": 200, "manager": 200, "approver-1": 403, "requester": 403, "nobc": 403},
    ("C", "configSubmit"): {"admin": 302, "manager": 302, "approver-1": 403, "requester": 403, "nobc": 403},
    ("H", "history"): {"approver-1": 200, "admin": 200, "manager": 200, "reqonly": 403, "nobc": 404},
    ("B", "/batch-control/requests/"): {"admin": 200, "requester": 200, "approver-1": 200, "manager": 200, "nobc": 404},
    ("B", "/batch-control/history/"): {"admin": 200, "requester": 403, "approver-1": 200, "manager": 200, "nobc": 404},
    ("B", "/job/batch-daily/batch-control/"): {"admin": 200, "requester": 200, "approver-1": 404, "manager": 200, "nobc": 404},
    ("R", "post_build"): {"nobc": 400, "requester": 400, "reqonly": 403},
    ("M", "page"): {"admin": 200, "requester": 200, "approver-1": 404, "manager": 200},
    ("M", "mark_button"): {"admin": 1, "requester": 0, "manager": 0},
}
# misc.py P: newest-first lists that grow at their top while the pager is walked (the timer runs finishing meanwhile).
# On these, Previous may bring page 1 back with its first row moved down, but only below rows that are newer than it
# or of the same second by the table's time column (see _shift_ok); no fixed row limit. Every other paged list restores exactly.
GROWING_LISTS = {"/batch-control/history/?kind=runs"}
def _shown_time(text):
    """A time cell as the plugin shows it (ui/Dates.java: "yyyy-MM-dd HH:mm:ss z") -> (datetime, zone), else None."""
    m = re.fullmatch(r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) (\S+)", (text or "").strip())
    if not m:
        return None
    try:
        return datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S"), m.group(2)
    except ValueError:
        return None


def _shift_ok(pv):
    """Previous on a growing list: page 1's first row is back on page 1 at index k >= 0 and each of the k rows above it
    is newer than it or of the same second by the time column (same zone; the list is sorted by start time descending,
    so an equal time at second resolution is a correct order; older, unparseable or missing times fail). A first row
    pushed off page 1 altogether fails: more new rows than a page holds in a few seconds is not expected, and judging
    it would need page 2 read too."""
    if pv.get("first_row_restored"):
        return True  # k == 0 (also the records from before first_row_at was recorded)
    k = pv.get("first_row_at")
    if not isinstance(k, int) or isinstance(k, bool) or k < 0:
        return False
    if k == 0:
        return True
    above = pv.get("above_times")
    first = _shown_time(pv.get("first_row_time"))
    if first is None or not isinstance(above, list) or len(above) != k:
        return False
    for t in above:
        a = _shown_time(t)
        if a is None or a[1] != first[1] or not a[0] >= first[0]:
            return False
    return True


# the Batch Control breadcrumb menu per role (SPEC 2: a tab only for a user who may open its section)
_TABS = ["Overview", "Run Requests", "Activations", "Grants"]
_HIST = _TABS + ["Changes", "Dashboard", "Incidents", "History"]
MISC_MENU = {"admin": _HIST + ["Configuration"], "manager": _HIST + ["Configuration"], "approver-1": _HIST,
             "requester": _TABS}


def expected_page_status(profile, role, key):
    for rid, prof, roles, rx, status, src in PAGE_STATUS:
        if (prof is None or prof == profile) and role in roles and re.fullmatch(rx, key):
            return status, rid
    return 200, None


# ------------------------------------------------------------------ the judge
class Judge:
    def __init__(self, driver, ctx=None):
        self.driver, self.ctx = driver, dict(ctx or {})
        self.fails = {}
        self.seen = Counter()

    def allowed(self, check, **f):
        for a in ALLOW:
            if (a.driver in ("*", self.driver) and (check in a.check if isinstance(a.check, tuple) else a.check == check)
                    and a.matches(f)):
                self.seen[a.id] += 1
                return a.id
        return None

    def fail(self, check, key, example=None):
        k = (check, json.dumps(key, sort_keys=True, default=str))
        if k in self.fails:
            self.fails[k][1] += 1
        else:
            self.fails[k] = [dict(key, **({"example": example} if example else {})), 1]

    def console(self, check, entries, base, allow_extra=None):
        """Judges console entries: error/pageerror only, each explained by allow_extra(entry) or an ALLOW entry."""
        for c in entries or []:
            if not is_error(c):
                continue
            f = dict(base, status=http_status(c), message=norm_message(c), stack=stack_frames(c))
            if allow_extra and allow_extra(c, f):
                continue
            if not self.allowed(check, **f):
                self.fail(check, {k: f.get(k) for k in ("role", "ui", "page", "label", "target", "status", "message")},
                          {"entry": c[:400]})

    def lines(self):
        out = []
        for (check, _), (obj, n) in sorted(self.fails.items()):
            # the key (what is judged) is never cut; only the example is (CI run 40080830269 cut an RS-7 line at 600)
            key = {k: v for k, v in obj.items() if k != "example"}
            ex = json.dumps(obj["example"], default=str)[:300] if "example" in obj else None
            out.append(f"FAIL {self.driver} {check} " + json.dumps(dict(key, rows=n), default=str)
                       + (f" example={ex}" if ex else ""))
        for a in ALLOW:
            if self.seen.get(a.id):
                out.append(f"ALLOW {a.id} matched {self.seen[a.id]}")
            elif a.applies(self.driver, self.ctx):
                out.append(f"STALE allow {a.id} {a.cls} {a.source}")
        return out


def _st(v):
    return v[0] if isinstance(v, (list, tuple)) and v else v


# ------------------------------------------------------------------ crawl
CORE_PARAMS_DIALOG = ("Direct Build (needs approval)", "Build with Parameters")
# Core's transient side-panel widgets (CI run 40175641627: manager, new UI, /job/ops/job/mb/batch-control-activation/,
# the menu chevron of the Build Queue's "batch-cron" entry, "Started by timer, Waiting for next available executor",
# gone on the fresh load where the pane read "No builds in the queue."). r14/crawl.py records the id of the enclosing
# core pane as "widget" only for #side-panel div.pane-frame#buildQueue (core lib/hudson/queue.jelly) and
# div.pane-frame#executors (lib/hudson/executors.jelly); their entries come and go with every queued or running build
# (batch-cron and fast run every minute). Keyed exactly: that pane, the side region, an anchor or chevron button, and a
# target core puts there (a job or build, a node, or the root for a pending executor); only "gone" or "not visible" on
# the fresh load. A Batch Control control is never inside these panes; one that disappears still FAILS.
TRANSIENT_WIDGETS = ("buildQueue", "executors")
TRANSIENT_TARGET = re.compile(r"(/job/[^/]+)+/(\{n\}/)?|/computer/[^/]+/|/")


def transient_widget_entry(r, res, target):
    return (res in ("gone-on-reload", "not-visible-on-reload") and r.get("widget") in TRANSIENT_WIDGETS
            and r.get("region") == "side" and r.get("tag") in ("a", "button")
            and bool(TRANSIENT_TARGET.fullmatch(target or "")))


def judge_crawl(rows, ctx):
    j = Judge("crawl", ctx)
    ids, prefix, profile = ctx.get("ids") or {}, ctx.get("prefix", ""), ctx.get("profile", "matrix")
    for r in rows:
        role, ui = r.get("role"), r.get("ui")
        page = page_key(r.get("page"), ids, prefix)
        base = {"role": role, "ui": ui, "profile": profile, "page": page}
        kind, res = r.get("kind"), r.get("result")
        if kind == "page":
            if res == "load-error":
                j.fail("page-load", base, {"detail": r.get("detail")})
                continue
            want, rid = expected_page_status(profile, role, page)
            if r.get("status") != want:
                j.fail("page-status", dict(base, status=r.get("status"), expected=want, rule=rid))
            j.console("page-console", r.get("console"), base)
        elif kind in ("link", "post-form"):
            target = page_key(r.get("target") or "", ids, prefix)
            if res == "BROKEN":
                st = r.get("status") if kind == "link" else r.get("status_get")
                if not j.allowed(kind, **base, target=target, label=r.get("label"), status=st):
                    j.fail(kind, dict(base, target=target, label=r.get("label"), status=st))
            elif res == "DEAD-HREF":
                j.fail("dead-href", dict(base, label=r.get("label"), cls=r.get("cls")))
            elif res == "help-button" and r.get("status") != 200:
                j.fail("help-button", dict(base, target=target, status=r.get("status")))
        elif kind == "click":
            d = r.get("detail") or {}
            target = page_key((r.get("data") or {}).get("href") or r.get("href") or "", ids, prefix)
            cbase = dict(base, label=r.get("label"), target=target)
            rotated = res == "gone-on-reload" and re.search(r"/\{n\}/$", target)  # a build entry that left the history
            if res in ("click-failed", "NOTHING", "not-visible-on-reload", "gone-on-reload") and not rotated \
                    and not transient_widget_entry(r, res, target):
                j.fail("click", dict(cbase, result=res), {"err": d.get("err"), "tag": r.get("tag"), "region": r.get("region")})
            j.console("click-console", d.get("console"), cbase)
            if res == "paged":
                # core's build-history pager (crawl.py PAGER_JS): "paged" is only recorded for that markup, and only when
                # the list showed a strictly older page; "Newer builds", enabled there, must bring the newer page back
                if d.get("pager") not in ("down", "up") or not d.get("history_after"):
                    j.fail("pager", dict(cbase, problem="paged without core's pager markup or a new history page"))
                elif (d.get("newer") or {}).get("result") not in (None, "paged"):
                    j.fail("pager", dict(cbase, problem="Newer builds on the older page: " + d["newer"]["result"]),
                           {"err": d["newer"].get("err")})
            if res == "dialog":
                problems = []
                if d.get("escape_closes") is not True:
                    problems.append("Escape does not close it")
                if d.get("url_unchanged") is False:
                    problems.append("opening it changed the URL")
                if "cancel_button" in d and "close_button" in d:  # the reopen for the Cancel step worked
                    if not d["cancel_button"] and not d["close_button"]:
                        problems.append("no Cancel and no close button")
                    elif not d["cancel_button"] and r.get("label") not in CORE_PARAMS_DIALOG:
                        problems.append("no Cancel button (only core's parameters dialog has just the close button)")
                    if d.get("cancel_closes") is False:
                        problems.append("Cancel/close does not close it")
                elif d.get("escape_closes"):
                    problems.append("did not reopen for the Cancel step")
                if problems:
                    j.fail("dialog", dict(cbase, problems=problems))
    return j


# ------------------------------------------------------------------ actions
def _state_rule(label, item):
    for rx, it, page_rx, srv_rx in ACTION_STATE:
        if re.fullmatch(rx, label or "") and (it is None or item in it):
            return page_rx, srv_rx
    return None, None


def _state_word(x):
    return re.sub(r",.*", "", x or "").strip()


def judge_actions(rows, ctx=None):
    j = Judge("actions", ctx)
    for r in rows:
        role, item = r.get("role"), r.get("item")
        if "probe_status" in r:
            want = ACTION_PROBES.get(role, {}).get(item)
            if want is None:
                j.fail("probe", {"role": role, "item": item, "why": "no expectation for this role and item"})
            elif (r["probe_status"], r.get("controls")) != want:
                j.fail("probe", {"role": role, "item": item, "status": r["probe_status"], "controls": r.get("controls"),
                                 "expected": list(want)})
            continue
        base = {"role": role, "item": item, "control": r.get("control")}
        if r.get("exception"):
            j.fail("control", dict(base, exception=r["exception"][:160]))
            continue
        posts = r.get("post_status")
        if not posts or any(s >= 400 for s in posts):
            j.fail("control-status", dict(base, post_status=posts))
        if r.get("ctype") == "confirm":
            if r.get("cancel_closed") is not True or r.get("escape_closed") is not True:
                j.fail("confirm-dialog", dict(base, cancel_closed=r.get("cancel_closed"), escape_closed=r.get("escape_closed")))
            for k in ("after_cancel_server", "after_escape_server"):
                # the state word(s) before a comma: "Open, 14 min left" may read "13 min left" a minute later
                if _state_word(_st((r.get(k) or [None, None])[1:])) != _state_word(r.get("state_before")):
                    j.fail("confirm-dialog", dict(base, step=k, state_before=r.get("state_before"), server=r.get(k)))
        page_rx, srv_rx = _state_rule(r.get("control"), item)
        if page_rx is None:
            j.fail("control", dict(base, why="no expected state for this control"))
        else:
            page_rx, srv_rx = page_rx.replace("{role}", re.escape(role or "")), srv_rx.replace("{role}", re.escape(role or ""))
            if not re.fullmatch(page_rx, r.get("state_after_page") or ""):
                j.fail("state-page", dict(base, state=r.get("state_after_page"), expected=page_rx))
            srv = r.get("server_after") or [None, None]
            if srv[0] != 200 or not re.fullmatch(srv_rx, srv[1] or ""):
                j.fail("state-server", dict(base, server=srv, expected=srv_rx))
        j.console("console", r.get("console"), {"role": role, "page": "/batch-control/" + (item or "") + "/", "label": r.get("control")})
    return j


# ------------------------------------------------------------------ jobui
DETAIL = re.compile(r"/batch-control/(requests|grants|activations)/" + UUID.pattern + "/")
NO_REQUEST = {"nobc"}  # Build without BatchControl/Request: D-60, the refusal stays in the dialog (J-07)


def judge_jobui(rows, ctx):
    j = Judge("jobui", ctx)
    prefix = ctx.get("prefix", "")
    for r in rows:
        role, ui = r.get("role"), r.get("ui")
        page = page_key(r.get("page"), None, prefix)
        base = {"role": role, "ui": ui, "page": page}
        if r.get("kind") == "page":
            j.fail("page-status", dict(base, status=r.get("status")))
            continue
        if r.get("kind") != "entry":
            continue
        label, where, res = r.get("label"), r.get("where"), r.get("result")
        ebase = dict(base, label=label, where=where)
        if r.get("exception"):
            j.fail("exception", dict(ebase, exception=r["exception"][:160]))
        if res == "NOTHING":
            j.fail("nothing", ebase)
        elif res == "notified":
            for t in r.get("notifications") or [""]:
                if not j.allowed("notified", **ebase, message=t.strip()):
                    j.fail("notified", dict(ebase, message=t.strip()))
        elif res == "navigated":
            nav = r.get("nav") or [None, ""]
            if (nav[0] or 0) >= 400:
                tgt = page_key(nav[1], None, prefix)
                if not j.allowed("nav", **ebase, target=tgt, status=nav[0]):
                    j.fail("nav", dict(ebase, target=tgt, status=nav[0]))
            for text, url, st in r.get("links") or []:
                if st >= 400:
                    j.fail("landing-link", dict(ebase, link=text, target=page_key(url, None, prefix), status=st))
        elif res == "dialog":
            problems = []
            if r.get("reopen_failed"):
                problems.append("did not reopen")
            if r.get("escape_closes") is not True:
                problems.append("Escape does not close it")
            if r.get("close_x_closes") is not True:
                problems.append(f"close (X): {r.get('close_x_closes')}")
            if r.get("url_unchanged") is False:
                problems.append("opening it changed the URL")
            if r.get("request_form"):
                if not r.get("has_cancel") or r.get("cancel_closes") is not True:
                    problems.append("no working Cancel")
                if r.get("empty_submit_stays") is not True or not r.get("empty_submit_errors"):
                    problems.append("an empty submit does not stay with field errors")
                if "enter_in_text" in r and not (r["enter_in_text"] or {}).get("dialog_open"):
                    problems.append("Enter in a text field left the dialog")
                if not DETAIL.fullmatch(r.get("submit_landing") or "") or r.get("server_detail_status") != 200:
                    problems.append(f"the complete submit landed on {r.get('submit_landing')} ({r.get('server_detail_status')})")
                if r.get("submit_stuck"):
                    problems.append("the complete submit stayed in the dialog")
            elif r.get("request_form") is False:
                ab = r.get("after_build")
                if not ab:
                    problems.append("core's parameters dialog has no Build button")
                elif role in NO_REQUEST:
                    if not ab.get("dialog_open"):
                        problems.append("Build closed the dialog for a user who may not request (D-60: the refusal stays)")
                elif not any(re.search(r"/batch-control/\?p\.", x or "") for x in (ab.get("url"), (ab.get("nav") or [None, ""])[1])):
                    problems.append(f"Build did not lead to the prefilled Request Run form: {ab.get('url')} {ab.get('nav')}")
            if problems:
                j.fail("dialog", dict(ebase, problems=problems))
        # J-01 (provoked): in a request form, the empty submit and the Enter step each answer 400 (SPEC 6 line 308)
        provoked = [1 + (1 if "enter_in_text" in r else 0)] if r.get("request_form") else [0]

        def j01(c, f, budget=provoked):
            if budget[0] > 0 and f["status"] == 400 and f["message"] == R400:
                budget[0] -= 1
                return True
            return False
        j.console("console", r.get("console"), ebase, j01)
    return j


# ------------------------------------------------------------------ misc
def _bad_links(links):
    return [x for x in links or [] if (x[-1] or 0) >= 400]


def _plain(label):
    return re.sub(r"\s*\n.*", "", label or "", flags=re.S).strip()


def judge_misc(rows, ctx=None):
    j = Judge("misc", ctx)
    for r in rows:
        sec, role = r.get("sec"), r.get("role")
        if "EXCEPTION" in r:
            continue  # ci/shard.py fails the step on the "EXCEPTION": row already
        cons = lambda page: j.console("console", r.get("console"), {"role": role or "", "page": page})  # noqa: E731
        if sec == "C":
            for what, k in (("page", "page_status"), ("configSubmit", "configSubmit_post")):
                want = MISC_STATUS[("C", what)].get(role)
                if r.get(k) != want:
                    j.fail("C-status", {"role": role, "what": what, "status": r.get(k), "expected": want})
            if role == "admin" and not r.get("link_visible"):
                j.fail("C-link", {"role": role, "why": "no link to the configuration page on /manage/"})
            if r.get("page_status") == 200:
                if r.get("save_status") != 200 or r.get("after_save_value") != "2":
                    j.fail("C-save", {"role": role, "status": r.get("save_status"), "value": r.get("after_save_value")})
                if r.get("refused_status") != 400 or r.get("after_refused_value") != r.get("after_save_value"):
                    j.fail("C-refused", {"role": role, "status": r.get("refused_status"), "value": r.get("after_refused_value")})
                if not r.get("inline_validation"):
                    j.fail("C-refused", {"role": role, "why": "no inline validation message for -5"})
                cons("/manage/batch-control-configuration/")
        elif sec == "H" and "role" in r:
            want = MISC_STATUS[("H", "history")].get(role)
            if r.get("status") != want:
                j.fail("H-status", {"role": role, "status": r.get("status"), "expected": want})
            if r.get("status") != 200:
                continue
            for k, v in (r.get("kinds") or {}).items():
                if v == "missing" or f"kind={k.lower()}" not in v[0] or v[2] != k:
                    j.fail("H-kind", {"role": role, "kind": k, "landing": v})
            fj = r.get("filter_job_fast") or {}
            if not fj.get("rows") or any(not re.match(r"fast\b", x) for x in fj.get("first_col") or []):
                j.fail("H-filter", {"role": role, "filter_job_fast": fj})
            if len([t for t in (r.get("bad_dates") or {}).get("text", []) if "is not a valid date" in t]) < 2:
                j.fail("H-dates", {"role": role, "bad_dates": r.get("bad_dates")})
            dls = r.get("downloads") or []
            if not dls or any(d[2] != 200 or not re.match(r"(text/csv|application/json)", d[3]) for d in dls):
                j.fail("H-downloads", {"role": role, "downloads": [d[:4] for d in dls]})
            if not (r.get("monthly") or {}).get("h1", "").startswith("Monthly Summary"):
                j.fail("H-monthly", {"role": role, "monthly": r.get("monthly")})
            cons("/batch-control/history/")
        elif sec == "P":
            if r.get("next_links"):
                nx, pv = r.get("next") or {}, r.get("prev")
                # the run history is newest first and gains a row with every timer run (batch-cron and fast run every
                # minute) while Next and Previous are walked: Previous is right when it brings back page 1 with its
                # first row moved down only by rows not older than it (the runs that finished meanwhile; a broken Previous
                # shows page 2, where page 1's first row is not, or older rows). Only those lists; every other paged
                # list must restore its first row exactly.
                ok = isinstance(pv, dict) and (_shift_ok(pv) if r.get("page") in GROWING_LISTS
                                               else bool(pv.get("first_row_restored")))
                if not nx.get("first_row_changed") or not ok:
                    j.fail("P-paging", {"page": r.get("page"), "next": nx, "prev": pv})
        elif sec == "B":
            want = MISC_STATUS[("B", r.get("page"))].get(role)
            if r.get("status") != want:
                j.fail("B-status", {"role": role, "page": r.get("page"), "status": r.get("status"), "expected": want})
            if r.get("status") != 200:
                continue
            bad = [x for x in (r.get("crumb_status") or []) + (r.get("menu_status") or []) if x[1] != 200]
            if bad:
                j.fail("B-links", {"role": role, "page": r.get("page"), "bad": bad})
            if r.get("page", "").startswith("/batch-control/"):
                menu = [_plain(t) for t, _ in r.get("menu") or []]
                if menu != MISC_MENU.get(role):
                    j.fail("B-menu", {"role": role, "page": r.get("page"), "menu": menu, "expected": MISC_MENU.get(role)})
            cons(r.get("page"))
        elif sec == "T":
            bad = [x for x in r.get("tab_status") or [] if x[1] != 200]
            if bad or not r.get("tab_status"):
                j.fail("T-tabs", {"role": role, "bad": bad})
        elif sec == "R" and "job" in r:
            want = MISC_STATUS[("R", "post_build")].get(role)
            if _st(r.get("post_build")) != want:
                j.fail("R-post", {"role": role, "ui": r.get("ui"), "job": r.get("job"), "status": _st(r.get("post_build")), "expected": want})
            ents = r.get("build_entries")
            if (role == "reqonly") != (not ents):
                j.fail("R-entries", {"role": role, "ui": r.get("ui"), "job": r.get("job"), "build_entries": ents})
            if _bad_links(r.get("links")):
                j.fail("R-links", {"role": role, "ui": r.get("ui"), "job": r.get("job"), "bad": _bad_links(r.get("links"))})
            if role == "nobc" and r.get("ui") == "new" and ents and not (r.get("after") or {}).get("dialog_open"):
                j.fail("R-refusal", {"role": role, "ui": "new", "job": r.get("job"), "why": "the refused build closed the dialog (D-60)"})
        elif sec == "R" and r.get("case") == "grant required (delete)":
            if not r.get("delete_entry") or r.get("still_exists") != 200 or _bad_links(r.get("links")):
                j.fail("R-delete", {k: r.get(k) for k in ("delete_entry", "still_exists", "status", "url")} | {"bad": _bad_links(r.get("links"))})
        elif sec == "R" and r.get("case") == "move refusal":
            ok = (r.get("move_page") == 200 and any((d or "").endswith("team") for d in r.get("destinations") or [])
                  and r.get("status") == 403 and r.get("still_in_prod") == 200 and not _bad_links(r.get("links")))
            if not ok:
                j.fail("R-move", {k: r.get(k) for k in ("window", "move_page", "destinations", "status", "url", "still_in_prod")}
                       | {"bad": _bad_links(r.get("links"))})
        elif sec == "S":
            n = r.get("mark_reviewed_links") or 0
            if n and (r.get("after_cancel_links") != n or r.get("after_ok_links") != n - 1 or _bad_links(r.get("mark_links"))):
                j.fail("S-mark", {k: r.get(k) for k in ("mark_reviewed_links", "after_cancel_links", "after_ok_links", "mark_links")})
            if (r.get("after_revert_cancel") != "BatchControlMatrixAuthorizationStrategy"
                    or "BatchControl" in (r.get("after_revert_ok") or "BatchControl")):
                j.fail("S-revert", {k: r.get(k) for k in ("revert_link", "after_revert_cancel", "after_revert_ok")})
            if not r.get("install_controls") or r.get("after_install") != "BatchControlMatrixAuthorizationStrategy":
                j.fail("S-install", {k: r.get(k) for k in ("install_controls", "install_dialog", "after_install")})
            if r.get("requester_build_after") != 200:
                j.fail("S-install", {"requester_build_after": r.get("requester_build_after")})
            cons("/manage/")
        elif sec == "M" and "role" in r:
            want = MISC_STATUS[("M", "page")].get(role)
            if r.get("status") != want:
                j.fail("M-status", {"role": role, "status": r.get("status"), "expected": want})
            if r.get("status") == 200:
                wb = MISC_STATUS[("M", "mark_button")].get(role)
                if r.get("mark_button") != wb:
                    j.fail("M-button", {"role": role, "mark_button": r.get("mark_button"), "expected": wb})
                if r.get("mark_button") and (r.get("mark_status") not in (200, "no response (same-document)")
                                             or "reviewed=1" not in (r.get("mark_landing") or "")):
                    j.fail("M-mark", {"role": role, "status": r.get("mark_status"), "landing": r.get("mark_landing")})
        elif sec == "M" and "request_again_buttons" in r:
            pf = r.get("prefill") or {}
            if (not r.get("request_again_buttons") or r.get("dialog") != 1 or pf.get("scope_type_selector") != 0
                    or not re.fullmatch(r"/batch-control/grants/" + UUID.pattern + "/", r.get("landing") or "")):
                j.fail("M-again", {k: r.get(k) for k in ("request_again_buttons", "dialog", "landing", "stuck")} | {"prefill": pf})
        elif sec == "K":
            if r.get("status") != 200 or r.get("invisible"):
                j.fail("K-dark", {"page": r.get("page"), "status": r.get("status"), "invisible": r.get("invisible")})
            j.console("console", r.get("console"), {"role": "admin", "page": page_key(r.get("page"))})
    return j


# ------------------------------------------------------------------ targeted (errpages, listpager, incident, help, monitor)
def incident_m4(r):
    """M4 (gap audit 2026-10, SPEC 11): the incident's state machine through its browser forms, as s_incident.py records
    it for one role. Returns {field: value} of what is wrong ({} when all holds): before anything is pressed the Status
    row says Open, the page offers Acknowledge and Add Comment but no Resolve, and the store says OPEN; Add Comment
    answers 200, keeps the status and adds a History row by the role with the comment; Acknowledge answers 200, the
    Status row says Acknowledged, Acknowledge is gone and Resolve offered, the store says ACKNOWLEDGED; Resolve answers
    200, the store says RESOLVED and the History ends with the Acknowledged and Resolved rows by the role with their
    comments."""
    role, comment = r.get("role"), f"comment by {r.get('role')} e2e-12"
    before, after_ack = r.get("forms_before") or [], r.get("after_ack_forms") or []
    hist_c, hist_r = r.get("history_after_comment") or [], r.get("history_after_resolve") or []
    want = {
        "state_before": r.get("state_before") == "Open",
        "forms_before": "acknowledge" in before and "addComment" in before and "resolve" not in before,
        "stored_before": r.get("stored_before") == "OPEN",
        "comment_status": r.get("comment_status") == 200,
        "state_after_comment": r.get("state_after_comment") == "Open",
        "history_after_comment": any(len(h) == 3 and h[1] == role and h[2] == comment for h in hist_c),
        "ack_status": r.get("ack_status") == 200,
        "state_after_ack": r.get("state_after_ack") == "Acknowledged",
        "after_ack_forms": "acknowledge" not in after_ack and "resolve" in after_ack,
        "stored_after_ack": r.get("stored_after_ack") == "ACKNOWLEDGED",
        "resolve_status": r.get("resolve_status") == 200,
        "stored_after_resolve": r.get("stored_after_resolve") == "RESOLVED",
        "history_after_resolve": hist_r[-2:] == [["Acknowledged", role, "ack"], ["Resolved", role, "resolved e2e-12"]],
    }
    return {k: r.get(k) for k, ok in want.items() if not ok}


def judge_targeted(name, rows, ctx=None):
    j = Judge(name, ctx)
    for r in rows:
        if name == "errpages":
            forms_bad = [f for f in r.get("forms") or [] if f[3] != 405]
            if r.get("broken") or forms_bad or not r.get("errors"):
                j.fail("page", {"case": r.get("case"), "broken": r.get("broken"), "forms_not_405": forms_bad, "errors": r.get("errors")})
            j.console("console", r.get("console"), {"page": page_key(r.get("url")), "label": r.get("case")})
        elif name == "listpager":
            if (r.get("rows_p1") != 50 or r.get("next") != 1 or r.get("prev") != 1 or r.get("back_same") is not True
                    or not (r.get("p2") or {}).get("rows")):
                j.fail("pager", {k: r.get(k) for k in ("role", "rows_p1", "next", "prev", "back_same", "p2")})
        elif name == "incident":
            if r.get("case") == "rerun validation":
                if r.get("status") != 400 or "Check at least one approver." not in (r.get("errors") or []):
                    j.fail("rerun-validation", {k: r.get(k) for k in ("status", "url", "errors")})
                # M4 (2026-10-31): the refusal is the incident's own page again (Status row, the rerun form offered
                # again) and links no rerun request. Rows recorded before M4 have no reruns_after and are not judged.
                if "reruns_after" in r and (r.get("state_after_refusal") != "Open" or "rerun" not in (r.get("forms_after_refusal") or [])
                                            or r.get("reruns_after") != r.get("reruns_before") or r.get("reruns_after") != []):
                    j.fail("rerun-refusal-state", {k: r.get(k) for k in ("state_after_refusal", "forms_after_refusal",
                                                                         "reruns_before", "reruns_after")})
            elif (r.get("comment_visible") is not True or "resolve" not in (r.get("after_ack_forms") or [])
                  or r.get("state_after_resolve") != "Resolved" or "resolve" in (r.get("forms_after_resolve") or ["resolve"])):
                j.fail("incident", {k: r.get(k) for k in ("role", "comment_visible", "after_ack_forms", "state_after_resolve",
                                                          "forms_after_resolve")})
            if r.get("case") != "rerun validation" and "state_before" in r:  # M4 (2026-10-31; rows before it: not judged)
                bad = incident_m4(r)
                if bad:
                    j.fail("incident-m4", {"role": r.get("role"), "id": r.get("id"), "wrong": bad})
        elif name == "help":
            for h in r.get("helps") or []:
                if h.get("status") != 200 or (h.get("chars") or 0) < 20 or h.get("expands") is not True:
                    j.fail("help", {"page": r.get("page"), "help": h})
            j.console("console", r.get("console"), {"page": r.get("page")})
        elif name == "help2":
            if not (r.get("after", 0) > r.get("before", 0)):
                j.fail("help-click", {k: r.get(k) for k in ("page", "help", "before", "after")})
        elif name == "monitor":
            want = {"plain": "GlobalMatrixAuthorizationStrategy", "after_cancel": "GlobalMatrixAuthorizationStrategy",
                    "after_ok": "BatchControlMatrixAuthorizationStrategy", "restored": r.get("before")}
            got = {k: r.get(k) for k in want}
            if got != want or not r.get("warned") or not r.get("dialog") or not r.get("install_gone"):
                j.fail("install", dict(got, expected=want, warned=r.get("warned"), dialog=r.get("dialog"),
                                       install_gone=r.get("install_gone")))
            n = r.get("mark_links") or 0
            if n and (r.get("after_mark_cancel") != n or r.get("after_mark_ok") != n - 1
                      or [x for x in r.get("mark_links_on_page") or [] if x[1] >= 400]):
                j.fail("mark-reviewed", {k: r.get(k) for k in ("mark_links", "after_mark_cancel", "after_mark_ok", "mark_links_on_page")})
            j.console("console", r.get("console"), {"page": "/manage/"})
    return j


# ------------------------------------------------------------------ role (r14/role)
ASSIGN_TABLE = {
    "0 before": (403, 403, 403), "1 global assigned": (200, 404, 404),
    "2 item roles e2e13-team+team-all assigned": (200, 200, 200), "3 e2e13-team unassigned (team-all kept)": (200, 200, 403),
    "4 item entry removed": (200, 404, 404), "5 global entry removed": (403, 403, 403)}
OVERLAY_TABLE = {"0 before": 403, "1 requested (pending)": 403, "2 approved (window open)": 200,
                 "3 after a Manage Roles save during the window": 200, "4 revoked": 403}
RS7 = {}
for _ep in ("checkPattern", "checkSidName"):
    RS7.update({f"{_ep} GET admin": 405, f"{_ep} GET requester": 405, f"{_ep} GET approver-1": 405, f"{_ep} GET nobc": 403,
                f"{_ep} POST requester": [403, "requester is missing the Overall/Administer permission"],
                f"{_ep} POST approver-1": [403, "approver-1 is missing the Overall/Administer permission"],
                f"{_ep} POST nobc": [403, ""], f"{_ep} POST anonymous": 403, f"{_ep} GET anonymous": 403})
RS7.update({"dropped checkName POST admin": 404, "dropped checkForWhitespace POST admin": 404})
for _u in ("requester", "approver-1"):
    for _p in ("/manage/role-strategy/", "/manage/role-strategy/manage-roles"):
        RS7[f"page {_p} GET {_u}"] = 403
RS_STRATEGY = "BatchControlRoleBasedAuthorizationStrategy"


def judge_role(name, res, ctx=None):
    """res: the one dict the role script logs. Tables from e2e-13/14 RS (SPEC D-35f/D-35g, D-35a)."""
    # the script passes its dict as built (tuples); the log and the tables hold JSON (lists): compare JSON with JSON
    # (CI run 40080830269: RS-7 "differed" only by tuple vs list)
    res = json.loads(json.dumps(res, default=str))
    j = Judge("role", ctx)
    bad_req = lambda: [x for x in res.get("requests") or [] if int(x.split()[0]) >= 400]  # noqa: E731
    if name == "grant_overlay":
        seq = {t: res.get(f"{t}: requester configure") for t in OVERLAY_TABLE}
        strat = {res.get(f"{t}: strategy") for t in OVERLAY_TABLE}
        if seq != OVERLAY_TABLE or strat != {RS_STRATEGY} or res.get("browser configure after revoke") != 403:
            j.fail("overlay", {"sequence": seq, "expected": OVERLAY_TABLE, "strategy": sorted(map(str, strat)),
                               "browser configure after revoke": res.get("browser configure after revoke")})
        if (_st(res.get("request submit")) != 200 or res.get("browser configure GET") != 200
                or _st(res.get("browser configure save")) != 200 or not res.get("description saved")):
            j.fail("overlay-flow", {k: res.get(k) for k in ("request submit", "browser configure GET", "browser configure save",
                                                            "description saved")})
        if res.get("role edit bad"):
            j.fail("overlay-flow", {"role edit bad": res.get("role edit bad")})
        for k in ("request console", "approver console", "role edit console", "admin console"):
            j.console("console", res.get(k), {"role": k.split()[0], "page": "/" + k})
    elif name == "assign_roles":
        got = {t: (res.get(f"{t}: nobc GET /"), res.get(f"{t}: nobc GET team/app-1"), res.get(f"{t}: nobc GET team/app-1/configure"))
               for t in ASSIGN_TABLE}
        strat = {res.get(f"{t}: strategy") for t in ASSIGN_TABLE}
        if got != ASSIGN_TABLE or strat != {RS_STRATEGY} or res.get("nobc rows after reload") != 0:
            j.fail("assign", {"table": got, "expected": ASSIGN_TABLE, "strategy": sorted(map(str, strat)),
                              "rows after reload": res.get("nobc rows after reload")})
        if res.get("assign GET") != 200 or bad_req() or res.get("bad"):
            j.fail("assign-requests", {"assign GET": res.get("assign GET"), "bad requests": bad_req(), "bad": res.get("bad")})
        j.console("console", res.get("console"), {"role": "admin", "page": "/manage/role-strategy/"})
    elif name == "manage_roles":
        ok = (res.get("manage-roles GET") == 200 and res.get("global role listed") == 1 and res.get("item role listed") == 1
              and res.get("item role after reload") == 1 and res.get("add enabled with invalid pattern") is False
              and "team(/.*)?" in (res.get("server item role") or "") and res.get("strategy") == RS_STRATEGY)
        if not ok or bad_req() or res.get("bad"):
            j.fail("manage", {k: res.get(k) for k in ("manage-roles GET", "global role listed", "item role listed",
                                                      "item role after reload", "add enabled with invalid pattern",
                                                      "server item role", "strategy", "bad")} | {"bad requests": bad_req()})
        j.console("console", res.get("console"), {"role": "admin", "page": "/manage/role-strategy/manage-roles"})
    elif name == "endpoints":
        rs6 = res.get("RS-6 admin forwarded == role-strategy") or {}
        bad6 = {k: v[:2] for k, v in rs6.items() if v[0] != 200 or v[1] is not True}
        if not rs6 or bad6:
            j.fail("RS-6", {"not identical": bad6, "entries": len(rs6)})
        got7 = res.get("RS-7 refusals") or {}
        diff = {k: [got7.get(k), v] for k, v in RS7.items() if got7.get(k) != v}
        diff.update({k: [v, None] for k, v in got7.items() if k not in RS7})
        if diff:
            j.fail("RS-7", {"differs": [{"key": k, "got": g, "expected": e} for k, (g, e) in sorted(diff.items())]})
        if res.get("strategy") != RS_STRATEGY:
            j.fail("RS-7", {"strategy": res.get("strategy")})
    return j


# ------------------------------------------------------------------ r15
# r15/check.py's duplicate check (side panel, app bar). Core's side-panel widgets list one entry per build, queue item or
# executor, so their texts repeat by design (CI run 40504779419: "Success" twice, the status badges of builds #1 and #2
# in the build history): the build history card (hudson/widgets/HistoryWidget/index.jelly: l:card#jenkins-builds around
# div#jenkins-build-history), the Build Queue pane (lib/hudson/queue.jelly, l:pane id="buildQueue") and the Build
# Executor Status pane (lib/hudson/executors.jelly, div.pane-frame#executors). check.py's COLLECT records the id of the
# enclosing one as "widget" (DOM containment, not the text); only core's markup is rendered there.
R15_REPEATING_WIDGETS = frozenset({"jenkins-builds", "jenkins-build-history", "buildQueue", "executors"})


def r15_duplicates(els, regions=("side", "appbar")):
    """The duplicate problems of a page's collected elements: a visible text that appears more than once in a region,
    counting only entries outside core's repeating widgets (task links of core and of the plugin, the app bar).
    Returns (problems, widget_duplicates); the second lists the repeats inside the widgets, for the record only."""
    problems, inside = [], []
    for reg in regions:
        seen, seen_w = {}, {}
        for e in els:
            if e.get("region") == reg and e.get("visible") and e.get("text"):
                if e.get("widget") in R15_REPEATING_WIDGETS:
                    k = (e["widget"], e["text"])
                    seen_w[k] = seen_w.get(k, 0) + 1
                else:
                    seen[e["text"]] = seen.get(e["text"], 0) + 1
        problems += [{"kind": "duplicate", "region": reg, "text": t, "count": n} for t, n in seen.items() if n > 1]
        inside += [{"region": reg, "widget": w, "text": t, "count": n} for (w, t), n in seen_w.items() if n > 1]
    return problems, inside


# r15/check.py's GET of every same-origin href: core's stop buttons in those widgets (lib/layout/stopButton.jelly,
# a.stop-button-link, posted by core's JS after its confirmation) answer 405 to a GET (CI run 40504779419: the
# executors pane's "Cancel" of a running batch-cron build). Each pane's stop URL, relative to the Jenkins root:
R15_CORE_STOP = (
    ("buildQueue", re.compile(r"/queue/cancelItem\?id=\d+")),  # lib/hudson/queue.jelly
    ("executors", re.compile(r"/computer/[^/?]+/(executors|oneOffExecutors)/\d+/stopBuild\?runExtId=[^&]*")),  # executors.jelly
    ("jenkins-build-history", re.compile(r"(/job/[^/?]+)+/\d+/stop")),  # HistoryWidget/entry.jelly: ${link}stop
)


def r15_core_stop(e, path, status):
    """The known-core kind of a fetched link of r15/check.py, or None (then a status >= 400 stays a problem): core's stop
    button inside its own widget pane, on that pane's stop URL (full match of path + query), answering 405 to the GET;
    or a one-off executor's stopBuild answering 404 (a flyweight executor exists only while its build runs)."""
    if "stop-button-link" not in (e.get("cls") or "").split():
        return None
    for widget, rx in R15_CORE_STOP:
        if e.get("widget") == widget and rx.fullmatch(path or ""):
            if status == 405:
                return "known_core_get_405"
            if status == 404 and widget == "executors" and "/oneOffExecutors/" in path:
                return "known_core"
    return None


def judge_r15(name, rows, ctx=None):
    j = Judge("r15", ctx)
    for r in rows:
        if name == "side_dialog":
            prefill = dict((k, v) for k, v in (r.get("prefill") or []) if k == "scopeFullName")
            if (r.get("entries") != 1 or r.get("dialog") != 1 or r.get("url_unchanged") is not True
                    or prefill.get("scopeFullName") != "team-mb" or r.get("closed_by_escape") is not True or r.get("bad")):
                j.fail("side-dialog", {k: r.get(k) for k in ("flag", "entries", "dialog", "url_unchanged", "closed_by_escape", "bad")}
                       | {"prefill": prefill})
            j.console("console", r.get("console"), {"role": "requester", "page": "/job/team-mb/batch-control-activation/"})
        elif name == "folder_compare":
            if r.get("identical") is not True:
                j.fail("folder-compare", {"user": r.get("user"), "own": r.get("own"), "activation": r.get("activation")})
        elif name == "menu":
            base = {"role": r.get("user"), "page": r.get("page")}
            bad = {k: v for k, v in (r.get("status") or {}).items() if v != 200}
            clicks = r.get("clicks") or {}
            problems = []
            if r.get("items") is not None and not r.get("items"):
                problems.append("the menu shows no entries")
            if bad:
                problems.append({"status": bad})
            if r.get("duplicates") or r.get("empty_href_anchors"):
                problems.append({"duplicates": r.get("duplicates"), "empty_href_anchors": r.get("empty_href_anchors")})
            if "Request Change Permission" in clicks and clicks["Request Change Permission"].get("dialog") != 1:
                problems.append({"Request Change Permission": clicks["Request Change Permission"]})
            if "Permalinks" in clicks and not _permalinks_entries(clicks["Permalinks"]):
                problems.append({"Permalinks": clicks["Permalinks"]})
            if [b for b in r.get("bad") or [] if not b.startswith("404 GET ") or "/static/" not in b]:
                problems.append({"bad": r.get("bad")})
            if problems:
                j.fail("menu", dict(base, problems=problems))
            j.console("console", r.get("console"), base)
    if name == "menu":
        # e2e-15: the activation page's menu is the job's; its Permalinks submenu lists the same permalinks (by href:
        # "#2" in the text moves on when a build finishes)
        subs = {}
        for r in rows:
            got = _permalinks_entries(((r.get("clicks") or {}).get("Permalinks")) or {})
            if got and all(i.get("href") for i in got):
                subs[(r.get("user"), r.get("page"))] = sorted({i.get("href") or i.get("text") for i in got})
        for (user, page), hrefs in sorted(subs.items()):
            job = page[:-len("batch-control-activation/")] if page.endswith("/batch-control-activation/") else None
            if job and (user, job) in subs and subs[(user, job)] != hrefs:
                j.fail("menu", {"role": user, "page": page, "problems": [{"Permalinks differ from the job page's":
                                                                          {"page": hrefs, "job page": subs[(user, job)]}}]})
    return j


def _permalinks_entries(c):
    """The Permalinks submenu entries a menu.py row saw: core opens the submenu on hover, and a click on the entry whose
    submenu is already open may toggle it closed again (CI run 40183795439: the hover read 4 entries, the click left no
    menu), so either step showing them counts; a submenu that neither step showed (or that shows nothing) FAILS."""
    hover = (c.get("hover") or {}).get("items") or []
    if hover:
        return hover
    if c.get("submenu"):
        return c["submenu"]
    n = c.get("submenu_items") or 0  # rows of the driver before CI run 40183795439: a difference of counts
    return [{"text": f"entry {k + 1}"} for k in range(n)] if n > 0 else []


# ------------------------------------------------------------------ entry point for the drivers
def judge(driver, rows, ctx=None):
    ctx = dict(ctx or {})
    if driver == "crawl":
        return judge_crawl(rows, ctx)
    if driver == "actions":
        return judge_actions(rows, ctx)
    if driver == "jobui":
        return judge_jobui(rows, ctx)
    if driver == "misc":
        return judge_misc(rows, ctx)
    if driver in ("errpages", "listpager", "incident", "help", "help2", "monitor"):
        return judge_targeted(driver, rows, ctx)
    if driver.startswith("role/"):
        return judge_role(driver.split("/", 1)[1], rows[0] if rows else {}, ctx)
    if driver.startswith("r15/"):
        return judge_r15(driver.split("/", 1)[1], rows, ctx)
    raise ValueError(f"no H1 rules for driver {driver!r}")


def report(driver, rows, ctx=None, out=print):
    """Judges a driver's rows and prints the FAIL / ALLOW / STALE lines; returns the number of FAIL lines."""
    lines = judge(driver, rows, ctx).lines()
    for line in lines:
        out(line)
    n = sum(1 for x in lines if x.startswith("FAIL "))
    out(f"H1 {driver}: {len(rows)} rows judged, {n} FAIL line(s)")
    return n
