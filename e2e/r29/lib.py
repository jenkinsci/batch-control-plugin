"""e2e-29 (core-flow gap audit, 2026-11-26): the time-limited permission windows and the run approval flow, on top of
../r24/lib.py: screenshots in screenshots/run-29/ (never committed), rows in r29/out/<driver>.jsonl.

The rules of every e2e pass hold: one new browser context per page and account (real login form), the server state read
separately (REST with basic auth; the script console and `docker exec` only arrange state or read it, never decide a
verdict by changing the plugin's files; windows.py F's fault changes only the modes of the store's files). Each driver
arranges its own items (named r29-*) and accounts (w29, r29-dual) idempotently in its first section and restores what it
changes globally (configuration values, the approver list, node labels, the switches) in its cleanup, also after a
failure. Verdict lines as in r16..r28: "PASS {...}" / "FAIL {...}" per assertion
(ci/shard.py fails a step on a FAIL line or a non-zero exit), "NOTE {...}" for an observation without a verdict. Every
check names the inventory row it covers ("[T2] ...", the rows of the e2e-29 coverage inventory) and pairs a positive
assertion with the guard that would fail if the feature were removed."""
import importlib.util
import json
import os
import pathlib
import re
import subprocess
import time

import requests

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("r24lib", HERE.parent / "r24" / "lib.py")
L24 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(L24)
L19 = L24.L19
for _m in (L19._r6, L19._l):  # r24/lib.py points r6/lib.py and r16/lib.py at run-24 and r24/out; this pass writes its own
    _m.SHOTS = HERE.parent / "screenshots" / "run-29"
    _m.OUT = HERE / "out"
L19._r6.SHOTS.mkdir(parents=True, exist_ok=True)
L19._r6.OUT.mkdir(parents=True, exist_ok=True)
SHOTS, OUT = L19._r6.SHOTS, L19._r6.OUT

Session, close, api, groovy, gv, ENV, BASE, pw = L24.Session, L24.close, L24.api, L24.groovy, L24.gv, L24.ENV, L24.BASE, L24.pw
check, note, text_of, J, decide, loc_id, tick = L24.check, L24.note, L24.text_of, L24.J, L24.decide, L24.loc_id, L24.tick
builds, next_build, run_req, act_req, queue_items, wait_until = (L24.builds, L24.next_build, L24.run_req, L24.act_req,
                                                                 L24.queue_items, L24.wait_until)
wait_build, activation, changes, facts, has_permission, exists = (L24.wait_build, L24.activation, L24.changes, L24.facts,
                                                                  L24.has_permission, L24.exists)
delete, create, stored_request, runs_of_request, build_params = (L24.delete, L24.create, L24.stored_request,
                                                                 L24.runs_of_request, L24.build_params)
browser_decide, refusal_text, run, CONTAINER, UUID, LOGNAME = (L24.browser_decide, L24.refusal_text, L24.run,
                                                               L24.CONTAINER, L24.UUID, L24.LOGNAME)
string_p, job_xml = L24.string_p, L24.job_xml
wait_status, approve_rest, browser_delete, all_queue = L24.wait_status, L24.approve_rest, L24.browser_delete, L24.all_queue
BC = L24.BC
CFG = L24.CFG
HOLDER = "w29"            # window holder: Overall/Read, Item/Read, View/Read, BatchControl/RequestGrant only
DUAL = "r29-dual"         # a listed approver who may also request (Request, RequestGrant, Approve), not an administrator
MAIL = f"http://localhost:{os.environ.get('BC_MAIL_PORT', '8025')}"
FOLDER_XML = L24.FOLDER_XML
H_XML = {"Content-Type": "application/xml"}
JH = "/var/jenkins_home"


def dexec(*cmd, user=None, timeout=120):
    """`docker exec` in the Jenkins container, as r23/lib.py (arrangement and evidence only: a fault on the store's
    file modes and the probes that prove it); `user` None runs as the image's user jenkins, the controller's own.
    Returns (rc, stdout+stderr)."""
    args = ["docker", "exec"] + (["-u", user] if user else []) + [CONTAINER] + list(cmd)
    p = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    return p.returncode, (p.stdout + p.stderr)


