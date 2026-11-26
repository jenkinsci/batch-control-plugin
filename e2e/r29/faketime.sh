#!/usr/bin/env bash
# e2e-29, local only (not a CI unit): r29/faketime.py on a fresh Jenkins whose wall clock it can move forward
# (r29/compose.faketime.yml, libfaketime). Proves the pending timeout on the real clock path (T9, R7): nothing in the
# store is edited, the requests simply get old.
#
#   PY=<python with ci/requirements.txt> BC_PORT=18090 BC_MAIL_PORT=18035 r29/faketime.sh [sections]
#
# Needs e2e/.env (ci/run.sh writes one with random passwords) and target/batch-control.hpi. Builds the e2e image and
# its faketime variant, starts the stack as compose project $BC_PROJECT (default bc-r29-ft) with JCasC only (no CI
# setup steps: pendingTimeoutHours stays at casc/jenkins.yaml's 1), waits for it, runs the driver, then removes the
# stack and its volumes (BC_KEEP=1 keeps it). Exit code: the driver's.
set -euo pipefail
E2E="$(cd "$(dirname "$0")/.." && pwd)"
PY="${PY:-python3}"
export BC_PROJECT="${BC_PROJECT:-bc-r29-ft}"
export BC_CONTAINER="$BC_PROJECT" BC_PORT="${BC_PORT:-18090}" BC_MAIL_PORT="${BC_MAIL_PORT:-18035}"
export BC_BASE="http://localhost:$BC_PORT/jenkins"
export BC_FAKETIME_DIR="$E2E/r29/out/faketime"
[ -f "$E2E/.env" ] || { echo "e2e/.env is missing (ci/run.sh writes one; see .env.example)" >&2; exit 2; }
[ -f "$E2E/../target/batch-control.hpi" ] || { echo "target/batch-control.hpi is missing (mvn -ntp clean package -DskipTests)" >&2; exit 2; }
mkdir -p "$BC_FAKETIME_DIR"
echo "+0" > "$BC_FAKETIME_DIR/rc"
files=(-f "$E2E/docker-compose.yml" -f "$E2E/compose.prefix.yml" -f "$E2E/r29/compose.faketime.yml")
compose() { docker compose -p "$BC_PROJECT" --project-directory "$E2E" "${files[@]}" "$@"; }
docker compose -p "$BC_PROJECT" --project-directory "$E2E" -f "$E2E/docker-compose.yml" build --quiet jenkins
docker build -q -t batch-control-e2e-faketime:2.568.3 "$E2E/faketime" > /dev/null
compose down -v --remove-orphans > /dev/null 2>&1 || true
finish() {
  compose logs --no-color --timestamps jenkins > "$E2E/r29/out/faketime-jenkins.log" 2>&1 || true
  if [ "${BC_KEEP:-0}" != 1 ]; then compose down -v --remove-orphans > /dev/null 2>&1 || true; fi
}
trap finish EXIT
compose up -d --no-build
ADMIN_PW="$(sed -n 's/^BC_ADMIN_PASSWORD=//p' "$E2E/.env")"
for _ in $(seq 1 120); do
  if curl -sf -u "admin:$ADMIN_PW" "$BC_BASE/pluginManager/api/json?tree=plugins%5BshortName,active%5D" 2>/dev/null \
      | grep -q '"active":true,"shortName":"batch-control"'; then
    break
  fi
  sleep 3
done
"$PY" "$E2E/r29/faketime.py" "$@"
