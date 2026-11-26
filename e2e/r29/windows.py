"""e2e-29: time-limited permission windows ("time lock", SPEC 8) end to end, the rows of the e2e-29 coverage inventory
that CI asserted only in part (WEAK) or not at all (GAP).

usage: python windows.py [ANOBEKRSYLZ]   rows: out/windows.jsonl, shots: screenshots/run-29/R29-W-*.png
The holder w29 (Overall/Read, Item/Read, View/Read, BatchControl/RequestGrant; no standing Configure, Create or Delete)
requests every window over the plugin's REST endpoint and approver-1 approves it (both the endpoints the dialogs post);
the holder then works with core's own endpoints (configure page, config.xml, createItem, doDelete), as a person or a
script would. Items under the folder r29/ (re-created by A). Waits: four one-minute windows opened together (one real
minute), the T6 notice timeline (about four minutes, in a background thread while the other sections run: REST and
the script console only) and two cron ticks (K).

A  arrangement: switches on, the Batch Control strategy, approver-1 listed, grantDurationOptions 1/15/30/60 and
   maxGrantMinutes 240 (casc/jenkins.yaml), allowAdminSelfApproval on; the accounts w29 and r29-dual (a listed approver
   who may also request: Request, RequestGrant, Approve; added to the approver list for this run and removed in the
   cleanup); the items fresh; no open window or pending request of w29 or r29-dual on them.
N  [T6] GRANT_EXPIRING exactly once at expiresAt - notifyBeforeExpiryMinutes (started here, judged in Z). The lead is set
   to 13 minutes and w29 gets a 15-minute CONFIGURE window W on r29/note, so W is due 2 minutes after its approval; a
   one-minute marker window M1 on r29/mark-1 is due at once, so M1's notice marks the first pass P1 of the periodic work
   after the approvals, which is before W is due. Assert: at P1 (and 3 s later, still in that pass) W is not notified;
   W is notified at the first pass at or after its due time (stored expiringNotified false before due, true after; one
   "ends soon" mail); two more marker windows M2, M3 approved after W's notice are notified by the next two passes, and
   W still has exactly one mail (no second notice in two later passes). Guard: each marker gets exactly one mail too.
O  windows opened together (one minute each): CREATE on the folder r29/drop, DELETE on r29/del-a and on r29/del-b,
   CONFIGURE on r29/cfg. Premise first: before any window the holder may not configure r29/cfg, delete r29/del-a or
   create in r29/drop (core 403, nothing changed).
   [T3] inside the CREATE window the holder creates r29/drop/made and r29/drop/made2 with a config.xml payload whose
   Batch Control property says approvalRequired, blockTimer and blockUpstream false and with a cron trigger every
   minute: both exist, both carry the D-17/D-34 lock (approvalRequired, blockTimer, blockUpstream true, no allowed
   upstream job) whatever the payload said, neither is activated, and the CREATE records name w29 and the window. Guard:
   the same window creates nothing in the nested folder r29/drop/inner or at the root (403, no item). Then the holder,
   through the D-35c Configure of items it created through the window, clears the lock of made (config.xml with all
   three false: 200, stored) and removes the property from made2 (config.xml without it: 200); CONFIGURE records name
   the window.
   [T2] inside the DELETE windows: the holder may not delete r29/del-other (no window: refused, it still exists); the
   window on r29/del-b confers Item/Delete on it (permission check); the holder deletes r29/del-a (gone, a DELETE record
   names w29 and the window).
B  [T1] boundary of the CONFIGURE window on r29/cfg: inside it the holder's config.xml POST changes the description (200,
   stored, a CONFIGURE record names the window); then the holder loads the configure page continuously (every ~0.2 s,
   so any permission-decision cache is warm) across expiresAt: every answer of a request that ended before expiresAt is
   200, every answer of a request that started after expiresAt is 403 (core's Access Denied) from the first one on, which
   started within two seconds after expiresAt; the window was never revoked (no revokedAtMillis); after it, the holder's
   config.xml POST is 403 and the description is unchanged.
E  after the windows' end: [T2] the holder may not delete r29/del-b (refused, it still exists) although the window had
   conferred Delete on it; [T3] the holder may not create in r29/drop any more (403, no item), nor load or save the
   configuration of r29/drop/made (403, description unchanged), nor delete it (refused, it exists); the holder's grants
   page lists the four windows under Ended: the three left to run out as Expired, del-a's as ended by its deletion.
K  [T3][R9][X3] the jobs created through the window never run on their timer while not activated, although made's lock
   was cleared by the holder (approvalRequired, blockTimer, blockUpstream false) and made2 has no Batch Control property
   at all: at the next cron tick neither builds nor queues, and a TRIGGER_BLOCKED record of the last hour names made
   (its timer fired and was refused; repeats within an hour are merged into one record). Guard: requester asks for made's activation, approver-1 approves it in the browser; at the
   next tick made builds with the timer cause, made2 (still not activated) does not.
R  [T4] revocation: w29's 15-minute CONFIGURE window on r29/rev; the holder loads the configure page (200, three times,
   warm); the administrator presses Revoke on the window's page and confirms core's dialog; the holder's very next check
   is 403 (no wait in between); the window file has revokedAtMillis and revokedBy admin; exactly one GRANT_REVOKE record
   was written, naming admin, the window and r29/rev; changes.csv (approver-1) lists it; the holder's grants page lists
   the window under Ended as revoked by admin.
S  [T8] self-approval of windows: r29-dual (listed approver, may request, not an administrator) files a window request
   on r29/self naming itself: refused, nothing stored; naming approver-1 and itself: refused, nothing stored. Guard:
   naming approver-1 only is stored PENDING. r29-dual's approver change of that request to itself is refused (the stored
   approvers unchanged); r29-dual's POST approve of it is refused (PENDING, no window). The administrator files one
   naming admin and approves it on its page: APPROVED, decided by admin, the window exists for admin. With
   allowAdminSelfApproval off the administrator's request naming admin is refused and nothing is stored (guard of the
   exception); the setting is put back.
Y  [T8] SPEC 2 ("an administrator's self-approval records selfApproved=true"): the administrator files a window request
   on r29/self naming admin and approves it on its page; the stored request says selfApproved=true, and the request
   page the decision returns to has the row "Self-approved" "Yes". Guard: the administrator's request on r29/self
   naming approver-1 only, approved by approver-1 on its page, stores no selfApproved=true and its page has no
   "Self-approved" row. Red on main before the fix of jenkinsci/batch-control-plugin#182 (885834bf, 33e2941e: neither
   the element nor the row); the CI unit r29-windows runs it since that fix (ci/shard.py).
L  [T10] durations: w29's window requests on r29/dur with durationMinutes above maxGrantMinutes (241, 1440,
   99999999999), 0, -15, abc, empty and 15.5: each refused with a 4xx, nothing stored. Guard: 1 and 60 are stored with
   that duration; the 60-minute one approved lasts exactly 60 minutes (expiresAt - grantedAt); the request dialog's
   duration list offers exactly 1, 15, 30, 60. A NOTE records what a length within the maximum that is not one of the
   options (7) gets: SPEC 5/8 call the options the choices and maxGrantMinutes the limit and do not say (reported).
Z  [T6] judged (the thread started in N).
cleanup: the lead, the approver list and allowAdminSelfApproval put back; pending requests of w29, r29-dual and admin on
r29/ cancelled, their open windows revoked; the cron jobs r29/drop/made and made2 deleted."""
import json
import re
import sys
import threading
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, check, note, J, decide, text_of, exists, delete, create, grant, grant_req,  # noqa: E402
                 open_window, records_mark, records_since, has_permission, activation, next_build, queue_items, fs_xml,
                 H_XML, HOLDER as U, DUAL)

