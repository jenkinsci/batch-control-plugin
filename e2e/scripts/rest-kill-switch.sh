#!/usr/bin/env bash
# S-15 - the change-control kill switch. Turning change control off revokes every
# open permission window (one GRANT_REVOKE record each), closes the Grants screen
# with a refusal that names the switch, refuses new window requests, and keeps
# the audit trail. Turning it back on does NOT resurrect a revoked window.
#
#   a  requester gets an approved CONFIGURE grant -> configure returns 200
#   b  admin turns change control OFF
#   c  a GRANT_REVOKE change record exists for that grant
#   d  requester's configure now returns 403
#   e  GET /batch-control/grants/ returns the "Change control is off" refusal
#   f  requesting a new grant is refused
#   g  the Change Records screen still shows the earlier grant records
#   h  admin turns change control back ON -> the revoked grant stays revoked
#      (configure is still 403)
#
# The switch is flipped through the script console, the same way
# the removed approvers-clear.sh changed the global configuration: the setter is where the
# revocation lives, so this exercises the behaviour under test. Change control is
# always turned back on at the end, even when a check fails.
#
# Every check prints PASS/FAIL; the script exits non-zero on any FAIL.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

SCOPE="${1:-batch-daily}"

echo "### S-15  change-control kill switch"

bc_login requester
bc_login approver-1
bc_login admin

FAILS=0
check() {   # $1 = label, $2 = expected, $3 = actual
  if [ "$2" = "$3" ]; then echo "PASS  $1 ($3)"; else echo "FAIL  $1: expected '$2', got '$3'"; FAILS=$((FAILS + 1)); fi
}

set_change_control() {   # $1 = true|false -> prints the value read back
  cat > "$OUT_DIR/ks-switch.groovy" <<GROOVY
import jenkins.model.Jenkins
def c = Jenkins.get().pluginManager.uberClassLoader
        .loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration')
        .getMethod('get').invoke(null)
c.setChangeControlEnabled($1)
c.save()
return "changeControl=\${c.isChangeControlEnabled()}"
GROOVY
  bc_script "$OUT_DIR/ks-switch.groovy" | tr -d '[:space:]' | sed 's/^Result://'
}
trap 'set_change_control true > /dev/null || true' EXIT

check "change control ON at start" "changeControl=true" "$(set_change_control true)"

# Clear any window left open by another scenario, so (d)/(h) are about this grant.
bc_get admin "$OUT_DIR/ks-active-before.html" "/batch-control/grants/" > /dev/null
for active in $(grep -o 'active/[0-9]\{8\}-[0-9]\{6\}-[a-z0-9]\{6\}/revoke' \
        "$OUT_DIR/ks-active-before.html" | sort -u); do
  bc_post admin "$OUT_DIR/ks-revoke.html" "/batch-control/grants/$active" > /dev/null
done
check "baseline: requester GET /job/$SCOPE/configure" 403 \
  "$(bc_get requester "$OUT_DIR/ks-configure-0.html" "/job/$SCOPE/configure")"

# --- a. an approved CONFIGURE grant
request_grant() {   # $1 = body file -> HTTP status
  bc_post requester "$1" "/batch-control/grants/create" \
    --data-urlencode "scopeFullName=$SCOPE" \
    --data-urlencode "actions=CONFIGURE" \
    --data-urlencode "durationMinutes=15" \
    --data-urlencode "reason=S-15 kill-switch scenario on $SCOPE" \
    --data-urlencode "approvers=approver-1"
}
check "(a) requester POST /batch-control/grants/create" 302 "$(request_grant "$OUT_DIR/ks-create.html")"
bc_get requester "$OUT_DIR/ks-list.html" "/batch-control/grants/" > /dev/null
GRANT_ID=$(grep -o 'href="[0-9]\{8\}-[0-9]\{6\}-[a-z0-9]\{6\}/"' "$OUT_DIR/ks-list.html" \
        | sed -e 's#href="##' -e 's#/"##' | sort | tail -1 || true)
echo "grant request id = $GRANT_ID"
check "(a) approver approves $GRANT_ID" 302 \
  "$(bc_post approver-1 "$OUT_DIR/ks-approve.html" "/batch-control/grants/$GRANT_ID/approve" \
      --data-urlencode "comment=S-15 approved")"
check "(a) requester GET /job/$SCOPE/configure inside the window" 200 \
  "$(bc_get requester "$OUT_DIR/ks-configure-a.html" "/job/$SCOPE/configure")"

# --- b. the kill switch
check "(b) admin turns change control OFF" "changeControl=false" "$(set_change_control false)"

# --- c. the revocation is recorded
bc_get admin "$OUT_DIR/ks-changes.csv" "/batch-control/history/changes.csv" > /dev/null
check "(c) GRANT_REVOKE record for $GRANT_ID" yes \
  "$(grep -q ",GRANT_REVOKE,.*,$GRANT_ID," "$OUT_DIR/ks-changes.csv" && echo yes || echo no)"

# --- d. the window confers nothing any more
check "(d) requester GET /job/$SCOPE/configure after switch-off" 403 \
  "$(bc_get requester "$OUT_DIR/ks-configure-d.html" "/job/$SCOPE/configure")"

# --- e. the Grants screen is closed with a refusal that names the switch
bc_get requester "$OUT_DIR/ks-grants-off.html" "/batch-control/grants/" > /dev/null || true
check "(e) GET /batch-control/grants/ carries 'Change control is off'" yes \
  "$(grep -q 'Change control is off' "$OUT_DIR/ks-grants-off.html" && echo yes || echo no)"

# --- f. no new window can be requested
status=$(request_grant "$OUT_DIR/ks-create-off.html")
check "(f) new grant request refused (HTTP $status, not a 302 redirect)" yes \
  "$([ "$status" != 302 ] && [ "$status" != 200 ] && echo yes || echo no)"
check "(f) refusal says change control is off" yes \
  "$(grep -q 'Change control is off' "$OUT_DIR/ks-create-off.html" && echo yes || echo no)"

# --- g. the audit trail survives
check "(g) GET /batch-control/changes/ (admin)" 200 \
  "$(bc_get admin "$OUT_DIR/ks-changes-screen.html" "/batch-control/changes/")"
check "(g) Change Records screen still shows $GRANT_ID" yes \
  "$(grep -q "$GRANT_ID" "$OUT_DIR/ks-changes-screen.html" && echo yes || echo no)"

# --- h. switching back on does not resurrect the window
check "(h) admin turns change control back ON" "changeControl=true" "$(set_change_control true)"
check "(h) requester GET /job/$SCOPE/configure after switch-on" 403 \
  "$(bc_get requester "$OUT_DIR/ks-configure-h.html" "/job/$SCOPE/configure")"

echo "### S-15: $FAILS failure(s)"
[ "$FAILS" -eq 0 ]
