"""e2e gap H2 (SPEC item 1, the first two acceptance lines): with a switch off, Jenkins behaves as before, and one switch
being on does not bring the other switch's UI or blocking. Section O of r18/final.py (it switches the switches, so it runs
in the `last` unit r18-final, after A and before S). It replaces the observational, non-CI r7/c_switch.py.

Arrangement (script console as admin, idempotent): account h2req with the requester role's permissions (Overall/Read,
Item/Read, View/Read, Item/Build, BatchControl/Request, BatchControl/RequestGrant; no Configure), so the run-control entries
and the "Request Change Permission" entry are both due to it whenever their switch is on; a dedicated account so that
flipping its new job page flag disturbs no other driver. Items: folder r18/h2 with r18/h2/run (Freestyle, approvalRequired,
no parameters, quiet period 0, `echo`), r18/h2/cfg (configured by configurer) and a throwaway r18/h2/del-<n> per run.

O-ctl  Both switches on (the state section A leaves): h2req's classic and new job page of r18/h2/run show the run-control
       UI ("Direct Build (needs approval)", "Request Run", the Batch Control notice) and the "Request Change Permission"
       entry (on the new job page in its overflow menu), and the folder page r18/h2 shows "Request Change Permission".
       The positive control that the absence checks below look for the right things. Menus: the overflow menu is opened
       and read like a person does, and core's menu model for the session (contextMenu?menu-only=true, what that menu
       renders) is read too; an opened menu must agree with the model on the Batch Control entries, and the model alone
       decides when core's menu keeps its loading spinner (seen locally on the first new-job-page visit of a fresh run).
O-run  Run control OFF, change control ON (recorded as such): on the classic and the new job page h2req gets core's
       "Build Now" and no "Direct Build (needs approval)", no "Request Run", no Batch Control notice card or alert, no
       activation/run-request link; the change-control entry "Request Change Permission" is still there (one switch does
       not take the other's UI with it). Pressing Build Now on each page queues a build that completes (SUCCESS, started
       by h2req) with no dialog and no approval text; a direct POST /job/r18/job/h2/job/run/build answers 201/302 and
       that build completes too. No run request is stored for the job and no TRIGGER_BLOCKED record is written.
O-chg  Change control OFF, run control ON: on the classic and the new job page and on the folder page h2req gets no
       "Request Change Permission" entry and no link into /batch-control/grants/; configurer (core Configure and
       Delete) loads r18/h2/cfg's configure page (200) and saves it (the description is stored), and deletes a throwaway
       job through the job page's Delete entry (side panel, or the new job page's overflow menu) and core's confirmation
       (the job is gone), with no Batch Control refusal. (With change control on the same delete is refused with
       "Permission window required", HTTP 400, so this check also goes red when change control fails to switch off.)
       Run control still gates: the job page shows "Direct Build (needs approval)", "Request Run" and the notice, no
       "Build Now", and a direct POST /build is refused (not 201) with nothing queued or built.
O-rst  Both switches are back on (the state section S expects), read back from the configuration.

Every check prints "PASS r18 <check> {...}" or "FAIL r18 <check> {...}" and a FAIL makes the driver exit non-zero
(lib.N, run_sections), so ci/shard.py fails the step. Waits are bounded polls, never fixed sleeps."""
import json
import re
import time

import lib
from lib import Session, api, gv, J, BASE, set_switches, switches, records_for, configure_save, flat, now_millis, exists

L16 = lib.L16
USER = "h2req"
FOLDER = "r18/h2"
RUN = "r18/h2/run"
CFG = "r18/h2/cfg"
# Where a job page offers its entries: the classic side panel (#tasks), core's app bar, and the new job page's build bar
# (.app-build-bar__controls, where core's Build Now and the plugin's Request Run sit). The new job page's other entries
# ("Request Change Permission", "Delete Project") are in its overflow menu, which is read by opening it (overflow()).
APPBAR = (".jenkins-app-bar a, .jenkins-app-bar button, .app-page-body__header a, .app-page-body__header button, "
          ".app-build-bar__controls a, .app-build-bar__controls button, #tasks a, #tasks button")
MENU_ITEMS = ".tippy-box a, .tippy-box button"
BLOCKED_LABEL = "Direct Build (needs approval)"
REQUEST_RUN = "Request Run"
GRANT_LABEL = "Request Change Permission"
NOTICE = "manual runs of this job need an approved run request"


