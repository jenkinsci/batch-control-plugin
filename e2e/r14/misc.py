"""e2e-12 part 4: the remaining controls, one section per letter (python misc.py CHPBTRSMKD).
C configuration page (Save, refused save, Manage-only user, roles without access)
H History (filters, kinds, monthly, CSV/JSON links), Changes/Incidents month navigation
P paging Previous/Next on list pages (rows differ, Previous returns)
B breadcrumbs and the Batch Control breadcrumb context menu
T tab badges against the pending rows they count
R refusal pages (approval required, grant required, move refusal) and their links
S strategy monitor: Mark as reviewed, Revert (Cancel/OK), Install (Cancel/OK)
M job page "Mark as reviewed" (JobRequestAction) and Request again on the grants page
K dark theme render of every Batch Control page
Rows: out/misc.jsonl.

H1 (2026-10-24): the rows are judged at the end (ci/h1_rules.py judge_misc): statuses per role against MISC_STATUS
(both directions), the saved and the refused value, the History kinds, filter, date messages, downloads and monthly
page, paging, breadcrumb and menu links (and the menu per role), tab links, the refusal pages and their links, the
strategy monitor's Revert/Install/Mark as reviewed, the job page's Mark as reviewed, Request again, the dark theme.
Recorded so that they mean that: the configuration link is counted on /manage/ before navigating away (M-01); each
console list holds only the messages of the page it is about (M-02, M-04); an inline validation message is listed
once (M-03); the build entry of the new job page is looked for in the main panel too (M-08); the move refusal gets its
premise, a CREATE window for mover1 on the destination folder team, so that cloudbees-folder lists the destination,
and the window is revoked afterwards (M-10)."""
import json, re, sys, time
from lib import opened, gone, Session, close, api, groovy, BASE, clean, pw
import lib

WANT = sys.argv[1] if len(sys.argv) > 1 else "CHPBTRSMK"
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"


ROWS = []


def emit(sec, row):
    row["sec"] = sec
    ROWS.append(row)
    lib.log("misc", row)
    print(json.dumps(row)[:400])


def strategy():
    return groovy("return jenkins.model.Jenkins.get().getAuthorizationStrategy().getClass().simpleName").replace("Result: ", "")


def cfg_value(name):
    return groovy(f"return io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get().{name}").replace("Result: ", "")


# ---------------------------------------------------------------- C
def sec_C():
    for role in ["admin", "manager", "approver-1", "requester", "nobc"]:
        s = Session(role)
        s.go("/manage/" if role in ("admin", "manager") else "/")
        link_visible = s.page.locator("a[href*='batch-control-configuration']").count()  # M-01: counted on this page
        nc = len(s.console)
        r = s.go("/manage/batch-control-configuration/")
        st = r.status if r else None
        row = {"role": role, "link_visible": link_visible, "page_status": st}
        if st == 200:
            row["buttons"] = [t.strip() for t in s.page.locator("#main-panel button, #main-panel a.jenkins-button").all_inner_texts() if t.strip()]
            row["console"] = s.console[nc:][:10]  # M-02: this page's messages only
            # valid save: change pending timeout 1 -> 2
            f = s.page.locator("input[name='_.pendingTimeoutHours']")
            old = cfg_value("pendingTimeoutHours")
            f.fill("2")
            with s.page.expect_navigation() as nav:
                s.page.locator("button[name=Submit], button.jenkins-button--primary", has_text="Save").first.click()
            row["save_status"] = nav.value.status
            row["save_landing"] = s.page.url.replace(BASE, "")
            row["after_save_value"] = cfg_value("pendingTimeoutHours")
            s.shot("#main-panel", f"C-{role}-after-save")
            # refused save: negative value
            s.go("/manage/batch-control-configuration/")
            s.page.locator("input[name='_.pendingTimeoutHours']").fill("-5")
            s.page.locator("input[name='_.pendingTimeoutHours']").blur(); s.page.wait_for_timeout(1200)
            msgs = [t.strip() for t in s.page.locator(".validation-error-area .error").all_inner_texts() if t.strip()] or \
                [t.strip() for t in s.page.locator(".error").all_inner_texts() if t.strip()]
            row["inline_validation"] = list(dict.fromkeys(msgs))[:3]  # M-03: the area and its .error child are one message
            with s.page.expect_navigation() as nav:
                s.page.locator("button[name=Submit], button.jenkins-button--primary", has_text="Save").first.click()
            row["refused_status"] = nav.value.status
            row["refused_text"] = re.sub(r"\s+", " ", s.text())[:300]
            row["refused_links"] = [(a.inner_text()[:30], a.get_attribute("href")) for a in s.page.locator("#main-panel a[href]").all()][:5]
            row["after_refused_value"] = cfg_value("pendingTimeoutHours")
            s.shot("#main-panel", f"C-{role}-refused")
            # Apply button (core f:apply) if present
            s.go("/manage/batch-control-configuration/")
            ap = s.page.locator("button", has_text="Apply")
            row["apply_button"] = ap.count()
            if ap.count():
                s.page.locator("input[name='_.pendingTimeoutHours']").fill("1")
                ap.first.click(); s.page.wait_for_timeout(2000)
                row["apply_value"] = cfg_value("pendingTimeoutHours")
                row["apply_notice"] = [t for t in s.page.locator(".jenkins-notification, #notification-bar").all_inner_texts() if t.strip()]
            groovy("def c=io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get(); c.setPendingTimeoutHours(" + (old if old.isdigit() else "1") + "); c.save()")
            # direct POST by a role that cannot see the page is checked below
        else:
            row["text"] = re.sub(r"\s+", " ", s.text())[:150]
        r2 = api(role, "/manage/batch-control-configuration/configSubmit", "POST", data={"json": "{}"})
        row["configSubmit_post"] = r2.status_code
        s.done()
        emit("C", row)


