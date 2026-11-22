# Batch Control user guide

This guide is the detailed reference for Batch Control. The
[README](../README.md) has the overview, requirements, installation and a quick
start; the complete list of known limitations is in
[`LIMITATIONS.md`](LIMITATIONS.md). To try the plugin on a throwaway local
Jenkins first, use the Docker Compose demo described in
[`demo/README.md`](../demo/README.md). Identifiers such as `D-37` (a design
decision) or "SPEC item 3" let the maintainer trace a statement; you do not need
them to use the plugin. They point into [`SPEC.md`](SPEC.md),
[`DECISIONS.md`](DECISIONS.md) and [`ARCHITECTURE.md`](ARCHITECTURE.md), which
are written largely in Korean, with the newer sections in English.

**Contents:** [Why](#why) · [The two controls](#the-two-controls) · [What run
control stops](#what-run-control-stops-and-what-it-does-not) ·
[Activation](#activation-putting-a-job-into-service) ·
[Configuration](#configuration) · [The screens](#the-screens) · [Notifications
and approver sets](#notifications-and-approver-sets) ·
[Limitations](#limitations) · [Compared with other
approaches](#compared-with-other-approaches) · [Roadmap](#roadmap)

## Why

Starting a production batch job (a Spring Batch job, a data load, a settlement
run, a reconciliation script) by hand outside its schedule, or changing what it
does, is an operational change: who started it, with which parameters, who
agreed to it, and why? Jenkins answers only in part. `Item/Build` lets a user
start any job at any moment, `Item/Configure` lets them change one permanently,
and the record lives in the build, so it disappears when the build is rotated
out. Batch Control adds the approval, the time limit and a record independent of
build retention. Installing it changes nothing: both controls are off until an
administrator turns them on, independently.

## The two controls

Recording does not depend on which control is on: if either one is, runs,
changes, decisions and failures are recorded.

### Run control

With run control on and "Require approval to run" set on a job, whoever wants to
run the job submits the parameters and a reason. A designated approver sees
exactly those parameters and approves, or rejects with a reason the requester
can read ([More than one approver](#more-than-one-approver)). On approval the
plugin queues the build with the stored parameters. No path edits parameters
after approval, and the approval is consumed by a single queue submission, so it
cannot be replayed, re-queued or rebuilt; the attempt is blocked and recorded.

Run control also decides whether a job may run *unattended*: a job created while
run control is on does not run on a timer, an upstream trigger, an SCM trigger
or a webhook until an approver has activated it
([Activation](#activation-putting-a-job-into-service)).

### Change control

A user who does *not* hold the standing permission to create, configure or
delete a job asks for a permission window instead: one item (a job, or a folder
of any kind), some combination of `CREATE`, `CONFIGURE` and `DELETE`, a duration
and a reason. The window grants nothing on any other item, not even on the jobs
and folders inside a folder it names; to change a job in a folder, request a
window for that job.

| Action | Applies to | Allows |
|---|---|---|
| `CONFIGURE` | any job or folder | The item's own configuration only. A folder's settings still affect the items inside it: implicitly loaded Pipeline libraries, for example, and the items a multibranch project or organization folder generates, which reconfiguring it can create or delete. |
| `CREATE` | a regular folder only (not a job, multibranch project or organization folder) | Creating items directly inside it, never inside a nested folder. |
| `DELETE` | a job only | Deleting it. |

- A request for `CREATE` or `DELETE` where it cannot apply is refused at
  submission. Every request shows the item's kind (Pipeline, Freestyle project,
  Folder, Multibranch Pipeline, Organization Folder, ...) next to its name, and
  approval is refused if the item is gone or its kind has changed.
- **A window follows its item, not its name.** When an administrator or a user
  with their own permissions renames or moves the item, the window follows it,
  as do the windows on the items inside a renamed or moved folder. Deleting the
  item ends the window, as do creating a new item at its name and starting
  Jenkins after the item vanished. So renaming, moving, swapping or re-creating
  items never makes a window reach an item nobody approved.
- **A window adds.** It adds its permissions to what the user already has, for
  as long as it lasts; it never takes anything away and imposes nothing on
  standing holders, whom the "standing change permissions" monitor points out.
- **Deleting is vetoed.** While change control is on, deleting a job needs an
  active `DELETE` window even from a user whose standing permissions allow it;
  only administrators are not vetoed. No window confers `Item/Delete` on a
  folder, multibranch project or organization folder (deleting one deletes
  everything inside it), so only an administrator can delete one of those.
- **Moving** an item between folders (the folders plugin's `Item/Move`, UI and
  REST) counts as deleting it here and creating it there: a user without
  `Overall/Administer` needs `Item/Delete` on the item and `Item/Create` at the
  destination, each standing or from an active window, and a `CREATE` window's
  name restriction is matched against the moved item's name. No window can
  authorise moving a folder of any kind; that needs an administrator or standing
  `Item/Delete` on it. While run control is also on, a job moved by a
  non-administrator arrives not activated and locked, recorded as `HELD`, like a
  new job ([Limitations](LIMITATIONS.md#moving-items)).
- **Renaming** a job or folder of any kind is never authorised by a window,
  because permissions matched by full name follow a rename. It needs an
  administrator, the user's own `Item/Configure` on the item, or their own
  `Item/Delete` on it plus `Item/Create` in its parent.
- A refused move or rename changes nothing, tells the user why and is recorded
  as a `GRANT_VIOLATION` (once per minute for the same attempt).

The holder works under their own account, with every usual Jenkins safeguard in
place. The approver decides *who* may change *what*, and *for how long*; the
change itself does not exist yet and is never shown to them, and what was
changed is answered afterwards by the change record and its diff. Expiry is a
clock comparison at each permission check: no timer, no revocation step, and the
window stays closed across a restart. A `BatchControl/Manage` holder can revoke
one early.

## What run control stops, and what it does not

Run control intercepts builds at queue entry and decides on the *cause* Jenkins
attached to the submission.

- **Refused:** "Build Now", the `build` and `buildWithParameters` REST
  endpoints, `jenkins-cli build`, Pipeline Replay, and a submission carrying a
  build token, which Jenkins attributes to no user at all.
- **Admitted:** an approved run request (once) and, **on an activated job
  only**, timer (cron) and upstream triggers, SCM triggers, webhooks,
  submissions from scripts and plugin code, and any other cause that is not a
  person acting now. An automatic retry (naginator and the like) counts as
  unattended even when the retried build was started by a person.

So what started a build decides, not how often the job has run: a person needs
an approved request every time, the hundredth run included, while automation
needs only the job's activation, decided once per job, and existing jobs are
activated (below). `Block cron (timer) triggers` and `Block upstream triggers`
(with a list of upstream jobs that may still trigger) make a single job
stricter; nothing narrows the SCM or other unattended paths on an activated job
beyond activation.

### Activation: putting a job into service

A job created while run control is on starts **not activated**: no unattended
cause (timer, upstream, SCM, webhook, script or plugin code, automatic retry)
starts it until an approver approves an `ACTIVATE` request. This holds for every
user including administrators. Clearing `Require approval to run`, `Block cron
(timer) triggers` or `Block upstream triggers`, or removing the job property,
does **not** activate a job, and no configuration write path (web form, REST
`config.xml`, CLI, script, JCasC) can: activation is stored by the plugin,
outside the job configuration.

- The job's page says whether it is activated, not activated or on hold, and
  offers a `BatchControl/Request` holder **Request activation** (on an activated
  job, **request a hold**). The request carries a reason and one or more
  designated approvers and is decided like a run request, on the **Activations**
  screen; approval writes an `ACTIVATED` record.
- An approved hold (`HOLD`) marks the job not activated and writes a `HELD`
  record. For an immediate stop, Jenkins' **Disable Project** and the global
  run-control switch remain available.
- An activation survives configuration edits, renames and moves, and is removed
  when the job is deleted.
- Existing schedules continue: every job and folder present when this version is
  first installed is recorded as activated once (`activatedBy = (upgrade)`); a
  job created while run control is **off** is activated at creation
  (`activatedBy = (uncontrolled)`); with run control off nothing is gated.
- Computed folders (multibranch projects, organization folders) carry activation
  for their children: one created while run control is on starts not activated,
  the `ACTIVATE`/`HOLD` request is made on the folder, and a child runs
  unattended only if its nearest computed-folder ancestor is activated. The
  child's page names that folder to viewers who may read it.

### Where refusals are shown and recorded

**Blocking happens only at queue entry.** Nothing interrupts a running build:
not a switch, a job property, a configuration change, or a window expiring
mid-build.

A person refused at a REST `build` call, at the CLI, or at the build link of a
job with parameters gets an "approval required" page or CLI message linking to
the request form. The build link of a job without parameters shows only Jenkins'
toast, "Failed to schedule build. Reload the page and try again."; the approval
notice on the job page carries the reason. Pipeline Replay (no UI channel for a
message), a build-token submission (a script reading an HTTP status) and a timer
or upstream trigger turned away by a per-job option or by activation have no
screen at all; each is logged and recorded:

- a blocked build-token attempt gets its own record;
- a person's refused Replay, Retry or Rebuild gets a `TRIGGER_BLOCKED` record
  per attempt naming the build it re-runs; a repeat within a minute is merged,
  at most 20 are listed per user in any 10 minutes, the next one writes a
  summary record saying further refusals are counted, not listed, and a closing
  record at the end of the 10 minutes gives their number and the builds. No
  record is ever rewritten;
- a refused timer or upstream submission, or any unattended submission refused
  because the job is not activated, is coalesced into at most one
  `TRIGGER_BLOCKED` record per job and cause per hour.

While `Block cron (timer) triggers` or `Block upstream triggers` is on, the
job's page shows a notice naming the switch to anyone who can read the job.

## Configuration

### 1. Turn on what you need

The same fields and checks are in two places:

- **Batch Control → Configuration** (`/batch-control-configuration/`), in the
  Batch Control page's sidebar ([The screens](#the-screens)). It needs only
  `BatchControl/Manage`, so a holder without `Overall/Administer`, to whom
  Manage Jenkins is not open, configures the plugin here. It is also listed as
  **Batch Control** on Manage Jenkins.
- The Batch Control section of **Manage Jenkins → System**, which needs
  `Overall/Manage`; a holder of `BatchControl/Manage` alone gets 403 there.

Every save that changes something is recorded: a switch flip writes
`CONFIG_TOGGLE`, any other field (the approver list included) one
`CONFIG_CHANGE` naming the user and each field's old and new value. Installing
or reverting a strategy variant (step 2) with Batch Control's buttons writes
`STRATEGY_CHANGE`; changing the strategy on **Manage Jenkins → Security** writes
no Batch Control record. A save that changes nothing writes nothing.

The section groups its fields under five headings, in the order of the table
below: **Switches**, **Approval**, **Permission windows**, **Incidents and
retention**, and **Notifications and strategy**. The last appears only while
it holds something: the e-mail switch or the strategy entry.

| Field | Default | Meaning |
|---|---|---|
| Enable run control | off | The approval gate at queue entry |
| Enable change control | off | Permission windows, the delete veto and the standing-permission monitor |
| Approvers | empty | The user IDs allowed to be designated as an approver |
| Allow administrators to approve their own requests | on | When off, separation of duties applies to administrators too |
| Pending request timeout (hours) | 72 | A pending request expires after this |
| Approved-but-not-run timeout (minutes) | 60 | An approved request that was never queued expires after this |
| Grant duration options (minutes, comma-separated) | 15, 30, 60 | The choices offered on the window request form |
| Maximum grant duration (minutes) | 240 | Upper bound on a custom duration |
| Notify before expiry (minutes) | 10 | How long before a pending request or an active grant window expires its requester or holder is notified |
| Results that open an incident (comma-separated) | FAILURE, UNSTABLE | `ABORTED` can be added |
| Retention period (months) | 24 | Month files older than this are deleted, and the deletion is recorded |
| Send e-mail notifications | off | Shown only while the Mailer plugin is installed; no mail is sent while it is off |
| Batch Control strategy | shown only while installed | Not a saved field: while a Batch Control authorization strategy variant is installed (step 2), a Jenkins administrator (`Overall/Administer`) finds **Revert to the plain strategy**, with what reverting does, under the **Advanced** button of **Notifications and strategy**, as its last entry; anyone else gets no Advanced button, no word of the revert, and reads "Only a Jenkins administrator can change the authorization strategy." instead |

A request cannot be created unless every designated approver is on the Approvers
list, and `BatchControl/Approve` is checked on them again when they decide.

**Turning change control off is an abrupt kill switch.** While it is off no
window confers anything, so every decision is the installed strategy's own.
Flipping it off *revokes* every open window, one revocation record per closure
naming who flipped it; anyone mid-change loses the permission to finish, with no
warning and no way back but a new request. The alternative was a switch that
leaves windows conferring `Item/Configure` for up to the maximum grant duration
after the control was supposedly off. Nothing accumulates while it is off: a
window cannot be requested or approved (both refusals explain themselves and are
recorded) and the Grants screen is closed, though its URL still answers with an
explanation rather than a dead link. History and Change Records keep showing the
windows that existed and the changes made under them.

### 2. Select a Batch Control authorization strategy

Change control does nothing until this is done, and it is the step most easily
missed: windows get approved and have no effect. A window confers its
permissions only when a Batch Control variant is selected *and* change control
is on; run control and recording need neither.

- Under **Manage Jenkins → Security → Authorization**, choose **Batch Control:
  Project-based Matrix Authorization Strategy** for matrix-auth or **Batch
  Control: Role-Based Strategy** for role-strategy (927 or newer). Each is a
  drop-in subclass of the upstream strategy: matrix, folder and agent
  authorization properties and role assignments stay configurable and effective.
- On a plain strategy, turn change control on first: a monitor on **Manage
  Jenkins** says grants confer nothing and offers **Install the Batch Control
  variant**, which converts the configuration in one click with every entry kept
  (there is no such button on the Security page). The monitor warns and offers
  the reinstall whenever change control is on without a variant, for example
  after a plain strategy is selected on the Security page.
- From Jenkins' built-in global "Matrix-based security", read [the conversion
  note](#converting-from-the-global-matrix-strategy-turns-on-per-item-permissions)
  first.
- To go back, a Jenkins administrator uses **Revert to the plain strategy**,
  under the **Advanced** button of the Batch Control section's **Notifications
  and strategy** group (**Manage Jenkins → System**), shown while a variant is
  installed.

While a window is open, the variant *adds* its action (`Item/Create`,
`Item/Configure` or `Item/Delete`) on the one item it names (`Item/Create`
directly inside it) and passes every other decision through, so with no active
window it behaves exactly like the plain strategy. It resolves permissions the
way Jenkins does, through the `impliedBy` chain, so a `CONFIGURE` window also
confers what Jenkins implies from `Item/Configure`
([Limitations](#limitations)). On role-strategy 927 and newer, saving **Manage
Roles** or the redesigned **Assign Roles** page keeps the variant in place.

### 3. Assign the permissions

| Permission | What it allows |
|---|---|
| `BatchControl/Request` | Create run requests for approval-protected jobs, and activation and hold requests (with `Item/Read` on the job; `Item/Build` is not required) |
| `BatchControl/Approve` | Approve or reject run, activation, hold and window requests |
| `BatchControl/RequestGrant` | Request temporary change permissions |
| `BatchControl/ViewHistory` | View the history screens, dashboards and CSV exports |
| `BatchControl/Manage` | Manage the global configuration on **Batch Control → Configuration**, and revoke windows |

`Manage` is implied by `Overall/Administer` and implies the other four, so
administrators pass every check. A requester typically holds `Overall/Read`,
`Item/Read`, `Request` and `RequestGrant`, deliberately without
`Item/Configure`, which a `CONFIGURE` window supplies. An approver usually gets
`Approve` and `ViewHistory` but not `Request`; `ViewHistory` is broader than its
name ([Limitations](#limitations)).

**On approval-required jobs, grant `Request` instead of `Build`.** A run request
needs `Request` and `Item/Read` on the job, nothing else, and while run control
is on `Item/Build` confers nothing on such a job, since every direct path is
refused and the plugin queues the approved run. Keep `Item/Build` for jobs that
do not require approval, and for the whole instance while run control is off.
Because an approval can authorise a run for someone who could not start the job,
the request page and the approver notification say so when the requester lacks
`Item/Build`; to let only Build holders ask, assign `Request` only to them. A
requester without `Item/Build` posting to `/build` from a script gets Jenkins'
own 403 ("missing the Job/Build permission"), because core checks Build first;
such users use **Request Run** or, from plugin code, the Java service API. There
is no REST API for requests ([Roadmap](#roadmap)).

### 4. Configure jobs

The job configuration's **Batch Control** section, with inline help, holds
`Require approval to run`, the two trigger overrides with their `Allowed
upstream jobs` list, and an optional job-level approver list that narrows the
global one.

While run control is on, **every newly created job starts locked**: all three
switches on and the allowed-upstream list empty, whoever created it and however.
The switches set how strict a job is once in service; they do not put it into
service. Clearing them is a recorded change that, with change control on, needs
a window, and the job still runs unattended only after an approved `ACTIVATE`
request ([Activation](#activation-putting-a-job-into-service)); clearing
`Require approval to run` does let a person use Build Now without a request.
Values in the creation payload are overwritten: an `approvalRequired=false`, a
`blockTimer=false` or an allowed-upstream list in a `config.xml` POST, a CLI
`create-job`, a Job DSL seed or a copied job. Only the job-level approver list
is carried over, since it can only narrow who may approve. If your instance
generates jobs from scripts, read [the automation
note](LIMITATIONS.md#automation-and-generated-jobs) first.

[`e2e/`](../e2e/) holds a working reference configuration (Dockerfile, plugin
list, security bootstrap, global settings) for the end-to-end suite; it is not a
deployment template, but its init scripts show each of these four steps.

## The screens

Everything is on the **Batch Control** page, `/batch-control/`. On Jenkins 2.568
its entry is not in the dashboard's left sidebar: open the **☰** (More actions)
menu in the page header and choose **Batch Control**. The page's own sidebar
lists only the sections the user may act on; a user with no Batch Control
permission gets no entry and a 404 at that URL. Records live in
`$JENKINS_HOME/batch-control/`, separately from builds, so they outlive build
rotation. They are append-only: no edit or delete API exists, only retention
expiry.

### Run Requests, and the entries on a job's page

**Run Requests** carries each request's stored parameters, reason, requester,
approver, status and decision history, and is where the approver decides. The
request page's **Decision** area follows the parameters directly: one comment
box (optional when approving, required when rejecting) and the **Approve** and
**Reject** buttons. A refused decision comes back with the comment kept. While
the job is disabled only **Reject** is offered.

- A protected job's sidebar carries **Request Run**, the same form with the
  job's parameters, and Jenkins' build entry is relabelled **Direct Build (needs
  approval)**; following that one reaches the queue with no approval and is
  refused.
- While change control is on, **Request Change Permission** appears for a user
  without `Item/Configure` on the job and opens the window form with the job
  filled in; a folder's page offers it to a user lacking a permission a window
  could add there. The form takes one full name, shows the item's kind once it
  is recognised, has no scope type to choose, and says where each action
  applies.
- A `CONFIGURE` request on a folder, multibranch project or organization folder
  warns that the folder's settings apply to the items inside it, that
  reconfiguring a multibranch project or organization folder can create or
  delete its generated items, and that the window does not allow renaming it; on
  a job it states the rename rule alone.

### Other screens

| Screen | What it shows |
|---|---|
| **Grants** | Pending window requests, the time left on an active window, the history of expired ones and a link to request another. |
| **Activations** | Activation and hold requests, the ones awaiting your decision first; the approver decides here. A request starts from **Request activation** in the job page's notice. |
| **Dashboard** (titled *Run Dashboard*) | The 50 most recent builds, with a link to **History** for older ones, in seven columns: Run (the result icon, the job, `#n` and the result label), Cause (User, Timer, Upstream job, Approved request, SCM change, Other), User, Parameters (the first three; the rest folded under "N more"; values cut at 120 characters), Started, Duration and Request id, which links an approved run to its request. |
| **History** | Runs, incidents, change records and requests filtered by period, job, user, result and status, each exportable as CSV, and a monthly summary. Runs use the dashboard's seven columns; incidents show Run, Incident, Status and Created. A CSV cell whose first non-whitespace character is `=`, `+`, `-` or `@` is prefixed so a spreadsheet does not evaluate it. |
| **Change Records** | The create / configure / delete / rename / move trail from every path (UI, REST, CLI, Job DSL), with a unified diff and the window it was made under, or an explicit note where there was none. A change with no usable earlier configuration to compare is recorded without a diff, with a note saying why ([Limitations](LIMITATIONS.md#records-and-screens) item 53). |

### Incidents

**Incidents** collects the failures that opened automatically (listed as Run,
Incident, Status and Created), each with the last 100 console lines, and offers acknowledge, resolve, comment and a rerun
request. The lifecycle runs Open → Acknowledged → Resolved, one way only,
each transition with a user, a timestamp and a comment.

A rerun carries the failed build's original parameters, password and file values
included, fixed rather than editable; its form has only the approver checkboxes,
and the reason is generated from the incident. If a value can no longer be
recovered (a stashed file, which the build removes when it completes, or a
deleted build, including a same-numbered build of a re-created job), no request
is created there: the job's Request Run form opens with the other values filled
in, the requester provides files and passwords again, and the request is linked
to the incident only after the server re-validates the reference (the incident
exists, belongs to that job, and the submitter holds
`BatchControl/ViewHistory`). A rerun needs `Request` plus `Item/Read` on the job
(not `Item/Build`), and the screen needs `ViewHistory`, so the user needs all
three; the typical roles in step 3 give that only to administrators.

## Notifications and approver sets

### Notifications

E-mail goes through the Mailer plugin, an optional dependency, and is **off by
default**: nothing is sent until an administrator turns on **Send e-mail
notifications**, shown only while Mailer is installed, so installing Batch
Control sends no mail by itself. With it on, a message goes out when:

- a request is created or its approver set changes (to the designated
  approvers), or is approved or rejected (to the requester);
- a pending request is about to expire (`EXPIRING`, to the requester) or an
  active grant window is about to end (`GRANT_EXPIRING`, to its holder), once,
  **Notify before expiry (minutes)** (default 10) beforehand;
- a request ends without a decision: the requester is told when it expires
  (pending, or approved but never run) or is invalidated, with the reason, and
  the designated approvers of a pending request when it is cancelled, expires or
  is invalidated. A requester is not mailed about their own cancel.

A message carries the reason and, only when the Jenkins URL is configured under
**Manage Jenkins → System**, a link to the request, never one guessed from the
request ([Limitations](LIMITATIONS.md#notifications-and-computed-folders)).
Other channels (Slack and the like) can be added by another plugin against the
same extension point.

### More than one approver

A run request or a grant request may designate several eligible approvers. Any
one of them may decide and the first decision closes the request; nobody else
may, not the rest of the approver list and not an administrator outside the set.
The self-approval ban applies to every member, and the requester may change the
set at any time before a decision.

### Restricting the name a CREATE window may create

A `CREATE` window request may carry an exact job name or a regular expression;
the window then confers `Item/Create` only for a new item whose name matches,
and without one it allows any name directly inside the folder. The pattern is
validated at submission and shown to the approver. It does not reach a child
that a computed folder (a multibranch project or an organization folder) creates
while indexing, since the system creates it
([Limitations](LIMITATIONS.md#notifications-and-computed-folders)).

## Limitations

The part that changes decisions; the complete list, with the reasoning, is in
[`LIMITATIONS.md`](LIMITATIONS.md).

### Administrators are recorded, not stopped

`Overall/Administer` implies every Batch Control permission, so an administrator
can approve their own requests (unless *Allow administrators to approve their
own requests* is off), switch either control off, clear a job's switches or
change the strategy; each is recorded, and stopping it is out of scope. The
gates do apply to them: on a job that requires approval their Direct Build, REST
build or Replay is refused, so they run it through a request too, and the
activation gate holds unattended runs of a job that is not activated.

### Turning change control off cuts off work in progress

Every open window is revoked at once (each revocation recorded), and a user
mid-change must request a new one once the control is back on ([step
1](#1-turn-on-what-you-need)).

### A refused configuration change still shows Jenkins' own 403

Without an active window, opening or saving a job configuration gives the stock
"missing the Job/Configure permission" page, which the plugin deliberately does
not intercept: it says nothing about windows, and looks the same to a user who
never had one and to one whose window just expired. The plugin instead puts
**Request Change Permission** on the job's sidebar before the 403, and keeps the
remaining time, expiry history and re-request link on the Grants screen.
Deleting is the exception: the plugin's own refusal says that an approved
`DELETE` window is needed, points a user who may request windows to the Grants
screen and tells anyone else to ask an administrator; for a folder, multibranch
project or organization folder it says that no window can allow it.

### A permission window covers one item

A window names one job or folder, so a change spanning several jobs needs a
window for each. `CREATE` never works at the Jenkins root, and no window can
delete or move a folder of any kind, because core deletes everything inside one
without checking it. A window follows its item ([Change
control](#change-control)), but:

- one that cannot follow for certain (its file cannot be updated with the new
  name, or another item took one of the names involved) ends, recorded with the
  reason "it could not follow its item", and its holder requests it again;
- the new name is shown only to users who may read the item where it is now;
  anyone else, the holder and the approvers included, sees the approved name
  marked "moved; its new location is not visible to you", as does the expiry
  e-mail;
- if, at startup, the change records written since the oldest open window was
  granted cannot all be read back (one damaged line is enough), every open
  window ends with the reason "its state could not be confirmed at startup";
- apart from Batch Control's storage refusing writes until a restart, one gap
  remains: an item replaced on disk outside Jenkins and then reloaded fires no
  item event, so a window naming it applies to the replacement. That needs
  file-system access; reloading a single item needs only `Item/Configure` on it,
  not `Overall/Administer` ([item
  11](LIMITATIONS.md#the-authorization-strategy)).

### A `CONFIGURE` window confers whatever Jenkins implies from `Item/Configure`

On the plugin set this project is built against: `Item/ExtendedRead` (which
reads `config.xml`), `Credentials/UseItem` and `Run/Replay`, and any installed
plugin can add a permission implied by `Item/Configure`. A standing matrix entry
confers the same, but a window is approved by a non-administrator shown only the
word `CONFIGURE`. Mind `Run/Replay`: run control still refuses a replay of a job
that requires approval, but on a job without run control a window holder can
replay a build with a modified Pipeline script.

### No window allows renaming a job or folder

Not a `CONFIGURE` window, not `DELETE` and `CREATE` windows combined (core's
other rename path), and not the Configure a `CREATE` window's holder keeps on
what they created. Otherwise, under role-strategy, whose item roles match full
names by pattern, a holder could rename a job or a folder and its contents into
one of their own patterns and keep that role after the window ended. The cost:
renaming needs an administrator or the user's own permissions ([Change
control](#change-control)). Jenkins still shows **Rename** to a window holder;
the refusal appears on the rename page for any URL form (encoded ones and core's
`doRename` included) and is recorded as a `GRANT_VIOLATION` (once per minute for
the same attempt).

### On an item a grant has touched, only an administrator can widen authorization

A Pipeline `properties` step saves authorization entries whatever account the
build runs as, so a `CONFIGURE` holder could keep access after the window ends.
While change control is on, Batch Control guards every item under an active
grant and every item changed under a grant (including a Pipeline job on which a
grant holder ran a Replay, a Pipeline Rebuild or a Restart from Stage), together
with everything below it.

- On a guarded item any change that widens access is put back and recorded,
  whoever makes it (an HTTP save gets a 403 message), except a save made over
  HTTP (web UI, `config.xml` POST, REST or CLI over HTTP) by an
  `Overall/Administer` holder. So a Jenkinsfile, Job DSL, JCasC or non-HTTP CLI
  change that widens authorization is put back even for an administrator (CLI
  over WebSocket or SSH included); use the web UI or the CLI over HTTP.
- A Pipeline build whose save was put back names the reverted entries in its
  log; a save whose build cannot be identified (a seed job saving another job, a
  Freestyle build) gets only the change record.
- A changed item stays guarded until **Mark as reviewed**: administrators use
  the Manage Jenkins monitor, which lists the waiting items; users with native
  Configure and `BatchControl/Request` use a job's Batch Control page (without
  `Request` it is not shown, so they ask an administrator). Folders, multibranch
  projects and organization folders have no such page, so only an administrator
  reviews them. A review writes `GUARD_REVIEWED`; an ordinary save is not one.
- Items no grant touched are unaffected; deleting a guarded item and re-creating
  it under the same name drops the guard.

### Run builds under a low-privilege account as well

A build running as SYSTEM or as an account with Configure can change whatever
that account may. Set Authorize Project's global default build authorization to
an account without Configure, for example **Run as Specific User** with a
dedicated build account that has neither `Overall/Administer` nor
`Item/Configure`, nor Configure on folders or jobs. **Run as the user who
triggered the build** is safe only with such a fallback, since timer and SCM
builds have no triggering user and would run as SYSTEM. A per-job strategy is
not enough: anyone who can configure the job, a window holder included, can
remove it. While change control is on and builds can run as SYSTEM or as an
account with Configure, the monitor says so, and a pending `CONFIGURE` request's
page warns its approvers and `BatchControl/Manage` holders. The check reads the
build account's permissions at the Jenkins root only (Configure on a folder or
job goes undetected), cannot judge authenticators that decide by job type,
folder or caller, and is cached for five minutes, except that replacing the
authenticators through the security configuration updates it at once.

### Grants work through Batch Control's own strategy variants

Any strategy other than the two variants ([step
2](#2-select-a-batch-control-authorization-strategy)) gets run control and
recording only, and a monitor says so. role-strategy must be 927 or newer, the
release with the redesigned **Assign Roles** page. The variant's descriptor
forwards to role-strategy's own methods instead of copying them, so validation
behaves as on the plain strategy, and a regression test fails Batch Control's
build if a later role-strategy release breaks it.

### Converting from the global matrix strategy turns on per-item permissions

The built-in global "Matrix-based security" ignores the authorization properties
saved on jobs, folders and agents. After converting to **Batch Control:
Project-based Matrix Authorization Strategy**, which grants need, all of them
take effect, stale ones included, and anyone with `Item/Configure` on an item
can edit its permissions. The monitor's **Install the Batch Control variant**
button, the explicit step, says the same. Review the properties first.

### A protected job refused at queue entry fails its caller

A Pipeline `build` step that hits the gate ends the upstream job as `FAILURE`,
even with `wait: false`. That is Jenkins' behaviour, not a choice made here.

### Plan for the new-job lock and activation before enabling run control

The lock overwrites what the creation payload supplied ([step
4](#4-configure-jobs)), so a Job DSL or JCasC definition pinning `blockTimer:
false` is not idempotent against a fresh creation and seems ignored, and the job
starts not activated, which only an approved `ACTIVATE` request changes. A
generated nightly job therefore misses its first night, but not silently: its
page says it is not activated (naming `blockTimer`/`blockUpstream` while a
switch is on), each refusal writes a `TRIGGER_BLOCKED` record, and the
controller log gets a line at most hourly per job. It needs a second pass to
clear the switches and an approver to activate it.

### Some re-run links stay visible on a job that requires approval

Jenkins' build link (relabelled **Direct Build (needs approval)**), Pipeline's
**Replay** and its own **Rebuild** on a build page, and naginator's **Retry**
are drawn for everyone with the underlying permission, and Batch Control cannot
remove them. A click is refused and nothing is queued; the job and build pages
explain why and point to **Request Run**, and a refused Pipeline **Rebuild**
shows the "Approval required" page and is recorded. On a job without parameters,
**Direct Build (needs approval)** gets only Jenkins' toast, which wrongly
suggests retrying, and that first click writes no record. The rebuild plugin's
**Rebuild**, a different link, is hidden.

### Other plugins' build buttons fail with their own generic message

A refused run from naginator's Retry, Rebuild or Rebuild Last where shown, or
from a button another plugin customised, gets that plugin's message ("Failed to
schedule build", "Failed.") with nothing about approval. Nothing was queued; the
job's page shows the reason: a notice that manual runs need an approved request,
linking **Request Run**, and the activation notice saying whether unattended
runs (an automatic retry among them) are allowed, with **Request activation**.

### Secrets survive only as far as detection reaches

An incident's stored log tail masks only the build's sensitive parameter values
and Jenkins `Secret` plaintexts; a token echoed by a script, a stack trace or a
third-party tool is kept and shown verbatim. Run request values are kept with
their types, so the approved run gets the original secret and file, for every
parameter type including core `file` and the file-parameters plugin's
`stashedFile` and `base64File`. Secrets are stored only in Jenkins' encrypted
form and shown as `********`, a file only as `[file] <original file name>`; an
incident rerun reuses only what the build still holds ([Incidents](#incidents)).
A run request submission is capped at 100 MB, but the instance-wide upload limit
is Jenkins' own ([Limitations](LIMITATIONS.md#records-and-screens) items 31 and
32).

### `BatchControl/ViewHistory` is an instance-wide audit read

The history screens, the dashboard and the CSV exports show every request and
run, with job names, parameter values, reasons and decision comments, to any
holder, with no `Item/Read` check. It also authorizes acknowledging and
resolving incidents, so a read-only auditor account can close them.

### Out of scope by design

Bypass by `Overall/Administer`, detecting edits made directly on disk,
restarting or resuming a step *inside* the batch application, and controlling
changes to Pipeline scripts kept in Git.

## Compared with other approaches

- The Pipeline `input` step gates a point *inside* a run that has started, works
  for Pipeline jobs only, and keeps its record in the build. Batch Control gates
  the *start* of a run, for any job type, and keeps the record independently of
  build retention.
- Audit Trail, AuditFlow and Job Configuration History record what happened, and
  do it well (Job Configuration History keeps diffs with their author; AuditFlow
  adds a searchable store and an export), but none puts a control plane in front
  of the record: a request, a designated approver, a decision, and a permission
  that ends by itself.
- Matrix and role-based authorization decide by who you are, not for how long.
  Batch Control layers over matrix-auth or role-strategy and adds permissions
  that exist only inside an approved window.

## Roadmap

Deliberately not in this release: a REST API, and approval events exported to
the Audit Log plugin; sequential multi-stage approval chains (today's designated
set is decided by whichever member acts first). The global configuration and the
authorization strategy variants already round-trip through JCasC.