# ---------------------------------------------------------------- arrangement
def ensure_account(user, perms):
    """Arrangement (script console): a local account `user` (password BC_OTHER_PASSWORD, mail <user>@e2e.local) with
    exactly Overall/Read, Item/Read, View/Read plus `perms` (BatchControl permission names) in the global matrix.
    JCasC re-applies the strategy at every boot, so after a restart this has to run again."""
    return gv("""import hudson.model.*
import hudson.security.*
import org.jenkinsci.plugins.matrixauth.*
def j = jenkins.model.Jenkins.get(); def realm = j.getSecurityRealm(); def u = """ + json.dumps(user) + """
if (User.getById(u, false) == null) realm.createAccount(u, """ + json.dumps(ENV["BC_OTHER_PASSWORD"]) + """)
User.getById(u, true).addProperty(new hudson.tasks.Mailer.UserProperty(u + '@e2e.local'))
def s = j.getAuthorizationStrategy()
def ids = ['hudson.model.Hudson.Read', 'hudson.model.Item.Read', 'hudson.model.View.Read'] + """
              + json.dumps([f"{BC}.security.BatchControlPermissions.{p}" for p in perms]) + """
ids.each { pid -> s.add(Permission.fromId(pid), new PermissionEntry(AuthorizationType.USER, u)) }
j.save()
return ids.collect { pid -> pid.tokenize('.').last() }.join(',')""")


def ensure_folder(full):
    if not exists(full):
        create(full, FOLDER_XML)
    return exists(full)


def fs_xml(description="e2e-29", shell="echo e2e-29", approval=None, timer=None, upstream=None, cron="", label="",
           params=""):
    """config.xml of a Freestyle job. `approval`/`timer`/`upstream`: the Batch Control job property with those values
    (None for all three: no property at all). `label`: assigned node label (canRoam false)."""
    from xml.sax.saxutils import escape
    props = ""
    if approval is not None or timer is not None or upstream is not None:
        props = (f"<{BC}.config.BatchControlJobProperty><approvalRequired>{str(bool(approval)).lower()}</approvalRequired>"
                 f"<blockTimer>{str(bool(timer)).lower()}</blockTimer><blockUpstream>{str(bool(upstream)).lower()}"
                 f"</blockUpstream></{BC}.config.BatchControlJobProperty>")
    if params:
        props += ("<hudson.model.ParametersDefinitionProperty><parameterDefinitions>" + params
                  + "</parameterDefinitions></hudson.model.ParametersDefinitionProperty>")
    node = f"<assignedNode>{escape(label)}</assignedNode><canRoam>false</canRoam>" if label else "<canRoam>true</canRoam>"
    triggers = f"<hudson.triggers.TimerTrigger><spec>{cron}</spec></hudson.triggers.TimerTrigger>" if cron else ""
    return ("<?xml version='1.1' encoding='UTF-8'?><project><description>" + escape(description) + "</description>"
            f"<keepDependencies>false</keepDependencies><properties>{props}</properties>"
            f"<scm class='hudson.scm.NullSCM'/>{node}<disabled>false</disabled>"
            f"<triggers>{triggers}</triggers><concurrentBuild>false</concurrentBuild>"
            f"<builders><hudson.tasks.Shell><command>{escape(shell)}</command></hudson.tasks.Shell></builders>"
            "<publishers/><buildWrappers/></project>")


def fresh_job(full, xml, approval=True, timer=False, upstream=False):
    """Arrangement: deletes `full` when it exists, creates it from `xml` (admin, REST) and sets its Batch Control
    property (the creation-time lock of D-34 replaced by the given values). Returns the property as stored."""
    delete(full)
    st = create(full, xml)
    assert st == 200, (full, st)
    return L24.set_property(full, approval=approval, timer=timer, upstream=upstream)


def config(*names):
    """Values of Batch Control global settings (getters) as a dict, read through the script console."""
    return facts(CFG + "return groovy.json.JsonOutput.toJson([" + ", ".join(f"{n}: cfg.{n}" for n in names) + "])")


def set_config(**values):
    """Arrangement (script console, as JCasC or a script sets it): Batch Control global settings by their setters, then
    saved. Values are Groovy literals. Returns the settings read back."""
    sets = "".join(f"cfg.set{k[0].upper()}{k[1:]}({v}); " for k, v in values.items())
    return facts(CFG + sets + "cfg.save()\nreturn groovy.json.JsonOutput.toJson([" +
                 ", ".join(f"{k}: cfg.{k}" for k in values) + "])")


def approvers():
    return facts(CFG + "return groovy.json.JsonOutput.toJson(cfg.approvers as List)")


def set_approvers(ids):
    return facts(CFG + f"cfg.setApprovers({json.dumps(list(ids))}); cfg.save()\n"
                 "return groovy.json.JsonOutput.toJson(cfg.approvers as List)")


# ---------------------------------------------------------------- the store (reading only)
def xml_of(rel):
    """The fields of the XML file batch-control/<rel> as {element name: text} for its direct children, plus
    "_children" (the element names) and "_exists"."""
    return facts("""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/""" + rel + """')
if (!f.exists()) return groovy.json.JsonOutput.toJson([_exists: false])
def x = new XmlSlurper().parse(f); def out = [_exists: true, _children: x.children()*.name()]
x.children().each { c -> out[c.name()] = c.children().size() ? c.children()*.text().join('|') : c.text() }
return groovy.json.JsonOutput.toJson(out)""")


