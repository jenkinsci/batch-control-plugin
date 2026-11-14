# batch-control 기능 스펙 (v1.0 — 확정)

이 문서는 모든 에이전트의 단일 기준입니다. 각 항목의 "수용 기준"은 테스트로 검증 가능한 문장으로 썼습니다.

## 0. 용어

| 용어 | 정의 |
|---|---|
| 요청자 (Requester) | 실행 요청 또는 임시 권한 요청을 올리는 사용자 |
| 결재자 (Approver) | 요청을 승인/반려하는 사용자. 플러그인 설정의 결재자 목록에 등록되어 있어야 함 |
| 실행 요청 (RunRequest) | 특정 잡을 특정 파라미터로 실행해 달라는 요청 |
| 권한 요청 (GrantRequest) | 특정 범위·행위·시간의 임시 권한을 달라는 요청 (JIT) |
| 권한 부여 (Grant) | 승인된 권한 요청의 결과. 만료 시각을 가짐 |
| 실행 기록 (RunRecord) | 모든 빌드의 실행 결과 요약 |
| 오류 건 (Incident) | FAILURE/UNSTABLE 결과에서 자동 생성되는 처리 대상 |
| 변경 기록 (ChangeRecord) | 잡 생성·수정·삭제·이름변경·이동의 기록 |

## 1. 범위

- **MVP (1차 릴리스)**: 항목 1~12
- **2차**: 항목 13~15
- **범위 밖**: 관리자(Overall/Administer)의 우회 차단, 디스크 직접 수정 탐지, 배치 애플리케이션 내부의 단계 재개(restart), Pipeline 스크립트 소스(Git) 변경 통제

## 2. 기능 스펙

### 공통 기반

**1. 전역 스위치**
플러그인 설치만으로는 아무 동작도 하지 않고, 관리자가 켜야 활성화됩니다.
행위별(실행 통제 / 변경 통제)로 따로 켜고 끌 수 있으며, 스위치 변경 자체도 이력에 남깁니다.
- 수용 기준: 설치 직후 기존 잡의 빌드·설정·삭제가 이전과 동일하게 동작한다.
- 수용 기준: 실행 통제만 켜면 변경 통제 관련 UI·차단은 나타나지 않는다(반대도 동일).
- 수용 기준: 스위치 on/off 시 ChangeRecord(type=CONFIG_TOGGLE, 사용자, 시각, 이전/이후 값)가 남는다.
- Acceptance: a switch change takes effect only once the new configuration is saved. If the save fails, the in-memory switch, the toggle record and any side effect (such as revoking active grants when change control is turned off) are not applied; side effects run only after the new state is durable. A direct setter call (JCasC, script console) never throws: it applies the value and logs a failed write (D-42).

