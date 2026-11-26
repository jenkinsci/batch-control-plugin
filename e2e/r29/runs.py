"""e2e-29: run approval end to end (SPEC 5, 6, 6a, 7), the rows of the e2e-29 coverage inventory that CI asserted only
in part (WEAK) or not at all (GAP).

usage: python runs.py [ALQJXCDW]   rows: out/runs.jsonl, shots: screenshots/run-29/R29-R-*.png
requester files every run request over the plugin's own submit endpoint (the json the Request Run form posts); the
designated approvers decide on the request page in the browser or with the endpoint the page posts. Jobs at the root
(r29-*) plus team/r29-x1, re-created by A. Waits: one approved build of 120 s (L), the approved run held in the queue
for at least 130 s, i.e. past approvedRunTimeoutMinutes (1) and two passes of the periodic work (Q..W), and one pass
after its build (W).

A  arrangement: both switches on, the Batch Control strategy, approvedRunTimeoutMinutes 1 (casc/jenkins.yaml); the
   account r29-dual (Request, RequestGrant, Approve; a listed approver for this run, removed again in the cleanup); the
   jobs fresh (approval required, a string parameter P); the built-in node has no label r29-nowhere; approver-disc holds
   Item/Discover but not Item/Read on team/r29-x1 and nothing on r29-x1-none (premise of X).
L  [T7] D-27: an approved build of r29-long (sleep 120) is never aborted by what happens while it runs: w29's one-minute
   CONFIGURE window on the job expires; the administrator turns run control off and on again on the configuration
   page; the job's configuration is saved with another description and approvalRequired off, then on again; the
   administrator turns change control off and on again. Assert: the build was running right after each of these
   (sampled), ends SUCCESS with its last line printed and was not aborted, and its duration covers the sleep. Guards:
   each event happened during the build (CONFIG_TOGGLE records of both switches off and on, written between the
   build's start and end; the window's expiresAt between them; the stored description and property changed), and
   blocking still happens at the queue entry afterwards (requester's direct Build POST refused, nothing queued).
Q  premise for D and W: r29-q-run and r29-q-del (bound to the label r29-nowhere, which no node has) each get an approved
   run request; r29-q-del is activated first (requester's ACTIVATE approved). Each approved run waits in the queue as
   exactly one item carrying its request.
J  [R4] a REJECTED and a CANCELLED request never run: approver-1 rejects one in the browser and the requester cancels the
   other; afterwards approver-1's approve POST of each is refused and the status stays; no queue item and no build of
   the job carry either (watched 8 s). A listed approver who is not an administrator (r29-dual) may not designate
   themself on their own run request, alone or next to approver-1 (refused, nothing stored). Guard: naming approver-1
   is stored PENDING; r29-dual's approver change of it to itself is refused (approvers unchanged) and its own approve
   is refused (PENDING).
X  [X1] SPEC 5: an approved request is submitted even when the approver holds only Item/Discover (team/r29-x1, approved
   in the browser) or no permission (r29-x1-none, approved with the page's endpoint) on the job: exactly one build each,
   with exactly the requested P, EXECUTED. Guard: approver-disc cannot read either job (premise in A) and its GET of
   the job page is refused.
C  [R5] races (SPEC 7 R-5): three rounds of approver-1 and approver-2 (both designated) approving the same request at
   once (a barrier, both POSTs in flight together): exactly one answer is the success redirect and the other a refusal
   (4xx, never 5xx), the stored decidedBy is the one that won, exactly one build with that round's P, EXECUTED, no
   second build. Three rounds of approver-1's approve against the requester's cancel: exactly one succeeds and the end
   state is not mixed: CANCELLED with no executedRunId, no queue item and no build, or EXECUTED with exactly one build.
   Guard: approved one after the other, the second approve is refused and one build runs.
D  [R12][X2] deleting r29-q-del (the administrator, its Delete page) while its approved run waits in the queue: the
   request is INVALIDATED with a reason naming the deletion (stored and shown on its page as "Why it was invalidated";
   the approver's decision comment stays) and its queue item is gone; approving it again is refused.
   The job re-created under the name starts not activated (it was activated before the deletion).
W  [R11] the approved run of r29-q-run, queued since Q (at least 130 s: longer than approvedRunTimeoutMinutes and two
   passes of the periodic work), is still APPROVED with exactly the same one queue item and no build. Once the built-in
   node gets the label r29-nowhere it builds exactly once, with the requested P, and the request is EXECUTED naming that
   build; no second build or queue item after one more pass (65 s). [R12][X2] the re-created r29-q-del never runs the
   deleted job's request, also with the label present.
cleanup: the node label, the approver list and both switches put back; pending requests of requester and r29-dual on
r29-* cancelled; w29's open windows on r29-* revoked."""
import re
import sys
import threading
import time

