"""e2e-14 copy of the e2e-13 driver (role-strategy 927 on the merged main): ../r6/lib.py with screenshots
in screenshots/run-14/ and logs in r13/out/. Default context path is the root
(plain docker-compose.yml); set BC_BASE for another one. Same rules: one new context per
account, red-boxed clipped shots, server state read separately with basic auth."""
import os
import pathlib
import importlib.util

os.environ.setdefault("BC_BASE", "http://localhost:8080/jenkins")
HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("r6lib", HERE.parent.parent / "r6" / "lib.py")
_l = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_l)
_l.SHOTS = HERE.parent.parent / "screenshots" / "run-14"
_l.OUT = HERE.parent / "out" / "role"
_l.SHOTS.mkdir(parents=True, exist_ok=True)
_l.OUT.mkdir(parents=True, exist_ok=True)
Session, close, api, groovy, log, ENV, BASE, pw, shot = (
    _l.Session, _l.close, _l.api, _l.groovy, _l.log, _l.ENV, _l.BASE, _l.pw, _l.shot)
SHOTS = _l.SHOTS

# H1: the fail rules of the observational drivers (ci/h1_rules.py); a driver ends with h1.report(<driver>, rows, ctx)
_h1spec = importlib.util.spec_from_file_location("h1_rules", HERE.parent.parent / "ci" / "h1_rules.py")
h1 = importlib.util.module_from_spec(_h1spec)
_h1spec.loader.exec_module(h1)
PREFIX = __import__("urllib.parse").parse.urlparse(BASE).path.rstrip("/")


def strategy():
    return groovy("return jenkins.model.Jenkins.get().getAuthorizationStrategy().getClass().simpleName").replace("Result: ", "")


def casc(path):
    """Applies a JCasC file inside the container (arrangement only)."""
    return groovy("io.jenkins.plugins.casc.ConfigurationAsCode.get().configure('%s'); return 'ok'" % path)
