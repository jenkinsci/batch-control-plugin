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


def design_refs(j, r, base, ctx):
    """#31 (the retired r6/s6.py): no internal design reference ("SPEC item", "D-61", ...) on a page a user sees.
    r14/crawl.py's CONTENT_JS scans every page below 400 (and again behind Advanced) and puts the scan on the row as
    "design" = {texts, skipped, refs, type}. Each ref FAILS design-ref; a row without the scan, or an HTML document
    (type text/html, or no type) whose scan read no text, FAILS design-ref-measured (the measurement is required). A
    document of another type (the JSON of /batch-control/history/summary, shown by Chromium inside a <pre>) is data, not
    UI copy, and needs no scanned text. Only selftest_h1.py's replay of rows recorded before #31 passes ctx
    pre31_design_ref."""
    d = r.get("design")
    html = isinstance(d, dict) and (d.get("type") or "text/html") in ("text/html", "application/xhtml+xml")
    if not (ctx or {}).get("pre31_design_ref") and (not isinstance(d, dict) or (html and not d.get("texts"))):
        j.fail("design-ref-measured", dict(base, kind=r.get("kind"), design="missing" if d is None else "no text scanned",
                                           type=d.get("type") if isinstance(d, dict) else None))
    refs = (d.get("refs") or []) if isinstance(d, dict) else []
    for x in refs:
        j.fail("design-ref", dict(base, match=x.get("match")), x)


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
            if (r.get("status") or 0) < 400:  # #31: the crawl scans every page it does not leave at its 4xx status
                design_refs(j, r, base, ctx)
        elif kind == "advanced":
            design_refs(j, r, base, ctx)
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


# #31 E2E-D4: the global configuration pages helpcheck.py must measure (its LAYOUT_PAGES)
LAYOUT_PAGES = ("/manage/batch-control-configuration/", "/manage/configure")


def monitor_def04(j, r):
    """#31 DEF-04 (docs/reports/e2e-06.md): on both /manage/ visits the strategy monitor shows its arranged item; each
    "Mark as reviewed" sits on the last line of its item's text (vertical centres within 2 px) right after it (gap 0..24
    px, not floated to the right edge), the controls share one top (2 px), and the alert shows one icon outside its
    buttons ("Before you install" once showed a second one) and no button more than one."""
    if not (r.get("r31") or {}).get("listed"):
        j.fail("def04-premise", {"r31": r.get("r31")})
    for visit in ("layout_global", "layout_bc"):
        lay = r.get(visit)
        if not lay:
            j.fail("def04-monitor-shown", {"visit": visit, "layout": lay})
            continue
        if visit == "layout_global" and not lay.get("before_install"):
            j.fail("def04-before-install-shown", {"visit": visit})
        measured = [m for m in lay.get("marks") or [] if not m.get("nested") and m.get("line")]
        if not measured:
            j.fail("def04-mark-measured", {"visit": visit, "marks": lay.get("marks")})
        for m in measured:
            if m.get("dy") is None or m["dy"] > 2 or m.get("gap") is None or not 0 <= m["gap"] <= 24:
                j.fail("def04-mark-beside-item", {"visit": visit, "item": m.get("item")}, m)
        tops = [c["box"][1] for c in lay.get("controls") or []]
        if tops and max(tops) - min(tops) > 2:
            j.fail("def04-controls-top", {"visit": visit, "controls": lay.get("controls")})
        if lay.get("icons") != 1:
            j.fail("def04-alert-icons", {"visit": visit, "icons": lay.get("icons"), "classes": lay.get("icon_classes")})
        for b in lay.get("button_icons") or []:
            j.fail("def04-button-icons", {"visit": visit, "button": b})


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
            # #31 E2E-D4: a label never overlaps or runs into its description/help text on the global configuration
            # pages (helpcheck.py LAYOUT). Each page of LAYOUT_PAGES must carry a layout (checked after the rows); the
            # section must be there with its labels and at least one label/text pair measured, so an empty page is no pass.
            lay = r.get("layout")
            if lay is not None:
                if lay.get("sections", 0) < 1 or lay.get("labels", 0) < 10 or lay.get("pairs", 0) < 1:
                    j.fail("label-layout-premise", {"page": r.get("page"),
                                                    **{k: lay.get(k) for k in ("sections", "labels", "pairs")}})
                for x in lay.get("overlap") or []:
                    j.fail("label-description-overlap", {"page": r.get("page"), "label": x.get("label")}, x)
                for x in lay.get("runin") or []:
                    j.fail("label-description-same-line", {"page": r.get("page"), "label": x.get("label")}, x)
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
            monitor_def04(j, r)  # #31 DEF-04 (s_monitor.py MONITOR): required, a row without the measurement fails
            j.console("console", r.get("console"), {"page": "/manage/"})
    if name == "help" and not (ctx or {}).get("pre31_layout"):
        # #31 E2E-D4: the measurement is required; a driver change that drops it fails instead of passing silently.
        # Only selftest_h1.py's replay of rows recorded before #31 passes pre31_layout (and lists it as skipped).
        have = {r.get("page") for r in rows if r.get("layout") is not None}
        for page in LAYOUT_PAGES:
            if page not in have:
                j.fail("label-layout-premise", {"page": page, "layout": "missing"})
    if name == "monitor" and not rows:
        j.fail("def04-monitor-shown", {"visit": None, "layout": "no monitor row"})
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
# #31 (r6/s2b.py, r6/s2d.py): what manage_roles.py's second part edits, checks and removes in the browser
RS_EDIT = {"role": "e2e31-prod", "old": "prod/.*", "new": "prod(/.*)?", "perms": "[hudson.model.Item.Read]",
           "template": "e2e31-tpl", "template perms": "[hudson.model.Item.Read, hudson.model.Item.Workspace]"}
RS_MATCH_CAP = 15  # role-strategy 927's manage-roles bundle: getMatchingJobs?maxJobs=15


# #31 (B/b8-role.mjs #1): the Batch Control permissions role-strategy's Add Role dialogs must offer (SPEC 2: "Matrix and
# Role based strategies expose them"; D-38a: Request is assignable per item), and the item role manage_roles.py saves
BC_PERM = "io.jenkins.plugins.batchcontrol.security.BatchControlPermissions."
BC_PERMS = tuple(BC_PERM + p for p in ("Manage", "Request", "Approve", "RequestGrant", "ViewHistory"))
CORE_ITEM_READ = "hudson.model.Item.Read"
RS_PERMS_ROLE, RS_PERMS_PATTERN = "e2e31-bcreq", "e2e31-none/.*"
RS_PERMS_SAVED = (CORE_ITEM_READ, BC_PERM + "Request")


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
    elif name == "manage_edit":
        manage_edit(j, res, bad_req)
    elif name == "manage_perms":
        manage_perms(j, res, bad_req)
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


