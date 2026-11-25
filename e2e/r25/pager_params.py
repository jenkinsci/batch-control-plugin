"""e2e-25 #45: paging one list on the Run Requests, Grants or Activations page keeps the other lists' page parameters
(pendingPage, activePage, endedPage) in the URL, so their content stays on their page; the pager link changes only its
own list's parameter and still escapes values (the links used to be "?<param>=N", which dropped the other lists' pages).

usage: python pager_params.py [APXS]   rows: out/pager_params.jsonl, shots: screenshots/run-25/R25-45-*.png
Items: folder r25-pg with Freestyle jobs j01..j51, each bound to the label r25-nowhere (no node has it, so an approved run
stays queued: an APPROVED request whose run has not started). Account r25-pager (local, BC_OTHER_PASSWORD): Overall/Read,
Item/Read, View/Read, Item/Build, BatchControl/Request and RequestGrant on Jenkins. Per job, over REST (r25-pager files,
approver-2 decides):
  activations: ACTIVATE A1 and A2, A1 approved (in effect: Active; A2 invalidated: Ended), then HOLD H1 (Pending); a job
               already activated by an earlier run gets a HOLD rejected (Ended) instead, its A1 stays in effect;
  run requests: R1 approved (queued: Active), R2 rejected (Ended), R3 (Pending);
  grants on r25-pg/jNN (Configure, 60 min): G1 approved (window: Active), G2 rejected (Ended), G3 (Pending).
So each of the nine lists holds more than 50 rows (50 a page). The viewer is admin (sees every row), one new browser
context per page.

A  arrangement: sweeps r25-pager's leftovers of an earlier run (pending requests cancelled, windows revoked, queue items
   of r25-pg cancelled), then creates the above; fixture: every list of the three pages has an Older link on page 1.
P  for each page and each list L: from ?pendingPage=2&activePage=2&endedPage=2 (guard: every list shows its page 2),
   L's Newer, then L's Older is clicked. #45 contract: the URL keeps the two other lists' parameters at 2 and their
   tables still show their page 2 (footer "Page 2", the same rows as before the click). Guard: L's own parameter and
   table change to the page clicked.
X  escaping guard: with a parameter value holding markup (another parameter, a list's own page parameter) or & and =,
   the pages render no injected element, the pager links stay intact and every value a link carries round-trips
   unchanged (for a page parameter that is no page number, the page its list shows is accepted as well).
S  screen summary: the Requests page after paging the Ended list from the all-page-2 state (screenshot).
cleanup: the same sweep (pending requests cancelled, windows revoked, queued runs cancelled)."""
import json
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import parse_qsl, urlsplit, quote

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, Session  # noqa: E402

lib.LOGNAME[0] = "pager_params"
FOLDER, LABEL, N_JOBS = "r25-pg", "r25-nowhere", 51
JOBS = [f"{FOLDER}/j{i:02d}" for i in range(1, N_JOBS + 1)]
USER, APPROVER, VIEWER = "r25-pager", "approver-2", "admin"
PAGES = {"requests": "/batch-control/requests/", "grants": "/batch-control/grants/",
         "activations": "/batch-control/activations/"}
LISTS = ("pending", "active", "ended")
PARAM = {"pending": "pendingPage", "active": "activePage", "ended": "endedPage"}
THREADS = 10  # parallel REST clients of the arrangement and the sweep
PERMS = ["hudson.model.Hudson.Read", "hudson.model.Item.Read", "hudson.model.View.Read", "hudson.model.Item.Build",
         lib.PERM + "Request", lib.PERM + "RequestGrant"]
S = {}
_local = threading.local()
LAT, _LAT_LOCK = {}, threading.Lock()  # seconds per REST call of the arrangement, by kind (reported in a NOTE)


def _timed(key, fn):
    t0 = time.monotonic()
    v = fn()
    with _LAT_LOCK:
        LAT.setdefault(key, []).append(time.monotonic() - t0)
    return v

LIST_JS = """(list) => {
  const t = document.querySelector(`table[data-batch-control-list="${list}"]`);
  if (!t) return null;
  let nav = t.nextElementSibling;
  while (nav && nav.getAttribute('role') !== 'navigation') nav = nav.nextElementSibling;
  const links = nav ? [...nav.querySelectorAll('a')].map(a => ({text: a.textContent.trim(), href: a.getAttribute('href')})) : [];
  return {ids: [...t.querySelectorAll('tbody tr')].map(tr => { const e = tr.querySelector('[data-batch-control-id]');
            return e ? e.getAttribute('data-batch-control-id') : tr.textContent.replace(/\\s+/g, ' ').trim().slice(0, 80); }),
          footer: nav ? nav.textContent.replace(/\\s+/g, ' ').trim() : null, links}; }"""


