"""e2e-19: the run gate on a real Jenkins, every manual path and the approved run (SPEC item 6; e2e-19 gap audit G-01..G-06).

usage: python gate.py [BCPARXM]   rows: out/gate.jsonl, shots: R19-GATE-*.png   (run r19/arrange.py first)
B  job page of the approval-required r19-gate: requester sees "Request Run" and no core Build entry, and the notice
   "manual runs of this job need an approved run request" with a link to the job's request form (SPEC 6 "Build Now is
   replaced by Request Run", D-60 notice, DEF-01/02); nobc (Item/Build, no Batch Control permission) sees the notice
   without the link and is told whom to ask
C  M12 (gap audit 2026-10, SPEC 6 "Build Now is replaced by Request Run"), section B on the classic job page: with the
   requester's new job page flag set to false (the setting the classic passes of the suite use), the page is the classic
   one (core's side panel #tasks) and its side panel offers "Request Run" (linking the job's request form) and no core
   "Build Now" / "Build with Parameters", nor anywhere else on the page; with the flag true (the new job page) the page
   has no such side panel and still offers Request Run and no core Build entry; the flag is put back afterwards
P  every other manual path is refused and nothing reaches the queue (SPEC 6 first acceptance line): as requester
   (Item/Build) POST buildWithParameters and POST build with a json payload (REST, basic auth), the CLI `build`
   command, the job's build token anonymously on buildWithParameters and through build-token-root's /buildByToken;
   each answer says that an approved request is needed; no queue item, no build; the token attempts are recorded as
   REMOTE_RUN_BLOCKED change records (S-14)
A  an approved request runs exactly once with exactly the requested values (SPEC 4, 6): requester submits DATE, MODE
   and SECRET, approver-1 presses Approve in the browser; one build starts, it prints the requested DATE/MODE and the
   sha256 of the original secret; the build page shows the cause "Approved batch run request <id> (requested by
   requester, approved by approver-1)" (SPEC 4 "Cause and build Action"); the request page says EXECUTED and links
   the build (SPEC 10 "linked to the request detail"); no second build appears; no MARKER_REUSE_BLOCKED record for it
R  Pipeline Replay of an approved build of r19-gpipe by the administrator in the browser: the refusal page explains
   it and links the request form, nothing is queued, and a TRIGGER_BLOCKED record names admin and the build (SPEC 6
   Replay, D-51a)
X  the rebuild plugin's Rebuild of the approved r19-gate build by requester in the browser: refused, nothing queued,
   a TRIGGER_BLOCKED record names requester and the re-run build (SPEC 6 "every refused retry, rebuild ... recorded",
   D-51; cross-plugin #36)
M  the consumed approval marker of A presented again (script console, impersonating nobc, as e2e/scripts/
   rest-marker-reuse.sh does: no HTTP endpoint hands a marker out): refused, no build, one MARKER_REUSE_BLOCKED record
   naming nobc (not the requester) and the request id, shown on the Changes page and in changes.csv (D-30)"""
import json
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, check, note, text_of, J, run_sections, run_req, queue_items, build_count, next_build,
                 wait_build, wait_executed, browser_decide, cli, changes_mark, changes_since, sha, BASE)  # noqa: E402

lib.LOGNAME[0] = "gate"
WANT = sys.argv[1] if len(sys.argv) > 1 else "BCPARXM"
JOB = "r19-gate"
SECRET = "r19-Gate-Secret-3"
STATE = {}
APPROVAL_TEXT = "manual runs of this job need an approved run request"


def nothing_queued(sec, what, nb0):
    time.sleep(3)
    q = queue_items(JOB)
    nb = next_build(JOB)
    return check(sec, f"{what}: nothing queued, no build", not q and nb == nb0, queue=q, next_build=nb, before=nb0)