# ---------------------------------------------------------------- H
def sec_H():
    for role in ["approver-1", "admin", "reqonly", "nobc", "manager"]:
        s = Session(role)
        r = s.go("/batch-control/history/")
        nc0 = len(s.console)
        row = {"role": role, "status": r.status if r else None}
        if row["status"] != 200:
            emit("H", row); s.done(); continue
        row["kind_buttons"] = [t.strip() for t in s.page.locator("#main-panel a.jenkins-button").all_inner_texts()][:12]
        # each kind button
        kinds = {}
        for k in ["Runs", "Requests", "Incidents", "Changes"]:
            s.go("/batch-control/history/")
            b = s.page.locator("#main-panel a.jenkins-button", has_text=re.compile("^" + k))
            if not b.count():
                kinds[k] = "missing"; continue
            b.first.click(); s.page.wait_for_load_state("load")
            kinds[k] = (s.page.url.replace(BASE, ""), s.page.locator("#main-panel table tbody tr").count(),
                        s.page.locator("#main-panel a.jenkins-button--primary").first.inner_text().strip() if s.page.locator("#main-panel a.jenkins-button--primary").count() else None)
        row["kinds"] = kinds
        # filter by job
        s.go("/batch-control/history/?kind=runs")
        s.page.fill("#filter-job", "fast")
        with s.page.expect_navigation():
            s.page.locator("#main-panel form[method=get] button, #main-panel form[method=get] input[type=submit]").first.click()
        jobs = set(re.sub(r"#\d+$", "", t.strip()) for t in s.page.locator("#main-panel table tbody tr td:first-child").all_inner_texts())
        row["filter_job_fast"] = {"url": s.page.url.replace(BASE, ""), "rows": s.page.locator("#main-panel table tbody tr").count(), "first_col": sorted(jobs)[:4],
                                  "text": re.sub(r"\s+", " ", s.page.locator("#main-panel table").first.inner_text())[:160] if s.page.locator("#main-panel table").count() else None}
        # filter by user and result
        s.go("/batch-control/history/?kind=runs")
        if s.page.locator("#filter-result").evaluate("e => e.tagName") == "SELECT":  # Wave D D2: a dropdown
            s.page.locator("#filter-result").select_option("FAILURE")
        else:
            s.page.fill("#filter-result", "FAILURE")
        with s.page.expect_navigation():
            s.page.locator("#main-panel form[method=get] button, #main-panel form[method=get] input[type=submit]").first.click()
        row["filter_result_failure"] = {"rows": s.page.locator("#main-panel table tbody tr").count(),
                                        "sample": re.sub(r"\s+", " ", s.page.locator("#main-panel table tbody tr").first.inner_text())[:120] if s.page.locator("#main-panel table tbody tr").count() else None}
        # invalid date range
        s.go("/batch-control/history/?kind=runs&from=2026-13-45&to=x")
        row["bad_dates"] = {"text": [t.strip()[:100] for t in s.page.locator("#main-panel .error, #main-panel .jenkins-alert-danger, #main-panel .jenkins-alert-warning").all_inner_texts()][:3]}
        # reset link / clear filters
        s.go("/batch-control/history/?kind=runs&job=fast")
        rs = s.page.locator("#main-panel a", has_text=re.compile("Reset|Clear", re.I))
        row["reset_link"] = rs.count()
        # CSV and JSON links
        s.go("/batch-control/history/?kind=runs")
        csv = []
        for a in s.page.locator("#main-panel a[href*='.csv'], #main-panel a[href*='summary']").all():
            url = a.evaluate("e => e.href")
            rr = s.context.request.get(url)
            body = rr.text()
            csv.append((a.inner_text().strip()[:30], url.replace(BASE, ""), rr.status, rr.headers.get("content-type", "")[:30],
                        len(body.splitlines()), body.splitlines()[0][:80] if body else ""))
        row["downloads"] = csv
        # monthly
        s.go("/batch-control/history/")
        m = s.page.locator("#main-panel a[href*='monthly']")
        if m.count():
            m.first.click(); s.page.wait_for_load_state("load")
            row["monthly"] = {"url": s.page.url.replace(BASE, ""), "h1": s.page.locator("h1").first.inner_text()[:60],
                              "tables": s.page.locator("#main-panel table").count(),
                              "links": [(a.inner_text().strip()[:25], a.get_attribute("href")) for a in s.page.locator("#main-panel a[href]").all()][:8]}
            prevb = s.page.locator("#main-panel a", has_text=re.compile("Previous|«"))
            if prevb.count():
                prevb.first.click(); s.page.wait_for_load_state("load")
                row["monthly_prev"] = s.page.url.replace(BASE, "")
        row["console"] = s.console[nc0:][:10]  # the History pages' messages (from the first History load on)
        s.shot("#main-panel", f"H-{role}-history")
        s.done()
        emit("H", row)
    # Changes and incidents month navigation
    s = Session("approver-1")
    for page in ["/batch-control/changes/", "/batch-control/incidents/"]:
        s.go(page)
        row = {"page": page, "rows": s.page.locator("#main-panel table tbody tr").count(),
               "buttons": [(a.inner_text().strip(), a.get_attribute("href")) for a in s.page.locator("#main-panel a.jenkins-button, #main-panel form button").all()]}
        sh = s.page.locator("#main-panel form button, #main-panel form input[type=submit]", has_text=re.compile("Show"))
        if sh.count():
            sel = s.page.locator("#main-panel form select, #main-panel form input[name=month]").first
            row["month_control"] = sel.evaluate("e => e.tagName + ' ' + e.name + ' ' + (e.value||'')") if sel.count() else None
            with s.page.expect_navigation():
                sh.first.click()
            row["show_landing"] = s.page.url.replace(BASE, "")
        prev = s.page.locator("#main-panel a", has_text="«")
        if prev.count():
            prev.first.click(); s.page.wait_for_load_state("load")
            row["prev_month"] = (s.page.url.replace(BASE, ""), s.page.locator("#main-panel table tbody tr").count(),
                                 re.sub(r"\s+", " ", s.page.locator("#main-panel").inner_text())[:200])
        emit("H", row)
    s.done()