import requests

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, check, J, decide, text_of, exists, string_p, fs_xml, fresh_job, run_req,  # noqa: E402
                 act_req, activation, stored_request, runs_of_request, build_params, queue_items, next_build,
                 records_mark, records_since, has_permission, DUAL)

lib.LOGNAME[0] = "runs"
LONG, REJ, RACE = "r29-long", "r29-rej", "r29-race"
X1D, X1N = "team/r29-x1", "r29-x1-none"
QRUN, QDEL = "r29-q-run", "r29-q-del"
LABEL = "r29-nowhere"
SLEEP = 120
HOLD_S = 130
CONFIG = "/manage/batch-control-configuration/"
S = {}


def pxml(description, shell, label=""):
    return fs_xml(description=description, shell=shell, label=label, params=string_p("P", "default"))


def ok(code):
    return code in (200, 302)


def build_state(job, number):
    r = api("admin", J(job) + f"/{number}/api/json?tree=building,result,duration,timestamp")
    return r.json() if r.status_code == 200 else {}


def console(job, number):
    r = api("admin", J(job) + f"/{number}/consoleText")
    return r.text if r.status_code == 200 else ""


def sess(user):
    """A REST session of `user` with basic auth and a crumb (both racers' POSTs leave without a crumb round trip)."""
    s = requests.Session()
    s.auth = (user, lib.pw(user))
    c = s.get(lib.BASE + "/crumbIssuer/api/json", timeout=30).json()
    s.headers[c["crumbRequestField"]] = c["crumb"]
    return s


def file_run(job, p, reason, approvers=("approver-1",)):
    st, rid, r = run_req("requester", job, reason, approvers=approvers, params={"P": p})
    assert rid, (job, st, text_of(r.text)[-300:])
    return rid


def end_state(rid, timeout=90):
    return lib.wait_status(rid, ("EXECUTED", "CANCELLED", "REJECTED", "EXPIRED", "INVALIDATED"), timeout=timeout)


def set_switches(run, change, shot=None):
    """The administrator sets the two switches on the Batch Control configuration page and saves, as a person does
    (clicking the toggle labels; r18/lib.py set_switches). Returns the save's HTTP status."""
    s = Session("admin", fresh=True)
    s.go(CONFIG)
    for name, want in (("runControlEnabled", run), ("changeControlEnabled", change)):
        if s.page.locator(f"input[name='_.{name}']").is_checked() != want:
            s.page.locator(f"input[name='_.{name}'] + label").click()
    if shot:
        s.shot("#main-panel", shot)
    with s.page.expect_navigation(timeout=30000) as nav:
        s.page.click("button[name=Submit]")
    s.page.wait_for_load_state("load")
    status = nav.value.status if nav.value else None
    s.done()
    return status


def switches():
    return lib.config("runControlEnabled", "changeControlEnabled")