def sec_B():
    s = Session("requester")
    s.go(J(JOB) + "/")
    t = s.text()
    links = s.page.locator("a[href]").evaluate_all("els => els.map(e => [e.textContent.trim(), e.getAttribute('href')])")
    s.shot("#main-panel", "R19-GATE-B-01-requester-job")
    body = s.page.locator("body").inner_text()
    core_build = re.findall(r"\bBuild (?:Now|with Parameters)\b", body)
    check("B", "requester: the job page offers Request Run and no core Build entry (SPEC 6)",
          "Request Run" in body and not core_build, core_build=core_build[:3])
    notice_link = [l for l in links if (l[1] or "").rstrip("/").endswith(J(JOB) + "/batch-control") and l[0] != "Request Run"]
    check("B", "requester: the notice says manual runs need an approved run request and links the request form",
          APPROVAL_TEXT in t and bool(notice_link), notice_link=notice_link[:2])
    s.done()
    s = Session("nobc")
    s.go(J(JOB) + "/")
    t = s.text()
    links = s.page.locator("#main-panel a[href]").evaluate_all("els => els.map(e => e.getAttribute('href'))")
    s.shot("#main-panel", "R19-GATE-B-02-nobc-job")
    check("B", "nobc: the notice is shown without a link to the request form and says whom to ask (DEF-02)",
          APPROVAL_TEXT in t and not any("batch-control" in (h or "") for h in links)
          and ("Ask a user who may submit run requests" in t or "administrator" in t),
          batch_control_links=[h for h in links if "batch-control" in (h or "")][:3])
    s.done()


def job_page_flag(user, value=None):
    """Arrangement (as r14/set_flag.py): the new job page experimental flag of `user` (true = new page, false = classic),
    set when `value` is given; returns its value afterwards."""
    setter = ("def m = new HashMap(); m.put('new-job-page.flag', " + json.dumps(value) + ")\n"
              "u.addProperty(new UserExperimentalFlagsProperty(m)); u.save()\n") if value is not None else ""
    return gv("import jenkins.model.experimentalflags.*\ndef u = hudson.model.User.getById(" + json.dumps(user) + ", true)\n"
              + setter + "return String.valueOf(new NewJobPageUserExperimentalFlag().getFlagValue(u))")


SIDE_JS = """() => { const n = t => (t || '').replace(/\\s+/g, ' ').trim();
  const tasks = document.querySelector('#side-panel #tasks, #tasks');
  return {tasks: !!tasks, side: tasks ? [...tasks.querySelectorAll('a, button')].filter(e => e.getClientRects().length)
    .map(e => [n(e.innerText || e.getAttribute('aria-label') || ''), e.getAttribute('href')]) : []}; }"""


def sec_C():
    before = job_page_flag("requester")
    try:
        for value, ui in (("false", "classic"), ("true", "new")):
            now = job_page_flag("requester", value)
            s = Session("requester")
            s.go(J(JOB) + "/")
            side = s.page.evaluate(SIDE_JS)
            body = s.page.locator("body").inner_text()
            s.shot("body", f"R19-GATE-C-0{1 if ui == 'classic' else 2}-requester-job-{ui}")
            s.done()
            core_build = re.findall(r"\bBuild (?:Now|with Parameters)\b", body)
            labels = [x[0] for x in side["side"]]
            if ui == "classic":
                request = [x for x in side["side"] if x[0] == "Request Run"]
                check("C", "M12 (SPEC 6): classic job page (requester's flag false): core's side panel offers 'Request Run', "
                      "linking the job's request form, and no core 'Build Now' / 'Build with Parameters', nor does the rest "
                      "of the page", now == "false" and side["tasks"] and len(request) == 1
                      and (request[0][1] or "").rstrip("/").endswith(J(JOB) + "/batch-control")
                      and not [x for x in labels if re.match(r"Build (Now|with Parameters)\b", x)] and not core_build,
                      flag=now, side=side["side"][:14], request=request, core_build=core_build[:3])
            else:
                check("C", "guard: new job page (requester's flag true): no classic side panel, the page still offers "
                      "Request Run and no core Build entry", now == "true" and not side["side"] and "Request Run" in body
                      and not core_build, flag=now, side=side["side"][:6], core_build=core_build[:3])
    finally:
        back = job_page_flag("requester", before)
        note("C", "the requester's new job page flag put back", before=before, after=back)