# ---------------------------------------------------------------- P
def sec_P():
    s = Session("admin")
    for page in ["/batch-control/dashboard/", "/batch-control/history/?kind=runs", "/batch-control/changes/", "/batch-control/requests/",
                 "/batch-control/grants/", "/batch-control/activations/", "/batch-control/history/?kind=requests", "/batch-control/history/?kind=changes"]:
        s.go(page)
        row = {"page": page, "footers": [t.strip()[:80] for t in s.page.locator("#main-panel :text-matches('^Page \\\\d')").all_inner_texts()][:4]}
        nxt = s.page.locator("#main-panel a", has_text=re.compile("^(Next|Older)"))
        row["next_links"] = nxt.count()
        if nxt.count():
            first = s.page.locator("#main-panel table tbody tr").first.inner_text()[:60]
            nxt.first.click(); s.page.wait_for_load_state("load")
            second = s.page.locator("#main-panel table tbody tr").first.inner_text()[:60] if s.page.locator("#main-panel table tbody tr").count() else None
            row["next"] = {"url": s.page.url.replace(BASE, ""), "first_row_changed": first != second}
            prv = s.page.locator("#main-panel a", has_text=re.compile("^(Previous|Newer)"))
            if prv.count():
                prv.first.click(); s.page.wait_for_load_state("load")
                back_rows = [t[:60] for t in s.page.locator("#main-panel table").first.locator("tbody tr").all_inner_texts()]
                back = back_rows[0] if back_rows else None
                row["prev"] = {"url": s.page.url.replace(BASE, ""), "first_row_restored": back == first,
                               # where the first row of page 1 is now: the newest-first run history gains a row with
                               # every timer run (batch-cron, fast) while the pager is walked (judged in h1_rules.py)
                               "first_row_at": back_rows.index(first) if first in back_rows else None}
            else:
                row["prev"] = "MISSING on page 2"
        emit("P", row)
    s.done()


