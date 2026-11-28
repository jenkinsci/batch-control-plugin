"""e2e-19: unattended triggers on a real Jenkins: a real cron schedule and a real upstream trigger (SPEC 6, 6a).

usage: python triggers.py [TU]   rows: out/triggers.jsonl, shots: R19-TRIG-*.png   (run r19/arrange.py first)
T  r19-cron (timer every minute, created under run control: blockTimer, blockUpstream, not activated):
   1. the job page shows the trigger-lock notice naming "Block cron (timer) triggers" and "Block upstream triggers"
      and the activation notice "not activated" (SPEC 6 #21, 6a)
   2. the next minute's timer run is refused: no build, and a TRIGGER_BLOCKED record names the job, the cause kind and
      blockTimer (SPEC 6 #21)
   3. the administrator clears blockTimer: the status part of the notice (the text before how to clear it)
      no longer names it; the how-to-clear sentence may still name both field titles (T-06-114). The next
      minute still starts nothing, because the job is not activated (SPEC 6a D-46: clearing a switch never
      lets a non-activated job run)
   4. requester asks for activation, approver-1 approves it in the browser: an ACTIVATED record, the job page says
      "activated", and the next minute's timer run starts with the timer cause; runs.csv lists it with cause TIMER
      (SPEC 6 "cron passes by default", 6a, SPEC 10 cause classes)
   5. a HOLD request approved in the browser: a HELD record, the job page says "on hold", the next minute starts
      nothing (SPEC 6a HOLD)
U  r19-ptsrc / r19-ptsrc2 trigger r19-ptdst through parameterized-trigger (the lock: blockUpstream, not activated):
   1. a run of r19-ptsrc: r19-ptdst refused, TRIGGER_BLOCKED record naming the upstream job and blockUpstream
   2. allow list = r19-ptsrc, still not activated: refused (activation needed)
   3. r19-ptdst activated: a run of r19-ptsrc starts r19-ptdst ("Started by upstream project r19-ptsrc"); a run of
      r19-ptsrc2 (not on the list) is refused and recorded naming r19-ptsrc2 (SPEC 6 R-1/D-16)
   4. blockUpstream off: r19-ptsrc2 now starts r19-ptdst whatever the list says (SPEC 6 D-16 second sentence)"""
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, check, note, text_of, J, run_sections, act_req, queue_items, next_build, wait_build,
                 browser_decide, changes_mark, changes_since, set_property, activation, BASE)  # noqa: E402

lib.LOGNAME[0] = "triggers"
WANT = sys.argv[1] if len(sys.argv) > 1 else "TU"


def hour_records(job, kind, switch):
    """TRIGGER_BLOCKED records of `job` for the cause kind and switch written in the last hour (controller clock)."""
    now = lib.now_ms()
    return [x for x in lib.changes(lambda x: x.get("type") == "TRIGGER_BLOCKED" and x.get("target") == job)
            if f"cause={kind} switch={switch}" in (x.get("detail") or "") and isinstance(x.get("at"), (int, float))
            and now - x["at"] < 3600 * 1000]


def wait_next_tick(extra=25):
    """Sleeps until `extra` seconds after the next full minute of the controller clock (the cron tick)."""
    sec = int(gv("return java.time.LocalTime.now().getSecond()"))
    time.sleep((60 - sec) + extra)


def notice(user, job, shot):
    s = Session(user)
    s.go(J(job) + "/")
    t = s.text()
    s.shot("#main-panel", shot)
    s.done()
    return t


def status_text(page_text):
    """The status part of the trigger-lock notice: the visible text before how to clear it.

    If that sentence is missing, the whole page is used, so an always-absent how-to-clear
    sentence cannot hide an off switch being named as on (same rule as T-06-49).
    """
    cut = page_text.lower().find("to clear it")
    return page_text if cut < 0 else page_text[:cut]


def activate(job, action="ACTIVATE", tag="T"):
    st, aid = act_req("requester", job, action, f"e2e-19 {action} {job}")
    assert aid, (job, action, st)
    s, t = browser_decide("approver-1", aid, "approve", f"e2e-19 {action}", f"R19-TRIG-{tag}-{action}-{job}", kind="activations")
    s.done()
    return aid