def manage_edit(j, res, bad_req):
    """#31 (the retired r6/s2b.py and r6/s2d.py; SPEC: "With the Batch Control role-strategy strategy installed, Manage
    Roles, item and agent roles ... work"), manage_roles.py's second part in the browser as admin. The endpoints are
    guarded on the server (T-02-88..92); these rules guard role-strategy's pages driving them under the Batch Control
    class:
      * role-edit: the card of the existing item role RS_EDIT["role"] (pattern RS_EDIT["old"]) opens its Edit role dialog,
        Save is enabled for RS_EDIT["new"] (the forwarded checkPattern accepts it) and closes the dialog; after a reload
        the card shows the new pattern and the server holds it with the permissions kept, the strategy still RS_STRATEGY;
      * role-pattern-check: the card's pattern button opens the "Items matching" dialog, titled with the pattern the card
        shows, which lists exactly the items whose full name that pattern matches on the server (script console,
        independent of role-strategy; at least one; beyond RS_MATCH_CAP matches, that many of them), and closes (judged
        on the shown pattern, so a failed edit does not fail this rule as well);
      * role-delete: Delete role opens a confirmation naming the role; Cancel closes it and keeps the role (unchanged on
        the server); OK closes it and removes the role (no card after a reload, none on the server), the strategy still
        RS_STRATEGY;
      * template-add: on Permission Templates the Add Template dialog creates RS_EDIT["template"] with
        RS_EDIT["template perms"] (server), listed after a reload, the strategy still RS_STRATEGY;
      * template-delete: the template card's Delete template, confirmed, removes it (not listed, none on the server),
        the strategy still RS_STRATEGY;
      * role-edit-measured: the arrangement (role-strategy's addRole, 200, the role on the server with the old pattern)
        held, the card was found, and the driver raised nothing;
      * role-edit-requests, console: no role-strategy request answered >= 400, no unexplained console error."""
    e = RS_EDIT
    if (res.get("arrange addRole") != 200 or res.get("server role before") != f"{e['old']} {e['perms']}"
            or (res.get("card before") or [0])[0] != 1 or res.get("error")):
        j.fail("role-edit-measured", {k: res.get(k) for k in ("arrange addRole", "server role before", "card before", "error")})
    if not (res.get("edit dialog") == f"Edit role: {e['role']}" and res.get("save enabled") is True
            and res.get("edit closed") is True and res.get("card after reload") == [1, e["new"]]
            and res.get("server role after edit") == f"{e['new']} {e['perms']}" and res.get("strategy after edit") == RS_STRATEGY):
        j.fail("role-edit", {k: res.get(k) for k in ("edit dialog", "save enabled", "pattern field", "edit closed", "card after reload",
                                                     "server role after edit", "strategy after edit")})
    pd, want = res.get("pattern dialog") or {}, res.get("server matching")
    shown = (res.get("card after reload") or [0, None])[1]
    listed = pd.get("items")
    # role-strategy asks for at most RS_MATCH_CAP items (maxJobs): beyond that the dialog lists that many of the matches
    full = (listed == want if isinstance(want, list) and len(want) <= RS_MATCH_CAP
            else isinstance(listed, list) and len(listed) == RS_MATCH_CAP and set(listed) <= set(want or []))
    if not (pd.get("opened") and shown and f'"{shown}"' in (pd.get("title") or "") and isinstance(want, list) and want
            and full and pd.get("closed") is True):
        j.fail("role-pattern-check", {"shown": shown, "dialog": {k: pd.get(k) for k in ("opened", "title", "closed")},
                                      "listed": pd.get("items"), "server": want})
    dd = res.get("delete dialog") or {}
    if not (dd.get("opened") and e["role"] in (dd.get("title") or "") and dd.get("closed on cancel") is True
            and dd.get("listed after cancel") == 1 and dd.get("server after cancel") not in (None, "none")
            and dd.get("server after cancel") == res.get("server role after edit")
            and dd.get("closed on ok") is True and res.get("listed after delete") == 0
            and res.get("server role after delete") == "none" and res.get("strategy after delete") == RS_STRATEGY):
        j.fail("role-delete", dict(dd, **{k: res.get(k) for k in ("listed after delete", "server role after delete",
                                                                 "strategy after delete")}))
    ta = res.get("template add dialog") or {}
    if not (ta.get("opened") and ta.get("closed") is True and res.get("template listed after reload") == 1
            and res.get("server template after add") == e["template perms"]
            and res.get("strategy after template add") == RS_STRATEGY):
        j.fail("template-add", dict(ta, **{k: res.get(k) for k in ("template listed after reload", "server template after add",
                                                                  "strategy after template add")}))
    td = res.get("template delete dialog") or {}
    if not (td.get("opened") and e["template"] in (td.get("title") or "") and td.get("closed") is True
            and res.get("template listed after delete") == 0 and res.get("server template after delete") == "none"
            and res.get("strategy after template delete") == RS_STRATEGY):
        j.fail("template-delete", dict(td, **{k: res.get(k) for k in ("template listed after delete",
                                                                     "server template after delete",
                                                                     "strategy after template delete")}))
    if bad_req() or res.get("bad") or res.get("template bad"):
        j.fail("role-edit-requests", {"bad requests": bad_req(), "bad": res.get("bad"), "template bad": res.get("template bad")})
    j.console("console", res.get("console"), {"role": "admin", "page": "/manage/role-strategy/manage-roles"})
    j.console("console", res.get("template console"), {"role": "admin", "page": "/manage/role-strategy/permission-templates"})