# ---------------------------------------------------------------- B
def sec_B():
    for role in ["admin", "requester", "approver-1", "manager", "nobc"]:
        s = Session(role)
        for page in ["/batch-control/requests/", "/batch-control/history/", "/job/batch-daily/batch-control/"]:
            nc = len(s.console)
            r = s.go(page)
            row = {"role": role, "page": page, "status": r.status if r else None}
            if row["status"] != 200:
                emit("B", row); continue
            crumbs = s.page.locator(".jenkins-breadcrumbs__list-item a, #breadcrumbs a")
            row["crumbs"] = [(c.inner_text().strip(), c.get_attribute("href")) for c in crumbs.all()]
            st = []
            for t, h in row["crumbs"]:
                if h and not h.startswith("#"):
                    st.append((t, s.context.request.get(s.page.locator(".jenkins-breadcrumbs__list-item a, #breadcrumbs a", has_text=t).first.evaluate("e=>e.href")).status))
            row["crumb_status"] = st
            # Batch Control crumb menu (hover / chevron)
            bc = s.page.locator(".jenkins-breadcrumbs__list-item", has_text="Batch Control").first
            if bc.count():
                bc.hover(); s.page.wait_for_timeout(800)
                chev = bc.locator("button, .jenkins-menu-dropdown-chevron, [data-href]")
                if chev.count():
                    chev.first.click(); s.page.wait_for_timeout(1200)
                items = s.page.locator(".tippy-box a, .tippy-box button")
                row["menu"] = [(i.inner_text().strip(), i.get_attribute("href")) for i in items.all()]
                ms = []
                for t, h in row["menu"]:
                    if h:
                        u = h if h.startswith("http") else BASE.split("/jenkins")[0] + h if h.startswith("/") else None
                        if u:
                            ms.append((t, s.context.request.get(u).status))
                row["menu_status"] = ms
                if row["menu"]:
                    s.shot([".tippy-box", ".jenkins-breadcrumbs"], f"B-{role}-crumb-menu")
                    # click the first non-current entry
                    if items.count() > 1:
                        items.nth(1).click(); s.page.wait_for_load_state("load")
                        row["menu_click_landing"] = s.page.url.replace(BASE, "")
            r = s.context.request.get(BASE + "/batch-control/contextMenu")
            row["contextMenu_GET"] = (r.status, r.text()[:200])
            row["console"] = s.console[nc:][:10]  # M-04: this page's messages only
            emit("B", row)
        s.done()


# ---------------------------------------------------------------- T
def sec_T():
    for role in ["admin", "requester", "reqonly", "approver-1", "approver-2", "manager", "fonly"]:
        s = Session(role)
        s.go("/batch-control/")
        tabs = [re.sub(r"\s+", " ", t).strip() for t in s.page.locator(".app-build-tabs a, nav a.jenkins-button").all_inner_texts()]
        row = {"role": role, "tabs": tabs}
        counts = {}
        for tab, path in [("Run Requests", "requests/"), ("Activations", "activations/"), ("Grants", "grants/")]:
            r = s.go("/batch-control/" + path)
            if not r or r.status != 200:
                counts[tab] = r.status if r else None; continue
            sec = s.page.locator("#main-panel table").first
            counts[tab] = {"pending_rows": sec.locator("tbody tr").count() if sec.count() else 0,
                           "first_h2": s.page.locator("#main-panel h2").first.inner_text() if s.page.locator("#main-panel h2").count() else None,
                           "first_table_cells": [re.sub(r"\s+", " ", x)[:40] for x in sec.locator("tbody tr td:nth-child(5)").all_inner_texts()][:30] if sec.count() else []}
        row["lists"] = counts
        # click each tab, it must land on its page and be marked current
        land = []
        s.go("/batch-control/")
        for t in s.page.locator(".app-build-tabs a, nav a.jenkins-button").all():
            href = t.evaluate("e=>e.href")
            land.append((t.inner_text().strip()[:30], s.context.request.get(href).status))
        row["tab_status"] = land
        s.done()
        emit("T", row)


