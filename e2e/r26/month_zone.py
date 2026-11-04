"""e2e-26 #33: a date-range query does not depend on the JVM default time zone (wave-B contract #33; JenkinsRule rows
T-04-74/75, MonthBucketZoneTest).

usage: python month_zone.py [AWBC]   rows: out/month_zone.jsonl, shots: screenshots/run-26/R26-33-*.png
The controller moves between two zones by re-creating its container with another TZ (docker-compose.yml passes ${TZ}
to the container and to the JVM as -Duser.timezone) on the same JENKINS_HOME volume: zone A is the stack's zone
(Asia/Seoul under ci/run.sh), zone B is UTC (Asia/Seoul when A is UTC). The compose project and files come from the
container's labels; `compose config` must first render exactly the running container's environment (lib.recreate_in_zone).
Restarts Jenkins twice (B, then back to A): a `last` unit.

Records are written at instants within the offset of the last month boundary, so that zone A and zone B put them in
different months. While writing, the plugin clock is fixed at that instant in the JVM's zone through the script console
(BatchClock.setForTest, as MonthBucketZoneTest does; reset right after): the administrator creates the item over REST,
which writes its CREATE change record at that instant, and the script console appends the item's run record through the
plugin's own store (FileStore.appendRunRecord with that start time; a real build would carry the wall-clock start time).
Item names carry a per-run tag, so the unit can run again on the same stack.

A  arrangement: run control on; zone A read from the container; the instants: west (written in A), east (written in B,
   read in A) and mid (mid-month, the same day in both zones).
W  in zone A: r26-zone-west-<tag> and r26-zone-mid-<tag> written; premise: the runs and changes listings for the west
   day in zone A list the west item (the record is stored and readable).
B  restart in zone B (premises: the container's TZ, the JVM's default zone and the plugin clock's zone are B). Contract
   #33: for west's day in zone B the History runs listing and changes listing (auditor, browser) and runs.csv and
   changes.csv (auditor, REST) all list r26-zone-west-<tag>. Guards: for the neighbouring day (west's day in zone A) none
   lists it; for mid's day all four list r26-zone-mid-<tag>; a range spanning both days lists west on all four
   (premise). Then r26-zone-east-<tag> is written in zone B.
C  restart in zone A again (which also restores the stack's zone). Contract #33 for east, the mirror: for east's day in
   zone A all four surfaces list it; guards: the neighbouring day (east's day in zone B) does not, mid is listed, the
   spanning range lists east.

Not here (JenkinsRule rows or nobody): incidents, the request history, the monthly summary counters, retention across a
zone change, the default 7-day window without explicit dates, and a run record written by a real build at a month
boundary (it would need the controller's wall clock at that instant). Without the fix the contract checks of B and C
fail on all four surfaces while the guards and premises pass."""
import datetime
import re
import secrets
import sys
import urllib.parse
from zoneinfo import ZoneInfo

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, Session  # noqa: E402

lib.LOGNAME[0] = "month_zone"
VIEWER = "auditor"
TAG = secrets.token_hex(2)
NAME = {k: f"r26-zone-{k}-{TAG}" for k in ("west", "east", "mid")}
FIXTURE = re.compile(rf"\br26-zone-(west|east|mid)-{TAG}\b")
MAX_PAGES = 20  # the pages listing() follows through "Older" before it fails
S = {}


def day(ms, zone):
    return datetime.datetime.fromtimestamp(ms / 1000, ZoneInfo(zone)).date().isoformat()


def month(ms, zone):
    return day(ms, zone)[:7]


def instants(now_ms, a, b):
    """west and east: two instants (half-hour steps) within a day of the latest past month boundary where zones a and b
    name different months; mid: the 15th of the month before, at an hour where both zones name the same day."""
    utc = datetime.timezone.utc
    now = datetime.datetime.fromtimestamp(now_ms / 1000, utc)
    firsts = [datetime.datetime(now.year, now.month, 1, tzinfo=utc)]
    prev = firsts[0] - datetime.timedelta(days=1)
    firsts.append(datetime.datetime(prev.year, prev.month, 1, tzinfo=utc))
    for m in firsts:
        cand = []
        for k in range(-48, 49):
            t = int((m + datetime.timedelta(minutes=30 * k)).timestamp() * 1000)
            if t < now_ms - 600000 and month(t, a) != month(t, b):
                cand.append(t)
        if len(cand) >= 4:
            mid_day = (m - datetime.timedelta(days=16)).replace(hour=0)
            mid = next(int((mid_day + datetime.timedelta(hours=h)).timestamp() * 1000) for h in range(24)
                       if day(int((mid_day + datetime.timedelta(hours=h)).timestamp() * 1000), a)
                       == day(int((mid_day + datetime.timedelta(hours=h)).timestamp() * 1000), b))
            return {"west": cand[1], "east": cand[-2], "mid": mid}
    return None


