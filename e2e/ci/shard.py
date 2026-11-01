#!/usr/bin/env python3
"""Shard plan and step runner for the CI e2e pass (called by ci/run.sh; usable on its own against a running stack).

    shard.py units                      list the units, their weights, groups and steps
    shard.py check                      check GROUPS (every unit in exactly one group, ...) and print the groups
    shard.py matrix                     the GitHub Actions matrix of the groups as JSON (after the same check)
    shard.py label <k>/<N>              the label of group k
    shard.py plan <k>/<N> [--json]      the units and steps shard k of N (= group k) runs
    shard.py plan-all [N]               every group with its estimated duration
    shard.py run <k>/<N> --out DIR      run shard k of N against $BC_BASE, write DIR/summary.{json,md}

A *unit* is a group of existing driver invocations that must run in order on one Jenkins (for example the
crawl of one role on both job UIs). Every shard starts from a fresh JENKINS_HOME and first runs the SETUP steps
(the e2e-14 arrangement and seed), then its units in the canonical e2e-14 order; a unit marked `last` replaces the
authorization strategy and therefore runs last in its shard. The units are split into the fixed, named GROUPS below,
one CI job each, named after the group's label; shard k of N is group k, and N must be the number of groups.
BC_UNITS=a,b runs just those units instead of group k's (debugging).

A step fails when the driver exits non-zero, or prints one of the drivers' own failure markers: a line starting
with "FAIL " (round3.py, def07.py, the crawl's content checks), `"ok": false` (r15/check.py) or `"EXCEPTION":`
(misc.py catches a section's exception and logs it). The drivers that used to be observational only (crawl, actions,
jobui, misc, the targeted scripts, r14/role, r15 side_dialog/folder_compare/menu) judge their own rows at their end
with ci/h1_rules.py (H1) and print "FAIL <driver> <check> <json>" lines; their allowlist entries print "ALLOW <id>
matched <n>" or, when an entry that applies matched nothing, "STALE allow <id> ...". STALE lines are warnings: the
summary lists the entries that were stale in a step and matched in no step of the shard. The rows are still counted
in the summary's observations.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

E2E = Path(__file__).resolve().parent.parent
ROLES6 = ["admin", "requester", "reqonly", "approver-1", "manager", "nobc"]
DEF07_ROLES = ["approver-1", "admin", "manager"]
ROLE_CRAWL = ["requester", "approver-1", "admin"]


def py(*args):
    return {"argv": list(args)}


def flag(value, *users):
    return py("r14/set_flag.py", value, *users)


# The e2e-14 arrangement (docs/reports/e2e-14.md, Environment) plus the CI-only pieces in ci/.
SETUP = [
    ("arrange-r7", py("r7/arrange.py")),
    ("arrange-r8", py("r8/arrange.py")),
    ("arrange-side", py("r8/arrange_side.py")),
    ("arrange-r12", py("r12/arrange.py")),
    ("arrange-fast", py("r14/arrange_fast.py")),
    ("arrange-ci", py("ci/arrange_ci.py")),
    ("seed-fast", py("r14/seed_fast.py")),
    ("seed", py("r14/seed.py")),
    ("seed-paging", py("r14/seed_paging.py")),
    ("seed-markup", py("ci/seed_markup.py")),
    ("preconditions", py("ci/preconditions.py")),  # e2e-16: the seeded fixtures the drivers assume, asserted
]
SETUP_WEIGHT = 3.4
# What a CI job spends outside the steps (checkout, the Python packages and Chromium, the fonts, starting Jenkins, the
# coverage dump and upload): job minus driver minutes in run 37883661045 were 3.1-3.4, of which Jenkins' start 2.4-2.6.
JOB_OVERHEAD = 3.2


class Unit:
    def __init__(self, name, weight, steps, last=False, doc="", group=None):
        self.name, self.weight, self.steps, self.last, self.doc, self.group = name, weight, steps, last, doc, group


def crawl_unit(role, weight):
    steps = [
        (f"flag-new-{role}", flag("true", role)),
        (f"crawl-{role}-new", py("r14/crawl.py", role, "new")),
        (f"flag-classic-{role}", flag("false", role)),
        (f"crawl-{role}-classic", py("r14/crawl.py", role, "classic")),
        (f"flag-reset-{role}", flag("true", role)),
    ]
    if role == "admin":  # the admin crawl dismisses the Batch Control monitors on /manage/ (see ci/arrange_ci.py)
        steps.append(("restore-monitors", py("ci/arrange_ci.py", "monitors")))
    return Unit(f"crawl-{role}", weight, steps,
                doc=f"e2e-12/14 crawl (run_crawl.sh) for {role}, new job page then classic, with the content checks")


# jobui.py <ui> [role ...] runs each role on its own; the roles are grouped so that the groups take similar time.
# Weights: the minutes of CI run 37883661045 (see UNITS). The local measurement of 2026-10-05 had reqonly and
# approver-1+manager+nobc as one figure (new UI 15.9, classic 13.6) and split it by a guess that reqonly had most of
# the entries; on CI reqonly logged 38 entries and took 3.0 / 2.7 min, approver-1+manager+nobc 90 entries, 9.3 / 7.3.
JOBUI_GROUPS = {"new": [("requester", 6.9), ("admin", 5.2), ("reqonly", 3.0), ("approver-1 manager nobc", 9.3)],
                "classic": [("requester", 6.2), ("admin", 4.0), ("reqonly", 2.7), ("approver-1 manager nobc", 7.3)]}


def jobui_unit(ui, group, weight):
    roles = group.split()
    name = f"jobui-{ui}-{roles[0] if len(roles) == 1 else 'others'}"
    return Unit(name, weight, [
        (f"flag-{name}", flag("true" if ui == "new" else "false", *roles)),
        (name, py("r14/jobui.py", ui, *roles)),
        (f"flag-reset-{name}", flag("true", *roles)),
    ], doc=f"e2e-12/14 G2c: entry points and dialog cycles, {ui} job UI, {', '.join(roles)}")


# Canonical order = the e2e-14 order. Weights: minutes per unit on the GitHub Actions runner. They were measured on
# local shard runs (2026-10-05..08: MacBook, Docker Desktop, under load from other builds) and calibrated on 2026-10-09
# against CI run 37883661045 (ubuntu-latest; each shard's summary.json, step seconds summed per unit): where the CI
# minutes differed from the weight by more than 0.3 min and more than 10%, the weight is now the CI minutes, rounded to
# 0.1. That changed def07, misc, crawl-admin/-requester/-reqonly/-manager, all eight jobui units, r16-follow/-params/
# -rerun/-d60/-names/-durable, r17-s39, r17-disk, r19-plugins, role and r18-final (the local runs had been off by a
# factor of 0.17 to 3.2, in both directions, not by a common factor). The others, and SETUP_WEIGHT, were within that
# margin and are unchanged. One CI run: take a weight as good to about 10%.
UNITS = [
    Unit("def07", 1.0, [
        ("flag-def07-new", flag("true", *DEF07_ROLES)),
        ("def07-new", py("r14/def07.py", "new")),
        ("flag-def07-classic", flag("false", *DEF07_ROLES)),
        ("def07-classic", py("r14/def07.py", "classic")),
        ("flag-def07-reset", flag("true", *DEF07_ROLES)),
    ], doc="e2e-14 G1: DEF-07 recheck on both job UIs"),
    crawl_unit("admin", 5.3),
    crawl_unit("requester", 4.8),
    crawl_unit("reqonly", 2.6),
    crawl_unit("approver-1", 1.2),
    crawl_unit("manager", 3.9),
    crawl_unit("nobc", 1.2),
    Unit("actions", 2.3, [("actions", py("r14/actions.py", "all"))],
         doc="e2e-12/14 G2b: every state-changing control per role"),
    # e2e-22 R4-01 (a) (bug-hunt batch B). r22/approver_case.py arranges its own item (r22-case). R4-01 (b), LDAP letter
    # case, needs compose.ldap.yml, which no CI group runs: T-08-194/195 (GrantIdStrategyTest, case-insensitive realm).
    Unit("r22-approver-case", 0.4, [("r22-approver-case", py("r22/approver_case.py"))],
         doc="e2e-22 R4-01: a job approver restriction spelled 'Approver-1' offers the global approver-1 on the Request Run "
             "form (which files the request) and on the Change Approvers form, as the service accepts it"),
] + [jobui_unit(ui, group, w) for ui in ("new", "classic") for group, w in JOBUI_GROUPS[ui]] + [
    Unit("misc", 1.8, [("misc", py("r14/misc.py", "CHPBTRMKS"))], doc="e2e-12/14 G2d: targeted checks C H P B T R M K S"),
    Unit("targeted", 0.9, [
        ("errpages", py("r14/errpages.py")),
        ("listpager", py("r14/s_listpager.py")),
        ("incident", py("r14/s_incident.py")),
        ("helpcheck", py("r14/helpcheck.py")),
        ("helpcheck2", py("r14/helpcheck2.py")),
        ("monitor", py("r14/s_monitor.py")),
    ], doc="e2e-12/14 G2d: error pages, list pager, incident, help, strategy monitor"),
    Unit("round3", 0.6, [("round3", py("r14/round3.py", "ABCDEFGHI"))], doc="e2e-14 R3-A..I: the e2e-11 round-3 checks"),
    # e2e-23 R1-01 (bug-hunt batch A). r23/xml_chars.py arranges its own items (r23-ansi-fail, r23-plain-fail).
    Unit("r23-xml-chars", 0.6, [("r23-xml-chars", py("r23/xml_chars.py"))], group="crawl and ui checks",
         doc="e2e-23 R1-01: an ANSI-coloured console tail leaves the incident readable (list, page, incidents.csv, "
             "summary); a comment or grant reason holding U+000B is refused with a 4xx and stores nothing"),
    # e2e-25 #43 (Wave C-UI, D-85 BOM by default). r25/csv_bom.py arranges its own item (r25-csv-배치), runs it once
    # per pass through an approved request and alternates its activation (ACTIVATE/HOLD) to write a change record.
    Unit("r25-csv-bom", 0.7, [("r25-csv-bom", py("r25/csv_bom.py"))], group="crawl and ui checks",
         doc="e2e-25 #43: runs.csv, incidents.csv, changes.csv and requests.csv (REST, filtered, empty, and the History "
             "page's download buttons) start with the UTF-8 BOM and the header row; guards: content type and "
             "attachment name unchanged, the existing columns, Korean values in UTF-8"),
    # e2e-26 #36 (wave B). r26/activation_write.py deletes and re-creates its own items (r26-aw-hold, r26-aw-act), makes
    # batch-control/activations/ unwritable with `docker exec -u root` around each approval, restores its exact modes, and
    # restarts Jenkins (last).
    Unit("r26-activation-write", 0.6, [("r26-activation-write", py("r26/activation_write.py"))], last=True,
         group="crawl and ui checks",
         doc="e2e-26 #36: an approved HOLD/ACTIVATE whose activation state cannot be written is refused (4xx 'could not "
             "be saved', no Oops), stays PENDING, the job page, state file and records are unchanged; after a restart the "
             "state is what the page said; once writable, the same approval works"),
    # e2e-16 (hosting review round 6: D-71..D-74). Each unit arranges its own items/accounts (r16/arrange.py is idempotent)
    # so it is self-contained on whatever shard it lands on. Drivers exit non-zero and print FAIL lines. Weights: minutes
    # measured on the 2026-10-06 runs (arrangement included), calibrated against CI (above).
    Unit("r16-items", 1.0, [("r16-arrange", py("r16/arrange.py")), ("r16-items", py("r16/items.py"))],
         doc="e2e-16: one-item windows on job/folder/multibranch (D-71): Configure/Create reach, legacy scope refused, kind+icon"),
    Unit("r16-rename", 0.8, [("r16-arrange", py("r16/arrange.py")), ("r16-rename", py("r16/rename.py"))],
         doc="e2e-16: no rename through a window via UI and every URL form; allowed for admin/own permission (D-71c)"),
    Unit("r16-follow", 2.4, [("r16-arrange", py("r16/arrange.py")), ("r16-follow", py("r16/follow.py"))],
         doc="e2e-16: windows follow admin rename/move, deletion ends them and DELETE records name the deleting user (D-74, "
             "1864bdc); refused moves once per minute (D-73); CREATE window under a naming strategy (T-08-168)"),
    # e2e-24 #39 (wave A). r24/switch_race.py arranges its own items (r24-race-1..12) and API tokens it revokes, switches
    # change control off and on (which revokes every open window), and leaves it on with no pending request.
    Unit("r24-switch-race", 1.8, [("r24-switch-race", py("r24/switch_race.py"))], group="job ui windows and disk",
         doc="e2e-24 #39: an approval racing the change-control switch-off leaves no active window and confers nothing "
             "after the switch is back on: a deterministic hold (store write) and a 40-round REST stress; guards"),
    Unit("r16-params", 1.4, [("r16-arrange", py("r16/arrange.py")), ("r16-params", py("r16/params.py"))],
         doc="e2e-16: typed parameters (core file, stashedFile, base64File, password) page+dialog; values file; 413 at both "
             "stages; repeated name, U+0000, disposal (D-72, D-72b, D-74)"),
    Unit("r16-rerun", 0.5, [("r16-arrange", py("r16/arrange.py")), ("r16-rerun", py("r16/rerun.py"))],
         doc="e2e-16: incident rerun reuses the secret/core file; stashedFile falls back to the validated prefilled form; "
             "fromRerun validation; incident actions need ViewHistory (D-72, D-72a, SPEC 11)"),
    Unit("r16-d60", 0.4, [("r16-arrange", py("r16/arrange.py")), ("r16-d60", py("r16/d60.py"))],
         doc="e2e-16: refused direct build -> prefilled form: run parameter carried (T-06-103), new job page dialog, files "
             "and secrets not carried (D-60, #115)"),
    Unit("r16-names", 0.2, [("r16-arrange", py("r16/arrange.py")), ("r16-names", py("r16/names.py"))],
         doc="e2e-16: CREATE name restriction in the #107 optionalBlock (ticks Create before filling); exact and /regex/"),
    # e2e-23 R3-01 (bug-hunt batch A). r23/kill_switch.py arranges its own item (r23-killswitch), makes
    # batch-control/changes/ unwritable with `docker exec -u root` and restores its exact modes and change control.
    Unit("r23-kill-switch", 0.6, [("r23-kill-switch", py("r23/kill_switch.py"))], group="admin and role strategy",
         doc="e2e-23 R3-01: with the change log unwritable, change control turned off on the form and by the setter "
             "completes (no 500, off on disk, open windows revoked, 403 after it is back on); the JCasC boot path: T-01-19"),
    # e2e-23 R3-01 follow-up (record-write guards). r23/record_writes.py deletes and re-creates its own items (r23-rw-*),
    # makes batch-control/changes/ unwritable around each action with `docker exec -u root` and restores its exact modes;
    # it turns change control off and on again (which revokes every open window) and leaves no pending request.
    Unit("r23-record-writes", 0.8, [("r23-record-writes", py("r23/record_writes.py"))], group="crawls and multibranch",
         doc="e2e-23 R3-01 follow-up: with the change log unwritable, an approved ACTIVATE/HOLD is applied (no 500, others "
             "INVALIDATED, mail), a non-admin's move is held and locked, a grant request while change control is off gets "
             "the 4xx refusal, a createItem payload or copy answers the D-48 403"),
    Unit("multibranch", 2.8, [
        ("mb-arrange", py("r15/arrange.py")),
        ("mb-check-crawl", py("r15/check.py", "crawl")),
        ("mb-submit-folder", py("r15/check.py", "submit", "true", "/job/team-mb/batch-control-activation/", "requester")),
        ("mb-submit-branch-new", py("r15/check.py", "submit", "true", "/job/team-mb/job/main/batch-control-activation/", "requester")),
        ("mb-submit-branch-classic", py("r15/check.py", "submit", "false", "/job/team-mb/job/feature-1/batch-control-activation/", "requester")),
        ("mb-submit-pipeline", py("r15/check.py", "submit", "true", "/job/batch-pipeline/batch-control-activation/", "requester")),
        ("mb-submit-daily", py("r15/check.py", "submit", "false", "/job/batch-daily/batch-control-activation/", "requester")),
        ("mb-side-dialog", py("r15/side_dialog.py")),
        ("mb-folder-compare", py("r15/folder_compare.py")),
        ("mb-menu", py("r15/menu.py", "ci")),
    ], doc="e2e-15: multibranch activation page and the DEF-08 job pages"),
    # e2e-17 (security-39 fixes, D-75). r17/arrange.py is idempotent and self-contained (account w17, items r17*).
    Unit("r17-s39", 1.5, [("r17-arrange", py("r17/arrange.py")), ("r17-s39", py("r17/s39.py", "PRD")),
                          ("r17-visibility", py("r17/s39.py", "H"))],
         doc="e2e-17: 404 for malformed and aliased record ids (S-39-01); a window follows two renames and the page shows "
             "the current name; delete + re-create ends it (S-39-02); followed name hidden from non-readers (D-75 (1), own step)"),
    # e2e-22 R4-03 (bug-hunt batch B). r22/nonascii_reason.py arranges its own item (r22-reason) and cancels the requests
    # it files.
    Unit("r22-nonascii-reason", 0.5, [("r22-nonascii-reason", py("r22/nonascii_reason.py"))],
         doc="e2e-22 R4-03: a non-ASCII reason survives the refusal of the multipart Request Run form and is stored as "
             "typed; a multipart client without the json field stores reason and DATE as sent; url-encoded guard"),
    # e2e-24 #40 (wave A). r24/json_params.py deletes and re-creates its own item (r24-json) and changes its defaults.
    Unit("r24-json-params", 0.4, [("r24-json-params", py("r24/json_params.py"))], group="crawl and ui checks",
         doc="e2e-24 #40: a json submission with all, some or no parameters (url-encoded and multipart) stores every "
             "parameter, the approver's page lists them, and the build runs with them after the defaults changed"),
    # e2e-27 D2 (Wave D, #18.3 and #18.2, with the colour badges of 2026-10-14). r27/d2_fixture.py arranges the records
    # of every status on its own items (r27-d2-*): it activates r27-d2-timer once through the request flow, deletes and
    # re-creates r27-d2-gone and r27-d2-w-del, ages three requests in the store so the periodic work expires them, and for
    # one deletion adds an administrator with a long user id to the matrix and removes it again. The first step leaves
    # the records for the second (BC_D2_KEEP_FIXTURE=1), which sweeps the requester's pending r27-d2 requests at its end;
    # it switches approver-1's theme and the Content-Security-Policy enforcement for its sections and back. In the group
    # "job ui params and restart" since the badges (its weight did not fit "crawl and ui checks"). The owner decisions of
    # 2026-10-14 added sections I and L (incident results, result labels, a build held running for about half a minute,
    # incidentResults changed and put back, the result labels read in three browser languages); T-UI-269j added section N
    # (the Incidents introduction under five settings saved on the configuration page). Weight: 164 to 170 s in four runs
    # on a fresh stack through ci/run.sh, both steps, with section N (2026-10-15, local, five stacks at once); r16-params
    # moved to "job ui other roles" so that this group stays under the heaviest group's 19.5.
    Unit("r27-labels-filters", 2.8, [("r27-labels-filters", dict(py("r27/labels_filters.py"), env={"BC_D2_KEEP_FIXTURE": "1"})),
                                     ("r27-status-badges", py("r27/status_badges.py"))], group="job ui params and restart",
         doc="e2e-27 D2: History's Result/Status filters are jenkins-select dropdowns (every option filters, Any, dates, "
             "paging, CSV link, keyboard, kept input escaped); every status and cause a readable label with "
             "data-batch-control-status on every screen and for five accounts; request and incident statuses colour "
             "badges with the contract's tones (approvals neutral in the Ended tables), causes plain; the Incidents "
             "Result filter offers Any, Failed, Unstable, Aborted; build results plain-text labels on six screens "
             "(In progress while running); contrast in the light and dark theme, geometry at 1280/1024/768 (inside "
             "the cell, no new sideways scroll), no CSP violation under enforcement, forced colours; guards: CSV, "
             "filters, JSON keep enums"),
    # e2e-19 (gap audit, docs/reports/e2e-19.md): SPEC acceptance lines no CI step checked before. r19/arrange.py is
    # idempotent and self-contained (items r19-*, accounts from JCasC). Each driver exits non-zero and prints FAIL lines,
    # and restores what it changes globally (executors, authorize-project's authenticator). Weights: minutes measured on
    # the 2026-10-08 local runs (arrangement included), calibrated against CI (above).
    Unit("r19-gate", 1.5, [("r19-arrange", py("r19/arrange.py")), ("r19-gate", py("r19/gate.py"))],
         doc="e2e-19: every manual path refused (REST build/buildWithParameters, CLI, build token, build-token-root, "
             "Replay, Rebuild), the job page notice, an approved run exactly once with exact values, Cause on the build "
             "page, marker re-use recorded (SPEC 6, 4, 10, D-30)"),
    # e2e-24 #37 (wave A). r24/user_cause.py deletes and re-creates its own items (r24-ucause*) and activates one through
    # an approved ACTIVATE request; the deprecated cause is submitted from the script console and the CLI groovy command.
    Unit("r24-user-cause", 1.6, [("r24-user-cause", py("r24/user_cause.py"))], group="runs and switches",
         doc="e2e-24 #37: a submission whose only cause is the deprecated Cause.UserCause is refused like UserIdCause "
             "(same refusal and records; console and CLI; activated and not activated); guards: UserIdCause, a job "
             "without approval, an approved run once"),
    # e2e-22 R4-02 (bug-hunt batch B). r22/scripted_build.py arranges its own item (r22-script) and a requester API token
    # it revokes at its end.
    Unit("r22-scripted-build", 0.4, [("r22-scripted-build", py("r22/scripted_build.py"))],
         doc="e2e-22 R4-02: a scripted build of an approval-required job (API token, no or a non-HTML Accept, a session "
             "without Accept) gets HTTP 400 with the approval message, also through curl -fL; browsers keep the D-60 303"),
    Unit("r19-plugins", 0.6, [("r19-arrange", py("r19/arrange.py")), ("r19-plugins", py("r19/plugins.py"))],
         doc="e2e-19: naginator retry refused and recorded, customize-build-now keeps Request Run, lockable-resources and "
             "authorize-project with an approved run, jobConfigHistory one CONFIGURE record (SPEC 6 #34/#36, SPEC 9)"),
    # e2e-23 R2-03 (bug-hunt batch A, D-80). r23/home_copy.py arranges its own item (r23-cron), copies its directory
    # in the container and disables the job at its end; it waits for one timer minute.
    Unit("r23-home-copy", 1.6, [("r23-home-copy", py("r23/home_copy.py"))], group="crawls and multibranch",
         doc="e2e-23 R2-03: a cron job's activation survives a cp -a copy of its directory (new inode) and a reload: the "
             "page still says activated, the next timer run starts, no TRIGGER_BLOCKED record"),
    Unit("r19-triggers", 6.0, [("r19-arrange", py("r19/arrange.py")), ("r19-triggers", py("r19/triggers.py"))],
         doc="e2e-19: a real cron schedule (blockTimer, TRIGGER_BLOCKED coalescing, activation, hold) and a real "
             "parameterized-trigger upstream (blockUpstream, allow list) (SPEC 6, 6a)"),
    # Section M (gap M11, 2026-10-31: the EXPIRING, INVALIDATED and run APPROVED mails) waits for two passes of the
    # plugin's one-minute periodic work: 97 s on a fresh stack through ci/run.sh (local, 2026-11-01; lifecycle step 213 s
    # with M), real time that does not shrink on the runner, so the weight went from 2.5 to 4.1.
    Unit("r19-lifecycle", 4.1, [("r19-arrange", py("r19/arrange.py")), ("r19-lifecycle", py("r19/lifecycle.py"))],
         doc="e2e-19: request form validation, decision and cancel rules, approver change, self-approval, approved-run "
             "expiry, disabled job, missing Build notice, and the mails each step sends (SPEC 2, 3, 4, 7, 12); the "
             "EXPIRING reminder exactly once, INVALIDATED and run APPROVED mails (SPEC 13, gap M11)"),
    # e2e-22 R2-05 (bug-hunt batch B). r22/cancelled_notice.py arranges its own items (r22-cancelq, r22-cancelq-dis).
    Unit("r22-cancelled-notice", 0.5, [("r22-cancelled-notice", py("r22/cancelled_notice.py"))],
         doc="e2e-22 R2-05: an approved run whose queue item was cancelled (from the queue, or by disabling the job) is not "
             "shown as starting shortly; the request page says the queued run was cancelled and will not start"),
    # e2e-27 D3 (Wave D, #18.5, #6 and #18.1). r27/decision_tables.py arranges its own items (r27-d3-*), runs each once per
    # pass (one aborted by admin), appends one WEIRD run record (r27-d3-ghost#7) once, disables r27-d3-off and enables it
    # again, sets every Batch Control setting but the switches to other values and puts them back, reverts to the plain
    # strategy once and migrates back, and cancels what it leaves pending. Section Z (screenshots in two themes and two
    # widths for the owner) is not run here: `python r27/decision_tables.py Z` after the others. Weight: 39 to 46 s in
    # two runs through ci/run.sh with sections S and D and the hover guard (one on a fresh stack, one in its group;
    # 2026-10-16, local).
    Unit("r27-decision-tables", 0.8, [("r27-decision-tables", py("r27/decision_tables.py", "AOPJBREXTLICSDQ"))],
         group="job ui params and restart",
         doc="e2e-27 D3: the run request page's Decision right after Parameters with one comment box and Approve/Reject "
             "in one form (approve, reject, refused reject and approval with the form following the refused verb, "
             "disabled job: Reject only), all in the browser; the 7-column run tables and 4-column incident tables with "
             "the icon/job/#n/label Run cell, the link rule per viewer, folded and cut parameters, no wrapping; the five "
             "setting groups on both configuration pages for admin and manager, saved through the real form"),
    # e2e-28 #1 follow-ups, one driver (r28/rerun_followups.py) in two units of different groups. The first re-creates
    # r28-rp, saves the matrix strategy and puts it back (it adds r28-alice and gives anonymous Overall/Read, Job/Read and
    # Run/Replay), switches change control off and on and puts it back, and revokes the windows it opens; r28-stale's
    # entry is taken out of Jenkins' item map for section S and put back, and r28-stale, r28-jobj and the folder
    # r28-team are deleted at the end. Weight: 23 s on a fresh stack with sections S and F (2026-10-18, local).
    Unit("r28-rerun-markers", 0.4, [("r28-rerun-markers", py("r28/rerun_followups.py", "PMNUSF"))], group="crawls and multibranch",
         doc="e2e-28 #1: re-runs of a marked run made while change control is off are marked (#1.1); a carried marker's "
             "record names the re-runner and the original replayer (#1.2); an anonymous re-run with an unresolvable "
             "source is recorded as unresolved (#1.5); a stale listed name can be marked reviewed by an administrator "
             "only (#1.4); the folder self-grant refusal names no Batch Control page and asks for direct Configure (#1.6)"),
    # The second re-creates r28-rq, r28-act and r28-dup and cancels the requests it leaves pending. Weight: 25 to 27 s
    # (a fresh stack and its group, 2026-10-17, local), counted as 0.4 so the group stays at 19.5 once r28-grant-blocked
    # (#44, 0.1) joins it.
    Unit("r28-request-followups", 0.4, [("r28-request-followups", py("r28/rerun_followups.py", "DI"))],
         group="job ui params and restart",
         doc="e2e-28 #1: a stale approver change on a decided request names its status (#1.7); an identical pending run "
             "request is not created twice, from the browser and from a script (#1.9)"),
    # e2e-28 #91. r28/incident_results.py saves both configuration pages as admin and manager (refused and accepted
    # values) and puts the settings back as they were. Weight: 9 to 20 s on a fresh stack (2026-10-18, local).
    Unit("r28-incident-results", 0.3, [("r28-incident-results", py("r28/incident_results.py"))], group="admin and role strategy",
         doc="e2e-28 #91: 'Results that open an incident' accepts only FAILURE, UNSTABLE and ABORTED: the live field check "
             "and the saves of Manage Jenkins > System (admin) and the Batch Control page (manager) refuse SUCCESS and "
             "NOT_BUILT, store nothing and record nothing; FAILURE, ABORTED and UNSTABLE, ABORTED are saved and recorded"),
    # e2e-24 #38 (wave A). r24/deleted_job.py deletes and re-creates its own items (r24-del-*) and cancels what it leaves
    # PENDING.
    Unit("r24-deleted-job", 1.4, [("r24-deleted-job", py("r24/deleted_job.py"))], group="crawls and multibranch",
         doc="e2e-24 #38: deleting a job, or a folder above it, invalidates its PENDING run requests naming the deletion; "
             "approving one is refused (browser and REST) and a job re-created under the name gets no build; guards"),
    Unit("r19-kinds", 1.0, [("r19-arrange", py("r19/arrange.py")), ("r19-kinds", py("r19/kinds.py")),
                            ("r19-guard", py("r19/guard.py"))],
         doc="e2e-19: matrix project and organization folder windows, credentials and run parameters through an approved "
             "run, rerun of a deleted build; the self-grant guard's 403 page with matrix-auth's form (SPEC 8, 5, 11, 2)"),
    # e2e-23 R2-02 and R2-01 (bug-hunt batch A, D-82). r23/matrix_children.py arranges its own item (r23-mx).
    Unit("r23-matrix-children", 0.8, [("r23-matrix-children", py("r23/matrix_children.py"))],
         group="job ui windows and disk",
         doc="e2e-23 R2-02/R2-01: an approved run of an activated matrix project runs its configurations; a configuration "
             "has no activation or request UI of its own and points to its parent; its direct build is refused"),
    # e2e-25 #45 (Wave C-UI). r25/pager_params.py arranges its own folder of 51 jobs (r25-pg, label r25-nowhere) and
    # account (r25-pager), files over 150 requests of each kind and, at its end, cancels the pending ones, revokes the
    # windows and cancels the queued runs. Weight: 1.3 min on a fresh stack (2026-10-10, local; 0.9 min of it the
    # arrangement: 51 jobs created over REST, then 714 REST calls), with room for the slower runner.
    Unit("r25-pager-params", 2.0, [("r25-pager-params", py("r25/pager_params.py"))], group="job ui windows and disk",
         doc="e2e-25 #45: paging one list on the Run Requests, Grants or Activations page keeps the other lists' page "
             "parameters and pages; guards: the own list pages, every list honours its parameter, markup in a "
             "parameter is escaped"),
    # e2e-26 #34 (wave B). r26/store_force.py re-creates its own item (r26-sf) and runs an strace sidecar
    # (r26/strace.Dockerfile, --pid=container:<jenkins> --cap-add SYS_PTRACE) only for its traced section; the Jenkins
    # container gets no extra capability. It revokes its window at its end.
    Unit("r26-store-force", 0.5, [("r26-store-force", py("r26/store_force.py"))], group="job ui windows and disk",
         doc="e2e-26 #34: fsync/fdatasync (strace) of the temporary file, the directory and the month file of a grant "
             "approval, a revocation, a run request and its decision and an activation decision; writes behave as before"),
    Unit("role", 6.0, [
        ("role-setup", py("r14/role/setup.py")),
        ("role-manage", py("r14/role/manage_roles.py")),
        ("role-assign", py("r14/role/assign_roles.py")),
        ("role-overlay", py("r14/role/grant_overlay.py")),
        ("role-endpoints", py("r14/role/endpoints.py")),
        ("role-naming", py("r19/naming.py")),  # e2e-19 G-27; re-applies profile-role.yaml at its end
        ("flag-role-crawl", flag("true", *ROLE_CRAWL)),
    ] + [(f"role-crawl-{r}", dict(py("r14/crawl.py", r, "new"), env={"BC_CRAWL_LOG": "crawl-role"})) for r in ROLE_CRAWL],
        last=True, doc="e2e-13/14 RS: role-strategy 927 profile, e2e-19 G-27 (RoleBasedProjectNamingStrategy with a CREATE "
            "window), then a crawl under it (replaces the strategy: last)"),
    # e2e-22 R2-04 (bug-hunt batch B). r22/rename_queue.py saves the authorization strategy, applies a Batch Control
    # role-based profile through JCasC for its own items (r22-team-a-*), and restores the strategy, the change-control
    # switch and the built-in node's labels exactly at its end (so it needs no `last`).
    # Since e2e-27 (2026-10-13) in the group "runs and switches" (it was in "admin and role strategy", which r27-titles-revert
    # would have taken to 20.1 min, above the heaviest group's 19.5); it restores what it changes, so its place is free.
    Unit("r22-rename-queue", 0.8, [("r22-rename-queue", py("r22/rename_queue.py"))], group="runs and switches",
         doc="e2e-22 R2-04: a rename by a user who loses Item/Read on the new name still invalidates the approved request "
             "and drops its queued run, which never builds once a node has the label; control: the administrator's rename"),
    # e2e-28 #35. r28/large_diff.py deletes and re-creates its own items (r28-big, r28-small, r28-huge, r28-control),
    # saves them through the configure form and REST, switches both switches off for one unrecorded reference save in the
    # script console and puts them back, and deletes its items at the end. Weight: 22 to 32 s in three runs through
    # ci/run.sh (two on a fresh stack, one in its group; 2026-10-17, local).
    Unit("r28-large-diff", 0.6, [("r28-large-diff", py("r28/large_diff.py"))], group="crawl and ui checks",
         doc="e2e-28 #35: large job configurations (4,000 and 6,000 description lines) saved through the configure form "
             "and REST: one CONFIGURE record each, whose diff equals difflib's line by line and reads the same for the "
             "auditor; the small change and the size-cap note unchanged; no OutOfMemoryError; the saving thread's "
             "allocation for the diff below 16 MB on the real controller"),
    # e2e-28 #44. r28/grant_blocked.py deletes and re-creates its own item (r28-gb), switches change control off for its
    # sections and puts it back, and cancels the window request it files with change control on. Weight: 5.5 to 6.6 s in
    # three runs through ci/run.sh (two on a fresh stack, one in its group; 2026-10-17, local).
    Unit("r28-grant-blocked", 0.1, [("r28-grant-blocked", py("r28/grant_blocked.py"))], group="job ui params and restart",
         doc="e2e-28 #44: with change control off an Approve-only user's POST to grants/create is refused (403) and writes "
             "no GRANT_REQUEST_BLOCKED record; RequestGrant and Manage holders still get the refusal and one record each; "
             "anonymous, no-crumb and GET write nothing; with change control on the normal flow is unchanged"),
    # e2e-26 #32 (wave B). r26/case_rename.py deletes and re-creates its own items (r26-Nightly, r26-Weekly, folders r26-Team
    # and r26-Ops), renames them through the script console, restarts Jenkins (last) and disables its timer jobs at its
    # end. On the Linux CI volume (case-sensitive) it is the guard of the scenario; with compose.ci-fs.yml on a macOS host
    # (case-insensitive store) it reproduces #32.
    Unit("r26-case-rename", 1.5, [("r26-case-rename", py("r26/case_rename.py"))], last=True, group="job ui other roles",
         doc="e2e-26 #32: after a case-only rename of a job and of a folder (Item.renameTo) and a restart, the jobs are still "
             "activated and their timer runs; the file system kind of the store is named in every check"),
    # e2e-27 D1 (T-UI-236 across a restart): r27/title_restart.py does not restart Jenkins itself; its `before` step
    # opens a window and renames its item, its `after` step reads the window's page after this unit's two restarts.
    # They took 4.3 s and 3.1 s (2026-10-13, local): the unit's weight went from 2.2 to 2.3.
    Unit("r16-durable", 2.3, [("r27-title-restart-before", py("r27/title_restart.py", "before")),
                              ("r16-arrange", py("r16/arrange.py")), ("r16-durable", py("r16/durable.py")),
                              ("r19-arrange", py("r19/arrange.py")), ("r19-restart", py("r19/restart.py")),
                              ("r27-title-restart-after", py("r27/title_restart.py", "after"))],
         last=True, doc="e2e-16: a window's end survives a failed grant write and a restart (6325e85); e2e-19: pending and "
                        "queued approved requests with typed values survive a restart and run once with the exact values; "
                        "restarts Jenkins, after which JCasC has reset the arrangement's permissions (last)"),
    Unit("r17-disk", 1.3, [("r17-arrange", py("r17/arrange.py")), ("r17-disk", py("r17/disk.py", "GFBU")),
                           ("r17-case", py("r17/disk.py", "C"))],
         last=True, doc="e2e-17: items removed on disk + reload (a rename onto the stale name: 'it could not follow its item'), "
                        "startup end of a vanished item, fail-closed restart re-end with an unreadable change log (S-39-02/03); "
                        "letter case after a restart (own step); reloads and restarts Jenkins (last)"),
    # e2e-18 (bd449cd..d696d5d). r18/arrange.py is idempotent and self-contained (account w18, items r18*).
    # Section O (gap H2, 2026-10-26): the switches-off checks of SPEC item 1 (r18/switches_off.py), between A and S. It
    # added about 40 s (local fresh runs: arrange 27 s, final 140 s with O), so the weight went from 2.2 to 2.8.
    Unit("r18-final", 2.8, [("r18-arrange", py("r18/arrange.py")), ("r18-final", py("r18/final.py", "KMDAOS"))],
         last=True, doc="e2e-18: creation-time saves (D-76 (2)), expiry notices of moved/unreadable windows (D-75 (1)), "
                        "approval refused while change control is off, recording baselines (D-76 (1)), switches off "
                        "(gap H2: core Build Now and no grant UI per switch), strategy migrate/revert refusals; switches "
                        "the switches and the authorization strategy (last)"),
    # e2e-23 R1-02 (bug-hunt batch A, D-81). r23/history_late.py arranges its own items (r23-late-slow, r23-late-fast),
    # shifts the plugin clock's zone and installs a holding RunListener, and restores both at its end.
    Unit("r23-history-late", 1.6, [("r23-history-late", py("r23/history_late.py"))], group="runs and switches",
         doc="e2e-23 R1-02: a run recorded late (held 65 s in onCompleted) does not hide the run recorded before it from "
             "the History listing of its day, which matches runs.csv"),
    # e2e-25 #42 (Wave C-UI). r25/run_links.py arranges its own items (r25-links-*), activates its matrix project
    # through the request flow and runs each job once per pass (approved requests).
    Unit("r25-run-links", 1.2, [("r25-run-links", py("r25/run_links.py"))], group="runs and switches",
         doc="e2e-25 #42: every run link on History, the Run Dashboard, Incidents and the incident pages resolves (200) "
             "to the run's own URL for a matrix configuration; guards: a Freestyle job, a job in a folder, the matrix "
             "parent and the request page's executed run"),
    # e2e-26 #33 (wave B). r26/month_zone.py writes its own items (r26-zone-*-<tag>, a new tag per run) with the plugin
    # clock fixed for a moment, and re-creates the Jenkins container in another zone and back with `docker compose` from
    # the container's labels (same JENKINS_HOME volume): two restarts (last).
    Unit("r26-month-zone", 1.0, [("r26-month-zone", py("r26/month_zone.py"))], last=True, group="crawls and multibranch",
         doc="e2e-26 #33: records written near a month boundary in one zone are listed for their day on the History runs "
             "and changes listings and in runs.csv/changes.csv after the controller restarts in another zone (both "
             "directions); the neighbouring day does not list them"),
    # e2e-21 (71d267b). r21/arrange.py is idempotent (item r21-reject).
    Unit("r21-reject-color", 0.3, [("r21-arrange", py("r21/arrange.py")), ("r21-reject-color", py("r21/reject_color.py", "RGA"))],
         doc="e2e-21: approver-1's Reject button on the run, permission window and activation request pages renders in "
             "var(--destructive-color) (computed colour vs a probe), the Approve button does not"),
    # e2e-27 D1 (Wave D, #18.4 and #18.7). r27/titles_revert.py arranges its own items (folder r27-d1, r27-d1-top-*, the
    # vault folder r27-d1-vault only administrators read, the multibranch project r27-d1/mb over repos/r27-d1.git, a
    # copy of the seed's repository with one more branch), files one window request on the seed's team-mb and cancels it,
    # cancels the requester's pending r27-d1 requests and revokes their windows, reverts the strategy through the moved
    # entry on both configuration pages and over REST and restores it through the monitor's migrate endpoint each time;
    # for its last section it saves the strategy, applies a role profile through JCasC and puts the saved strategy back
    # (as r22/rename_queue.py, so it needs no `last`). Weight: 190 to 199 s in four
    # runs on a fresh stack through ci/run.sh, four stacks at a time (2026-10-14, local, 827 checks).
    Unit("r27-titles-revert", 3.3, [("r27-titles-revert", py("r27/titles_revert.py"))], group="admin and role strategy",
         doc="e2e-27 D1: run, grant, activation and hold request pages titled 'Run request: <job>' etc. (title, heading, "
             "breadcrumb and its tooltip; four roles, folder and top-level, Pipeline and multibranch subjects, escaped odd "
             "names, branch jobs, classic flag, decided, executed and expired requests, refusals at the POST URL) with an "
             "ID row and core's copy button; a window's title follows its renamed or moved item for a reader and keeps the "
             "approved name for anyone else, also after the window ended; the revert entry inside the Batch Control section's Advanced control and last, on "
             "both configuration pages, under the matrix and the role strategy; guards: revert still works and keeps "
             "every entry, Administer-only (403/405), a Manage holder is told so without Advanced"),
    # e2e-27 D1 (#18.7 for Overall/Manage and Overall/SystemRead). r27/revert_permissions.py saves the strategy, adds four
    # accounts (r27-ovm*, r27-sysread*), switches Overall/SystemRead on for its pass when the stack was not started with
    # compose.permissions.yml, and puts everything back (no `last`). Weight: 12.2 to 14.2 s on a fresh stack
    # (2026-10-13, local, 34 checks).
    Unit("r27-revert-permissions", 0.2, [("r27-revert-permissions", py("r27/revert_permissions.py"))],
         # other accounts' view of the configuration pages; was in "job ui other roles" until r26-case-rename (1.5, last)
         # joined it on 2026-10-17 and took it to 20.0: moved with r22-grant-monitor to the lightest group
         group="crawl and ui checks",
         doc="e2e-27 D1: holders of Overall/Manage and Overall/SystemRead (with and without BatchControl/Manage) get "
             "nothing of the revert on Manage Jenkins > System and the Batch Control page, no Advanced control, the "
             "administrator-only sentence where the section is shown, 403 on the endpoint; a save of System by an "
             "Overall/Manage holder changes neither the strategy nor the Batch Control settings"),
    # e2e-25 #41 (Wave C-UI). r25/breadcrumb_menu.py arranges its own folder, job and accounts (r25-team, r25-folder-*)
    # and cancels the folder requester's pending request at its end.
    Unit("r25-breadcrumb-menu", 0.6, [("r25-breadcrumb-menu", py("r25/breadcrumb_menu.py"))],
         group="crawls and multibranch",
         doc="e2e-25 #41: the Batch Control context menu lists exactly the tab bar's sections for a requester with "
             "Request on one folder only (JSON and the breadcrumb dropdown); guards: admin, approver-1, and no menu for "
             "nobc and a folder requester without requests of their own"),
    # e2e-22 R3-04 (bug-hunt batch B). r22/grant_monitor.py arranges its own item (r22-monitor) and holds the GrantService
    # monitor for 10 s from the script console while the requester's REST requests run; REST only, no screenshot.
    Unit("r22-grant-monitor", 0.3, [("r22-grant-monitor", py("r22/grant_monitor.py"))],
         doc="e2e-22 R3-04: while another thread holds the GrantService monitor, the requester's job lists and an item "
             "answer at once with the baseline content and no permission check is BLOCKED on it (T-08-196 end to end)"),
    # Gap H3 (upgrade from the released plugin). h3/upgrade.py starts a controller of its own (h3/compose.yml: own compose
    # project bc-h3-<shard>, container, JENKINS_HOME volume and port BC_PORT + 100) with the released .hpi (pinned sha256,
    # BC_RELEASED_HPI or downloaded into ci/.cache/released/), makes data there, re-creates it with target/batch-control.hpi
    # on the same volume and removes it at its end. It never touches the shard's Jenkins, so it is not `last` (every group
    # already has one). Group: the restart theme of "job ui params and restart". Weight: 97 s in a shard through ci/run.sh
    # next to the shard's Jenkins, 113 and 130 s alone (2026-10-31, local); most of it is two controller boots and two
    # waits for a cron minute (up to 60 s each), so 2.5 leaves room for a slower runner.
    Unit("h3-upgrade", 2.5, [("h3-upgrade", py("h3/upgrade.py"))], group="job ui params and restart",
         doc="gap H3: a controller upgraded from batch-control 1414.vc347803c1997 to this build loads its data (plugin "
             "active at the new version, no XStream/class error or Batch Control warning in the log, no old-data entry; "
             "strategy, configuration, every request, window, activation, incident, page status and CSV row as before) "
             "and keeps working: the queued approved run starts once, the activated cron job's timer fires after the "
             "upgrade (SPEC 6a), the pending request runs, the window still confers, pending requests can be decided"),
]
BY_NAME = {u.name: u for u in UNITS}
ORDER = {u.name: i for i, u in enumerate(UNITS)}

# The CI jobs: fixed, named groups of units, one GitHub Actions job each, named "e2e (<label>)" (.github/workflows/e2e.yml
# reads `shard.py matrix`). Group k is shard k of N = len(GROUPS): `run.sh k/N`, `--bc-shard k/N`, the artefact
# e2e-shard-<k> and ci/out/<k>/ keep the index. A label is lower-case words of letters and digits separated by single
# spaces or '-', at most 25 characters (the rule of .github/test-shards.txt), and says what the group tests. Each role's
# job-UI units stay together, so a red job also tells whose screens broke. `shard.py check` (and `matrix`, which the
# workflow's plan job reads) fails when a unit is in no group or in two, when a group holds two `last` units or a name
# that is no unit, or when a label is not valid; it prints the weight per group and the heaviest group against an automatic split of the
# same weights, so the balance stays visible. A new unit: add it to UNITS and to the group whose label it fits.
# Seven groups (2026-10-09), drawn on the CI-calibrated weights. The five before them took 26.9, 26.8, 26.8, 19.8 and
# 35.0 min per job in run 37883661045 (E2E wall clock 36.7). The floor is approver-1/manager/nobc's job UI (16.6 min of
# units, kept together by the rule above); every other group stays near it. Weight sums: 15.6, 13.1, 15.2, 16.7, 13.9,
# 16.6, 13.7 (+ SETUP_WEIGHT + JOB_OVERHEAD = about 23.3 min for the longest job); the longest-processing-time split of
# the same weights into 7 has 15.1 at most, but splits each role's job UI and mixes unrelated units.
GROUPS = [
    ("crawl and ui checks", [  # requester's and manager's crawls, the DEF-07 recheck, every control per role, the
        "def07", "crawl-requester", "crawl-manager", "r22-approver-case",  # e2e-12/14 targeted checks, round 3, the Reject colour
        "actions", "misc", "targeted", "round3", "r21-reject-color", "r22-grant-monitor"]),
    ("crawls and multibranch", [  # the crawls of admin, reqonly, approver-1 and nobc, the multibranch job pages
        "crawl-admin", "crawl-reqonly", "crawl-approver-1", "crawl-nobc", "multibranch", "r22-cancelled-notice"]),
    ("admin and role strategy", [  # admin's job UI, then the role-strategy profile and its crawl (last)
        "jobui-new-admin", "jobui-classic-admin", "role"]),
    ("job ui params and restart", [  # requester's job UI, requests with typed values across a restart (last)
        "jobui-new-requester", "jobui-classic-requester", "r16-durable"]),
    ("job ui windows and disk", [  # reqonly's job UI, permission windows on items and renames, item kinds, items
        "jobui-new-reqonly", "jobui-classic-reqonly", "r22-nonascii-reason",  # removed on disk with reload and restart (last)
        "r16-items", "r16-rename", "r16-follow", "r16-names", "r17-s39", "r19-kinds", "r17-disk"]),
    ("job ui other roles", [  # the job UI of approver-1, manager and nobc; typed parameters (r16-params arranges its
        "jobui-new-others", "jobui-classic-others",  # own items; moved here on 2026-10-14 when the D2 unit's 2.8 min
        "r16-params"]),  # would have taken "job ui params and restart" to 19.6, above the heaviest 19.5; r22-grant-monitor
    # and r27-revert-permissions left on 2026-10-17 for "crawl and ui checks" when r26-case-rename (last) joined
    ("runs and switches", [  # the run gate, reruns and the prefilled form, triggers, other plugins, the request
        "r16-rerun", "r16-d60",  # lifecycle, then the global switches and the strategy (last)
        "r19-gate", "r22-scripted-build", "r19-plugins", "r19-triggers", "r19-lifecycle", "r18-final"]),
]
# A unit may name its group in UNITS (Unit(..., group=<label>)) instead of being listed above: it joins the end of that
# group's list here, before anything reads GROUPS (a label that is no group's leaves the unit in no group, which `check`
# reports). The e2e-23 units use it, so that each of their PRs adds lines of its own to this file and none edits the
# group lists above, which other open PRs edit (git cannot merge two edits of neighbouring lines).
GROUPS = [(label, names + [u.name for u in UNITS if u.group == label]) for label, names in GROUPS]
LABEL_SYNTAX = re.compile(r"^[a-z0-9]+(?:[ -][a-z0-9]+)*$")
LABEL_MAX = 25

# Steps (as "<unit>:<step>") that are retried once when they fail (pytest: @pytest.mark.flaky(reruns=1) through
# pytest-rerunfailures; `shard.py run`: the same single retry). A step belongs here only with evidence of an intermittent
# failure (CI history or local runs, named in the comment) AND when its driver is safe to run twice on the same Jenkins
# (read-only, or idempotent arrangement). Every retry is listed in summary.md ("retried") and the JUnit XML, the failed
# attempt's log and traces are kept, so a retry never hides a failure silently. BC_FLAKY=a:b,c:d adds steps for one run.
FLAKY = set(
    # (empty: no step has failed intermittently in the e2e-17..e2e-20 runs or the CI history of e2e.yml; the one CI
    # failure, round3 F "no h-scroll at 1280", was deterministic on the runner's fonts and is fixed in the workflow)
)
FLAKY |={x.strip() for x in os.environ.get("BC_FLAKY", "").split(",") if x.strip()}


def parse_shard(s):
    m = re.fullmatch(r"(\d+)/(\d+)", s or "")
    if not m or not (1 <= int(m.group(1)) <= int(m.group(2))):
        raise SystemExit(f"shard must be <k>/<N> with 1 <= k <= N, got {s!r}")
    return int(m.group(1)), int(m.group(2))


def group_errors(groups=None):
    """Everything wrong with GROUPS (an empty list when every unit is in exactly one group, ...)."""
    groups = GROUPS if groups is None else groups
    errors = [] if groups else ["GROUPS defines no group"]
    labels, where = {}, {}
    for i, (label, names) in enumerate(groups, 1):
        if not isinstance(label, str) or len(label) > LABEL_MAX or not LABEL_SYNTAX.match(label):
            errors.append(f"group {i}: label {label!r} must be lower-case words of letters and digits separated by "
                          f"single spaces or '-', at most {LABEL_MAX} characters")
        elif label in labels:
            errors.append(f"group {i}: label {label!r} is already the label of group {labels[label]}")
        else:
            labels[label] = i
        if not names:
            errors.append(f"group {i} ({label}) has no units")
        for name in names:
            if name not in BY_NAME:
                errors.append(f"group {i} ({label}): {name!r} is not a unit (shard.py units lists them)")
            where.setdefault(name, []).append(i)
        lasts = [name for name in names if name in BY_NAME and BY_NAME[name].last]
        if len(lasts) > 1:
            errors.append(f"group {i} ({label}) holds {len(lasts)} `last` units ({', '.join(lasts)}): at most one per "
                          f"group, because each replaces the strategy or restarts Jenkins and must run last")
    for u in UNITS:
        gs = where.get(u.name, [])
        if not gs:
            errors.append(f"unit {u.name} is in no group (add it to the GROUPS entry whose label it fits)")
        elif len(gs) > 1:
            twice = len(set(gs)) == 1
            errors.append(f"unit {u.name} is listed {len(gs)} times in group {gs[0]}" if twice else
                          f"unit {u.name} is in more than one group: {', '.join(map(str, gs))}")
    return errors


def checked_groups():
    errors = group_errors()
    if errors:
        raise SystemExit("ci/shard.py GROUPS is inconsistent (shard.py check):\n  " + "\n  ".join(errors))
    return GROUPS


def group_for(k, n):
    groups = checked_groups()
    if n != len(groups):
        raise SystemExit(f"shard {k}/{n}: N must be the number of groups, {len(groups)}, so <k>/{len(groups)} with "
                         f"1 <= k <= {len(groups)} (shard.py check lists them)")
    return groups[k - 1]


def group_units(names):
    return sorted((BY_NAME[x] for x in names), key=lambda u: (u.last, ORDER[u.name]))


def bc_units():
    names = [x.strip() for x in os.environ.get("BC_UNITS", "").split(",") if x.strip()]
    unknown = [x for x in names if x not in BY_NAME]
    if unknown:
        raise SystemExit(f"unknown units {unknown}; known: {list(BY_NAME)}")
    return names


def units_for(k, n):
    _, names = group_for(k, n)
    return group_units(bc_units() or names)


def label_for(k, n):
    label, _ = group_for(k, n)
    return f"BC_UNITS {','.join(bc_units())}" if bc_units() else label


def weight(names):
    return sum(BY_NAME[x].weight for x in names)


def job_minutes(names):
    """The estimated minutes of the group's CI job: its units, the setup and the runner's own steps."""
    return weight(names) + SETUP_WEIGHT + JOB_OVERHEAD


def lpt_loads(n):
    """The automatic split the groups replaced (deterministic longest-processing-time over the weights, at most one
    `last` unit per shard), kept only to compare the balance in `shard.py check`: its shard loads without the setup."""
    load = [0.0] * n
    lasts = [0] * n
    for u in sorted(UNITS, key=lambda u: (-u.weight, ORDER[u.name])):
        i = min((i for i in range(n) if not (u.last and lasts[i])), key=lambda i: (load[i], i))
        load[i] += u.weight
        lasts[i] += u.last
    return load


def matrix():
    groups = checked_groups()
    return {"include": [{"shard": i, "of": len(groups), "label": label} for i, (label, _) in enumerate(groups, 1)]}


def check():
    errors = group_errors()
    for e in errors:
        print(f"::error::{e}")
    if errors:
        return 1
    loads = [weight(names) for _, names in GROUPS]
    lpt = max(lpt_loads(len(GROUPS)))
    lines = ["| Shard | Job label | Minutes | Job minutes | `last` unit | Units |", "|---:|---|---:|---:|---|---|"]
    for i, (label, names) in enumerate(GROUPS, 1):
        last = next((x for x in names if BY_NAME[x].last), "")
        lines.append(f"| {i}/{len(GROUPS)} | {label} | {loads[i - 1]:.1f} | {job_minutes(names):.1f} | {last} | "
                     f"{', '.join(u.name for u in group_units(names))} |")
    lines.append(f"| | total | {sum(loads):.1f} | | | {len(UNITS)} units |")
    note = (f"heaviest group {max(loads):.1f} min of units (job estimate with {SETUP_WEIGHT} setup and {JOB_OVERHEAD} "
            f"runner overhead: {max(job_minutes(n) for _, n in GROUPS):.1f}); an automatic split of the same weights "
            f"into {len(GROUPS)} would have {lpt:.1f} at most")
    print("\n".join(lines))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("### e2e groups\n\n" + "\n".join(lines) + f"\n\n{note[0].upper() + note[1:]}.\n")
    print(f"OK: {len(UNITS)} units, each in exactly one of {len(GROUPS)} groups, at most one `last` unit per group; "
          f"{note}")
    return 0


def steps_for(units):
    steps = [("setup:" + name, spec) for name, spec in SETUP]
    if os.environ.get("BC_SKIP_SETUP") == "1":
        steps = []
    for u in units:
        steps += [(f"{u.name}:{name}", spec) for name, spec in u.steps]
    return steps


# ------------------------------------------------------------------ verdicts
FAIL_LINE = re.compile(r"^FAIL ", re.M)
OK_FALSE = re.compile(r'"ok": false')
EXCEPTION = re.compile(r'"EXCEPTION": ')
PASS_LINE = re.compile(r"^PASS ", re.M)
STALE_LINE = re.compile(r"^STALE allow (\S+)", re.M)
ALLOW_LINE = re.compile(r"^ALLOW (\S+) matched (\d+)", re.M)


def r15_rows(before):
    """check.jsonl rows that a r15/check.py step appended (the file had `before` lines when the step started)."""
    f = E2E / "r15" / "out" / "check.jsonl"
    if not f.exists():
        return []
    return [json.loads(l) for l in f.read_text().splitlines()[before:] if l.strip()]


# r15/check.py writes "ok": false as a hint for the reviewer (e2e-15 judged each row). The runner judges a row from its
# data, leaving out exactly these known or provoked items (everything else still fails). Repeats inside core's
# side-panel widgets (build history, Build Queue, Build Executor Status) are not duplicate problems at all: check.py
# leaves them out by DOM containment (ci/h1_rules.py r15_duplicates), not by their text.
def r15_problems(row):
    left = []
    for p in row.get("problems", []):
        if p.get("kind", "").startswith("known_core"):
            continue  # r15/check.py's own classification (core GET 405/404, e2e-15 Known)
        left.append(p)
    provoked = row.get("step") == "submit"  # the empty submit first: the form re-renders with errors as HTTP 400
    bad = [b for b in row.get("bad_responses", []) if not (provoked and re.match(r"400 POST .*/batch-control-activation/submit", b))]
    # "Refused to execute script from": core's DialogEvent MIME line (D-70, e2e-11 UX 2), r15's own KNOWN_CONSOLE.
    # "reading 'replace'": core header.js breadcrumb overflow (xmlEscape <- menuItem), e2e-13 U-1, intermittent; e2e-14
    # confirmed it by stack (round3.py's known_core). r15 records no stack, but the plugin's own scripts
    # (ui/dialogOpener.js, ui/getForm.js) call no replace(), so the message cannot come from plugin code.
    cons = [c for c in row.get("console", []) if not (provoked and "status of 400" in c)
            and "Refused to execute script from" not in c and "Cannot read properties of undefined (reading 'replace')" not in c]
    if row.get("step") == "submit" and row.get("form") and not (row.get("uuid") and row.get("server_detail_status") == 200):
        left.append({"kind": "submit did not land on a stored activation request"})
    return left + [{"kind": "response", "detail": b} for b in bad] + [{"kind": "console", "detail": c} for c in cons]


def verdict(rc, text, timed_out, extra=None):
    reasons = []
    if timed_out:
        reasons.append("timeout")
    elif rc != 0:
        reasons.append(f"exit {rc}")
    lines = text.splitlines()
    fails = [l for l in lines if l.startswith("FAIL ")]
    if fails:
        reasons.append(f"{len(fails)} FAIL line(s)")
    okf = [] if extra is not None else [l for l in lines if OK_FALSE.search(l) and not l.startswith(("FAIL ", "PASS "))]
    if okf:
        reasons.append(f'{len(okf)} "ok": false row(s)')
    exc = [l for l in lines if EXCEPTION.search(l)]
    if exc:
        reasons.append(f"{len(exc)} EXCEPTION row(s)")
    if extra:
        reasons.append(f"{len(extra)} r15 problem(s)")
    return ("FAIL" if reasons else "PASS"), reasons, (fails + okf + exc + (extra or []))[:30], len(PASS_LINE.findall(text))


def observations():
    """Rows the observational drivers logged that a reviewer should look at (not a verdict)."""
    obs = {}
    out = E2E / "r14" / "out"

    def rows(name):
        f = out / name
        if not f.exists():
            return []
        res = []
        for line in f.read_text().splitlines():
            try:
                res.append(json.loads(line))
            except ValueError:
                pass
        return res

    for log in ("crawl.jsonl", "crawl-role.jsonl"):
        r = rows(log)
        if r:
            obs[log] = {
                "page_loads": sum(1 for x in r if x.get("kind") == "page"),
                "control_checks": sum(1 for x in r if x.get("kind") not in ("page", "content")),
                "dialog_cycles": sum(1 for x in r if x.get("kind") == "click" and x.get("result") == "dialog"),
                "nothing_or_click_failed": sum(1 for x in r if x.get("result") in ("NOTHING", "click-failed")),
                "broken_links": sum(1 for x in r if x.get("result") == "BROKEN"),
                "pages_with_console_errors": sum(1 for x in r if x.get("kind") == "page" and x.get("console")),
                "content_defects": sum(1 for x in r if x.get("kind") == "content" and x.get("result") == "DEFECT"),
                "empty_optional_columns": sorted({f'{x.get("pattern")} {x.get("key")}' for x in r if x.get("check") == "empty-optional"})[:80],
                # e2e-16: raw enum values shown as text (INFO, the deferred UX-4/UX-5 items), per value with its URL patterns
                "raw_enum_values": {k: sorted({x.get("pattern") for x in r if x.get("check") == "raw-enum" and x.get("key") == k})[:12]
                                    for k in sorted({x.get("key") for x in r if x.get("check") == "raw-enum"})},
                # #18.2: result cells read (once per URL pattern and text) and the raw result words found in them
                "result_cells": {"read": sum(1 for x in r if x.get("check") == "result-cell"),
                                 "texts": sorted({x.get("key") for x in r if x.get("check") == "result-cell"})[:20],
                                 "raw": sum(1 for x in r if x.get("check") == "raw-result")},
                # D1 follow-up: pages whose core Advanced controls the crawl pressed before checking them again
                "advanced_expanded": sorted({f'{x.get("page")} ({x.get("pressed")})' for x in r if x.get("kind") == "advanced"})[:20],
            }
    a = rows("actions.jsonl")
    if a:
        obs["actions.jsonl"] = {"rows": len(a), "exception": sum(1 for x in a if x.get("exception")),
                                "status_ge_400": sum(1 for x in a if (x.get("status") or 0) >= 400)}
    j = rows("jobui.jsonl")
    if j:
        obs["jobui.jsonl"] = {"entries": sum(1 for x in j if x.get("kind") == "entry"),
                              "exception": sum(1 for x in j if x.get("exception")),
                              "nothing": sum(1 for x in j if x.get("result") == "NOTHING")}
    return obs


SESSIONS = re.compile(r"^SESSIONS logins=(\d+) reused=(\d+)", re.M)


class Runner:
    """Runs the steps of one shard one by one (ci/shard.py run, or one pytest test per step: ci/test_shard.py).

    Per step: the driver as a subprocess with its log in logs/, the verdict from its exit code and output, a Playwright
    trace per browser context in traces/<idx>-<step>/ kept only when the step did not pass (BC_TRACE=off: no traces),
    and the login state shared by the shard's steps in a private temporary directory (BC_AUTH_DIR, never in the
    artefacts; see r6/lib.py Session). finish() writes summary.json and summary.md."""

    def __init__(self, k, n, outdir):
        self.k, self.n = k, n
        self.outdir = Path(outdir)
        self.logs = self.outdir / "logs"
        self.logs.mkdir(parents=True, exist_ok=True)
        self.units = units_for(k, n)
        self.label = label_for(k, n)
        self.steps = steps_for(self.units)
        self.pyexe = os.environ.get("PY", sys.executable)
        self.timeout = int(os.environ.get("BC_STEP_TIMEOUT", "2700"))
        self.trace_mode = os.environ.get("BC_TRACE", "retain-on-failure")  # retain-on-failure | on (keep all) | off
        self.trace = self.trace_mode != "off"
        self.auth_dir = Path(tempfile.mkdtemp(prefix=f"bc-auth-{k}-"))
        self.results = {}
        self.attempts = {}
        self.setup_failed = False
        self.t_all = time.time()

    def plan_line(self):
        return f"shard {self.k}/{self.n} ({self.label}): units {[u.name for u in self.units]} ({len(self.steps)} steps)"

    def run_step(self, idx, name, spec):
        attempt = self.attempts.get(idx, 0) + 1
        self.attempts[idx] = attempt
        base = f"{idx:02d}-{name.replace(':', '-').replace('/', '_')}"
        log = self.logs / (base + (f".attempt{attempt}" if attempt > 1 else "") + ".log")
        if attempt > 1 and name.startswith("setup:"):
            self.setup_failed = False  # the retry of the setup step that failed decides again
        if self.setup_failed:
            res = {"step": name, "verdict": "BLOCKED", "reasons": ["setup failed"], "seconds": 0, "log": log.name}
            self.results[idx] = res
            return res
        tdir = self.outdir / "traces" / (base + (f".attempt{attempt}" if attempt > 1 else ""))
        env = dict(os.environ, PYTHONUNBUFFERED="1", BC_AUTH_DIR=str(self.auth_dir), **spec.get("env", {}))
        if self.trace:
            env["BC_TRACE_DIR"] = str(tdir)
        is_r15 = spec["argv"][0] == "r15/check.py"
        r15_before = len(r15_rows(0)) if is_r15 else 0
        argv = [self.pyexe] + spec["argv"]
        t0 = time.time()
        timed_out = False
        with open(log, "w") as fh:
            fh.write("$ " + " ".join(spec["argv"]) + "\n")
            fh.flush()
            try:
                p = subprocess.run(argv, cwd=E2E, env=env, stdout=fh, stderr=subprocess.STDOUT, timeout=self.timeout)
                rc = p.returncode
            except subprocess.TimeoutExpired:
                rc, timed_out = -1, True
        secs = round(time.time() - t0, 1)
        text = log.read_text(errors="replace")
        extra = None
        if is_r15:
            extra = [json.dumps({"user": r.get("user"), "flag": r.get("flag"), "page": r.get("page"), "problem": p})[:400]
                     for r in r15_rows(r15_before) for p in r15_problems(r)]
            extra += [json.dumps({"page": r.get("page"), "status": r.get("status"), "expected": 404})
                      for r in r15_rows(r15_before) if "expected" in r and r.get("status") != r["expected"]]
        v, reasons, lines, passes = verdict(rc, text, timed_out, extra)
        traces = sorted(tdir.glob("*.zip")) if tdir.exists() else []
        recorded = len(traces)
        if v == "PASS" and tdir.exists() and self.trace_mode != "on":
            shutil.rmtree(tdir, ignore_errors=True)  # retain-on-failure
            traces = []
        sess = [tuple(map(int, m)) for m in SESSIONS.findall(text)]
        stale = sorted(set(STALE_LINE.findall(text)))
        matched = {k: int(n) for k, n in ALLOW_LINE.findall(text)}
        prev = self.results.get(idx)
        res = {"step": name, "verdict": v, "reasons": reasons, "seconds": secs, "log": log.name,
               "pass_lines": passes, "failure_lines": lines, "attempt": attempt,
               "traces": [str(t.relative_to(self.outdir)) for t in traces], "traces_recorded": recorded,
               "logins": sum(a for a, _ in sess), "logins_reused": sum(b for _, b in sess),
               "stale_allow": stale, "allow_matched": matched}
        if prev and attempt > 1:
            res["earlier_attempts"] = prev.get("earlier_attempts", []) + [
                {k: prev[k] for k in ("verdict", "reasons", "seconds", "log", "traces")}]
            if v == "PASS":
                res["reasons"] = [f"passed on attempt {attempt} (flaky step, retried)"]
        self.results[idx] = res
        print(f"[{time.strftime('%H:%M:%S')}] {v:4} {secs:7.1f}s {name} {'; '.join(res['reasons'])}"
              f"{f' (traces: {len(traces)})' if traces else ''}", flush=True)
        if v != "PASS" and name.startswith("setup:"):
            self.setup_failed = True
        return res

    def finish(self):
        shutil.rmtree(self.auth_dir, ignore_errors=True)
        results = [self.results[i] for i in sorted(self.results)]
        total = round(time.time() - self.t_all, 1)
        matched = {k for r in results for k in r.get("allow_matched", {})}
        stale = sorted({k for r in results for k in r.get("stale_allow", [])} - matched)
        summary = {"shard": f"{self.k}/{self.n}", "label": self.label, "units": [u.name for u in self.units],
                   "seconds": total,
                   "steps": results, "observations": observations(),
                   "failed": [r["step"] for r in results if r["verdict"] != "PASS"],
                   "retried": [r["step"] for r in results if r.get("attempt", 1) > 1],
                   "steps_traced": sum(1 for r in results if r.get("traces_recorded")),
                   "traces_kept": sum(len(r.get("traces", [])) + sum(len(e.get("traces", [])) for e in r.get("earlier_attempts", []))
                                      for r in results),
                   "logins": sum(r.get("logins", 0) for r in results),
                   "logins_reused": sum(r.get("logins_reused", 0) for r in results),
                   # H1: allowlist entries (ci/h1_rules.py) that applied in a step of this shard and matched nothing in
                   # any step: a warning, not a failure (the core issue may be fixed, the entry may have to go)
                   "stale_allow": stale}
        (self.outdir / "summary.json").write_text(json.dumps(summary, indent=1))
        (self.outdir / "summary.md").write_text(markdown(summary))
        return 1 if summary["failed"] else 0


def run(k, n, outdir):
    r = Runner(k, n, outdir)
    print(r.plan_line(), flush=True)
    for idx, (name, spec) in enumerate(r.steps, 1):
        res = r.run_step(idx, name, spec)
        if res["verdict"] == "FAIL" and name in FLAKY:
            r.run_step(idx, name, spec)
    return r.finish()


def markdown(s):
    out = [f"### e2e ({s.get('label', '')}), shard {s['shard']}: {'FAIL' if s['failed'] else 'PASS'}", "",
           f"Units: {', '.join(s['units'])}. Steps: {len(s['steps'])}, failed: {len(s['failed'])}, "
           f"retried: {len(s.get('retried', []))}, driver time {s['seconds'] / 60:.1f} min, "
           f"logins {s.get('logins', 0)} (reused sessions {s.get('logins_reused', 0)}), steps traced "
           f"{s.get('steps_traced', 0)} (traces kept for failed steps: {s.get('traces_kept', 0)}).", "",
           "| Step | Result | Seconds | Notes |", "|---|---|---|---|"]
    for r in s["steps"]:
        notes = "; ".join(r["reasons"]) or (f"{r['pass_lines']} PASS lines" if r.get("pass_lines") else "")
        if r.get("traces"):
            notes += f" (Playwright traces: `{Path(r['traces'][0]).parent}/`)"
        out.append(f"| `{r['step']}` | {r['verdict']} | {r['seconds']} | {notes} |")
    fails = [r for r in s["steps"] if r.get("failure_lines")]
    if fails:
        out += ["", "#### Failure lines (first 30 per step)", ""]
        for r in fails:
            out.append(f"`{r['step']}` (`logs/{r['log']}`):")
            out.append("```")
            out += [l[:400] for l in r["failure_lines"]]
            out.append("```")
    if s.get("stale_allow"):
        out += ["", "#### Stale allowlist entries (warning, not a failure)", "",
                "Entries of `ci/h1_rules.py` that applied to a step of this shard and matched nothing in any step: "
                + ", ".join(f"`{x}`" for x in s["stale_allow"]) + "."]
    if s.get("observations"):
        out += ["", "#### Observational drivers (for review, not a verdict)", "", "```", json.dumps(s["observations"], indent=1), "```"]
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("units")
    sub.add_parser("check")
    sub.add_parser("matrix")
    p = sub.add_parser("label")
    p.add_argument("shard")
    p = sub.add_parser("plan")
    p.add_argument("shard")
    p.add_argument("--json", action="store_true")
    p = sub.add_parser("plan-all")
    p.add_argument("n", type=int, nargs="?")
    p = sub.add_parser("run")
    p.add_argument("shard")
    p.add_argument("--out", required=True)
    a = ap.parse_args()
    if a.cmd == "units":
        group_of = {x: label for label, names in GROUPS for x in names}
        print(f"setup ({SETUP_WEIGHT} min, every shard): " + ", ".join(n for n, _ in SETUP))
        for u in UNITS:
            print(f"{u.name:16} {u.weight:5.1f} min{' (last)' if u.last else ''}  [{group_of.get(u.name, 'no group')}]  "
                  f"{u.doc}")
            for name, spec in u.steps:
                print(f"    {name:26} {' '.join(spec['argv'])}{'  ' + str(spec['env']) if spec.get('env') else ''}")
    elif a.cmd == "check":
        sys.exit(check())
    elif a.cmd == "matrix":
        print(json.dumps(matrix(), separators=(",", ":")))
    elif a.cmd == "label":
        k, n = parse_shard(a.shard)
        print(label_for(k, n))
    elif a.cmd == "plan":
        k, n = parse_shard(a.shard)
        units = units_for(k, n)
        steps = steps_for(units)
        if a.json:
            print(json.dumps({"shard": a.shard, "label": label_for(k, n), "units": [u.name for u in units],
                              "steps": [{"step": s, "argv": spec["argv"]} for s, spec in steps]}, indent=1))
        else:
            print(f"shard {k}/{n} ({label_for(k, n)}): {[u.name for u in units]}")
            for s, spec in steps:
                print(f"  {s:34} {' '.join(spec['argv'])}")
    elif a.cmd == "plan-all":
        n = len(checked_groups())
        if a.n is not None and a.n != n:
            raise SystemExit(f"plan-all {a.n}: there are {n} groups (shard.py check lists them)")
        for i, (label, names) in enumerate(GROUPS, 1):
            print(f"shard {i}/{n} ({label}): ~{SETUP_WEIGHT + weight(names):.0f} min drivers "
                  f"(incl. {SETUP_WEIGHT:.0f} setup), ~{job_minutes(names):.0f} min job: "
                  f"{[u.name for u in group_units(names)]}")
    elif a.cmd == "run":
        k, n = parse_shard(a.shard)
        sys.exit(run(k, n, a.out))


if __name__ == "__main__":
    main()