# ---------------------------------------------------------------- A
def sec_A():
    S["approvers0"] = lib.approvers()
    S["label0"] = lib.node_labels()
    S["dual"] = lib.ensure_account(DUAL, ["Request", "RequestGrant", "Approve"])
    if DUAL not in S["approvers0"]:
        lib.set_approvers(S["approvers0"] + [DUAL])
    left = {u: lib.cancel_pending(u, "r29-") for u in ("requester", DUAL)}
    left["team"] = lib.cancel_pending("requester", X1D)
    left["windows"] = lib.revoke_open(lib.HOLDER, "r29-")
    lib.ensure_account(lib.HOLDER, ["RequestGrant"])
    props = {}
    props[LONG] = fresh_job(LONG, fs_xml(description="e2e-29 T7 long build",
                                         shell=f"echo r29-long-start; sleep {SLEEP}; echo r29-long-done"))
    for job in (REJ, RACE, X1D, X1N):
        props[job] = fresh_job(job, pxml(f"e2e-29 {job}", 'echo "r29 P=$P"'))
    for job in (QRUN, QDEL):
        props[job] = fresh_job(job, pxml(f"e2e-29 {job}", 'echo "r29 P=$P"', label=LABEL))
    cfg = lib.config("runControlEnabled", "changeControlEnabled", "approvedRunTimeoutMinutes")
    disc = {"discover team/r29-x1": has_permission("approver-disc", X1D, "hudson.model.Item.DISCOVER"),
            "read team/r29-x1": has_permission("approver-disc", X1D, "hudson.model.Item.READ"),
            "discover r29-x1-none": has_permission("approver-disc", X1N, "hudson.model.Item.DISCOVER"),
            "read r29-x1-none": has_permission("approver-disc", X1N, "hudson.model.Item.READ")}
    check("A", "arrangement: both switches on, approvedRunTimeoutMinutes 1; r29-dual on the approver list next to "
          "approver-1, approver-2 and approver-disc; the jobs fresh with approvalRequired on; no node has the label "
          "r29-nowhere; approver-disc holds Item/Discover but not Item/Read on team/r29-x1 and nothing on r29-x1-none",
          cfg["runControlEnabled"] and cfg["changeControlEnabled"] and int(cfg["approvedRunTimeoutMinutes"]) == 1
          and {"approver-1", "approver-2", "approver-disc", DUAL} <= set(lib.approvers())
          and all("approvalRequired=true" in v for v in props.values()) and LABEL not in (S["label0"] or "").split()
          and disc == {"discover team/r29-x1": True, "read team/r29-x1": False, "discover r29-x1-none": False,
                       "read r29-x1-none": False},
          config=cfg, approvers=lib.approvers(), label=S["label0"], approver_disc=disc, leftovers=left, dual=S["dual"])