def grant_request(rid):
    return xml_of(f"requests/grant/{rid}.xml")


def grant(rid):
    """The stored permission window of grant request `rid` (batch-control/grants/<rid>.xml) with "open" against the
    controller clock and "now"."""
    g = facts("""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/grants/""" + rid + """.xml')
def now = System.currentTimeMillis()
if (!f.exists()) return groovy.json.JsonOutput.toJson([_exists: false, now: now])
def x = new XmlSlurper().parse(f); def out = [_exists: true, now: now]
x.children().each { c -> out[c.name()] = c.children().size() ? c.children()*.text().join('|') : c.text() }
out.open = x.revokedAtMillis.text() == '' && ((x.expiresAtMillis.text() ?: '0') as long) > now
return groovy.json.JsonOutput.toJson(out)""")
    for k in ("grantedAtMillis", "expiresAtMillis", "revokedAtMillis"):
        if g.get(k):
            g[k] = int(g[k])
    return g


def grant_requests_of(user, scope=None):
    """ids of the stored grant requests of `user` (optionally on `scope`), with their status and duration."""
    rows = facts("""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/requests/grant'); def out = []
if (d.exists()) d.listFiles().findAll { it.name ==~ /[0-9a-f-]{36}\\.xml/ }.each { f -> def x = new XmlSlurper().parse(f)
  if (x.requester.text() == """ + json.dumps(user) + """) out << [id: x.id.text(), scope: x.scope.fullName.text(),
    status: x.status.text(), minutes: x.durationMinutes.text()] }
return groovy.json.JsonOutput.toJson(out)""")
    return [r for r in rows if scope is None or r["scope"] == scope]


def run_requests_of(user, job):
    return facts("""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/requests/run'); def out = []
if (d.exists()) d.listFiles().findAll { it.name ==~ /[0-9a-f-]{36}\\.xml/ }.each { f -> def x = new XmlSlurper().parse(f)
  if (x.requester.text() == """ + json.dumps(user) + """ && x.jobFullName.text() == """ + json.dumps(job) + """)
    out << [id: x.id.text(), status: x.status.text()] }
return groovy.json.JsonOutput.toJson(out)""")


def run_request(rid):
    return xml_of(f"requests/run/{rid}.xml")


def now_ms():
    return int(gv("return System.currentTimeMillis()"))


