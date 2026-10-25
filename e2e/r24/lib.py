"""e2e-24: CI units for the fixes of wave A (#38, #39, #37, #40), one driver per bug, on top of ../r19/lib.py: screenshots
in screenshots/run-24/ (never committed), rows in r24/out/<driver>.jsonl.

The rules of every e2e pass hold: one new browser context per page and account (real login form), the server state read
separately (REST with basic auth; the script console and `docker exec` only arrange or read state, except where a
driver's docstring says the behaviour under test can only be reached that way, as for #37's deprecated cause). Each
driver arranges its own items (named r24-*) idempotently in its first section and restores what it changes globally.
Verdict lines as in r16..r23: "PASS {...}" / "FAIL {...}" per assertion (ci/shard.py fails a step on a FAIL line or a
non-zero exit), "NOTE {...}" for an observation without a verdict. Every check names the contract item it covers
("[#38 E2] ..."; the contract is the main session's frozen wave A text, quoted in each driver's docstring). Each driver
asserts the correct behaviour, so it fails on a plugin without the fix.

This file is shared by the four drivers and kept identical on every branch that adds one."""
import importlib.util
import json
import pathlib
import re
import time

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("r19lib", HERE.parent / "r19" / "lib.py")
L19 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(L19)
# r19/lib.py points r6/lib.py and r16/lib.py at run-19 and r19/out; this pass writes its own.
for _m in (L19._r6, L19._l):
    _m.SHOTS = HERE.parent / "screenshots" / "run-24"
    _m.OUT = HERE / "out"
L19._r6.SHOTS.mkdir(parents=True, exist_ok=True)
L19._r6.OUT.mkdir(parents=True, exist_ok=True)
SHOTS, OUT = L19._r6.SHOTS, L19._r6.OUT

Session, close, api, groovy, gv, ENV, BASE, pw = L19.Session, L19.close, L19.api, L19.groovy, L19.gv, L19.ENV, L19.BASE, L19.pw
check, note, run_sections, text_of, J, decide, loc_id, tick = (L19.check, L19.note, L19.run_sections, L19.text_of, L19.J,
                                                               L19.decide, L19.loc_id, L19.tick)
builds, next_build, request_state, run_req, act_req, queue_items = (L19.builds, L19.next_build, L19.request_state,
                                                                    L19.run_req, L19.act_req, L19.queue_items)
ensure_job, job_xml, set_property, wait_until, wait_build, activation = (L19.ensure_job, L19.job_xml, L19.set_property,
                                                                         L19.wait_until, L19.wait_build, L19.activation)
string_p, choice_p, run_files, cli, changes, changes_mark, changes_since = (L19.string_p, L19.choice_p, L19.run_files,
                                                                           L19.cli, L19._l.changes, L19.changes_mark,
                                                                           L19.changes_since)
UUID, LOGNAME, CONTAINER = L19.UUID, L19.LOGNAME, L19.CONTAINER
BC = "io.jenkins.plugins.batchcontrol"
CFG = f"def cfg = {BC}.config.BatchControlGlobalConfiguration.get()\n"
FORM = "form[name=batch-control-request]"
FOLDER_XML = ("<?xml version='1.1' encoding='UTF-8'?><com.cloudbees.hudson.plugins.folder.Folder><description>e2e-24"
              "</description><properties/><folderViews/><healthMetrics/></com.cloudbees.hudson.plugins.folder.Folder>")


def facts(script):
    """A JSON object computed by the script console (arrangement facts, server state)."""
    return json.loads(gv(script))


def switches():
    return facts(CFG + """return groovy.json.JsonOutput.toJson([run_control: cfg.runControlEnabled,
  change_control: cfg.changeControlEnabled, approvers: cfg.approvers as List])""")


def has_permission(user, item, permission):
    """Whether `user` holds `permission` (e.g. 'hudson.model.Item.CONFIGURE') on item `item` ('' = the root), read as
    admin through the script console (the ACL the plugin installs, with any permission window it confers)."""
    target = f"j.getItemByFullName({json.dumps(item)})" if item else "j"
    return gv(f"""def j = jenkins.model.Jenkins.get(); def u = hudson.model.User.getById({json.dumps(user)}, false)
def t = {target}
return (u == null || t == null) ? 'null' : t.getACL().hasPermission2(u.impersonate2(), {permission})""") == "true"


def exists(item):
    return api("admin", J(item) + "/api/json?tree=name").status_code == 200


def delete(item):
    """Deletes `item` as admin over REST when it exists; True when it is gone."""
    if exists(item):
        api("admin", J(item) + "/doDelete", "POST")
    return not exists(item)


def create(item, xml):
    """Creates `item` from `xml` as admin over REST (its parent must exist); returns the HTTP status."""
    parent, _, leaf = item.rpartition("/")
    r = api("admin", (J(parent) if parent else "") + f"/createItem?name={leaf}", "POST", data=xml.encode("utf-8"),
            headers={"Content-Type": "application/xml"})
    return r.status_code