# ---------------------------------------------------------------- L: a running build is not aborted (T7)
def sec_L():
    wid, gw = lib.open_window(lib.HOLDER, LONG, ["CONFIGURE"], 1, "e2e-29 T7 window that expires mid-build")
    mark = records_mark()
    nb = next_build(LONG)
    rid = file_run(LONG, "long", "e2e-29 T7 long build")
    decide("approver-1", "requests", rid, "approve", "e2e-29 T7")
    started = lib.wait_until(lambda: build_state(LONG, nb).get("building") is True, timeout=90, step=1)
    seen = {}

    def sample(what):
        seen[what] = build_state(LONG, nb).get("building")
    if not started:
        check("L", "[T7] premise: the approved build of r29-long starts", False, request=rid, state=stored_request(rid))
        return
    seen["run off"] = set_switches(False, True, shot="R29-R-L-01-run-control-off")
    sample("after run control off")
    seen["run on"] = set_switches(True, True)
    sample("after run control on")
    d1 = "e2e-29 T7 configuration saved while the build runs"
    seen["config"] = api("admin", J(LONG) + "/config.xml", "POST", headers=lib.H_XML, data=fs_xml(
        description=d1, shell=f"echo r29-long-start; sleep {SLEEP}; echo r29-long-done", approval=False, timer=False,
        upstream=False).encode()).status_code
    seen["approval off stored"] = re.search(r"<approvalRequired>(\w+)</approvalRequired>",
                                            api("admin", J(LONG) + "/config.xml").text).group(1)
    sample("after approvalRequired off")
    seen["config back"] = api("admin", J(LONG) + "/config.xml", "POST", headers=lib.H_XML, data=fs_xml(
        description=d1, shell=f"echo r29-long-start; sleep {SLEEP}; echo r29-long-done", approval=True, timer=False,
        upstream=False).encode()).status_code
    sample("after approvalRequired on")
    exp = lib.wait_until(lambda: lib.now_ms() > gw["expiresAtMillis"] + 1000 and lib.now_ms(), timeout=120, step=1)
    seen["holder after expiry"] = api(lib.HOLDER, J(LONG) + "/configure").status_code
    sample("after the window expired")
    seen["change off"] = set_switches(True, False)
    sample("after change control off")
    seen["change on"] = set_switches(True, True, shot="R29-R-L-02-both-on")
    sample("after change control on")
    lib.shot_page("admin", J(LONG) + f"/{nb}/", "R29-R-L-03-build-running")
    res, _ = lib.wait_build(LONG, nb, SLEEP + 120)
    b = build_state(LONG, nb)
    log = console(LONG, nb)
    start, end = b.get("timestamp", 0), b.get("timestamp", 0) + b.get("duration", 0)
    toggles = records_since(mark, lambda x: x.get("type") == "CONFIG_TOGGLE")
    inside = [x for x in toggles if start <= x.get("at", 0) <= end]
    check("L", "[T7] D-27: the approved build of r29-long was still running right after run control off, run control on, "
          "the job's configuration save (approvalRequired off, then on), the expiry of the window on the job, change "
          "control off and change control on; it ends SUCCESS with its last line printed, not aborted, and its duration "
          "covers the sleep",
          all(v is True for k, v in seen.items() if k.startswith("after")) and res == "SUCCESS"
          and "r29-long-done" in log and "Aborted" not in log and b.get("duration", 0) >= SLEEP * 1000,
          request=rid, build=f"{LONG}#{nb}", result=res, duration_ms=b.get("duration"),
          samples={k: v for k, v in seen.items() if k.startswith("after")}, tail=log.strip().splitlines()[-2:])
    desc = api("admin", J(LONG) + "/api/json?tree=description").json().get("description")
    check("L", "[T7] guard: each event happened during the build: the four switch saves succeeded and wrote "
          "CONFIG_TOGGLE records between the build's start and end (run control off, on; change control off, on); the "
          "window's expiresAt lies between them and the holder was refused right after it; the configuration saves were "
          "stored (description changed, approvalRequired false in between)",
          all(seen[k] in (200, 302) for k in ("run off", "run on", "change off", "change on", "config", "config back"))
          and len(inside) >= 4 and start < gw["expiresAtMillis"] < end and seen["holder after expiry"] == 403
          and exp and desc == d1 and seen["approval off stored"] == "false",
          toggles=[(x.get("at", 0) - start, (x.get("detail") or "")[:120]) for x in toggles], window_expiry_ms=gw["expiresAtMillis"] - start,
          build_ms=end - start, saves={k: seen[k] for k in ("run off", "run on", "change off", "change on", "config", "config back")},
          holder=seen["holder after expiry"], description=desc, window=wid)
    q0 = len(queue_items(LONG))
    direct = api("requester", J(LONG) + "/build", "POST").status_code
    time.sleep(3)
    check("L", "[T7] guard: blocking still happens at the queue entry: after all this the requester's direct Build POST is "
          "refused and nothing is queued or built",
          direct >= 400 and len(queue_items(LONG)) == q0 == 0 and next_build(LONG) == nb + 1, status=direct,
          next_build=next_build(LONG), switches=switches())


# ---------------------------------------------------------------- Q: two approved runs held in the queue
def sec_Q():
    st, aid = act_req("requester", QDEL, "ACTIVATE", "e2e-29 X2 activate r29-q-del before its deletion")
    if aid:
        decide("approver-1", "activations", aid, "approve", "e2e-29 X2")
    S["qdel_activated"] = lib.wait_until(lambda: activation(QDEL) == "activated", timeout=20, step=1)
    S["q"] = {}
    for job, p in ((QRUN, "queued-run"), (QDEL, "queued-del")):
        rid = file_run(job, p, f"e2e-29 R11/R12 {job}")
        decide("approver-1", "requests", rid, "approve", "e2e-29 R11/R12")
        lib.wait_until(lambda: lib.queue_of_request(rid), timeout=30, step=1)
        S["q"][job] = {"rid": rid, "at": time.time(), "queue": lib.queue_of_request(rid), "p": p}
    st = {job: stored_request(v["rid"]).get("status") for job, v in S["q"].items()}
    check("Q", "premise: r29-q-del is activated; the approved runs of r29-q-run and r29-q-del (label r29-nowhere, which no "
          "node has) wait in the queue, exactly one item each carrying its request, and stay APPROVED",
          S["qdel_activated"] and all(len(v["queue"]) == 1 for v in S["q"].values()) and set(st.values()) == {"APPROVED"}
          and next_build(QRUN) == 1 and next_build(QDEL) == 1, activation=activation(QDEL), status=st,
          queue={k: v["queue"] for k, v in S["q"].items()})
    lib.shot_page("admin", f"/batch-control/requests/{S['q'][QRUN]['rid']}/", "R29-R-Q-01-approved-waiting")


