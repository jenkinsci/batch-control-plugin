# 진행 상태

메인 세션만 이 파일을 갱신한다. 최신 항목이 위.

형식:
```
## YYYY-MM-DD HH:MM — Phase N / 슬라이스 Sn
- 위임: <에이전트> ← <작업 한 줄>
- 결과: <산출물 경로>, <게이트 판정>
- 대기: <사람 확인 필요 사항 또는 없음>
- 미해결 요청: <경로> <내용> (있으면)
```

---

## 2026-11-06 — #31 stage three: legacy e2e drivers replaced by CI checks and JenkinsRule rows
- The entries between 2026-10-18 and this one were not written here. The pull requests merged in that period are its record.
- **Merged for #31, 2026-11-05 to 11-06.** Each guard went red with its defect put back in `src/main` before it was merged. The CI e2e guards ran green three times on the fork.
  - `e2e/scripts` and `e2e/r6`..`e2e/r11`:
    - #133: removed the drivers whose checks CI already asserted.
    - #134: JenkinsRule guards, note 369.
    - #140: CI e2e guards (`role/manage_edit`, `destructive` configuration page, `dark`, `grants-nowrap`, `tab-bar-narrow`).
    - #142: removed the last 28 drivers and 11 orphaned helpers. `r9/`..`r11/` are gone.
  - `e2e/legacy/`:
    - #137: removed 128 of 203 files.
    - #138 and #139: JenkinsRule guards, notes 370 and 371.
    - #141: CI e2e guards (`month-nav`, `role/manage_perms`, `detail-narrow`, `deploy` as the new unit `h3-deploy`, `request-dialog-cancel`).
    - #143: removed the rest. Only `legacy/extra/theme.mjs` (contrast, X-54) stays, until #123 is decided.
  - #135 (merged through #140, label bug): the grants pages' Approvers cell no longer splits an approver id at its hyphen. The new `grants-nowrap-word` rule found it. It is user-facing, so it is in the next release.
  - #136: LIMITATIONS item 12 says that the standing-permission monitor reads only controller-wide permissions (security-05 S-07, gap X-39).
  - #132 filed: the jelly `checkAnyPermission` finding.
