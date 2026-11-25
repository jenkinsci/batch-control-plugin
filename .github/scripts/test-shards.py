#!/usr/bin/env python3
"""Safeguard for the sharded Surefire run in .github/workflows/build.yml.

The Surefire run is split into feature groups, defined in .github/test-shards.txt (the
format is described in its header). Each group has a label, which names its job
("test (jdk 21, run approval)"), and an id derived from the label (lower case, every run
of other characters turned into '-': "run-approval"), which names its artifact and is
the argument of "verify".

Usage:
  test-shards.py check
      Lists every test class Surefire would run and checks that each one matches exactly
      one group, that no group is empty and that no pattern is stale (matches no class).
      On success it writes the job matrix to $GITHUB_OUTPUT (key "matrix"): one entry per
      JDK and group, with the keys jdk, group (the id), label and tests (the -Dtest
      value). The JDK versions come from the JDKS environment variable (comma-separated,
      default "21").
  test-shards.py verify <group-id>
      Run after a group's Surefire run. Checks that target/surefire-reports holds a report
      for every class assigned to the group and for no other class, and that no report
      counts a failure or an error (a test that failed and then passed on a rerun counts
      as a flake, not as a failure). Writes the group's test counts to
      $GITHUB_STEP_SUMMARY, then exits 1 when a check fails, with one error line per
      failing, missing or extra class. The failure check is a second guard behind the
      Maven step's exit status (#172).

The pattern matching mirrors Surefire's -Dtest handling for patterns without a package
part: the pattern is matched against the simple class name, '*' matches any run of
characters.
"""

import fnmatch
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SHARD_FILE = ROOT / ".github" / "test-shards.txt"
TEST_SRC = ROOT / "src" / "test" / "java"
REPORTS = ROOT / "target" / "surefire-reports"

# Surefire's default includes. A class matching one of these but no group pattern would
# be silently skipped by the sharded run, so the check treats it as an error.
SUREFIRE_INCLUDES = ["Test*", "*Test", "*Tests", "*TestCase"]

# Generated at build time by maven-hpi-plugin (insert-test goal), not present in src/test.
GENERATED_TESTS = ["io.jenkins.plugins.batch_control.InjectedTest"]

HEADER = re.compile(r"^\[(.*)\]$")
LABEL_SYNTAX = re.compile(r"^[a-z0-9]+(?:[ -][a-z0-9]+)*$")
LABEL_MAX = 25
PATTERN_SYNTAX = re.compile(r"^[A-Za-z0-9_*]+$")

# The counts "verify" reads from the <testsuite> element of each Surefire report.
COUNT_KEYS = ("tests", "failures", "errors", "skipped")


def fail(msg):
    print(f"::error::{msg}")
    sys.exit(1)


def group_id(label):
    return re.sub(r"[^a-z0-9]+", "-", label).strip("-")