def stored_request(rid):
    """Fields of the stored run request file batch-control/requests/run/<rid>.xml (script console, reading only):
    status, jobFullName, decisionComment, requester, and the stored parameters as {name: value}."""
    return facts(f"""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/requests/run/{rid}.xml')
if (!f.exists()) return groovy.json.JsonOutput.toJson([exists: false])
def x = new XmlSlurper().parse(f)
def params = [:]
x.parameters.entry.each {{ e -> def kids = e.children(); if (kids.size() >= 2) params[kids[0].text()] = kids[1].text() }}
return groovy.json.JsonOutput.toJson([exists: true, status: x.status.text(), job: x.jobFullName.text(),
  comment: x.decisionComment.text(), requester: x.requester.text(), parameters: params])""")


def pending_requests(job_prefix):
    """ids of the PENDING or APPROVED run requests whose job's full name starts with `job_prefix` (leftovers)."""
    out = gv(f"""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/requests/run')
def ids = []
if (d.exists()) d.listFiles().findAll {{ it.name ==~ /[0-9a-f-]{{36}}\\.xml/ }}.each {{ f ->
  def x = new XmlSlurper().parse(f)
  if (x.jobFullName.text().startsWith({json.dumps(job_prefix)}) && x.status.text() in ['PENDING', 'APPROVED']) ids << x.id.text() }}
return ids.join(',')""")
    return [x for x in out.split(",") if x]


def all_queue():
    """Every queue item (admin's view): [(id, task url, why)]."""
    r = api("admin", "/queue/api/json?tree=items[id,task[url],why]")
    items = r.json().get("items", []) if r.status_code == 200 else []
    return [(i.get("id"), (i.get("task") or {}).get("url", ""), i.get("why")) for i in items]


def build_params(job, number):
    """{name: value} of the ParametersAction of build `number` of `job` (REST), or None when the build is missing."""
    r = api("admin", J(job) + f"/{number}/api/json?tree=actions[parameters[name,value]]")
    if r.status_code != 200:
        return None
    out = {}
    for a in r.json().get("actions", []):
        for p in (a or {}).get("parameters", []) or []:
            out[p.get("name")] = p.get("value")
    return out


def runs_of_request(rid):
    """What request `rid`'s approval started anywhere (script console, reading only): the queue items carrying its
    approval marker and the builds of any job (the last 50 of each) whose cause is its approved cause."""
    return facts(f"""def j = jenkins.model.Jenkins.get(); def cl = j.pluginManager.uberClassLoader
def A = cl.loadClass('{BC}.queue.ApprovedRunAction'); def C = cl.loadClass('{BC}.queue.ApprovedCause')
def q = j.queue.items.findAll {{ it.getAction(A)?.requestId == '{rid}' }}.collect {{ it.task.fullName + ' queue#' + it.id }}
def b = []
j.allItems(hudson.model.Job).each {{ job -> job.builds.limit(50).each {{ r ->
  if (r.getCauses().any {{ C.isInstance(it) && it.requestId == '{rid}' }}) b << r.externalizableId }} }}
return groovy.json.JsonOutput.toJson([queue: q, builds: b])""")


def browser_delete(item, shot=None):
    """admin deletes `item` as a person does, in a new browser context: the item's Delete confirmation page, then its
    Yes button. Returns (HTTP status of the deletion's response or None, page text after it)."""
    s = Session("admin", fresh=True)
    s.go(J(item) + "/delete")
    btn = s.page.locator("#main-panel form button[name=Submit], #main-panel form button[type=submit]").first
    if shot:
        s.shot("#main-panel form" if btn.count() else "#main-panel", shot + "-confirm")
    if btn.count() == 0:
        t = s.text()
        s.done()
        return None, t
    with s.page.expect_navigation(timeout=30000) as nav:
        btn.click()
    s.page.wait_for_load_state("load")
    status = nav.value.status if nav.value else None
    t = s.text()
    if shot:
        s.shot("body", shot + "-after")
    s.done()
    return status, t


def form_request(user, job, reason, approver="approver-1", shot=None, fill=None):
    """`user` files a run request through the Request Run form in a new browser context, ticking only `approver`;
    `fill(form)` may type parameter values first. Returns (request id or None, page text)."""
    s = Session(user, fresh=True)
    s.go(J(job) + "/batch-control/")
    form = s.page.locator(FORM)
    if form.count() == 0:
        t = s.text()
        if shot:
            s.shot("body", shot)
        s.done()
        return None, t
    form.locator("textarea[name=reason]").fill(reason)
    for box in form.locator("input[name=approvers]:checked").all():
        tick(form, "approvers", box.get_attribute("value"), on=False)
    tick(form, "approvers", approver)
    if fill:
        fill(form)
    with s.page.expect_navigation(timeout=30000):
        form.locator("button[name=Submit]").click()
    s.page.wait_for_load_state("load")
    m = re.search(r"/batch-control/requests/(" + UUID + ")/", s.page.url)
    t = s.text()
    if shot:
        s.shot("#main-panel", shot)
    s.done()
    return (m.group(1) if m else None), t