def hcheck(cid, step, ok, **kw):
    """One verdict line: "PASS r18 <cid> {json}" / "FAIL r18 <cid> {json}"; a FAIL counts towards the driver's exit code."""
    row = dict(sec="O", check=cid, step=step, ok=bool(ok), **kw)
    L16.N["pass" if ok else "fail"] += 1
    L16.log(row)
    print(f"{'PASS' if ok else 'FAIL'} r18 {cid} " + json.dumps(row, default=str)[:1500], flush=True)
    return bool(ok)


# ---------------------------------------------------------------- arrangement
def arrange():
    out = gv(r'''
import jenkins.model.Jenkins
import hudson.model.*
import hudson.security.*
import org.jenkinsci.plugins.matrixauth.*
def j = Jenkins.get()
def BC = 'io.jenkins.plugins.batchcontrol.security.BatchControlPermissions.'
if (User.getById('h2req', false) == null) j.getSecurityRealm().createAccount('h2req', "''' + lib.ENV["BC_OTHER_PASSWORD"] + r'''")
def s = j.getAuthorizationStrategy()
['hudson.model.Hudson.Read', 'hudson.model.Item.Read', 'hudson.model.View.Read', 'hudson.model.Item.Build', BC + 'Request',
 BC + 'RequestGrant'].each { pid -> s.add(Permission.fromId(pid), new PermissionEntry(AuthorizationType.USER, 'h2req')) }
j.save()
def F = com.cloudbees.hudson.plugins.folder.Folder
def r18 = j.getItem('r18') ?: j.createProject(F, 'r18')
def h2 = r18.getItem('h2') ?: r18.createProject(F, 'h2')
def pc = j.pluginManager.uberClassLoader.loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty')
def run = h2.getItem('run')
if (run == null) {
  run = h2.createProject(FreeStyleProject, 'run')
  run.getBuildersList().add(new hudson.tasks.Shell('echo e2e-18 H2 switches off'))
}
run.setQuietPeriod(0)
// the creation-time lock (D-34) is replaced by exactly approvalRequired=true (blockTimer/blockUpstream false)
while (run.getProperty(pc) != null) run.removeProperty(pc)
run.addProperty(pc.getConstructor(boolean).newInstance(true))
run.save()
if (h2.getItem('cfg') == null) { def c = h2.createProject(FreeStyleProject, 'cfg'); c.setDescription('e2e-18 H2 v0'); c.save() }
def u = User.getById('h2req', false)
def acl = { it, p -> it.getACL().hasPermission2(u.impersonate2(), p) }
def BCP = io.jenkins.plugins.batchcontrol.security.BatchControlPermissions
def cu = User.getById('configurer', false)
return groovy.json.JsonOutput.toJson([build: acl(run, Item.BUILD), configure: acl(run, Item.CONFIGURE), request: acl(run, BCP.REQUEST),
  requestGrant: j.getACL().hasPermission2(u.impersonate2(), BCP.REQUEST_GRANT), approvalRequired: run.getProperty(pc)?.isApprovalRequired(),
  configurerConfigure: h2.getItem('cfg').getACL().hasPermission2(cu.impersonate2(), Item.CONFIGURE),
  configurerDelete: h2.getItem('cfg').getACL().hasPermission2(cu.impersonate2(), Item.DELETE)])''')
    try:
        return json.loads(out)
    except ValueError:
        return {"raw": out[:400]}


def set_flag(value):
    """The new job page experimental flag of h2req (as r14/set_flag.py); returns its value afterwards."""
    return gv("""import jenkins.model.experimentalflags.*
def u = hudson.model.User.getById('h2req', true)
def m = new HashMap(); m.put('new-job-page.flag', '""" + value + """')
u.addProperty(new UserExperimentalFlagsProperty(m)); u.save()
return String.valueOf(new NewJobPageUserExperimentalFlag().getFlagValue(u))""")


def run_requests(job):
    """Ids of the stored run requests (requests/run/<id>.xml, any status) of `job`."""
    out = gv("""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/requests/run'); def o = []
if (d.exists()) d.listFiles().findAll { it.name.endsWith('.xml') && !it.name.endsWith('.values.xml') }.each { f ->
  try { def x = new XmlSlurper().parse(f); if (x.jobFullName.text() == """ + json.dumps(job) + """) o << x.id.text() } catch (e) { o << 'unreadable:' + f.name } }
return o.sort().join(',')""")
    return [x for x in out.split(",") if x]