def manage_perms(j, res, bad_req):
    """#31 (the retired B/b8-role.mjs #1; SPEC 2: the five permissions are exposed to the Role based strategy, D-38a:
    Request also per item), manage_roles.py's third part as admin under the Batch Control role strategy:
      * role-bc-perms: the Add Role dialog of Global roles offers all five Batch Control permissions (BC_PERMS), and the
        one of Item roles offers BatchControl/Request;
      * role-bc-save: the item role RS_PERMS_ROLE (pattern RS_PERMS_PATTERN) saved through that dialog with
        BatchControl/Request and Job/Read ticked closes the dialog, is listed after a reload and holds exactly those
        two permissions on the server, the strategy still RS_STRATEGY;
      * role-bc-perms-measured: both dialogs opened and their permissions were read, core's Job/Read offered in each
        read (the reader works), the role removed again afterwards, nothing raised, no role-strategy request answered
        >= 400 and no unexplained console error."""
    glob, item = res.get("global offered"), res.get("item offered")
    measured = isinstance(glob, list) and isinstance(item, list) and CORE_ITEM_READ in glob and CORE_ITEM_READ in item
    if (not measured or not res.get("global dialog") or not res.get("item dialog") or res.get("error")
            or res.get("server role after remove") != "none" or bad_req() or res.get("bad")):
        j.fail("role-bc-perms-measured", {k: res.get(k) for k in ("global dialog", "item dialog", "error", "remove",
                                                                  "server role after remove", "bad")}
               | {"core read offered": [CORE_ITEM_READ in (glob or []), CORE_ITEM_READ in (item or [])], "bad requests": bad_req()})
    if measured:
        missing = [p.rsplit(".", 1)[1] for p in BC_PERMS if p not in glob]
        if missing or BC_PERM + "Request" not in item:
            j.fail("role-bc-perms", {"global missing": missing, "item offers Request": BC_PERM + "Request" in item},
                   {"global": [p for p in glob if p.startswith(BC_PERM)], "item": [p for p in item if p.startswith(BC_PERM)]})
    want = f"{RS_PERMS_PATTERN} [{', '.join(sorted(RS_PERMS_SAVED))}]"
    if not (res.get("item closed") is True and res.get("listed after reload") == 1 and res.get("server role") == want
            and res.get("strategy") == RS_STRATEGY):
        j.fail("role-bc-save", {k: res.get(k) for k in ("ticked", "item closed", "listed after reload", "server role", "strategy")}
               | {"expected": want})
    j.console("console", res.get("console"), {"role": "admin", "page": "/manage/role-strategy/manage-roles"})


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
# ------------------------------------------------------------------ r21/reject_color.py D (#31, r6/s5c.py, r6/s5a.py #5)
# The destructive controls section D measures: (where, control). Each must have a row (destructive-measured).
DESTRUCTIVE_CONTROLS = (("run request", "Cancel Request"), ("permission window request", "Cancel Request"),
                        ("activation request", "Cancel Request"), ("grants list", "Revoke"), ("window", "Revoke"),
                        ("configuration page", "Revert to the plain strategy"))


def _painted(button, probe, probe_n):
    """Whether a dialog button shows the probe's colour as its text, background or ::before background (core paints a
    button's background on ::before). Raw computed values are compared, and the canvas-normalised "r,g,b,a" of each
    (reject_color.py "n") with the probe's, so oklch(...) and rgb(...) of one colour match."""
    if not probe:
        return False
    keys = ("color", "bg", "before_bg")
    n = button.get("n") or {}
    return probe in [button.get(k) for k in keys] or (bool(probe_n) and probe_n in [n.get(k) for k in keys])


def judge_destructive(rows, ctx=None):
    """#31 (the retired r6/s5c.py; e2e-06 S5c "red destructive controls and dialogs"; r6/s5a.py #5): Cancel Request on
    the three request pages, Revoke on the grants list and on the window's page, and "Revert to the plain strategy" on
    the Batch Control configuration page (behind core's Advanced button, which the driver presses first) render in the
    theme's destructive colour and open core's destructive confirmation dialog. Colours are compared with a probe styled
    color: var(--destructive-color) on the same page (reject_color.py D):
      * destructive-measured: every DESTRUCTIVE_CONTROLS entry has a row, and its probe resolves to a colour of its own
        (it differs from the text around the control; an undefined variable would make every comparison vacuous);
      * destructive-button: the control ("button": a.confirmation-link styled as a button) is on the page, shown, and
        its computed colour is the probe's;
      * destructive-dialog: a click opens a dialog whose OK button is red (its computed colour, background or ::before
        background is the dialog's probe: core's .jenkins-button--primary[class*=color]) and whose Cancel button is
        not; Cancel closes it and nothing changed on the server (the request still pending, the window still
        conferring Configure, a Batch Control strategy still installed)."""
    j = Judge("destructive", ctx)
    seen = {(r.get("where"), r.get("control")) for r in rows}
    for where, control in DESTRUCTIVE_CONTROLS:
        if (where, control) not in seen:
            j.fail("destructive-measured", {"where": where, "control": control, "row": "missing"})
    for r in rows:
        key = {"where": r.get("where"), "control": r.get("control")}
        probe = r.get("probe")
        if not probe or probe == r.get("around"):
            j.fail("destructive-measured", dict(key, probe=probe, around=r.get("around")))
        ctl = r.get("button")
        if not ctl or not ctl.get("visible") or r.get("http") != 200:
            j.fail("destructive-button", dict(key, problem="no shown control", http=r.get("http"), count=r.get("count")))
        elif ctl.get("color") != probe:
            j.fail("destructive-button", dict(key, color=ctl.get("color"), probe=probe), {"classes": ctl.get("classes")})
        d = r.get("dialog")
        if not ctl:
            continue
        if not d:
            j.fail("destructive-dialog", dict(key, problem="the click opened no dialog"))
            continue
        ok, cancel, dprobe = d.get("ok") or {}, d.get("cancel") or {}, d.get("probe")
        problems = []
        if not ok.get("visible") or not _painted(ok, dprobe, d.get("probe_n")):
            problems.append("OK not in the destructive colour")
        if not cancel.get("visible") or _painted(cancel, dprobe, d.get("probe_n")):
            problems.append("no Cancel, or Cancel in the destructive colour")
        if r.get("closed_on_cancel") is not True:
            problems.append("Cancel did not close the dialog")
        if r.get("state_before") is not True or r.get("state_after") is not True:
            problems.append("the request is no longer pending, the window no longer confers or the Batch Control strategy "
                            "is no longer installed (or that never held)")
        if problems:
            j.fail("destructive-dialog", dict(key, problems=problems),
                   {"ok": ok, "cancel": cancel, "probe": dprobe, "state": [r.get("state_before"), r.get("state_after")]})
    return j


# ------------------------------------------------------------------ r19/gate.py L (#31, r8/rebuild.py)
REBUILD_HREF = re.compile(r"(^|/)(lastCompletedBuild|\d+)/rebuild(/|$)")


