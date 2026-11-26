"""e2e-29: [T5] a permission window that expires while Jenkins is down is refused at the first check after the start
(SPEC 8: the window ends at its expiry time; "만료 시각이 지나면 즉시 회수"), while a window still running keeps working.

usage: python restart.py [AWSC]   rows: out/restart.jsonl, shots: screenshots/run-29/R29-T5-*.png
Stops and starts the Jenkins container (docker stop with 180 s grace, so JaCoCo writes its session, then docker start
once the window's expiry has passed), so ci/shard.py runs it as a `last` unit. The holder is JCasC's `requester`
(RequestGrant), whose permissions JCasC re-applies at the boot, so nothing has to be re-arranged after the start.
Real waits: about a minute (the window's rest) plus Jenkins' boot.

A  arrangement: change control on; the jobs r29-rst and r29-rst-ctl fresh; requester holds no Configure on them (its
   configure page answers 403) and no open window or pending window request on them.
W  requester's one-minute CONFIGURE window on r29-rst and fifteen-minute CONFIGURE window on r29-rst-ctl (approver-1
   approves both): both configure pages 200 for the holder.
S  Jenkins is stopped before the one-minute window's expiresAt and started only after it (the controller clock offset
   measured before the stop): the window expired while no Jenkins was running.
C  after the start, the holder's very first check on r29-rst (its configure page, as soon as the API answers) is core's
   403, its config.xml POST is 403 and the description is unchanged; the window was not revoked (no revokedAtMillis; it
   expired) and no GRANT_REVOKE record names it; the holder's grants page lists it under Ended as Expired. Guard: the
   fifteen-minute window still confers Configure on r29-rst-ctl after the start (200, a config.xml save is stored)."""
import subprocess
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, J, H_XML, fs_xml, exists, delete, create, grant, open_window, CONTAINER  # noqa: E402

lib.LOGNAME[0] = "restart"
U = "requester"
JOB, CTL = "r29-rst", "r29-rst-ctl"
S = {}


def status(user, path, method="GET", **kw):
    return api(user, path, method, **kw).status_code


def description(job):
    r = api("admin", J(job) + "/api/json?tree=description")
    return r.json().get("description") if r.status_code == 200 else None


def sec_A():
    left = (lib.revoke_open(U, "r29-rst"), lib.cancel_pending(U, "r29-rst"))
    for job in (JOB, CTL):
        delete(job)
        create(job, fs_xml(description=f"e2e-29 T5 {job} base"))
    pre = {job: status(U, J(job) + "/configure") for job in (JOB, CTL)}
    cfg = lib.config("changeControlEnabled")
    check("A", "arrangement: change control on; r29-rst and r29-rst-ctl fresh; requester may not configure them (403) and "
          "holds no window on them", cfg["changeControlEnabled"] and all(exists(j) for j in (JOB, CTL))
          and set(pre.values()) == {403}, configure=pre, leftovers=str(left))


def sec_W():
    S["w"], g = open_window(U, JOB, ["CONFIGURE"], 1, "e2e-29 T5 window that expires while Jenkins is down")
    S["ctl"], _ = open_window(U, CTL, ["CONFIGURE"], 15, "e2e-29 T5 control window")
    S["exp"] = g["expiresAtMillis"]
    warm = {job: status(U, J(job) + "/configure") for job in (JOB, CTL)}
    check("W", "[T5] requester's one-minute window on r29-rst and fifteen-minute window on r29-rst-ctl are open and confer "
          "Configure (configure page 200 on both)", set(warm.values()) == {200} and g["expiresAtMillis"] - g["grantedAtMillis"] == 60000,
          configure=warm, windows=[S["w"], S["ctl"]])


def sec_S():
    if "exp" not in S:
        check("S", "[T5] needs the windows of section W", False)
        return
    offset, half = lib.clock_offset()
    stop0 = time.time() * 1000 + offset
    r = subprocess.run(["docker", "stop", "-t", "180", CONTAINER], capture_output=True, text=True, timeout=240)
    stopped = time.time() * 1000 + offset
    while time.time() * 1000 + offset < S["exp"] + 3000:
        time.sleep(0.5)
    start0 = time.time() * 1000 + offset
    r2 = subprocess.run(["docker", "start", CONTAINER], capture_output=True, text=True, timeout=120)
    S["ready_s"] = lib.ready()
    S["first"] = status(U, J(JOB) + "/configure")    # the very first check after the start
    S["first_at"] = time.time() * 1000 + offset
    check("S", "[T5] Jenkins was stopped before the window's expiresAt and started only after it (the window expired while "
          "no Jenkins ran)", r.returncode == 0 and r2.returncode == 0 and stopped + half < S["exp"] < start0 - half
          and S["ready_s"] is not None, stop_request_ms=round(stop0 - S["exp"]), stopped_ms=round(stopped - S["exp"]),
          start_ms=round(start0 - S["exp"]), ready_s=S["ready_s"], docker=[r.stderr.strip()[:200], r2.stderr.strip()[:200]])


def sec_C():
    if "first" not in S:
        check("C", "[T5] needs the restart of section S", False)
        return
    d0 = description(JOB)
    post = status(U, J(JOB) + "/config.xml", "POST", headers=H_XML,
                  data=fs_xml(description="e2e-29 T5 after the restart").encode())
    g = grant(S["w"])
    recs = [x for x in lib.changes() if x.get("type") == "GRANT_REVOKE" and x.get("grantId") == S["w"]]
    row = lib.ended_row(U, S["w"])
    check("C", "[T5] the first check after the start on r29-rst is core's 403; the config.xml POST is 403 and the "
          "description unchanged; the window expired (no revokedAtMillis, no GRANT_REVOKE record names it) and is listed "
          "under Ended as Expired",
          S["first"] == 403 and post == 403 and description(JOB) == d0 == "e2e-29 T5 r29-rst base"
          and not g.get("revokedAtMillis") and not recs and list(row) == ["ended"] and "Expired" in row["ended"],
          first=S["first"], first_ms_after_expiry=round(S["first_at"] - S["exp"]), post=post, revoked=g.get("revokedAtMillis"),
          records=len(recs), row=row)
    lib.shot_page(U, "/batch-control/grants/", "R29-T5-01-grants-after-restart")
    ctl = status(U, J(CTL) + "/configure")
    saved = status(U, J(CTL) + "/config.xml", "POST", headers=H_XML,
                   data=fs_xml(description="e2e-29 T5 control saved after the restart").encode())
    check("C", "[T5] guard: the fifteen-minute window still confers Configure on r29-rst-ctl after the start (configure "
          "page 200, a config.xml save is stored)", ctl == 200 and saved == 200
          and description(CTL) == "e2e-29 T5 control saved after the restart" and grant(S["ctl"])["open"],
          configure=ctl, save=saved)


def cleanup():
    out = (lib.revoke_open(U, "r29-rst"), lib.cancel_pending(U, "r29-rst"))
    lib.note("cleanup", "requester's windows and pending requests on r29-rst* ended", revoked=str(out))


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "W": sec_W, "S": sec_S, "C": sec_C}, cleanup)