# ---------------------------------------------------------------- R
def sec_R():
    # approval required: nobc (Build) and requester (Build) push Build Now / Build on a controlled job
    for role, ui in [("nobc", "classic"), ("requester", "classic"), ("nobc", "new"), ("reqonly", "classic")]:
        groovy("""import jenkins.model.experimentalflags.*
def u=hudson.model.User.getById('%s',true); def m=new HashMap(); m.put('new-job-page.flag','%s'); u.addProperty(new UserExperimentalFlagsProperty(m)); u.save()""" % (role, "true" if ui == "new" else "false"))
        s = Session(role)
        for job in ["batch-pipeline", "batch-daily"]:
            s.go(f"/job/{job}/")
            row = {"role": role, "ui": ui, "job": job}
            # M-08: the new job page shows the build entry in the main panel (jobui lists it as "main"), not in an app bar.
            # The label is matched on the visible element's normalised inner text, as jobui.py does: an anchored has_text
            # regex missed the new job page's button: its text content starts with whitespace and an svg before the label
            # (Playwright trace of CI run 40080830269, misc step, nobc new UI).
            sel = ("#tasks a, .jenkins-app-bar a, .jenkins-app-bar button, .app-page-body__header a, "
                   ".app-page-body__header button, #main-panel a, #main-panel button")
            label_rx = re.compile(r"^(Build Now|Direct Build|Build with Parameters)")
            cands = []
            for el in s.page.locator(sel).all():
                try:
                    t = re.sub(r"\s+", " ", el.inner_text() or el.get_attribute("aria-label") or "").strip()
                    if el.is_visible() and label_rx.match(t):
                        cands.append((t, el))
                except Exception:  # noqa: an element that left the page
                    continue
            row["build_entries"] = list(dict.fromkeys(t for t, _ in cands))
            if cands:
                nb = len(s.bad)
                cands[0][1].click(); s.page.wait_for_timeout(2500)
                d = s.page.locator("dialog[open]")
                if d.count():
                    b = d.first.get_by_role("button", name=re.compile("^Build$"))
                    if b.count():
                        b.first.click(); s.page.wait_for_timeout(3500)
                s.page.wait_for_load_state("load")
                row["after"] = {"url": s.page.url.replace(BASE, ""), "h1": s.page.locator("h1").first.inner_text()[:80] if s.page.locator("h1").count() else None,
                                "dialog_open": s.page.locator("dialog[open]").count(),
                                "notifications": [t.strip()[:150] for t in s.page.locator(".jenkins-notification, #notification-bar, .tippy-box").all_inner_texts() if t.strip()][:2],
                                "bad": s.bad[nb:][:3], "text": re.sub(r"\s+", " ", s.text())[:300]}
                links = []
                for a in s.page.locator("#main-panel a[href]").all():
                    h = a.get_attribute("href")
                    if h and not h.startswith("#"):
                        links.append((a.inner_text().strip()[:35], a.evaluate("e=>e.href").replace(BASE, ""), s.context.request.get(a.evaluate("e=>e.href")).status))
                row["links"] = links[:12]
                s.shot("#main-panel", f"R-approval-{role}-{ui}-{job}")
            # the POST refusal page itself (classic Build Now posts to /build)
            r = api(role, f"/job/{job}/build?delay=0sec", "POST")
            row["post_build"] = (r.status_code, re.sub(r"\s+", " ", clean(r.text))[clean(r.text).find("Batch Control") if "Batch Control" in r.text else 0:][:200])
            emit("R", row)
        s.done()
    groovy("""import jenkins.model.experimentalflags.*
['nobc','requester','reqonly'].each{ def u=hudson.model.User.getById(it,true); def m=new HashMap(); m.put('new-job-page.flag','true'); u.addProperty(new UserExperimentalFlagsProperty(m)); u.save() }""")
    # grant required: configurer deletes prod/x (Delete standing, no window)
    s = Session("configurer")
    s.go("/job/prod/job/x/")
    d = s.page.locator("#tasks a, .jenkins-app-bar a, .jenkins-app-bar button, [data-testid=app-bar-overflow-button]")
    row = {"case": "grant required (delete)", "role": "configurer"}
    ov = s.page.locator("[data-testid=app-bar-overflow-button]")
    if ov.count():
        ov.first.click(); s.page.wait_for_timeout(1000)
    de = s.page.locator(".tippy-box a, .tippy-box button, #tasks a", has_text=re.compile("Delete"))
    row["delete_entry"] = de.count()
    if de.count():
        de.first.click(); s.page.wait_for_timeout(1000)
        dl = s.page.locator("dialog[open]")
        if dl.count():
            nav_status = None
            try:
                with s.page.expect_navigation(timeout=10000) as nav:
                    dl.first.locator("button[data-id=ok], button.jenkins-button--destructive, button.jenkins-button--primary").first.click()
                nav_status = nav.value.status if nav.value else None
            except Exception as e:  # noqa
                row["nav_exc"] = str(e)[:100]
            row["status"] = nav_status
        s.page.wait_for_load_state("load")
        row["url"] = s.page.url.replace(BASE, "")
        row["text"] = re.sub(r"\s+", " ", s.text())[:400]
        row["links"] = [(a.inner_text().strip()[:35], a.evaluate("e=>e.href").replace(BASE, ""), s.context.request.get(a.evaluate("e=>e.href")).status)
                        for a in s.page.locator("#main-panel a[href]").all() if not (a.get_attribute("href") or "#").startswith("#")][:10]
        btns = s.page.locator("#main-panel button, #main-panel a.jenkins-button")
        row["buttons"] = [b.inner_text().strip() for b in btns.all()][:6]
        s.shot("#main-panel", "R-grant-required")
        # the "Request Change Permission" control on the refusal page, if any
        rq = s.page.locator("#main-panel a, #main-panel button", has_text=re.compile("Request"))
        if rq.count():
            rq.first.click(); s.page.wait_for_timeout(1500)
            row["request_click"] = {"dialog": s.page.locator("dialog[open]").count(), "url": s.page.url.replace(BASE, "")}
    row["still_exists"] = api("admin", "/job/prod/job/x/api/json").status_code
    emit("R", row)
    s.done()
    # move refusal: mover1 moves prod/z -> team. M-10: cloudbees-folder's move page offers only destinations where the
    # user may create items (StandardHandler.permitted: Job.CREATE), and mover1 holds Item/Move and RequestGrant only;
    # so mover1 first gets an approved CREATE window on team (as r9/s83.py was arranged). The move still lacks Item/Delete
    # on prod/z, which no window can confer on a move here: the refusal page (D-59) must answer.
    wid = create_window("mover1", "team")
    s = Session("mover1")
    r = s.go("/job/prod/job/z/move/")
    row = {"case": "move refusal", "role": "mover1", "window": wid, "move_page": r.status if r else None}
    if r and r.status == 200:
        sel = s.page.locator("select[name=destination]")
        opts = [o.get_attribute("value") for o in sel.locator("option").all()]
        row["destinations"] = opts[:10]
        tgt = next((o for o in opts if o.endswith("team")), opts[0] if opts else None)
        if tgt:
            sel.select_option(tgt)
            with s.page.expect_navigation() as nav:
                s.page.locator("button[name=Submit], button.jenkins-button--primary").first.click()
            row["status"] = nav.value.status
            row["url"] = s.page.url.replace(BASE, "")
            row["text"] = re.sub(r"\s+", " ", s.text())[:400]
            row["links"] = [(a.inner_text().strip()[:35], a.evaluate("e=>e.href").replace(BASE, ""), s.context.request.get(a.evaluate("e=>e.href")).status)
                            for a in s.page.locator("#main-panel a[href]").all() if not (a.get_attribute("href") or "#").startswith("#")][:10]
            row["buttons"] = [b.inner_text().strip() for b in s.page.locator("#main-panel button, #main-panel a.jenkins-button").all()][:6]
            s.shot("#main-panel", "R-move-refusal")
            rq = s.page.locator("#main-panel a, #main-panel button", has_text=re.compile("Request"))
            if rq.count():
                rq.first.click(); s.page.wait_for_timeout(1500)
                row["request_click"] = {"dialog": s.page.locator("dialog[open]").count(), "url": s.page.url.replace(BASE, ""),
                                        "dialog_text": re.sub(r"\s+", " ", s.page.locator("dialog[open]").first.inner_text())[:200] if s.page.locator("dialog[open]").count() else None}
    row["still_in_prod"] = api("admin", "/job/prod/job/z/api/json").status_code
    if wid:
        row["window_revoked"] = api("admin", f"/batch-control/grants/{wid}/revoke", "POST").status_code
    emit("R", row)
    s.done()