def judge_rebuild_menu(rows, ctx=None):
    """#31 (the retired r8/rebuild.py; e2e-07 DEF-06, e2e-08 item 3; SPEC 6 lines 114-117 and 121): the rebuild plugin's
    "Rebuild Last" in the new job page's "More actions" menu of an approval-required job with a completed build.
      * rebuild-menu-offered: the menu opens with its entries, Rebuild Last is one of them with the rebuild URL of a
        build (DEF-06: a null URL left the whole menu empty), and it opens the rebuild form (200, a rebuild URL);
      * rebuild-menu-refused: submitting that form is refused (4xx, the approval message), nothing is queued, no build
        starts, and a TRIGGER_BLOCKED record names the user and the re-run build "#<n>" as a Rebuild (D-51).
    The row is required: no row, or one without the menu, fails rebuild-menu-offered."""
    j = Judge("rebuild-menu", ctx)
    if not rows:
        j.fail("rebuild-menu-offered", {"row": "missing"})
    for r in rows:
        key = {"user": r.get("user"), "job": r.get("job")}
        menu = r.get("menu") or []
        entry = [m for m in menu if (m.get("text") or "") == "Rebuild Last"]
        href = (entry[0].get("href") or "") if entry else None
        if not r.get("build") or not menu or not entry or not REBUILD_HREF.search(href or ""):
            j.fail("rebuild-menu-offered", dict(key, build=r.get("build"), href=href, entries=[m.get("text") for m in menu][:12]),
                   {"console": r.get("menu_console")})
        elif r.get("entry_status") != 200 or not REBUILD_HREF.search(r.get("entry_url") or "") or not r.get("form"):
            j.fail("rebuild-menu-offered", dict(key, entry_status=r.get("entry_status"), entry_url=r.get("entry_url"),
                                                form=r.get("form")), {"error": r.get("entry_error")})
        text = (r.get("submit_text") or "").lower()
        build = f"#{r.get('build')}"
        recorded = [x for x in r.get("records") or [] if x.get("type") == "TRIGGER_BLOCKED" and x.get("user") == r.get("user")
                    and build in (x.get("detail") or "") and "Rebuild" in (x.get("detail") or "")]
        problems = []
        if not isinstance(r.get("submit_status"), int) or r["submit_status"] < 400 or not ("approv" in text or "run request" in text):
            problems.append("the submission was not refused with the approval message")
        if r.get("queue") or r.get("next_build") is None or r.get("next_build") != r.get("next_build_before"):
            problems.append("something was queued or built")
        if not recorded:
            problems.append("no TRIGGER_BLOCKED record naming the user and the re-run build")
        if problems:
            j.fail("rebuild-menu-refused", dict(key, problems=problems),
                   {k: r.get(k) for k in ("submit_status", "submit_url", "submit_error", "queue", "next_build_before",
                                          "next_build", "records")} | {"text": text[:160]})
    return j


# ------------------------------------------------------------------ r14/round3.py B (#31, r9/s76.py)
# The roles whose tab badges round3.py B measures on /batch-control/ (r9/s76.py's roles; each exists in casc/jenkins.yaml).
BADGE_ROLES = ("admin", "requester", "approver-1", "manager", "reqonly", "approver-2", "approver-unlisted", "auditor")


def _tip(e):
    """The tooltip text of an element: core's l:badge writes `tooltip`; data-tooltip and title are read as well."""
    return next((v.strip() for v in (e.get("tooltip"), e.get("data_tooltip"), e.get("title")) if v and v.strip()), "")


def judge_badges(rows, ctx=None):
    """#31 (the retired r9/s76.py): the Batch Control tab badges explain themselves. SPEC and TEST-MATRIX do not fix
    the tooltip's wording, so only its presence and its number are judged (round3.py B, one row per BADGE_ROLES role):
      * badge-tooltip: every badge below a tab is shown, its text is a number, and it carries a non-empty tooltip that
        contains that number as a whole number ("3" does not match "13");
      * badge-tooltip-absent: a tab that shows no badge has no tooltip-like attribute below it either (a count
        announced without a badge);
      * badge-tooltip-measured: each BADGE_ROLES role has a row whose overview answered 200 with its tabs measured,
        and at least one role shows a badge (otherwise the two rules above pass on nothing)."""
    j = Judge("badges", ctx)
    by_user = {r.get("user"): r for r in rows}
    shown = 0
    for user in BADGE_ROLES:
        r = by_user.get(user)
        if r is None:
            j.fail("badge-tooltip-measured", {"user": user, "row": "missing"})
            continue
        tabs = r.get("tabs")
        if r.get("status") != 200 or not isinstance(tabs, list) or not tabs:
            j.fail("badge-tooltip-measured", {"user": user, "status": r.get("status"), "tabs": None if tabs is None else len(tabs)},
                   {"error": r.get("error")})
            continue
        for t in tabs:
            key = {"user": user, "tab": t.get("tab")}
            badges = t.get("badges") or []
            for b in badges:
                text, tip = (b.get("text") or "").strip(), _tip(b)
                if not b.get("visible") or not re.fullmatch(r"\d+", text):
                    j.fail("badge-tooltip", dict(key, problem="badge not shown with a number", text=text), b)
                elif not tip:
                    j.fail("badge-tooltip", dict(key, problem="no tooltip", number=text), b)
                elif not re.search(rf"(?<!\d){text}(?!\d)", tip):
                    j.fail("badge-tooltip", dict(key, problem="tooltip without the badge's number", number=text), {"tooltip": tip})
                else:
                    shown += 1
            if not badges:
                stray = [o for o in t.get("others") or [] if _tip(o)]
                if stray:
                    j.fail("badge-tooltip-absent", key, {"others": stray[:3]})
    if rows and not shown:
        j.fail("badge-tooltip-measured", {"badges": 0, "problem": "no role shows a badge with its tooltip"})
    return j


# ------------------------------------------------------------------ r14/round3.py C (#31, r11/s_tabs.py #2)
# The bars round3.py C measures on /batch-control/ at narrow widths: admin has every tab, approver-1 every tab of its
# sections with badges (its bar already takes two rows at 1024 px).
TAB_BAR_NARROW_USERS = ("admin", "approver-1")
TAB_BAR_NARROW_WIDTHS = (1024, 800)
NARROW_TOLERANCE_PX = 2  # sub-pixel rounding of text widths, as round3.py's SCROLL_TOLERANCE_PX


