"""The crawl's content checks on the synthetic pages of ci/selftest_content.py, as pytest-playwright tests (no Jenkins).

    python -m pytest e2e/ci/test_selftest_content.py --browser chromium        (or --browser-channel chrome)

pytest-playwright gives each test a fresh `page` (its --tracing retain-on-failure / --screenshot options apply).
test_h1_rules (no browser) replays recorded rows and synthetic findings through the H1 fail rules (ci/selftest_h1.py);
it lives here so that the workflow's selftest step runs it too."""
import importlib.util
from pathlib import Path

import pytest

_spec = importlib.util.spec_from_file_location("bc_selftest", Path(__file__).resolve().parent / "selftest_content.py")
selftest = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(selftest)


@pytest.mark.parametrize("name", list(selftest.PAGES))
def test_content_checks(page, name):
    good, lines = selftest.check_page(page, name)
    print("\n".join(lines))
    assert good, "\n".join(lines)


_h1spec = importlib.util.spec_from_file_location("bc_selftest_h1", Path(__file__).resolve().parent / "selftest_h1.py")
selftest_h1 = importlib.util.module_from_spec(_h1spec)
_h1spec.loader.exec_module(selftest_h1)


def test_h1_rules():
    good, lines = selftest_h1.check_all()
    print("\n".join(lines))
    assert good, "\n".join(x for x in lines if not x.startswith("PASS"))