# ---------------------------------------------------------------- J: rejected and cancelled never run (R4)
def sec_J():
    nb = next_build(REJ)
    rj = file_run(REJ, "rejected", "e2e-29 R4 to be rejected")
    # the navigation after core's confirm dialog may carry no response object (status None); the stored REJECTED decides
    form, st, _t = lib.browser_decide("approver-1", rj, "reject", "e2e-29 R4 rejected", shot="R29-R-J-01-rejected")
    rc = file_run(REJ, "cancelled", "e2e-29 R4 to be cancelled")
    cancel = api("requester", f"/batch-control/requests/{rc}/cancel", "POST").status_code
    ends = {"rejected": stored_request(rj).get("status"), "cancelled": stored_request(rc).get("status")}
    late = {"rejected": lib.approve_rest("approver-1", rj, "e2e-29 R4 late")[0],
            "cancelled": lib.approve_rest("approver-1", rc, "e2e-29 R4 late")[0]}
    time.sleep(8)
    after = {"rejected": stored_request(rj).get("status"), "cancelled": stored_request(rc).get("status")}
    runs = {"rejected": runs_of_request(rj), "cancelled": runs_of_request(rc)}
    check("J", "[R4] a REJECTED and a CANCELLED request never run: approver-1 rejected one in the browser, the requester "
          "cancelled the other; approver-1's approve POST of each afterwards is refused (4xx) and the status stays; no "
          "queue item or build carries either and the job did not build (watched 8 s)",
          form and (st is None or ok(st)) and ok(cancel) and ends == {"rejected": "REJECTED", "cancelled": "CANCELLED"} and after == ends
          and all(400 <= v < 500 for v in late.values()) and all(not v["queue"] and not v["builds"] for v in runs.values())
          and next_build(REJ) == nb and not queue_items(REJ), reject_form=form, reject_http=st, cancel_http=cancel,
          ends=ends, late=late, after=after, runs=runs, next_build=next_build(REJ))
    n0 = len(lib.run_requests_of(DUAL, REJ))
    codes = {}
    for k, who in (("alone", (DUAL,)), ("with approver-1", ("approver-1", DUAL))):
        codes[k] = run_req(DUAL, REJ, f"e2e-29 R4 naming myself ({k})", approvers=who, params={"P": "self"})[0]
    n1 = len(lib.run_requests_of(DUAL, REJ))
    check("J", "[R4] a listed approver who is not an administrator (r29-dual) may not designate themself on their own run "
          "request, alone or next to approver-1: refused with a 4xx and nothing stored",
          all(400 <= v < 500 for v in codes.values()) and n1 == n0, codes=codes, stored_before=n0, stored_after=n1)
    st, rd, _ = run_req(DUAL, REJ, "e2e-29 R4 naming approver-1", params={"P": "dual"})
    ch = api(DUAL, f"/batch-control/requests/{rd}/changeApprover", "POST", data=[("approvers", DUAL)]).status_code if rd else None
    ap = lib.approve_rest(DUAL, rd, "e2e-29 R4 my own")[0] if rd else None
    stored = lib.run_request(rd) if rd else {}
    check("J", "[R4] guard: naming approver-1 r29-dual's request is stored PENDING; its approver change to itself is "
          "refused (approvers still approver-1) and its own approve POST is refused (PENDING, nothing queued)",
          rd is not None and stored.get("status") == "PENDING" and stored.get("approvers") == "approver-1"
          and ch is not None and ch >= 400 and ap is not None and ap >= 400 and not runs_of_request(rd)["queue"],
          submit=st, change=ch, approve=ap, stored={k: stored.get(k) for k in ("status", "approvers", "decidedBy")})
    if rd:
        api("admin", f"/batch-control/requests/{rd}/cancel", "POST")