lib.LOGNAME[0] = "windows"
F = "r29"
DROP = "r29/drop"
MADE, MADE2 = "r29/drop/made", "r29/drop/made2"
JOBS = ("cfg", "del-a", "del-b", "del-other", "rev", "note", "mark-1", "mark-2", "mark-3", "self", "dur")
LEAD = 13          # minutes: W (15 minutes) is due 2 minutes after its approval
CRON = "* * * * *"
PERM = "hudson.model.Item."
S = {}


def it(name):
    return f"{F}/{name}"


def status(user, path, method="GET", **kw):
    return api(user, path, method, **kw).status_code


def lock(job):
    """The Batch Control property in the stored config.xml of `job` ({} when it has none, None when no job)."""
    r = api("admin", J(job) + "/config.xml")
    if r.status_code != 200:
        return None
    m = re.search(r"<io\.jenkins\.plugins\.batchcontrol\.config\.BatchControlJobProperty[^>]*>(.*?)</io\.jenkins\.plugins"
                  r"\.batchcontrol\.config\.BatchControlJobProperty>", r.text, re.S)
    if not m:
        return {}
    body = m.group(1)

    def tag(t):
        x = re.search(rf"<{t}>([^<]*)</{t}>", body)
        return x.group(1) if x else None
    allowed = re.search(r"<allowedUpstreamJobs>(.*?)</allowedUpstreamJobs>", body, re.S)
    return {"approvalRequired": tag("approvalRequired"), "blockTimer": tag("blockTimer"),
            "blockUpstream": tag("blockUpstream"),
            "allowed": re.findall(r"<string>([^<]*)</string>", allowed.group(1)) if allowed else []}


def description(job):
    r = api("admin", J(job) + "/api/json?tree=description")
    return r.json().get("description") if r.status_code == 200 else None


def records(mark, typ, target=None, user=None):
    return [r for r in records_since(mark, lambda x: x.get("type") == typ and (target is None or x.get("target") == target)
                                     and (user is None or x.get("user") == user))]


def wait_expired(*rids, slack=1.0):
    """Waits until the controller clock is past the expiry of every window of `rids` (plus `slack` seconds)."""
    end = max(grant(r)["expiresAtMillis"] for r in rids)
    lib.wait_until(lambda: lib.now_ms() > end + slack * 1000, timeout=150, step=1)
    return lib.now_ms() - end


# ---------------------------------------------------------------- A
def sec_A():
    S["lead0"] = lib.config("notifyBeforeExpiryMinutes")["notifyBeforeExpiryMinutes"]
    S["self0"] = lib.config("allowAdminSelfApproval")["allowAdminSelfApproval"]
    S["approvers0"] = lib.approvers()
    S["accounts"] = {U: lib.ensure_account(U, ["RequestGrant"]),
                     DUAL: lib.ensure_account(DUAL, ["Request", "RequestGrant", "Approve"])}
    if DUAL not in S["approvers0"]:
        lib.set_approvers(S["approvers0"] + [DUAL])
    left = {u: (lib.revoke_open(u, F + "/"), lib.cancel_pending(u, F + "/")) for u in (U, DUAL, "admin")}
    lib.ensure_folder(F)
    delete(DROP)
    create(DROP, lib.FOLDER_XML)
    create(DROP + "/inner", lib.FOLDER_XML)
    for n in JOBS:
        delete(it(n))
        create(it(n), fs_xml(description=f"e2e-29 {n} base"))
    delete("r29-root-by-window")
    cfg = lib.config("runControlEnabled", "changeControlEnabled", "grantDurationOptions", "maxGrantMinutes",
                     "allowAdminSelfApproval")
    strategy = gv("return jenkins.model.Jenkins.get().getAuthorizationStrategy().getClass().simpleName")
    hold = {n: has_permission(U, it(n), PERM + p) for n in ("cfg", "del-a") for p in ("CONFIGURE", "DELETE")}
    check("A", "arrangement: both switches on, a Batch Control strategy, durations 1/15/30/60 and max 240, admin "
          "self-approval on; w29 and r29-dual exist (r29-dual on the approver list); the items are fresh; w29 holds no "
          "Configure or Delete on them (premise for every 'refused' below)",
          cfg["runControlEnabled"] and cfg["changeControlEnabled"] and strategy.startswith("BatchControl")
          and [int(x) for x in cfg["grantDurationOptions"]] == [1, 15, 30, 60] and int(cfg["maxGrantMinutes"]) == 240
          and cfg["allowAdminSelfApproval"] and DUAL in lib.approvers() and all(exists(it(n)) for n in JOBS)
          and exists(DROP + "/inner") and not any(hold.values()),
          config=cfg, strategy=strategy, accounts=S["accounts"], leftovers=left, holder_perms=hold, lead=S["lead0"])


