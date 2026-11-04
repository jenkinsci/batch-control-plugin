#!/usr/bin/env bash
# CI e2e runner: one shard of the scripted e2e pass on a fresh Jenkins with JaCoCo coverage.
#
#   e2e/ci/run.sh <k>/<N>          e.g. e2e/ci/run.sh 2/7
#
# Shard k is group k of ci/shard.py GROUPS (`ci/shard.py check` lists them); N must be the number of groups, checked
# before anything starts (exit 64 otherwise).
# 1. uses target/batch-control.hpi (builds it with `mvn -ntp -q clean package -DskipTests` only when it is missing
#    and BC_BUILD is not "never"); records its sha256;
# 2. writes e2e/.env with random passwords when it does not exist (CI: never commit one);
# 3. fetches the pinned, sha256-verified JaCoCo agent (ci/fetch-jacoco.sh);
# 4. starts docker-compose.yml + compose.prefix.yml + compose.coverage.yml (+ $BC_COMPOSE_EXTRA) under its own compose
#    project (default bc-cov-<k>) on BC_PORT/BC_MAIL_PORT (default 18080/18025), from an empty JENKINS_HOME;
# 5. waits for Jenkins (login page, admin API, plugin active);
# 6. runs the shard's steps through pytest (ci/test_shard.py: one test per step, JUnit XML in junit.xml, Playwright
#    traces of a failed step in traces/, one login per account and shard; BC_RUNNER=legacy: ci/shard.py run <k>/<N>);
# 7. always (also on failure): dumps the coverage through the script console, copies that snapshot, stops the stack
#    gracefully (JaCoCo writes on JVM exit), copies the final exec, the container log, the driver logs and the
#    screenshots to e2e/ci/out/<k>/, then removes the containers and the volume (BC_KEEP=1 keeps them);
# 8. exits 1 when a step failed (ci/shard.py verdicts), 2 when the stack could not be started, else 0.
#
# Environment: PY (python with ci/requirements.txt, default python3), BC_PORT, BC_MAIL_PORT, BC_PROJECT,
# BC_BROWSER_CHANNEL (chrome | chromium, default chromium here), BC_COMPOSE_EXTRA (space-separated extra -f files),
# BC_UNITS (run only these units), BC_KEEP=1, BC_BUILD=never, BC_OUT (output directory), TZ (default Asia/Seoul, the
# zone docker-compose.yml gives the JVM), BC_RUNNER (pytest | legacy), BC_TRACE=off (no Playwright traces),
# BC_LOGIN_REUSE=0 (log in for every browser context), BC_FLAKY (extra steps to retry once, see ci/shard.py FLAKY).
set -euo pipefail

SHARD="${1:-}"
if ! [[ "$SHARD" =~ ^([0-9]+)/([0-9]+)$ ]] || [ "${BASH_REMATCH[1]}" -lt 1 ] || [ "${BASH_REMATCH[1]}" -gt "${BASH_REMATCH[2]}" ]; then
  echo "usage: $0 <k>/<N>   (1 <= k <= N)" >&2
  exit 64
fi
K="${BASH_REMATCH[1]}"
N="${BASH_REMATCH[2]}"

E2E="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$E2E/.." && pwd)"
OUT="${BC_OUT:-$E2E/ci/out/$K}"
PY="${PY:-python3}"
export PY
# The group of shard K (and a clear error when N is not the number of groups), before Docker is touched.
if ! LABEL="$("$PY" "$E2E/ci/shard.py" label "$K/$N")"; then
  exit 64
fi
export TZ="${TZ:-Asia/Seoul}"
export BC_PORT="${BC_PORT:-18080}"
export BC_MAIL_PORT="${BC_MAIL_PORT:-18025}"
export BC_SHARD="$K"
export BC_PROJECT="${BC_PROJECT:-bc-cov-$K}"
export BC_CONTAINER="$BC_PROJECT"
export BC_BASE="http://localhost:$BC_PORT/jenkins"
export BC_BROWSER_CHANNEL="${BC_BROWSER_CHANNEL:-chromium}"
JENKINS_CONTAINER="$BC_CONTAINER-jenkins"
EXEC_IN_CONTAINER="/var/jenkins_home/jacoco/jacoco-$K.exec"

