"""e2e-19 (gap audit) on top of ../r16/lib.py: screenshots in screenshots/run-19/ (never committed), rows in
r19/out/<driver>.jsonl. Same rules as every e2e pass: one new browser context per account (real login form), a
red-boxed clipped screenshot per step, the server state read separately (REST with basic auth, the store through the
script console, which is used only to arrange or read state).

Verdict lines: every assertion prints "PASS {...}" or "FAIL {...}" (ci/shard.py fails a step on a FAIL line or a
non-zero exit). A section that raises prints one FAIL line and the driver goes on with the next section."""
import importlib.util
import json
import os
import pathlib
import re
import subprocess
import time

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("r16lib", HERE.parent / "r16" / "lib.py")
_l = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_l)
_r6 = _l._l
_r6.SHOTS = HERE.parent / "screenshots" / "run-19"
_r6.OUT = HERE / "out"
_r6.SHOTS.mkdir(parents=True, exist_ok=True)
_r6.OUT.mkdir(parents=True, exist_ok=True)
_l.OUT = _r6.OUT
_l.SHOTS = _r6.SHOTS

Session, close, api, groovy, ENV, BASE, pw = _l.Session, _l.close, _l.api, _l.groovy, _l.ENV, _l.BASE, _l.pw
gv, check, note, text_of, J, decide, changes, builds, wait_build, next_build, sha, make_file = (
    _l.gv, _l.check, _l.note, _l.text_of, _l.J, _l.decide, _l.changes, _l.builds, _l.wait_build, _l.next_build, _l.sha,
    _l.make_file)
request_state, requests_of, loc_id, tick, fill_run_form, param_box, console_ok, run_files = (
    _l.request_state, _l.requests_of, _l.loc_id, _l.tick, _l.fill_run_form, _l.param_box, _l.console_ok, _l.run_files)
UUID, N, LOGNAME = _l.UUID, _l.N, _l.LOGNAME
LOGNAME[0] = "r19"
CONTAINER = (os.environ["BC_CONTAINER"] + "-jenkins") if os.environ.get("BC_CONTAINER") else "batch-control-e2e"
PREFIX = re.sub(r"^https?://[^/]+", "", BASE).rstrip("/")


def run_sections(want, table):
    _l.run_sections(want, table)


def run_req(user, job, reason, approvers=("approver-1",), params=None):
    """A run request through the plugin's own POST endpoint (json form, as the browser form posts it).
    Returns (status, request id or None, response)."""
    body = {"reason": reason, "approvers": list(approvers),
            "parameter": [{"name": k, "value": v} for k, v in (params or {}).items()]}
    r = api(user, J(job) + "/batch-control/submit", "POST", data={"json": json.dumps(body)})
    return r.status_code, loc_id(r), r


def act_req(user, job, action, reason, approvers=("approver-1",)):
    data = [("action", action), ("reason", reason)] + [("approvers", a) for a in approvers]
    r = api(user, J(job) + "/batch-control-activation/submit", "POST", data=data)
    return r.status_code, loc_id(r)


def queue_items(job):
    """Queue items of `job` (name match on the task's full name)."""
    r = api("admin", "/queue/api/json?tree=items[id,task[name,url],why]")
    items = r.json().get("items", []) if r.status_code == 200 else []
    return [i for i in items if (i.get("task") or {}).get("url", "").rstrip("/").endswith(J(job).rstrip("/"))]


def build_count(job):
    return len(builds(job))


def recent_changes(since_ms, pred=None):
    """Change records written at or after `since_ms` (epoch millis of the controller clock)."""
    out = []
    for r in changes(pred):
        at = r.get("at")
        try:
            ms = at if isinstance(at, (int, float)) else int(time.mktime(time.strptime(at[:19], "%Y-%m-%dT%H:%M:%S")) * 1000)
        except Exception:  # noqa
            ms = since_ms
        out.append((ms, r))
    return [r for ms, r in out if ms >= since_ms - 2000]


def now_ms():
    return int(gv("return System.currentTimeMillis()"))


def changes_since(mark, pred=None):
    """Change records appended after `mark` (the record count returned by changes_mark())."""
    rows = changes()
    return [r for r in rows[mark:] if pred is None or pred(r)]