# ---------------------------------------------------------------- N / Z: GRANT_EXPIRING (T6)
def notified(*rids):
    out = lib.facts("""def root = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/grants'); def out = [now: System.currentTimeMillis()]
""" + json.dumps(list(rids)) + """.each { id -> def f = new File(root, id + '.xml')
  out[id] = f.exists() ? new XmlSlurper().parse(f).expiringNotified.text() == 'true' : null }
return groovy.json.JsonOutput.toJson(out)""")
    return out


def t6_flow(T):
    """The T6 timeline (background thread: REST and the script console only; the verdicts are judged in Z)."""
    try:
        T["lead"] = lib.set_config(notifyBeforeExpiryMinutes=LEAD)["notifyBeforeExpiryMinutes"]
        w, gw = open_window(U, it("note"), ["CONFIGURE"], 15, "e2e-29 T6 window W (15 minutes)")
        m1, _ = open_window(U, it("mark-1"), ["CONFIGURE"], 1, "e2e-29 T6 marker M1")
        T.update(w=w, m1=m1, due=gw["expiresAtMillis"] - LEAD * 60000, w_granted=gw["grantedAtMillis"])
        samples = []
        t0 = time.time()
        while time.time() - t0 < 260:
            n = notified(w, m1)
            samples.append(n)
            if n[m1] and "p1" not in T:
                T["p1"] = n["now"]
                time.sleep(3)
                T["w_at_p1_plus3"] = notified(w)[w]
                T["w_at_p1"] = n[w]
            if n[w]:
                T["w_seen"] = n["now"]
                break
            time.sleep(1)
        T["w_last_false"] = max((x["now"] for x in samples if x[w] is False), default=None)
        for k in ("m2", "m3"):
            if "w_seen" not in T:
                break
            rid, _ = open_window(U, it("mark-" + k[1]), ["CONFIGURE"], 1, f"e2e-29 T6 marker {k.upper()}")
            T[k] = rid
            T[k + "_seen"] = lib.wait_until(lambda: notified(rid)[rid] and lib.now_ms(), timeout=100, step=1)
        time.sleep(8)  # the single dispatch thread sends a mail after anything queued before it
        T["mails"] = {k: [m for m in lib.mails_for(f"{U}@e2e.local", T[k]) if "ends soon" in (m["subject"] or "")]
                      for k in ("w", "m1", "m2", "m3") if T.get(k)}
        T["w_final"] = notified(w)[w]
    except Exception as e:  # noqa: judged in Z
        T["error"] = repr(e)[:500]
    finally:
        T["lead_back"] = lib.set_config(notifyBeforeExpiryMinutes=S.get("lead0", 1))["notifyBeforeExpiryMinutes"]


def sec_N():
    S["t6"] = {}
    S["t6_thread"] = threading.Thread(target=t6_flow, args=(S["t6"],), daemon=True)
    S["t6_thread"].start()
    lib.wait_until(lambda: "due" in S["t6"] or "error" in S["t6"], timeout=60, step=1)


def sec_Z():
    th = S.get("t6_thread")
    if th is None:
        check("Z", "[T6] the notice timeline ran (section N)", False)
        return
    th.join(timeout=420)
    T = S["t6"]
    m = {k: len(v) for k, v in (T.get("mails") or {}).items()}
    check("Z", "[T6] arrangement: the lead was set to 13 minutes (W is due 2 minutes after its approval) and put back "
          "afterwards; no exception in the timeline", T.get("lead") == LEAD and T.get("lead_back") == S.get("lead0")
          and "error" not in T and not th.is_alive(), lead=T.get("lead"), back=T.get("lead_back"), error=T.get("error"))
    check("Z", "[T6] not before expiresAt - notifyBeforeExpiryMinutes: at the first pass of the periodic work after the "
          "approvals (marked by M1's notice, which was due at once) W was not notified, also 3 s later, and that pass was "
          "before W's due time", T.get("p1") is not None and T.get("w_at_p1") is False and T.get("w_at_p1_plus3") is False
          and T["p1"] < T["due"], p1=T.get("p1"), due=T.get("due"), w_at_p1=T.get("w_at_p1"),
          w_at_p1_plus3=T.get("w_at_p1_plus3"))
    seen = T.get("w_seen")
    check("Z", "[T6] at expiresAt - notifyBeforeExpiryMinutes: W became notified at the first pass at or after its due "
          "time (stored expiringNotified false until then, true within a pass of 60 s and a few seconds after it)",
          seen is not None and seen >= T["due"] and seen - T["due"] <= 75000
          and (T.get("w_last_false") or 0) < seen, due=T.get("due"), seen=seen,
          after_due_ms=(seen - T["due"]) if seen else None, last_false=T.get("w_last_false"))
    check("Z", "[T6] exactly once: W's holder got exactly one GRANT_EXPIRING mail, also after two later passes of the "
          "periodic work (proved by the notices of the markers M2 and M3 approved after W's notice); guard: M1, M2 and "
          "M3 got exactly one each, and W stays marked notified",
          m.get("w") == 1 and m.get("m1") == 1 and m.get("m2") == 1 and m.get("m3") == 1 and T.get("w_final") is True
          and T.get("m2_seen") and T.get("m3_seen") and T["m3_seen"] > T["m2_seen"] > seen,
          mails=m, subjects=[x["subject"] for x in (T.get("mails") or {}).get("w", [])][:3],
          m2_seen=T.get("m2_seen"), m3_seen=T.get("m3_seen"))