def poster(user):
    d = getattr(_local, "posters", None)
    if d is None:
        d = _local.posters = {}
    if user not in d:
        d[user] = lib.Poster(user)
    return d[user]


def loc(r):
    m = re.search(lib.UUID, r.headers.get("Location", "") or "")
    return m.group(0) if m else None


def file_run(job):
    r = _timed("run request", lambda: poster(USER).post(lib.J(job) + "/batch-control/submit", data={"json": json.dumps(
        {"reason": f"e2e-25 #45 {job}", "approvers": [APPROVER], "parameter": []})}))
    return loc(r), r.status_code


def file_act(job, action):
    r = _timed(f"{action} request", lambda: poster(USER).post(lib.J(job) + "/batch-control-activation/submit", data=[
        ("action", action), ("reason", f"e2e-25 #45 {action} {job}"), ("approvers", APPROVER)]))
    return loc(r), r.status_code


def file_grant(job):
    r = _timed("grant request", lambda: poster(USER).post("/batch-control/grants/create", data=[
        ("scopeFullName", job), ("durationMinutes", "60"), ("reason", f"e2e-25 #45 {job}"), ("actions", "CONFIGURE"),
        ("approvers", APPROVER)]))
    return loc(r), r.status_code


def decide(kind, rid, verb):
    if not rid:
        return None
    return _timed(f"{kind} {verb}", lambda: poster(APPROVER).post(f"/batch-control/{kind}/{rid}/{verb}",
                                                                 data={"comment": "e2e-25 #45"})).status_code


def per_job(job, activated):
    """The nine rows of one job (see the module doc); returns the ids and the HTTP codes."""
    out = {"job": job}
    if activated:
        h0, out["h0"] = file_act(job, "HOLD")
        out["h0_reject"] = decide("activations", h0, "reject")
    else:
        a1, out["a1"] = file_act(job, "ACTIVATE")
        a2, out["a2"] = file_act(job, "ACTIVATE")
        out["a1_approve"] = decide("activations", a1, "approve")
    out["h1_id"], out["h1"] = file_act(job, "HOLD")
    r1, out["r1"] = file_run(job)
    out["r1_approve"] = decide("requests", r1, "approve")
    r2, out["r2"] = file_run(job)
    out["r2_reject"] = decide("requests", r2, "reject")
    out["r3_id"], out["r3"] = file_run(job)
    g1, out["g1"] = file_grant(job)
    out["g1_approve"] = decide("grants", g1, "approve")
    g2, out["g2"] = file_grant(job)
    out["g2_reject"] = decide("grants", g2, "reject")
    out["g3_id"], out["g3"] = file_grant(job)
    return out


def sweep():
    """Cancels r25-pager's pending run, activation and grant requests, revokes its active windows and cancels the
    queue items of r25-pg's jobs. Returns the counts."""
    rows = lib.facts("""def root = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control')
def now = System.currentTimeMillis()
def out = [run: [], activation: [], grant: [], window: []]
[['requests/run', 'run'], ['activation-requests', 'activation'], ['requests/grant', 'grant']].each { d, k ->
  def f = new File(root, d); if (f.exists()) f.listFiles().findAll { it.name ==~ /[0-9a-f-]{36}\\.xml/ }.each { x ->
    def r = new XmlSlurper().parse(x)
    if (r.requester.text() == '""" + USER + """' && r.status.text() == 'PENDING') out[k] << r.id.text() } }
def g = new File(root, 'grants'); if (g.exists()) g.listFiles().findAll { it.name.endsWith('.xml') }.each { x ->
  def r = new XmlSlurper().parse(x)
  if (r.user.text() == '""" + USER + """' && r.revokedAtMillis.text() == '' && (r.expiresAtMillis.text() as long) > now) out.window << r.id.text() }
return groovy.json.JsonOutput.toJson(out)""")
    kinds = {"run": "requests", "activation": "activations", "grant": "grants"}
    done = {}
    with ThreadPoolExecutor(THREADS) as ex:
        for k, ids in rows.items():
            if k == "window":
                done[k] = list(ex.map(lambda i: poster("admin").post(f"/batch-control/grants/{i}/revoke").status_code, ids))
            else:
                done[k] = list(ex.map(lambda i, k=k: poster(USER).post(f"/batch-control/{kinds[k]}/{i}/cancel").status_code, ids))
    q = api("admin", "/queue/api/json?tree=items[id,task[url]]")
    items = [i["id"] for i in (q.json().get("items", []) if q.status_code == 200 else [])
             if f"/job/{FOLDER}/job/" in ((i.get("task") or {}).get("url") or "")]
    done["queue"] = [api("admin", f"/queue/cancelItem?id={i}", "POST").status_code for i in items]
    return {k: (len(v), sorted(set(v))) for k, v in done.items()}


