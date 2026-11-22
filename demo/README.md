# Batch Control demo

A throwaway Jenkins with [Batch Control](../README.md) installed and configured,
to try run approval and permission windows in a few minutes.

> **This is a local demo, not a setup to copy.** The passwords are fixed and
> printed below, builds run on the built-in node, and Jenkins listens on
> 127.0.0.1 only. For a real instance, start from the
> [user guide](../docs/USER-GUIDE.md#configuration).

## What you need

- Docker with Docker Compose v2 (Docker Desktop, or Docker Engine with the
  compose plugin).
- About 1 GB of memory free for Docker, and 1 GB of disk for the image.
- Internet access on the first start, which downloads the Jenkins image and the
  plugins.

## Start it

```bash
cd demo
docker compose up
```

The first start builds the image, which takes a few minutes; later starts take
seconds. When the log says `Jenkins is fully up and running`, open
<http://localhost:8080/>. There is no setup wizard.

Port 8080 taken? Run `DEMO_PORT=8081 docker compose up` (or put `DEMO_PORT=8081`
in a file `demo/.env`) and open <http://localhost:8081/>.

## Logins

| User | Password | Can |
|---|---|---|
| `requester` | `batch-demo` | ask for runs and permission windows |
| `approver-1` | `batch-demo` | approve or reject them, and read the history |
| `admin` | `batch-demo` | everything (Jenkins administrator) |

Use a private window or a second browser for `approver-1`, so that both users
stay logged in.

Already set up: run control and change control are on, `approver-1` is the
approver, and administrators may not approve their own requests. The folder
`batch` holds two jobs with **Require approval to run** checked:
`daily-settlement` (parameters `BUSINESS_DATE`, `SCOPE`, `DRY_RUN`) and
`nightly-load`. Their build steps only print what they would do. `requester`
also holds Job/Build, only so that step 1 can show the refusal: requesting a
run needs just `BatchControl/Request` with Overall/Read and Job/Read.

## Walkthrough

The vendor file for 8 October arrived late, so the settlement for that day has
to run again.

1. **Build Now is refused.** Log in as `requester` and open
   **batch » daily-settlement**. The sidebar offers **Request Run**, and Build
   Now has become **Direct Build (needs approval)**. Click it, enter
   `2026-10-08` as `BUSINESS_DATE` and click **Build**. Nothing is queued: you
   land on the Request Run form with your values filled in, under "The build was
   not started: this job only runs through an approved run request." A script
   that posts the parameters to `build` or `buildWithParameters` gets HTTP 400,
   "Approval required: …".
2. **Request the run.** Back on the job, click **Request Run**. Enter a reason
   (`Re-run the settlement for 8 October: the vendor file arrived late.`),
   leave `approver-1` checked, set `BUSINESS_DATE` to `2026-10-08`, `SCOPE` to
   `CARD`, clear `DRY_RUN`, and click **Submit Request**. The request is
   Pending.
3. **Approve it.** Log in as `approver-1`. Open the **☰** menu at the top right,
   then **Batch Control** → **Run Requests** and the request. The reason, the
   requester and the parameters are on one page. Click **Approve**.
4. **The build runs, once.** Open **batch » daily-settlement** → **#1**: Success,
   started by "Approved batch run request … (requested by requester, approved by
   approver-1)", with exactly the parameters that were approved. Its console
   prints `Settling business date 2026-10-08, scope CARD, dry run false`.
5. **The history.** As `approver-1`, open **Batch Control** → **History**.
   **Runs** lists #1 with the cause *Approved request*; **Requests** shows who
   asked (`requester`), who decided (`approver-1`) and the status *Executed*.

Optionally, a permission window:

6. As `requester`, open **batch » nightly-load**: there is no **Configure**.
   Click **Request Change Permission**. **Configure** and 15 minutes are
   already selected; give a reason and click **Request Grant**.
7. As `approver-1`: **Batch Control** → **Grants** → the request → **Approve**.
8. As `requester`, reload the job: **Configure** is there until the window
   closes. Change the description and save. **History** → **Changes** shows the
   change, by `requester`, with the window's ID.

## Things you will notice

- History → Changes already holds two `ACTIVATED` records by `(upgrade)`: the
  jobs existed before Batch Control first started, so it keeps them in service,
  as it does on an instance where it is newly installed. A job you create now
  starts not activated and needs an approved activation before a timer or an
  upstream job may start it ([Activation](../docs/USER-GUIDE.md#activation-putting-a-job-into-service)).
- `admin` sees warnings on **Manage Jenkins**, and `approver-1` one on the
  Configure window request, that builds run as SYSTEM because no build
  authenticator (Authorize Project) is configured. That is true of this demo;
  the [user guide](../docs/USER-GUIDE.md#run-builds-under-a-low-privilege-account-as-well)
  explains what to set up on a real instance.
- `casc.yaml` is applied again on every start: the users, their permissions,
  the two switches, the approver list and the self-approval setting go back to
  what it says on a restart, whatever was changed in Jenkins meanwhile.

## Stop and reset

```bash
docker compose down       # stop; `docker compose up` continues with the same data
docker compose down -v    # stop and delete everything: builds, requests, history
```

`docker image rm batch-control-demo:1545.v013d127dfcf5` removes the image as
well.

## What is in it

| File | What it does |
|---|---|
| `docker-compose.yml` | One Jenkins service on 127.0.0.1, a volume for `JENKINS_HOME` |
| `Dockerfile` | `jenkins/jenkins:2.580.2-lts-jdk21` with the plugins, jobs and `casc.yaml` |
| `plugins.txt` | Batch Control `1545.v013d127dfcf5`, matrix-auth, Configuration as Code and their dependencies, every version pinned |
| `casc.yaml` | The three users, their permissions and the Batch Control settings |
| `jobs/` | The folder `batch` and its two jobs, copied into `JENKINS_HOME` on the first start |

Java 25 instead of 21: `docker compose build --build-arg JENKINS_TAG=2.580.2-lts-jdk25`,
then `docker compose up`.