# ---------------------------------------------------------------- O: the one-minute windows
def sec_O():
    xml = fs_xml(description="e2e-29 premise", shell="echo premise").encode()
    pre = {"configure r29/cfg": status(U, J(it("cfg")) + "/configure"),
           "delete r29/del-a": status(U, J(it("del-a")) + "/doDelete", "POST"),
           "create in r29/drop": status(U, J(DROP) + "/createItem?name=pre", "POST", data=xml, headers=H_XML)}
    check("O", "[T1][T2][T3] premise: before any window the holder may not configure r29/cfg, delete r29/del-a or create "
          "in r29/drop (core refusal, nothing changed)",
          pre["configure r29/cfg"] == 403 and pre["delete r29/del-a"] in (400, 403) and pre["create in r29/drop"] == 403
          and exists(it("del-a")) and not exists(DROP + "/pre"), **pre)
    mark = records_mark()
    S["c"], gc = open_window(U, DROP, ["CREATE"], 1, "e2e-29 T3 create in r29/drop")
    S["da"], _ = open_window(U, it("del-a"), ["DELETE"], 1, "e2e-29 T2 delete r29/del-a")
    S["db"], _ = open_window(U, it("del-b"), ["DELETE"], 1, "e2e-29 T2 delete r29/del-b (left unused)")
    S["b"], gb = open_window(U, it("cfg"), ["CONFIGURE"], 1, "e2e-29 T1 configure r29/cfg")
    check("O", "the four windows are open and last one minute each", all(grant(S[k])["open"] for k in ("c", "da", "db", "b"))
          and gb["expiresAtMillis"] - gb["grantedAtMillis"] == 60000 and gc["expiresAtMillis"] - gc["grantedAtMillis"] == 60000,
          ids={k: S[k] for k in ("c", "da", "db", "b")})
    # T3: inside the CREATE window, a payload that tries to start without the lock and with a cron trigger
    payload = fs_xml(description="e2e-29 made by a window", shell="echo e2e-29 made", approval=False, timer=False,
                     upstream=False, cron=CRON).encode()
    S["made_at"] = lib.now_ms()
    made = {n: status(U, J(DROP) + f"/createItem?name={n.rsplit('/', 1)[1]}", "POST", data=payload, headers=H_XML)
            for n in (MADE, MADE2)}
    locks = {n: lock(n) for n in (MADE, MADE2)}
    acts = {n: activation(n) for n in (MADE, MADE2)}
    rec = {n: records(mark, "CREATE", n) for n in (MADE, MADE2)}
    locked = {"approvalRequired": "true", "blockTimer": "true", "blockUpstream": "true", "allowed": []}
    check("O", "[T3][X3] inside the CREATE window the holder creates r29/drop/made and made2 from a payload that clears "
          "the Batch Control switches: both exist, both carry the D-17/D-34 lock (approvalRequired, blockTimer, "
          "blockUpstream true, no allowed upstream job) whatever the payload said, neither is activated, and each CREATE "
          "record names w29 and the window",
          all(v == 200 for v in made.values()) and all(exists(n) for n in (MADE, MADE2))
          and all(v == locked for v in locks.values()) and all(v == "not activated" for v in acts.values())
          and all(len(v) == 1 and v[0].get("user") == U and v[0].get("grantId") == S["c"] for v in rec.values()),
          create=made, locks=locks, activation=acts, records={k: [(x.get("user"), x.get("grantId")) for x in v] for k, v in rec.items()})
    guard = {"nested": (status(U, J(DROP + "/inner") + "/createItem?name=nested", "POST", data=payload, headers=H_XML),
                        exists(DROP + "/inner/nested")),
             "root": (status(U, "/createItem?name=r29-root-by-window", "POST", data=payload, headers=H_XML),
                      exists("r29-root-by-window"))}
    check("O", "[T3] guard: the same CREATE window creates nothing in the nested folder r29/drop/inner or at the root "
          "(403, no item)", guard["nested"] == (403, False) and guard["root"] == (403, False), **{k: str(v) for k, v in guard.items()})
    mark = records_mark()
    cleared = status(U, J(MADE) + "/config.xml", "POST", headers=H_XML, data=fs_xml(
        description="e2e-29 made: lock cleared by the holder", shell="echo e2e-29 made", approval=False, timer=False,
        upstream=False, cron=CRON).encode())
    removed = status(U, J(MADE2) + "/config.xml", "POST", headers=H_XML, data=fs_xml(
        description="e2e-29 made2: property removed by the holder", shell="echo e2e-29 made2", cron=CRON).encode())
    S["locks_after"] = {n: lock(n) for n in (MADE, MADE2)}
    rec = {n: records(mark, "CONFIGURE", n) for n in (MADE, MADE2)}
    check("O", "[T3] through the window (D-35c Configure of items it created) the holder clears made's lock "
          "(approvalRequired, blockTimer, blockUpstream false: 200, stored) and saves made2 without the Batch Control "
          "property (200); each save is a CONFIGURE record naming w29 and the window",
          cleared == 200 and removed == 200
          and S["locks_after"][MADE] == {"approvalRequired": "false", "blockTimer": "false", "blockUpstream": "false", "allowed": []}
          and description(MADE2) == "e2e-29 made2: property removed by the holder"
          and all(len(v) == 1 and v[0].get("user") == U and v[0].get("grantId") == S["c"] for v in rec.values()),
          cleared=cleared, removed=removed, locks=S["locks_after"],
          records={k: [(x.get("user"), x.get("grantId")) for x in v] for k, v in rec.items()})
    note("O", "[T3] what the store holds for made2 after the holder's save without the property (the SPEC does not say "
         "whether the plugin adds it back; K judges only that it never runs unattended)", lock=S["locks_after"][MADE2])
    # T2: inside the DELETE windows
    other = status(U, J(it("del-other")) + "/doDelete", "POST")
    conferred_b = has_permission(U, it("del-b"), PERM + "DELETE")
    mark = records_mark()
    da = status(U, J(it("del-a")) + "/doDelete", "POST")
    rec = records(mark, "DELETE", it("del-a"))
    check("O", "[T2] inside the DELETE windows: the holder may not delete r29/del-other, which no window names (refused, "
          "it still exists); the window on r29/del-b confers Item/Delete on it; the holder deletes r29/del-a (gone, one "
          "DELETE record names w29 and its window)",
          other in (400, 403) and exists(it("del-other")) and conferred_b and da in (200, 302) and not exists(it("del-a"))
          and len(rec) == 1 and rec[0].get("user") == U and rec[0].get("grantId") == S["da"],
          del_other=other, del_b_conferred=conferred_b, del_a=da, records=[(x.get("user"), x.get("grantId")) for x in rec])


