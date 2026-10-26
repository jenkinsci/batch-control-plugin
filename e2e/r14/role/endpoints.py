"""RS-6/7: the forwarded descriptor endpoints over REST.

R13-6: admin POST to the Batch Control descriptor's checkPattern / checkSidName returns the
byte-identical body of role-strategy's own descriptor for the same input.
R13-7: GET is refused (RequirePOST) for admin and others; non-admin accounts (requester,
approver-1, nobc, anonymous) get 403 on POST; the endpoints dropped by D-35g (checkName,
checkForWhitespace) are gone. A check that Manage/Assign Roles and the role-strategy REST calls
never return 403/404/500 for admin is part of manage_roles.py and assign_roles.py.
H1 (2026-10-24): judged at the end by ci/h1_rules.py (judge_role "endpoints"); a finding prints a "FAIL role ..." line.
"""
import re
import requests
import lib
from lib import api, log, BASE, strategy, groovy

res = {}
BC = "/descriptor/io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy"
RS = "/descriptor/com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy"

same = {}
for v in ["team(", "*bad", "team(/.*)?", "nomatch-.*", "", "^prod-${x}$", "<b>x</b>("]:
    a = api("admin", BC + "/checkPattern", "POST", data={"value": v})
    b = api("admin", RS + "/checkPattern", "POST", data={"value": v})
    same[f"checkPattern {v!r}"] = (a.status_code, a.text == b.text, a.text[:90])
for sid, t in [("nobc", "USER"), (" nobc ", "USER"), ("ghost-user", "USER"), ("some-group", "GROUP"),
               ("authenticated", "GROUP"), ("anonymous", "USER"), ("", "USER"), ("<script>x</script>", "USER"),
               ("nobc", "EITHER"), ("nobc", "")]:
    a = api("admin", BC + "/checkSidName", "POST", data={"value": sid, "type": t})
    b = api("admin", RS + "/checkSidName", "POST", data={"value": sid, "type": t})
    same[f"checkSidName {sid!r} {t}"] = (a.status_code, a.text == b.text, re.sub(r"<svg.*?</svg>", "", a.text)[:160])
res["RS-6 admin forwarded == role-strategy"] = same

deny = {}
for ep in ["checkPattern", "checkSidName"]:
    data = {"value": "nobc", "type": "USER"} if ep == "checkSidName" else {"value": "team("}
    for u in ["admin", "requester", "approver-1", "nobc"]:
        deny[f"{ep} GET {u}"] = api(u, f"{BC}/{ep}?value=x&type=USER").status_code
        if u != "admin":
            r = api(u, f"{BC}/{ep}", "POST", data=data)
            m = re.search(r"([\w-]+ is missing the [^<]*?permission)", r.text)
            deny[f"{ep} POST {u}"] = (r.status_code, m.group(1) if m else "")
    s = requests.Session()
    c = s.get(BASE + "/crumbIssuer/api/json")
    h = {c.json()["crumbRequestField"]: c.json()["crumb"]} if c.status_code == 200 else {}
    deny[f"{ep} POST anonymous"] = s.post(BASE + f"{BC}/{ep}", data=data, headers=h, allow_redirects=False).status_code
    deny[f"{ep} GET anonymous"] = requests.get(BASE + f"{BC}/{ep}?value=x", allow_redirects=False).status_code
for ep in ["checkName", "checkForWhitespace"]:
    deny[f"dropped {ep} POST admin"] = api("admin", f"{BC}/{ep}", "POST", data={"value": "x y"}).status_code
for u in ["requester", "approver-1"]:
    for page in ["/manage/role-strategy/", "/manage/role-strategy/manage-roles"]:
        deny[f"page {page} GET {u}"] = api(u, page).status_code
res["RS-7 refusals"] = deny
res["monitor 'not a Batch Control strategy' on /manage/"] = "not a Batch Control strategy" in api("admin", "/manage/").text
res["strategy"] = strategy()
res["e2e13-team after R13-5 edit"] = groovy("""def s=jenkins.model.Jenkins.get().authorizationStrategy
return s.getRoleMap(com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType.Project).getRole('e2e13-team').permissions*.id.sort()""")
for k, v in res.items():
    if isinstance(v, dict):
        print(k)
        for kk, vv in v.items():
            print("   ", kk, "=>", vv)
    else:
        print(k, "=>", v)
log("endpoints", res)
lib.h1.report("role/endpoints", [res])
