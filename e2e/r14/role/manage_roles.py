"""RS-1/2: role-strategy 927 Manage Roles under Batch Control: Role-Based Strategy.

Arranged by casc/profile-role.yaml (applied by setup.py). As admin in the browser: open Manage
Roles, add a global role and an item role through the Add Role dialog, typing an invalid and a
valid pattern first so the forwarded checkPattern answer is shown. Records every request the
page makes (status >= 400 flagged) and the console. Server state is read separately.
H1 (2026-10-24): judged at the end by ci/h1_rules.py (judge_role "manage_roles"); a finding prints a "FAIL role ..." line.
"""
import re
import lib
from lib import Session, close, log, strategy, groovy, BASE

res = {}
reqs = []

s = Session("admin")
s.page.on("response", lambda r: reqs.append(f"{r.status} {r.request.method} {r.url.replace(BASE, '')}")
          if "/static/" not in r.url and "/adjuncts/" not in r.url else None)
r = s.go("/manage/role-strategy/manage-roles")
res["manage-roles GET"] = r.status
s.page.wait_for_timeout(800)
s.shot("#main-panel", "RS-1-01-manage-roles")


def open_add(tab):
    t = s.page.get_by_role("tab", name=tab)
    if t.get_attribute("aria-selected") != "true":
        t.click()
    s.page.wait_for_timeout(300)
    s.page.get_by_role("button", name="Add Role").click()
    d = s.page.locator("dialog[open]").first
    d.wait_for()
    return d


def pattern_msg(d, value, tag):
    d.locator("#rsp-role-pattern").fill(value)
    d.locator("#rsp-role-pattern").press("Tab")
    s.page.wait_for_timeout(1500)
    item = d.locator("#rsp-role-pattern").locator("xpath=ancestor::div[contains(@class,'jenkins-form-item')][1]")
    s.shot(item, tag)
    return re.sub(r"\s+", " ", item.inner_text()).strip()


# Global role (no pattern field expected)
d = open_add("Global roles")
res["global dialog has pattern field"] = d.locator("#rsp-role-pattern").count()
d.locator("#rsp-role-name").fill("e2e13-global")
d.locator("[data-permission-id='hudson.model.Hudson.Read'] label").click()
s.shot(d, "RS-1-02-add-global-role")
d.get_by_role("button", name="Add").click()
s.page.wait_for_timeout(1500)
res["global role listed"] = s.page.locator("#main-panel").get_by_text("e2e13-global", exact=True).count()

# Item role with invalid then valid pattern
d = open_add("Item roles")
d.locator("#rsp-role-name").fill("e2e13-team")
res["pattern invalid 'team('"] = pattern_msg(d, "team(", "RS-2-01-pattern-invalid")
res["add enabled with invalid pattern"] = d.get_by_role("button", name="Add").is_enabled()
res["pattern invalid '*bad'"] = pattern_msg(d, "*bad", "RS-2-02-pattern-invalid-2")
res["pattern valid 'team(/.*)?'"] = pattern_msg(d, "team(/.*)?", "RS-2-03-pattern-valid")
res["pattern valid no match 'nomatch-.*'"] = pattern_msg(d, "nomatch-.*", "RS-2-04-pattern-nomatch")
pattern_msg(d, "team(/.*)?", "RS-2-05-pattern-valid-again")
d.locator("[data-permission-id='hudson.model.Item.Read'] label").click()
d.locator("[data-permission-id='hudson.model.Item.Configure'] label").click()
s.shot(d, "RS-2-06-add-item-role")
d.get_by_role("button", name="Add").click()
s.page.wait_for_timeout(1500)
res["item role listed"] = s.page.locator("#main-panel").get_by_text("e2e13-team", exact=True).count()
s.shot("#main-panel", "RS-2-07-item-roles")

# Reload: persisted
s.go("/manage/role-strategy/manage-roles#item")
s.page.wait_for_timeout(800)
res["item role after reload"] = s.page.locator("#main-panel").get_by_text("e2e13-team", exact=True).count()
res["console"] = s.console
res["requests"] = [x for x in reqs if "/descriptor/" in x or "/role-strategy/" in x]
res["bad"] = s.bad
s.done()
close()

res["strategy"] = strategy()
res["server item role"] = groovy("""def s=jenkins.model.Jenkins.get().authorizationStrategy
def m=s.getRoleMap(com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType.Project)
def r=m.getRole('e2e13-team'); return r==null?'none':(r.pattern.pattern()+' '+r.permissions*.id.sort())""")
res["server global role"] = groovy("""def s=jenkins.model.Jenkins.get().authorizationStrategy
def r=s.getRoleMap(com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType.Global).getRole('e2e13-global'); return r==null?'none':r.permissions*.id.sort()""")
for k, v in res.items():
    print(k, "=>", v)
log("manage_roles", res)
lib.h1.report("role/manage_roles", [res])