**2. 권한 체계**
`BatchControl/Request`, `BatchControl/Approve`, `BatchControl/RequestGrant`, `BatchControl/ViewHistory`, `BatchControl/Manage` 권한을 정의합니다. `BatchControl/Request`는 전역 외에 잡·폴더 단위로도 부여할 수 있습니다(D-38a, D-38b).
Matrix와 Role 기반 권한 전략에 자동으로 노출되고, 관리자(Overall/Administer)는 본인 요청을 스스로 결재할 수 있습니다.
- 수용 기준: 각 권한이 Matrix Authorization 설정 화면에 "Batch Control" 그룹으로 표시된다.
- 수용 기준: `Manage`가 없는 사용자는 전역 설정·결재자 목록을 바꿀 수 없다.
- 수용 기준: 관리자 자가 결재 시 ApprovalRecord에 `selfApproved=true`가 기록된다.
- Acceptance: with the Batch Control matrix-auth strategy installed, folder, job and agent authorization properties are configurable and effective, and an existing job property survives a save of the job page. With the Batch Control role-strategy strategy installed, Manage Roles, item and agent roles, pattern-based Create and the role naming strategy work. (D-35a, #30)
- Acceptance: the global configuration and the Batch Control strategy round-trip through config.xml, a restart and JCasC. A saved configuration of the withdrawn wrapper is not converted (D-35e). (D-35a)
- Acceptance: a user whose Configure comes only from a grant cannot change an item's authorization property; the change is reverted and recorded as GRANT_VIOLATION. A Create grant leaves no permanent authorization entry on the item it created, whether matrix-auth's creator listener or the creation payload (a submitted config.xml or a copied item) would have added it; a payload's authorization property is removed and recorded as GRANT_VIOLATION. (D-35b, D-35c)
- Acceptance: when the guard reverts part of a save, the saving user is told: a save made through an HTTP request (the configuration form, a `config.xml` POST, `createItem`, a copy) answers 403 with a plain message naming the item, saying the authorization entries were not kept because the user's Configure comes only from a temporary grant, that the other changes were saved, and to ask an administrator; the browser form shows it on a standard Jenkins page. (D-48)
- Acceptance: while change control is on, an item in the scope of an active grant, or whose configuration was changed under a grant and has not since been saved through an HTTP request by a native Item/Configure or Overall/Administer holder, is guarded: any change that widens access on it (an added or widened entry for any sid including `anonymous`, a widening inheritance change, removing the authorization property, a second authorization property, or authorization entries on an item created inside a guarded folder) is reverted and recorded as GRANT_VIOLATION whoever makes it, unless it is an HTTP save by an Overall/Administer holder. Guarding follows renames and moves. A Pipeline build's reverted save is named in its build log. (D-58, D-58a)
- Acceptance: guarding covers the item and every item below it. A Replay, Pipeline Rebuild or Restart from Stage by a user whose permission for it comes only from a grant puts the job into the "changed under a grant" state. The state ends only through the explicit "Mark as reviewed" action (POST, native Item/Configure or Overall/Administer, not by a grant), which writes a `GUARD_REVIEWED` record; an ordinary save does not end it. (D-58b)
- Acceptance: a run started by a Replay, Pipeline Rebuild or Restart from Stage of a grant-only user carries a marker and a `REPLAY_UNDER_GRANT` record; the review surfaces list such runs; re-running a marked run (Replay, Pipeline Rebuild, Restart from Stage, rebuild-plugin Rebuild) is refused with a plain message for everyone but an Overall/Administer holder, before and after the review; a re-run of a marked run (an administrator's included) is marked too, and a re-run whose source cannot be resolved is refused. (D-58c)
- 전역 옵션 `allowAdminSelfApproval` 기본값 true. false면 관리자도 직무 분리 적용.
- Acceptance: the permission names used by JCasC and scripts are `BatchControl/<Name>`, independent of the display title of the group. (D-41)
- Acceptance: a user who holds none of the Batch Control permissions does not see the Batch Control root action in the navigation, and `/batch-control/` and every URL beneath it answer 404 to them. A user who holds at least one Batch Control permission but not the one a section needs still gets 403 from that section. A link inside the Batch Control screens is shown only to a user who may open its target. (hosting review, #31)
- Acceptance: `BatchControl/Request` is checked on the job for every job-specific use — submitting, viewing, cancelling and re-designating a run request, and requesting an activation or hold. A user who holds Request only on some jobs or folders reaches the Batch Control root page and the run requests section once they have requests of their own, and the activations section whenever they reach the root page (D-38c), sees there only requests they may see, and after submitting lands on their request's page (never a 404 or 403). Deciding whether such a user may open the sections does not scan all items. (D-38b)
- Acceptance: the Batch Control pages navigate with a tab bar under the app bar instead of a side panel; each tab is shown only to a user who may open its section; a tab shows a badge with the number of pending requests awaiting the viewer's decision (designated approver) or, for others, the viewer's own pending requests; the breadcrumb of the root action offers the same sections as a context menu. Existing URLs are unchanged. (D-61)
- Acceptance: the per-job request action (`/job/<name>/batch-control/`) is absent, not merely refused, for a user who may not use it: it is not listed on the job page, and its URL and every URL beneath it (for example `submit`) answer 404. (hosting review, #31)

**3. 결재자 지정**
플러그인 설정에 결재 가능자 목록(사용자 ID)을 등록하고, 요청자는 그중 한 명을 골라 요청합니다.
결재 전까지 요청자가 결재자를 바꿀 수 있고, 변경 내역도 요청 이력에 남습니다.
- 수용 기준: 목록에 없는 사용자를 결재자로 지정하면 요청 생성이 거부된다.
- 수용 기준: 결재 시점에 결재자가 `Approve` 권한을 잃었으면 결재가 거부된다(목록 등재 + 권한 보유 둘 다 필요).
- Acceptance: the job's own approver list (`jobApprovers`) in force at decision time applies to the deciding approver, and it applies when the designation is changed even if the requester has since lost `Item/Read` on the job (the job is resolved as SYSTEM for this check after the caller's permission checks). User ids are compared with Jenkins' configured user id strategy, not by plain string equality.
- 수용 기준: 요청자 본인을 결재자로 지정할 수 없다(관리자 자가 결재 허용 시 관리자 예외).
- 수용 기준: 결재자 변경 시 요청 이력에 (이전 결재자, 새 결재자, 변경자, 시각)이 남는다.
- Acceptance: the requester designates one or more approvers (form field `approvers`, one user id per entry). Every designated approver must pass the checks above, and the requester may not be among them (administrator exception as above). Any one of them may approve or reject; the first decision closes the request and the record names who decided (`decidedBy`). Changing the designation edits the set and is recorded as (previous set, new set, changed by, time). (D-37, D-69)
- 수용 기준(D-37로 집합에 적용): 그 요청의 지정 결재자만 결재할 수 있다. 결재자 목록에 등재된 다른 사용자나 관리자도 대신 결재할 수 없다. 지정 결재자가 부재일 때는 결재 전까지 요청자가 결재자를 변경해 처리한다. (D-29)

**4. 이력 저장소**
요청·결재·권한·실행·변경·오류를 빌드와 독립된 저장소(`$JENKINS_HOME/batch-control/`)에 기록해, 빌드가 삭제되어도 남습니다.
컨트롤러 재시작 후에도 대기 중인 요청과 유효한 권한 부여가 유지됩니다.
- 수용 기준: 빌드 보관 정책으로 빌드가 삭제된 뒤에도 해당 RunRecord와 관련 요청이 조회된다.
- 수용 기준: PENDING 요청이 있는 상태에서 재시작하면 요청이 그대로 PENDING으로 복구된다.
- 수용 기준: 승인됐지만 큐 투입 전 재시작된 요청은 재시작 후 자동으로 큐에 투입된다(중복 투입 없음).
- 수용 기준: 기록은 추가 전용이다. 수정·삭제 API가 없다(보관 기간 만료 삭제 제외).
- Acceptance: the file name derived from an item's full name is unique: no two distinct full names map to the same stored file, including names long enough to be shortened. Files in the pre-release shortened form are not read (D-43).
- Acceptance: stored file names, month bucket names and record ids do not depend on the controller's default locale or time zone: they use ASCII digits (`Locale.ROOT`) and the plugin clock's zone, so retention and the history screens keep working after a locale change. Dates on screens are rendered in the plugin clock's zone.
- Acceptance: a history, dashboard or change-list page load reads a bounded amount of data regardless of how many records a month holds (it does not materialise a whole month bucket to render one page), and a query span is capped by records, not by files. Only records inside the requested period count toward the cap; when the cap is reached the screen says so and points to the CSV export, which is complete. A record the fast reader cannot scan is read with the reference parser before it is ever skipped, so no valid record disappears from a screen. A line longer than 1 MiB is skipped on every read path, including CSV exports, and a screen that skipped one says so, so screens and exports never silently disagree. (security-11) Closed requests and grants past the retention period are deleted by retention like the other records, and the per-minute expiry work does not scan closed requests.
- Acceptance: retention deletes in bounded batches and never holds the store lock that the queue gate or build completion waits on for longer than one batch; the queue gate performs no store I/O that can wait behind bulk work. Scheduling never stalls because retention is running.


### 실행 통제

**5. 실행 요청과 결재**
승인 대상 잡은 파라미터와 사유를 입력해 실행을 요청하고, 결재자가 파라미터 원본을 확인한 뒤 승인 또는 반려합니다.
승인되면 요청 당시 파라미터 그대로 자동 실행되며, 반려 시에는 사유가 필수입니다.
- 수용 기준: 사유가 비어 있으면 요청 생성이 거부된다.
- 수용 기준: 승인 후 실행된 빌드의 파라미터가 요청 시 저장된 파라미터와 정확히 일치한다.
- 수용 기준: 승인 후 파라미터를 바꾸는 경로가 없다. 바꾸려면 새 요청을 만들어야 한다.
- 수용 기준: 반려 사유가 비어 있으면 반려가 거부된다.
- 수용 기준: 반려된 요청에서 요청자는 반려 사유, 반려한 결재자, 반려 시각을 볼 수 있다. (D-28)
- 수용 기준: 사유가 4,000자를 초과하거나 문자열 파라미터 값이 개당 10,000자를 초과하면 요청 생성이 거부된다. (R-7 부분 채택, D-22)
- 수용 기준: 실행된 빌드에는 요청 ID, 요청자, 결재자가 Cause와 빌드 Action으로 표시된다.
- 잡 단위 설정(JobProperty): `approvalRequired`(bool), 잡별 결재자 목록 제한(선택).
- Acceptance: submitting a run request (job request form, incident rerun, and the service API) requires `BatchControl/Request` and `Item/Read` on the job; `Item/Build` is not required. The request detail page and the approver notification state when the requester lacks `Item/Build` on the job. An incident rerun also needs `BatchControl/ViewHistory`, which the Incidents screen requires. (D-38a, D-57)
- Acceptance: whether an approver may approve is decided by the approval policy alone. An approved request is submitted even when the approver holds only `Item/Discover` or no permission on the job: the job lookup for the submission runs as SYSTEM after the policy check.
- Acceptance: a run request keeps the submitted parameter values with their types, for every parameter type including core `file` and the file-parameters plugin's `stashedFile` and `base64File`, and the approved build receives exactly those values, including the original value of a password or other sensitive parameter and the original file. A `PasswordParameterValue` and any other `Secret` field are stored only in Jenkins' encrypted form, never in plaintext. Wherever a request's parameters are shown or written as text (request screens, CSV, history) they come from a masked map derived once at submission; run records and incidents mask the build's own values with the same rule (D-72b (8)): a sensitive value appears as `********`, and a file value only as `[file] <original file name>`, never its content, Base64 or a server path. (D-72, supersedes P-03 option ①)
- Acceptance: file content stays where its parameter type stores it (core `$JENKINS_HOME/fileParameterValueFiles/`, file-parameters `stashedFileParameterValueFiles/`, Base64 inside the request's values file); Batch Control does not copy it. When a request ends without a run (rejected, cancelled, expired, invalidated, or its approved run could not be queued) Batch Control disposes of those temporary files; once the approved run is queued, the queue and the build own them. (D-72)
- Acceptance: each parameter name appears at most once in a run request; a repeated name is refused before anything is stored, and approval and submission refuse a request whose stored values do not match the displayed ones (repeated or differing names, a value that cannot be loaded). Every stored value obeys the display length limit and contains only characters XML can store; a failed save leaves nothing behind. A request with parameters whose values file is missing or unreadable cannot be approved or run. Typed values live in a separate values file per request, which is deleted when the approved run starts, when the approved run's queue item is cancelled (the request stays APPROVED and is never submitted again), or when the request ends; listings never load it. (D-74) (D-72b)
- Acceptance: a run request submission whose body is larger than the cap (default 100 MB, system property `io.jenkins.plugins.batchcontrol.maxRequestBodyBytes`) is refused (HTTP 413) before Batch Control reads the form, with no request created and nothing kept in JENKINS_HOME, because requesting a run does not require `Item/Build` (D-38a). This is the first of two stages (D-74 (2)): after the form is read and before anything is stored, what the request would keep (the sizes of the uploaded parts the submission read, plus its stored texts; a `base64File` value counts as its Base64 text, about 4/3 of the file) is checked against the same cap, so a body under the cap can still be refused with 413 and nothing kept. The upload itself and core's own temporary parsing are not prevented by this cap (core behaviour); the instance-wide limit is Stapler's `FILEUPLOAD_MAX_SIZE` system property, documented in LIMITATIONS. (D-72, D-72a)

**6. 실행 경로 차단**
UI, REST API, CLI 등 승인 없는 수동 실행은 모두 큐 진입 단계에서 차단합니다.
cron 정기 실행과 상위 잡 연쇄 실행은 통과가 기본이며, 잡별로 조정할 수 있습니다.
- 수용 기준: 실행 통제 on + `approvalRequired` 잡에서 다음 경로가 모두 큐에 들어가지 않는다: 빌드 버튼, `/job/X/build` POST, `/job/X/buildWithParameters`, CLI `build`, Pipeline Replay, `build` 스텝으로 호출된 상위 잡(정책이 통과로 설정되지 않은 경우).
- 수용 기준: 승인된 요청의 투입(플러그인 내부 Cause 포함)은 통과된다.
- 수용 기준: TimerTrigger(cron) Cause는 기본 통과, 잡 설정 `blockTimer=true`면 차단.
- 수용 기준: UpstreamCause는 기본 통과, 잡 설정 `blockUpstream=true`면 차단. `allowedUpstreamJobs` 목록이 있으면 그 잡만 통과.
- 수용 기준: `blockUpstream=true`일 때 `allowedUpstreamJobs`가 비어 있거나 미설정이면 모든 상위 잡이 차단된다(빈 목록은 미설정과 동일). `blockUpstream=false`면 목록과 무관하게 모든 상위 잡이 통과한다. (R-1, D-16)
- 수용 기준: 승인 투입 마커는 요청 ID에 묶이고 큐 투입 1회로 소비된다. 동일 마커의 재사용(재큐·rebuild 등)은 차단되고 기록된다. (R-8, D-23)
- 수용 기준: 1회 소비 규칙은 **사람이 올린 실행 요청에만** 적용된다. cron 정기 실행은 `blockTimer=true`가 아닌 한 요청·승인 없이 계속 통과하므로, 정기 배치 잡은 실행 통제를 켜도 스케줄대로 계속 실행된다. 정기 실행을 멈추려면 `blockTimer`(또는 잡 비활성화)를 켠다. (D-25)
- 수용 기준: 동일 마커의 재사용 시도는 감사 이력에 `type=MARKER_REUSE_BLOCKED` 레코드로 남고 대시보드·CSV(`changes.csv`)에서 조회된다. `user`는 **재사용을 시도한 계정**이며(원 승인의 요청자가 아니다), 레코드는 소비된 요청 ID와 대상 잡을 식별할 수 있어야 한다. 정상 실행 1회만으로는 이 레코드가 생기지 않는다. 조회는 12절의 `ViewHistory` 게이트를 따른다. (D-30)
- 수용 기준: 통제 설정 변경(전역 스위치·잡 속성 토글), 대상 잡의 설정 변경, 권한 창 만료 중 어느 것도 이미 실행 중인 빌드를 중단시키지 않는다. 차단은 큐 진입 단계에서만 일어난다. (D-27)
- 수용 기준: 차단 시 사용자에게 "승인 필요" 안내와 요청 화면 링크가 표시된다(조용한 실패 금지).
- Acceptance: when a person's build submission with parameters (the build form, the parameters dialog of the new job page, `/job/X/buildWithParameters` from a browser) is refused on an approval-required job, the refusal leads to the Request Run form of that job with the submitted parameter values filled in (sensitive values are never carried; file values are not carried either, issue #10); nothing is queued and nothing is stored until the requester submits the form. Opened from the new job page, the refusal does not fall back to the classic build form. (D-60)
- Acceptance: the page of an approval-required job always shows a notice that manual runs need an approved request, with a link to the request screen, so a user whose click on another plugin's build button (Rebuild, Retry, a customised build button) only gets that plugin's generic failure message still sees why and where to go. A refusal page links the request screen only for users who may open it and otherwise says whom to ask. (e2e-03 DEF-01, DEF-02)
- Acceptance: every refused retry, rebuild or re-queue of an approved or manual run is recorded in the history (MARKER_REUSE_BLOCKED when it presents a consumed marker, otherwise TRIGGER_BLOCKED with the cause kind), not only logged. (e2e-03 DEF-03)
- Acceptance: a refused timer, upstream or Replay submission writes a `TRIGGER_BLOCKED` change record (job, cause kind, the switch that blocked it), coalesced so that one job and cause kind produce at most one record per hour; the record appears in the history and in `changes.csv`.
- Acceptance: the hourly coalescing applies to unattended submissions only. A refused re-run submitted by a person (Retry, Rebuild, Replay) writes one record per attempt naming the user and the re-run build; a repeat of the same attempt by the same user within one minute is merged. Per user at most 20 such records per rolling 10 minutes; the next refusal in that window writes one summary record, later ones are only counted, and one closing record gives the number of all refusals beyond the 20 per-attempt records, the one that opened the summary included, when the window ends (records are never rewritten). Every such record names the re-run build, for Replay too. (D-51, D-51a)
- Acceptance: when `blockTimer` or `blockUpstream` is on for a job, the job page shows a notice naming the switch that blocks it and how to clear it (the job configuration, which is change-controlled), to users who can read the job. The notice is absent when both are off or when run control is off.
- Acceptance: a run shown on any Batch Control screen links to its build page only when the viewer has `Item/Read` on the job; otherwise it is plain text. The rule is the same on every screen, including history, and is defined once.
- 수용 기준: 승인 대상 잡의 사이드바에서 "Build Now"가 "Request Run"으로 대체된다.
- Acceptance: the guarantees above hold when other plugins that add build buttons or triggers are installed and configured on the job (customize-build-now, rebuild, parameterized-trigger, build-token-root, naginator): a manual run of an approval-required job does not reach the queue without an approved request, and an approved run is queued exactly once. An automatic retry (for example naginator) is judged by the causes of the build it retries: a retry of an approved manual run is refused like any other re-use of the approval, and the way to run it again is a new request (for example the incident rerun request); a retry of a timer or upstream run follows the timer and upstream rules. Where another plugin replaces or relabels the build link, the job page still offers "Request Run". (hosting review, #34, #36)
- Acceptance: with queue plugins installed (lockable-resources, throttle-concurrents) the gate still refuses unapproved manual runs and an approved run still starts. With authorize-project configured, a build authorised as another user does not bypass the gate.

**6a. Activation approval (D-39, DESIGN-ACTIVATION-APPROVAL)**
Creating a job does not put it into service. Whether a job may run unattended (any cause that is not a human submission: timer, upstream, SCM, and unclassified causes including a submission with no cause or only `LegacyCodeCause`) is decided by an approved activation stored by the plugin, outside the job's configuration, so no configuration write path (web form, REST `config.xml`, CLI, script, JCasC) can activate a job.
- Acceptance: while run control is on, for any unattended cause on any non-computed job, the queue gate passes only if the job is activated AND its `blockTimer`/`blockUpstream` does not block the cause, regardless of `approvalRequired` (D-46). Clearing `blockTimer`/`blockUpstream`/`approvalRequired` or removing the job property never lets a non-activated job run unattended, for any user including administrators.
- Acceptance: an activation is requested per job (`ACTIVATE`, with a reason and one or more designated approvers, D-37) with the existing `BatchControl/Request` permission plus `Item/Read` on the job, and decided like a run request (designated approver, self-approval rules, notifications D-36). Approval marks the job activated and writes an `ACTIVATED` change record; rejection changes nothing.
- Acceptance: putting a job on hold (`HOLD`) is also a request that needs approval (symmetric; owner decision in #15). An approved hold marks the job not activated and writes a `HELD` change record. Jenkins' own Disable Project and the global run-control switch remain available as immediate emergency stops.
- Acceptance: an activation is per job and survives configuration edits; renaming or moving a job keeps its activation; deleting a job removes it. Exception (D-59a): while run control and change control are both on, a job moved by a user without Overall/Administer starts over like a newly created job — it is no longer activated and gets the D-34 lock (`approvalRequired`, `blockTimer`, `blockUpstream` on, `allowedUpstreamJobs` emptied), recorded as a `HELD` change record naming the move.
- Acceptance: jobs that exist when this version is first installed are recorded as activated (`activatedBy = (upgrade)`, one `ACTIVATED` record each), exactly once, keyed by a stored schema marker, so installing or upgrading never stops an existing schedule. Jobs created afterwards while run control is on start not activated; a job created while run control is off is recorded as activated at creation (D-45), so turning run control on never stops an existing schedule. Computed folders (multibranch projects, organization folders) carry the activation for their children: one created while run control is on starts not activated, the ACTIVATE/HOLD request is made on it, and a computed child passes only if its nearest computed-folder ancestor is activated (D-46). Seeding and D-45 apply to computed folders as to jobs.
- Acceptance: the job page shows whether the job is activated or on hold and links to the activation request form; pending activation and hold requests appear in the approval inbox; the history shows `ACTIVATED`/`HELD` records.
- Acceptance: a sub-item of a job (a multi-configuration project's configuration, a Maven module) has no activation or approval state of its own; the queue gate applies its parent job's: the parent's runs start their configurations, and a direct submission of a configuration is judged by the parent's approval property and activation. (D-82)
- Acceptance: with run control off nothing changes (unattended runs pass as before).
- Acceptance: activation state is truthful and fails closed: a job re-created under a deleted job's name, a job whose state file could not be deleted, or a job created while a failed seeding is retried starts not activated; an approved HOLD or a deletion is never undone by a stale cached value or by a stale pending ACTIVATE (approving one request invalidates the job's other pending activation requests). Notifications name the action (ACTIVATE or HOLD). (security-13)
- Acceptance: an automatic retry of a build is unattended even when the retried build was started by a person, so it needs activation on every job (D-47); a Rebuild click is a person acting. Where an item's activation is carried by a computed-folder ancestor, the ancestor's name and state are shown only to viewers with Item/Read on it. (security-14)

**7. 요청 만료와 취소**
승인 대기 요청은 설정된 기간이 지나면 자동 만료되고, 요청자는 결재 전까지 취소할 수 있습니다.
승인 후 일정 시간 안에 실행되지 않은 건도 무효 처리해 오래된 승인으로 실행되는 것을 막습니다.
- 전역 설정: `pendingTimeoutHours`(기본 72), `approvedRunTimeoutMinutes`(기본 60).
- 수용 기준: 만료 시 상태가 EXPIRED로 바뀌고 이력에 남는다.
- 수용 기준: APPROVED 상태에서 `approvedRunTimeoutMinutes` 안에 큐 투입이 안 되면 EXPIRED가 된다(재시작 복구 지연은 예외로 허용: 복구 시점 기준으로 판정).
- 수용 기준: 취소는 요청자 본인 또는 `Manage` 권한자만 가능하고, PENDING 상태에서만 가능하다.
- 수용 기준: 요청 상태 전이는 원자적이다(compare-and-set). 동시 승인 2건 중 정확히 1건만 성립하고 빌드는 정확히 1회만 투입된다. 승인과 취소가 경합하면 하나만 성립하며 상태 혼합(예: CANCELLED에 executedRunId)이 없다. 큐 투입 직전에 만료를 재확인(check-at-submit)하여 만료된 승인 건은 절대 투입되지 않는다. (R-5, D-20)
- 수용 기준: PENDING/APPROVED 요청의 대상 잡이 rename 또는 move되면 요청은 상태 INVALIDATED로 종료되고 이력에 남는다. (R-6, D-21)
- Acceptance: deleting a job, or a folder above it, ends its PENDING and APPROVED run requests as INVALIDATED with a reason that names the deletion, like the activation requests of a deleted job. Approving a run request whose job no longer exists is refused with nothing scheduled, and a job re-created under the same name never runs a request made for the deleted one. (#38, D-87)

### 변경 통제

**8. 임시 권한 요청 (JIT)**
잡 생성·수정·삭제가 필요하면 대상 범위(잡 또는 폴더), 행위(CREATE/CONFIGURE/DELETE 중 다중 선택), 시간, 사유를 지정해 권한을 요청하고 결재를 받습니다.
승인되면 그 시간 동안 본인 계정으로 직접 작업하며, 만료 시각이 지나면 즉시 회수됩니다.
- 전역 설정: `grantDurationOptions`(기본 15, 30, 60분), `maxGrantMinutes`(기본 240).
- 수용 기준: 승인 즉시 요청자가 지정 범위에서 지정 행위의 Jenkins 권한(Item/Create, Item/Configure, Item/Delete)을 얻는다.
- 수용 기준: 권한 창 만료 후의 거부 화면은 Jenkins 코어의 것을 그대로 쓴다(코어가 그 화면의 확장점을 제공하지 않으며, 가로채는 구현은 인스턴스 전체의 권한 거부에 영향을 준다). 만료 안내와 재요청 동선은 권한 화면에서 제공한다: 활성 창의 남은 시간, 만료된 창의 이력, 재요청 링크. 편집 중이던 설정 값의 복원은 제공하지 않는다. (D-33)
- 수용 기준: 지정 범위 밖 잡에는 권한이 생기지 않는다.
- Acceptance: a CREATE request may carry an optional name restriction (form field `createNamePattern`): an exact item name, or a Java regular expression written as `/regex/`. It is validated at submission (an invalid regex is refused) and shown to the approver. With a restriction, the grant lets its holder create only items directly inside the window's folder (D-71) whose name matches in full; an attempt with another name is refused with HTTP 4xx, leaves no item, and is recorded as GRANT_VIOLATION. Without a restriction the grant behaves as before. The restriction does not otherwise affect CONFIGURE or DELETE, and it does not make any rename possible: no window allows a rename, the D-35c Configure on an item the holder created through the window included, so a rename resting on a window is refused whatever the new name (D-71c). A validation request while typing a name (GET) is refused without a record. Names longer than 255 characters are refused, and matching a regular expression is bounded in time so that no pattern can stall the instance. Children that a computed folder creates during indexing are not name-restricted (they are created by the system; LIMITATIONS). (D-40, D-40a)
- 수용 기준: 만료 시각 경과 후 첫 권한 검사부터 거부된다(타이머 의존 없음).
- 수용 기준: 활성 권한이 있는 상태에서 재시작해도 만료 전이면 유지, 만료 후면 즉시 없음.
- 수용 기준: `Manage` 권한자는 활성 권한을 즉시 회수(revoke)할 수 있고 이력에 남는다.
- 수용 기준: 변경 통제 on 상태에서, 권한 부여 없이 Item/Configure·Create·Delete·Move를 가진 사용자가 있으면 관리 화면에 경고(AdministrativeMonitor)가 표시된다. (Move: D-59)
- Acceptance: when change control is on and the installed authorization strategy is not a Batch Control strategy (for example a plain strategy installed on the security page or by JCasC; role-strategy 927 or newer keeps the Batch Control strategy on its own saves, D-35f, D-35g), the administrative monitor `batch-control-strategy` says that grants do not confer and offers to install the matching Batch Control strategy. It also warns when change control is on and no build authenticator (Authorize Project) is configured, because builds running as SYSTEM are outside the self-grant guard. (D-35a, D-35d; replaces the former Role Strategy "JIT unsupported" notice)
- Acceptance: while change control is on and the configured build authenticators would let a build of a job without its own build authorization and without a user cause run as SYSTEM, the `batch-control-strategy` monitor shows one fixed warning (no job names) and the detail page of a pending request that includes CONFIGURE shows the same warning to users who may decide it or hold BatchControl/Manage, not to the requester. A global default build authorization removes both. The same applies when that build identity holds Overall/Administer or root-level Item/Configure without a grant. (D-50, D-50a, D-50b)
- 수용 기준: 실행 통제가 켜져 있으면, 활성 Grant(권한 창) 안에서 생성된 잡은 `approvalRequired=true`가 기본으로 적용된다(권한 창을 이용해 무승인 실행 경로를 심는 것 방지). (R-2 경량 채택, D-17)
- 수용 기준: 멀티브랜치 프로젝트가 자동 생성한 브랜치 자식 잡은 이 기본값에서 제외한다(설정 화면이 없어 해제 경로가 없고, 재인덱싱 시 설정이 재생성되기 때문). 해당 잡의 실행은 10절대로 기록된다. (D-32)
- 수용 기준: 실행 통제가 켜져 있으면, 새로 생성되는 잡은 `blockTimer=true`·`blockUpstream=true` 로도 시작한다. 잡을 만드는 행위가 그 잡을 가동시키지 않는다. 가동하려면 잡 설정에서 해당 스위치를 꺼야 하며, 그 변경은 변경 통제 대상이라 권한 창과 기록을 거친다. 계산된 자식 잡은 D-32대로 제외한다. (D-34)
- 수용 기준: 실행 통제가 켜져 있으면, **새로 생성되는 모든 잡**에 `approvalRequired=true`가 기본으로 적용된다(생성 경로·생성자와 무관). 통제를 풀려면 잡 설정을 바꿔야 하며, 그 변경 자체가 변경 통제 대상이라 기록에 남는다. 이 기본값 자체는 사람이 직접 누르는 실행에만 영향을 준다. 다만 새로 생성되는 잡에서는 D-34가 타이머·상위 잡 트리거를 같은 시점에 함께 막는다. 따라서 이 항목이 원래 달고 있던 “자동 생성 잡의 자동 빌드는 멈추지 않는다”는 단서는 D-34로 대체된다. 새로 생성되는 잡에서 계속 통과하는 것은 SCM 트리거뿐이고, 이미 존재하는 잡은 영향을 받지 않는다. (D-31, 뒷부분은 D-34로 대체)
- Implementation: Batch Control variants of the matrix-auth and role-strategy strategies that layer active grants over the parent's ACLs, chosen on the global security page or installed by the monitor's migration button. The withdrawn generic wrapper is not supported: the plugin was never released, so no saved configuration needs converting. (D-35a, D-35e)
- Acceptance: when the installed strategy is matrix-auth's global matrix strategy and change control is on, the `batch-control-strategy` monitor shows, before its install action, a warning that converting makes per-item authorization properties effective, and the install action asks for confirmation repeating it. (D-35d, hosting review)
- Acceptance: while change control is on, moving an item (folders plugin Item/Move) by a user without Overall/Administer is allowed only if the user holds Item/Delete on the item and Item/Create on the destination, each native or from an active grant; a CREATE grant's name restriction is matched against the moved item's name, and the installed project naming strategy must accept the moved name in the destination (D-59b). A refused move changes nothing, answers with a plain message naming what is missing and is recorded as GRANT_VIOLATION (the same refused move by the same user — same item and destination — is recorded once per minute; repeats inside that minute go to the Jenkins log only, D-73); the refusal page links the item for a user who may read it. With change control off moves behave as in Jenkins. Because a window's DELETE never applies to an item group (D-71), moving a folder, multibranch project or organization folder cannot be authorised through permission windows. (D-59, D-59a, D-71)
- Acceptance: a permission window names exactly one item, a job or a folder of any kind (scope type `ITEM`), and confers nothing on any other item, including the items inside a folder. CONFIGURE (and the EXTENDED_READ that comes with it, P-11) covers that item only; for a folder that is the folder's own configuration. CREATE applies only to a modifiable item group (a regular folder; not a job, not a computed folder such as a multibranch project or an organization folder) and allows creating items directly inside it only, never inside a nested folder; the D-40 name restriction applies unchanged, and the D-35c Configure covers the items the holder created through the window whose parent is that folder. DELETE applies only to a job (an item that is a `Job`, including multi-configuration and Maven projects, whose sub-items are part of the job); it never applies to an item group that is not a job, so no window lets its holder delete a folder, multibranch project or organization folder. No window allows renaming the item (D-71c); a rename needs an administrator or the user's own permissions under core's rule. (D-71, replaces D-65)
- Acceptance: a window applies to its item, not to a name: when an administrator or a user with their own permissions renames or moves the item (no window allows a rename, D-71c), the window follows it — windows on the items below a renamed or moved folder follow too — and deleting the item ends the window, as do creating a new item at the window's name and starting Jenkins after the item vanished; so renaming, moving, swapping or re-creating items never makes a window reach an item nobody approved. While change control is on, no window allows renaming any item (job or folder of any kind) — neither a CONFIGURE window nor DELETE and CREATE windows combined; a refused rename, whatever URL form reaches core's rename endpoints, is recorded as GRANT_VIOLATION (D-73 coalescing applies). The stored scope is the item's canonical full name, whatever spelling was typed. (D-71a, D-71c, D-74, security-34, security-36, security-38)
- Acceptance: a window's followed name is shown only to a viewer who may read the item: when its item was renamed or moved (D-74), the grant pages, the grants list and the expiry notice show the item's current full name only to a viewer (or recipient) with Item/Read on it; anyone else sees the approved name with a fixed note that the item was moved and its new location is not visible to them. Administrators see the current name. (D-75, security-39 S-39-04)
- Acceptance: a window never bypasses the installed project naming strategy (core `ProjectNamingStrategy`, e.g. role-strategy's `RoleBasedProjectNamingStrategy`): a CREATE window lets its holder create only names that strategy also admits, and the D-40 name restriction applies on top. (coverage inventory T-08-168)
- Acceptance: a window may name only a top-level item; a sub-item of a job (a multi-configuration project's configuration, a Maven module) is refused at submission. The approval page of a CONFIGURE request whose item is an item group states that the group's settings apply to the items inside it (for example implicitly loaded Pipeline libraries) and that reconfiguring a multibranch project or organization folder can create or delete its generated items. (D-71a, spec-review-S6 O-1, security-34 S-34-02)
- Acceptance: a request for CREATE or DELETE on an item kind where it cannot apply is refused at submission. The request records the item's kind (descriptor id and display name, for example "Pipeline", "Freestyle project", "Folder", "Multibranch Pipeline", "Organization Folder"); approval is refused when no item exists at that name any more or its kind has changed. The request screens show the item's kind with its icon; there is no scope type selector. Stored windows and requests with the earlier scope types (JOB, FOLDER, FOLDER_ONLY) are not converted (D-69). (D-71)
- Acceptance: while the requester types the item of a permission window request, the form suggests the full names of jobs and folders the requester may read (Item/Read), names that start with the typed text first and then names that contain it, ignoring case, at most 20. Sub-items of a job (D-71a) are not offered. The suggestion request (GET) needs BatchControl/RequestGrant, changes nothing and writes no record. (#8)
- Acceptance: while change control is on, a refused deletion of an item says that an approved `DELETE` window on that item is needed; it points a user who may request windows to the Grants screen and tells anyone else to ask an administrator, and for an item kind on which no window can allow deletion it says so. (e2e-03 DEF-26, D-78)
- Acceptance: requesting a permission window (from the Batch Control grants page, a job page or a folder page) and requesting a run (the Request Run action) open a dialog on the current page; submitting it creates the request and leads to its detail page. The grants, run requests and activations pages list pending requests first, then active and ended items, each row linking to its detail page; the request form is not the first thing on the grants page. A permission window can be revoked from its own detail page as well as from the list. A refused direct build still leads to the pre-filled Request Run page (D-60). (D-66)
- Acceptance: the Batch Control overview shows pending counts only as tab badges (no separate banner or count table). The run dashboard shows at most the 50 most recent runs and links to History for the rest. (D-67)
- Acceptance: a holder of Overall/Administer sees an "Administrator checks" section on the Batch Control overview that lists the findings of the `batch-control-strategy` and configure-without-grant monitors with their remedies, also while a monitor is disabled or dismissed; no other user sees the section, and the missing-strategy warning comes from `batch-control-strategy` alone. (D-86)
- Acceptance: new requests and windows get UUID identifiers; identifiers stored in the earlier format still load and resolve. (D-68)

**9. 변경 자동 기록**
생성·수정·삭제·이름변경·이동은 경로와 무관하게 누가·언제·무엇을 바꿨는지 자동으로 기록됩니다.
설정 변경은 변경 전후 config.xml diff를 남기고, 어떤 권한 부여 건에서 이뤄진 작업인지 연결합니다.
- 수용 기준: UI, REST(`config.xml` POST), CLI, Job DSL 경로의 변경이 모두 ChangeRecord로 남는다.
- 수용 기준: CONFIGURE 변경에 unified diff가 저장된다(비밀값은 마스킹).
- 수용 기준: 변경 시각에 변경자의 활성 Grant가 있으면 `grantId`가 연결되고, 없으면 `grantId=null`로 남아 "권한 부여 없는 변경"으로 조회된다.
- 수용 기준: 변경 통제 스위치가 꺼져 있어도 실행 통제가 켜져 있으면 변경 기록은 남는다(기록은 어느 스위치든 켜지면 활성).
- Acceptance: a save that changes no user-editable configuration writes no CONFIGURE record. The comparison ignores the `plugin="name@version"` attributes, so saving an unchanged job after a plugin upgrade records nothing. (hosting review, #20)
- Acceptance: a computed folder (multibranch project, organization folder) saving itself during indexing writes no CONFIGURE record unless its user-editable configuration changed.
- Acceptance: with jobConfigHistory installed, each configuration save still produces exactly one CONFIGURE record.

### 운영 관리

**10. 실행 기록 대시보드**
모든 잡의 실행을 잡, 실행 원인, 사용자, 파라미터, 결과, 소요 시간과 함께 한 화면에서 봅니다.
중단된 실행은 누가 중단했는지 표시하고, 승인 건과 실행 결과가 한 줄로 이어집니다.
- 수용 기준: Freestyle과 Pipeline 빌드가 모두 기록된다.
- 수용 기준: 실행 원인이 USER / TIMER / UPSTREAM / APPROVED_REQUEST / SCM / OTHER로 분류된다.
- 수용 기준: ABORTED 빌드에 중단 사용자가 표시된다(Jenkins가 기록한 경우).
- 수용 기준: APPROVED_REQUEST 실행은 요청 ID로 요청 상세에 연결된다.
- 기본 화면: 최근 7일, 페이지당 50건.

**11. 오류 자동 등록과 처리**
FAILURE, UNSTABLE 결과는 사람 개입 없이 오류 건으로 자동 등록되고, 담당자가 확인·조치·완료 상태를 관리합니다.
오류 건에서 바로 재실행을 요청할 수 있고, 재실행이 성공하면 원래 건에 자동으로 연결됩니다.
- 전역 설정: `incidentResults`(기본 [FAILURE, UNSTABLE]; ABORTED 추가 가능).
- 수용 기준: 실행 원인과 무관하게(cron 포함) 해당 결과면 Incident가 생성된다.
- 수용 기준: Incident 상태는 OPEN → ACKNOWLEDGED → RESOLVED, 각 전이에 사용자·시각·코멘트가 남는다.
- Acceptance: acknowledging, resolving and commenting on an incident require `BatchControl/ViewHistory` (the permission that opens the Incidents screen); each is a POST with a crumb; a user without it gets 403 and nothing changes. (documents existing behaviour, coverage inventory G-H6)
- Acceptance: "Request rerun" on an incident creates a RunRequest linked by `incidentId` that carries the failed run's own parameter values, including the original secret values and the file values that can still be recovered from the build. When a value cannot be recovered (for example a stashed file, which the build clears when it completes, or a deleted build), the rerun does not create the request directly: it opens the job's Request Run form prefilled with the recoverable non-sensitive values (secret values are never prefilled, as for D-60). (D-72) A request submitted from that prefilled form is linked to the incident only after the server re-validates the incident reference it carries (the incident exists, belongs to that job, and the submitter holds `BatchControl/ViewHistory`), so a successful run records `resolvedByRunId` as for a direct rerun. (D-72a)
- 수용 기준: 연결된 재실행이 SUCCESS면 Incident에 `resolvedByRunId`가 자동 기록된다(상태 자동 변경은 하지 않음; 사람이 RESOLVED 처리).
- 수용 기준: 콘솔 로그 마지막 100줄이 Incident에 발췌 저장된다.
- 수용 기준: logTail 저장 시 해당 빌드의 비밀 파라미터 값과 Jenkins `Secret` 평문이 발견되면 마스킹된다. 그 외 콘솔에 출력된 비밀은 탐지 한계로 마스킹되지 않을 수 있으며 이를 문서에 명시한다. (R-4, D-19)

**12. 조회와 집계**
기간, 잡, 사용자, 결과, 처리 상태로 필터링해 조회하고 월 단위 집계 화면을 제공합니다.
감사·보고용으로 CSV 내보내기를 지원하며, 보관 기간은 전역 설정으로 정합니다.
- 전역 설정: `retentionMonths`(기본 24).
- 수용 기준: 실행 기록, 오류 건, 변경 기록, 요청 이력 각각에 기간 필터와 CSV 내보내기가 있다.
- 수용 기준: 월별 집계에 실행 수, 성공/실패/불안정 수, 오류 건 OPEN/RESOLVED 수, 요청 승인/반려 수가 나온다.
- 수용 기준: `ViewHistory` 권한이 없으면 모든 조회 화면과 CSV가 403이다.
- 수용 기준: 보관 기간 지난 월 파일은 주기 작업이 삭제하고, 삭제 사실을 ChangeRecord(type=RETENTION)로 남긴다.
- 수용 기준: CSV 셀 값이 `=`, `+`, `-`, `@`로 시작하면 수식으로 해석되지 않도록 무해화(`'` 프리픽스)된다. (R-3, D-18)
- Acceptance: every CSV export starts with the UTF-8 byte order mark (EF BB BF) followed by the header row; the content type stays `text/csv; charset=UTF-8` and the columns are unchanged. (D-85)

### 확장 (2차)

**13. 알림**
요청 발생, 결재 완료, 권한 만료 임박, 오류 발생 시 관련자에게 알립니다.
이메일을 기본으로 하고 Slack 등은 확장 포인트로 붙입니다.
- Acceptance: an extension point `io.jenkins.plugins.batchcontrol.ops.BatchControlNotifier` (events in `ops.NotificationEvent`) receives events `REQUEST_CREATED`, `APPROVERS_CHANGED`, `APPROVED`, `REJECTED`, `EXPIRING` for run and change requests, and `GRANT_EXPIRING` for an active change window. Recipients: the designated approvers for `REQUEST_CREATED`/`APPROVERS_CHANGED`, the requester for the others. `EXPIRING` fires once for a pending request and `GRANT_EXPIRING` once for an active change window, `notifyBeforeExpiryMinutes` (global, default 10) before the expiry; an approved request that is not executed in time receives `EXPIRED` (below) but no `EXPIRING`. A notifier failure never fails or delays the request action. (D-36, D-77)
- Acceptance: the shipped e-mail notifier uses the Mailer plugin, an optional dependency, and the recipient's Mailer e-mail address. It sends nothing unless the global option `emailNotifications` (default false) is on, so an upgrade changes nothing. Without Mailer the option is absent and nothing breaks. Messages contain the request id, job or scope, requester, a link and the reason, all plain text; each reason line is quoted so it cannot pose as another field. The link uses the configured Jenkins URL only and is left out when none is configured. The dispatch queue is bounded; on overflow a notice is dropped and logged. (D-36)
- Acceptance: events `CANCELLED`, `EXPIRED` and `INVALIDATED` are added. The requester receives `EXPIRED` (pending, or approved but not executed) and `INVALIDATED`, with the reason; the designated approvers of a pending request receive `CANCELLED`, `EXPIRED` and `INVALIDATED`. The requester is not mailed about their own cancel. (D-54)
- Acceptance: CSV exports keep their existing columns; the `approver` column holds the designated set joined by `;`, and a `decidedBy` column is appended at the end. (D-37)

**14. REST API와 외부 연동**
요청·결재·이력 조회를 API로 제공해 사내 결재 시스템과 연동할 수 있게 합니다.
전역 설정은 JCasC, 승인 이벤트는 Audit Log 플러그인으로 내보낼 수 있게 합니다.

**15. 다단계 결재선**
단일 결재자 구조를 순차 결재선으로 확장합니다.
합의, 전결, 대결(부재 시 대리 결재)은 이후 검토 항목으로 둡니다.

- Acceptance: a save of the Batch Control configuration that changes anything besides the two switches writes one `CONFIG_CHANGE` record naming the user and each changed field with old and new values; installing or reverting a Batch Control strategy writes one `STRATEGY_CHANGE` record; a save that changes nothing writes nothing. (D-52)
- Acceptance: an approver id that names no existing user and that the security realm does not resolve is refused with a message next to the field naming it; the input is kept and nothing is saved. If the realm cannot be asked the id is accepted with a warning. An empty approver list is refused while either switch is on. (D-53)
- Acceptance: while the target job is disabled, the run request decision form says so and Approve is refused with a message; Reject stays possible. A request approved before the job was disabled shows that its queued run was cancelled and will not start, and that the job must be enabled and a new run request submitted. (D-55, D-83)

## 3. 데이터 모델 (MVP)

```
RunRequest        id, jobFullName, parameterValues[] in the separate values file <id>.values.xml (typed ParameterValue, Secret fields encrypted; D-72, D-74),
                  parameters(Map, masked display form derived at submission; D-72), reason, requester,
                  approvers[], decidedBy?,
                  status(PENDING|APPROVED|REJECTED|CANCELLED|EXPIRED|EXECUTED|INVALIDATED),
                  createdAt, decidedAt, decisionComment, selfApproved,
                  approverChanges[{from[],to[],by,at}], incidentId?, executedRunId?
GrantRequest      id, scope{type: ITEM, fullName}, itemKind{descriptorId, displayName, iconClassName} (D-71),
                  actions[CREATE|CONFIGURE|DELETE],
                  durationMinutes, reason, requester, approvers[], decidedBy?, createNamePattern?,
                  status(PENDING|APPROVED|REJECTED|CANCELLED|EXPIRED), createdAt, decidedAt, decisionComment,
                  approverChanges[{from[],to[],by,at}]
Grant             id, grantRequestId, user, scope, itemKind (copied from the request, D-71), actions,
                  grantedAt, expiresAt, revokedAt?, revokedBy?, revokedReason? (D-63)
RunRecord         runId(jobFullName#number), jobFullName, number, causeType, user?,
                  parameters, result, startedAt, durationMs, abortedBy?, runRequestId?
Incident          id, runId, jobFullName, result, status(OPEN|ACKNOWLEDGED|RESOLVED),
                  transitions[{status,by,at,comment}], logTail, rerunRequestIds[], resolvedByRunId?
ChangeRecord      id, type(CREATE|CONFIGURE|DELETE|RENAME|MOVE|CONFIG_TOGGLE|RETENTION|GRANT_REVOKE|
                       MARKER_REUSE_BLOCKED),
                  target, user, at, grantId?, diff?, detail
```

## 4. 상태 머신

```
RunRequest:  PENDING -> APPROVED -> EXECUTED
             PENDING -> REJECTED | CANCELLED | EXPIRED
             APPROVED -> EXPIRED (approvedRunTimeout)
             PENDING | APPROVED -> INVALIDATED (대상 잡 rename/move, D-21; job or folder deleted, D-87)
GrantRequest: PENDING -> APPROVED(=Grant 생성) | REJECTED | CANCELLED | EXPIRED
Grant:       ACTIVE -> EXPIRED(시각) | REVOKED(수동)
Incident:    OPEN -> ACKNOWLEDGED -> RESOLVED  (역방향 없음, RESOLVED에서 코멘트 추가는 가능)
```

## 5. 전역 설정 항목과 기본값

| 키 | 기본값 | 설명 |
|---|---|---|
| runControlEnabled | false | 실행 통제 스위치 |
| changeControlEnabled | false | 변경 통제 스위치 |
| approvers | [] | 결재 가능자 ID 목록 |
| allowAdminSelfApproval | true | 관리자 자가 결재 |
| pendingTimeoutHours | 72 | 대기 만료 |
| approvedRunTimeoutMinutes | 60 | 승인 후 미실행 만료 |
| grantDurationOptions | [15,30,60] | 권한 요청 시간 선택지(분) |
| maxGrantMinutes | 240 | 권한 상한 |
| incidentResults | [FAILURE, UNSTABLE] | 오류 등록 대상 |
| retentionMonths | 24 | 보관 기간 |

## 6. 비기능 요구

- 재시작 내구성: 4번 수용 기준.
- 성능: 하루 5,000 실행 규모에서 대시보드 최근 7일 조회가 2초 이내(로컬 기준). This is measured once with a generated dataset and the figure is recorded in `docs/HOSTING-READINESS.md`.
- 보안: 모든 상태 변경은 POST + 권한 체크. CSRF crumb 준수. Secret parameter values never appear in plaintext in Batch Control's files, screens, records or CSV: a run request stores them in Jenkins' encrypted form so that the approved build receives the original value, and every textual form shows `********` (D-72).
- 보안: 사용자 입력(사유, 파라미터 값, 잡 이름)은 모든 화면 렌더링에서 이스케이프되어 스크립트·태그로 실행되지 않는다. (R-3, D-18)
- Usability (e2e-03): every button, link and form is shown only to users who can use it; every refusal, on the web, the CLI or a Replay, tells the user in plain words why and what to do instead (no bare "Access Denied", stack trace, "Oops!" page or generic toast from our own code); invalid input is refused with a message next to the field and the user's input is kept; no link leads to a 404 or 403 page; recorded history names who did what (for example who cancelled a request and why a request was invalidated). The BatchControl/Manage permission is enough to open and save the Batch Control configuration.
- 호환: 최신 LTS 라인. Freestyle, Pipeline(WorkflowJob), Folder 지원. Multibranch는 기록만(통제 대상 아님, 문서에 명시).
