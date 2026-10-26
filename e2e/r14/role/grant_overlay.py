"""RS-5: the Batch Control grant layer on top of role-strategy 927 roles.

requester holds item role team-all (Job/Read+Build on team(/.*)?), no Configure. In the
browser: requester asks for a CONFIGURE window on team/app-1, approver-1 approves it, requester
opens and saves the configure page; admin revokes the window; requester is refused again.
Server state (REST, basic auth) is read after every step, and the role pages are saved once while
the window is open to check that a role-page save keeps the grant layer.
H1 (2026-10-24): judged at the end by ci/h1_rules.py (judge_role "grant_overlay"); a finding prints a "FAIL role ..." line.
"""
import re
import lib
from lib import Session, close, log, strategy, api, groovy, BASE

res = {}
APP = "/job/team/job/app-1"


def state(tag):
    res[f"{tag}: requester configure"] = api("requester", APP + "/configure").status_code
    res[f"{tag}: requester GET app-1"] = api("requester", APP + "/api/json").status_code
    res[f"{tag}: strategy"] = strategy()


state("0 before")

# requester: new grant request in the browser
r = Session("requester")
r.go("/batch-control/grants/")
# e2e-14: since r4/ui (D-66) the grants page has a "Request Change Permission" button that opens the form in a dialog
r.page.locator("#main-panel button, #main-panel a", has_text="Request Change Permission").first.click()
f = r.page.locator("dialog[open]").first
# D-71: one item per window, no scope type selector; the name check shows the item's kind
f.locator("input[name=scopeFullName]").wait_for()
res["scope type selector"] = f.locator("select[name=scopeType]").count()
f.locator("input[name=scopeFullName]").fill("team/app-1")
f.locator("input[name=scopeFullName]").blur()
try:
    f.locator("[data-batch-control-item-kind]").first.wait_for(timeout=5000)
    res["item kind"] = f.locator("[data-batch-control-item-kind]").first.get_attribute("data-batch-control-item-kind")
except Exception:
    res["item kind"] = None
f.locator("input[name=actions][value=CONFIGURE] + label").click()
dur = f.locator("select[name=durationMinutes]")
if dur.count():
    dur.select_option("15") if dur.locator("option[value='15']").count() else None
f.locator("textarea[name=reason]").fill("e2e-14 role-strategy 927 overlay")
f.locator("input[name=approvers][value='approver-1'] + label").first.click()
r.shot(f, "RS-5-01-grant-form")
with r.page.expect_navigation() as nav:
    f.get_by_role("button", name="Request Grant").click()
r.page.wait_for_load_state("load")
res["request submit"] = (nav.value.status, r.page.url.replace(BASE, ""))
r.shot("#main-panel", "RS-5-02-grant-requested")
res["request console"] = r.console
r.done()
gid = re.search(r"/grants/([^/]+)/?$", res["request submit"][1]).group(1)
res["grant id"] = gid
state("1 requested (pending)")

# approver-1 approves in the browser
a = Session("approver-1")
a.go(f"/batch-control/grants/{gid}/")
a.shot("#main-panel", "RS-5-03-approver-detail")
c = a.page.locator("textarea[name=comment]")
if c.count():
    c.first.fill("e2e-13 ok")
btn = a.page.locator("form[action$='approve'] button, button", has_text=re.compile(r"^\s*Approve\s*$")).first
with a.page.expect_navigation():
    btn.click()
a.page.wait_for_load_state("load")
d = a.page.locator("dialog[open]")
if d.count():
    with a.page.expect_navigation():
        d.first.get_by_role("button", name=re.compile("Approve")).click()
a.shot("#main-panel", "RS-5-04-approved")
res["approved page text"] = re.sub(r"\s+", " ", a.text())[:400]
res["approver console"] = a.console
a.done()
state("2 approved (window open)")

# requester configures and saves under the window
r = Session("requester")
resp = r.go(APP + "/configure")
res["browser configure GET"] = resp.status
r.page.fill("textarea[name=description]", "edited under a grant window on role-strategy 927 (e2e-14)")
r.shot("#main-panel", "RS-5-05-configure-under-grant")
with r.page.expect_navigation() as nav:
    r.page.click("button[name=Submit]")
res["browser configure save"] = (nav.value.status, r.page.url.replace(BASE, ""))
r.done()
res["description saved"] = api("admin", APP + "/api/json?tree=description").json().get("description")

# a role-page save while the window is open keeps the grant layer
s = Session("admin")
s.go("/manage/role-strategy/manage-roles#item")
s.page.wait_for_timeout(800)
card = s.page.locator(".rsp-card").filter(has_text="e2e13-team").first
card.locator("button[aria-label*='Edit' i]").first.click()
dlg = s.page.locator("dialog[open]").first
dlg.wait_for()
dlg.locator("[data-permission-id='hudson.model.Item.Build'] label").click()
dlg.get_by_role("button", name="Save").click()
s.page.wait_for_timeout(1800)
s.shot("#main-panel", "RS-5-06-role-edited-during-window")
res["role edit console"] = s.console
res["role edit bad"] = s.bad
s.done()
state("3 after a Manage Roles save during the window")

# admin revokes in the browser
s = Session("admin")
s.go("/batch-control/grants/")
row = s.page.locator("tr", has_text=gid)
row.locator("button, a", has_text="Revoke").first.click()
dlg = s.page.locator("dialog[open]").first
dlg.wait_for()
s.shot(dlg, "RS-5-07-revoke-dialog")
with s.page.expect_navigation():
    dlg.locator("button", has_text="Revoke").last.click()
s.page.wait_for_load_state("load")
s.shot("#main-panel", "RS-5-08-revoked")
res["admin console"] = s.console
s.done()
state("4 revoked")

r = Session("requester")
resp = r.go(APP + "/configure")
res["browser configure after revoke"] = resp.status
r.shot("#main-panel, body", "RS-5-09-configure-refused")
r.done()
close()
res["audit records"] = groovy(f"""def out=[]; new File(jenkins.model.Jenkins.get().rootDir, 'batch-control').eachFileRecurse {{ f -> if (f.isFile() && f.text.contains('{gid}')) out << f.path.replace(jenkins.model.Jenkins.get().rootDir.path,'') }}; return out""")
for k, v in res.items():
    print(k, "=>", v)
log("grant_overlay", res)
lib.h1.report("role/grant_overlay", [res])