def create_window(user, item, actions=("CREATE",)):
    """An approved permission window for user on item (REST, as r9/grants.py); returns its id or None."""
    data = [("scopeFullName", item), ("durationMinutes", "15"), ("reason", f"e2e-12 misc R premise for {user}"),
            ("approvers", "approver-1")] + [("actions", a) for a in actions]
    r = api(user, "/batch-control/grants/create", "POST", data=data)
    m = re.search(UUID, r.headers.get("Location", ""))
    if not m:
        print(f"misc R: the {actions} window for {user} on {item} was not created: {r.status_code}", flush=True)
        return None
    a = api("approver-1", f"/batch-control/grants/{m.group(0)}/approve", "POST", data={"comment": "misc R premise"})
    print(f"misc R: window {m.group(0)} for {user} on {item}: create {r.status_code}, approve {a.status_code}", flush=True)
    return m.group(0)


# ---------------------------------------------------------------- S
def reveal_revert(page):
    """Wave D D1 (#18.7): the revert entry sits inside the Advanced control at the end of the Batch Control section, so
    a person presses Advanced first. Returns True when it had to be revealed (False: visible at once, or no entry)."""
    rv = page.locator("a[data-url*='/revert']")
    if not rv.count() or rv.first.is_visible():
        return False
    page.locator("section:has([name='_.runControlEnabled']) button.advanced-button").last.click()
    rv.first.wait_for(state="visible", timeout=5000)
    return True