def changes_mark():
    return len(changes())


def set_property(job, approval=True, timer=True, upstream=True, allowed=""):
    """Arrangement (script console, admin): THE batch-control job property of `job`, as the job configuration would
    save it. Replaces the property (Job#addProperty appends), so the creation-time lock is overwritten."""
    return gv(f"""def j = jenkins.model.Jenkins.get(); def job = j.getItemByFullName({json.dumps(job)})
def cl = j.pluginManager.uberClassLoader.loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty')
def p = cl.getConstructor(boolean).newInstance({str(approval).lower()})
p.setBlockTimer({str(timer).lower()}); p.setBlockUpstream({str(upstream).lower()}); p.setAllowedUpstreamJobsText({json.dumps(allowed)})
def old = job.getProperty(cl); if (old != null) job.removeProperty(cl)
job.addProperty(p); job.save()
def n = job.getProperty(cl)
return 'approvalRequired=' + n.approvalRequired + ' blockTimer=' + n.blockTimer + ' blockUpstream=' + n.blockUpstream + ' allowed=' + n.allowedUpstreamJobs""")


def activation(job):
    """The stored activation state of `job` ('activated' / 'not activated' / ...), as the job page notice says it."""
    r = api("admin", J(job) + "/")
    t = text_of(r.text)
    m = re.search(r"Batch Control: this (?:job|folder) is ([a-z ]+?)\.", t)
    return m.group(1) if m else None


def cli(user, *args, stdin=None):
    """jenkins-cli.jar inside the Jenkins container (it has Java 21), as `user`; returns (exit code, output)."""
    get = f"[ -f /tmp/jenkins-cli.jar ] || curl -sSf -o /tmp/jenkins-cli.jar http://localhost:8080{PREFIX}/jnlpJars/jenkins-cli.jar"
    subprocess.run(["docker", "exec", CONTAINER, "sh", "-c", get], check=True, capture_output=True, timeout=120)
    p = subprocess.run(["docker", "exec", "-i", CONTAINER, "java", "-jar", "/tmp/jenkins-cli.jar", "-s",
                        f"http://localhost:8080{PREFIX}/", "-http", "-auth", f"{user}:{pw(user)}"] + list(args),
                       input=stdin, capture_output=True, text=True, timeout=180)
    return p.returncode, (p.stdout + p.stderr)


def wait_until(fn, timeout=120, step=2):
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = fn()
        if v:
            return v
        time.sleep(step)
    return fn()


def wait_executed(rid, timeout=150):
    """Waits until the request is EXECUTED (or any end state); returns (status, detail text)."""
    end = None

    def done():
        nonlocal end
        st, status, t = request_state(rid)
        end = (status, t)
        return status in ("EXECUTED", "EXPIRED", "REJECTED", "CANCELLED", "INVALIDATED")
    wait_until(done, timeout, 3)
    return end


def browser_decide(user, rid, verb, comment="", shot_name=None, kind="requests"):
    """Opens the request's page as `user` and presses Approve or Reject with `comment`. Returns (session, text)."""
    s = Session(user)
    s.go(f"/batch-control/{kind}/{rid}/")
    form = s.page.locator(f"form[name={verb}]")
    # Wave D D3 (#18.5): the run request page has one form "decision" with one comment box and a button per verb whose
    # formaction names the endpoint; the permission window and activation pages keep a form per verb
    btn = form.locator("button[name=Submit], button[type=submit], input[type=submit]").first
    if form.count() == 0:
        form = s.page.locator("form[name=decision]")
        btn = form.locator(f"button[formaction$='/{verb}']").first
        if form.count() == 0 or btn.count() == 0:
            return s, None
    form.locator("textarea[name=comment]").fill(comment)
    with s.page.expect_navigation(timeout=20000):
        btn.click()
        # #93: Reject asks for confirmation first (skipped for a blank comment, which the
        # server refuses anyway); confirm core's dialog so the form posts.
        if verb == "reject" and comment.strip() and _r6.opened(s.page, timeout=5000):
            s.page.locator("dialog[open] button[data-id=ok]").first.click()
    s.page.wait_for_load_state("load")
    if shot_name:
        s.shot("#main-panel", shot_name)
    return s, s.text()