- **Decided, without a row or a fix:**
  - X-20 and X-35: no meaningful assertion exists (notes 370 (2) and 371 (2)).
  - X-40 and X-55: obsolete by the owner's ruling.
  - X-54: the light-theme contrast of `.batch-control-run__result` (3.91:1, core's `--text-color-secondary`) is parked with #123. The guard is written but not merged.
- **Open for the human:**
  - **SPEC 8:** whether to say that the monitor does not see per-item grants (`SPEC.md:161`).
  - **Note 369 (13):** core Overall/Manage on the reviewed page needs a SPEC sentence.
  - **Note 370:** whether the save-failure answer should hide the file path and the I/O error (U-25), and the pending lists' oldest-first order, which SPEC leaves open.
  - **D-88:** quoted by T-05-204/205/212 but not in DECISIONS yet.
  - **`.claude/agents/e2e-tester.md`:** still points at `e2e/scripts/rest-*.sh`, which no longer exist. The crumb and cookie helpers are in `scripts/lib.sh`.
- **Request:** `.github/test-shards.txt`. The per-group time estimates in the header predate several new classes, and they need a timed Build run.

## 2026-10-18 02:30 — Wave D UI (D1–D3) and five bugs merged; D4 held; e2e split between local and CI
- **Merged since the last entry**, each with red tests first and an e2e unit:
  - UI (#18, #6):
    - #90: D1, request pages titled with their subject, and the strategy revert under Advanced.
    - #92: D2, readable statuses and results with colour badges, and dropdown History filters.
    - #94: D3, one Decision area, seven-column run tables with a Run cell, a subject-only breadcrumb and five setting groups. It fixes #6.
  - Bugs:
    - #95: a case-only rename keeps the activation (#32).
    - #96: large configuration diffs in linear memory (#35).
    - #97: no GRANT_REQUEST_BLOCKED record from a user without RequestGrant (#44).
    - #98: the #1 follow-ups, including D-88 (an identical pending run request is not created twice) (#1).
    - #99: incidentResults accepts only FAILURE, UNSTABLE and ABORTED. A stored SUCCESS or NOT_BUILT is dropped on load, with one warning (#91).
  - #89: README screenshots arranged by role and step.
- **Owner decisions (10-14 to 10-18):**
  - **D1–D3 layout:**
    - labels Success / Unstable / Failed / Not built / Aborted, with "In progress";
    - status badges;
    - Run cell order: icon, job, `#n`, label;
    - Run column minimum width 16em;
    - core's float `model-link` in the Run cell;
    - five configuration groups;
    - revert sentence for administrators only.
  - **Left as they are:**
    - the configuration group heading size;
    - the sideways scroll of History > Runs at 768 px;
    - U+FFFD in a kept refused comment;
    - "Aborted" shown twice (label and "Aborted by").
  - **Process:**
    - local e2e only writes the checks and proves red on main / green on the branch; repeat runs, the Linux run and the full groups are left to the fork's GitHub Actions (owner principle 10-17);
    - one task at a time to save tokens.
  - **Release plan:**
    - one release after D4 and the README screenshots;
    - #87, #88, #93 and D-89 go to the next release.
- **Issues:**
  - New: #91 (fixed by #99) and #93 (confirmation before Reject, enhancement).
  - Labelled "help wanted" on 10-17: #4, #5, #8, #9 and #10.
- **CI notes:**
  - Upstream runs saw three infrastructure failures, each passed on a re-run of the failed job:
    - an e2e job hung while starting its stack;
    - a Maven artifact was not resolved;
    - one Windows test failed, `RerunFallbackLinkTest.t_11_19`, which had passed on the fork. I record it under #88.
  - `r28-large-diff` timed out typing 4,000 lines on the slower upstream runner. Its driver now pastes the value; this was pushed to #96 with the owner's approval.
- **Held:** D4 (#7, D-86, Administrator checks), on the local branch `ui/d4-admin-checks`.
  - Implementation done: AdminChecksTest 6/6, and e2e `r27-admin-checks` 28/28 on the branch, with every D4 check red on main.
  - Owner-approved polish to do when it resumes: table spacing and the space before the display name, a title per monitor box, the Mark as reviewed list as a table with short ids, and the same button position as on Manage Jenkins.
- **Waiting for the owner:**
  - D-88 (#1.9, ruling of 10-10) and the D-89 proposal (the deleted-item note) to be written into `docs/DECISIONS.md` and SPEC. Drafts are in the session scratchpad.
  - The outside PR #58 (no answer since 10-09).
- **Open requests:** unchanged from 10-13 (`CLAUDE.md` line 49, `.claude/agents/release-manager.md` line 25, the core-only build wording).

## 2026-10-13 17:50 — two releases out; ci.jenkins.io runs the core tests only; work goes through the fork
- **Releases** (JEP-229 CD, manual `cd` dispatch):
  - `1124.vfe83a_6d946c3` on 2026-10-10, the first release.
  - `1244.ve130334a_a_2a_9` on 2026-10-13 (16:35Z on 10-12), from main `e130334`. All 60 checks on that commit were green, including `Jenkins`, Build, E2E and Windows tests. The update center lists it.
  - #66 had switched the version to `1.0.<count>.v<hash>`, which Jenkins orders below 1124. #86 went back to the default `<count>.v<hash>` before the second release.
- **Merged since the last entry:**
  - Second round of the bug-hunt issues, one PR per bug, each with a red test, the fix and an e2e unit: #69–#76 (10-10) and #82 (#36), #83 (#34), #84 (#33) (10-13).
  - #62 (Windows test premises), #64 and #67 (docs: TEST-MATRIX order, D-85, D-86), #65 (e2e dependency).
- **ci.jenkins.io:**
  - #61 and #63 moved the build to non-spot agents and split the tests across 33 parallel agents. On 10-10 about 40 pushed branches each started such a build. A ci.jenkins.io administrator cancelled them and opened #80.
  - #81: plain `buildPlugin` on spot agents, Linux JDK 25 and Windows JDK 21, only the tests tagged `core` (about 54 minutes). The whole suite runs on GitHub Actions: Build (Linux, JDK 21 and 25) and Windows tests (JDK 21).
  - Owner rule since 10-11: work branches are pushed only to the fork `YongGoose/batch-control-plugin`. A PR to jenkinsci is opened from there after a PR inside the fork is green, and jenkinsci keeps `main` only.
- **README (#85):** screenshots retaken with frames and the section arranged by role and step, on the fork branch `docs/readme-screenshots-by-role`. The owner approved it on 10-13; the fork-side checks are running before the upstream PR.
- **Housekeeping on 10-13:**
  - Issues #87 (flaky e2e unit `def07:def07-new`) and #88 (Windows tests on main: extension download, lost middle run).
  - The seven identical duplicate lines in TEST-MATRIX (notes 300, 302, 304, 305; rows T-05-90, T-05-144) are removed in the same change as this entry.
  - Merged branches and worktrees removed, on the fork and locally.
- **Unfinished, local only** (started 10-10, on no remote; the owner asked on 10-13 to finish them one after another, with thorough e2e):
  - UI (#18, #6, #7): d1 request page titles, ID row, revert inside Advanced; d2 status labels and History filter dropdowns; d3 one decision box and 7-column run tables (tests committed, implementation uncommitted); d4 administrator checks on the overview (tests only).
  - Bugs: #32 (assembled with its e2e unit), #35 and #44 (test and fix, no e2e unit yet), #1 follow-ups (14 commits).
- **Waiting:** the outside PR #58 (`tabs.jelly`, issue #9): changes requested on 10-09, no answer since.
- **Open requests:**
  - `CLAUDE.md` line 49 (human-only) still says the `Jenkinsfile` builds Linux on 21 and 25; it builds Linux 25 and Windows 21 since #81. The line "Agents only commit; the human pushes" also predates the fork rule.
  - `.claude/agents/release-manager.md` line 25 still prescribes the old `Jenkinsfile` configurations.
  - `pom.xml` and `CONTRIBUTING.md` say the core-only build fails when tests are lost; it fails only when no core test runs at all. TEST-MATRIX note 330, `Jenkinsfile` and `CONTRIBUTING.md` give three different numbers for the core test time (release-manager, test-author).

## 2026-10-10 02:15 — bug hunt merged; ci.jenkins.io moved to non-spot agents; first release waits for its check
- **Bug hunt** (owner request: find bugs in code, prove each with an e2e PoC, then fix): 26 candidates, 12 reproduced.
  - All 12 fixed, each with a red test, the fix and an e2e unit, one PR per bug: #47–#57.
  - Decisions D-80..D-84 (#46).
  - Low-severity findings filed as issues #32–#45.
- **Follow-ups merged:**
  - #60: a failed change record never breaks the operation it records. This is the R3-01 rule in four more places; its e2e unit has 30 checks and fails on R3-01.
  - #59: a pending lock retry never writes a deleted job back (TEST-MATRIX note 284 (c)). This was the flaky `FailClosedGapTest.t_gap_438`: 1 failure in 20 runs before, 60 of 60 passes after.
  - #56 also carries:
    - a test-fixture fix: `t_gap_113` rebuilt the job directory in place, and ext4 handed back the same inode;
    - the merge with #60 in `ActivationService.java`.
- **Windows** (merged earlier on 10-09):
  - #28: a manual Windows workflow;
  - #29: skip storage-fault fixtures Windows cannot build;
  - #30: store writes survive a concurrent open handle.
  - #27: ten feature groups per JDK; Build went from 22 to 13 minutes.
- **ci.jenkins.io** had never passed on main.
  - After #16 the remaining failures were "Agent was removed": the `maven-<jdk>` pods run on spot nodes, which were reclaimed during the ~2 h suite, and buildPlugin retries on spot.
  - #61 runs Linux on `maven-<jdk>-nonspot` (jenkins-infra/helpdesk#5171, jenkinsci/jenkins#26895). Its first run on `da1109c` scheduled on the nonspot labels.
- **CD:** `cd` skips the release while the `Jenkins` check on the main HEAD is not green. The owner's dispatch at 16:35Z skipped for that reason.
- **Repository:** all merged or contained remote branches were deleted; `main` is the only branch left.
- **Waiting:**
  - ci.jenkins.io on `da1109c`. Main stays frozen until it finishes; then the owner decides on running `cd`.
  - Review of the outside PR #58 (`tabs.jelly`, issue #9).
- **Prepared, to merge after the release:**
  - this TEST-MATRIX reorder (notes 294–305 and misplaced rows; content unchanged);
  - Dependabot alert 1 (`requests` 2.33.0 in the e2e harness).

## 2026-10-09 (later) — plugin page docs, old issue numbers, ci.jenkins.io, multi-approver defect
- Merged: #14 (required checks, e2e path filter, feature-named test jobs), #13 (CD on manual dispatch only), #12 into #11's branch (its base was `e2e/gap-audit`; #11 now carries both).
- Delegated: release-manager ← README shortened to 220 lines for plugins.jenkins.io, with the detail moved verbatim to the new `docs/USER-GUIDE.md`; the review findings M2–M11 and L1–L12 fixed (CONTRIBUTING, PR template with Testing done and a Releases section, LIMITATIONS); README.ko.md re-translated from the new README. e2e-tester ← five screenshots in `docs/images/` and `e2e/readme-screenshots/capture.py` to regenerate them. Main session ← CLAUDE.md brought up to date, with owners for `docs/USER-GUIDE.md` (release-manager) and `docs/images/**` (e2e-tester), approved by the owner.
- Old issue numbers (the owner chose new numbers where re-filed, otherwise none): #17 removes 161 citations from `src/main` comments (old #115 → #10). Tests, e2e, TEST-MATRIX and the reports keep the historical numbers, and CONTRIBUTING notes that they refer to the former repository.
- ci.jenkins.io is red on main and every PR: buildPlugin's 60-minute default cut linux-21 off after ~500–700 of ~1,300 tests, and failFast aborted the rest; windows-21 fails 16 tests. #16 raises the timeout to 180 and turns failFast off. test-author ← the CLI test helper exceeds the Windows command-line limit (CreateProcess error=206). The other Windows failures wait for #16's full report. The owner merges with the admin bypass while `Jenkins` is red, after `build` is green.
- Defect found by the screenshot pass, confirmed in the Stapler bytecode: the Request Run form posts multipart (D-72), Stapler keeps one value per multipart field name, so only the last ticked approver is stored (D-37). T-03-07 posts urlencoded and did not catch it. Delegated: test-author ← red test for the multipart form and the browser form; ui-dev ← fix.
- UI polish noticed by the screenshot pass: issue #18 (owner: record only).
- Waiting: owner decision on `EXPIRING` for approved requests (SPEC.md:228 vs the code, which notifies pending requests only); e2e job names by feature and `.github` old numbers after these PRs; the CONTRIBUTING sentence on the ci.jenkins.io timeout.

## 2026-10-09 — main protected by rulesets; CI cleanup after the move to jenkinsci
- Repository: jenkinsci/batch-control-plugin was created fresh on 2026-10-08, not transferred. Issues and PRs restart at #1, so old `#n` references in docs and code comments are dead (follow-up).
- Rulesets on `main` (owner decision; D1 of CI-PROPOSAL): `main: CI and history` (no deletion, no force push, PR required, required checks `build` (GitHub Actions) and `Jenkins` (ci.jenkins.io), branch up to date; Repository admin may bypass, for the owner's force push) and `main: one approval` (one approving review; admin may bypass on PRs, since the sole maintainer cannot approve their own PRs).
- PR #11 had no GitHub Actions runs: it conflicted with main because #11 and #118 both used `e2e/r19/`. Delegated: e2e-tester ← merge main into e2e/gap-audit (main's Reject-colour driver moved to `r21/`, TEST-MATRIX notes 286/287 of #11 renumbered 287/288) and merge the result into e2e/best-practice (#12, stacked on #11). Offline checks green; the Docker e2e run is left to CI.
- Delegated: release-manager ← CONTRIBUTING and workflow headers name both required checks; e2e.yml runs only when `src/main/**`, `pom.xml`, `.mvn/**`, `e2e/**` or its own file changes; unit-test jobs named by feature (6 groups × JDK 21/25). Shard safeguard green (277 classes); the real CI run is pending.
- Review: release-manager read-only review of the public markdown (README, README.ko, CONTRIBUTING, LICENSE, CLAUDE.md, PR template, CODEOWNERS, LIMITATIONS): 1 High (README.ko.md contradicts current behaviour), 12 Medium, 12 Low; LICENSE and CODEOWNERS fine. Fixes wait for the owner's decisions.
- Waiting: owner pushes e2e/gap-audit, e2e/best-practice and ci/required-checks-names; owner records D1 in DECISIONS.md; decisions on README.ko.md, README length, old issue numbers and CLAUDE.md.
- Open requests: e2e job names by feature once #12 is merged (e2e-tester `e2e/ci/shard.py`, release-manager `e2e.yml`); optional mention of the e2e paths filter in `e2e/README.md` "CI contract".

## 2026-10-07 — hosting review round 6 (mawinter69, #5338, comment 5986189363): PR #116
- Blockers fixed: ITEM scope (one-item windows, item kind shown; D-71..D-71c, D-74) and typed run parameters incl. file parameters (D-72..D-72b, D-74).
- Decisions: D-71, D-71a/b (replaced by D-74), D-71c, D-72, D-72a, D-72b, D-73, D-74, D-75 (followed names for readers only; D-71c (3) stands; SPEC 171/206 approved), D-76 (snapshot refresh at switch-on, creation-time saves, accepted storage residuals).
- Reviews: security-34..40 (security-40: 0 BLOCKER/HIGH/MEDIUM, 2 LOW documented in LIMITATIONS 32/55), spec-review-r6 (PASS WITH NOTES, resolved).
- Gates: mvn clean verify on 01a3a96 (src/main final): 1289 tests, 0 failures, SpotBugs 0, JDK 21 and 25; later test-only commits verified in PR CI (JDK 21/25 green).
- e2e: e2e-16, e2e-17, e2e-18 (final, 166/167 + rerun, supp 17/17, cifs 14/14, 0 plugin defects). CI e2e shard 5: one layout check (no h-scroll at 1280) fails in CI only.
- Coverage (JaCoCo unit+integration+e2e): 93.0% lines overall, 90.3% of the round's changed lines.
- Issues: #107 and #111 done; #108-#110, #112-#115 open. Replies posted on #5338 (6018970812, 6018971375).
- Follow-ups (LOW): S-40-01, S-40-02, lock retry vs deletion race (TEST-MATRIX note 284 (c)), orphan snapshots, switch-on by editing saved config, CI h-scroll check, README.ko lag.
- Waiting: mawinter69's re-review.

## 2026-10-04 — hosting review rounds 3 and 4 (mawinter69, #5338, 2026-10-03): all merged
- Decisions: D-35g (role-strategy 927 minimum, forward to its descriptor), D-63, D-64, D-65 (FOLDER_ONLY scope), D-66 (lists and dialogs), D-67 (badges only, dashboard 50), D-68 (UUID ids), D-69 (no pre-release migration code), D-70 (beta DialogEvent).
- PRs merged: #98 Java 25 build, caffeine cache, CI per JDK 21/25, UUID ids, migration code removed; #99 FOLDER_ONLY scope; #100 badges, core tab look, model-link, dashboard 50; #101 Pending/Active/Ended lists, revoke on detail, request dialogs, DEF-07 crumb fix; #102 role-strategy 927 forwarding; #103 UX-1 empty id cells (regression of the DEF-05 fix); #104 e2e-14 report; #105 DEF-08 activation page layout on the new job page. #97 (Dependabot 918) closed as superseded.
- Gates (mvn clean verify, JDK 21 and 25): main at b6c1693 788 tests, 0 failures, SpotBugs 0; GitHub CI 19/19 on every PR.
- e2e: e2e-11 (round 3), e2e-12 (exhaustive control crawl, DEF-07), e2e-13 (role-strategy 927, 8/8), e2e-14 (final main: 615 scripted checks, ~20k crawl checks, DEF-08), e2e-15 (multibranch activation page): 6/6 pass, no defect (seed fix: multibranch branch discovery).
- Reply posted on #5338 (2026-10-04).
- Waiting: mawinter69's re-review. UX review (30 items, UX-1 fixed) is held until the owner asks for UX work.

## 2026-10-03 — hosting review round 2 (mawinter69, #5338, 2026-10-02): six PRs opened
- Decisions: D-35e, D-35f, D-35f(a), D-38a, D-38b, D-59, D-59a, D-60, D-61 (PR #64, docs branch).
- PRs: #65 Move change-controlled (D-59/D-59a, security finding); #66 role-strategy 918 pin, legacy wrapper removed, role-strategy surface guard, #766 forward compatibility; #67 new job page compatibility and pre-filled Request Run (D-60); #68 UI polish; #69 run request without Item/Build, job-level Request (D-38a/D-38b); #70 tabs, badges, context menu (D-61, stacked on #68).
- Gates (final heads, mvn clean verify): #65 645, #66 645, #67 639, #68 635, #69 642, #70 644 tests, 0 failures, SpotBugs 0; merged integration build 707 tests green.
- Reviews: security-33 (0 high; MEDIUM fixed as D-59a), spec-review-S5 (BLOCKER fixed as D-38b), final verification PASS WITH NOTES.
- e2e: e2e-06 (E2E-1), e2e-07 (full regression), e2e-08 (targeted re-check) — all functional items pass.
- Merge order: #64, #65, #66, #67, #69, #68, #70; expected conflicts in TEST-MATRIX, LIMITATIONS, ApprovalRequiredFailure and the strategy monitor view.
- Deferred: issues #71-#91 (label backlog).
- Waiting: mawinter69's re-review on #5338.

## 2026-09-30 — final e2e check on 30e9252: DEF-39..41 fixed (part 7); #51 merged
- #51 merged (GitHub `build` passed 620 tests). Code-scanning alerts 31-33 are closed as fixed.
- The final check on 30e9252 passed: DEF-38 is closed, Mark as reviewed and replay marking work, and FD-14..16 and DD-05/06 are fixed.
- It found three low defects, all fixed here:
  - DEF-39: monitor and 403 texts now say that only Mark as reviewed ends the guard, and where to find it;
  - DEF-40: records for re-runs of a marked run give the true reason;
  - DEF-41: Rebuild is hidden on marked runs for non-administrators.
- security-32 found 0/0/0/1/3. The LOW and INFO items are in issue #52.
- Gate at ffbf5e7: 623 tests, 0 failures, SpotBugs 0.

## 2026-09-30 — DEF-38 per-item authorization guard and final e2e defects (part 6); #50 merged
- #50 merged (part 5; GitHub `build` passed 588 tests).
- The final re-verification on 80e5271 passed everything that had been open, except C-08, which failed on DEF-38 (High).
  - DEF-38: a Pipeline `properties` step running as any build identity could write a permanent self-grant for a Configure window holder.
  - The owner ruled per-item guarding over per-principal guarding (D-58, then D-58a).
  - security-27..30 closed the remaining gaps: descendants, Replay/Rebuild/Restart from Stage marking and explicit "Mark as reviewed" (D-58b); runs replayed under a grant stay untrusted, and their re-runs inherit the marker (D-58c).
  - The grant file gained an optional `changedItems` list (ARCHITECTURE 5).
- Also in this part:
  - Jenkins Security Scan alerts 31-33 (role form checks now require permission);
  - e2e-04 re-verification defects FD-14/15/16 and DD-05/06;
  - UX-18/19;
  - D-05, verified with a faked clock.
- Reviews:
  - security-27 (3 BLOCKER / 3 HIGH);
  - security-28 (3 BLOCKER);
  - security-29 (1 MEDIUM);
  - security-30 (1 BLOCKER / 1 MEDIUM) — all fixed;
  - security-31: 0/0/0/4/5. The LOW items are follow-ups.
- Gate at d0f243d: 620 tests, 0 failures, SpotBugs 0.

## 2026-09-30 — fresh-eyes e2e (e2e-04) defects fixed (part 5); #49 merged
- #49 merged (part 4, GitHub `build` 566 tests green).
- An independent fresh-eyes e2e pass (docs/reports/e2e-04.md, on main 99be699) found 13 defects FD-01..13 and 4 doc defects DD-01..04. The core flows held: an approved run executes once, activation, grant windows, and the self-grant refusal. Rulings D-52..D-57. Part 5 fixes:
  - FD-01: strategy actions are shown only to administrators.
  - FD-02 / D-52: CONFIG_CHANGE and STRATEGY_CHANGE records.
  - FD-03 / D-53: approver ids are validated, the input is kept, capped and de-duplicated.
  - FD-04 / D-54: mails for cancelled, expired and invalidated requests.
  - FD-05: expiry reasons.
  - FD-06 / D-55: a disabled job cannot be approved.
  - FD-07: a refusal after a hold gets its own record.
  - FD-08: the grant form comes first.
  - FD-09: approved runs carry the requester, but only for the request's own run.
  - FD-10 / D-57: the incident rerun needs ViewHistory, with guidance.
  - FD-11: History date messages.
  - FD-12/13 / D-56: no change, documented.
  - DD-01..04: documentation.
- Also fixed: security-24 items (the re-run budget after shutdown, retry warnings, a store test seam that works only in unit tests) and S-26-01 (an approved run is never folded into a waiting queue item).
- Reviews:
  - security-25: 0/0/1/2/6. S-25-01 was forged Rebuild attribution; fixed.
  - security-26: 0/0/0/1/4; all fixed.
- Gate at bb3d182: 588 tests, 0 failures, SpotBugs 0.

## 2026-09-30 — e2e re-verification defects fixed (part 4); #48 merged
- #48 merged (security-19/20 hardening, GitHub `build` 550 tests green).
- The e2e re-verification on the #46/#47 build (e2e-03 "Re-verification after #46/#47") found 50 re-audit FAIL rows -> 46 PASS / 4 FAIL, Section C 29/2/1, D 11 PASS and 2 blocked (D-05 month boundary, D-09 no released version). Part 4 fixes the open defects:
  - DEF-32: a person's refused re-run is recorded per attempt. The root cause was the hourly merge. See D-51/D-51a: a budget of 20 per user per 10 minutes, then a summary and a closing count, and records stay append-only.
  - DEF-36: the Rename check and the CLI create-job name the restriction.
  - DEF-37: while change control is on, one instance-wide warning appears when builds can run as SYSTEM or as an account with Configure. It is on the monitor and, for deciders and Manage holders, on the Configure request detail (D-50/D-50a/D-50b).
  - DD-14/15 and the rulings in D-49 are documented.
- Reviews:
  - security-21: 0/2/3/2/2. The per-job scan missed risks and leaked hidden jobs; ruled D-50a and D-51a.
  - security-22: 0/1/0/4/3. A service account with Configure; ruled D-50b.
  - security-23: 0/0/0/3/5. Wrong remedy text, a flush regression, root-only scope.
  - security-24: 0/0/0/1/4. Its items move to part 5.
- Gate at 8e2db39: 566 tests, 0 failures, SpotBugs 0.

## 2026-09-30 — security-19/20 follow-up (hardening of the part-3 fixes)
- #47 merged (GitHub `build` 546 tests green).
- security-19 0/0/0/3/3 and security-20 0/0/0/1/3 fixed on `fix/security-19`: a Retry skips the token step only with the clicking user's cause (S-19-01); a numeric `configVersion` is dropped only without attributes (S-19-02); the self-grant filter leaves POSTs unwrapped while change control is off, its fallback never fails, it is async-safe and its writer keeps `checkError()` without copying (S-19-03..05, S-20-01/02); both group caps of the monitor log a WARNING and truncated users are probed in full (S-19-06, S-20-03). S-20-04 (async output edges not reachable from a save path) is left as INFO.
- Rows T-06-76, T-09-23, T-01-16, T-02-50 (notes 154-157). Gate at d4bff88: 550 tests, 0 failures, SpotBugs 0.
- e2e re-verification on the #46/#47 build is running; the DEF-35 rows are re-run after this merges.

## 2026-09-30 — e2e-03 re-audit defects DEF-28..35 fixed (part 3); part-2 fixes merged (#46)
- #46 merged (DEF-08..27, DD-07..12; GitHub `build` 530 tests green).
- The rubric re-audit of 215 rows (run on the #45 build) found DEF-28..35; 30 of its new FAILs were part-2 defects already fixed in #46. Part 3 fixes: DEF-28 no "Back to" links on refusal pages; DEF-29 the standing-holder monitor lists each user only for their own entries and each group once (also under role-strategy groups, S-18-01); DEF-30 plugin-version and numeric `configVersion` re-serialisation is not a change; DEF-31 build pages carry the approval notice (only while approval is required, S-18-06); DEF-32 a user-clicked Retry is recorded as that user; DEF-33/34 a refused token run answers 403 with a plain message; DEF-35 the self-grant guard answers 403 and tells the user (D-48, SPEC item 2); DEF-06 activation crumbs read `<item> > Activation`.
- Tests: rows T-UI-23/24, T-08-53/56, T-09-21/22, T-06-71..75, T-02-47..49, T-01-15, notes 140-153; T-02-35 and T-08-47 updated to D-48. Gate at d5863e5: 546 tests, 0 failures, SpotBugs 0.
- Reviews: security-18 0/0/1/2/3 (all fixed in this branch), security-19 0/0/0/3/3 (S-19-01..03 LOW and the INFO items go into a follow-up PR; red rows ready).
- Next: follow-up PR for security-19, then redeploy the hpi from main, re-verify every FAIL row and every DEF, then e2e part 3 (C, D) and the independent pass.

## 2026-09-30 — final e2e (e2e-03) in progress; part-1 fixes merged (#45), part-2 fixes ready
- e2e-03 part 1 (pre-flight, reviewer items, plugin precedence) and part 2 (Section B, 21 groups) done; the gate held on every path; defects were about what users are told and what is recorded. Part-1 defects DEF-01..07 merged in #45 (security-16 0/0/0/1).
- Part-2 defects DEF-08..27 and DD-07..12 fixed on `fix/e2e-run3-part2` (usability acceptance line added to SPEC section 6). Rulings: core Build Now, Pipeline Replay and naginator Retry links cannot be hidden by a plugin, so they stay visible and a click is refused with an explanation (LIMITATIONS); Rebuild is hidden via RebuildValidator; a Discover-only user is not offered the rerun form.
- Owner: strict five-criterion e2e rubric; a re-audit of pre-rubric rows is running and has found DEF-28..33 (next fix batch). Owner approved the #5338 reply and the Discussion draft (docs/hosting-reply) for posting after the remaining steps.

## 2026-09-29 (evening) — p1 round complete: PRs #41, #42, #43 merged; #15 ready
- **Lanes (owner-approved partial parallelism):** lane B #41 (#19, #24, #26; security-09 0/0/1/1, D-42) and lane A #42 (#13, #17, #18, #25; security-10 0/1/2/8 → D-43 removed legacy file-name compatibility; security-11 re-review 0/0/2/0). A real defect was found in lane A and pinned (T-04-15: saving a long-named job deleted another job's baseline). SPEC section 6 measured: dashboard median 101 ms, history 79 ms (target 2 s).
- **#43** (#21 TRIGGER_BLOCKED records and job-page lock notice, #22 one run-link rule D-44, shared paging, Store seam, security-11 N-01/N-02): security-12 0/0/0/1.
- **#15 activation approval:** SPEC 6a, D-39, D-45 (a job created with run control off counts as activated). Three review rounds: security-13 **1/2/3/5** (clearing approvalRequired, SCM/unclassified causes and computed folders all bypassed activation → D-46 covers every unattended start path, computed folders carry activation); security-14 **1/1/0/1** (an automatic retry inherited a human cause → D-47 classifies by the submission itself); security-15 0/0/1/0 (a user cause submitted as SYSTEM → human only when the submitter is not SYSTEM; anonymous Build Now unchanged). Gate: **488 tests, 0 failures, SpotBugs 0.**
- **Process notes:** machine sleep and swap exhaustion from parallel forks stalled agents repeatedly (fixed: caffeinate on AC power, `-DforkCount=2` for concurrent builds); one test I specified (10,001 jobs) would have run for hours and was rewritten with a configurable bound.
- **e2e:** checklist `e2e/CHECKLIST.md` (~300 rows, branch `e2e/checklist`) and environment WIP (branch `e2e/run-1`) ready; runs only when the owner says start. Reply to the hosting reviewer after e2e.

## 2026-09-29 — PR 4 (#32, #33, #35, #23, #11) ready
- PR 3 merged as #39.
- D-37 several approvers (any one decides, first decision wins), D-36 notifications (`ops.BatchControlNotifier` extension point, optional Mailer e-mail, off by default), D-40 CREATE name restriction (exact name or `/regex/`, enforced before the item exists on every creation path).
- security-08: **BLOCKER 1 / HIGH 2 / MEDIUM 1 / LOW 9**: rename bypass of the name restriction, name taken from the wrong parameter, ReDoS in user regex, attacker-chosen host in the mail link, plus the pre-existing #23. Rulings D-40a; all fixed except S-07 (computed-folder children, documented) and S-13 (not reachable, excluded with a reason). Regression rows T-SEC-35..51.
- GitHub Actions `build` workflow (#11) added: `mvn clean verify` on pull requests and main, read-only token, no secrets. Branch protection deliberately not enabled until after the jenkinsci fork (owner).
- Gate: 369 tests, 0 failures, SpotBugs 0, 6 min. One earlier run had two timing failures under parallel forks (a 1 s bound in T-SEC-37, a RealJenkinsRule start in T-02-42); stabilising them without weakening them.
- Next (owner-approved): two parallel lanes, A storage (#13, #17, #18, #25) and B policy (#24, #26, #19); then #21/#22, #15, and the final detailed e2e.

## 2026-09-28 (night) — PR 3 (#20, #34, #36) ready
- PR 2 (#30) merged as #38.
- #36: nine plugin-interaction test classes (customize-build-now, rebuild, parameterized-trigger, build-token-root, naginator, lockable-resources, throttle-concurrents, authorize-project, jobConfigHistory). 29 of 30 held on the existing gate; the naginator automatic retry of an approved run was the gap. Ruling in SPEC item 6: a retry is judged by the causes of the build it retries. Fixed in the queue gate.
- #34: customize-build-now does not bypass the gate and the job page still offers Request Run. No code change was needed beyond the tests.
- #20: configuration comparison ignores `plugin="…@version"` and the root `<actions>`, and computed-folder children are not recorded as CONFIGURE. The snapshot lock is striped.
- Gate: 300 tests, 0 failures, SpotBugs 0, but **1 h 13 min** (was 22 min). Every JenkinsRule now loads the nine extra plugins. Parallel forks go into the next PR, whose gate validates them.
- security-07 (0 blocker / 0 high / 2 medium / 2 low): same-job UpstreamCause no longer unwrapped as a retry, `plugin` stripped only as a real attribute, computed-child saves skipped only on the parent's own indexing thread. Regression rows T-SEC-32..34. Parallel forks (`forkCount=1C`) added here. Final gate: **303 tests, 0 failures, SpotBugs 0, 7 min 53 s**.

## 2026-09-28 (evening) — PR 2 (#30 per-strategy subclasses) ready
- PR 1 (#31) merged as #37 after security-04 (0 blocker / 0 high).
- #30: D-35a (option A from PoC-5) implemented: `BatchControlMatrixAuthorizationStrategy`, `BatchControlRoleBasedAuthorizationStrategy`, converters, JCasC configurators, legacy wrapper load shim, `batch-control-strategy` monitor with migrate/revert, D-35b self-grant guard, D-35c created-item Configure. matrix-auth, role-strategy and JCasC are optional dependencies.
- security-05 on the first implementation: **BLOCKER 3 / HIGH 1 / MEDIUM 1 / LOW 4** (inherited-grant guard bypass, SYSTEM builds, class linking without one of the two plugins, global-matrix conversion widening, stale guard baseline). Rulings recorded as D-35d; all fixed except S-02, which is accepted and documented (LIMITATIONS 35, plus a monitor warning when no build authenticator is configured). security-06 re-review: no BLOCKER/HIGH remains.
- Gate: `mvn clean verify` 270 tests, 0 failures, 2 errors in the two RealJenkinsRule optional-dependency tests (fixture referenced `JenkinsRule$DummySecurityRealm`); fixture fixed in test code only and both classes re-run green. SpotBugs 0.
- Next: #34 + #36 (customize-build-now and plugin interaction tests).

## 2026-09-28 — Hosting review round: PR 1 (#31 cleanup) gate green; #30 PoC chose option A
- **Scope of this round** (owner, 2026-09-28): the hosting reviewer's feedback on jenkins-infra/repository-permissions-updater#5338 first, then every open p1 issue, then a detailed per-feature e2e pass. New issues #30..#36 (label `hosting-review`); the config-diff remark went to #20.
- **Owner decisions D-35..D-41** recorded (authorization mechanism by PoC, notifications via extension point + Mailer, several approvers / any one decides, run requests need Item/Build, activation approval per job reusing Request, CREATE requests may restrict the job name, stable permission group id). SPEC item 2 gained the absent-action rule: no Batch Control permission at all means 404 at the action and everything beneath it; a partial holder still gets 403 from a section.
- **PR 1 (#31)**: test-author red tests (T-02-06..09, T-SEC-11/15, T-12-01) → core-dev (permission group id, GrantService singleton, `getAllPermissionEntries`, id-free messages) → ui-dev (root/job actions return null URL, `doIndex` removed with `l:layout permissions`, `l:adminMonitor`, `jenkins-select`/`f:checkbox`, ionicons symbols, app bars, no back-links) → six older tests moved from 403 to 404 under the new SPEC line (contract change, guards kept). `mvn clean verify`: **238 tests, 0 failures, SpotBugs 0**.
- **#30 PoC-5** (`docs/POC-RESULTS.md` on the PoC branch, 30 tests): the current wrapper silently drops per-item matrix properties and breaks role-strategy's UI. Option B (native entries) fails D-35's second criterion (an entry can outlive its window after a crash or a stale admin form). **Chosen: option A**, a Batch Control subclass per supported strategy, plus D-35b (revert an item's matrix property edited by a grant holder) and D-35c (a Create grant covers Configure on items created in its window). Recorded on the #30 branch.
- Next: merge PR 1 after CI and review → #30.

## 2026-09-26 — Phase 5 CLOSED, seven owner decisions landed, hosting prep done
- **Phase 5 gate met.** e2e-01 found four failures; e2e-02 re-ran them on a rebuilt plugin and all four are fixed (reports `docs/reports/e2e-01.md`, `e2e-02.md`). The visual pass was done in a browser by the main session, not inferred from HTTP.
- **E2E-D1 was the find of the phase**: an approved CONFIGURE grant let the requester *save* a job config but the configure screen returned 403, so nobody could actually use the feature. 159 integration tests passed throughout — the positive path was asserted only on the POST. Root cause was `Item.EXTENDED_READ`, which core ships disabled and resolves through `impliedBy`; the resolution happened inside the delegate ACL where the grant is invisible (P-11, `GrantConfigureAccessTest`).
- **Owner decisions D-24..D-33** recorded, with SPEC criteria: no separate hold state (the toggles are the off switch); no standing-approval model — single consumption applies to human-submitted requests while cron keeps passing, verified live (an approvalRequired job produced three timer builds in the observation window); delegation lines stay out of MVP; no control change may interrupt a running build; rejection shows the requester reason, approver and time; only the designated approver may decide; marker re-use lands in the audit history; new jobs start controlled except computed children; expiry guidance lives on the grants screen.
- **P0 coverage is now complete**: T-SEC-07, the last empty row, is filled under the P-03 ruling (masking kept, the limitation documented). Suite 156 → **197 tests**, matrix 135 → **174 rows / 117 P0**. The suite migrated to JUnit 5 with the per-class counts unchanged, and `ban-junit4-imports.skip` is back to `false`.
- **Hosting preparation**: 65-item checklist now 56 PASS / 0 FAIL (the four CD rows are N/A after the owner chose manual releases). LICENSE, CODEOWNERS, dependabot, README rewritten as a plugin document with `docs/LIMITATIONS.md` behind it, CONTRIBUTING, a PR template, `docs/HOSTING-REQUEST.md` as the run book, and the agent toolkit translated to English so a fork can use it. Remaining owner items: the commit-permission GitHub handle and the submission's "how it differs" wording.
- **Pre-merge review `security-03`: BLOCKER 0 / HIGH 1 / MEDIUM 2 / LOW 7 — no merge blockers.** S-14 was afterwards **reproduced live**: on a job with an auth token, a user who gets 403 on the build screen started a build by adding `?token=`, because `Cause.RemoteCause` falls through the gate's unclassified branch. The findings go to a dedicated issue and a follow-up PR.

## 2026-09-23 (later) — Phase 4 CLOSED (gate met) → Phase 5 is next
- security-02 re-review done (commit 16666a2): **BLOCKER 0 / HIGH 0 — Phase 4 gate MET**. S-01/S-03/S-05/S-06/S-07/S-10/S-11 all verified FIXED with file:line evidence; S-02, S-04/P-06, S-08, S-09 deferred by pending human decisions. The new ACL.SYSTEM2 block in IncidentItem.doRerun was scrutinised and cleared (existence lookup only; permission decision outside the context).
- Coverage gap closed (commit 5937ab1): T-SEC-15 asserts the run-request submit POST is refused (403, no request, no build) without BatchControl/Request — previously only the GET form was asserted. Matrix now 135 rows / 84 P0; suite 156 tests.
- New LOW findings from the re-review: S-12 (history/CSV surfaces are deliberately outside the P-09 visibility boundary — documentation item, added to issue #6) and S-13 (residue from the S-03 fix — stale comment, an advertised root-level Item/Create that no longer exists, no scope re-validation on approve; cleanup in progress).
- Also noted: regression test s_05 is shallow (would pass with the monitor cache removed) — strengthening queued.
- Issue #1 closed. Next: issue #2 (Phase 5 E2E), then #3 (overall cross-review + red-team second pass).

## 2026-09-23 — HANDOFF checkpoint (Phase 4 nearly done; security-02 pending)
- Done since last entry: security-01 (BLOCKER 0 / HIGH 1 / MEDIUM 4 / LOW 6) → all routable findings fixed (S-01 P-09 visibility model, S-03, S-05 incl. SpotBugs restructure, S-06, S-07, S-10, S-11) + SecurityRegressionTest (7 methods, T-SEC-08..14; matrix now 134 rows) + jenkins-security-scan workflow. Final verify at `6f37f2d`: 155/155 tests, SpotBugs 0.
- NOT done: security-02 re-review (the Phase 4 closing gate check) — the reviewer agent was killed by an API session limit before starting. Everything else queued behind it: Phase 5 E2E, overall cross-review, Phase 6, Phase 7.
- Handoff artifacts: docs/HANDOFF.md (environment, position, next steps), GitHub issues #1..#7 (remaining work, human decisions), draft PR with the continuation plan. All branches pushed to origin.
- Deferred by pending human decisions: S-02, S-04/P-06, T-SEC-07 (P-03), P-09 ratification, P-01..P-08.

## 2026-09-21 (afternoon) — Phase 3 CLOSED → Phase 4 started
- S4 result: tests f87d8f0 + impl f49dbad + review fixes (resolve() requires ACKNOWLEDGED; dead HistoryService/CsvSupport deleted; FileStore month bucketing unified on BatchClock zone) + new XssEscapingTest (T-RT-10). spec-review-S4 = PASS WITH NOTES, BLOCKER 0.
- Final verify: 148/148 tests green, SpotBugs 0 (9m12s).
- Phase 3 closing gate: P0 non-e2e 76/77 (98.7%) — sole gap T-SEC-07, blocked on human P-03 decision (carried as release-blocking follow-up); P1 non-e2e 35/35 after T-RT-10 landed (was 34/35); P2 5/6 (T-RT-18 deferred per D-22); 9 e2e rows → Phase 5 by design. Gate judged CLOSED with the T-SEC-07 carry.
- New proposal P-08 (summary counting semantics). Human-pending pile: P-01..P-08, T-SEC-07/P-03, CLAUDE.md ownership row for src/main/webapp/help/**, retroactive approvals for two mechanical test edits (CLICommandInvoker imports, (Cause) null cast).
- phase-3-impl merged to main, pushed.
- Environment: Docker Desktop 29.8.0 + Compose v5.5.1 now installed and running (user action) — Phase 5 can run the designed docker-compose flow; browser automation via connected Chrome replaces Playwright MCP.
- Delegating: security-reviewer ← Phase 4 full src/main review (HOSTING-CHECKLIST section B) → docs/reports/security-01.md

## 2026-09-21 01:05 — Phase 3 S3 done → S4 started
- S3 result: commits f1f4793 (tests) + e3bbc3f (impl) + 4f89602 (monitor banners). Full verify green twice (core-dev run and orchestrator run, 119/119, SpotBugs 0). spec-review-S3.md = PASS WITH NOTES, BLOCKER 0.
- MAJOR (getACL(IComputer) delegation gap) + MINOR (root-scope "" consistency) routed to core-dev for immediate fix.
- All 5 documented core-dev deviations accepted by spec-guardian (SaveableListener CONFIGURE recording, Grant.id==requestId, P-06 pending, masked-note diffs, folder-rename MOVE records).
- New proposals: P-06 (RequestGrant HTTP-layer-only enforcement), P-07 (GrantRequest approver-change model conflict). Pending human: CLAUDE.md ownership row for src/main/webapp/help/** (assigned to ui-dev by orchestrator), Grants-link switch-independence judgment, P-01..P-07.
- Session-limit interruptions: S3 core-dev/ui-dev were killed once by the API session limit and resumed cleanly (no disk state lost).
- Delegating: test-author ← S4 tests (SPEC 10, 11, 12) — in progress; spec-guardian S3 and orchestrator verify ran in parallel.

## 2026-09-20 21:00 — Phase 3 S2 done → S3 started (standing instruction: proceed when no BLOCKER)
- S2 result: commits c56872e (tests) + 752c38b (impl). Full `mvn clean verify`: 79/79 tests green, SpotBugs 0, 4m26s. spec-review-S2.md = PASS WITH NOTES, BLOCKER 0.
- MAJOR fixed by main session: DECISIONS.md P-02/P-03 fusion restored; new proposals P-04 (marker re-use record location) and P-05 (SPEC §3 model field sync) registered.
- MINOR routing: expiry/queue-snapshot race + guidance link → core-dev (carried into S3 delegation); T-RT-02 wording + T-RT-07 file column → test-author (carried into S3 delegation); test-import one-line fixes (CLICommandInvoker) noted for retroactive human approval; root-action icon visibility = informational.
- Language policy: from now on all written artifacts are in English (user instruction; CLAUDE.md updated, commit 3e27580).
- GitHub: public repo https://github.com/YongGoose/batch-control-plugin created; main + all phase branches pushed; push on every phase gate from now on.
- Delegating: test-author ← S3 tests (SPEC 8, 9 + T-SEC-06 remainder + carried fixes)

## 2026-09-20 19:40 — Phase 3 S1 완료 → S2 시작 (사람 사전 지시: BLOCKER 없으면 직행)
- S1 결과: 커밋 ea742d3. 테스트 16/16 녹색, SpotBugs 0, hpi 패키징 성공. spec-review-S1.md = PASS WITH NOTES, BLOCKER 0.
- T-02-02 판정 기록: 실패 원인은 matrix-auth 3.3 카드 UI(그룹 제목이 접힌 DOM에 렌더링)로, 기능은 정상. 오케스트레이터 판정 후 **test-author가** 단언을 raw DOM 기준으로 수정(사유 주석 + 매트릭스 병기). spec-review의 MAJOR(테스트 수정 소급 승인 건)는 수정 주체가 core-dev가 아닌 test-author 위임이었음을 명시해 종결 — 사람이 이의 있으면 재론.
- MINOR 2건(권한 함의 구조·스코프 표기) → DECISIONS 제안 P-02 등재.
- 위임: test-author ← S2(SPEC 3,5,6,7 + 이월 T-02-03/04·T-04-02/04 + T-RT-01/02/03/14/15/16/17/19 + T-SEC-01/02/05/06) 테스트 작성

## 2026-09-20 18:10 — Phase 2 게이트 통과 🧑✓ → Phase 3 S1 시작
- 사람 결정: 매트릭스 승인. R-1~R-8 권고안 그대로 채택(R-1·3·4·5·6·8 채택, R-2 경량, R-7 크기 상한만). SPEC 수정 위임 허가(이번 건 한정).
- 반영: SPEC 5·6·7·8·11·12·비기능에 수용 기준 8건 추가, 상태 머신·데이터 모델에 INVALIDATED 추가. DECISIONS 확정 D-16~D-23 기록. test-author가 e2e 4행 추가(T-E2E-05~08, P1) → 최종 127행, P0 80 / P1 40 / P2 7, e2e 9.
- RT-08 → README "Known limitations" 항목으로 release-manager에 전달 예정(Phase 7).
- phase-2-matrix → main 머지, phase-3-impl 브랜치 생성.
- 위임: test-author ← S1(SPEC 1,2,4 + T-CFG + T-SEC-04) 테스트 작성 (src/main 읽기 금지)

## 2026-09-20 17:40 — Phase 2 완료, 게이트 대기 🧑
- 위임 결과: test-author 1차(107행) + red-team-01.md(20 시나리오) + 병합 2차(T-RT 16행 추가, 4건 제외 사유 기록). release-manager: pom에 pipeline-build-step·job-dsl·role-strategy·workflow-multibranch test 의존성 추가(전부 BOM 관리, verify 녹색).
- 최종 매트릭스: 총 123행, P0 80 / P1 36 / P2 7, integration 116 / unit 2 / e2e 5.
- 대기: 사람이 TEST-MATRIX 직접 읽고 승인 🧑 + red-team 제안 R-1~R-8 판정(T-RT 12개 행이 SPEC 보강 결정에 종속).
- 미해결 요청: README에 다계정 자가 결재 한계 명시(RT-08, release-manager Phase 7에서) / core-dev에 Clock 교체 API·PeriodicWork 수동 실행·idempotency 키 설계(Phase 3 위임 시 전달).

## 2026-09-20 17:00 — Phase 1 게이트 통과 🧑✓ → Phase 2 시작
- 사람 결정: 설계 유지, Phase 2 진행. ARCHITECTURE 갱신 승인(전 getACL 오버로드 위임 / 차단 시 사용자 유래 Cause는 Failure throw·무인 Cause는 false+로그 / BuildUpstreamCause instanceof 분류 / 제약 2건 추가). SPEC 8에 Role Strategy 미지원 안내 수용 기준 추가. C-2=(a), DECISIONS 제안 P-01 등록. pipeline-build-step 테스트 의존성 추가 승인(release-manager, Phase 2와 병렬).
- phase-1-poc → main 머지 완료.
- 위임: test-author ← SPEC 수용 기준 전부를 TEST-MATRIX 행으로 변환 / red-team ← docs/reports/red-team-01.md / release-manager ← pom.xml pipeline-build-step test 의존성 (3건 병렬)
- 대기: 매트릭스 완성 후 사람이 직접 읽고 승인 🧑

## 2026-09-20 16:50 — Phase 1 완료, 게이트 대기 🧑
- 위임: poc-engineer ← 가정 A~D 검증 (poc/ 모듈 + docs/POC-RESULTS.md, 브랜치 phase-1-poc)
- 결과: docs/POC-RESULTS.md, 커밋 eda272c. 판정 A 통과 / B 통과 / C 통과(Matrix)·조건부(Role Strategy) / D 조건부(WebClient 단언, 육안 확인은 Phase 5 이월). poc/ `mvn test` 22개 전부 녹색.
- 대기: 사람 확인 — ① 설계 유지 여부, ② ARCHITECTURE 4절(전 getACL 오버로드 위임)·2/6절(차단 시 Failure throw) 갱신 승인, ③ Role Strategy 지원 수준(C-2) 결정
- 미해결 요청: pom.xml에 `pipeline-build-step` test 의존성 추가 (release-manager, Phase 3 전) / ARCHITECTURE·DECISIONS 갱신 (사람)

## 2026-09-20 16:27 — Phase 0 완료
- 위임: 없음 (메인 세션 직접, WORKFLOW Phase 0)
- 결과: pom.xml(parent 6.2236.v12dd4c483242, jenkins.version 2.568.3, bom-2.568.x:7046.v43536164769c, 의존성 structs·cloudbees-folder + 테스트 스코프 workflow-job·workflow-cps·workflow-basic-steps·matrix-auth), Jenkinsfile(buildPlugin), src/main/resources/index.jelly, .gitignore, git init + 첫 커밋. `mvn clean verify` BUILD SUCCESS (JDK 21 Temurin, Maven 3.9.16).
- 비고: 아키타입 대신 pom 직접 작성(비대화식 환경). 빌드 도구는 로컬 설치: JDK 17/21(winget Temurin), Maven ~/tools/apache-maven-3.9.16.
- 대기: 없음
- 다음 작업: Phase 1 (poc-engineer 위임)