log() { echo "[$(date +%H:%M:%S)] run.sh $K/$N ($LABEL): $*"; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

compose_files=(-f "$E2E/docker-compose.yml" -f "$E2E/compose.prefix.yml" -f "$E2E/compose.coverage.yml")
for f in ${BC_COMPOSE_EXTRA:-}; do compose_files+=(-f "$f"); done
compose() { docker compose -p "$BC_PROJECT" --project-directory "$E2E" "${compose_files[@]}" "$@"; }

rm -rf "$OUT"
mkdir -p "$OUT"
T0=$(date +%s)

# ---------------------------------------------------------------- 1. artifact
HPI="$ROOT/target/batch-control.hpi"
if [ ! -f "$HPI" ]; then
  if [ "${BC_BUILD:-auto}" = never ] || ! command -v mvn >/dev/null; then
    echo "missing $HPI: build it first (mvn -ntp clean package -DskipTests)" >&2
    exit 2
  fi
  log "building $HPI"
  (cd "$ROOT" && mvn -ntp -q clean package -DskipTests)
fi
{
  echo "hpi_sha256=$(sha256 "$HPI")"
  echo "git_head=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "shard=$K/$N project=$BC_PROJECT port=$BC_PORT browser=$BC_BROWSER_CHANNEL tz=$TZ"
  echo "label=$LABEL"
} > "$OUT/build-info.txt"

# ---------------------------------------------------------------- 2. .env (passwords only; never committed)
if [ ! -f "$E2E/.env" ]; then
  log "writing e2e/.env with random passwords"
  gen() { "$PY" -c 'import secrets; print(secrets.token_urlsafe(18))'; }
  umask 077
  {
    echo "BC_ADMIN_PASSWORD=$(gen)"
    echo "BC_APPROVER_PASSWORD=$(gen)"
    echo "BC_REQUESTER_PASSWORD=$(gen)"
    echo "BC_OTHER_PASSWORD=$(gen)"
  } > "$E2E/.env"
  umask 022
fi
if [ "${GITHUB_ACTIONS:-}" = true ]; then
  while IFS='=' read -r k v; do case "$k" in BC_*PASSWORD) echo "::add-mask::$v" ;; esac; done < "$E2E/.env"
fi
ADMIN_PW="$(sed -n 's/^BC_ADMIN_PASSWORD=//p' "$E2E/.env")"

# ---------------------------------------------------------------- 3. JaCoCo agent
"$E2E/ci/fetch-jacoco.sh"

# Transient driver output from an earlier run is moved aside, so the shard's artefacts are its own.
DRIVER_OUT=(r14/out r15/out r16/out r17/out r18/out r19/out r21/out r22/out r12/out r7/out r8/out screenshots)
DRIVER_OUT+=(r25/out)  # e2e-25 (Wave C-UI), on its own line
DRIVER_OUT+=(r27/out)  # e2e-27 (Wave D), on its own line
prev="$E2E/ci/out/_previous/$(date +%Y%m%d-%H%M%S)-$K"
DRIVER_OUT+=(r23/out)  # e2e-23 (bug-hunt batch A), on its own line
DRIVER_OUT+=(r24/out)  # e2e-24 (wave A), on its own line
DRIVER_OUT+=(r26/out)  # e2e-26 (wave B: #36, #34, #32, #33), on its own line
DRIVER_OUT+=(h3/out)  # gap H3 (upgrade from the released plugin), on its own line
for d in "${DRIVER_OUT[@]}"; do
  if [ -d "$E2E/$d" ] && [ -n "$(ls -A "$E2E/$d" 2>/dev/null)" ]; then mkdir -p "$prev/$(dirname "$d")"; mv "$E2E/$d" "$prev/$d"; fi
done

script() { # groovy through the script console as admin (coverage dump only); the crumb is bound to the session
  local crumb jar
  jar="$(mktemp)"
  crumb=$(curl -s -c "$jar" -b "$jar" -u "admin:$ADMIN_PW" "$BC_BASE/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\":\",//crumb)" || true)
  curl -s -c "$jar" -b "$jar" -u "admin:$ADMIN_PW" ${crumb:+-H "$crumb"} --data-urlencode "script=$1" "$BC_BASE/scriptText"
  rm -f "$jar"
}

