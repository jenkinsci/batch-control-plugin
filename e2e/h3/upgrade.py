"""Gap H3: upgrade a real controller from the released plugin to the .hpi of this build; existing data loads and works.

usage: python h3/upgrade.py          verdicts: "PASS h3 <check> {json}" / "FAIL h3 <check> {json}", exit 1 on any FAIL
                                     artefacts: h3/out/ (snapshots, both controller logs, the rows)

The unit runs its own controller (h3/compose.yml: own compose project, container and JENKINS_HOME volume, own port), so
it shares nothing with the shard's Jenkins and needs no `last`; it removes that stack and its volume at its end.

Phase A  the released plugin: batch-control RELEASED (RELEASED_SHA256 pinned; the update center's plugin-versions.json
         publishes the same digest) from BC_RELEASED_HPI when that file exists, else downloaded from the update center
         (redirects followed, retried with backoff on 429/5xx and connection errors) into BC_RELEASED_HPI or
         ci/.cache/released/. A fresh controller boots with it, then data is made as people make it: the administrator
         installs the Batch Control matrix strategy (script console: the matrix UI is not what this unit tests) and saves
         the Batch Control configuration on its page in a browser (both switches, approvers, non-default values); jobs are
         created and configured over REST by the administrator (CREATE/CONFIGURE records; one CREATE record is written
         with the plugin clock set to last month, so there are two month buckets); a cron job is activated through an
         approved ACTIVATE request and runs on its timer, a second cron job only has a pending ACTIVATE request; run
         requests are filed and decided over the plugin's REST endpoints: one EXECUTED, one REJECTED, one CANCELLED, one
         PENDING and one APPROVED whose run waits in the queue (no executor); a permission window (CONFIGURE on one job)
         is approved and a second window request stays PENDING; a failing build opens an incident, which is acknowledged.
         Then a snapshot of everything observable: each entity loaded through the plugin's store (every getter), the
         status each item's page shows, the job pages' activation notices, the four CSV exports, the global
         configuration, the authorization strategy and its entries, the active Batch Control administrative monitors, the
         store's file names and the queue.
Phase B  the controller stops cleanly (the queue is saved), BC_H3_TAMPER (proof hook only, see below) runs on the volume,
         and the container is re-created with the .hpi of this build (BC_H3_CURRENT_HPI, default target/batch-control.hpi)
         on the same volume.
Phase C  checks, each its own verdict. Loading: the plugin under test is the build's version and active, no plugin
         failed, the controller log of the new boot has no XStream/class-loading error and no Batch Control warning
         about an unreadable or skipped file, the old-data monitor names no Batch Control object, the active Batch
         Control monitors are those of phase A. Identical state: strategy class and entries, configuration, every
         entity (no entity missing, every phase A getter value equal), every page status and activation notice, the
         CSV rows (requests and incidents equal; changes and runs keep every phase A row), the store's files, no second
         activation seeding. Still working: with the executors back, the queued approved run starts exactly once with
         its parameters; the activated cron job's timer fires after the upgrade (SPEC 6a, a build whose queue item is not
         one restored from phase A) while the not activated one never builds; the pending request is approved and runs
         with its parameters; the window still lets its holder change the job's configuration (and only that job) and the
         change record names the window; the pending window request and the pending activation request are approved and
         take effect; a new run request is accepted with a UUID; the acknowledged incident is resolved; the History
         listings show the old records, including last month's.

Environment: BC_RELEASED_HPI (path of the released .hpi: used when present and its digest matches, written there when
downloaded), BC_H3_CURRENT_HPI, BC_H3_PORT (default BC_PORT + 100), BC_H3_PROJECT (default bc-h3-<BC_SHARD or local>),
BC_KEEP=1 (keep the stack), BC_H3_TAMPER (a shell snippet run between the phases in a throwaway container with the
working directory JENKINS_HOME and the phase A ids as variables: PENDING, APPROVED, EXECUTED, REJECTED, CANCELLED, GRANT
(the window's grant id), GRANT_REQUEST, GRANT_PENDING, ACT_APPROVED, ACT_PENDING, INCIDENT; used only to show that the
checks go red, never in CI).

Not covered here: an upgrade between two different code bases (on the day this unit was written the release and main
had the same src/main, so the unit is a guard for the next change), Pipeline jobs, folders, files and secrets in run
parameters (r19/restart.py covers them across a restart), role-strategy, JCasC-configured Batch Control, retention.
"""
import base64
import csv
import hashlib
import io
import json
import os
import pathlib
import re
import subprocess
import sys
import time
import zipfile
from datetime import datetime, timedelta, timezone

import requests

HERE = pathlib.Path(__file__).resolve().parent
E2E = HERE.parent
ROOT = E2E.parent
OUT = HERE / "out"
OUT.mkdir(parents=True, exist_ok=True)

RELEASED = "1414.vc347803c1997"
RELEASED_URL = f"https://updates.jenkins.io/download/plugins/batch-control/{RELEASED}/batch-control.hpi"
# sha256 of batch-control-1414.vc347803c1997.hpi, computed from the download on 2026-10-31 and equal to the update
# center's plugin-versions.json entry ("sha256": "n6uqGaErSYZMz7Fiw4WEnNmr/qdbvjaQq42z87xtNwY=", base64).
RELEASED_SHA256 = "9fabaa19a12b49864ccfb162c385849cd9abfea75bbe3690ab8db3f3bc6d3706"
BC = "io.jenkins.plugins.batchcontrol"
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

ENV = {}
for _line in (E2E / ".env").read_text().splitlines():
    if "=" in _line and not _line.startswith("#"):
        _k, _v = _line.split("=", 1)
        ENV[_k] = _v
PW = {"admin": ENV["BC_ADMIN_PASSWORD"], "requester": ENV["BC_REQUESTER_PASSWORD"],
      "approver-1": ENV["BC_APPROVER_PASSWORD"], "approver-2": ENV["BC_APPROVER_PASSWORD"]}
PORT = int(os.environ.get("BC_H3_PORT") or int(os.environ.get("BC_PORT", "18080")) + 100)
PROJECT = os.environ.get("BC_H3_PROJECT") or f"bc-h3-{os.environ.get('BC_SHARD', 'local')}"
CONTAINER = f"{PROJECT}-jenkins"
VOLUME = f"{PROJECT}_h3_home"
IMAGE = "batch-control-e2e-jenkins:2.568.3"
BASE = f"http://localhost:{PORT}"
COMPOSE = ["docker", "compose", "-p", PROJECT, "-f", str(HERE / "compose.yml")]