def jvm_zone():
    return lib.gv(lib.CLOCK + "return java.util.TimeZone.getDefault().ID + ' ' + bc.clock().zone.id")


def write(key, at_ms):
    """Writes the CREATE change record (admin's createItem over REST) and a run record (the plugin's store, through the
    script console) of NAME[key] with the plugin clock fixed at `at_ms` in the JVM's zone; resets the clock after."""
    fixed = lib.gv(lib.CLOCK + f"bc.setForTest(java.time.Clock.fixed(java.time.Instant.ofEpochMilli({at_ms}L), "
                               "java.time.ZoneId.systemDefault())); return bc.now().toEpochMilli() + ' ' + bc.clock().zone.id")
    try:
        created = lib.create(NAME[key], lib.job_xml("fs", shell="echo r26"))
        run = lib.gv(f"""def cl = jenkins.model.Jenkins.get().pluginManager.uberClassLoader
def RR = cl.loadClass('{lib.BC}.model.RunRecord'); def CT = cl.loadClass('{lib.BC}.model.CauseType')
def FS = cl.loadClass('{lib.BC}.store.FileStore')
def r = RR.getConstructor(String, String, int.class, CT, String, java.time.Instant, long.class)
  .newInstance('{NAME[key]}#1', '{NAME[key]}', 1, Enum.valueOf(CT, 'USER'), 'SUCCESS', java.time.Instant.ofEpochMilli({at_ms}L), 1000L)
FS.getMethod('get').invoke(null).appendRunRecord(r)
return 'appended'""")
    finally:
        reset = lib.gv(lib.CLOCK + "bc.reset(); return bc.clock().zone.id")
    return {"clock": fixed, "created": created, "run": run, "clock_after": reset}


def listing(kind, frm, to, shot):
    """The fixture names in the table rows of every page of the History listing as the auditor sees it (a new browser
    context), the status of page 1 and the page numbers read. The listing shows 50 rows a page, and on the first days of
    a month the shard's own records of today share the date range with the fixture, so the later pages are reached
    through the listing's own "Older" link, as a user pages (each click waits for its navigation; the page= of the new
    URL must be the previous page plus one); more than MAX_PAGES pages or a skipped or repeated page raises (a FAIL,
    never a silent cut)."""
    s = Session(VIEWER, fresh=True)
    try:
        r = s.go(f"/batch-control/history/?kind={kind}&from={frm}&to={to}")
        rows, pages = s.page.locator("tr").all_inner_texts(), [1]
        s.shot("#main-panel", shot)
        older = s.page.get_by_role("navigation", name="Pages of history records").get_by_role("link", name="Older",
                                                                                              exact=True)
        while older.count():
            if len(pages) >= MAX_PAGES:
                raise RuntimeError(f"the {kind} listing for {frm}..{to} still offers Older after {pages} pages "
                                   f"({s.page.url})")
            with s.page.expect_navigation(wait_until="load"):
                older.click()
            n = int(dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(s.page.url).query)).get("page", 0))
            if n != pages[-1] + 1:
                raise RuntimeError(f"Older on page {pages[-1]} of the {kind} listing led to page {n} ({s.page.url})")
            pages.append(n)
            rows += s.page.locator("tr").all_inner_texts()
        return (r.status if r else None), sorted({m.group(0) for t in rows for m in FIXTURE.finditer(t)}), pages
    finally:
        s.done()


def exported(kind, frm, to):
    r = api(VIEWER, f"/batch-control/history/{kind}.csv?from={frm}&to={to}")
    return r.status_code, sorted({m.group(0) for m in FIXTURE.finditer(r.text)})


def surfaces(frm, to, shot):
    """Which fixture names each of the four surfaces lists for the days frm..to; the HTTP status of each; the pages read
    of each listing."""
    out, codes, pages = {}, {}, {}
    for kind in ("runs", "changes"):
        codes[f"{kind} listing"], out[f"{kind} listing"], pages[f"{kind} listing"] = listing(kind, frm, to,
                                                                                            f"{shot}-{kind}")
        codes[f"{kind}.csv"], out[f"{kind}.csv"] = exported(kind, frm, to)
    return out, codes, pages


def on_all(sec, key, frm, to, want, what, shot):
    got, codes, pages = surfaces(frm, to, shot)
    wrong = [s for s, names in got.items() if (NAME[key] in names) != want]
    span = frm if frm == to else f"{frm}..{to}"
    check(sec, f"{what}: for {span} {NAME[key]} is {'' if want else 'not '}listed on the runs listing, runs.csv, the "
          "changes listing and changes.csv", not wrong and all(c == 200 for c in codes.values()),
          wrong_on=wrong, http=codes, listed=got, pages=pages, zone=S.get("zone_now"))