# ---------------------------------------------------------------- X: approver without Read (X1)
def sec_X():
    out = {}
    for job, how in ((X1D, "browser"), (X1N, "rest")):
        nb = next_build(job)
        p = f"x1-{how}"
        rid = file_run(job, p, f"e2e-29 X1 {job}", approvers=("approver-disc",))
        if how == "browser":
            form, st, t = lib.browser_decide("approver-disc", rid, "approve", "e2e-29 X1",
                                             shot="R29-R-X-01-discover-only-approved")
        else:
            form, (st, t) = None, lib.approve_rest("approver-disc", rid, "e2e-29 X1")
        end = end_state(rid)
        res, _ = lib.wait_build(job, nb, 90)
        out[job] = {"form": form, "status": st, "end": end.get("status"), "result": res, "runs": runs_of_request(rid),
                    "params": build_params(job, nb), "nb": nb, "page": api("approver-disc", J(job) + "/").status_code,
                    "refusal": text_of(t or "")[-200:] if not ok(st or 0) else ""}
    check("X", "[X1] SPEC 5: an approved request is submitted even when the approver holds only Item/Discover (team/r29-x1, "
          "approved on its page in the browser) or no permission (r29-x1-none, approved with the endpoint the page "
          "posts): exactly one build each with exactly the requested P, the request EXECUTED",
          all(ok(v["status"] or 0) and v["end"] == "EXECUTED" and v["result"] == "SUCCESS"
              and v["runs"]["builds"] == [f"{j}#{v['nb']}"] and v["params"] == {"P": f"x1-{h}"}
              for (j, h), v in zip(((X1D, "browser"), (X1N, "rest")), out.values()))
          and out[X1D]["form"] is True, **{k: {x: y for x, y in v.items() if x != "page"} for k, v in out.items()})
    check("X", "[X1] guard: approver-disc cannot read either job (its GET of the job page is refused; premise in A)",
          all(v["page"] in (403, 404) for v in out.values()), pages={k: v["page"] for k, v in out.items()})


# ---------------------------------------------------------------- C: races (R5)
def race(rid, posts):
    """Sends the POSTs of `posts` [(name, session, path)] together (a barrier); {name: (status, ms after the first send,
    the end of the text)}."""
    gate = threading.Barrier(len(posts))
    out = {}

    def go(name, s, path):
        gate.wait()
        t0 = time.time()
        r = s.post(lib.BASE + path, data={"comment": f"e2e-29 R5 {name}"}, allow_redirects=False, timeout=60)
        out[name] = (r.status_code, t0, text_of(r.text)[-160:] if r.status_code >= 400 else "")
    ts = [threading.Thread(target=go, args=p) for p in posts]
    [t.start() for t in ts]
    [t.join() for t in ts]
    first = min(v[1] for v in out.values())
    return {k: (v[0], round((v[1] - first) * 1000), v[2]) for k, v in out.items()}