# ---------------------------------------------------------------- B: the expiry boundary (T1)
def sec_B():
    if "b" not in S:
        check("B", "[T1] needs the window of section O", False)
        return
    rid = S["b"]
    mark = records_mark()
    desc = "e2e-29 T1 saved inside the window"
    saved = status(U, J(it("cfg")) + "/config.xml", "POST", headers=H_XML,
                   data=fs_xml(description=desc, shell="echo cfg").encode())
    rec = records(mark, "CONFIGURE", it("cfg"))
    check("B", "[T1] inside the CONFIGURE window the holder saves r29/cfg's configuration (config.xml 200, the description "
          "stored, one CONFIGURE record names w29 and the window)", saved == 200 and description(it("cfg")) == desc
          and len(rec) == 1 and rec[0].get("user") == U and rec[0].get("grantId") == rid,
          status=saved, records=[(x.get("user"), x.get("grantId")) for x in rec])
    g = grant(rid)
    exp = g["expiresAtMillis"]
    offset, half_rtt = lib.clock_offset()
    samples = []
    import requests
    sess = requests.Session()
    sess.auth = (U, lib.pw(U))
    while True:
        t0 = time.time() * 1000 + offset
        r = sess.get(lib.BASE + J(it("cfg")) + "/configure", allow_redirects=False)
        t1 = time.time() * 1000 + offset
        samples.append((round(t0), round(t1), r.status_code, "Access Denied" in r.text))
        if t0 > exp + 3000:
            break
        time.sleep(0.2)
    tol = max(250, 2 * half_rtt)
    before = [s for s in samples if s[1] < exp - tol]
    after = [s for s in samples if s[0] > exp + tol]
    boundary = [s for s in samples if s not in before and s not in after]
    first_after = after[0] if after else None
    g2 = grant(rid)
    check("B", "[T1] the expiry boundary under continuous checks: every configure page answered before expiresAt is 200, "
          "every one requested after expiresAt is core's 403 (Access Denied) from the first on, which started within "
          "two seconds after expiresAt (the sampling reached the boundary); the window was not revoked (it expired, no "
          "revokedAtMillis)",
          len(before) >= 10 and all(s[2] == 200 for s in before) and len(after) >= 5
          and all(s[2] == 403 and s[3] for s in after) and first_after[0] - exp <= 2000 + tol
          and not g2.get("revokedAtMillis"),
          expires_at=exp, tolerance_ms=round(tol), before=len(before), before_codes=sorted({s[2] for s in before}),
          after=len(after), after_codes=sorted({s[2] for s in after}), first_after_ms=(first_after[0] - exp) if first_after else None,
          boundary=[(s[0] - exp, s[1] - exp, s[2]) for s in boundary], revoked=g2.get("revokedAtMillis"))
    late = status(U, J(it("cfg")) + "/config.xml", "POST", headers=H_XML,
                  data=fs_xml(description="e2e-29 T1 after the window", shell="echo cfg").encode())
    check("B", "[T1] after the window the holder's config.xml POST is refused (403) and the description is unchanged",
          late == 403 and description(it("cfg")) == desc and not has_permission(U, it("cfg"), PERM + "CONFIGURE"),
          status=late, description=description(it("cfg")))


# ---------------------------------------------------------------- E: after the windows' end
def sec_E():
    if not all(k in S for k in ("c", "db")):
        check("E", "[T2][T3] needs the windows of section O", False)
        return
    since = wait_expired(S["c"], S["db"], S["da"], S["b"])
    xml = fs_xml(description="e2e-29 late", shell="echo late").encode()
    db = status(U, J(it("del-b")) + "/doDelete", "POST")
    check("E", "[T2] after its DELETE window ended unused, the holder may not delete r29/del-b (refused, it still exists, "
          "no Delete permission), although the window had conferred Delete on it", db in (400, 403) and exists(it("del-b"))
          and not has_permission(U, it("del-b"), PERM + "DELETE"), status=db, ms_after_expiry=since)
    d0 = description(MADE)
    res = {"create r29/drop/late": status(U, J(DROP) + "/createItem?name=late", "POST", data=xml, headers=H_XML),
           "configure page made": status(U, J(MADE) + "/configure"),
           "config.xml made": status(U, J(MADE) + "/config.xml", "POST", data=xml, headers=H_XML),
           "delete made": status(U, J(MADE) + "/doDelete", "POST")}
    check("E", "[T3] after the CREATE window the holder has nothing on r29/drop or the job it created there beyond its own "
          "permissions: create 403 (no item), configure page 403, config.xml 403 (description unchanged), delete refused "
          "(it still exists)",
          res["create r29/drop/late"] == 403 and not exists(DROP + "/late") and res["configure page made"] == 403
          and res["config.xml made"] == 403 and description(MADE) == d0 and res["delete made"] in (400, 403)
          and exists(MADE), **res)
    rows = {k: lib.ended_row(U, S[k]) for k in ("c", "da", "db", "b")}
    how = {"c": "Expired", "db": "Expired", "b": "Expired", "da": f"Revoked (its item was deleted) by {U}"}
    check("E", "the holder's grants page lists the four windows once each, under Ended: the three left to run out as "
          "Expired, the one whose job the holder deleted as ended by that deletion (SPEC 8: deleting the item ends the "
          "window)", all(list(rows[k]) == ["ended"] and how[k] in rows[k]["ended"] for k in how), rows=rows)
    lib.shot_page(U, "/batch-control/grants/", "R29-W-E-01-grants-ended")