def judge_tab_bar_narrow(rows, ctx=None):
    """#31 (the retired r11/s_tabs.py #2; e2e-11 R4-7: the bar "wraps to 2 rows at 1024 and 800, with no horizontal
    scroll"): the Batch Control tab bar may wrap at a narrow width, but it neither makes the page scroll sideways nor puts
    a tab out of reach. round3.py C opens /batch-control/ (the overview: the bar and badges, nothing else that is wide,
    so a sideways scroll there comes from the bar) as each TAB_BAR_NARROW_USERS user at each TAB_BAR_NARROW_WIDTHS width:
      * tab-bar-narrow-scroll: the document's scroll width is at most its client width (+ NARROW_TOLERANCE_PX);
      * tab-bar-narrow-reach: every tab is shown and lies inside the viewport (left >= 0, right <= client width + the
        tolerance), and the bar has no hidden overflow of its own (its scroll width at most its client width + the
        tolerance: tabs clipped or scrolled inside the bar are out of reach too);
      * tab-bar-narrow-measured: a row for each user and width whose page answered 200 with the bar and its tabs measured,
        the same number of tabs at every width of a user. How many rows the bar takes is recorded, not judged."""
    j = Judge("tab-bar-narrow", ctx)
    tol = NARROW_TOLERANCE_PX
    seen = {(r.get("user"), r.get("width")): r for r in rows}
    counts = {}
    for user in TAB_BAR_NARROW_USERS:
        for width in TAB_BAR_NARROW_WIDTHS:
            r = seen.get((user, width))
            key = {"user": user, "width": width}
            if r is None:
                j.fail("tab-bar-narrow-measured", dict(key, row="missing"))
                continue
            tabs = r.get("tabs")
            if r.get("status") != 200 or not r.get("nav") or not isinstance(tabs, list) or not tabs:
                j.fail("tab-bar-narrow-measured", dict(key, status=r.get("status"), nav=r.get("nav"),
                                                       tabs=None if tabs is None else len(tabs)), {"error": r.get("error")})
                continue
            counts.setdefault(user, set()).add(len(tabs))
            if (r.get("scrollW") or 0) > (r.get("clientW") or 0) + tol:
                j.fail("tab-bar-narrow-scroll", dict(key, scrollW=r.get("scrollW"), clientW=r.get("clientW")),
                       {"navRight": r.get("navRight"), "rows": r.get("rows"), "overflowX": r.get("overflowX")})
            out = [t.get("tab") for t in tabs if not t.get("visible") or (t.get("left") or 0) < 0
                   or (t.get("right") or 0) > (r.get("clientW") or 0) + tol]
            hidden = (r.get("navScrollW") or 0) > (r.get("navClientW") or 0) + tol
            if out or hidden:
                j.fail("tab-bar-narrow-reach", dict(key, out_of_reach=out, bar_overflow=hidden),
                       {"navScrollW": r.get("navScrollW"), "navClientW": r.get("navClientW"), "clientW": r.get("clientW"),
                        "overflowX": r.get("overflowX")})
    for user, n in counts.items():
        if len(n) > 1:
            j.fail("tab-bar-narrow-measured", {"user": user, "problem": "the number of tabs differs between widths",
                                               "tabs": sorted(n)})
    return j


# ------------------------------------------------------------------ r14/round3.py C (#31, B/section-a #27, A/sa-read #14)
# The request pages round3.py C reads at narrow widths: (path below /batch-control/, the seed's id in r14/out/ids.json),
# as their approver, approver-1 (the legacy A-17 user: the Decision section is on the page).
DETAIL_NARROW_USER = "approver-1"
DETAIL_NARROW_PAGES = (("requests", "req_pending"), ("grants", "g_pending_job"))
DETAIL_NARROW_WIDTHS = (1280, 800)
COMPACT_TABLE = "jenkins-table--small"


def judge_detail_narrow(rows, ctx=None):
    """#31 (the retired B/section-a.mjs #27 and B/audit/sa-read.mjs #14, A-17; e2e-03 G-37 "at 800 px the run and grant
    request detail pages do not scroll sideways (compact summary)"): round3.py C opens a pending run request's and a
    pending grant request's page as DETAIL_NARROW_USER at each DETAIL_NARROW_WIDTHS width:
      * detail-narrow-scroll: the document's scroll width is at most its client width (+ NARROW_TOLERANCE_PX), and the
        summary table has no hidden overflow of its own and ends inside the viewport;
      * detail-narrow-compact: the summary table (the main panel's first table) is core's compact table, class
        jenkins-table--small;
      * detail-narrow-measured: a row for each page and width that answered 200 in a viewport of that width, with the
        summary table found (its row headers read)."""
    j = Judge("detail-narrow", ctx)
    tol = NARROW_TOLERANCE_PX
    seen = {(r.get("kind"), r.get("width")): r for r in rows}
    for kind, _ in DETAIL_NARROW_PAGES:
        for width in DETAIL_NARROW_WIDTHS:
            r = seen.get((kind, width))
            key = {"kind": kind, "width": width}
            if r is None:
                j.fail("detail-narrow-measured", dict(key, row="missing"))
                continue
            t = r.get("table")
            # the client width is the viewport less a vertical scroll bar, if any
            if (r.get("status") != 200 or not isinstance(t, dict) or not t.get("rows") or not t.get("heads")
                    or not (width - 20 <= (r.get("clientW") or 0) <= width)):
                j.fail("detail-narrow-measured", dict(key, status=r.get("status"), table=None if t is None else t.get("rows"),
                                                      clientW=r.get("clientW")), {"error": r.get("error")})
                continue
            cw = r.get("clientW") or 0
            if ((r.get("scrollW") or 0) > cw + tol or (t.get("scrollW") or 0) > (t.get("clientW") or 0) + tol
                    or (t.get("right") or 0) > cw + tol):
                j.fail("detail-narrow-scroll", dict(key, scrollW=r.get("scrollW"), clientW=cw),
                       {"table": {k: t.get(k) for k in ("left", "right", "width", "scrollW", "clientW")}, "main": r.get("main")})
            if COMPACT_TABLE not in (t.get("cls") or "").split():
                j.fail("detail-narrow-compact", dict(key, cls=t.get("cls")))
    return j


# ------------------------------------------------------------------ r14/round3.py H (#31, A/b4 #4, V/v5 #4)
# The Request Run dialogs round3.py H opens: (ui, user, job) of the new job page's app bar entry and the classic side
# panel's entry.
REQUEST_DIALOG_CANCEL = (("new", "requester", "batch-daily"), ("classic", "classic", "batch-pipeline"))


def judge_request_dialog_cancel(rows, ctx=None):
    """#31 (the retired B/audit/b4.mjs #4 and B/verify/v5.mjs #4; e2e-03 G-36 "Cancel on the Request Run form stores
    nothing", the dialog half; the page form's Cancel is a link to the job, a JenkinsRule row): round3.py H opens the
    Request Run dialog on each REQUEST_DIALOG_CANCEL job page, fills a reason unique to the run and ticks approver-1:
      * request-dialog-cancel: the dialog has exactly one Cancel button; it closes the dialog without leaving the job
        page, posts nothing to Batch Control and no request with that reason is in the store;
      * request-dialog-cancel-guard: the same dialog, filled the same way and submitted, lands on the new request's page
        and the store holds exactly one PENDING request with that reason (a Cancel that only fails to submit because the
        dialog cannot submit at all would otherwise pass);
      * request-dialog-cancel-measured: a row for each dialog, with no driver error, no request with the reason before
        the dialog opened, and every step read."""
    j = Judge("request-dialog-cancel", ctx)
    by_ui = {r.get("ui"): r for r in rows}
    for ui, user, job in REQUEST_DIALOG_CANCEL:
        r = by_ui.get(ui)
        key = {"ui": ui, "user": user, "job": job}
        if r is None:
            j.fail("request-dialog-cancel-measured", dict(key, row="missing"))
            continue
        # the Cancel half is judged whenever it was measured, also when a later step raised
        cancel_measured = (r.get("user") == user and r.get("job") == job and r.get("stored_before") == []
                           and isinstance(r.get("closed"), bool) and isinstance(r.get("stored_after_cancel"), list))
        guard_measured = cancel_measured and isinstance(r.get("stored_after_submit"), list)
        if r.get("error") or not guard_measured:
            j.fail("request-dialog-cancel-measured", dict(key, problem="not measured"),
                   {k: r.get(k) for k in ("error", "user", "job", "stored_before", "closed", "stored_after_cancel",
                                          "stored_after_submit")})
        if cancel_measured and not (r.get("cancel_buttons") == 1 and r.get("closed") is True and r.get("url_unchanged") is True
                                    and r.get("posts_on_cancel") == [] and r.get("stored_after_cancel") == []):
            j.fail("request-dialog-cancel", key, {k: r.get(k) for k in ("cancel_buttons", "closed", "url_unchanged",
                                                                        "posts_on_cancel", "stored_after_cancel")})
        stored = r.get("stored_after_submit") or []
        if guard_measured and not (DETAIL.fullmatch(r.get("landing") or "") and "/batch-control/requests/" in r.get("landing")
                                   and len(stored) == 1 and stored[0].endswith(" PENDING")
                                   and stored[0].split()[0] in r.get("landing")):
            j.fail("request-dialog-cancel-guard", key, {"landing": r.get("landing"), "stored": stored})
    return j