def sec_P():
    nb0 = next_build(JOB)
    mark = changes_mark()
    # 1. buildWithParameters, REST as requester (Item/Build)
    r = api("requester", J(JOB) + "/buildWithParameters", "POST", data={"DATE": "2026-02-02", "MODE": "full", "SECRET": "x"})
    body = text_of(r.text)
    loc = r.headers.get("Location", "")
    check("P", "POST buildWithParameters (requester, REST): refused with guidance to an approved request",
          r.status_code != 201 and ("approv" in body.lower() or "batch-control" in loc),
          status=r.status_code, location=loc.replace(BASE, ""), body=body[:240])
    nothing_queued("P", "buildWithParameters", nb0)
    # 2. build with a json payload (what the classic build form posts)
    payload = {"parameter": [{"name": "DATE", "value": "2026-02-03"}, {"name": "MODE", "value": "full"}, {"name": "SECRET", "value": "y"}]}
    r = api("requester", J(JOB) + "/build", "POST", data={"json": json.dumps(payload)})
    body = text_of(r.text)
    loc = r.headers.get("Location", "")
    check("P", "POST build with parameters (requester): refused with guidance",
          r.status_code != 201 and ("approv" in body.lower() or "batch-control" in loc),
          status=r.status_code, location=loc.replace(BASE, ""), body=body[:240])
    nothing_queued("P", "build", nb0)
    # 3. CLI build
    rc, out = cli("requester", "build", JOB, "-p", "DATE=2026-02-04", "-p", "MODE=full", "-p", "SECRET=z")
    check("P", "CLI build (requester): non-zero exit with a one-line error naming the approved request",
          rc != 0 and "approv" in out.lower() and "Exception" not in out.split("ERROR:")[0][-200:] and "\tat " not in out,
          exit=rc, output=out.strip()[:300])
    nothing_queued("P", "CLI build", nb0)
    # 4. build token, anonymously, on the job's endpoint and through build-token-root
    import requests
    # core's own endpoint needs Overall/Read (no anonymous read here): nobc presents the token; build-token-root's
    # endpoint is anonymous
    r1 = api("nobc", J(JOB) + "/buildWithParameters?token=r19-tok&DATE=2026-02-05", "POST")
    r2 = requests.post(BASE + "/buildByToken/buildWithParameters", params={"job": JOB, "token": "r19-tok", "DATE": "2026-02-06"},
                       allow_redirects=False)
    for name, r in (("job buildWithParameters?token (nobc)", r1), ("buildByToken/buildWithParameters", r2)):
        check("P", f"{name} (valid token): refused with a plain message",
              r.status_code >= 400 and "approved batch-control run request" in r.text,
              status=r.status_code, body=text_of(r.text)[:240])
    nothing_queued("P", "build token", nb0)
    rec = changes_since(mark, lambda x: x.get("type") == "REMOTE_RUN_BLOCKED" and x.get("target") == JOB)
    check("P", "the token attempts are recorded as REMOTE_RUN_BLOCKED change records (S-14)", len(rec) >= 1,
          records=[(x.get("user"), (x.get("detail") or "")[:120]) for x in rec][:3])


def sec_A():
    nb0 = next_build(JOB)
    n0 = build_count(JOB)
    st, rid, r = run_req("requester", JOB, "e2e-19 approved run, exact values",
                         params={"DATE": "2026-03-03", "MODE": "partial", "SECRET": SECRET})
    check("A", "requester submits a run request with DATE, MODE and SECRET", st == 302 and rid, status=st, body=text_of(r.text)[:200])
    if not rid:
        return
    STATE["rid"] = rid
    s, t = browser_decide("approver-1", rid, "approve", "e2e-19 go", "R19-GATE-A-01-approved")
    s.done()
    status, detail = wait_executed(rid)
    check("A", "the request is EXECUTED after the approval in the browser", status == "EXECUTED", status=status)
    result, console = wait_build(JOB, nb0)
    STATE["build"] = nb0
    vals = dict(re.findall(r"^(DATE|MODE|SECRET_SHA)=(.*)$", console, re.M))
    check("A", "the approved build received exactly the requested values, the original secret included (SPEC 4, 5)",
          result == "SUCCESS" and vals.get("DATE") == "2026-03-03" and vals.get("MODE") == "partial"
          and vals.get("SECRET_SHA") == sha(SECRET.encode()), result=result, values={k: v[:16] for k, v in vals.items()})
    s = Session("approver-1")
    s.go(J(JOB) + f"/{nb0}/")
    bt = s.text()
    s.shot("#main-panel", "R19-GATE-A-02-build-page")
    s.done()
    want = f"Approved batch run request {rid} (requested by requester, approved by approver-1)"
    check("A", "the build page names the request, the requester and the approver (SPEC 4 Cause)", want in bt, want=want,
          found=re.findall(r"Approved batch run request[^\n]*", bt)[:2])
    s = Session("requester")
    s.go(f"/batch-control/requests/{rid}/")
    rt = s.text()
    href = s.page.locator("tr:has(th:text-is('Executed run')) a").evaluate_all("els => els.map(e => e.getAttribute('href'))")
    s.shot("#main-panel", "R19-GATE-A-03-request-executed")
    s.done()
    check("A", "the request page links the executed build (SPEC 10)",
          any((h or "").rstrip("/").endswith(J(JOB) + f"/{nb0}") for h in href), hrefs=href)
    time.sleep(15)
    check("A", "exactly one build was queued for the approval (SPEC 6, D-23)", build_count(JOB) == n0 + 1 and not queue_items(JOB),
          builds=build_count(JOB), before=n0)
    reuse = [x for x in lib.changes(lambda x: x.get("type") == "MARKER_REUSE_BLOCKED") if rid in json.dumps(x)]
    check("A", "an ordinary approved run writes no MARKER_REUSE_BLOCKED record (D-30)", not reuse, records=reuse[:2])