STACK_UP=0
finish() {
  local rc=$?
  set +e
  if [ "$STACK_UP" = 1 ]; then
    log "coverage: dump through the script console, then a graceful stop"
    script "def rt = Class.forName('org.jacoco.agent.rt.RT', true, ClassLoader.getSystemClassLoader())
def a = rt.getMethod('getAgent').invoke(null); a.dump(false); return 'dumped session ' + a.getSessionId()" > "$OUT/coverage-dump.txt" 2>&1
    cat "$OUT/coverage-dump.txt"; echo
    docker cp "$JENKINS_CONTAINER:$EXEC_IN_CONTAINER" "$OUT/snapshot-before-stop.exec" 2>/dev/null
    compose logs --no-color --timestamps jenkins > "$OUT/jenkins.log" 2>&1
    local t_stop=$(date +%s)
    compose stop -t 180 jenkins
    echo "stop_seconds=$(( $(date +%s) - t_stop ))" >> "$OUT/build-info.txt"
    docker cp "$JENKINS_CONTAINER:$EXEC_IN_CONTAINER" "$OUT/jacoco-$K.exec" || log "WARNING: no exec file in the container"
    compose logs --no-color --timestamps jenkins > "$OUT/jenkins.log" 2>&1
    echo "jenkins_exit=$(docker inspect -f '{{.State.ExitCode}} oom={{.State.OOMKilled}}' "$JENKINS_CONTAINER" 2>/dev/null)" >> "$OUT/build-info.txt"
    echo "severe_lines=$(grep -c 'SEVERE' "$OUT/jenkins.log")" >> "$OUT/build-info.txt"
    if [ "${BC_KEEP:-0}" != 1 ]; then compose down -v --remove-orphans >/dev/null 2>&1; fi
  fi
  # gap H3: the upgrade unit's own controller (h3/upgrade.py removes it itself; this covers a step killed by its timeout)
  if [ "${BC_KEEP:-0}" != 1 ] && [ -n "$(docker ps -aq --filter "label=com.docker.compose.project=bc-h3-$K" 2>/dev/null)" ]; then
    BC_H3_HPI=/dev/null docker compose -p "bc-h3-$K" --env-file "$E2E/.env" -f "$E2E/h3/compose.yml" down -v --remove-orphans >/dev/null 2>&1
  fi
  for d in "${DRIVER_OUT[@]}"; do
    if [ -d "$E2E/$d" ]; then mkdir -p "$OUT/driver/$(dirname "$d")"; mv "$E2E/$d" "$OUT/driver/$d"; fi
  done
  echo "wall_seconds=$(( $(date +%s) - T0 ))" >> "$OUT/build-info.txt"
  log "done (exit $rc), artefacts in $OUT"
  exit $rc
}
trap finish EXIT

# ---------------------------------------------------------------- 4./5. stack
log "starting $BC_PROJECT on port $BC_PORT"
compose down -v --remove-orphans >/dev/null 2>&1 || true
STACK_UP=1
if ! compose up -d --build --quiet-pull > "$OUT/compose-up.log" 2>&1; then
  tail -n 30 "$OUT/compose-up.log"
  log "docker compose up failed"
  exit 2
fi
plugin_active() {
  curl -sf -u "admin:$ADMIN_PW" "$BC_BASE/pluginManager/api/json?tree=plugins%5BshortName,active%5D" | "$PY" -c '
import json, sys
sys.exit(0 if any(p.get("shortName") == "batch-control" and p.get("active") for p in json.load(sys.stdin)["plugins"]) else 1)'
}
ready=0
for _ in $(seq 1 120); do
  if curl -sf -o /dev/null "$BC_BASE/login" && plugin_active 2>/dev/null; then
    ready=1
    break
  fi
  sleep 3
done
if [ "$ready" != 1 ]; then
  log "Jenkins did not become ready (login page, admin API, batch-control active)"
  exit 2
fi
echo "ready_seconds=$(( $(date +%s) - T0 ))" >> "$OUT/build-info.txt"
log "Jenkins ready after $(( $(date +%s) - T0 ))s"

# ---------------------------------------------------------------- 6. steps
# pytest (ci/test_shard.py, one test per step, JUnit XML in junit.xml); BC_RUNNER=legacy runs `shard.py run` instead.
# Both judge the steps with the same code (ci/shard.py Runner) and write the same summary.json/summary.md.
set +e
if [ "${BC_RUNNER:-pytest}" = legacy ]; then
  "$PY" "$E2E/ci/shard.py" run "$K/$N" --out "$OUT"
  RC=$?
else
  "$PY" -m pytest "$E2E/ci/test_shard.py" -s -p no:playwright --bc-shard "$K/$N" --bc-out "$OUT" \
    --junitxml "$OUT/junit.xml" -o junit_suite_name="e2e shard $K of $N ($LABEL)"
  RC=$?
  [ "$RC" -le 1 ] || log "pytest exited $RC (not a step verdict: usage or internal error)"
  [ "$RC" = 0 ] || RC=1
fi
set -e
exit $RC