# ------------------------------------------------------------------ r14/round3.py F (#31, r9/s89.py #4, r10/s89.py)
# The users whose grants page must show all three lists (the seed gives requester, and so admin, pending requests,
# active windows and ended ones); round3.py F measures every user it visits.
GRANTS_WRAP_USERS = ("admin", "requester")
GRANTS_LISTS = ("pending", "active", "ended")
# Short columns stay on one line (GrantsSection/index.jelly: the id, the user or requester, the duration, the Names
# restriction, the remaining time and the column of the Revoke and Request again buttons, whose header is empty).
GRANTS_SHORT_COLUMNS = ("ID", "User", "Requester", "Duration", "Names", "Remaining", "")
GRANTS_FREE_TEXT = ("Reason",)  # the requester's own words, which may break anywhere


def judge_grants_nowrap(rows, ctx=None):
    """#31 (the retired r9/s89.py #4 and r10/s89.py; e2e-09 DEF-01 "approver-2 breaks as approver- / 2", header "New job
    names" on 3 lines; e2e-10 DEF-04 "the How text is a state line and may wrap"): at 1280 px, with pending, active and
    ended entries listed, the grants page's tables keep headers and short cells on one line. round3.py F reads every
    table[data-batch-control-list] on /batch-control/grants/ for each user it visits:
      * grants-nowrap-header: every header cell's text takes one line;
      * grants-nowrap-short: every cell of a GRANTS_SHORT_COLUMNS column takes one line;
      * grants-nowrap-word: no other cell, the free-text Reason excepted, splits a word (a run of non-blank characters:
        an item name, an approver name, an action, a date or time part, a word of How) across two lines; those cells
        may wrap only between words (Scope between kind and name, Approvers and Actions between entries, a date between
        date and time, How anywhere between words);
      * grants-nowrap-measured: a row for each GRANTS_WRAP_USERS user whose page answered 200 in a viewport 1280 px
        wide and showed each of GRANTS_LISTS with at least one entry, every cell read."""
    j = Judge("grants-nowrap", ctx)
    by_user = {r.get("user"): r for r in rows}
    for user in GRANTS_WRAP_USERS:
        r = by_user.get(user)
        if r is None:
            j.fail("grants-nowrap-measured", {"user": user, "row": "missing"})
            continue
        lists = {t.get("list"): t for t in r.get("tables") or []}
        thin = [x for x in GRANTS_LISTS if not (lists.get(x) or {}).get("rows")]
        unread = [x for x, t in lists.items() if (t.get("read") or 0) < (t.get("rows") or 0)]
        if r.get("status") != 200 or r.get("viewport") != 1280 or thin or unread:
            j.fail("grants-nowrap-measured", {"user": user, "status": r.get("status"), "viewport": r.get("viewport"),
                                              "lists without entries": thin, "unread": unread}, {"error": r.get("error")})
    for r in rows:
        user = r.get("user")
        for t in r.get("tables") or []:
            key = {"user": user, "list": t.get("list")}
            for h in t.get("heads") or []:
                if (h.get("lines") or 0) > 1:
                    j.fail("grants-nowrap-header", dict(key, header=h.get("text"), lines=h.get("lines")))
            for c in t.get("cells") or []:
                col = c.get("col")
                if col in GRANTS_SHORT_COLUMNS and (c.get("lines") or 0) > 1:
                    j.fail("grants-nowrap-short", dict(key, column=col, lines=c.get("lines")), {"text": c.get("text")})
                elif col not in GRANTS_FREE_TEXT and c.get("broken"):
                    j.fail("grants-nowrap-word", dict(key, column=col, words=c.get("broken")), {"text": c.get("text")})
    return j


# ------------------------------------------------------------------ r14/misc.py K (#31, r7/e_dark.py #3, r11/s_dark_monitor.py #2)
def _n(c):
    """The canvas-normalised "r,g,b,a" of a measured colour ({raw, n}), or None."""
    return (c or {}).get("n")