def job_xml(kind, props="", shell="", script="", params="", publishers="", triggers="", extra=""):
    """config.xml for a Freestyle (kind 'fs') or Pipeline (kind 'wf') job."""
    from xml.sax.saxutils import escape
    pdefs = (f"<hudson.model.ParametersDefinitionProperty><parameterDefinitions>{params}</parameterDefinitions>"
             "</hudson.model.ParametersDefinitionProperty>") if params else ""
    if kind == "fs":
        return ("<?xml version='1.1' encoding='UTF-8'?><project><description>e2e-19</description><keepDependencies>false</keepDependencies>"
                f"<properties>{props}{pdefs}</properties><scm class='hudson.scm.NullSCM'/><canRoam>true</canRoam><disabled>false</disabled>"
                f"{extra}<triggers>{triggers}</triggers><concurrentBuild>false</concurrentBuild>"
                f"<builders><hudson.tasks.Shell><command>{escape(shell)}</command></hudson.tasks.Shell></builders>"
                f"<publishers>{publishers}</publishers><buildWrappers/></project>")
    return ("<?xml version='1.1' encoding='UTF-8'?><flow-definition><description>e2e-19</description><keepDependencies>false</keepDependencies>"
            f"<properties>{props}{pdefs}</properties><definition class='org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition'>"
            f"<script>{escape(script)}</script><sandbox>true</sandbox></definition><triggers>{triggers}</triggers><disabled>false</disabled></flow-definition>")


def string_p(name, default=""):
    return (f"<hudson.model.StringParameterDefinition><name>{name}</name><defaultValue>{default}</defaultValue>"
            "<trim>false</trim></hudson.model.StringParameterDefinition>")


def choice_p(name, choices):
    return (f"<hudson.model.ChoiceParameterDefinition><name>{name}</name><choices class='java.util.Arrays$ArrayList'>"
            f"<a class='string-array'>{''.join(f'<string>{c}</string>' for c in choices)}</a></choices></hudson.model.ChoiceParameterDefinition>")


def password_p(name):
    return (f"<hudson.model.PasswordParameterDefinition><name>{name}</name><defaultValue></defaultValue>"
            "</hudson.model.PasswordParameterDefinition>")


def ensure_job(name, xml):
    """Creates `name` from `xml` when it does not exist (idempotent). Returns True when it was created now."""
    parent, _, leaf = name.rpartition("/")
    if api("admin", J(name) + "/api/json").status_code == 200:
        return False
    r = api("admin", (J(parent) if parent else "") + f"/createItem?name={leaf}", "POST", data=xml.encode("utf-8"),
            headers={"Content-Type": "application/xml"})
    assert r.status_code == 200, (name, r.status_code, text_of(r.text)[:300])
    return True


def diff_of(record_id):
    """The stored diff of a change record (batch-control/changes/diff/<id>.patch, ARCHITECTURE storage), '' when none."""
    return gv(f"""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/changes/diff/{record_id}.patch')
return f.exists() ? f.getText('UTF-8') : ''""")


MAIL = f"http://localhost:{os.environ.get('BC_MAIL_PORT', '8025')}"


def mails(to=None, contains=None):
    """Messages in the mailpit sink (newest first): dicts with to, subject, id, text (the plain body)."""
    import requests
    r = requests.get(MAIL + "/api/v1/messages?limit=500", timeout=20)
    out = []
    for m in r.json().get("messages", []):
        rcpt = [a.get("Address") for a in m.get("To") or []]
        if to and to not in rcpt:
            continue
        out.append({"to": rcpt, "subject": m.get("Subject", ""), "id": m.get("ID")})
    res = []
    for m in out:
        if contains is None or contains in m["subject"]:
            res.append(m)
            continue
        body = requests.get(MAIL + f"/api/v1/message/{m['id']}", timeout=20).json().get("Text", "")
        if contains in body:
            m["text"] = body
            res.append(m)
    return res


def mail_text(mid):
    import requests
    return requests.get(MAIL + f"/api/v1/message/{mid}", timeout=20).json().get("Text", "")