def sec_C():
    rounds = []
    for i in range(3):
        nb = next_build(RACE)
        p = f"race-aa-{i}"
        rid = file_run(RACE, p, f"e2e-29 R5 approve/approve {i}", approvers=("approver-1", "approver-2"))
        ss = {u: sess(u) for u in ("approver-1", "approver-2")}
        got = race(rid, [(u, ss[u], f"/batch-control/requests/{rid}/approve") for u in ss])
        end = end_state(rid)
        lib.wait_build(RACE, nb, 60)
        time.sleep(4)
        winners = [u for u, v in got.items() if ok(v[0])]
        rounds.append({"rid": rid, "got": got, "winners": winners, "decidedBy": lib.run_request(rid).get("decidedBy"),
                       "end": end.get("status"), "runs": runs_of_request(rid), "params": build_params(RACE, nb),
                       "builds": next_build(RACE) - nb, "nb": nb, "p": p})
    check("C", "[R5] two designated approvers approve the same request at once (three rounds): exactly one answer is the "
          "success redirect and the other a refusal (4xx, no 5xx); decidedBy is the winner; exactly one build with that "
          "round's P; EXECUTED; no second build",
          all(len(r["winners"]) == 1 and all(ok(v[0]) or 400 <= v[0] < 500 for v in r["got"].values())
              and r["decidedBy"] == r["winners"][0] and r["end"] == "EXECUTED" and r["runs"]["builds"] == [f"{RACE}#{r['nb']}"]
              and r["params"] == {"P": r["p"]} and r["builds"] == 1 for r in rounds),
          rounds=[{k: r[k] for k in ("got", "winners", "decidedBy", "end", "builds")} for r in rounds])
    mixed = []
    for i in range(3):
        nb = next_build(RACE)
        p = f"race-ac-{i}"
        rid = file_run(RACE, p, f"e2e-29 R5 approve/cancel {i}")
        got = race(rid, [("approve", sess("approver-1"), f"/batch-control/requests/{rid}/approve"),
                         ("cancel", sess("requester"), f"/batch-control/requests/{rid}/cancel")])
        end = end_state(rid)
        if end.get("status") == "EXECUTED":
            lib.wait_build(RACE, nb, 60)
        time.sleep(6)
        stored = lib.run_request(rid)
        mixed.append({"got": got, "end": end.get("status"), "executedRunId": stored.get("executedRunId"),
                      "runs": runs_of_request(rid), "builds": next_build(RACE) - nb, "nb": nb, "p": p,
                      "params": build_params(RACE, nb)})

    def consistent(r):
        wins = [k for k, v in r["got"].items() if ok(v[0])]
        if r["end"] == "CANCELLED":
            return wins == ["cancel"] and not r["executedRunId"] and not r["runs"]["queue"] and not r["runs"]["builds"] \
                and r["builds"] == 0
        return r["end"] == "EXECUTED" and wins == ["approve"] and r["runs"]["builds"] == [f"{RACE}#{r['nb']}"] \
            and r["builds"] == 1 and r["params"] == {"P": r["p"]}
    check("C", "[R5] approve against cancel at once (three rounds): exactly one succeeds and the end state is not mixed: "
          "CANCELLED with no executedRunId, no queue item and no build, or EXECUTED with exactly one build of that P; "
          "the loser gets a 4xx, no 5xx",
          all(consistent(r) and all(ok(v[0]) or 400 <= v[0] < 500 for v in r["got"].values()) for r in mixed),
          rounds=[{k: r[k] for k in ("got", "end", "executedRunId", "builds")} for r in mixed])
    nb = next_build(RACE)
    rid = file_run(RACE, "sequential", "e2e-29 R5 guard", approvers=("approver-1", "approver-2"))
    first = lib.approve_rest("approver-1", rid, "e2e-29 R5 first")[0]
    second = lib.approve_rest("approver-2", rid, "e2e-29 R5 second")[0]
    end_state(rid)
    lib.wait_build(RACE, nb, 60)
    time.sleep(4)
    check("C", "[R5] guard: approved one after the other, the first approve succeeds, the second is refused (4xx) and "
          "one build runs", ok(first) and 400 <= second < 500 and next_build(RACE) - nb == 1
          and lib.run_request(rid).get("decidedBy") == "approver-1", first=first, second=second, builds=next_build(RACE) - nb)


# ---------------------------------------------------------------- D: deleting a job with an approved queued run (R12, X2)
def sec_D():
    q = S.get("q", {}).get(QDEL)
    if not q:
        check("D", "[R12] needs the approved run of section Q", False)
        return
    before = {"activation": activation(QDEL), "status": stored_request(q["rid"]).get("status"),
              "queue": lib.queue_of_request(q["rid"])}
    status, _ = lib.browser_delete(QDEL, shot="R29-R-D-01-delete")
    st = lib.wait_status(q["rid"], ("INVALIDATED", "EXPIRED", "EXECUTED", "CANCELLED"), timeout=20)
    time.sleep(2)
    why = lib.run_request(q["rid"]).get("invalidationReason") or ""
    page = text_of(api("requester", f"/batch-control/requests/{q['rid']}/").text)
    shown = re.search(r"Why it was invalidated (.{0,80})", page)
    gone = {"queue of the request": lib.queue_of_request(q["rid"]),
            "queue of the name": [x for x in lib.all_queue() if f"/job/{QDEL}/" in (x[1] or "")]}
    check("D", "[R12] deleting r29-q-del (its Delete page) while its approved run waits in the queue: the request is "
          "INVALIDATED with a reason naming the deletion and its queue item is gone",
          before["status"] == "APPROVED" and len(before["queue"]) == 1 and status is not None and status < 400
          and not exists(QDEL) and st.get("status") == "INVALIDATED" and re.search(r"(?i)delet", why)
          and shown and why in shown.group(1) and not gone["queue of the request"] and not gone["queue of the name"],
          before=before, http=status, status=st.get("status"), reason=why, page=shown.group(0) if shown else None, **gone)
    late = lib.approve_rest("approver-1", q["rid"], "e2e-29 R12 late")[0]
    made = lib.create(QDEL, pxml("e2e-29 r29-q-del re-created", 'echo "r29 P=$P re-created"', label=LABEL))
    act = activation(QDEL)
    check("D", "[R12][X2] approving the invalidated request again is refused; the job re-created under the name "
          "starts not activated, although the deleted one was activated",
          400 <= late < 500 and made == 200 and before["activation"] == "activated" and act == "not activated"
          and stored_request(q["rid"]).get("status") == "INVALIDATED", approve=late, create=made, activation=act)
    lib.shot_page("admin", J(QDEL) + "/", "R29-R-D-02-recreated-not-activated")