def load_groups():
    groups = []
    for lineno, raw in enumerate(SHARD_FILE.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        where = f"{SHARD_FILE.name}:{lineno}"
        header = HEADER.match(line)
        if header:
            label = header.group(1)
            if len(label) > LABEL_MAX or not LABEL_SYNTAX.match(label):
                fail(f"{where}: label '{label}' must be words of lower-case letters and digits "
                     f"separated by single spaces or '-', at most {LABEL_MAX} characters")
            gid = group_id(label)
            if any(g["id"] == gid for g in groups):
                fail(f"{where}: label '{label}' repeats the group id '{gid}' of another group")
            groups.append({"id": gid, "label": label, "patterns": []})
            continue
        if not groups:
            fail(f"{where}: patterns before the first '[<label>]' line")
        for p in re.split(r"[,\s]+", line):
            if not p:
                continue
            if not PATTERN_SYNTAX.match(p):
                fail(f"{where}: unsupported pattern '{p}'")
            if p in groups[-1]["patterns"]:
                fail(f"{where}: pattern '{p}' is listed twice in group '{groups[-1]['label']}'")
            groups[-1]["patterns"].append(p)
    if not groups:
        fail(f"{SHARD_FILE.name} defines no groups")
    for g in groups:
        if not g["patterns"]:
            fail(f"{SHARD_FILE.name}: group '{g['label']}' has no patterns")
    return groups


def is_abstract(path, simple):
    # Surefire does not run abstract classes, so they need no group.
    return re.search(rf"\babstract\s+class\s+{re.escape(simple)}\b", path.read_text(encoding="utf-8")) is not None


def list_test_classes():
    classes = []
    for path in sorted(TEST_SRC.rglob("*.java")):
        simple = path.stem
        if not any(fnmatch.fnmatchcase(simple, inc) for inc in SUREFIRE_INCLUDES):
            continue
        if is_abstract(path, simple):
            continue
        fqcn = ".".join(path.relative_to(TEST_SRC).with_suffix("").parts)
        classes.append(fqcn)
    classes.extend(GENERATED_TESTS)
    return sorted(set(classes))


def assign(groups, classes):
    by_group = {g["id"]: [] for g in groups}
    used = set()
    errors = []
    for fqcn in classes:
        simple = fqcn.rsplit(".", 1)[-1]
        hits = []
        for g in groups:
            matched = [p for p in g["patterns"] if fnmatch.fnmatchcase(simple, p)]
            used.update((g["id"], p) for p in matched)
            if matched:
                hits.append(g)
        if not hits:
            errors.append(f"{fqcn} matches no group in .github/test-shards.txt")
        elif len(hits) > 1:
            errors.append(f"{fqcn} matches more than one group: {', '.join(repr(g['label']) for g in hits)}")
        else:
            by_group[hits[0]["id"]].append(fqcn)
    for g in groups:
        if not by_group[g["id"]]:
            errors.append(f"group '{g['label']}' matches no test class")
        for p in g["patterns"]:
            if (g["id"], p) not in used:
                errors.append(f"pattern '{p}' of group '{g['label']}' matches no test class (remove it)")
    return by_group, errors


def write_output(key, value):
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as f:
            f.write(f"{key}={value}\n")


def write_summary(text):
    out = os.environ.get("GITHUB_STEP_SUMMARY")
    if out:
        with open(out, "a", encoding="utf-8") as f:
            f.write(text + "\n")


def checked_assignment():
    groups = load_groups()
    classes = list_test_classes()
    by_group, errors = assign(groups, classes)
    if errors:
        for e in errors:
            print(f"::error::{e}")
        sys.exit(1)
    return groups, classes, by_group


def cmd_check():
    groups, classes, by_group = checked_assignment()
    lines = ["| Job label | Group id | Classes | Patterns |", "|---|---|---:|---|"]
    for g in groups:
        lines.append(f"| {g['label']} | {g['id']} | {len(by_group[g['id']])} | `{','.join(g['patterns'])}` |")
    lines.append(f"| total | | {len(classes)} | |")
    print("\n".join(lines))
    write_summary("### Test groups\n\n" + "\n".join(lines))
    jdks = [j.strip() for j in os.environ.get("JDKS", "21").split(",") if j.strip()]
    matrix = {
        "include": [
            {"jdk": j, "group": g["id"], "label": g["label"], "tests": ",".join(g["patterns"])}
            for j in jdks
            for g in groups
        ]
    }
    write_output("matrix", json.dumps(matrix, separators=(",", ":")))
    print(f"OK: {len(classes)} test classes, each in exactly one of {len(groups)} groups")


def cmd_verify(gid):
    groups, _, by_group = checked_assignment()
    if gid not in by_group:
        fail(f"unknown group id '{gid}'")
    label = next(g["label"] for g in groups if g["id"] == gid)
    expected = set(by_group[gid])
    counts = {}
    for p in REPORTS.glob("TEST-*.xml"):
        root = ET.parse(p).getroot()
        counts[p.name[len("TEST-"):-len(".xml")]] = {k: int(root.get(k, "0")) for k in COUNT_KEYS}
    reported = set(counts)
    missing = sorted(expected - reported)
    extra = sorted(reported - expected)
    failing = sorted(c for c, n in counts.items() if n["failures"] or n["errors"])
    totals = {k: sum(n[k] for n in counts.values()) for k in COUNT_KEYS}
    summary = (
        f"### Group {label}\n\n"
        f"{len(reported)} of {len(expected)} classes reported; "
        f"tests {totals['tests']}, failures {totals['failures']}, "
        f"errors {totals['errors']}, skipped {totals['skipped']}"
    )
    print(summary)
    write_summary(summary)
    write_output("tests", totals["tests"])
    # Failing classes first: GitHub shows only the first ten error annotations of a step.
    for c in failing:
        print(f"::error::group {label}: {c} failed "
              f"(failures {counts[c]['failures']}, errors {counts[c]['errors']})")
    for c in missing:
        print(f"::error::group {label}: no Surefire report for {c}")
    for c in extra:
        print(f"::error::group {label}: ran {c}, which belongs to another group")
    if failing or missing or extra:
        sys.exit(1)


def main(argv):
    if len(argv) == 2 and argv[1] == "check":
        cmd_check()
    elif len(argv) == 3 and argv[1] == "verify":
        cmd_verify(argv[2])
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main(sys.argv)
