"""RS-3/4: role-strategy 927 Assign Roles under Batch Control: Role-Based Strategy.

As admin in the browser: the Add User or Group dialog's forwarded checkSidName answer for a
padded, an unknown and an existing user and an unknown group; assign nobc to e2e13-global
(Overall/Read) and e2e13-team (item role from manage_roles.py, Job/Read+Configure on team(/.*)?),
unassign the item role, delete the nobc entry. After every step nobc's effective access is read
over REST (basic auth) and the installed strategy class is checked.
H1 (2026-10-24): judged at the end by ci/h1_rules.py (judge_role "assign_roles"); a finding prints a "FAIL role ..." line.
"""
import re
import lib
from lib import Session, close, log, strategy, api, BASE

res = {}
reqs = []


def access(tag):
    res[f"{tag}: nobc GET /"] = api("nobc", "/api/json").status_code
    res[f"{tag}: nobc GET team/app-1"] = api("nobc", "/job/team/job/app-1/api/json").status_code
    res[f"{tag}: nobc GET team/app-1/configure"] = api("nobc", "/job/team/job/app-1/configure").status_code
    res[f"{tag}: strategy"] = strategy()


access("0 before")
s = Session("admin")
s.page.on("response", lambda r: reqs.append(f"{r.status} {r.request.method} {r.url.replace(BASE, '')}")
          if "/static/" not in r.url and "/adjuncts/" not in r.url else None)
res["assign GET"] = s.go("/manage/role-strategy/").status
s.page.wait_for_timeout(800)
s.shot("#main-panel", "RS-3-01-assign-roles")


def tab(name):
    t = s.page.get_by_role("tab", name=name)
    if t.get_attribute("aria-selected") != "true":
        t.click()
        s.page.wait_for_timeout(400)


def add_dialog():
    s.page.get_by_role("button", name="Add User or Group").click()
    d = s.page.locator("dialog[open]").first
    d.wait_for()
    return d


def sid_msg(d, value, tag, kind="User"):
    d.get_by_text(kind, exact=True).click()
    d.locator("#rsp-sid-name").fill(value)
    d.locator("#rsp-sid-name").press("Tab")
    s.page.wait_for_timeout(1500)
    item = d.locator("#rsp-sid-name").locator("xpath=ancestor::div[contains(@class,'jenkins-form-item')][1]")
    s.shot(item, tag)
    html = item.inner_html()
    tips = re.findall(r"tooltip=['\"]([^'\"]+)", html) + re.findall(r"data-html-tooltip=['\"]([^'\"]+)", html)
    return {"text": re.sub(r"\s+", " ", item.inner_text()).strip(), "tooltips": tips}


def row(name):
    return s.page.locator(".rsp-card").filter(has=s.page.locator(f"[data-sid='{name}']")).first


def wait_post():
    s.page.wait_for_timeout(1800)


# --- R13-3: sid check in the Add dialog (Global roles tab) ---
d = add_dialog()
res["sid ' nobc ' (padded)"] = sid_msg(d, " nobc ", "RS-3-02-sid-padded")
res["sid 'ghost-user'"] = sid_msg(d, "ghost-user", "RS-3-03-sid-unknown-user")
res["sid 'some-group' (Group)"] = sid_msg(d, "some-group", "RS-3-04-sid-unknown-group", kind="Group")
res["sid 'nobc'"] = sid_msg(d, "nobc", "RS-3-05-sid-known-user")
d.locator("[data-role-name='e2e13-global'] label").click()
s.shot(d, "RS-3-06-add-nobc-global")
d.get_by_role("button", name="Add").click()
wait_post()
s.shot("#main-panel", "RS-3-07-nobc-assigned-global")
res["row nobc buttons"] = row("nobc").locator("button").evaluate_all("bs => bs.map(b => b.getAttribute('aria-label') || b.title || b.innerText)")
access("1 global assigned")

# --- R13-4: item role assign, unassign (edit + Save), remove entry, delete ---
tab("Item roles")
d = add_dialog()
d.locator("#rsp-sid-name").fill("nobc")
d.locator("#rsp-sid-name").press("Tab")
s.page.wait_for_timeout(800)
d.locator("[data-role-name='e2e13-team'] label").click()
d.locator("[data-role-name='team-all'] label").click()
s.shot(d, "RS-4-01-add-nobc-item")
d.get_by_role("button", name="Add").click()
wait_post()
s.shot("#main-panel", "RS-4-02-nobc-assigned-item")
access("2 item roles e2e13-team+team-all assigned")


def remove_entry(tag):
    row("nobc").locator("button[aria-label='Remove user']").click()
    c = s.page.locator("dialog[open]").first
    c.wait_for()
    res[f"{tag} confirm text"] = re.sub(r"\s+", " ", c.inner_text()).strip()
    s.shot(c, tag)
    c.get_by_role("button", name="Remove").click()
    wait_post()


# unassign one role: edit nobc's item roles, clear e2e13-team, Save
row("nobc").locator("button[aria-label='Edit roles']").click()
d = s.page.locator("dialog[open]").first
d.wait_for()
d.locator("[data-role-name='e2e13-team'] label").click()
s.shot(d, "RS-4-03-unassign-e2e13-team")
d.get_by_role("button", name="Save").click()
wait_post()
s.shot("#main-panel", "RS-4-04-after-unassign")
access("3 e2e13-team unassigned (team-all kept)")

# the edit dialog refuses zero roles; removing the entry is the way to drop the last one
row("nobc").locator("button[aria-label='Edit roles']").click()
d = s.page.locator("dialog[open]").first
d.wait_for()
d.locator("[data-role-name='team-all'] label").click()
res["edit Save enabled with zero roles"] = d.get_by_role("button", name="Save").is_enabled()
s.shot(d, "RS-4-05-zero-roles")
d.locator(".jenkins-dialog__title__close-button").click()
s.page.wait_for_timeout(300)
remove_entry("RS-4-06-remove-item-entry")
access("4 item entry removed")

# delete the global entry
tab("Global roles")
remove_entry("RS-4-07-remove-global-entry")
s.shot("#main-panel", "RS-4-08-after-delete")
res["nobc rows after delete"] = s.page.locator("#main-panel").get_by_text(re.compile(r"\(nobc\)")).count()
access("5 global entry removed")

# reload persisted
s.go("/manage/role-strategy/")
s.page.wait_for_timeout(800)
res["nobc rows after reload"] = s.page.locator("#main-panel").get_by_text(re.compile(r"\(nobc\)")).count()
res["console"] = s.console
res["requests"] = [x for x in reqs if "/descriptor/" in x or "/role-strategy/" in x]
res["bad"] = s.bad
s.done()
close()
for k, v in res.items():
    print(k, "=>", v)
log("assign_roles", res)
lib.h1.report("role/assign_roles", [res])