N = {"pass": 0, "fail": 0}
ROWS = OUT / "upgrade.jsonl"


def check(name, ok, **facts):
    """One verdict line; ci/shard.py fails the step on a FAIL line (and on the non-zero exit at the end)."""
    word = "PASS" if ok else "FAIL"
    N["pass" if ok else "fail"] += 1
    line = json.dumps(facts, default=str, sort_keys=True)
    if len(line) > 4000:
        line = line[:4000] + "...(cut; the full row is in h3/out/upgrade.jsonl)"
    print(f"{word} h3 {name} {line}", flush=True)
    with open(ROWS, "a", encoding="utf-8") as fh:
        fh.write(json.dumps({"verdict": word, "check": name, **facts}, default=str) + "\n")
    return ok


def info(what, **facts):
    print(f"NOTE h3 {what} {json.dumps(facts, default=str, sort_keys=True)[:2000]}", flush=True)


def poll(fn, timeout, step=2.0):
    """Calls fn until it returns a truthy value or `timeout` seconds have passed; returns the last value."""
    t0 = time.time()
    while True:
        v = fn()
        if v or time.time() - t0 >= timeout:
            return v
        time.sleep(step)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------- the two artefacts
def released_hpi():
    """The released .hpi, digest-checked: BC_RELEASED_HPI when it exists and matches, else downloaded (into
    BC_RELEASED_HPI or ci/.cache/released/) with retries. Raises when no file with the pinned digest can be had."""
    target = pathlib.Path(os.environ.get("BC_RELEASED_HPI") or E2E / "ci" / ".cache" / "released"
                          / f"batch-control-{RELEASED}.hpi").resolve()
    if target.is_file():
        got = sha256(target)
        if got == RELEASED_SHA256:
            return target, "cached"
        info("released-hpi-cache-mismatch", path=str(target), sha256=got)
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(".part")
    last = None
    for attempt in range(1, 7):
        try:
            r = requests.get(RELEASED_URL, timeout=120, allow_redirects=True, stream=True)
            if r.status_code == 200:
                with open(tmp, "wb") as fh:
                    for chunk in r.iter_content(1 << 20):
                        fh.write(chunk)
                got = sha256(tmp)
                if got == RELEASED_SHA256:
                    tmp.replace(target)
                    return target, f"downloaded (attempt {attempt}, {r.url})"
                last = f"digest {got} from {r.url}"
            elif r.status_code == 429 or r.status_code >= 500:
                last = f"HTTP {r.status_code} from {r.url}"
                wait = r.headers.get("Retry-After")
                if wait and wait.isdigit():
                    time.sleep(min(int(wait), 120))
            else:
                raise RuntimeError(f"HTTP {r.status_code} from {r.url}")
        except requests.RequestException as e:
            last = repr(e)[:300]
        if last and last.startswith("digest"):
            break  # a wrong file does not get better by asking again
        time.sleep(min(2 ** attempt, 60))
    raise RuntimeError(f"could not obtain batch-control {RELEASED} with sha256 {RELEASED_SHA256}: {last}")


def current_hpi():
    p = pathlib.Path(os.environ.get("BC_H3_CURRENT_HPI") or ROOT / "target" / "batch-control.hpi").resolve()
    if not p.is_file():
        raise RuntimeError(f"missing {p}: build it first (mvn -ntp clean package -DskipTests)")
    return p


def hpi_version(path):
    with zipfile.ZipFile(path) as z:
        text = z.read("META-INF/MANIFEST.MF").decode("utf-8")
    text = re.sub(r"\r?\n ", "", text)  # manifest continuation lines
    m = re.search(r"^Plugin-Version: (.+?)\r?$", text, re.M)
    return m.group(1).strip() if m else None


# ---------------------------------------------------------------- the stack
def compose(*args, hpi, timeout=900):
    env = dict(os.environ, BC_H3_HPI=str(hpi), BC_H3_PORT=str(PORT), BC_H3_PROJECT=PROJECT,
               BC_ADMIN_PASSWORD=PW["admin"], BC_APPROVER_PASSWORD=PW["approver-1"],
               BC_REQUESTER_PASSWORD=PW["requester"])
    return subprocess.run(COMPOSE + list(args), capture_output=True, text=True, env=env, timeout=timeout)


def container_log():
    p = subprocess.run(["docker", "logs", "--timestamps", CONTAINER], capture_output=True, text=True, timeout=120)
    return p.stdout + p.stderr


def plugin_info():
    try:
        r = requests.get(BASE + "/pluginManager/api/json?tree=plugins%5BshortName,version,active,enabled%5D",
                         auth=("admin", PW["admin"]), timeout=20)
        if r.status_code != 200:
            return None
        return next((p for p in r.json()["plugins"] if p.get("shortName") == "batch-control"), {})
    except (requests.RequestException, ValueError):
        return None


def wait_ready(version, timeout=480):
    """Waits until the login page answers and batch-control `version` is active; returns the seconds or None."""
    t0 = time.time()

    def ready():
        try:
            if requests.get(BASE + "/login", timeout=10).status_code != 200:
                return False
        except requests.RequestException:
            return False
        p = plugin_info()
        return bool(p and p.get("active") and p.get("version") == version)
    return round(time.time() - t0) if poll(ready, timeout, 3) else None


# ---------------------------------------------------------------- HTTP as a user
class User:
    """One account's HTTP session (basic auth with the password, the crumb bound to the session's cookie)."""

    def __init__(self, name):
        self.name = name
        self.s = requests.Session()
        self.s.auth = (name, PW[name])
        self.crumb = None

    def get(self, path, **kw):
        return self.s.get(BASE + path, allow_redirects=False, timeout=60, **kw)

    def post(self, path, data=None, **kw):
        if self.crumb is None:
            c = self.s.get(BASE + "/crumbIssuer/api/json", timeout=30)
            self.crumb = (c.json()["crumbRequestField"], c.json()["crumb"]) if c.status_code == 200 else ()
        headers = kw.pop("headers", {})
        if self.crumb:
            headers[self.crumb[0]] = self.crumb[1]
        return self.s.post(BASE + path, data=data, headers=headers, allow_redirects=False, timeout=120, **kw)


USERS = {}


def u(name):
    if name not in USERS:
        USERS[name] = User(name)
    return USERS[name]


def new_sessions():
    USERS.clear()


def groovy(script):
    """The script console as the administrator (arrangement and reading state only); the text after 'Result:'."""
    r = u("admin").post("/scriptText", data={"script": script})
    if r.status_code != 200:
        raise RuntimeError(f"script console HTTP {r.status_code}: {r.text[:300]}")
    out = r.text.strip()
    return out[len("Result:"):].strip() if out.startswith("Result:") else out


