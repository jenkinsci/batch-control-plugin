"""#31 (the retired B/b21.mjs #1b, BT25): Deploy Plugin with the built .hpi on a clean controller installs Batch Control
and resolves its required plugins (README "Requirements" and "Installation": "resolved for you on installation", "upload
a build under Advanced settings -> Deploy Plugin").

usage: python h3/deploy.py      rows: h3/out/deploy.json; verdicts: ci/h1_rules.py judge "deploy" (FAIL lines, exit 1)

A controller of its own, next to the shard's (nothing shared, so no `last`): the core of the e2e image
(batch-control-e2e-jenkins:2.568.3, the core of pom.xml) started straight from its jenkins.war with an empty
JENKINS_HOME, so none of the image's baked plugins (plugins.txt) is copied in: a clean LTS controller without any
plugin, without security and without the setup wizard, as `java -jar jenkins.war` gives it. On it:
  1. before: the plugin list over REST, and /batch-control/ (the guard: Batch Control and its three required plugins are
     not there yet);
  2. Manage Jenkins -> Plugins -> Check now (POST pluginManager/checkUpdatesServer with a crumb): the update center's
     metadata is downloaded, which dependency resolution needs (retried on failure);
  3. Deploy Plugin: the form's POST pluginManager/uploadPlugin with target/batch-control.hpi (BC_H3_DEPLOY_HPI) as the
     file field "name" and an empty "pluginUrl", as the Advanced settings page sends it; the update center's installation
     jobs are read until none is pending or installing;
  4. a restart (POST safeRestart) and the plugin list again: Batch Control at the build's version, active, its required
     plugins (cloudbees-folder, ionicons-api, caffeine-api) installed and active, no failed plugin, /batch-control/ 200.
Needs the update center (updates.jenkins.io and its download mirrors), as h3/upgrade.py does for the released .hpi.
The container is removed at the end (BC_KEEP=1 keeps it). BC_H3_DEPLOY_HPI points at another .hpi (proof hook only: a
doctored manifest shows the checks go red; never in CI)."""
import json
import os
import pathlib
import re
import subprocess
import sys
import time
import zipfile

import importlib.util
import requests

HERE = pathlib.Path(__file__).resolve().parent
E2E = HERE.parent
ROOT = E2E.parent
OUT = HERE / "out"
OUT.mkdir(parents=True, exist_ok=True)
_spec = importlib.util.spec_from_file_location("h1_rules", E2E / "ci" / "h1_rules.py")
h1 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(h1)

IMAGE = os.environ.get("BC_H3_DEPLOY_IMAGE") or "batch-control-e2e-jenkins:2.568.3"  # docker-compose.yml builds it
PORT = int(os.environ.get("BC_H3_DEPLOY_PORT") or int(os.environ.get("BC_PORT", "18080")) + 200)
CONTAINER = os.environ.get("BC_H3_DEPLOY_CONTAINER") or f"bc-h3-deploy-{os.environ.get('BC_SHARD', 'local')}"
BASE = f"http://localhost:{PORT}"
PLUGINS = "/pluginManager/api/json?tree=plugins%5BshortName,version,active,enabled%5D"
FAILED = ("return groovy.json.JsonOutput.toJson(jenkins.model.Jenkins.get().pluginManager.failedPlugins"
          ".collect { it.name + ': ' + it.cause })")


def poll(fn, timeout, step=2.0):
    t0 = time.time()
    while True:
        v = fn()
        if v or time.time() - t0 >= timeout:
            return v
        time.sleep(step)


def hpi_version(path):
    with zipfile.ZipFile(path) as z:
        text = re.sub(r"\r?\n ", "", z.read("META-INF/MANIFEST.MF").decode("utf-8"))
    m = re.search(r"^Plugin-Version: (.+?)\r?$", text, re.M)
    return m.group(1).strip() if m else None


def docker(*args, timeout=300):
    return subprocess.run(["docker", *args], capture_output=True, text=True, timeout=timeout)


class Anon:
    """The controller has no security: anonymous holds Overall/Administer; POSTs need the crumb of the session."""

    def __init__(self):
        self.s = requests.Session()
        self.crumb = None

    def get(self, path, **kw):
        return self.s.get(BASE + path, timeout=60, **kw)

    def post(self, path, **kw):
        if self.crumb is None:
            c = self.s.get(BASE + "/crumbIssuer/api/json", timeout=30)
            self.crumb = (c.json()["crumbRequestField"], c.json()["crumb"]) if c.status_code == 200 else ()
        headers = kw.pop("headers", {})
        if self.crumb:
            headers[self.crumb[0]] = self.crumb[1]
        return self.s.post(BASE + path, headers=headers, allow_redirects=False, timeout=300, **kw)


def plugins(a):
    try:
        r = a.get(PLUGINS)
        return {p["shortName"]: {k: p.get(k) for k in ("version", "active", "enabled")} for p in r.json()["plugins"]} \
            if r.status_code == 200 else None
    except (requests.RequestException, ValueError):
        return None