def approved_pipeline_build():
    job = "r19-gpipe"
    ok = gv(f"return jenkins.model.Jenkins.get().getItemByFullName('{job}').getLastSuccessfulBuild()?.number ?: ''")
    if ok:
        return int(ok)
    nb = next_build(job)
    st, rid, _ = run_req("requester", job, "e2e-19 pipeline run for Replay", params={"X": "two"})
    assert rid, ("pipeline request", st)
    assert lib.decide("approver-1", "requests", rid, "approve") in (200, 302)
    result, _ = wait_build(job, nb)
    assert result == "SUCCESS", ("pipeline build", result)
    return nb


def sec_R():
    job = "r19-gpipe"
    num = approved_pipeline_build()
    nb0 = next_build(job)
    mark = changes_mark()
    s = Session("admin")
    s.go(J(job) + f"/{num}/replay/")
    s.shot("#main-panel", "R19-GATE-R-01-replay-page")
    btn = s.page.locator("button[name=Submit], button:has-text('Run'), input[type=submit]").first
    with s.page.expect_navigation(timeout=20000):
        btn.click()
    s.page.wait_for_load_state("load")
    t = s.text()
    links = s.page.locator("#main-panel a[href]").evaluate_all("els => els.map(e => e.getAttribute('href'))")
    s.shot("#main-panel", "R19-GATE-R-02-replay-refused")
    s.done()
    check("R", "Replay (admin, browser): the refusal page explains it and links the job's request form (SPEC 6 guidance)",
          "only runs through an approved Batch Control run request" in t and any((h or "").rstrip("/").endswith(J(job) + "/batch-control") for h in links),
          text=t[:300], links=[h for h in links if "batch-control" in (h or "")][:3])
    time.sleep(3)
    check("R", "Replay: nothing queued, no build", next_build(job) == nb0 and not queue_items(job), next_build=next_build(job))
    rec = changes_since(mark, lambda x: x.get("type") == "TRIGGER_BLOCKED" and x.get("target") == job)
    check("R", "a TRIGGER_BLOCKED record names admin and the replayed build (SPEC 6 Replay, D-51a)",
          any(x.get("user") == "admin" and f"#{num}" in (x.get("detail") or "") and "Replay" in (x.get("detail") or "") for x in rec),
          records=[(x.get("user"), (x.get("detail") or "")[:160]) for x in rec][:3])