def gjson(script):
    """A script console script whose last line returns groovy.json.JsonOutput.toJson(...); the parsed value."""
    out = groovy("import groovy.json.JsonOutput\n" + script)
    try:
        return json.loads(out)
    except ValueError:
        raise RuntimeError(f"script console did not return JSON: {out[:600]}")


def text_of(html):
    t = re.sub(r"<script\b.*?</script>|<style\b.*?</style>", " ", html or "", flags=re.S)
    t = re.sub(r"<[^>]+>", " ", t)
    for a, b in (("&nbsp;", " "), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", '"'), ("&#39;", "'"), ("&amp;", "&")):
        t = t.replace(a, b)
    return re.sub(r"\s+", " ", t).strip()


def loc_id(r):
    m = re.search(UUID, r.headers.get("Location", "") or "")
    return m.group(0) if m else None


STATUS_HOOK = re.compile(r'data-batch-control-status="([A-Z_]+)"')


def page_status(path):
    """(HTTP status, the status enum the page shows through its data-batch-control-status element) as admin."""
    r = u("admin").get(path)
    m = STATUS_HOOK.search(r.text or "")
    return r.status_code, (m.group(1) if m else None)


def activation_notice(job):
    """The job page's activation notice as the requester reads it ('activated', 'not activated', ...)."""
    r = u("requester").get(f"/job/{job}/")
    m = re.search(r"Batch Control: this (?:job|folder) is ([a-z ]+?)\.", text_of(r.text))
    return m.group(1) if m else None


def builds(job):
    r = u("admin").get(f"/job/{job}/api/json?tree=builds%5Bnumber,result,building,queueId,timestamp,"
                       "actions%5Bcauses%5B_class,shortDescription%5D,parameters%5Bname,value%5D%5D%5D")
    return r.json().get("builds", []) if r.status_code == 200 else []


def causes(b):
    return [c.get("_class", "") for a in b.get("actions", []) if a for c in (a.get("causes") or [])]


def params(b):
    return {p["name"]: p.get("value") for a in b.get("actions", []) if a for p in (a.get("parameters") or [])}


def queue_items(job):
    r = u("admin").get("/queue/api/json?tree=items%5Bid,task%5Bname%5D,inQueueSince%5D")
    return [i for i in r.json().get("items", []) if (i.get("task") or {}).get("name") == job] if r.status_code == 200 else []


# ---------------------------------------------------------------- jobs
PROP = (f"<{BC}.config.BatchControlJobProperty><approvalRequired>{{approval}}</approvalRequired>"
        f"<blockTimer>false</blockTimer><blockUpstream>false</blockUpstream></{BC}.config.BatchControlJobProperty>")


def job_xml(description, shell, approval=None, cron="", params_xml=""):
    from xml.sax.saxutils import escape
    props = PROP.format(approval="true" if approval else "false") if approval is not None else ""
    if params_xml:
        props += ("<hudson.model.ParametersDefinitionProperty><parameterDefinitions>" + params_xml
                  + "</parameterDefinitions></hudson.model.ParametersDefinitionProperty>")
    triggers = f"<hudson.triggers.TimerTrigger><spec>{cron}</spec></hudson.triggers.TimerTrigger>" if cron else ""
    return ("<?xml version='1.1' encoding='UTF-8'?><project><description>" + escape(description) + "</description>"
            f"<keepDependencies>false</keepDependencies><properties>{props}</properties>"
            "<scm class='hudson.scm.NullSCM'/><canRoam>true</canRoam><disabled>false</disabled>"
            f"<triggers>{triggers}</triggers><concurrentBuild>false</concurrentBuild>"
            f"<builders><hudson.tasks.Shell><command>{escape(shell)}</command></hudson.tasks.Shell></builders>"
            "<publishers/><buildWrappers/></project>")


DAILY_PARAMS = ("<hudson.model.StringParameterDefinition><name>DATE</name><defaultValue>2026-01-01</defaultValue>"
                "<trim>false</trim></hudson.model.StringParameterDefinition>"
                "<hudson.model.ChoiceParameterDefinition><name>MODE</name><choices class='java.util.Arrays$ArrayList'>"
                "<a class='string-array'><string>full</string><string>delta</string></a></choices>"
                "</hudson.model.ChoiceParameterDefinition>")
JOBS = {
    "h3-daily": dict(description="H3 parameterised batch", approval=True, params_xml=DAILY_PARAMS,
                     shell='echo "H3 DATE=$DATE MODE=$MODE"'),
    "h3-cron": dict(description="H3 activated timer job", approval=True, cron="* * * * *", shell="echo H3 timer"),
    "h3-held": dict(description="H3 timer job, not activated", approval=True, cron="* * * * *", shell="echo H3 held"),
    "h3-target": dict(description="H3 window target", approval=True, shell="echo H3 target"),
    "h3-other": dict(description="H3 job outside the window", approval=True, shell="echo H3 other"),
    "h3-fail": dict(description="H3 failing job", approval=False, shell="echo H3 failing; exit 1"),
}


def create_job(name, spec):
    """createItem, then the same config.xml again (the D-34 creation-time lock replaced by the job's own property), both
    as the administrator over REST. Returns (create status, update status)."""
    xml = job_xml(**spec).encode("utf-8")
    h = {"Content-Type": "application/xml"}
    c = u("admin").post(f"/createItem?name={name}", data=xml, headers=h).status_code
    p = u("admin").post(f"/job/{name}/config.xml", data=xml, headers=h).status_code
    return c, p


# ---------------------------------------------------------------- requests over the plugin's REST endpoints
def run_request(user, job, reason, values, approvers=("approver-1",)):
    body = {"reason": reason, "approvers": list(approvers),
            "parameter": [{"name": k, "value": v} for k, v in values.items()]}
    r = u(user).post(f"/job/{job}/batch-control/submit", data={"json": json.dumps(body)})
    return r.status_code, loc_id(r)


def decide(user, kind, rid, verb, comment):
    return u(user).post(f"/batch-control/{kind}/{rid}/{verb}", data={"comment": comment}).status_code


def grant_request(user, scope, actions, minutes, reason, approvers=("approver-1",)):
    data = [("scopeFullName", scope), ("durationMinutes", str(minutes)), ("reason", reason)]
    data += [("actions", a) for a in actions] + [("approvers", a) for a in approvers]
    r = u(user).post("/batch-control/grants/create", data=data)
    return r.status_code, loc_id(r)


def activation_request(user, job, action, reason, approvers=("approver-1",)):
    data = [("action", action), ("reason", reason)] + [("approvers", a) for a in approvers]
    r = u(user).post(f"/job/{job}/batch-control-activation/submit", data=data)
    return r.status_code, loc_id(r)


def request_status(rid):
    return page_status(f"/batch-control/requests/{rid}/")[1]


# ---------------------------------------------------------------- the snapshot
DUMP = r"""
def cl = jenkins.model.Jenkins.get().pluginManager.uberClassLoader
def store = cl.loadClass('BC.store.Store').getMethod('get').invoke(null)
def render
render = { v ->
  if (v == null) return null
  if (v instanceof CharSequence || v instanceof Number || v instanceof Boolean || v instanceof Enum) return v.toString()
  if (v instanceof java.time.temporal.TemporalAccessor) return v.toString()
  if (v instanceof Map) { def m = new TreeMap(); v.each { k, x -> m[k.toString()] = render(x) }; return m }
  if (v instanceof Collection || v.getClass().isArray()) return v.collect { render(it) }
  if (v.getClass().name.startsWith('BC.')) {
    def m = new TreeMap()
    v.getClass().methods.findAll { it.parameterCount == 0 && !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
        it.declaringClass.name.startsWith('BC.') && (it.name ==~ /(get|is)[A-Z].*/) }.each { me ->
      try { m[me.name] = render(me.invoke(v)) } catch (Throwable t) { m[me.name] = 'THREW ' + t.class.simpleName }
    }
    return m
  }
  return v.toString()
}
def byId = { list -> def m = new TreeMap(); list.each { m[it.id] = render(it) }; m }
def out = [:]
out.runRequests = byId(store.listRunRequests())
out.runRequestValues = [:]
store.listRunRequests().each { q ->
  try { def vs = store.loadRunRequestValues(q.id); if (vs != null) out.runRequestValues[q.id] = vs.collect { it.name + '=' + it.value } }
  catch (Throwable t) { out.runRequestValues[q.id] = 'THREW ' + t.class.simpleName }
}
out.grantRequests = byId(store.listGrantRequests())
out.grants = byId(store.listGrants())
out.activationRequests = byId(store.listActivationRequests())
out.activationStates = new TreeMap(); store.listActivationStates().each { out.activationStates[it.jobFullName] = render(it) }
out.activationSchemaMarked = store.isActivationSchemaMarked()
out.incidents = new TreeMap(); store.listStoredMonths().each { m -> store.listIncidents(m).each { out.incidents[it.id] = render(it) } }
out.months = store.listStoredMonths().collect { it.toString() }
def c = cl.loadClass('BC.config.BatchControlGlobalConfiguration').getMethod('get').invoke(null)
out.config = [:]
['runControlEnabled', 'changeControlEnabled', 'approvers', 'allowAdminSelfApproval', 'pendingTimeoutHours',
 'approvedRunTimeoutMinutes', 'grantDurationOptions', 'maxGrantMinutes', 'incidentResults', 'retentionMonths',
 'emailNotifications', 'notifyBeforeExpiryMinutes'].each { k -> out.config[k] = render(c."$k") }
def a = jenkins.model.Jenkins.get().authorizationStrategy
out.strategy = [cls: a.getClass().name, entries: []]
if (a.respondsTo('getGrantedPermissionEntries')) {
  a.grantedPermissionEntries.each { p, es -> es.each { e -> out.strategy.entries << (e.type.toString() + ':' + e.sid + ':' + p.id) } }
  out.strategy.entries = out.strategy.entries.sort()
}
out.monitors = jenkins.model.Jenkins.get().administrativeMonitors.findAll { it.getClass().name.startsWith('BC.') && it.isEnabled() && it.isActivated() }.collect { it.getClass().name }.sort()
def root = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control')
def files = []
root.eachFileRecurse(groovy.io.FileType.FILES) { files << root.toPath().relativize(it.toPath()).toString() }
out.files = files.sort()
out.executors = jenkins.model.Jenkins.get().numExecutors
return JsonOutput.toJson(out)
""".replace("BC.", BC + ".")


def history_csv(kind):
    """A History CSV export as approver-1 (ViewHistory) over the last 62 days: the rows as dicts (BOM stripped)."""
    frm = (datetime.now() - timedelta(days=62)).strftime("%Y-%m-%d")
    to = (datetime.now() + timedelta(days=1)).strftime("%Y-%m-%d")
    r = u("approver-1").get(f"/batch-control/history/{kind}.csv?from={frm}&to={to}")
    if r.status_code != 200:
        return r.status_code, []
    return 200, list(csv.DictReader(io.StringIO(r.content.decode("utf-8-sig"))))


def snapshot(ids):
    snap = {"model": gjson(DUMP)}
    pages = {}
    for kind, keys in (("requests", ("EXECUTED", "REJECTED", "CANCELLED", "PENDING", "APPROVED")),
                       ("grants", ("GRANT_REQUEST", "GRANT_PENDING")),
                       ("activations", ("ACT_APPROVED", "ACT_PENDING")), ("incidents", ("INCIDENT",))):
        for k in keys:
            pages[k] = page_status(f"/batch-control/{kind}/{ids[k]}/")
    snap["pages"] = pages
    snap["notices"] = {j: activation_notice(j) for j in ("h3-cron", "h3-held")}
    snap["csv"] = {k: history_csv(k) for k in ("requests", "changes", "runs", "incidents")}
    snap["queue"] = {j: [i["id"] for i in queue_items(j)] for j in ("h3-daily", "h3-cron", "h3-held")}
    snap["builds"] = {j: [{"number": b["number"], "queueId": b.get("queueId"), "causes": causes(b), "params": params(b)}
                          for b in builds(j)] for j in JOBS}
    return snap


def instant(text):
    """Seconds since the epoch of an Instant's ISO form (2026-10-31T11:53:08.911Z)."""
    m = re.fullmatch(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(\.\d+)?Z", text or "")
    if not m:
        return float("nan")
    whole = datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc).timestamp()
    return whole + float(m.group(2) or 0)


def diff(a, b, path=""):
    """Paths where b differs from a (keys of a missing in b, values not equal); keys only in b are not differences."""
    out = []
    if isinstance(a, dict) and isinstance(b, dict):
        for k in a:
            if k not in b:
                out.append(f"{path}/{k}: missing")
            else:
                out += diff(a[k], b[k], f"{path}/{k}")
    elif a != b:
        out.append(f"{path}: {json.dumps(a, default=str)[:200]} -> {json.dumps(b, default=str)[:200]}")
    return out


# ---------------------------------------------------------------- phase A
def phase_a(released):
    ids = {}
    t0 = time.time()
    r = compose("up", "-d", "--force-recreate", hpi=released)
    if r.returncode != 0:
        raise RuntimeError("compose up (released) failed: " + (r.stderr or r.stdout)[-600:])
    secs = wait_ready(RELEASED)
    if not check("A-released-up", secs is not None, version=(plugin_info() or {}).get("version"), seconds=secs):
        raise RuntimeError("the controller with the released plugin did not become ready")

    # The Batch Control matrix strategy (script console: what the strategy page would save).
    out = groovy(f"""import org.jenkinsci.plugins.matrixauth.PermissionEntry
def j = jenkins.model.Jenkins.get(); def cl = j.pluginManager.uberClassLoader
def P = cl.loadClass('{BC}.security.BatchControlPermissions')
def s = cl.loadClass('{BC}.security.BatchControlMatrixAuthorizationStrategy').getConstructor().newInstance()
def give = {{ String who, perms -> perms.each {{ s.add(it, PermissionEntry.user(who)) }} }}
def bc = {{ String f -> P.getField(f).get(null) }}
give('admin', [jenkins.model.Jenkins.ADMINISTER])
give('requester', [jenkins.model.Jenkins.READ, hudson.model.Item.READ, hudson.model.View.READ, hudson.model.Item.BUILD, bc('REQUEST'), bc('REQUEST_GRANT')])
['approver-1', 'approver-2'].each {{ give(it, [jenkins.model.Jenkins.READ, hudson.model.Item.READ, hudson.model.View.READ, bc('APPROVE'), bc('VIEW_HISTORY')]) }}
j.setAuthorizationStrategy(s); j.save()
return j.authorizationStrategy.getClass().name""")
    check("A-strategy", out == f"{BC}.security.BatchControlMatrixAuthorizationStrategy", strategy=out)
    new_sessions()

    # The Batch Control configuration, saved on its page by the administrator in a browser.
    status, shown = save_config()
    cfg = gjson(f"""def c = jenkins.model.Jenkins.get().pluginManager.uberClassLoader.loadClass('{BC}.config.BatchControlGlobalConfiguration').getMethod('get').invoke(null)
return JsonOutput.toJson([run: c.runControlEnabled, change: c.changeControlEnabled, approvers: c.approvers, self: c.allowAdminSelfApproval,
  pending: c.pendingTimeoutHours, approved: c.approvedRunTimeoutMinutes, max: c.maxGrantMinutes, retention: c.retentionMonths])""")
    check("A-config-saved", status == 200 and cfg == {"run": True, "change": True, "approvers": ["approver-1", "approver-2", "admin"],
                                                      "self": False, "pending": 70, "approved": 50, "max": 200,
                                                      "retention": 20}, status=status, config=cfg, form=shown)

    # Jobs (REST, administrator), and one CREATE record in last month's bucket (plugin clock set for that one call).
    made = {name: create_job(name, spec) for name, spec in JOBS.items()}
    clock = f"def bc = jenkins.model.Jenkins.get().pluginManager.uberClassLoader.loadClass('{BC}.store.BatchClock')\n"
    last_month = (datetime.now().replace(day=1) - timedelta(days=10)).replace(hour=12, minute=0, second=0, microsecond=0)
    ids["OLD_AT"] = last_month.strftime("%Y-%m-%d")
    groovy(clock + f"bc.setForTest(java.time.Clock.fixed(java.time.Instant.ofEpochMilli({int(last_month.timestamp() * 1000)}L), "
                   "java.time.ZoneId.systemDefault())); return 0")
    try:
        made["h3-old"] = (u("admin").post("/createItem?name=h3-old", data=job_xml("H3 job created last month", "echo old").encode(),
                                          headers={"Content-Type": "application/xml"}).status_code, None)
    finally:
        groovy(clock + "bc.reset(); return 0")
    check("A-jobs", all(c == 200 and p in (200, None) for c, p in made.values()), statuses=made)

    # Activation: h3-cron through an approved ACTIVATE request; h3-held only requested.
    st, act = activation_request("requester", "h3-cron", "ACTIVATE", "H3 put the timer job into service")
    ds = decide("approver-1", "activations", act, "approve", "H3 activation approved") if act else None
    st2, act_p = activation_request("requester", "h3-held", "ACTIVATE", "H3 activation left pending")
    ids["ACT_APPROVED"], ids["ACT_PENDING"] = act, act_p
    notices = {j: activation_notice(j) for j in ("h3-cron", "h3-held")}
    check("A-activation", act and act_p and ds in (200, 302) and notices == {"h3-cron": "activated", "h3-held": "not activated"},
          submit=[st, st2], decide=ds, notices=notices)
    timer = poll(lambda: [b for b in builds("h3-cron") if any("TimerTriggerCause" in c for c in causes(b)) and not b.get("building")], 150, 3)
    check("A-timer-runs", bool(timer) and not builds("h3-held"), timer_builds=[b["number"] for b in timer or []],
          held_builds=len(builds("h3-held")))

    # Incident: a failing build (h3-fail is not approval-required), acknowledged by approver-1.
    u("admin").post("/job/h3-fail/build")
    inc = poll(lambda: [x for x in gjson(DUMP)["incidents"].values() if x.get("getJobFullName") == "h3-fail"], 120, 3)
    ids["INCIDENT"] = inc[0]["getId"] if inc else None
    ack = decide("approver-1", "incidents", ids["INCIDENT"], "acknowledge", "H3 looking into it") if inc else None
    check("A-incident", bool(inc) and ack in (200, 302) and page_status(f"/batch-control/incidents/{ids['INCIDENT']}/")[1] == "ACKNOWLEDGED",
          incident=ids["INCIDENT"], acknowledge=ack)

    # Run requests: executed, rejected, cancelled, pending, approved-and-queued.
    st, ids["EXECUTED"] = run_request("requester", "h3-daily", "H3 executed before the upgrade", {"DATE": "2026-10-01", "MODE": "full"})
    d = decide("approver-1", "requests", ids["EXECUTED"], "approve", "H3 go")
    ran = poll(lambda: request_status(ids["EXECUTED"]) == "EXECUTED" and all(not b.get("building") for b in builds("h3-daily")), 180, 3)
    st, ids["REJECTED"] = run_request("requester", "h3-daily", "H3 rejected before the upgrade", {"DATE": "2026-10-02", "MODE": "delta"},
                                      approvers=("approver-2",))
    d2 = decide("approver-2", "requests", ids["REJECTED"], "reject", "H3 wrong date") if ids["REJECTED"] else None
    st, ids["CANCELLED"] = run_request("requester", "h3-daily", "H3 cancelled before the upgrade", {"DATE": "2026-10-03", "MODE": "full"})
    d3 = u("requester").post(f"/batch-control/requests/{ids['CANCELLED']}/cancel").status_code if ids["CANCELLED"] else None
    st, ids["PENDING"] = run_request("requester", "h3-daily", "H3 pending across the upgrade", {"DATE": "2026-10-04", "MODE": "delta"},
                                     approvers=("approver-1", "approver-2"))
    groovy("jenkins.model.Jenkins.get().setNumExecutors(0); return 0")
    poll(lambda: all(not b.get("building") for j in JOBS for b in builds(j)), 120, 2)
    st, ids["APPROVED"] = run_request("requester", "h3-daily", "H3 approved, queued across the upgrade", {"DATE": "2026-10-05", "MODE": "full"})
    d4 = decide("approver-1", "requests", ids["APPROVED"], "approve", "H3 queued") if ids["APPROVED"] else None
    queued = poll(lambda: len(queue_items("h3-daily")) == 1, 30, 1)
    statuses = {k: request_status(ids[k]) for k in ("EXECUTED", "REJECTED", "CANCELLED", "PENDING", "APPROVED")}
    check("A-run-requests", bool(ran) and queued and statuses == {"EXECUTED": "EXECUTED", "REJECTED": "REJECTED",
                                                                   "CANCELLED": "CANCELLED", "PENDING": "PENDING",
                                                                   "APPROVED": "APPROVED"} and (d, d2, d3, d4) and
          all(x in (200, 302) for x in (d, d2, d3, d4)), statuses=statuses, decisions=[d, d2, d3, d4], queued=queued)

    # Permission window: CONFIGURE on h3-target, approved; a DELETE window on h3-other left pending.
    st, ids["GRANT_REQUEST"] = grant_request("requester", "h3-target", ["CONFIGURE"], 60, "H3 window across the upgrade")
    d5 = decide("approver-1", "grants", ids["GRANT_REQUEST"], "approve", "H3 window approved") if ids["GRANT_REQUEST"] else None
    st2, ids["GRANT_PENDING"] = grant_request("requester", "h3-other", ["DELETE"], 30, "H3 window request left pending")
    model = gjson(DUMP)
    grant = [g for g in model["grants"].values() if g.get("getGrantRequestId") == ids["GRANT_REQUEST"]]
    ids["GRANT"] = grant[0]["getId"] if grant else None
    reach = {j: u("requester").get(f"/job/{j}/config.xml").status_code for j in ("h3-target", "h3-other")}
    check("A-window", bool(grant) and d5 in (200, 302) and ids["GRANT_PENDING"] and reach == {"h3-target": 200, "h3-other": 403},
          grant=ids["GRANT"], decide=d5, pending=ids["GRANT_PENDING"], config_xml=reach)
    info("phase-a-done", seconds=round(time.time() - t0), ids=ids)
    return ids


def save_config():
    """The administrator saves the Batch Control configuration page in a browser (switches on, approvers, non-default
    numbers, admin self-approval off). Returns (HTTP status of the save, the values typed)."""
    from playwright.sync_api import sync_playwright
    values = {"approversText": "approver-1\napprover-2\nadmin", "pendingTimeoutHours": "70",
              "approvedRunTimeoutMinutes": "50", "maxGrantMinutes": "200", "retentionMonths": "20"}
    with sync_playwright() as p:
        channel = os.environ.get("BC_BROWSER_CHANNEL", "chrome")
        b = p.chromium.launch(**({} if channel == "chromium" else {"channel": channel}))
        try:
            page = b.new_context(viewport={"width": 1280, "height": 900}, locale="en-US").new_page()
            page.set_default_timeout(30000)
            page.goto(BASE + "/login")
            page.fill("#j_username", "admin")
            page.fill('input[name="j_password"]', PW["admin"])
            page.click('button[name="Submit"], button[type="submit"]')
            page.wait_for_load_state("load")
            page.goto(BASE + "/manage/batch-control-configuration/")
            for name, want in (("runControlEnabled", True), ("changeControlEnabled", True), ("allowAdminSelfApproval", False)):
                box = page.locator(f"input[name='_.{name}']")
                if box.is_checked() != want:
                    page.locator(f"input[name='_.{name}'] + label").click()
            for name, value in values.items():
                page.locator(f"[name='_.{name}']").first.fill(value)
            page.screenshot(path=str(OUT / "A-config.png"), full_page=True)
            with page.expect_navigation() as nav:
                page.click("button[name=Submit]")
            page.wait_for_load_state("load")
            return (nav.value.status if nav.value else None), values
        finally:
            b.close()


# ---------------------------------------------------------------- phase B
def phase_b(current, ids):
    (OUT / "jenkins-a.log").write_text(container_log())
    t0 = time.time()
    r = compose("stop", "jenkins", hpi=current)
    tamper = os.environ.get("BC_H3_TAMPER")
    if tamper:
        env = []
        for k, v in ids.items():
            env += ["-e", f"{k}={v}"]
        p = subprocess.run(["docker", "run", "--rm", "-u", "1000", "-v", f"{VOLUME}:/var/jenkins_home", "-w", "/var/jenkins_home",
                            *env, "--entrypoint", "sh", IMAGE, "-c", tamper], capture_output=True, text=True, timeout=120)
        info("tamper", snippet=tamper, rc=p.returncode, output=(p.stdout + p.stderr)[-400:])
    r2 = compose("up", "-d", "--force-recreate", "jenkins", hpi=current)
    version = hpi_version(current)
    secs = wait_ready(version)
    check("B-upgraded-up", r.returncode == 0 and r2.returncode == 0 and secs is not None, version=version,
          stop_rc=r.returncode, up_rc=r2.returncode, up_err=(r2.stderr or "")[-300:] if r2.returncode else "",
          seconds_to_ready=secs, seconds=round(time.time() - t0))
    new_sessions()
    return secs is not None


# ---------------------------------------------------------------- phase C
LOAD_ERRORS = re.compile(r"ConversionException|CannotResolveClassException|ClassNotFoundException|NoClassDefFoundError|"
                         r"XStreamException|UnmarshallingException|AbstractReflectionConverter|Failed Loading plugin|"
                         r"Failed to load|NoSuchMethodError|LinkageError")
# Jenkins' log abbreviates logger names: io.jenkins.plugins.batchcontrol.store.FileStore is "i.j.p.b.store.FileStore".
BC_WARN = re.compile(r"\t(WARNING|SEVERE)\t(?:i\.j\.p\.b\.|io\.jenkins\.plugins\.batchcontrol\.)")


def log_problems(text):
    lines = text.splitlines()
    load = [l[:300] for l in lines if LOAD_ERRORS.search(l)]
    warn = [l[:300] for l in lines if BC_WARN.search(l)]
    return load, warn


def phase_c(before, ids, current):
    version = hpi_version(current)
    # ---- loading
    p = plugin_info() or {}
    failed = gjson("return JsonOutput.toJson(jenkins.model.Jenkins.get().pluginManager.failedPlugins.collect { it.name + ': ' + it.cause })")
    check("C-plugin-swapped", p.get("active") is True and p.get("version") == version and version != RELEASED and not failed,
          version=p.get("version"), expected=version, active=p.get("active"), failed_plugins=failed)
    log = container_log()
    (OUT / "jenkins-c-boot.log").write_text(log)
    load, warn = log_problems(log)
    check("C-log-clean", not load and not warn, load_errors=load[:10], batchcontrol_warnings=warn[:10])
    old = gjson("""def m = jenkins.model.Jenkins.get().getExtensionList(hudson.diagnosis.OldDataMonitor).get(0)
return JsonOutput.toJson(m.data.collect { k, v -> k.getClass().name + ' ' + k + ': ' + v.extra })""")
    check("C-old-data-monitor", not [x for x in old if "batchcontrol" in x], batchcontrol_entries=[x for x in old if "batchcontrol" in x],
          all_entries=len(old))
    after = snapshot(ids)
    (OUT / "snapshot-c.json").write_text(json.dumps(after, indent=1, sort_keys=True, default=str))
    bm, am = before["model"], after["model"]
    check("C-monitors", am["monitors"] == bm["monitors"], before=bm["monitors"], after=am["monitors"])
    # ---- identical state
    check("C-strategy", am["strategy"] == bm["strategy"] and am["strategy"]["cls"].endswith("BatchControlMatrixAuthorizationStrategy"),
          cls=am["strategy"]["cls"], differences=diff(bm["strategy"], am["strategy"]))
    check("C-config", am["config"] == bm["config"], config=am["config"], differences=diff(bm["config"], am["config"]))
    # SPEC 7: an APPROVED request's expiry is judged from the recovery time after a restart, so startup recovery moves its
    # expiry base forward (any restart does that, not only an upgrade). That one value is checked on its own: later, never
    # earlier; every other getter must be equal.
    rebased = {}
    for rid, q in bm["runRequests"].items():
        if q.get("getStatus") == "APPROVED" and rid in am["runRequests"]:
            rebased[rid] = (q.pop("getExpiryBase", None), am["runRequests"][rid].pop("getExpiryBase", None))
    check("C-approved-expiry-rebased", bool(rebased) and all(b and a and instant(a) >= instant(b) for b, a in rebased.values()),
          expiry_base={k: {"before": b, "after": a} for k, (b, a) in rebased.items()}, rule="SPEC 7 restart recovery")
    for key in ("runRequests", "runRequestValues", "grantRequests", "grants", "activationRequests", "activationStates", "incidents"):
        d = diff(bm[key], am[key])
        check(f"C-entities-{key}", len(bm[key]) > 0 and not d, count_before=len(bm[key]), count_after=len(am[key]), differences=d[:20])
    check("C-no-reseeding", am["activationSchemaMarked"] is True and
          [r for r in after["csv"]["changes"][1] if r.get("user") == "(upgrade)"] == [r for r in before["csv"]["changes"][1] if r.get("user") == "(upgrade)"]
          and am["activationStates"].get("h3-held", {}).get("isActivated") in (None, "false"),
          marked=am["activationSchemaMarked"], held=am["activationStates"].get("h3-held"))
    check("C-pages", after["pages"] == before["pages"] and all(c == 200 for c, _ in after["pages"].values()),
          before=before["pages"], after=after["pages"])
    check("C-activation-notices", after["notices"] == before["notices"] == {"h3-cron": "activated", "h3-held": "not activated"},
          before=before["notices"], after=after["notices"])
    for kind, mode in (("requests", "equal"), ("incidents", "equal"), ("changes", "keeps"), ("runs", "keeps")):
        (sb, rb), (sa, ra) = before["csv"][kind], after["csv"][kind]
        missing = [r for r in rb if r not in ra]
        extra = [r for r in ra if r not in rb]
        ok = sb == sa == 200 and rb and not missing and (mode == "keeps" or not extra)
        check(f"C-csv-{kind}", ok, rows_before=len(rb), rows_after=len(ra), missing=missing[:5], extra=extra[:5] if mode == "equal" else len(extra))
    lost = [f for f in bm["files"] if f not in am["files"]]
    check("C-store-files", not lost, files_before=len(bm["files"]), files_after=len(am["files"]), lost=lost[:20])
    check("C-queue-restored", after["queue"]["h3-daily"] and len(after["queue"]["h3-daily"]) == 1 and am["executors"] == 0,
          queue=after["queue"], executors=am["executors"])
    # ---- history listings show the old records (approver-1, ViewHistory)
    old_day = ids["OLD_AT"]
    h = u("approver-1").get(f"/batch-control/history/?kind=changes&from={old_day}&to={old_day}")
    ex = [b for b in before["builds"]["h3-daily"] if ids["EXECUTED"] and b["params"].get("DATE") == "2026-10-01"]
    frm = (datetime.now() - timedelta(days=62)).strftime("%Y-%m-%d")
    to = (datetime.now() + timedelta(days=1)).strftime("%Y-%m-%d")
    hr = u("approver-1").get(f"/batch-control/history/?kind=runs&from={frm}&to={to}")
    hq = u("approver-1").get(f"/batch-control/history/?kind=requests&from={frm}&to={to}")
    hi = u("approver-1").get(f"/batch-control/history/?kind=incidents&from={frm}&to={to}")
    listed = {"last-month CREATE h3-old": h.status_code == 200 and "h3-old" in text_of(h.text),
              "executed run h3-daily": hr.status_code == 200 and bool(ex) and f"h3-daily #{ex[0]['number']}" in text_of(hr.text),
              "requests": hq.status_code == 200 and all(ids[k] in hq.text for k in ("EXECUTED", "REJECTED", "CANCELLED")),
              "incident": hi.status_code == 200 and "h3-fail" in text_of(hi.text)}
    last_month = old_day[:7]
    listed["month buckets kept"] = last_month in am["months"] and all(m in am["months"] for m in bm["months"])
    check("C-history-listings", all(listed.values()), listed=listed, old_day=old_day, months_before=bm["months"],
          months_after=am["months"])

    # ---- still working
    restored = set(after["queue"]["h3-cron"] + after["queue"]["h3-daily"] + after["queue"]["h3-held"])
    known = {b["number"] for b in before["builds"]["h3-daily"]}
    groovy("jenkins.model.Jenkins.get().setNumExecutors(2); return 0")
    done = poll(lambda: request_status(ids["APPROVED"]) == "EXECUTED" and
                [b for b in builds("h3-daily") if b["number"] not in known and not b.get("building")], 180, 3)
    new = [b for b in builds("h3-daily") if b["number"] not in known]
    check("C-queued-approved-runs-once", bool(done) and len(new) == 1 and params(new[0]) == {"DATE": "2026-10-05", "MODE": "full"}
          and new[0].get("result") == "SUCCESS", status=request_status(ids["APPROVED"]),
          new_builds=[(b["number"], b.get("result"), params(b)) for b in new])
    known_cron = {b["number"] for b in before["builds"]["h3-cron"]}
    fresh = poll(lambda: [b for b in builds("h3-cron") if b["number"] not in known_cron and b.get("queueId") not in restored
                          and any("TimerTriggerCause" in c for c in causes(b))], 150, 3)
    check("C-timer-fires-after-upgrade", bool(fresh), new_timer_builds=[(b["number"], b.get("queueId")) for b in fresh or []],
          restored_queue_ids=sorted(restored), spec="SPEC 6a: an upgrade never stops a schedule")
    check("C-held-timer-still-refused", not builds("h3-held") and not queue_items("h3-held"), held_builds=len(builds("h3-held")))
    known = {b["number"] for b in builds("h3-daily")}
    d = decide("approver-2", "requests", ids["PENDING"], "approve", "H3 approved after the upgrade")
    done = poll(lambda: request_status(ids["PENDING"]) == "EXECUTED" and
                [b for b in builds("h3-daily") if b["number"] not in known and not b.get("building")], 180, 3)
    new = [b for b in builds("h3-daily") if b["number"] not in known]
    check("C-pending-approved-runs", d in (200, 302) and bool(done) and len(new) == 1
          and params(new[0]) == {"DATE": "2026-10-04", "MODE": "delta"}, decide=d, status=request_status(ids["PENDING"]),
          new_builds=[(b["number"], b.get("result"), params(b)) for b in new])
    # The window: requester (no Item/Configure of its own) changes h3-target's configuration, not h3-other's.
    xml = job_xml(**dict(JOBS["h3-target"], description="H3 window target, changed after the upgrade")).encode()
    hx = {"Content-Type": "application/xml"}
    t_status = u("requester").post("/job/h3-target/config.xml", data=xml, headers=hx).status_code
    o_status = u("requester").post("/job/h3-other/config.xml", data=job_xml(**JOBS["h3-other"]).encode(), headers=hx).status_code
    desc = u("admin").get("/job/h3-target/api/json?tree=description").json().get("description")
    _, rows = history_csv("changes")
    rec = [r for r in rows if r.get("type") == "CONFIGURE" and r.get("target") == "h3-target" and r.get("user") == "requester"]
    check("C-window-confers", t_status == 200 and o_status == 403 and desc == "H3 window target, changed after the upgrade"
          and bool(rec) and rec[0].get("grantId") == ids["GRANT"], target=t_status, other=o_status, description=desc,
          record=rec[:1], grant=ids["GRANT"])
    d = decide("approver-1", "grants", ids["GRANT_PENDING"], "approve", "H3 window approved after the upgrade")
    model = gjson(DUMP)
    g = [x for x in model["grants"].values() if x.get("getGrantRequestId") == ids["GRANT_PENDING"]]
    check("C-pending-window-approved", d in (200, 302) and page_status(f"/batch-control/grants/{ids['GRANT_PENDING']}/")[1] == "APPROVED"
          and bool(g) and g[0].get("getUser") == "requester", decide=d, grant=g[:1])
    d = decide("approver-1", "activations", ids["ACT_PENDING"], "approve", "H3 activation approved after the upgrade")
    check("C-pending-activation-approved", d in (200, 302) and activation_notice("h3-held") == "activated",
          decide=d, notice=activation_notice("h3-held"))
    st, rid = run_request("requester", "h3-daily", "H3 new request after the upgrade", {"DATE": "2026-10-06", "MODE": "full"})
    c = u("requester").post(f"/batch-control/requests/{rid}/cancel").status_code if rid else None
    check("C-new-request", st in (200, 302) and bool(rid) and request_status(rid) == "CANCELLED", submit=st, id=rid, cancel=c)
    d = decide("approver-1", "incidents", ids["INCIDENT"], "resolve", "H3 resolved after the upgrade")
    check("C-incident-resolved", d in (200, 302) and page_status(f"/batch-control/incidents/{ids['INCIDENT']}/")[1] == "RESOLVED",
          decide=d)
    load, warn = log_problems(container_log())
    check("C-log-clean-at-end", not load and not [w for w in warn if "SEVERE" in w], load_errors=load[:10],
          batchcontrol_severe=[w for w in warn if "SEVERE" in w][:10])


def main():
    released, how = released_hpi()
    check("A-released-hpi", sha256(released) == RELEASED_SHA256 and hpi_version(released) == RELEASED,
          path=str(released), obtained=how, sha256=RELEASED_SHA256, version=hpi_version(released))
    current = current_hpi()
    info("current-hpi", path=str(current), version=hpi_version(current), sha256=sha256(current))
    compose("down", "-v", "--remove-orphans", hpi=current)
    ids = phase_a(released)
    before = snapshot(ids)
    (OUT / "snapshot-a.json").write_text(json.dumps(before, indent=1, sort_keys=True, default=str))
    (OUT / "ids.json").write_text(json.dumps(ids, indent=1))
    if phase_b(current, ids):
        phase_c(before, ids, current)


if __name__ == "__main__":
    t_start = time.time()
    try:
        main()
    except Exception as e:  # noqa: any error is a failed check, never a silent pass
        check("driver", False, error=repr(e)[:800])
    finally:
        try:
            (OUT / "jenkins-final.log").write_text(container_log())
        except Exception:  # noqa
            pass
        if os.environ.get("BC_KEEP") != "1":
            try:
                compose("down", "-v", "--remove-orphans", hpi=os.devnull, timeout=600)
            except Exception as e:  # noqa
                info("down-failed", error=repr(e)[:300])
    print(f"SUMMARY h3 pass={N['pass']} fail={N['fail']} seconds={round(time.time() - t_start)}", flush=True)
    sys.exit(1 if N["fail"] or not N["pass"] else 0)