def job_state(job):
    r = api("admin", J(job) + "/api/json?tree=nextBuildNumber,inQueue,lastBuild[number]")
    d = r.json() if r.status_code == 200 else {}
    return {"next": d.get("nextBuildNumber"), "inQueue": d.get("inQueue"), "last": (d.get("lastBuild") or {}).get("number")}


def wait_build(job, number, timeout=120):
    """Bounded poll (1 s) of build `number` until it has finished: {status, result, building, user} of the build API."""
    t0 = time.time()
    last = {}
    while time.time() - t0 < timeout:
        r = api("admin", J(job) + f"/{number}/api/json?tree=number,building,result,actions[causes[userId,shortDescription]]")
        if r.status_code == 200:
            d = r.json()
            users = [c.get("userId") for a in d.get("actions", []) if a for c in a.get("causes", []) or [] if c.get("userId")]
            causes = [c.get("shortDescription") for a in d.get("actions", []) if a for c in a.get("causes", []) or []]
            last = {"status": 200, "building": d.get("building"), "result": d.get("result"), "users": users, "causes": causes}
            if not d.get("building") and d.get("result"):
                last["seconds"] = round(time.time() - t0, 1)
                return last
        else:
            last = {"status": r.status_code}
        time.sleep(1)
    last["timed_out"] = timeout
    return last


def settle(page):
    try:
        page.wait_for_load_state("networkidle", timeout=8000)
    except Exception:
        pass


def overflow(p):
    """The entries of the page's overflow menu ("More actions", new job page) as a person sees them after opening it: []
    when the page has none, None when it is there but did not show entries (two attempts of 5 s; core's menu sometimes
    keeps its loading spinner, CI-like local run 2026-10-26, so the second attempt reloads the page first). It is closed
    again before returning."""
    ov = p.get_by_test_id("app-bar-overflow-button")
    if not ov.count() or not ov.first.is_visible():
        return []
    items = None
    for attempt in range(2):
        if attempt:
            p.reload()
            p.wait_for_load_state("load")
            settle(p)
            ov = p.get_by_test_id("app-bar-overflow-button")
        ov.first.click()
        try:
            p.locator(MENU_ITEMS).first.wait_for(state="visible", timeout=5000)
            items = [flat(t) for t in p.locator(MENU_ITEMS).all_inner_texts() if flat(t)]
        except Exception:
            items = None
        p.keyboard.press("Escape")
        try:
            p.locator(".tippy-box").first.wait_for(state="detached", timeout=3000)
        except Exception:
            pass
        if items:
            return items
    return None


def menu_model(p, path):
    """The display names of the page's context menu as the server builds it for this session (core's
    contextMenu?menu-only=true, the data the new job page's overflow menu and the breadcrumb menu render), or None."""
    try:
        r = p.request.get(BASE + path + "contextMenu?menu-only=true")
        if r.status != 200:
            return None
        return [i.get("displayName") for i in r.json().get("items", []) if i.get("displayName")]
    except Exception:
        return None


def look(s, path, tag):
    """Opens `path` in session `s` and reads what the page offers: visible entry labels of the app bar and side panel,
    the Batch Control notice, and which labels/links occur anywhere in the served markup (menus and templates too)."""
    r = s.go(path)
    p = s.page
    settle(p)
    labels = []
    for el in p.locator(APPBAR).all():
        try:
            if el.is_visible():
                t = flat(el.inner_text() or el.get_attribute("aria-label") or el.get_attribute("tooltip") or "")
                if t and t not in labels:
                    labels.append(t)
        except Exception:
            continue
    html = p.content()
    body = flat(p.locator("body").inner_text())
    menu = overflow(p)
    model = menu_model(p, path)
    seen = (menu or []) + (model or [])
    info = {
        "status": r.status if r else None,
        "labels": labels,
        "menu": menu,
        "menu_model": model,
        # the menu is known when the server's menu model was read, and the opened overflow menu (when the page has one
        # and it opened) agrees with it on the Batch Control entries
        "menu_read": model is not None and (not menu or all((x in menu) == (x in model)
                                                                for x in (GRANT_LABEL, REQUEST_RUN, BLOCKED_LABEL))),
        "build_now": any(re.fullmatch(r"Build Now", t) for t in labels),
        "blocked_label": BLOCKED_LABEL in html or BLOCKED_LABEL in seen,
        "request_run": REQUEST_RUN in html or REQUEST_RUN in seen,
        "notice": NOTICE in body or p.locator("#batch-control-notices").count() > 0
        or p.locator(".jenkins-alert", has_text="Batch Control:").count() > 0,
        "activation_link": "batch-control-activation" in html,
        "grant_entry": GRANT_LABEL in html or GRANT_LABEL in seen,
        "grant_link": "batch-control/grants/" in html,
        "url": p.url.replace(BASE, ""),
    }
    s.shot("body", f"R18-O-{tag}")
    return info