# ---------------------------------------------------------------- K: the window-created jobs and their timer
def wait_tick(extra=25):
    sec = int(gv("return java.time.LocalTime.now().getSecond()"))
    time.sleep((60 - sec) + extra)


def sec_K():
    if not (exists(MADE) and exists(MADE2)):
        check("K", "[R9] needs the jobs of section O", False)
        return
    nb = {n: next_build(n) for n in (MADE, MADE2)}
    wait_tick()
    # TRIGGER_BLOCKED is coalesced per job name and cause per hour (SPEC 6: "repeats within an hour are merged"), so
    # the record of made's refused timer is the one of the first tick after its creation; on a fresh Jenkins (CI) that
    # is this run's. A rerun within the hour on the same Jenkins finds the earlier run's record of the same name.
    tick_at = lib.now_ms()
    blocked = [r for r in lib.changes() if r.get("type") == "TRIGGER_BLOCKED" and r.get("target") == MADE
               and tick_at - 3600 * 1000 <= r.get("at", 0) <= tick_at]
    check("K", "[T3][R9][X3] not activated: at the next cron tick neither window-created job builds or waits in the queue, "
          "although made's lock was cleared (all three switches false) and made2 has no Batch Control property; a "
          "TRIGGER_BLOCKED record of the last hour names made's refused timer (repeats within the hour are merged into "
          "the first)",
          all(next_build(n) == nb[n] and not queue_items(n) for n in nb) and len(blocked) >= 1,
          next_build={n: next_build(n) for n in nb}, before=nb, created_at=S.get("made_at"),
          blocked=[(x.get("at"), (x.get("detail") or "")[:160]) for x in blocked][:2],
          locks=S.get("locks_after"))
    st, aid = lib.act_req("requester", MADE, "ACTIVATE", "e2e-29 R9 guard: activate the window-created job")
    ok = False
    if aid:
        _, dst, _t = lib.browser_decide("approver-1", aid, "approve", "e2e-29 R9 guard", "R29-W-K-01-activation-approved",
                                        kind="activations")
        ok = lib.wait_until(lambda: activation(MADE) == "activated", timeout=20, step=1)
    wait_tick(30)
    res, console = lib.wait_build(MADE, nb[MADE], 60)
    cause = api("admin", J(MADE) + f"/{nb[MADE]}/api/json?tree=actions[causes[shortDescription]]").text
    check("K", "[R9] guard: once its ACTIVATE request is approved, made builds at the next tick with the timer cause; "
          "made2, still not activated, does not",
          aid and ok and res == "SUCCESS" and "Started by timer" in cause and next_build(MADE2) == nb[MADE2]
          and not queue_items(MADE2), activation_request=aid, status=st, result=res, made2_next=next_build(MADE2))


# ---------------------------------------------------------------- R: revocation (T4)
def sec_R():
    rid, _ = open_window(U, it("rev"), ["CONFIGURE"], 15, "e2e-29 T4 window to revoke")
    warm = [status(U, J(it("rev")) + "/configure") for _ in range(3)]
    mark = records_mark()
    s = Session("admin", fresh=True)
    s.go(f"/batch-control/grants/{rid}/")
    btn = s.page.locator("#main-panel a, #main-panel button", has_text=re.compile(r"^\s*Revoke\s*$"))
    found = btn.count()
    after = None
    if found:
        btn.first.click()
        lib.L19._r6.opened(s.page)
        s.shot("dialog[open]", "R29-W-R-01-revoke-confirm")
        with s.page.expect_navigation(timeout=20000):
            s.page.locator("dialog[open] button[data-id=ok]").first.click()
        s.page.wait_for_load_state("load")
        after = status(U, J(it("rev")) + "/configure")   # the very next check, no wait
        s.shot("#main-panel", "R29-W-R-02-revoked")
    s.done()
    g = grant(rid)
    rec = records(mark, "GRANT_REVOKE")
    csv = api("approver-1", "/batch-control/history/changes.csv").content.decode("utf-8-sig", "replace")
    row = lib.ended_row(U, rid)
    check("R", "[T4] the administrator revokes the window on its page: the holder's very next check is 403 (it was 200 "
          "three times just before); the window file has revokedAtMillis and revokedBy admin",
          found == 1 and warm == [200, 200, 200] and after == 403 and g.get("revokedAtMillis") and g.get("revokedBy") == "admin"
          and not g["open"], warm=warm, after=after, revoked_by=g.get("revokedBy"))
    check("R", "[T4] recorded: exactly one GRANT_REVOKE record, naming admin, the window and r29/rev; changes.csv lists "
          "it; the holder's grants page lists the window under Ended as revoked by admin",
          len(rec) == 1 and rec[0].get("user") == "admin" and rec[0].get("grantId") == rid and it("rev") in (rec[0].get("target") or "")
          and any("GRANT_REVOKE" in line and it("rev") in line for line in csv.splitlines())
          and list(row) == ["ended"] and any("Revoked" in c for c in row["ended"]) and any("admin" in c for c in row["ended"]),
          records=[{k: x.get(k) for k in ("type", "user", "grantId", "target", "detail")} for x in rec], row=row)


