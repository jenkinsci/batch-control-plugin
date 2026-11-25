"""e2e-12: 32 more pending run requests (requester, batch-pipeline) so the Run Requests pending table has > 50 rows
and its Newer/Older pager appears."""
from lib import api
ok = 0
for i in range(55):
    r = api("requester", "/job/batch-pipeline/batch-control/submit", "POST", data=[("reason", f"e2e-12 paging {i}"), ("approvers", "approver-2")])
    ok += r.status_code == 302
print("created", ok)