def up(a):
    try:
        return a.get("/api/json?tree=mode").status_code == 200
    except requests.RequestException:
        return False


def groovy(a, script):
    r = a.post("/scriptText", data={"script": script})
    return r.text.strip()[len("Result:"):].strip() if r.text.strip().startswith("Result:") else r.text.strip()[:300]


def main(row):
    hpi = pathlib.Path(os.environ.get("BC_H3_DEPLOY_HPI") or ROOT / "target" / "batch-control.hpi").resolve()
    row["hpi"], row["hpi_version"] = str(hpi), hpi_version(hpi)
    docker("rm", "-f", "-v", CONTAINER)
    t0 = time.time()
    r = docker("run", "-d", "--name", CONTAINER, "-p", f"{PORT}:8080", "--memory", "1536m", "-e", "TZ=UTC",
               "--entrypoint", "java", IMAGE, "-Djenkins.install.runSetupWizard=false", "-Xmx768m", "-Duser.language=en",
               "-jar", "/usr/share/jenkins/jenkins.war", "--httpPort=8080")
    if r.returncode != 0:
        raise RuntimeError("docker run failed: " + (r.stderr or r.stdout)[-400:])
    a = Anon()
    row["boot_seconds"] = round(time.time() - t0) if poll(lambda: up(a), 300, 2) else None
    if row["boot_seconds"] is None:
        raise RuntimeError("the clean controller did not answer")
    row["core"] = a.get("/api/json").headers.get("X-Jenkins")
    row["before"] = plugins(a)
    row["before_page"] = a.get("/batch-control/").status_code
    # Check now: the update center's metadata (dependency resolution needs it); retried, the network may blip
    t1 = time.time()
    for attempt in range(1, 5):
        c = a.post("/pluginManager/checkUpdatesServer")
        got = groovy(a, "def s = jenkins.model.Jenkins.get().updateCenter.getSite('default'); "
                        "return s?.data == null ? 'none' : s.data.plugins.size()")
        row["update_center"] = {"status": c.status_code, "plugins": got, "attempts": attempt}
        if got.isdigit() and int(got) > 0:
            break
        time.sleep(5 * attempt)
    row["check_seconds"] = round(time.time() - t1)
    # Deploy Plugin (Advanced settings): the file field "name" and the URL field "pluginUrl", in the form's order
    t2 = time.time()
    with open(hpi, "rb") as fh:
        u = a.post("/pluginManager/uploadPlugin", files=[("name", ("batch-control.hpi", fh, "application/octet-stream")),
                                                         ("pluginUrl", (None, ""))])
    row["upload"] = {"status": u.status_code, "location": u.headers.get("Location", "")}

    def jobs():
        j = a.get("/updateCenter/api/json?depth=2&tree=jobs%5Bname,type,status%5B_class%5D,errorMessage%5D")
        return [{"name": x.get("name"), "status": (x.get("status") or {}).get("_class", "").rsplit("$", 1)[-1],
                 "error": (x.get("errorMessage") or "")[:200]} for x in j.json().get("jobs", []) if x.get("name")] \
            if j.status_code == 200 else []

    def settled():
        out = jobs()
        return out if out and not [x for x in out if x["status"] in ("Pending", "Installing", "")] else None
    poll(settled, 240, 2)
    row["jobs"] = jobs()
    row["install_seconds"] = round(time.time() - t2)
    t3 = time.time()
    a.post("/safeRestart")
    time.sleep(3)
    a2 = Anon()
    want = row["hpi_version"]

    def restarted():  # back with the build loaded, or back with a plugin that failed to load (no need to wait longer)
        p = plugins(a2) if up(a2) else None
        if p and (p.get("batch-control") or {}).get("version") == want:
            return p
        return p if p and groovy(a2, FAILED) not in ("[]", "") else None
    row["after"] = poll(restarted, 300, 3) or plugins(a2)
    row["restart_seconds"] = round(time.time() - t3)
    row["failed"] = groovy(a2, FAILED)
    row["after_page"] = a2.get("/batch-control/").status_code
    row["seconds"] = round(time.time() - t0)


if __name__ == "__main__":
    row = {}
    t_start = time.time()
    try:
        main(row)
    except Exception as e:  # noqa: recorded; judge "deploy" fails a run that did not measure
        row["error"] = repr(e)[:800]
    finally:
        try:
            (OUT / "deploy-jenkins.log").write_text(docker("logs", CONTAINER).stdout + docker("logs", CONTAINER).stderr)
        except Exception:  # noqa
            pass
        if os.environ.get("BC_KEEP") != "1":
            docker("rm", "-f", "-v", CONTAINER)
    (OUT / "deploy.json").write_text(json.dumps(row, indent=1, default=str))
    for k, v in row.items():
        print(k, "=>", json.dumps(v, default=str)[:600])
    n = h1.report("deploy", [row])
    print(f"SUMMARY deploy fail={n} seconds={round(time.time() - t_start)}", flush=True)
    sys.exit(1 if n else 0)