def judge_dark(rows, ctx=None):
    """#31 (the retired r7/e_dark.py #3 and r11/s_dark_monitor.py #2): in the dark theme the Batch Control tables,
    alerts and current tab take the dark palette, not light colours of their own. "The dark palette" is the theme's own
    CSS variables, read on the same page by probes (misc.py K, DARK_JS: a span styled with var(--x), as the destructive
    probe), for the colours core's components take from the theme; colours compare canvas-normalised ("r,g,b,a"):
      * dark-table: on a Batch Control page every shown .jenkins-table paints var(--table-background); each cell (td) of
        its first row var(--table-body-background) in var(--text-color), a row header (th) of that row is written in
        var(--text-color) over the table's background; each column header in var(--table-header-foreground);
      * dark-alert: every shown .jenkins-alert has a variant (success, info, warning, danger) and paints
        var(--alert-<variant>-bg-color) in var(--alert-<variant>-text-color);
      * dark-tab: the current tab of the tab bar is written in var(--text-color) on var(--button-background) (its
        ::before, where core paints a button's background) and looks different from the other tabs (still marked);
      * dark-measured: the light theme's variables were read before the switch (row "light"); a dark row for each page
        misc.py K visits (ctx "pages") that answered 200 with data-theme "dark" and whose --background and --text-color
        differ from the light ones (the theme really changed the palette, so the comparisons are not light against
        light); and over all pages at least one table, one alert and one current tab measured."""
    j = Judge("dark", ctx)
    light = next((r for r in rows if r.get("phase") == "light"), None)
    lv = (light or {}).get("vars") or {}
    if not _n(lv.get("background")) or not _n(lv.get("text")):
        j.fail("dark-measured", {"light": "missing" if light is None else "no variables"})
    dark = [r for r in rows if r.get("phase") == "dark"]
    want = (ctx or {}).get("pages")
    if not dark or (want is not None and len(dark) != want):
        j.fail("dark-measured", {"dark rows": len(dark), "pages": want})
    tables = alerts = tabs = 0
    for r in dark:
        page, v = r.get("page"), r.get("vars") or {}
        key = {"page": page_key(page or "")}
        if (r.get("status") != 200 or r.get("theme") != "dark" or not _n(v.get("background"))
                or _n(v.get("background")) == _n(lv.get("background")) or _n(v.get("text")) == _n(lv.get("text"))):
            j.fail("dark-measured", dict(key, status=r.get("status"), theme=r.get("theme")),
                   {"background": [_n(lv.get("background")), _n(v.get("background"))], "text": [_n(lv.get("text")), _n(v.get("text"))],
                    "error": r.get("error")})
            continue
        for t in r.get("tables") or []:
            tables += 1
            tkey = dict(key, list=t.get("list"))
            bad = []
            if _n(t.get("bg")) != _n(v.get("table")):
                bad.append({"part": "table", "bg": (t.get("bg") or {}).get("raw"), "want": (v.get("table") or {}).get("raw")})
            for i, c in enumerate(t.get("cells") or []):
                # a row header (th, the label column of a detail page's table) is left transparent by core, over the
                # table's own background; its text colour is judged
                paint = c.get("tag") == "th" or _n(c.get("bg")) == _n(v.get("body"))
                if not paint or _n(c.get("color")) != _n(v.get("text")):
                    bad.append({"part": f"cell {i}", "bg": (c.get("bg") or {}).get("raw"), "color": (c.get("color") or {}).get("raw"),
                                "cls": c.get("cls")})
            for h in t.get("heads") or []:
                if _n(h.get("color")) != _n(v.get("head")):
                    bad.append({"part": f"header {h.get('text')}", "color": (h.get("color") or {}).get("raw")})
            if bad:
                j.fail("dark-table", dict(tkey, parts=sorted({b["part"].split()[0] for b in bad})),
                       {"bad": bad[:4], "want": {k: (v.get(k) or {}).get("raw") for k in ("table", "body", "text", "head")}})
        for a in r.get("alerts") or []:
            alerts += 1
            var = a.get("variant")
            bg, fg = v.get(f"alert_{var}_bg"), v.get(f"alert_{var}_text")
            if not var or _n(a.get("bg")) != _n(bg) or _n(a.get("color")) != _n(fg):
                j.fail("dark-alert", dict(key, variant=var),
                       {"text": a.get("text"), "bg": (a.get("bg") or {}).get("raw"), "color": (a.get("color") or {}).get("raw"),
                        "want": [(bg or {}).get("raw"), (fg or {}).get("raw")]})
        tab = r.get("tab")
        if tab is not None:
            cur, other = tab.get("current") or {}, tab.get("other") or {}
            if not cur:
                j.fail("dark-tab", dict(key, problem="no current tab"))
                continue
            tabs += 1
            looks = lambda x: (_n(x.get("color")), _n(x.get("before")))  # noqa: E731
            if _n(cur.get("color")) != _n(v.get("text")) or _n(cur.get("before")) != _n(v.get("button")) or looks(cur) == looks(other):
                j.fail("dark-tab", dict(key, tab=cur.get("tab")),
                       {"current": [(cur.get("color") or {}).get("raw"), (cur.get("before") or {}).get("raw")],
                        "other": [(other.get("color") or {}).get("raw"), (other.get("before") or {}).get("raw")],
                        "want": [(v.get("text") or {}).get("raw"), (v.get("button") or {}).get("raw")]})
    if dark and not (tables and alerts and tabs):
        j.fail("dark-measured", {"tables": tables, "alerts": alerts, "current tabs": tabs,
                                 "problem": "a kind of element was measured on no page"})
    return j


# ------------------------------------------------------------------ r14/misc.py H (#31, B/b11 #9, B/b14 #16, A/b11 #13, A/b14 #17)
# The month pages misc.py H walks, with the sentence each shows for a month without records (ChangesSection and
# IncidentsSection index.jelly) and the noun of its page footer ("Page 1 (198 records in 2026-11)").
MONTH_NAV_PAGES = {"/batch-control/changes/": ("No change records in {m}.", "records?"),
                   "/batch-control/incidents/": ("No incidents in {m}.", "incidents?")}
MONTH_PAGE_SIZE = 50  # Paging.PAGE_SIZE
MONTH_RX = re.compile(r"\d{4}-(0[1-9]|1[0-2])")


def _prev_month(m):
    y, mo = int(m[:4]), int(m[5:7])
    return f"{y - 1}-12" if mo == 1 else f"{y}-{mo - 1:02d}"


