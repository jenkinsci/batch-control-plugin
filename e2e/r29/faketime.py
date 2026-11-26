"""e2e-29, local only: the pending timeout on the real clock path ([T9] window requests, [R7] run requests; SPEC 7
pendingTimeoutHours, SPEC 13 D-54), on a Jenkins whose wall clock is moved forward by libfaketime while it runs.

usage: r29/faketime.sh [APJE]   (the script starts the stack; this driver needs BC_FAKETIME_DIR, the directory of the
libfaketime offset file mounted into the container by r29/compose.faketime.yml). rows: out/faketime.jsonl,
shots: screenshots/run-29/R29-FT-*.png
CI asserts the pending timeout only after rewriting a request's createdAtMillis in the store under a running Jenkins
(r27/titles_revert.py S, r27/d2_fixture.py), and cannot wait an hour. Here nothing in JENKINS_HOME is edited: the
controller's clock moves 65 minutes ahead (pendingTimeoutHours is 1, casc/jenkins.yaml) and the periodic work, which
keeps its real one-minute rhythm (monotonic clock not faked), finds the requests old. Real waits: about four minutes.

A  arrangement on a fresh JCasC-only Jenkins: both switches on, pendingTimeoutHours 1, e-mail on; the controller clock
   equals the real one (offset file "+0"); the jobs r29-ft-run (approval required, P), r29-ft-win and r29-ft-act fresh;
   requester files a run request on r29-ft-run, a CONFIGURE window request on r29-ft-win and an ACTIVATE request on
   r29-ft-act, each naming approver-1: all PENDING.
P  guard before the jump: after a pass of the periodic work (65 s) all three are still PENDING (younger than the
   timeout).
J  the offset file says "+3900": the controller clock is 65 minutes ahead of the real one (read back through the script
   console). requester files a fourth request (a run request on r29-ft-run) at the moved clock.
E  [R7][T9] at the next pass of the periodic work the three old requests are EXPIRED, through the clock alone: the run
   request (approver-1's approve POST refused, nothing queued or built), the window request (approve refused, no
   window, the requester's configure page 403) and the activation request (approve refused, the job still not
   activated). The requester and approver-1 get an "expired" mail naming the run request and the window request (D-54).
   Guard: the fourth request, filed after the jump, is still PENDING after the same pass (the expiry is by age, not a
   reaction to the clock change)."""
import os
import pathlib
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J, text_of, string_p, fs_xml, fresh_job, run_req, act_req, stored_request, runs_of_request  # noqa: E402

lib.LOGNAME[0] = "faketime"
RUN, WIN, ACT = "r29-ft-run", "r29-ft-win", "r29-ft-act"
JUMP_S = 3900
RC = pathlib.Path(os.environ.get("BC_FAKETIME_DIR", "/nonexistent")) / "rc"
S = {}


def offset_s():
    """Controller clock minus the real clock, in seconds (the median of five script-console round trips)."""
    return lib.clock_offset()[0] / 1000


def statuses():
    return {"run": stored_request(S["run"]).get("status"), "grant": lib.grant_request(S["grant"]).get("status"),
            "activation": lib.xml_of(f"activation-requests/{S['act']}.xml").get("status")}


def sec_A():
    if not RC.parent.is_dir():
        check("A", "BC_FAKETIME_DIR names the directory of the offset file (run this driver through r29/faketime.sh)", False,
              dir=str(RC.parent))
        raise SystemExit(1)
    RC.write_text("+0\n")
    time.sleep(2)
    cfg = lib.config("runControlEnabled", "changeControlEnabled", "pendingTimeoutHours", "emailNotifications")
    fresh_job(RUN, fs_xml(description="e2e-29 R7", shell='echo "P=$P"', params=string_p("P", "x")))
    fresh_job(WIN, fs_xml(description="e2e-29 T9"))
    fresh_job(ACT, fs_xml(description="e2e-29 T9 activation"))
    st1, S["run"], _ = run_req("requester", RUN, "e2e-29 R7 left pending", params={"P": "ft"})
    st2, S["grant"], _ = lib.grant_req("requester", WIN, ["CONFIGURE"], 15, "e2e-29 T9 left pending")
    st3, S["act"] = act_req("requester", ACT, "ACTIVATE", "e2e-29 T9 activation left pending")
    S["created"] = lib.now_ms()
    off = offset_s()
    check("A", "arrangement: both switches on, pendingTimeoutHours 1, e-mail on; the controller clock is the real one; "
          "requester's run, window and activation requests (approver-1) are PENDING",
          cfg["runControlEnabled"] and cfg["changeControlEnabled"] and int(cfg["pendingTimeoutHours"]) == 1
          and cfg["emailNotifications"] and abs(off) < 5 and all((S["run"], S["grant"], S["act"]))
          and set(statuses().values()) == {"PENDING"}, config=cfg, offset_s=round(off, 1), codes=[st1, st2, st3],
          ids={k: S[k] for k in ("run", "grant", "act")})


