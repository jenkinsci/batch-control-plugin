"""RS-1/2: role-strategy 927 Manage Roles under Batch Control: Role-Based Strategy.

Arranged by casc/profile-role.yaml (applied by setup.py). As admin in the browser: open Manage
Roles, add a global role and an item role through the Add Role dialog, typing an invalid and a
valid pattern first so the forwarded checkPattern answer is shown. Records every request the
page makes (status >= 400 flagged) and the console. Server state is read separately.
H1 (2026-10-24): judged at the end by ci/h1_rules.py (judge_role "manage_roles"); a finding prints a "FAIL role ..." line.

#31 (the retired r6/s2b.py and r6/s2d.py), judged as judge_role "manage_edit": an existing item role (e2e31-prod, pattern
prod/.*, Job/Read; arranged through role-strategy's own addRole endpoint) is edited in the browser through its card's
Edit role dialog to the pattern prod(/.*)?; after a reload the card and the server hold the new pattern with the
permissions kept (Save is read once the forwarded checkPattern has answered for the new pattern). The card's pattern
button opens the "Items matching" dialog, which must list exactly the items the server matches for the pattern the
card shows (read through the script console, independently of role-strategy). Delete role asks for confirmation:
Cancel keeps the role, OK removes it. On Permission Templates the Add Template dialog creates
e2e31-tpl (Job/Read, Job/Workspace) and its card's Delete template, confirmed, removes it. The installed strategy is
read after every step and must stay the Batch Control role class.
"""
import json
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

# ---------------------------------------------------------------- #31: edit, pattern check, delete; permission templates
EDIT_ROLE, OLD_PATTERN, NEW_PATTERN = "e2e31-prod", "prod/.*", "prod(/.*)?"
TEMPLATE, TEMPLATE_PERMS = "e2e31-tpl", ["hudson.model.Item.Read", "hudson.model.Item.Workspace"]
ed = {}
reqs2 = []
until, opened, gone = lib._l.until, lib._l.opened, lib._l.gone


def server_role(name):
    return groovy(f"""def s=jenkins.model.Jenkins.get().authorizationStrategy
def r=s.getRoleMap(com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType.Project).getRole('{name}')
return r==null?'none':(r.pattern.pattern()+' '+r.permissions*.id.sort())""").replace("Result: ", "")


def server_template(name):
    return groovy(f"""def t=jenkins.model.Jenkins.get().authorizationStrategy.permissionTemplates.find{{ it.name == '{name}' }}
return t==null?'none':t.permissions*.id.sort().toString()""").replace("Result: ", "")


def server_matching(pattern):
    """The items whose full name the pattern matches in full, by their full display names (the dialog's wording)."""
    out = groovy("def p = java.util.regex.Pattern.compile(%s)\nreturn groovy.json.JsonOutput.toJson(jenkins.model.Jenkins.get()"
                 ".getAllItems(hudson.model.Item).findAll{ p.matcher(it.fullName).matches() }.collect{ it.fullDisplayName }.sort())"
                 % repr(pattern).replace("'", '"'))
    try:
        return json.loads(out.replace("Result: ", ""))
    except ValueError:
        return out[:200]


def card(page, name):
    tab = page.get_by_role("tab", name="Item roles")
    if tab.get_attribute("aria-selected") != "true":
        tab.click()
    return page.locator(".rsp-card").filter(has=page.locator(".rsp-card__name", has_text=re.compile("^" + re.escape(name) + "$")))


def dialog_shown(page, timeout=5000):
    """A dialog is open (the pattern check's dialog has only its Close button, which lib's opened() does not wait for)."""
    try:
        page.locator("dialog[open]").first.wait_for(state="visible", timeout=timeout)
        return True
    except Exception:  # noqa: no dialog within the timeout
        return False


def dialog_title(page):
    return re.sub(r"\s+", " ", page.locator("dialog[open] .jenkins-dialog__title").first.inner_text()).strip()


def track(sess):
    sess.page.on("response", lambda r: reqs2.append(f"{r.status} {r.request.method} {r.url.replace(BASE, '')}")
                 if ("/role-strategy/" in r.url or "/descriptor/" in r.url) and "/static/" not in r.url else None)