def sec_S():
    s = Session("admin")
    s.go("/manage/")
    mon = s.page.locator(".jenkins-alert, .alert", has_text=re.compile("Batch Control|permission window|reviewed"))
    row = {"monitor_alerts": [re.sub(r"\s+", " ", t)[:200] for t in mon.all_inner_texts()][:4]}
    mr = s.page.locator("a[data-url*='markReviewed']")
    row["mark_reviewed_links"] = mr.count()
    if mr.count():
        item = re.search(r"item=([^&]+)", mr.first.get_attribute("data-url")).group(1)
        mr.first.click(); opened(s.page)  # e2e-20: dialog waits instead of fixed 0.5-0.9 s (section S)
        s.page.locator("dialog[open] button[data-id=cancel]").click(); gone(s.page)
        s.go("/manage/")
        row["after_cancel_links"] = s.page.locator("a[data-url*='markReviewed']").count()
        s.page.locator("a[data-url*='markReviewed']").first.click(); opened(s.page)
        with s.page.expect_navigation() as nav:
            s.page.locator("dialog[open] button[data-id=ok]").click()
        row["mark_status"] = nav.value.status if nav.value else "no response (same-document)"
        row["mark_landing"] = s.page.url.replace(BASE, "")
        row["mark_text"] = re.sub(r"\s+", " ", s.text())[:250]
        row["mark_links"] = [(a.inner_text().strip()[:30], a.evaluate("e=>e.href").replace(BASE, ""), s.context.request.get(a.evaluate("e=>e.href")).status)
                             for a in s.page.locator("#main-panel a[href]").all() if not (a.get_attribute("href") or "#").startswith("#")][:6]
        s.shot("#main-panel", "S-marked-reviewed")
        s.go("/manage/")
        row["after_ok_links"] = s.page.locator("a[data-url*='markReviewed']").count()
        row["item"] = item
    # Revert (from the configuration page): Cancel then OK; then Install from the monitor
    s.go("/manage/batch-control-configuration/")
    rv = s.page.locator("a[data-url*='/revert']")
    row["revert_link"] = rv.count()
    row["revert_behind_advanced"] = reveal_revert(s.page)
    if rv.count():
        rv.first.click(); opened(s.page)
        s.page.locator("dialog[open] button[data-id=cancel]").click(); gone(s.page)
        row["after_revert_cancel"] = strategy()
        rv.first.click(); opened(s.page)
        with s.page.expect_navigation() as nav:
            s.page.locator("dialog[open] button[data-id=ok]").click()
        row["revert_status"] = nav.value.status if nav.value else None
        row["revert_landing"] = s.page.url.replace(BASE, "")
        row["after_revert_ok"] = strategy()
    s.go("/manage/")
    s.shot("#main-panel", "S-monitor-plain")
    row["monitor_after_revert"] = [re.sub(r"\s+", " ", t)[:200] for t in s.page.locator(".jenkins-alert").all_inner_texts() if "Batch Control" in t][:3]
    inst = s.page.locator("#main-panel button, #main-panel a", has_text=re.compile("Install"))
    row["install_controls"] = [t.strip() for t in inst.all_inner_texts()]
    if inst.count():
        inst.first.click(); opened(s.page)
        d = s.page.locator("dialog[open]")
        row["install_dialog"] = re.sub(r"\s+", " ", d.first.inner_text())[:200] if d.count() else None
        if d.count():
            d.first.locator("button[data-id=cancel], button:has-text('Cancel')").first.click(); gone(s.page)
            row["after_install_cancel"] = strategy()
            inst.first.click(); opened(s.page)
            with s.page.expect_navigation() as nav:
                s.page.locator("dialog[open] button[data-id=ok], dialog[open] button.jenkins-button--primary").first.click()
            row["install_status"] = nav.value.status if nav.value else None
        else:
            s.page.wait_for_load_state("load")
        row["install_landing"] = s.page.url.replace(BASE, "")
        row["after_install"] = strategy()
        s.shot("#main-panel", "S-after-install")
    row["requester_build_after"] = api("requester", "/job/batch-daily/batch-control/").status_code
    row["console"] = [c for c in s.console if "MIME" not in c][:3]
    s.done()
    emit("S", row)