# ---------------------------------------------------------------- S: self-approval (T8)
def sec_S():
    job = it("self")
    n0 = len(lib.grant_requests_of(DUAL, job))
    st1, r1, _ = grant_req(DUAL, job, ["CONFIGURE"], 15, "e2e-29 T8 naming myself", approvers=(DUAL,))
    st2, r2, _ = grant_req(DUAL, job, ["CONFIGURE"], 15, "e2e-29 T8 naming approver-1 and myself", approvers=("approver-1", DUAL))
    n1 = len(lib.grant_requests_of(DUAL, job))
    check("S", "[T8] a listed approver who is not an administrator may not designate themself on their own window "
          "request: alone or next to approver-1, refused with a 4xx and nothing stored",
          400 <= st1 < 500 and 400 <= st2 < 500 and r1 is None and r2 is None and n1 == n0, alone=st1, with_other=st2,
          stored_before=n0, stored_after=n1)
    st3, r3, _ = grant_req(DUAL, job, ["CONFIGURE"], 15, "e2e-29 T8 naming approver-1")
    ch = status(DUAL, f"/batch-control/grants/{r3}/changeApprover", "POST", data=[("approvers", DUAL)]) if r3 else None
    ap = status(DUAL, f"/batch-control/grants/{r3}/approve", "POST", data={"comment": "e2e-29 my own"}) if r3 else None
    rq = lib.grant_request(r3) if r3 else {}
    check("S", "[T8] guard: naming approver-1 the same requester's request is stored PENDING; its approver change to "
          "itself is refused (the stored approvers still approver-1) and its own POST approve is refused (PENDING, no "
          "window)", r3 is not None and rq.get("status") == "PENDING" and rq.get("approvers") == "approver-1"
          and ch is not None and ch >= 400 and ap is not None and ap >= 400 and not grant(r3).get("_exists"),
          submit=st3, change=ch, approve=ap, stored={k: rq.get(k) for k in ("status", "approvers", "decidedBy")})
    st4, r4, _ = grant_req("admin", job, ["CONFIGURE"], 15, "e2e-29 T8 administrator self-approval", approvers=("admin",))
    if r4:
        lib.browser_decide("admin", r4, "approve", "e2e-29 T8 self", "R29-W-S-01-admin-self-approved", kind="grants")
    rq = lib.grant_request(r4) if r4 else {}
    g = grant(r4) if r4 else {}
    check("S", "[T8] the administrator files a window request naming admin and approves it on its page: APPROVED, "
          "decided by admin, the window exists for admin",
          r4 is not None and rq.get("status") == "APPROVED" and rq.get("decidedBy") == "admin" and g.get("user") == "admin"
          and g.get("open"), submit=st4, stored={k: rq.get(k) for k in ("status", "requester", "decidedBy")})
    try:
        lib.set_config(allowAdminSelfApproval="false")
        n2 = len(lib.grant_requests_of("admin", job))
        st5, r5, r = grant_req("admin", job, ["CONFIGURE"], 15, "e2e-29 T8 self-approval switched off", approvers=("admin",))
        n3 = len(lib.grant_requests_of("admin", job))
    finally:
        back = lib.set_config(allowAdminSelfApproval=str(bool(S.get("self0", True))).lower())
    check("S", "[T8] guard of the exception: with allowAdminSelfApproval off the administrator's window request naming "
          "admin is refused and nothing is stored; the setting is put back",
          400 <= st5 < 500 and r5 is None and n3 == n2 and back["allowAdminSelfApproval"] == S.get("self0", True),
          status=st5, stored_before=n2, stored_after=n3, refusal=text_of(r.text)[-200:])


# ---------------------------------------------------------------- Y: the administrator's self-approval flag (T8, #182)
def sec_Y():
    st4, r4, _ = grant_req("admin", it("self"), ["CONFIGURE"], 15, "e2e-29 T8 administrator self-approval (flag)",
                           approvers=("admin",))
    shown = dst = None
    if r4:
        _, dst, shown = lib.browser_decide("admin", r4, "approve", "e2e-29 T8 self", "R29-W-Y-01-admin-self-approved",
                                           kind="grants")
    rq = lib.grant_request(r4) if r4 else {}
    check("Y", "[T8] SPEC 2: an administrator's self-approval is recorded as selfApproved=true (the stored request)",
          rq.get("selfApproved") == "true", children=rq.get("_children"), self_approved=rq.get("selfApproved"),
          page_says_self=bool(shown and "Self-approved" in shown))
    # The page half: the decision posts the form and returns to the request's own page (the same browser context).
    # Guard: the administrator's request decided by approver-1 is no self-approval, so neither the file nor the page
    # may say so (fails if every approval, or every request of an administrator, were flagged).
    st6, r6, _ = grant_req("admin", it("self"), ["CONFIGURE"], 15, "e2e-29 T8 administrator's request, approver-1 decides")
    other = ost = None
    if r6:
        _, ost, other = lib.browser_decide("approver-1", r6, "approve", "e2e-29 T8 not self",
                                           "R29-W-Y-02-approver-approved", kind="grants")
    rq6 = lib.grant_request(r6) if r6 else {}
    check("Y", "[T8] SPEC 2: the request page of the administrator's self-approved window request (the page the "
          "Approve button returns to: its id, Approved, decided by admin) has exactly one row \"Self-approved\" with "
          "\"Yes\"; guard: the administrator's request approved by approver-1 stores no selfApproved=true and its page "
          "(its id, Approved, decided by approver-1) has no \"Self-approved\"",
          self_row(shown) == ["Yes"] and decided_page(shown, r4, "admin")
          and r6 is not None and rq6.get("status") == "APPROVED" and rq6.get("decidedBy") == "approver-1"
          and rq6.get("selfApproved") != "true" and decided_page(other, r6, "approver-1") and "Self-approved" not in other,
          self_row=self_row(shown), self_page=page_rows(shown), decision_status=dst,
          guard={"submit": st6, "id": r6, "decision_status": ost, "stored": {k: rq6.get(k) for k in
                 ("status", "requester", "decidedBy", "selfApproved")}, "page_row": self_row(other),
                 "page": page_rows(other)})


