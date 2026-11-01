"""e2e-21 (the Reject buttons' rendered colour, 71d267b) on top of ../r16/lib.py: screenshots in screenshots/run-21/
(never committed), rows in r21/out/<driver>.jsonl. The rules of every e2e pass hold: one new browser context per page
(real login form), a red-boxed clipped screenshot per step, the server state read separately (REST with basic auth, the
script console only arranges or reads state).

Verdict lines as in r16..r18: "PASS {...}" / "FAIL {...}" per assertion (ci/shard.py fails a step on a FAIL line or a
non-zero exit), "NOTE {...}" for an observation without a verdict."""
import importlib.util
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("r16lib", HERE.parent / "r16" / "lib.py")
L16 = importlib.util.module_from_spec(_spec)
sys.modules["r16lib"] = L16
_spec.loader.exec_module(L16)
# r16/lib.py points r6/lib.py at run-16 and r16/out; this pass writes its own.
L16._l.SHOTS = HERE.parent / "screenshots" / "run-21"
L16._l.OUT = HERE / "out"
L16.SHOTS = L16._l.SHOTS
L16.OUT = L16._l.OUT
L16.SHOTS.mkdir(parents=True, exist_ok=True)
L16.OUT.mkdir(parents=True, exist_ok=True)
SHOTS, OUT = L16.SHOTS, L16.OUT

# #31: the destructive-control checks (reject_color.py D) are judged by ci/h1_rules.py, as r14/lib.py loads it
_h1spec = importlib.util.spec_from_file_location("h1_rules", HERE.parent / "ci" / "h1_rules.py")
h1 = importlib.util.module_from_spec(_h1spec)
_h1spec.loader.exec_module(h1)

Session, api, groovy, gv, BASE, ENV = L16.Session, L16.api, L16.groovy, L16.gv, L16.BASE, L16.ENV
check, note, run_sections, console_ok, J = L16.check, L16.note, L16.run_sections, L16.console_ok, L16.J
grant_req, loc_id, decide, LOGNAME = L16.grant_req, L16.loc_id, L16.decide, L16.LOGNAME
JOB = "r21-reject"  # the activation and grant target of this pass (r21/arrange.py); run requests go to batch-daily