try:
    r = lib.api("admin", "/role-strategy/strategy/addRole", "POST", data={
        "type": "projectRoles", "roleName": EDIT_ROLE, "permissionIds": "hudson.model.Item.Read", "overwrite": "true",
        "pattern": OLD_PATTERN})
    ed["arrange addRole"] = r.status_code
    ed["server role before"] = server_role(EDIT_ROLE)

    # r6/s2b.py: the edit dialog of an existing item role; the card's pattern check after the reload
    s = Session("admin")
    track(s)
    s.go("/manage/role-strategy/manage-roles")
    c = card(s.page, EDIT_ROLE)
    ed["card before"] = [c.count(), c.locator(".rsp-card__pattern").first.inner_text().strip() if c.count() else None]
    c.first.locator("button[aria-label='Edit role']").click()
    ed["edit dialog"] = dialog_title(s.page) if opened(s.page) else None
    d = s.page.locator("dialog[open]").first
    # Save keeps the state of the old (valid) pattern until the forwarded checkPattern answers for the new one: wait
    # for that answer's body, then for two frames so the dialog has rendered its verdict
    with s.page.expect_response(lambda r: r.url.endswith("/checkPattern") and r.request.method == "POST") as chk:
        d.locator("#rsp-role-pattern").fill(NEW_PATTERN)
        d.locator("#rsp-role-pattern").press("Tab")
    chk.value.text()  # the body has arrived
    s.page.evaluate("() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))")
    save = d.get_by_role("button", name="Save")
    ed["save enabled"] = save.is_enabled()
    item = d.locator("#rsp-role-pattern").locator("xpath=ancestor::div[contains(@class,'jenkins-form-item')][1]")
    ed["pattern field"] = re.sub(r"\s+", " ", item.inner_text()).strip()[:200]
    s.shot(d, "RS-31-01-edit-item-role")
    if ed["save enabled"]:
        save.click()
        ed["edit closed"] = gone(s.page, timeout=5000)
    else:
        s.page.keyboard.press("Escape")
        gone(s.page)
    s.go("/manage/role-strategy/manage-roles")
    c = card(s.page, EDIT_ROLE)
    ed["card after reload"] = [c.count(), c.locator(".rsp-card__pattern").first.inner_text().strip() if c.count() else None]
    ed["server role after edit"] = server_role(EDIT_ROLE)
    ed["strategy after edit"] = strategy()
    # the pattern check is judged against the pattern the card shows (independent of whether the edit took)
    ed["server matching"] = server_matching(ed["card after reload"][1]) if c.count() else None
    if c.count():
        c.first.locator(".rsp-card__pattern--button").click()
        pd = {"opened": dialog_shown(s.page)}
        if pd["opened"]:
            items = s.page.locator("dialog[open] .rsp-matching-list li")
            until(s.page, lambda: items.count() > 0, 5000)
            pd["title"] = dialog_title(s.page)
            pd["items"] = sorted(re.sub(r"\s+", " ", x).strip() for x in items.all_inner_texts())
            s.shot("dialog[open]", "RS-31-02-pattern-check")
            s.page.keyboard.press("Escape")
            pd["closed"] = gone(s.page)
        ed["pattern dialog"] = pd

        # r6/s2d.py: Delete role with confirmation (Cancel keeps it, OK removes it)
        c.first.locator("button[aria-label='Delete role']").click()
        dd = ed["delete dialog"] = {"opened": opened(s.page)}
        if dd["opened"]:
            dd["title"] = dialog_title(s.page)
            s.page.locator("dialog[open] button[data-id=cancel]").click()
            dd["closed on cancel"] = gone(s.page)
        dd["listed after cancel"] = card(s.page, EDIT_ROLE).count()
        dd["server after cancel"] = server_role(EDIT_ROLE)
        c.first.locator("button[aria-label='Delete role']").click()
        if opened(s.page):
            s.shot("dialog[open]", "RS-31-03-delete-role-confirm")
            s.page.locator("dialog[open] button[data-id=ok]").click()
            dd["closed on ok"] = gone(s.page, timeout=5000)
    s.go("/manage/role-strategy/manage-roles")
    ed["listed after delete"] = card(s.page, EDIT_ROLE).count()
    ed["server role after delete"] = server_role(EDIT_ROLE)
    ed["strategy after delete"] = strategy()
    ed["console"] = list(s.console)
    ed["bad"] = list(s.bad)
    s.done()

    # r6/s2d.py: Permission Templates, Add Template dialog and the card's Delete template
    s = Session("admin")
    track(s)
    s.go("/manage/role-strategy/permission-templates")
    s.page.locator("#rsp-add-template-btn").click()
    ta = {"opened": opened(s.page)}
    if ta["opened"]:
        ta["title"] = dialog_title(s.page)
        d = s.page.locator("dialog[open]").first
        d.locator("#rsp-template-name").fill(TEMPLATE)
        for pid in TEMPLATE_PERMS:
            d.locator(f"[data-permission-id='{pid}'] label").click()
        s.shot(d, "RS-31-04-add-template")
        d.get_by_role("button", name="Add").click()
        ta["closed"] = gone(s.page, timeout=5000)
    ed["template add dialog"] = ta
    s.go("/manage/role-strategy/permission-templates")
    tcard = s.page.locator(".rsp-card").filter(has_text=TEMPLATE)
    ed["template listed after reload"] = tcard.count()
    ed["server template after add"] = server_template(TEMPLATE)
    ed["strategy after template add"] = strategy()
    td = {}
    if tcard.count():
        tcard.first.locator("button[aria-label='Delete template']").click()
        td["opened"] = opened(s.page)
        if td["opened"]:
            td["title"] = dialog_title(s.page)
            s.shot("dialog[open]", "RS-31-05-delete-template-confirm")
            s.page.locator("dialog[open] button[data-id=ok]").click()
            td["closed"] = gone(s.page, timeout=5000)
    ed["template delete dialog"] = td
    s.go("/manage/role-strategy/permission-templates")
    ed["template listed after delete"] = s.page.locator(".rsp-card").filter(has_text=TEMPLATE).count()
    ed["server template after delete"] = server_template(TEMPLATE)
    ed["strategy after template delete"] = strategy()
    ed["template console"] = list(s.console)
    ed["template bad"] = list(s.bad)
    s.done()