def row_values(text, header):
    """The values of the summary-table rows `header` in a page's visible text (header cell, a tab, the value)."""
    return re.findall(rf"(?m)^[ \t]*{re.escape(header)}[ \t]*:?[ \t]+(\S.*?)[ \t]*$", text or "")


def self_row(text):
    return row_values(text, "Self-approved")


def page_rows(text):
    return {h: row_values(text, h) for h in ("ID", "Status", "Decided by", "Self-approved")}


def decided_page(text, rid, decider):
    """True when `text` is the visible text of grant request `rid`'s page, approved and decided by `decider`."""
    ids = [v.split()[0] for v in row_values(text, "ID")]
    status = row_values(text, "Status")
    return (bool(text) and ids == [rid] and len(status) == 1 and status[0].lower().startswith("approved")
            and row_values(text, "Decided by") == [decider])


# ---------------------------------------------------------------- L: durations (T10)
def sec_L():
    job = it("dur")
    n0 = lib.grant_requests_of(U, job)
    bad = {d: grant_req(U, job, ["CONFIGURE"], d, f"e2e-29 T10 duration {d!r}")[0]
           for d in ("241", "1440", "99999999999", "0", "-15", "abc", "", "15.5")}
    n1 = lib.grant_requests_of(U, job)
    check("L", "[T10] a duration the SPEC refuses is refused with a 4xx and stores nothing: above maxGrantMinutes (241, "
          "1440, 99999999999; SPEC 8 maxGrantMinutes, T-08-09), zero, negative, not a whole number (abc, empty, 15.5)",
          all(400 <= v < 500 for v in bad.values()) and len(n1) == len(n0), codes=bad, stored_before=len(n0), stored_after=len(n1))
    st7, r7, _ = grant_req(U, job, ["CONFIGURE"], "7", "e2e-29 T10 duration 7 (not an option, below the maximum)")
    note("L", "[T10] a POSTed duration that is not one of grantDurationOptions (1, 15, 30, 60) but within maxGrantMinutes: "
         "SPEC 5/8 call the options the request's choices and maxGrantMinutes the limit, and do not say whether the "
         "endpoint refuses another length (reported for the owner's decision)", status=st7,
         stored=lib.grant_request(r7).get("durationMinutes") if r7 else None)
    good = {d: grant_req(U, job, ["CONFIGURE"], d, f"e2e-29 T10 duration {d}") for d in ("1", "60")}
    stored = {d: lib.grant_request(v[1]).get("durationMinutes") if v[1] else None for d, v in good.items()}
    g60 = None
    if good["60"][1]:
        decide("approver-1", "grants", good["60"][1], "approve", "e2e-29 T10")
        g60 = grant(good["60"][1])
    s = Session(U)
    s.go("/batch-control/grants/")
    opener = s.page.locator("button[data-batch-control-request=grant][data-type=dialog-opener]")
    offered = None
    if opener.count():
        opener.first.click()
        s.page.wait_for_selector("dialog[open] select[name=durationMinutes]", timeout=10000)
        offered = s.page.locator("dialog[open] select[name=durationMinutes] option").evaluate_all("os => os.map(o => o.value)")
        s.shot("dialog[open]", "R29-W-L-01-durations")
    s.done()
    check("L", "[T10] guard: 1 and 60 are stored with that duration; the 60-minute window approved lasts exactly 60 "
          "minutes; the request dialog offers exactly 1, 15, 30 and 60",
          stored == {"1": "1", "60": "60"} and g60 and g60["expiresAtMillis"] - g60["grantedAtMillis"] == 3600000
          and offered == ["1", "15", "30", "60"], stored=stored, offered=offered,
          window_ms=(g60["expiresAtMillis"] - g60["grantedAtMillis"]) if g60 else None)


# ---------------------------------------------------------------- cleanup
def cleanup():
    out = {}
    th = S.get("t6_thread")
    if th is not None and th.is_alive():
        th.join(timeout=420)
    if "lead0" in S:
        out["lead"] = lib.set_config(notifyBeforeExpiryMinutes=S["lead0"])
    if "self0" in S:
        out["self"] = lib.set_config(allowAdminSelfApproval=str(bool(S["self0"])).lower())
    if "approvers0" in S:
        out["approvers"] = lib.set_approvers(S["approvers0"])
    for u in (U, DUAL, "admin"):
        out[u] = (lib.cancel_pending(u, F + "/"), lib.revoke_open(u, F + "/"))
    out["deleted"] = {n: delete(n) for n in (MADE, MADE2)}
    check("cleanup", "the lead, admin self-approval and the approver list are back; the window-created cron jobs are "
          "deleted; nothing of this run is left pending or open",
          (("lead0" not in S) or out["lead"]["notifyBeforeExpiryMinutes"] == S["lead0"])
          and (("approvers0" not in S) or lib.approvers() == S["approvers0"]) and all(out["deleted"].values()),
          **{k: str(v)[:300] for k, v in out.items()})


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "N": sec_N, "O": sec_O, "B": sec_B, "E": sec_E, "K": sec_K, "R": sec_R, "S": sec_S,
                       "Y": sec_Y, "L": sec_L, "Z": sec_Z}, cleanup)