# ---------------------------------------------------------------- M
def sec_M():
    # job page Mark as reviewed (shown on /job/<job>/batch-control/ when the job was changed under a window)
    for role in ["admin", "requester", "approver-1", "manager"]:
        s = Session(role)
        r = s.go("/job/team/job/app-1/batch-control/")
        row = {"role": role, "status": r.status if r else None}
        if row["status"] == 200:
            b = s.page.locator("form[name=markReviewed] button")
            row["mark_button"] = b.count()
            if b.count():
                with s.page.expect_navigation() as nav:
                    b.first.click()
                row["mark_status"] = nav.value.status if nav.value else "no response (same-document)"
                row["mark_landing"] = s.page.url.replace(BASE, "")
                row["notice"] = re.sub(r"\s+", " ", s.text())[:200]
        emit("M", row)
        s.done()
    # Request again on an ended grant (requester)
    s = Session("requester")
    s.go("/batch-control/grants/")
    ra = s.page.locator("#main-panel button", has_text="Request again")
    row = {"request_again_buttons": ra.count()}
    if ra.count():
        ra.first.click(); s.page.wait_for_timeout(1500)
        d = s.page.locator("dialog[open]").first
        row["dialog"] = d.count()
        if d.count():
            kind = d.locator("[data-batch-control-item-kind]")
            try:
                kind.first.wait_for(timeout=5000)
            except Exception:
                pass
            row["prefill"] = {"scope_type_selector": d.locator("select[name=scopeType]").count(),  # D-71: must be 0
                              "item_kind": kind.first.get_attribute("data-batch-control-item-kind") if kind.count() else None,
                              "scope": d.locator("input[name=scopeFullName]").input_value(),
                              "actions": [a.get_attribute("value") for a in d.locator("input[name=actions]").all() if a.is_checked()],
                              "reason": d.locator("textarea[name=reason]").input_value()[:60],
                              "approvers": [a.get_attribute("value") for a in d.locator("input[name=approvers]").all() if a.is_checked()]}
            s.shot("dialog[open]", "M-request-again")
            if not row["prefill"]["approvers"]:
                d.locator("input[name=approvers]").first.locator("xpath=following-sibling::label").click()
            if not d.locator("textarea[name=reason]").input_value():
                d.locator("textarea[name=reason]").fill("again")
            d.locator("button.jenkins-button--primary").last.click()
            try:
                s.page.wait_for_url(re.compile(r".*/grants/" + UUID + "/$"), timeout=10000)
            except Exception:
                row["stuck"] = re.sub(r"\s+", " ", s.page.locator("dialog[open]").first.inner_text())[:300] if s.page.locator("dialog[open]").count() else s.page.url
            row["landing"] = s.page.url.replace(BASE, "")
    s.done()
    emit("M", row)


# ---------------------------------------------------------------- K
def sec_K():
    ids = json.loads((lib.HERE / "out" / "ids.json").read_text())
    pages = ["/batch-control/", "/batch-control/requests/", "/batch-control/activations/", "/batch-control/grants/", "/batch-control/changes/",
             "/batch-control/dashboard/", "/batch-control/incidents/", "/batch-control/history/", "/batch-control/history/monthly",
             f"/batch-control/requests/{ids['req_pending']}/", f"/batch-control/activations/{ids['act_hold_pending']}/",
             f"/batch-control/grants/{ids['g_pending_job']}/", f"/batch-control/grants/{ids['g_active_fonly']}/",
             "/manage/batch-control-configuration/", "/job/batch-daily/batch-control/", "/job/batch-daily/"]
    s = Session("admin")
    s.go("/user/admin/appearance/")
    s.page.locator("input[data-theme=dark]").check(force=True)
    s.page.locator("button[name=Submit]").first.click(); s.page.wait_for_load_state("load")
    for page in pages:
        nc = len(s.console)
        r = s.go(page)
        theme = s.page.evaluate("() => document.documentElement.getAttribute('data-theme') || getComputedStyle(document.body).backgroundColor")
        btns = s.page.evaluate("""() => [...document.querySelectorAll('#main-panel button, #main-panel a.jenkins-button, #main-panel input[type=submit]')].filter(e=>e.offsetWidth).map(e => {
            const cs = getComputedStyle(e); return [e.innerText.trim().slice(0,25), cs.color, cs.backgroundColor, e.offsetWidth, e.offsetHeight]; }).slice(0,12)""")
        invisible = [b for b in btns if b[1] == b[2] or b[3] < 8 or b[4] < 8]
        emit("K", {"page": page, "status": r.status if r else None, "theme": theme, "buttons": len(btns), "invisible": invisible,
                   "console": [c for c in s.console[nc:] if "MIME" not in c][:3]})
        s.shot("#main-panel", "K-dark-" + re.sub(r"[^a-z0-9]+", "_", page)[:50])
    s.go("/user/admin/appearance/")
    s.page.locator("input[data-theme=none]").check(force=True)
    s.page.locator("button[name=Submit]").first.click(); s.page.wait_for_load_state("load")
    s.done()


for k in WANT:
    try:
        globals()["sec_" + k]()
    except Exception as e:  # noqa
        emit(k, {"EXCEPTION": str(e)[:300]})
close()
lib.h1.report("misc", ROWS, {"sections": WANT})