JOB_XML = ("<?xml version='1.1' encoding='UTF-8'?><project><description>e2e-25 #45</description><keepDependencies>false"
           f"</keepDependencies><properties/><scm class='hudson.scm.NullSCM'/><assignedNode>{LABEL}</assignedNode>"
           "<canRoam>false</canRoam><disabled>false</disabled><triggers/><concurrentBuild>false</concurrentBuild>"
           "<builders/><publishers/><buildWrappers/></project>")
ITEMS_STATE = """import jenkins.model.Jenkins
def j = Jenkins.get()
def f = j.getItem('""" + FOLDER + """')
def cl = j.pluginManager.uberClassLoader.loadClass('""" + lib.BC + """.config.BatchControlJobProperty')
def act = """ + lib.BC + """.policy.ActivationService.get()
def items = f == null ? [] : f.getItems()
return groovy.json.JsonOutput.toJson([folder: f != null, jobs: items*.name.sort(),
  bound: items.findAll { it.assignedLabelString == '""" + LABEL + """' }*.name,
  approval: items.findAll { def p = it.getProperty(cl); p != null && p.approvalRequired }*.name,
  activated: items.findAll { act.isActivated(it) }*.fullName])"""


def arrange_items():
    """The folder and its 51 jobs: missing jobs are created over REST (config.xml bound to the label, one save each;
    created while run control is on, so approval-required, D-31). Existing jobs are only read: re-saving 51 jobs costs
    minutes (every save writes a change record and a configuration history entry)."""
    if not lib.facts(ITEMS_STATE)["folder"]:
        lib.gv(f"jenkins.model.Jenkins.get().createProject(com.cloudbees.hudson.plugins.folder.Folder, '{FOLDER}'); "
               "return 'ok'")
    have = set(lib.facts(ITEMS_STATE)["jobs"])
    missing = [j.split("/", 1)[1] for j in JOBS if j.split("/", 1)[1] not in have]

    def create(name):
        return api("admin", f"{lib.J(FOLDER)}/createItem?name={name}", "POST", data=JOB_XML.encode("utf-8"),
                   headers={"Content-Type": "application/xml"}).status_code
    with ThreadPoolExecutor(THREADS) as ex:
        made = list(ex.map(create, missing))
    state = lib.facts(ITEMS_STATE)
    want = {j.split("/", 1)[1] for j in JOBS}
    state["made"] = {"count": len(missing), "codes": sorted(set(made))}
    state["ok"] = want <= set(state["jobs"]) and want <= set(state["bound"]) and want <= set(state["approval"])
    return state


def lists_of(s):
    return {L: s.page.evaluate(LIST_JS, L) for L in LISTS}


def page_no(view):
    m = re.search(r"Page (\d+)", (view or {}).get("footer") or "")
    return int(m.group(1)) if m else None


def link_of(view, text):
    return next((lk["href"] for lk in (view or {}).get("links", []) if lk["text"] == text), None)