except Exception as e:  # noqa: recorded; judge_role "manage_edit" fails the steps that were not measured
    ed["error"] = repr(e)[:1500]
close()
ed["requests"] = reqs2
for k, v in ed.items():
    print(k, "=>", v)
log("manage_edit", ed)
lib.h1.report("role/manage_edit", [ed])

# ---------------------------------------------------------------- #31: the Batch Control permissions in Add Role
# (the retired B/b8-role.mjs #1): the permissions each Add Role dialog offers (global and item roles), read in the same
# dialogs as core's; then an item role saved through the dialog with BatchControl/Request (scope ITEM, D-38a) and Job/Read
# keeps both on the server; removed again over role-strategy's own endpoint. Judged as judge_role "manage_perms".
PERM_ROLE, PERM_PATTERN = lib.h1.RS_PERMS_ROLE, lib.h1.RS_PERMS_PATTERN
pm = {}
reqs3 = []
t0 = __import__("time").time()
try:
    s = Session("admin")
    s.page.on("response", lambda r: reqs3.append(f"{r.status} {r.request.method} {r.url.replace(BASE, '')}")
              if ("/role-strategy/" in r.url or "/descriptor/" in r.url) and "/static/" not in r.url else None)
    s.go("/manage/role-strategy/manage-roles")
    for tab, key in (("Global roles", "global"), ("Item roles", "item")):
        d = open_add(tab)
        pm[f"{key} dialog"] = dialog_title(s.page)
        pm[f"{key} offered"] = sorted(set(d.locator("[data-permission-id]").evaluate_all(
            "els => els.map(e => e.getAttribute('data-permission-id'))")))
        if key == "global":
            s.page.keyboard.press("Escape")
            pm["global closed"] = gone(s.page)
            continue
        d.locator("#rsp-role-name").fill(PERM_ROLE)
        with s.page.expect_response(lambda r: r.url.endswith("/checkPattern") and r.request.method == "POST") as chk:
            d.locator("#rsp-role-pattern").fill(PERM_PATTERN)
            d.locator("#rsp-role-pattern").press("Tab")
        chk.value.text()
        s.page.evaluate("() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))")
        for pid in lib.h1.RS_PERMS_SAVED:
            d.locator(f"[data-permission-id='{pid}'] label").click()
        pm["ticked"] = sorted(d.locator("[data-permission-id] input:checked").evaluate_all(
            "els => els.map(e => e.closest('[data-permission-id]').getAttribute('data-permission-id'))"))
        s.shot(d, "RS-31-06-add-item-role-batch-control-request")
        d.get_by_role("button", name="Add").click()
        pm["item closed"] = gone(s.page, timeout=5000)
    s.go("/manage/role-strategy/manage-roles")
    pm["listed after reload"] = card(s.page, PERM_ROLE).count()
    pm["server role"] = server_role(PERM_ROLE)
    pm["strategy"] = strategy()
    pm["console"] = list(s.console)
    pm["bad"] = list(s.bad)
    s.done()
except Exception as e:  # noqa: recorded; judge_role "manage_perms" fails what was not measured
    pm["error"] = repr(e)[:1500]
close()
pm["remove"] = lib.api("admin", "/role-strategy/strategy/removeRoles", "POST",
                       data={"type": "projectRoles", "roleNames": PERM_ROLE}).status_code
pm["server role after remove"] = server_role(PERM_ROLE)
pm["requests"] = reqs3
pm["seconds"] = round(__import__("time").time() - t0, 1)
for k, v in pm.items():
    print(k, "=>", v)
log("manage_perms", pm)
lib.h1.report("role/manage_perms", [pm])