def sec_T():
    job = "r19-cron"
    set_property(job, approval=True, timer=True, upstream=True)
    gv(f"""def j = jenkins.model.Jenkins.get().getItem('{job}'); j.triggers.values().each {{ it.start(j, false) }}; return 'started'""")
    t = notice("requester", job, "R19-TRIG-T-01-locked")
    check("T", "job page: the trigger-lock notice names blockTimer and blockUpstream, the job is not activated (SPEC 6 #21, 6a)",
          "unattended triggers of this job are blocked" in t and "Block cron (timer) triggers" in t and "Block upstream triggers" in t
          and ("this job is not activated" in t or "this job is on hold" in t))
    nb0 = next_build(job)
    mark = changes_mark()
    wait_next_tick()
    hour = hour_records(job, "TIMER", "blockTimer")
    check("T", "the timer run is refused: no build; one TRIGGER_BLOCKED record this hour names the job, the cause kind and "
          "blockTimer, repeats merged into it (SPEC 6 #21: at most one per job and cause kind per hour)",
          next_build(job) == nb0 and len(hour) == 1, next_build=next_build(job), records=[(x.get("detail") or "")[:200] for x in hour][:3])
    set_property(job, approval=True, timer=False, upstream=True)
    t = notice("requester", job, "R19-TRIG-T-02-timer-cleared")
    status = status_text(t)
    check("T", "blockTimer cleared: the status part of the notice no longer names it",
          "Block cron (timer) triggers" not in status and "Block upstream triggers" in status)
    wait_next_tick()
    check("T", "blockTimer cleared but not activated: the timer still starts nothing (SPEC 6a D-46)", next_build(job) == nb0,
          next_build=next_build(job))
    mark = changes_mark()
    activate(job, "ACTIVATE")
    rec = changes_since(mark, lambda x: x.get("type") == "ACTIVATED" and x.get("target") == job)
    check("T", "the approved activation writes an ACTIVATED record and the job page says activated (SPEC 6a)",
          len(rec) == 1 and activation(job) == "activated", activation=activation(job), records=len(rec))
    wait_next_tick(30)
    result, console = wait_build(job, nb0, 60)
    cause = api("admin", J(job) + f"/{nb0}/api/json?tree=actions[causes[shortDescription]]").text
    check("T", "activated: the next timer run starts with the timer cause (SPEC 6 cron passes by default)",
          result == "SUCCESS" and "Started by timer" in cause, result=result)
    csv = api("approver-1", "/batch-control/history/runs.csv")
    rows = [l for l in csv.text.splitlines() if job in l and f",{nb0}," in l or (job in l and f"#{nb0}" in l)]
    check("T", "runs.csv lists the timer run with cause TIMER (SPEC 10)", any("TIMER" in l for l in rows) or
          any("TIMER" in l for l in csv.text.splitlines() if job in l), rows=rows[:1],
          sample=[l for l in csv.text.splitlines() if job in l][:2])
    mark = changes_mark()
    activate(job, "HOLD")
    rec = changes_since(mark, lambda x: x.get("type") == "HELD" and x.get("target") == job)
    t = notice("requester", job, "R19-TRIG-T-03-held")
    check("T", "the approved hold writes a HELD record and the job page says on hold (SPEC 6a)",
          len(rec) == 1 and "this job is on hold" in t, records=len(rec))
    time.sleep(5)
    nb1 = next_build(job)
    wait_next_tick()
    check("T", "on hold: the next minute starts nothing", next_build(job) == nb1 and not queue_items(job), next_build=next_build(job), before=nb1)


def src_run(src):
    nb = next_build(src)
    r = api("admin", J(src) + "/build", "POST")
    assert r.status_code in (200, 201, 302), (src, r.status_code)
    result, _ = wait_build(src, nb, 90)
    time.sleep(6)  # parameterized-trigger schedules the downstream build after the upstream completes
    return result


def sec_U():
    dst = "r19-ptdst"
    set_property(dst, approval=True, timer=True, upstream=True)
    nb0 = next_build(dst)
    mark = changes_mark()
    src_run("r19-ptsrc")
    rec = changes_since(mark, lambda x: x.get("type") == "TRIGGER_BLOCKED" and x.get("target") == dst)
    check("U", "blockUpstream with an empty list: the upstream-triggered run is refused and recorded naming r19-ptsrc (SPEC 6 D-16)",
          next_build(dst) == nb0 and any("r19-ptsrc" in (x.get("detail") or "") and "blockUpstream" in (x.get("detail") or "") for x in rec),
          next_build=next_build(dst), records=[(x.get("detail") or "")[:200] for x in rec][:2])
    set_property(dst, approval=True, timer=True, upstream=True, allowed="r19-ptsrc")
    if activation(dst) != "activated":
        src_run("r19-ptsrc")
        check("U", "on the allow list but not activated: still refused (SPEC 6a)", next_build(dst) == nb0, next_build=next_build(dst))
        activate(dst, "ACTIVATE", "U")
    src_run("r19-ptsrc")
    result, console = wait_build(dst, nb0, 60)
    check("U", "activated and on the allow list: r19-ptsrc starts r19-ptdst (SPEC 6 UpstreamCause)",
          result == "SUCCESS" and "Started by upstream project" in console and "r19-ptsrc" in console, result=result)
    nb1 = next_build(dst)
    mark = changes_mark()
    src_run("r19-ptsrc2")
    hour = hour_records(dst, "UPSTREAM", "blockUpstream")
    check("U", "a job not on the allow list is refused; the refusal is merged into the hour's one UPSTREAM record (SPEC 6 "
          "allowedUpstreamJobs, #21 coalescing)", next_build(dst) == nb1 and len(hour) == 1,
          next_build=next_build(dst), records=[(x.get("detail") or "")[:200] for x in hour][:3])
    set_property(dst, approval=True, timer=True, upstream=False, allowed="r19-ptsrc")
    src_run("r19-ptsrc2")
    result, console = wait_build(dst, nb1, 60)
    check("U", "blockUpstream off: every upstream job passes whatever the list says (SPEC 6 D-16)",
          result == "SUCCESS" and "r19-ptsrc2" in console, result=result)


run_sections(WANT, {"T": sec_T, "U": sec_U})