def sec_A():
    t0 = time.monotonic()
    perms = lib.ensure_account(USER, PERMS)
    swept = sweep()
    t1 = time.monotonic()
    items = arrange_items()
    activated = set(items["activated"])
    t2 = time.monotonic()
    with ThreadPoolExecutor(THREADS) as ex:
        res = list(ex.map(lambda j: per_job(j, j in activated), JOBS))
    S["seconds"] = {"sweep": round(t1 - t0, 1), "items": round(t2 - t1, 1), "requests": round(time.monotonic() - t2, 1)}
    S["res"] = res
    codes = {}
    for r in res:
        for k, v in r.items():
            if k != "job" and not k.endswith("_id"):
                codes.setdefault(k, {}).setdefault(v, 0)
                codes[k][v] += 1
    latency = {k: {"n": len(v), "mean_s": round(sum(v) / len(v), 2), "max_s": round(max(v), 2)} for k, v in LAT.items()}
    note("A", "arrangement HTTP codes per step (code: count)", codes=codes, swept=swept, seconds=S["seconds"],
         latency=latency, threads=THREADS, items={"made": items["made"], "jobs": len(items["jobs"]),
                                                  "bound": len(items["bound"]), "approval": len(items["approval"])},
         previously_activated=len(activated))
    ok_codes = all(set(v) <= {200, 302, 303} for v in codes.values())
    s = Session(VIEWER, fresh=True)
    fixture = {}
    for page, path in PAGES.items():
        s.go(path)
        views = lists_of(s)
        fixture[page] = {L: {"footer": (views[L] or {}).get("footer"), "next": bool(link_of(views[L], "Older"))} for L in LISTS}
    s.done()
    S["fixture"] = fixture
    check("A", "arrangement: r25-pg/j01..j51 exist, bound to r25-nowhere and approval-required; r25-pager filed and "
          "approver-2 decided every request (2xx/3xx); every list of the Run Requests, Grants and Activations pages has "
          "more than one page (an Older link on page 1)",
          items["ok"] and ok_codes and all(v["next"] for p in fixture.values() for v in p.values()), codes=codes, fixture=fixture,
          perms=perms)


def query_of(url):
    return dict(parse_qsl(urlsplit(url).query, keep_blank_values=True))


def start_state(s, path, L, text):
    """The state a click starts from: every list on page 2 (for Newer), or L on page 1 and the others on page 2
    (for Older: the state the fixed links lead to, loaded by URL so that the Older check does not depend on Newer)."""
    if text == "Newer":
        qs = "pendingPage=2&activePage=2&endedPage=2"
    else:
        qs = "&".join(f"{PARAM[o]}={1 if o == L else 2}" for o in LISTS)
    s.go(path + "?" + qs)
    return qs, lists_of(s)


def click_pager(s, path, L, text):
    """Clicks L's Newer/Older from its start state and reads the result. A row that moves list between loading the
    start state and the click (another unit's request expiring) changes a page's rows; a second attempt tells that apart
    from a dropped parameter."""
    others = [o for o in LISTS if o != L]
    result = {}
    for attempt in (1, 2):
        qs, start = start_state(s, path, L, text)
        href = link_of(start[L], text)
        if href is None:
            return {"href": None, "start": qs, "start_footer": (start[L] or {}).get("footer")}
        nav = s.page.locator(f'table[data-batch-control-list="{L}"]').locator(
            "xpath=following-sibling::*[@role='navigation'][1]").locator("a", has_text=re.compile(rf"^{text}$"))
        with s.page.expect_navigation(timeout=20000):
            nav.first.click()
        s.page.wait_for_load_state("load")
        after = lists_of(s)
        result = {"start": qs, "href": href, "url": s.page.url.replace(lib.BASE, ""), "query": query_of(s.page.url),
                  "own_page": page_no(after[L]), "others_pages": {o: page_no(after[o]) for o in others},
                  "same_rows": all((after[o] or {}).get("ids") == (start[o] or {}).get("ids") for o in others),
                  "own_changed": (after[L] or {}).get("ids") != (start[L] or {}).get("ids"), "attempt": attempt}
        if result["same_rows"]:
            break
    return result