def look_as(user, path, tag):
    s = Session(user)
    try:
        return look(s, path, tag)
    finally:
        s.done()


def press_build_now(user, path, job, tag):
    """`user` presses core's Build Now on the job page; returns (what the page showed, the build's outcome)."""
    before = job_state(job)
    s = Session(user)
    try:
        info = look(s, path, tag + "-before")
        p = s.page
        btn = p.locator(APPBAR).filter(has_text=re.compile(r"^\s*Build Now\s*$"))
        visible = [b for b in btn.all() if b.is_visible()]
        clicked = False
        if visible:
            visible[0].click()
            clicked = True
        number = before.get("next")
        outcome = wait_build(job, number) if clicked else {"not_clicked": True}
        p.wait_for_load_state("load")
        text = flat(p.locator("body").inner_text())
        info.update(clicked=clicked, number=number, dialogs=p.locator("dialog[open]").count(),
                    approval_text=[w for w in ("approved run request", "needs approval", "Approval required") if w in text],
                    url_after=p.url.replace(BASE, ""))
        s.shot("body", f"R18-O-{tag}-after")
        return info, outcome
    finally:
        s.done()


def built_ok(outcome, user):
    return outcome.get("result") == "SUCCESS" and user in (outcome.get("users") or [])


# ---------------------------------------------------------------- the browser delete (configurer)
def confirm_dialog(page):
    page.locator("dialog[open] button[data-id=ok]").wait_for(state="visible", timeout=10000)
    with page.expect_navigation():
        page.locator("dialog[open] button[data-id=ok]").click()
    page.wait_for_load_state("load")


def browser_delete(user, job, tag):
    s = Session(user)
    try:
        r = s.go(J(job) + "/")
        p = s.page
        label = re.compile(r"^\s*Delete( Project)?\s*$")
        # The classic side panel lists the entry; the new job page (configurer's default) has it in the overflow menu.
        entry = p.locator("#tasks a, #tasks button").filter(has_text=label)
        where = "side" if entry.count() else None
        if not where:
            ov = p.get_by_test_id("app-bar-overflow-button")
            if ov.count():
                ov.first.click()
                try:
                    p.locator(MENU_ITEMS).first.wait_for(state="visible", timeout=5000)
                except Exception:
                    pass
                entry = p.locator(MENU_ITEMS).filter(has_text=label)
                where = "overflow" if entry.count() else None
        status = None
        body = ""
        if where:
            seen = []
            p.on("response", lambda x: seen.append(x.status) if x.request.method == "POST" else None)
            entry.first.click()
            confirm_dialog(p)
            status = seen[-1] if seen else None
            body = flat(p.locator("body").inner_text())
        s.shot("body", f"R18-O-{tag}")
        return {"page": r.status if r else None, "entry": where, "post": status, "landing": p.url.replace(BASE, ""),
                "refusal": [w for w in ("temporary grant", "permission window", "Change control", "Access Denied", "not kept")
                            if w in body]}
    finally:
        s.done()


