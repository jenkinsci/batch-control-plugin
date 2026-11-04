#!/usr/bin/env python3
"""Self-test of the crawl's content checks (r14/crawl.py), no Jenkins needed: runs the real CONTENT_JS and the real
content_checks() (taken from crawl.py's source, its module code is not executed) in headless Chromium against two
synthetic pages. The "defects" page plants one instance of every finding the checks must report, next to look-alikes
that must stay silent (text in <pre>, a URL path, an optional empty column); the "clean" page renders the markup probe
correctly and must produce no defect finding. The raw-enum INFO rows (e2e-16) are compared too (EXPECTED_ENUM), and the
design-reference scan content_checks() returns (#31, EXPECTED_DESIGN). Exit 0 when both pages give exactly the expected
findings.

    python e2e/ci/selftest_content.py          (BC_BROWSER_CHANNEL as for the drivers; default chromium here)
"""
import ast
import html
import json
import os
import re
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

CRAWL = Path(__file__).resolve().parent.parent / "r14" / "crawl.py"
WANTED = {"CONTENT_JS", "EXPECTED_COLUMNS", "PROBE_AS_TYPED", "_content_seen", "content_checks"}


def load():
    tree = ast.parse(CRAWL.read_text())
    nodes = [n for n in tree.body if (isinstance(n, ast.Assign) and any(getattr(t, "id", None) in WANTED for t in n.targets))
             or (isinstance(n, ast.FunctionDef) and n.name in WANTED)]
    rows = []
    # emit() rows are collected; the FAIL lines that content_checks() prints are swallowed (the verdict is this script's)
    ns = {"json": json, "re": re, "ROLE": "selftest", "UI": "page", "emit": rows.append, "print": lambda *a, **k: None}
    exec(compile(ast.Module(body=nodes, type_ignores=[]), str(CRAWL), "exec"), ns)
    missing = WANTED - set(ns)
    if missing:
        raise SystemExit(f"crawl.py no longer defines {sorted(missing)}")
    return ns, rows


PROBE = 'bc-markup-probe <b>b</b> & "q" <img src=x onerror=window.__bcCanary=1>'
PROBE_HTML = html.escape(PROBE, quote=False)
DEFECTS = """<div id="main-panel"><h2>Requests</h2>
<table><thead><tr><th>ID</th><th>Job</th><th>Comment</th></tr></thead>
<tbody><tr><td>r-1</td><td></td><td></td></tr></tbody></table>
<table><tbody><tr><th>Requester</th><td> </td></tr><tr><th>Decided by</th><td></td></tr></tbody></table>
<p>Store: /var/jenkins_home/batch-control/requests</p>
<p>failed: StoreLocation=batch-control</p>
<p>name &amp;lt;b&amp;gt; shown escaped twice</p>
<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>
<p>bc-markup-probe <b>b</b> &amp; "q" <img src=x onerror=window.__bcCanary=1></p>
<pre>/var/log/in-a-pre-block-is-fine</pre>
<p>Grant g-1 for user 'u' (ITEM:a/b) ended; type GRANT_REVOKE, status PENDING</p>
<table><tbody><tr><th>Scope</th><td>FOLDER_ONLY: team</td></tr></tbody></table>
<p><a href="/jenkins/job/batch-daily/">/jenkins/job/batch-daily/</a></p>
<table><thead><tr><th>Run</th><th>Result</th></tr></thead>
<tbody><tr><td>#1</td><td>FAILURE</td></tr><tr><td>#2</td><td><span class="batch-control-status" data-batch-control-tone="danger">Failed</span></td></tr></tbody></table>
<table><tbody><tr><th>Result</th><td>IN PROGRESS</td></tr></tbody></table>
<table><thead><tr><th>Run</th><th>Cause</th></tr></thead><tbody><tr><td><a href="/jenkins/job/x/">x</a> <a href="/jenkins/job/x/3/">#3</a> <span>UNSTABLE</span></td><td>User</td></tr></tbody></table>
<p>Pending counts are shown as tab badges (D-61).</p>
<span title="Defined in SPEC item 8">i</span>
<div style="display:none">Moves need Delete and Create (D-59a).</div>
<template><p>See DECISIONS.md and the TEST-MATRIX rows.</p></template>
</div>"""
CLEAN = f"""<div id="main-panel"><h2>Requests</h2>
<table><thead><tr><th>ID</th><th>Job</th><th>Comment</th></tr></thead>
<tbody><tr><td>r-1</td><td>batch-daily</td><td></td></tr></tbody></table>
<p>Reason: {PROBE_HTML}</p><p>Comment: {html.escape(PROBE[:30], quote=False)}…</p>
<p>Jobs under /jenkins/job/ops/ and the folder ops/sub; 3 &lt; 5 &amp; 7 &gt; 2</p>
<code>/tmp/in-code-is-fine GRANT_REVOKE ITEM:a/b</code>
<p>Status: Approved; Folder: team; JOB_NAME is not shown as code here but MIXED_case and lower_snake are not constants</p>
<table><thead><tr><th>Run</th><th>Result</th><th>Note</th></tr></thead>
<tbody><tr><td>#1</td><td>Failed</td><td>FAILURE outside a result cell is not judged here</td></tr><tr><td>#2</td><td>Not built</td><td>x</td></tr></tbody></table>
<table><tbody><tr><th>Result</th><td>In progress</td></tr></tbody></table>
<table><thead><tr><th>Run</th><th>Cause</th></tr></thead><tbody>
<tr><td><span class="batch-control-run"><svg width="16" height="16"><title>Aborted</title></svg> <a href="/jenkins/job/x/">x</a> <a href="/jenkins/job/x/4/">#4</a> <span>Aborted</span></span><div>Aborted by u1</div></td><td>User</td></tr>
<tr><td><span>ghost</span> <span>#7</span> <span>WEIRD</span></td><td>Other</td></tr></tbody></table>
<code>D-61</code><pre>SPEC item 8 in a diff</pre><textarea>D-59</textarea>
<p>Reason: e2e-17 D-75 (1): configure r17/vis-x</p>
<p>ID-12, AD-2026x, D-1, d-61 and spec item are not design references</p></div>"""
EXPECTED_DEFECTS = {("empty-cell", "Requests / Job"), ("empty-cell", "Requests / Requester"),
                    ("server-path", "/var/jenkins_home/batch-control/requests"), ("server-path", "StoreLocation="),
                    ("double-escaped", "&lt;"), ("markup-text", "<script"),
                    ("markup-live", "probe <img onerror> rendered as markup"), ("markup-live", "probe text not as typed"),
                    ("legacy-scope", "FOLDER_ONLY:"), ("raw-result", "FAILURE"), ("raw-result", "IN PROGRESS"),
                    ("result-badge", "Failed"), ("raw-result", "UNSTABLE")}