def judge_month_nav(rows, ctx=None):
    """#31 (the retired B/b11.mjs #9, B/b14.mjs #16, A/b11.mjs #13, A/b14.mjs #17; SPEC 9 and 11: one month at a time):
    the month navigation of Change Records and Incidents as approver-1 uses it (misc.py H, one row per page):
      * month-nav-prev: the "«" link names the month before the shown one (href ?month=<previous>) and opens that month
        (its URL and its month field); with no record there it says the empty month's sentence for that month, with
        records it lists them and does not say it;
      * month-nav-empty: a month without records (ten years before the shown one, asked for by URL) lists nothing and
        says exactly "No change records in <m>." / "No incidents in <m>." for that month;
      * month-nav-paging: on Change Records, whose shown month holds more than 50 records on the CI stack, page 1 lists
        50 and its footer counts them in that month; Older (?month=<m>&page=2) opens page 2 with the rest (at most 50),
        a footer "Page 2 (<n> records in <m>)", another first row, and Newer;
      * month-nav-measured: a row for each MONTH_NAV_PAGES page that answered 200 with its month shown and the "«" link
        followed; the shown month of Change Records lists records (a page that always says "No change records" fails)
        and more than 50 of them (the premise of Older), measured with page 2 opened."""
    j = Judge("month-nav", ctx)
    by_page = {r.get("page"): r for r in rows}
    for page, (sentence, noun) in MONTH_NAV_PAGES.items():
        r = by_page.get(page)
        key = {"page": page}
        if r is None:
            j.fail("month-nav-measured", dict(key, row="missing"))
            continue
        m = r.get("month") or ""
        if r.get("status") != 200 or not MONTH_RX.fullmatch(m) or not isinstance(r.get("prev"), dict) \
                or not isinstance(r.get("empty_month"), dict):
            j.fail("month-nav-measured", dict(key, status=r.get("status"), month=m, prev="prev" in r, empty_month="empty_month" in r),
                   {"error": r.get("error")})
            continue
        want = _prev_month(m)
        pv = r["prev"]
        prev_ok = (r.get("prev_href") == f"?month={want}" and (pv.get("url") or "").endswith(f"{page}?month={want}")
                   and pv.get("month") == want)
        if (pv.get("rows") or 0) == 0:
            prev_ok = prev_ok and pv.get("empty") == sentence.format(m=want)
        else:
            prev_ok = prev_ok and pv.get("empty") is None
        if not prev_ok:
            j.fail("month-nav-prev", dict(key, shown=m, expected=want),
                   {"href": r.get("prev_href"), "landing": pv.get("url"), "month": pv.get("month"), "rows": pv.get("rows"),
                    "empty": pv.get("empty")})
        em = r["empty_month"]
        asked = em.get("asked") or ""
        if not (em.get("status") == 200 and MONTH_RX.fullmatch(asked) and em.get("month") == asked and em.get("rows") == 0
                and em.get("empty") == sentence.format(m=asked) and em.get("footer") is None):
            j.fail("month-nav-empty", dict(key, asked=asked),
                   {k: em.get(k) for k in ("status", "month", "rows", "empty", "footer", "url")})
        if page != "/batch-control/changes/":
            continue
        foot = re.fullmatch(rf"Page 1 \((\d+) {noun} in ({MONTH_RX.pattern})\)", r.get("footer") or "")
        total = int(foot.group(1)) if foot else 0
        pg = r.get("paging")
        if not r.get("rows") or total <= MONTH_PAGE_SIZE or not isinstance(pg, dict):
            j.fail("month-nav-measured", dict(key, problem="the shown month of Change Records does not hold more than "
                                                           f"{MONTH_PAGE_SIZE} records, or Older was not measured",
                                              rows=r.get("rows"), footer=r.get("footer")))
            continue
        p2 = pg.get("page2") or {}
        rest = min(MONTH_PAGE_SIZE, total - MONTH_PAGE_SIZE)
        if not (foot.group(2) == m and r.get("rows") == MONTH_PAGE_SIZE and pg.get("older_href") == f"?month={m}&page=2"
                and (p2.get("url") or "").endswith(f"{page}?month={m}&page=2") and p2.get("month") == m
                and p2.get("rows") == rest and p2.get("footer") == f"Page 2 ({total} records in {m})"
                and p2.get("first_row") and p2.get("first_row") != pg.get("first_row") and p2.get("newer") == 1):
            j.fail("month-nav-paging", dict(key, month=m, total=total),
                   {"rows": r.get("rows"), "older": pg.get("older_href"), "page2": {k: p2.get(k) for k in
                    ("url", "month", "rows", "footer", "newer")}, "same_first_row": p2.get("first_row") == pg.get("first_row")})
    return j


# ------------------------------------------------------------------ h3/deploy.py (#31, B/b21 #1b)
# README "Requirements": the plugins Batch Control needs, "resolved for you on installation".
DEPLOY_REQUIRED = ("cloudbees-folder", "ionicons-api", "caffeine-api")
# where Deploy Plugin sends the browser: the update center's installation status (core 2.568: the Plugins page's Updates)
DEPLOY_LANDING = re.compile(r"/(updateCenter|pluginManager/updates)/?$")


def judge_deploy(rows, ctx=None):
    """#31 (the retired B/b21.mjs #1b, BT25; README "Installation": "upload a build under Advanced settings -> Deploy
    Plugin", "Required plugins ... resolved for you on installation"): h3/deploy.py on a clean controller of the core of
    pom.xml without any plugin:
      * deploy-clean-before: before the upload neither batch-control nor any of DEPLOY_REQUIRED is installed and
        /batch-control/ answers 404 (the guard: what the upload installs was not there already);
      * deploy-installed: after the upload (pluginManager/uploadPlugin, the form's fields) and a restart, batch-control
        is installed at the uploaded build's version, enabled and active, every DEPLOY_REQUIRED plugin is installed and
        active, no installation job failed, no plugin failed to load, and /batch-control/ answers 200;
      * deploy-measured: one row, no driver error, the controller answered, the plugin list was read before and after,
        the update center's metadata was downloaded (Check now), and the upload was accepted (302 to the installation
        status, DEPLOY_LANDING) with its installation jobs read."""
    j = Judge("deploy", ctx)
    r = rows[0] if len(rows) == 1 else None
    if r is None:
        j.fail("deploy-measured", {"rows": len(rows)})
        return j
    uc = r.get("update_center") or {}
    up = r.get("upload") or {}
    if (r.get("error") or not r.get("boot_seconds") or not isinstance(r.get("before"), dict)
            or not isinstance(r.get("after"), dict) or not str(uc.get("plugins") or "").isdigit()
            or int(uc.get("plugins")) == 0 or up.get("status") != 302 or not DEPLOY_LANDING.search(up.get("location") or "")
            or not r.get("jobs")):
        j.fail("deploy-measured", {k: r.get(k) for k in ("error", "boot_seconds", "update_center", "upload")}
               | {"before": isinstance(r.get("before"), dict), "after": isinstance(r.get("after"), dict), "jobs": r.get("jobs")})
    before = r.get("before") if isinstance(r.get("before"), dict) else {}
    there = [p for p in ("batch-control",) + DEPLOY_REQUIRED if p in before]
    if not isinstance(r.get("before"), dict) or there or r.get("before_page") != 404:
        j.fail("deploy-clean-before", {"installed before": there, "page before": r.get("before_page")})
    after = r.get("after") if isinstance(r.get("after"), dict) else {}
    bc = after.get("batch-control") or {}
    missing = [p for p in DEPLOY_REQUIRED if not (after.get(p) or {}).get("active")]
    failed_jobs = [x for x in r.get("jobs") or [] if "Failure" in (x.get("status") or "")]
    if not (bc.get("version") == r.get("hpi_version") and bc.get("active") is True and bc.get("enabled") is True
            and not missing and not failed_jobs and r.get("failed") == "[]" and r.get("after_page") == 200):
        j.fail("deploy-installed", {"batch-control": bc, "expected version": r.get("hpi_version"), "required not active": missing,
                                    "failed jobs": failed_jobs, "failed plugins": r.get("failed"), "page after": r.get("after_page")})
    return j


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
    if driver == "destructive":
        return judge_destructive(rows, ctx)
    if driver == "rebuild-menu":
        return judge_rebuild_menu(rows, ctx)
    if driver == "badges":
        return judge_badges(rows, ctx)
    if driver == "tab-bar-narrow":
        return judge_tab_bar_narrow(rows, ctx)
    if driver == "grants-nowrap":
        return judge_grants_nowrap(rows, ctx)
    if driver == "dark":
        return judge_dark(rows, ctx)
    if driver == "month-nav":
        return judge_month_nav(rows, ctx)
    if driver == "detail-narrow":
        return judge_detail_narrow(rows, ctx)
    if driver == "request-dialog-cancel":
        return judge_request_dialog_cancel(rows, ctx)
    if driver == "deploy":
        return judge_deploy(rows, ctx)
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
