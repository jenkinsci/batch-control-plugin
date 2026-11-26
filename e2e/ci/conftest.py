"""pytest entry point of the CI e2e pass (e2e-20). One test per step of a shard; the steps are the existing driver
invocations of ci/shard.py, run and judged exactly as `shard.py run` does (ci/shard.py Runner).

    python -m pytest e2e/ci/test_shard.py --bc-shard 2/8 --bc-out e2e/ci/out/2 --junitxml e2e/ci/out/2/junit.xml

ci/run.sh calls it like that. The stack must be up ($BC_BASE). Retries: only the steps in shard.FLAKY carry
@pytest.mark.flaky(reruns=1) (pytest-rerunfailures). A step after a failed setup step is skipped as BLOCKED.
"""
import importlib.util
import os
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("bc_shard", HERE / "shard.py")
shard = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(shard)


def pytest_addoption(parser):
    g = parser.getgroup("batch-control e2e")
    g.addoption("--bc-shard", default=os.environ.get("BC_SHARD_SPEC"), help="<k>/<N>: the shard to run (ci/shard.py)")
    g.addoption("--bc-out", default=None, help="artefact directory (default e2e/ci/out/<k>)")


def _plan(config):
    spec = config.getoption("--bc-shard")
    if not spec:
        return None
    try:  # a malformed <k>/<N>, N other than the number of groups, inconsistent GROUPS: a usage error, not a crash
        k, n = shard.parse_shard(spec)
        return k, n, shard.steps_for(shard.units_for(k, n))
    except SystemExit as e:
        raise pytest.UsageError(str(e))


def pytest_configure(config):
    _plan(config)  # reports a bad --bc-shard as a plain usage error (exit 4) before collection


def pytest_generate_tests(metafunc):
    if "bc_step" not in metafunc.fixturenames:
        return
    plan = _plan(metafunc.config)
    if plan is None:
        raise pytest.UsageError("--bc-shard <k>/<N> is required for test_shard.py")
    params = []
    for idx, (name, spec) in enumerate(plan[2], 1):
        marks = [pytest.mark.flaky(reruns=1, reruns_delay=5)] if name in shard.FLAKY else []
        params.append(pytest.param((idx, name, spec), id=name, marks=marks))
    metafunc.parametrize("bc_step", params)


@pytest.fixture(scope="session")
def bc_runner(request):
    k, n, _ = _plan(request.config)
    out = request.config.getoption("--bc-out") or str(HERE / "out" / str(k))
    r = shard.Runner(k, n, out)
    print("\n" + r.plan_line(), flush=True)
    yield r
    r.finish()