def browser_decide(user, rid, verb, comment, shot=None, kind="requests"):
    """`user` opens the request's page in a new browser context and presses Approve or Reject with `comment`.
    Returns (form present, HTTP status of the decision's response or None, page text after it)."""
    s = Session(user, fresh=True)
    s.go(f"/batch-control/{kind}/{rid}/")
    form = s.page.locator(f"form[name={verb}]")
    btn = form.locator("button[name=Submit], button[type=submit], input[type=submit]").first
    if form.count() == 0:  # Wave D D3 (#18.5): the run request page's one form "decision", a button per verb
        form = s.page.locator("form[name=decision]")
        btn = form.locator(f"button[formaction$='/{verb}']").first
    if form.count() == 0 or btn.count() == 0:
        t = s.text()
        if shot:
            s.shot("#main-panel", shot)
        s.done()
        return False, None, t
    box = form.locator("textarea[name=comment]")
    if box.count():
        box.fill(comment)
    with s.page.expect_navigation(timeout=30000) as nav:
        btn.click()
        # #93: Reject asks for confirmation first (skipped for a blank comment, which the
        # server refuses anyway); confirm core's dialog so the form posts.
        if verb == "reject" and comment.strip() and L19._r6.opened(s.page, timeout=5000):
            s.page.locator("dialog[open] button[data-id=ok]").first.click()
    s.page.wait_for_load_state("load")
    status = nav.value.status if nav.value else None
    t = s.text()
    if shot:
        s.shot("#main-panel", shot)
    s.done()
    return True, status, t


def approve_rest(user, rid, comment="e2e-24", kind="requests"):
    """(HTTP status, visible text) of `user`'s POST approve on the request (REST, basic auth)."""
    r = api(user, f"/batch-control/{kind}/{rid}/approve", "POST", data={"comment": comment})
    return r.status_code, text_of(r.text)


def refusal_text(text):
    """The refusal sentence of a decision page or response: the error box text when there is one."""
    text = text or ""
    m = re.search(r"(?:Grant r|R)equest [0-9a-f-]{36} is \w+[^.]*\.", text)
    if m:
        return m.group(0)
    m = re.search(r"(?i)no longer|deleted|does not exist|refused|cannot|is off", text)
    return text[max(0, m.start() - 160):m.end() + 160] if m else text[-240:]


def wait_status(rid, statuses, timeout=60):
    """Waits until the stored request's status is one of `statuses`; returns the last stored request facts."""
    last = {}

    def done():
        nonlocal last
        last = stored_request(rid)
        return last.get("status") in statuses
    wait_until(done, timeout, 2)
    return last


def make_token(user, name):
    """A new API token of `user` through core's endpoint (the user's own session); returns (value, uuid)."""
    import requests
    s = requests.Session()
    s.auth = (user, pw(user))
    c = s.get(BASE + "/crumbIssuer/api/json").json()
    r = s.post(BASE + f"/user/{user}/descriptorByName/jenkins.security.ApiTokenProperty/generateNewToken",
               data={"newTokenName": name}, headers={c["crumbRequestField"]: c["crumb"]})
    data = r.json().get("data", {}) if r.status_code == 200 else {}
    return data.get("tokenValue"), data.get("tokenUuid")


def revoke_token(user, uuid):
    return api(user, f"/user/{user}/descriptorByName/jenkins.security.ApiTokenProperty/revoke", "POST",
               data={"tokenUuid": uuid}).status_code


def token_session(user, token):
    """A requests session authenticating `user` with an API token (no crumb needed, no session cookie)."""
    import requests
    s = requests.Session()
    s.auth = (user, token)
    return s


def timed(fn):
    t0 = time.monotonic()
    v = fn()
    return v, round(time.monotonic() - t0, 3)


def run(argv, table, cleanup=None):
    """Runs the sections named by the letters of argv[1] (default: all, in table order; the arrangement "A" always
    runs first), each catching its exception as one FAIL line, then `cleanup` (its exception is a FAIL too), then
    closes the browser, prints the summary and exits 1 when any check failed."""
    want = [x for x in (argv[1] if len(argv) > 1 else "".join(table)) if x in table]
    if "A" in table and "A" not in want:
        want.insert(0, "A")
    for sec in want:
        try:
            table[sec]()
        except Exception as e:  # noqa: a section that raises is one FAIL, the driver goes on
            check(sec, "section raised", False, error=repr(e)[:600])
    if cleanup is not None:
        try:
            cleanup()
        except Exception as e:  # noqa
            check("cleanup", "restoring the global state raised", False, error=repr(e)[:600])
    run_sections([], table)