# ---------------------------------------------------------------- the section
def sec_O():
    facts = arrange()
    hcheck("O-arrange", "fixture: h2req holds Build, Request and RequestGrant and no Configure on r18/h2/run, which is "
           "approval-required; configurer holds core Configure and Delete on r18/h2/cfg",
           facts.get("build") is True and facts.get("request") is True and facts.get("requestGrant") is True
           and facts.get("configure") is False and facts.get("approvalRequired") is True
           and facts.get("configurerConfigure") is True and facts.get("configurerDelete") is True, **facts)
    sw0 = switches()
    flag0 = gv("return String.valueOf(new jenkins.model.experimentalflags.NewJobPageUserExperimentalFlag()"
               ".getFlagValue(hudson.model.User.getById('h2req', true)))")
    set_flag("false")
    try:
        if not (sw0["run"] and sw0["change"]):
            set_switches(True, True, "O-0-on")
        _controls()
        _run_off()
        _change_off()
    finally:
        cur = switches()
        back = 200 if (cur["run"] and cur["change"]) else set_switches(True, True, "O-9-restore")
        set_flag("false")
        sw = switches()
        hcheck("O-rst", "both switches are on again after section O (the state section S expects), read back from the "
               "configuration", back == 200 and sw["run"] is True and sw["change"] is True,
               save=back, before=sw0, after=sw, h2req_flag_before=flag0)


def _controls():
    """Positive controls: the readers below find the run-control UI and the grant entry when they are due, on both job
    pages (the new job page's grant entry only in its overflow menu) and on the folder page."""
    for ui, flag in (("classic", "false"), ("new", "true")):
        got = set_flag(flag)
        ctl = look_as(USER, J(RUN) + "/", f"1-ctl-job-{ui}")
        hcheck(f"O-ctl-{ui}", f"positive control, both switches on: h2req's {ui} job page (new job page flag {got}) shows "
               "'Direct Build (needs approval)', 'Request Run', the Batch Control notice and 'Request Change Permission' "
               "and no 'Build Now'", got == flag and ctl["status"] == 200 and ctl["menu_read"] and ctl["blocked_label"]
               and ctl["request_run"] and ctl["notice"] and ctl["grant_entry"] and not ctl["build_now"],
               switches=switches(), **ctl)
    set_flag("false")
    fol = look_as(USER, J(FOLDER) + "/", "1-ctl-folder")
    hcheck("O-ctl-folder", "positive control, both switches on: the folder page r18/h2 (h2req) shows 'Request Change "
           "Permission'", fol["status"] == 200 and fol["menu_read"] and fol["grant_entry"], **fol)


def _run_off():
    t0 = now_millis()
    reqs0 = run_requests(RUN)
    save = set_switches(False, True, "O-2-run-off")
    sw = switches()
    hcheck("O-run-switch", "precondition: the administrator turned run control off on the configuration page; change "
           "control stays on", save == 200 and sw["run"] is False and sw["change"] is True, save=save, switches=sw)
    for ui, flag in (("classic", "false"), ("new", "true")):
        got = set_flag(flag)
        info, outcome = press_build_now(USER, J(RUN) + "/", RUN, f"2-run-off-{ui}")
        hcheck(f"O-run-page-{ui}", f"run control off, {ui} job page (h2req, new job page flag {got}): core's 'Build Now' is "
               "offered; no 'Direct Build (needs approval)', no 'Request Run', no Batch Control notice, no activation link; "
               "the change-control entry 'Request Change Permission' is still offered (change control is on)",
               got == flag and info["status"] == 200 and info["menu_read"] and info["build_now"] and not info["blocked_label"]
               and not info["request_run"] and not info["notice"] and not info["activation_link"] and info["grant_entry"],
               **info)
        hcheck(f"O-run-build-{ui}", f"run control off, {ui} job page: pressing Build Now queues a build that completes "
               "(SUCCESS, started by h2req) with no dialog and no approval text on the page",
               info["clicked"] and built_ok(outcome, USER) and info["dialogs"] == 0 and not info["approval_text"],
               build=info.get("number"), outcome=outcome, dialogs=info["dialogs"], approval_text=info["approval_text"])
    before = job_state(RUN)
    r = api(USER, J(RUN) + "/build", "POST")
    outcome = wait_build(RUN, before.get("next")) if r.status_code in (201, 302) else {}
    hcheck("O-run-post", "run control off: a direct POST /job/r18/job/h2/job/run/build by h2req is accepted (201/302) and "
           "the build it queues completes (SUCCESS, started by h2req)",
           r.status_code in (201, 302) and built_ok(outcome, USER), status=r.status_code,
           location=(r.headers.get("Location") or "").replace(BASE, ""), build=before.get("next"), outcome=outcome)
    reqs1 = run_requests(RUN)
    blocked = [x for x in records_for(RUN, t0) if x.get("type") in ("TRIGGER_BLOCKED", "MARKER_REUSE_BLOCKED")]
    hcheck("O-run-store", "run control off: no run request was stored for r18/h2/run and no blocked-trigger record was "
           "written", reqs1 == reqs0 and not blocked, requests_before=reqs0, requests_after=reqs1,
           blocked=[{k: x.get(k) for k in ("type", "user", "detail")} for x in blocked])