EXPECTED_INFO = {"Comment", "Decided by"}
# raw-enum INFO rows (e2e-16): planted on the defects page; the clean page has look-alikes that must stay silent except
# JOB_NAME, a real UPPER_SNAKE token in visible text (the check cannot tell a parameter name from an enum: INFO only).
EXPECTED_ENUM = {"defects": {"ITEM:", "GRANT_REVOKE", "PENDING", "FOLDER_ONLY"}, "clean": {"JOB_NAME"}}


# #31 (r6/s6.py): the design references content_checks() returns (the "design" scan judged by ci/h1_rules.py): planted in
# shown text, a title attribute, hidden text and a <template> on the defects page; the clean page holds look-alikes
# (pre/code/textarea, a driver-typed reason, words that only resemble one) and one skipped driver-typed text.
EXPECTED_DESIGN = {"defects": ({"D-61", "SPEC item", "D-59a", "DECISIONS.md"}, 0), "clean": (set(), 1)}


# result-cell INFO rows (#18.2): every result cell read, by its text
EXPECTED_RESULT_CELLS = {"defects": {"FAILURE", "Failed", "IN PROGRESS", "UNSTABLE"},
                         "clean": {"Failed", "Not built", "In progress", "Aborted", "WEIRD"}}


PAGES = {"defects": (DEFECTS, EXPECTED_DEFECTS, EXPECTED_INFO), "clean": (CLEAN, set(), {"Comment"})}


def check_page(page, name):
    """Runs the crawl's checks on the synthetic page `name` in `page`; returns (good, report lines). Used by main() and
    by the pytest-playwright test ci/test_selftest_content.py."""
    html, want, want_info = PAGES[name]
    ns, rows = load()
    page.set_content(html)
    # the probe's <img src=x onerror> fires once the image load fails; wait for that, not for a fixed time
    page.wait_for_function("() => [...document.images].every(i => i.complete)", timeout=5000)
    design = ns["content_checks"](type("S", (), {"page": page})(), "/batch-control/requests/", "/batch-control/requests/")
    got = {(r["check"], r["key"]) for r in rows if r["result"] == "DEFECT"}
    info = {r["key"] for r in rows if r["check"] == "empty-optional"}
    enums = {r["key"] for r in rows if r["check"] == "raw-enum"}
    errors = [r for r in rows if r["result"] == "ERROR"]
    results = {r["key"] for r in rows if r["check"] == "result-cell"}
    refs = {x["match"] for x in (design or {}).get("refs") or []}
    want_refs, want_skipped = EXPECTED_DESIGN[name]
    design_ok = bool(design) and design.get("texts", 0) > 0 and refs == want_refs and design.get("skipped") == want_skipped
    good = (got == want and info == want_info and enums == EXPECTED_ENUM[name] and not errors
            and results == EXPECTED_RESULT_CELLS[name] and design_ok)
    lines = [f"{'PASS' if good else 'FAIL'} selftest {name}: {len(got)} defect finding(s), empty-optional {sorted(info)}, "
             f"raw-enum {sorted(enums)}, result cells {sorted(results)}, design refs {sorted(refs)} "
             f"(skipped {(design or {}).get('skipped')}, texts {(design or {}).get('texts')})"]
    lines += [f"   design-ref mismatch: {x}" for x in sorted(want_refs ^ refs)]
    lines += [f"   result-cell mismatch: {x}" for x in sorted(EXPECTED_RESULT_CELLS[name] ^ results)]
    lines += [f"   raw-enum mismatch: {x}" for x in sorted(EXPECTED_ENUM[name] ^ enums)]
    lines += [f"   missed:     {x}" for x in sorted(want - got)]
    lines += [f"   unexpected: {x}" for x in sorted(got - want)]
    lines += [f"   error:      {r}" for r in errors]
    return good, lines


def main():
    channel = os.environ.get("BC_BROWSER_CHANNEL", "chromium")
    ok = True
    with sync_playwright() as pw:
        b = pw.chromium.launch(channel=None if channel in ("", "chromium") else channel)
        for name in PAGES:
            page = b.new_page()
            good, lines = check_page(page, name)
            ok &= good
            print("\n".join(lines))
            page.close()
        b.close()
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