# ---------------------------------------------------------------- W: the queued approved run runs once (R11)
def sec_W():
    q = S.get("q", {}).get(QRUN)
    if not q:
        check("W", "[R11] needs the approved run of section Q", False)
        return
    wait = HOLD_S - (time.time() - q["at"])
    if wait > 0:
        time.sleep(wait)
    held = round(time.time() - q["at"])
    now_q = lib.queue_of_request(q["rid"])
    st = stored_request(q["rid"]).get("status")
    check("W", "[R11] the approved run of r29-q-run waited in the queue longer than approvedRunTimeoutMinutes (1) and two "
          "passes of the periodic work: still APPROVED, the same one queue item, no build",
          held >= HOLD_S and st == "APPROVED" and now_q == q["queue"] and len(now_q) == 1 and next_build(QRUN) == 1,
          held_s=held, status=st, queue=now_q, queued_at_Q=q["queue"])
    lib.node_labels(((S.get("label0") or "") + " " + LABEL).strip())
    res, _ = lib.wait_build(QRUN, 1, 90)
    end = end_state(q["rid"], timeout=30)
    stored = lib.run_request(q["rid"])
    check("W", "[R11] once the built-in node has the label r29-nowhere the approved run builds exactly once: r29-q-run#1 "
          "SUCCESS with exactly the requested P, the request EXECUTED naming that build",
          res == "SUCCESS" and build_params(QRUN, 1) == {"P": q["p"]} and end.get("status") == "EXECUTED"
          and stored.get("executedRunId") == f"{QRUN}#1", result=res, params=build_params(QRUN, 1),
          status=end.get("status"), executed=stored.get("executedRunId"))
    time.sleep(65)
    d = S["q"].get(QDEL, {})
    check("W", "[R11][R12][X2] after one more pass of the periodic work: no second build or queue item of r29-q-run (still "
          "EXECUTED); the re-created r29-q-del has not built and has nothing queued although the label now exists, and "
          "the deleted job's request is still INVALIDATED",
          next_build(QRUN) == 2 and not queue_items(QRUN) and stored_request(q["rid"]).get("status") == "EXECUTED"
          and next_build(QDEL) == 1 and not queue_items(QDEL)
          and (not d or stored_request(d["rid"]).get("status") == "INVALIDATED"),
          q_run_next=next_build(QRUN), q_del_next=next_build(QDEL), q_del_queue=queue_items(QDEL),
          old_request=stored_request(d["rid"]).get("status") if d else None)
    lib.node_labels(S.get("label0") or "")


# ---------------------------------------------------------------- cleanup
def cleanup():
    out = {}
    if "label0" in S:
        out["label"] = lib.node_labels(S["label0"] or "")
    sw = switches()
    if not (sw["runControlEnabled"] and sw["changeControlEnabled"]):
        out["switches"] = lib.set_config(runControlEnabled="true", changeControlEnabled="true")
    if "approvers0" in S:
        out["approvers"] = lib.set_approvers(S["approvers0"])
    out["pending"] = {u: lib.cancel_pending(u, "r29-") for u in ("requester", DUAL)}
    out["windows"] = lib.revoke_open(lib.HOLDER, "r29-")
    check("cleanup", "the node label, the approver list and both switches are back; nothing of this run is left pending",
          (("label0" not in S) or (lib.node_labels() or "") == (S["label0"] or ""))
          and (("approvers0" not in S) or lib.approvers() == S["approvers0"])
          and all(switches().values()), **{k: str(v)[:300] for k, v in out.items()})


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "L": sec_L, "Q": sec_Q, "J": sec_J, "X": sec_X, "C": sec_C, "D": sec_D, "W": sec_W},
            cleanup)