def clock_offset(samples=5):
    """Milliseconds to add to the local clock (time.time()) to get the controller's clock, the median of `samples`
    script-console round trips (the uncertainty is half a round trip, returned as the second value)."""
    rows = []
    for _ in range(samples):
        t0 = time.time()
        c = now_ms()
        t1 = time.time()
        rows.append((c - (t0 + t1) / 2 * 1000, (t1 - t0) * 1000 / 2))
    rows.sort()
    return rows[len(rows) // 2]


def records_since(mark, pred=None):
    """Change records appended after `mark` (the record count of this month's change log when it was taken)."""
    rows = changes()
    return [r for r in rows[mark:] if pred is None or pred(r)]


def records_mark():
    return len(changes())


# ---------------------------------------------------------------- windows
def grant_req(user, scope, actions, minutes=15, reason="e2e-29", approvers=("approver-1",), extra=()):
    data = [("scopeFullName", scope), ("durationMinutes", str(minutes)), ("reason", reason)]
    data += [("actions", a) for a in actions] + [("approvers", a) for a in approvers] + list(extra)
    r = api(user, "/batch-control/grants/create", "POST", data=data)
    return r.status_code, loc_id(r), r


def open_window(user, scope, actions, minutes, reason, approver="approver-1"):
    """`user` requests a window and `approver` approves it (REST). Returns (grant request id, the stored window)."""
    st, rid, r = grant_req(user, scope, actions, minutes, reason)
    assert rid, (scope, actions, st, text_of(r.text)[:300])
    st = decide(approver, "grants", rid, "approve", "e2e-29")
    assert st in (200, 302), ("approve", rid, st)
    return rid, grant(rid)


def revoke_open(user, prefix):
    """Cleanup: revokes every open window of `user` on an item whose full name starts with `prefix` (manager, REST)."""
    out = facts("""def now = System.currentTimeMillis(); def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/grants')
def ids = []
if (d.exists()) d.listFiles().findAll { it.name.endsWith('.xml') }.each { f -> def g = new XmlSlurper().parse(f)
  if (g.user.text() == """ + json.dumps(user) + """ && g.scope.fullName.text().startsWith(""" + json.dumps(prefix) + """)
      && g.revokedAtMillis.text() == '' && ((g.expiresAtMillis.text() ?: '0') as long) > now) ids << g.id.text() }
return groovy.json.JsonOutput.toJson(ids)""")
    return {g: api("manager", f"/batch-control/grants/{g}/revoke", "POST").status_code for g in out}


def cancel_pending(user, prefix):
    """Cleanup: cancels every PENDING run, grant and activation request of `user` whose job or item starts with
    `prefix` (administrator, REST: Manage may cancel any pending request)."""
    rows = facts("""def root = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control'); def out = []
['run': 'requests/run', 'grant': 'requests/grant', 'activation': 'activation-requests'].each { k, d ->
  def f = new File(root, d); if (f.exists()) f.listFiles().findAll { it.name ==~ /[0-9a-f-]{36}\\.xml/ }.each { x ->
    def r = new XmlSlurper().parse(x); def name = k == 'grant' ? r.scope.fullName.text() : r.jobFullName.text()
    if (r.requester.text() == """ + json.dumps(user) + """ && r.status.text() == 'PENDING' && name.startsWith(""" + json.dumps(prefix) + """))
      out << [k, r.id.text()] } }
return groovy.json.JsonOutput.toJson(out)""")
    url = {"run": "requests", "grant": "grants", "activation": "activations"}
    return {rid: api("admin", f"/batch-control/{url[k]}/{rid}/cancel", "POST").status_code for k, rid in rows}


# ---------------------------------------------------------------- mail (mailpit)
def mails_for(to, needle):
    """Messages to `to` whose subject or plain body holds `needle`: [{id, subject, created}] (newest first)."""
    r = requests.get(MAIL + "/api/v1/messages?limit=500", timeout=20)
    out = []
    for m in r.json().get("messages", []):
        if to not in [a.get("Address") for a in m.get("To") or []]:
            continue
        hit = needle in (m.get("Subject") or "")
        if not hit:
            hit = needle in requests.get(MAIL + f"/api/v1/message/{m['ID']}", timeout=20).json().get("Text", "")
        if hit:
            out.append({"id": m["ID"], "subject": m.get("Subject"), "created": m.get("Created")})
    return out


# ---------------------------------------------------------------- nodes and queue
def node_labels(labels=None):
    """The built-in node's label string; set first when `labels` is given (arrangement, restored by the caller). Core's
    labels cache the nodes they match: after Jenkins#setLabelString alone a label taken off the built-in node kept
    matching it (2.568.3, the e2e-29 dev runs), so the node list is set again, which refreshes the caches as a node
    configuration save does."""
    if labels is not None:
        gv(f"def j = jenkins.model.Jenkins.get(); j.setLabelString({json.dumps(labels)}); j.save()\n"
           "j.setNodes(j.getNodes()); return 'ok'")
    return gv("return jenkins.model.Jenkins.get().getLabelString()")


def queue_of_request(rid):
    return runs_of_request(rid).get("queue", [])


def builds_of_request(rid):
    return runs_of_request(rid).get("builds", [])


def ready(timeout=420):
    """Waits after a (re)start until the admin API answers with batch-control active; seconds waited or None."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            r = api("admin", "/pluginManager/api/json?tree=plugins[shortName,active]")
            if r.status_code == 200 and any(p.get("shortName") == "batch-control" and p.get("active")
                                            for p in r.json()["plugins"]):
                return round(time.time() - t0)
        except Exception:  # noqa: Jenkins is still starting
            pass
        time.sleep(2)
    return None


def ended_row(user, rid):
    """The cells of the row of grant request `rid` in `user`'s grants page lists ({list name: cells}), read over REST as
    the page renders them (data-batch-control-list tables)."""
    html = ""
    for page in range(1, 21):
        r = api(user, f"/batch-control/grants/?endedPage={page}")
        html = r.text
        if rid in html:
            break
    out = {}
    for m in re.finditer(r"<table[^>]*data-batch-control-list=\"([a-z-]+)\"[^>]*>(.*?)</table>", html, re.S):
        for row in re.findall(r"<tr[^>]*>(.*?)</tr>", m.group(2), re.S):
            if rid in row:
                out[m.group(1)] = [re.sub(r"\s+", " ", text_of(c)).strip() for c in re.findall(r"<td[^>]*>(.*?)</td>", row, re.S)]
    return out


def shot_page(user, path, name, target="#main-panel"):
    """A screenshot of `path` as `user` in a new browser context (the evidence of a step); returns the page text."""
    s = Session(user)
    s.go(path)
    s.shot(target, name)
    t = s.text()
    s.done()
    return t