def sec_P():
    time.sleep(65)
    st = statuses()
    check("P", "guard: after a pass of the periodic work the three requests are still PENDING (younger than the timeout)",
          set(st.values()) == {"PENDING"}, status=st)


def sec_J():
    RC.write_text(f"+{JUMP_S}\n")
    moved = lib.wait_until(lambda: abs(offset_s() - JUMP_S) < 10, timeout=30, step=2)
    off = offset_s()
    st, S["fresh"], _ = run_req("requester", RUN, "e2e-29 R7 filed after the jump", params={"P": "fresh"})
    S["jumped_at"] = lib.now_ms()
    check("J", "the controller clock is 65 minutes ahead of the real one (offset file +3900, read back through the "
          "script console); a fourth request filed at the moved clock is PENDING",
          moved and abs(off - JUMP_S) < 10 and S["fresh"] and stored_request(S["fresh"]).get("status") == "PENDING",
          offset_s=round(off, 1), fresh=S["fresh"], http=st)


def sec_E():
    lib.wait_until(lambda: "PENDING" not in statuses().values(), timeout=150, step=3)
    st = statuses()
    fresh = stored_request(S["fresh"]).get("status")
    late = {"run": lib.approve_rest("approver-1", S["run"], "e2e-29 R7 late")[0],
            "grant": lib.approve_rest("approver-1", S["grant"], "e2e-29 T9 late", kind="grants")[0],
            "activation": lib.approve_rest("approver-1", S["act"], "e2e-29 T9 late", kind="activations")[0]}
    time.sleep(3)
    effects = {"runs": runs_of_request(S["run"]), "next build": lib.next_build(RUN),
               "window": lib.grant(S["grant"]).get("_exists"), "configure": api("requester", J(WIN) + "/configure").status_code,
               "activation": lib.activation(ACT)}
    check("E", "[R7][T9] at the next pass after the jump the three old requests are EXPIRED by the clock alone (nothing "
          "in the store edited)", st == {"run": "EXPIRED", "grant": "EXPIRED", "activation": "EXPIRED"}, status=st,
          minutes_since_filed_on_the_controller=round((lib.now_ms() - S["created"]) / 60000, 1))
    check("E", "[R7][T9] the expired requests can no longer be decided and had no effect: approver-1's approve POSTs are "
          "refused (4xx); no queue item or build carries the run request and r29-ft-run never built; no window exists and "
          "the requester's configure page on r29-ft-win is 403; r29-ft-act is not activated",
          all(400 <= v < 500 for v in late.values()) and not effects["runs"]["queue"] and not effects["runs"]["builds"]
          and effects["next build"] == 1 and effects["window"] is False and effects["configure"] == 403
          and effects["activation"] == "not activated", approve=late, **{k: str(v) for k, v in effects.items()})
    check("E", "[R7] guard: the request filed after the jump is still PENDING after the same pass (expiry by age)",
          fresh == "PENDING", fresh=fresh)
    lib.shot_page("requester", f"/batch-control/requests/{S['run']}/", "R29-FT-E-01-run-request-expired")
    lib.shot_page("requester", f"/batch-control/grants/{S['grant']}/", "R29-FT-E-02-window-request-expired")
    time.sleep(8)  # the dispatch thread sends after the state change
    mails = {}
    for who in ("requester", "approver-1"):
        for k in ("run", "grant", "act"):
            mails[f"{who} {k}"] = [m["subject"] for m in lib.mails_for(f"{who}@e2e.local", S[k])
                                   if re.search(r"(?i)expired", m["subject"] or "")]
    check("E", "[R7][T9] D-54: the requester and the designated approver each get an 'expired' mail naming the run request "
          "and one naming the window request",
          all(len(mails[f"{w} {k}"]) == 1 for w in ("requester", "approver-1") for k in ("run", "grant")), mails=mails)
    note("E", "the activation request's expiry mails (SPEC 6a decides an activation 'like a run request'; D-54 names run "
         "and change requests)", requester=mails["requester act"], approver=mails["approver-1 act"],
         page=text_of(api("requester", f"/batch-control/activations/{S['act']}/").text)[-200:])


def cleanup():
    note("cleanup", "the stack is removed by r29/faketime.sh (the clock is not moved back under a running Jenkins)",
         offset=RC.read_text().strip() if RC.exists() else None)


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "P": sec_P, "J": sec_J, "E": sec_E}, cleanup)