def sec_P():
    for page, path in PAGES.items():
        s = Session(VIEWER, fresh=True)
        _, all2 = start_state(s, path, None, "Newer")
        check("P", f"guard: {page}: ?pendingPage=2&activePage=2&endedPage=2 shows page 2 of every list",
              all(page_no(all2[L]) == 2 for L in LISTS), footers={L: (all2[L] or {}).get("footer") for L in LISTS})
        for L in LISTS:
            others = [o for o in LISTS if o != L]
            for text, want in (("Newer", 1), ("Older", 2)):
                res = click_pager(s, path, L, text)
                s.shot([f'table[data-batch-control-list="{o}"]' for o in LISTS], f"R25-45-{page}-{L}-{text.lower()}")
                q = res.get("query") or {}
                clicked = res.get("href") is not None
                check("P", f"#45 contract: {page}: {L}'s {text} from ?{res.get('start')} keeps "
                      + " and ".join(f"{PARAM[o]}=2" for o in others) + " in the URL",
                      clicked and all(q.get(PARAM[o]) == "2" for o in others), href=res.get("href"), url=res.get("url"))
                check("P", f"#45 contract: {page}: after {L}'s {text} the " + " and ".join(others)
                      + " lists still show their page 2 (footer and the same rows)",
                      clicked and all(res["others_pages"][o] == 2 for o in others) and res["same_rows"],
                      others_pages=res.get("others_pages"), same_rows=res.get("same_rows"), attempt=res.get("attempt"))
                check("P", f"guard: {page}: {L}'s {text} sets {PARAM[L]}={want} and the {L} list shows page {want}",
                      clicked and q.get(PARAM[L]) == str(want) and res["own_page"] == want and res["own_changed"],
                      own_param=q.get(PARAM[L]), own_page=res.get("own_page"), own_changed=res.get("own_changed"),
                      start_footer=res.get("start_footer"))
        s.done()


def sec_X():
    probes = {
        "markup in another parameter": "pendingPage=2&activePage=2&endedPage=2&r25x=" + quote('"><img id=r25xss src=x>'),
        "markup in a list's own parameter": "pendingPage=" + quote('2"><b id=r25xss2>x</b>') + "&activePage=2&endedPage=2",
        "& and = in a page parameter": "pendingPage=2&activePage=" + quote("2&endedPage=9") + "&endedPage=2",
    }
    for page, path in PAGES.items():
        s = Session(VIEWER, fresh=True)
        for what, qs in probes.items():
            r = s.go(path + "?" + qs)
            sent = dict(parse_qsl(qs))
            injected = s.page.locator("#r25xss, #r25xss2").count()
            views = lists_of(s)
            links = [(L, lk) for L in LISTS for lk in (views[L] or {}).get("links", [])]
            texts = sorted({lk["text"] for _, lk in links})
            carried = {}  # a value a link carries for a parameter other than its own list's
            for L, lk in links:
                for k, v in parse_qsl(urlsplit(lk["href"] or "").query, keep_blank_values=True):
                    if k in sent and k != PARAM[L]:
                        carried.setdefault(k, set()).add(v)
            # A carried value is the one sent; for a page parameter whose value is no page number, the page the list
            # shows instead (a link may carry the normalised page) is accepted too.
            shown = {PARAM[L]: str(page_no(views[L])) for L in LISTS if views[L]}
            bad = {k: sorted(v) for k, v in carried.items()
                   if not v <= ({sent[k]} | ({shown[k]} if k in shown and not sent[k].isdigit() else set()))}
            check("X", f"escaping guard: {page} with {what}: no injected element, the pager links are intact "
                  "and every value a link carries round-trips unchanged", (r.status if r else None) == 200
                  and injected == 0 and set(texts) <= {"Newer", "Older"} and bool(links) and not bad,
                  status=r.status if r else None, injected=injected, link_texts=texts, mismatched=bad,
                  hrefs=[lk["href"] for _, lk in links][:6])
        s.shot("#main-panel", f"R25-45-{page}-escaping")
        s.done()


def sec_S():
    s = Session(VIEWER, fresh=True)
    s.go(PAGES["requests"] + "?pendingPage=2&activePage=2&endedPage=2")
    s.shot("#main-panel", "R25-45-summary-1-all-page-2")
    view = lists_of(s)
    href = link_of(view["ended"], "Newer")
    if href:
        nav = s.page.locator('table[data-batch-control-list="ended"]').locator(
            "xpath=following-sibling::*[@role='navigation'][1]").locator("a", has_text=re.compile("^Newer$"))
        with s.page.expect_navigation(timeout=20000):
            nav.first.click()
        s.page.wait_for_load_state("load")
    s.shot("#main-panel", "R25-45-summary-2-after-ended-previous")
    note("S", "screen: the Requests page after the Ended list's Newer from the all-page-2 state",
         url=s.page.url.replace(lib.BASE, ""), footers={L: (lists_of(s)[L] or {}).get("footer") for L in LISTS})
    s.done()


def cleanup():
    t0 = time.monotonic()
    swept = sweep()
    note("cleanup", "swept r25-pager's pending requests, windows and queued runs", swept=swept,
         seconds=round(time.monotonic() - t0, 1))


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "P": sec_P, "X": sec_X, "S": sec_S}, cleanup=cleanup)