def _change_off():
    save = set_switches(True, False, "O-3-change-off")
    sw = switches()
    hcheck("O-chg-switch", "precondition: the administrator turned change control off and run control on on the "
           "configuration page", save == 200 and sw["run"] is True and sw["change"] is False, save=save, switches=sw)
    for ui, flag in (("classic", "false"), ("new", "true")):
        got = set_flag(flag)
        info = look_as(USER, J(RUN) + "/", f"3-change-off-{ui}")
        hcheck(f"O-chg-job-{ui}", f"change control off, {ui} job page (h2req, new job page flag {got}): no 'Request "
               "Change Permission' entry and no link into /batch-control/grants/",
               got == flag and info["status"] == 200 and info["menu_read"] and not info["grant_entry"] and not info["grant_link"],
               **info)
        hcheck(f"O-chg-gate-{ui}", f"change control off, run control on, {ui} job page: run control still gates "
               "('Direct Build (needs approval)', 'Request Run' and the notice are shown, 'Build Now' is not)",
               info["blocked_label"] and info["request_run"] and info["notice"] and not info["build_now"], **info)
    set_flag("false")
    fol = look_as(USER, J(FOLDER) + "/", "3-change-off-folder")
    hcheck("O-chg-folder", "change control off: the folder page r18/h2 (h2req) has no 'Request Change Permission' entry "
           "and no link into /batch-control/grants/", fol["status"] == 200 and fol["menu_read"] and not fol["grant_entry"]
           and not fol["grant_link"],
           **fol)
    before = job_state(RUN)
    r = api(USER, J(RUN) + "/build", "POST")
    body = lib.text_of(r.text)
    loc = (r.headers.get("Location") or "").replace(BASE, "")
    # The refusal is synchronous at queue entry; a short bounded poll makes sure nothing was queued or started late.
    t0, after = time.time(), job_state(RUN)
    while time.time() - t0 < 5 and after == before:
        time.sleep(1)
        after = job_state(RUN)
    hcheck("O-chg-post", "change control off, run control on: a direct POST /build by h2req is refused (not 201, the "
           "approval guidance) and nothing is queued or built",
           r.status_code not in (200, 201) and ("approv" in body.lower() or "batch-control" in loc) and after == before
           and not after.get("inQueue"), status=r.status_code, location=loc, body=body[:240], before=before, after=after)
    desc = f"e2e-18 H2 saved while change control is off {now_millis()}"
    saved = configure_save("configurer", CFG, "O-4-configure", description=desc)
    stored = api("admin", J(CFG) + "/api/json?tree=description").json().get("description")
    hcheck("O-chg-configure", "change control off: configurer (core Configure) loads r18/h2/cfg's configure page (200), "
           "saves it (200 landing) and the description is stored", saved == (200, 200) and stored == desc,
           saved=saved, stored=stored)
    name = f"del-{now_millis()}"
    api("admin", J(FOLDER) + f"/createItem?name={name}&mode=hudson.model.FreeStyleProject", "POST", headers=lib.H)
    made = exists(f"{FOLDER}/{name}")
    res = browser_delete("configurer", f"{FOLDER}/{name}", "5-delete")
    gone = not exists(f"{FOLDER}/{name}")
    hcheck("O-chg-delete", "change control off: configurer (core Delete) deletes a throwaway job through the job page's "
           "Delete entry and core's confirmation; the job is gone and no Batch Control refusal is shown",
           made and res["entry"] is not None and res["post"] in (200, 302) and gone and not res["refusal"]
           and res["landing"].rstrip("/").endswith(J(FOLDER)),
           job=f"{FOLDER}/{name}", created=made, gone=gone, **res)
    if not gone:  # a failed delete leaves the throwaway behind; the administrator removes it (arrangement only)
        api("admin", J(f"{FOLDER}/{name}") + "/doDelete", "POST")