def move(sec, zone):
    res = lib.recreate_in_zone(zone)
    S["zone_now"] = zone
    jz = jvm_zone() if res.get("ok") else None
    check(sec, f"precondition: the controller restarted in {zone} (container TZ, JVM default zone and plugin clock zone)",
          res.get("ok") and jz == f"{zone} {zone}", jvm=jz, recreate=res)
    return bool(res.get("ok"))


# ---------------------------------------------------------------- sections
def sec_A():
    a = lib.container_env().get("TZ") or "UTC"
    b = "UTC" if a != "UTC" else "Asia/Seoul"
    S.update(zone_a=a, zone_b=b, zone_now=a)
    now = lib.now_ms()
    t = instants(now, a, b)
    S["t"] = t
    sw = lib.switches()
    jz = jvm_zone()
    check("A", "arrangement: run control on; the JVM and the plugin clock run in the stack's zone A; boundary instants "
          "found where zones A and B name different months", sw["run_control"] and t is not None and jz == f"{a} {a}",
          zone_a=a, zone_b=b, jvm=jz, names=NAME,
          instants={k: {"utc": datetime.datetime.fromtimestamp(v / 1000, datetime.timezone.utc).isoformat(),
                        "day_a": day(v, a), "day_b": day(v, b)} for k, v in (t or {}).items()})


def sec_W():
    a, b, t = S["zone_a"], S["zone_b"], S["t"]
    w = {k: write(k, t[k]) for k in ("west", "mid")}
    check("W", f"arrangement (zone {a}): the west and mid items and their run records are written with the plugin clock "
          "at their instants", all(v["created"] == 200 and v["run"] == "appended" and v["clock_after"] == a
                                   and v["clock"].startswith(str(t[k])) for k, v in w.items()), written=w)
    d = day(t["west"], a)
    got, codes, pages = surfaces(d, d, "R26-33-W-premise")
    check("W", f"premise (zone {a}): for {d} the four surfaces list {NAME['west']} (stored and readable where it was "
          "written)", all(NAME["west"] in v for v in got.values()), listed=got, http=codes, pages=pages)
    note("W", "the month files that hold the west records (the bucket is the plugin's choice)",
         files=lib.sh(f"grep -l {NAME['west']} {lib.STORE}/runs/*.jsonl {lib.STORE}/changes/*.jsonl; true")[1].split())


def check_side(sec, key, read_zone, write_zone, shot):
    t = S["t"][key]
    d, other = day(t, read_zone), day(t, write_zone)
    on_all(sec, key, d, d, True, f"contract #33 ({key} written in {write_zone}, read in {read_zone})", f"{shot}-day")
    on_all(sec, key, other, other, False, f"guard: the neighbouring day ({key} lies on {d} in {read_zone})",
           f"{shot}-neighbour")
    lo, hi = sorted((d, other))
    on_all(sec, key, lo, hi, True, f"premise: {key} is stored and readable (a range spanning both days)", f"{shot}-range")
    m = day(S["t"]["mid"], read_zone)
    on_all(sec, "mid", m, m, True, "guard: the mid-month item (the same day in both zones)", f"{shot}-mid")


def sec_B():
    a, b = S["zone_a"], S["zone_b"]
    if not move("B", b):
        return
    check_side("B", "west", b, a, "R26-33-B-west")
    w = write("east", S["t"]["east"])
    check("B", f"arrangement (zone {b}): the east item and its run record are written with the plugin clock at its "
          "instant", w["created"] == 200 and w["run"] == "appended" and w["clock_after"] == b, written=w)
    S["east"] = w


def sec_C():
    a, b = S["zone_a"], S["zone_b"]
    if not move("C", a):
        return
    if S.get("east"):
        check_side("C", "east", a, b, "R26-33-C-east")


def cleanup():
    a = S.get("zone_a")
    now = lib.container_env().get("TZ")
    res = lib.recreate_in_zone(a) if a and now != a else {"ok": True, "unchanged": now}
    clock = lib.gv(lib.CLOCK + "bc.reset(); return bc.clock().zone.id") if lib.plugin_active() else None
    note("cleanup", "the stack is back in its zone and the plugin clock is the system clock", zone=a, recreate=res,
         clock=clock)
    if not res.get("ok"):
        raise RuntimeError(f"the controller is not back in {a}: {res}")


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "W": sec_W, "B": sec_B, "C": sec_C}, cleanup)