def sec_X():
    num = STATE.get("build") or int(gv(f"return jenkins.model.Jenkins.get().getItemByFullName('{JOB}').getLastSuccessfulBuild()?.number ?: 0"))
    if not num:
        check("X", "an approved build of r19-gate exists (section A)", False)
        return
    nb0 = next_build(JOB)
    mark = changes_mark()
    s = Session("requester")
    s.go(J(JOB) + f"/{num}/")
    body = s.page.locator("body").inner_text()
    hrefs = s.page.locator("a[href]").evaluate_all("els => els.map(e => e.getAttribute('href'))")
    check("X", "the build page does not link the rebuild plugin's Rebuild on an approval-required job (DEF-25 design)",
          not any("/rebuild" in (h or "") for h in hrefs), rebuild_links=[h for h in hrefs if "/rebuild" in (h or "")][:3])
    s.go(J(JOB) + f"/{num}/rebuild/parameterized")
    s.shot("#main-panel", "R19-GATE-X-01-rebuild-form")
    btn = s.page.locator("form[action=configSubmit] button[name=Submit]").first
    status = None
    try:
        with s.page.expect_navigation(timeout=20000) as nav:
            btn.click()
        status = nav.value.status if nav.value else None
    except Exception:  # noqa
        pass
    s.page.wait_for_load_state("load")
    t = s.text()
    s.shot("#main-panel", "R19-GATE-X-02-rebuild-refused")
    s.done()
    note("X", "landing page of the refused Rebuild (direct URL rebuild/parameterized)", status=status, text=t[:300])
    time.sleep(3)
    check("X", "Rebuild of an approved build: nothing queued, no build", next_build(JOB) == nb0 and not queue_items(JOB),
          next_build=next_build(JOB), before=nb0)
    rec = changes_since(mark, lambda x: x.get("type") in ("TRIGGER_BLOCKED", "MARKER_REUSE_BLOCKED") and x.get("target") == JOB)
    check("X", "the refused Rebuild is recorded, naming requester and the re-run build (SPEC 6, D-51)",
          any(x.get("user") == "requester" and f"#{num}" in (x.get("detail") or "") for x in rec),
          records=[(x.get("type"), x.get("user"), (x.get("detail") or "")[:160]) for x in rec][:3])
    check("X", "the refusal tells the person to submit a new run request",
          "approv" in t.lower() or "run request" in t.lower(), text=t[:240])


def sec_M():
    rid = STATE.get("rid")
    if not rid:
        check("M", "an executed request from section A exists", False)
        return
    nb0 = next_build(JOB)
    mark = changes_mark()
    out = gv(f"""import hudson.model.*
import hudson.security.ACL
def j = jenkins.model.Jenkins.get()
def marker = j.pluginManager.uberClassLoader.loadClass('io.jenkins.plugins.batchcontrol.queue.ApprovedRunAction').getConstructor(String).newInstance('{rid}')
def ctx = ACL.as2(User.getById('nobc', false).impersonate2())
try {{ return 'scheduled=' + (Queue.getInstance().schedule2(j.getItemByFullName('{JOB}'), 0, [marker]).isAccepted()) }}
catch (Throwable e) {{ return 'refused: ' + e.class.simpleName + ': ' + e.message }} finally {{ ctx.close() }}""")
    time.sleep(3)
    check("M", "a consumed approval marker presented again is refused, no build (D-23)",
          "scheduled=true" not in out and next_build(JOB) == nb0 and not queue_items(JOB), result=out[:200])
    rec = changes_since(mark, lambda x: x.get("type") == "MARKER_REUSE_BLOCKED")
    check("M", "one MARKER_REUSE_BLOCKED record names nobc (the account that tried) and identifies the request and job (D-30)",
          len(rec) == 1 and rec[0].get("user") == "nobc" and rid in json.dumps(rec[0]) and JOB in json.dumps(rec[0]),
          records=rec[:2])
    s = Session("approver-1")
    s.go("/batch-control/changes/")
    t = s.text()
    s.shot("#main-panel", "R19-GATE-M-01-changes")
    s.done()
    check("M", "the Changes page lists the MARKER_REUSE_BLOCKED record with nobc", "MARKER_REUSE_BLOCKED" in t and "nobc" in t)
    csv = api("approver-1", "/batch-control/history/changes.csv")
    rows = [l for l in csv.text.splitlines() if "MARKER_REUSE_BLOCKED" in l and "nobc" in l]
    check("M", "changes.csv has the record (D-30)", csv.status_code == 200 and rows, status=csv.status_code, rows=rows[:1])


run_sections(WANT, {"B": sec_B, "C": sec_C, "P": sec_P, "A": sec_A, "R": sec_R, "X": sec_X, "M": sec_M})
