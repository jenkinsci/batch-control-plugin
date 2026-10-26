"""e2e-12 part 1 (reused by e2e-14): scripted crawl of every Batch Control page and entry point, per role and per job UI.

usage: python crawl.py <role> <ui: new|classic>
For every page reached (start set + Batch Control links found on the way, two pages per URL pattern):
  * page status, JS console errors/page errors, failed sub-requests;
  * every control in the main panel, side panel and breadcrumbs: links (GET via the browser context,
    redirects followed, status recorded), forms (POST action probed with GET -> 405 expected, GET action
    fetched), dead links (href '#', '', 'javascript:' without a handler);
  * every non-submit button / dialog link: clicked on a fresh load of the page and classified
    (dialog opened, navigated, DOM changed, nothing). A dialog is closed again with Escape and, on a
    second open, with its Cancel button; both must close it.
Rows go to out/crawl.jsonl. Nothing is POSTed here (state-changing buttons are clicked in actions.py).

H1 (2026-10-24): at its end the crawl judges its own rows with ci/h1_rules.py (judge_crawl) and prints a
"FAIL crawl <check> <json>" line per finding no rule explains: a page status other than the role's expectation table
(both directions), a BROKEN link or POST form, a DEAD-HREF, a click that failed or did nothing, a dialog that Escape or
Cancel does not close, and an error/pageerror in the console that no allowlist entry (exact message, page pattern, role)
explains. How the rows are recorded, so that they mean what they say:
  * the browser's own "Failed to load resource" line for the page's document is dropped when the page answers 4xx
    (it repeats the page status, which the table judges);
  * core's help "?" (a.jenkins-help-button, wired by its helpurl) is a help-button row whose help URL is fetched;
  * a link that core posts (data-callback, data-task-post="true", the list view's build button) to a build or rebuild
    URL answers 405 to a GET: a post-link row, like a POST form;
  * a disabled control, or one whose "disabled" class sets pointer-events:none, is an inert row and not clicked (core's
    "Newer builds" on the first page);
  * core's build-history pager ("Older builds", button#down of .app-builds-container__controls, on a job page or a job
    action page once the job has more than one page of builds) pages the list in place: a paged row when the list shows
    a strictly older page (then "Newer builds", now enabled, must bring the newer page back: detail "newer"); a list that
    does not page is NOTHING, as for any other control (CI run 40158487092 read its in-place swap as NOTHING);
  * a control is found again on its fresh load by tag, label, href, data-href, region and occurrence, not by its index
    in the document (the page may have changed in between); one that is gone is a gone-on-reload row, a failure unless
    it points at a numbered build (batch-cron's history moves on every minute) or is an entry of core's Build Queue or
    Build Executor Status pane (the row's "widget": buildQueue/executors, see CORE_STOP; a queued or running build
    leaves them at any moment); such an entry does not use up its click key, so the next control with that key is
    clicked;
  * core's stop button in those panes (a.stop-button-link to queue/cancelItem or .../executors/<n>/stopBuild) is a
    post-link row (405 on a GET) and never clicked; a one-off executor's that answers 404 is transient-gone (the build
    ended);
  * a menu chevron (.jenkins-menu-dropdown-chevron, or an unlabelled button with data-href) is clicked after hovering
    its link, as core requires (it takes pointer events only while the link is hovered); a failed click keeps
    Playwright's whole message (triage U-1: if the first-row chevron still cannot be clicked, it is a FAIL);
  * a dialog's Cancel and its title close button are counted apart (cancel_button, close_button);
  * core's administrative monitor "Dismiss" (data-url .../administrativeMonitor/<id>/disable) posts without asking and
    is not clicked (a not-clicked row): clicking it hid the monitor's own controls from the rest of the crawl.

Content checks (CI, 2026-10-05), on every page reached with status < 400, all roles: a row with kind "content" and a
"FAIL content ..." line (once per check, URL pattern and finding) for
  * empty-cell: an empty data cell on a Batch Control page in a column (or th/td row) whose value always exists
    (EXPECTED_COLUMNS); empty optional cells are logged as check "empty-optional" with result INFO, without a FAIL;
  * server-path: a server filesystem path in the visible text of the main or side panel (/var/, /tmp/, /home/, ...,
    jenkins_home, JENKINS_HOME, StoreLocation=, WEB-INF/, a Windows drive path);
  * double-escaped: an HTML entity shown as text (&lt; &gt; &amp; &quot; &apos; &#..;);
  * markup-text: "<script" shown as text;
  * markup-live: the seeded probe's <img onerror> canary ran, or the probe (ci/seed_markup.py) does not read as typed;
  * legacy-scope: a scope written with a scope type that D-71 removed ("JOB:", "FOLDER:", "FOLDER_ONLY") (e2e-16);
  * raw-result: on a Batch Control page, a result cell (the cell under a table's "Result" heading, or the td of a row
    headed "Result") that shows a raw build result word (SUCCESS, FAILURE, UNSTABLE, NOT_BUILT, ABORTED) or the former
    "IN PROGRESS" / "UNKNOWN" instead of its label (#18.2, owner decision 2026-10-14: Success, Unstable, Failed, Not built,
    Aborted, In progress, Unknown); result-badge: a result cell holding a badge (class batch-control-status or a
    data-batch-control-tone): a result is plain text. Every result cell read is logged as check "result-cell" with
    result INFO, once per URL pattern and text, so the summary can say how many were checked. Since Wave D D3 (#6) the
    run and incident tables have no Result column: there the Run cell is read (its text after "#n" is the label; a raw
    word anywhere in the cell is a raw-result, a badge in it a result-badge).
Advanced (D1 follow-up, owner decision 2026-10-14): on every page reached, core's Advanced controls of the main panel
are pressed after the page has been checked as loaded, and the content checks run again, so what sits behind Advanced
(the administrator's revert entry and its sentence on the Batch Control configuration page) is checked too; the controls
are then listed and clicked in that expanded state (classify_click expands again on its fresh load). A row with kind
"advanced" says how many were pressed; who gets an Advanced control differs by role (the BatchControl/Manage holder has
none on that page).
Raw enum values (e2e-16): an UPPER_SNAKE_CASE constant (GRANT_REVOKE, APPROVED_REQUEST, ...), the scope prefix "ITEM:",
or a state/action constant of the plugin's vocabulary (PENDING, APPROVED, ..., CONFIGURE, CREATE, DELETE) shown as
text is logged as check "raw-enum" with result INFO, once per URL pattern and value, without a FAIL: showing these
words is the known, deferred UX review item UX-4/UX-5 (owner: UX fixes only on request); summary.md lists them.
Text inside pre/code/textarea and hidden elements is not checked. BC_CRAWL_LOG names the log (default "crawl")."""
import json, os, re, sys, time
from urllib.parse import urljoin, urlparse
from lib import Session, close, BASE, groovy, react, gone, until
import lib

MENUS = ".tippy-box, .jenkins-dropdown, [data-tippy-root]"

ROLE, UI = sys.argv[1], sys.argv[2]
ids = json.loads((lib.HERE / "out" / "ids.json").read_text())
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
SKIP = re.compile(r"/logout|/exit|/restart|/safeRestart|/quietDown|/doDelete|/delete$|/script$|/cli/|/login\b")
FN = lambda f: "/job/" + "/job/".join(f.split("/")) + "/"  # noqa

START = ["/batch-control/", "/batch-control/requests/", "/batch-control/activations/", "/batch-control/grants/",
         "/batch-control/changes/", "/batch-control/dashboard/", "/batch-control/incidents/", "/batch-control/history/",
         "/batch-control/history/?kind=requests", "/batch-control/history/?kind=incidents", "/batch-control/history/?kind=changes",
         "/batch-control/history/summary", "/batch-control/grants/new", "/manage/",
         FN("batch-daily"), FN("batch-cron"), FN("batch-pipeline"), FN("batch-upstream"), FN("ops/job-a"),
         FN("team/sub/deep-job"), FN("ops/mb"), FN("ops"), FN("team"), FN("team/sub"), FN("side/job-b"), FN("fast"),
         "/view/batch-view/", "/view/batch-view/job/batch-daily/", FN("batch-daily") + "1/", FN("fast") + "60/",
         FN("batch-daily") + "batch-control/", FN("batch-cron") + "batch-control-activation/",
         FN("batch-failing") + "1/", FN("batch-failing")]
for k, v in ids.items():
    if isinstance(v, str) and re.fullmatch(UUID, v):
        kind = {"r": "requests", "a": "activations", "g": "grants"}[k[0]]
        START.append(f"/batch-control/{kind}/{v}/")

PATTERN_LIMIT = 2
if UI == "classic":
    # The /batch-control/ pages do not depend on the job page flag (crawled in the "new" pass);
    # the classic pass covers job, folder, view and build pages and their Batch Control sub-pages.
    START = [p for p in START if p.startswith(("/job/", "/view/"))]


def pattern(path):
    p = re.sub(UUID, "{id}", path)
    p = re.sub(r"/\d{8}-\d{6}-[a-z0-9]{6}", "/{cid}", p)
    p = re.sub(r"/\d+/", "/{n}/", p)
    p = re.sub(r"page=\d+", "page={n}", p)
    p = re.sub(r"month=[\d-]+", "month={m}", p)
    return p


def crawlable(path):
    if SKIP.search(path) or ".csv" in path:
        return False
    if re.search(r"/(submit|approve|reject|cancel|revoke|changeApprover|migrate|revert|markReviewed|acknowledge|resolve|comment|rerun|create)\b", path):
        return False
    if UI == "classic" and not path.startswith(("/job/", "/view/")):
        return False
    return "/batch-control" in path


ROWS = []  # every row of this run, judged at the end (H1)


def emit(row):
    row.update(role=ROLE, ui=UI)
    ROWS.append(row)
    lib.log(os.environ.get("BC_CRAWL_LOG", "crawl"), row)


ELEMENTS_JS = r"""
() => {
  const regionOf = el => el.closest('#breadcrumbBar, .jenkins-breadcrumbs, [data-type=breadcrumb]') ? 'crumb'
     : el.closest('#side-panel') ? 'side' : el.closest('#main-panel, .app-page-body, main') ? 'main'
     : el.closest('header, #page-header, footer') ? 'chrome' : 'other';
  const vis = el => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
  const out = [];
  document.querySelectorAll('a, button, input[type=submit], input[type=button], [role=button], select').forEach((el, i) => {
    const r = regionOf(el);
    if (r === 'chrome' || r === 'other') return;
    const f = el.closest('form');
    out.push({i, tag: el.tagName.toLowerCase(), region: r, visible: vis(el),
      text: (el.innerText || el.value || el.getAttribute('aria-label') || el.title || '').trim().replace(/\s+/g,' ').slice(0,80),
      href: el.getAttribute('href'), anchor: !!(el.id || el.getAttribute('name')) && !el.hasAttribute('href') && !(el.innerText||'').trim(), abs: el.href || null, type: el.getAttribute('type'),
      onclick: el.getAttribute('onclick'), cls: el.className && el.className.baseVal === undefined ? el.className : '',
      data: Object.fromEntries(Object.entries(el.dataset || {}).slice(0, 8)),
      disabled: !!el.disabled, helpurl: el.getAttribute('helpurl'), pe: getComputedStyle(el).pointerEvents,
      widget: (w => w ? w.id : null)(el.closest('#side-panel div.pane-frame#buildQueue, #side-panel div.pane-frame#executors')),
      chevron: el.classList.contains('jenkins-menu-dropdown-chevron')
        || (el.tagName === 'BUTTON' && !!(el.dataset || {}).href && !(el.innerText || '').trim()),
      form: f ? {method: (f.getAttribute('method') || 'get').toLowerCase(), action: f.action, name: f.getAttribute('name')} : null});
  });
  return out;
}"""


CONTENT_JS = r"""
() => {
  const vis = el => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
  const texts = [];
  for (const root of [document.querySelector('#main-panel'), document.querySelector('#side-panel')]) {
    if (!root) continue;
    const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {acceptNode: n => {
      const p = n.parentElement;
      if (!p || p.closest('pre, code, textarea, script, style, template, noscript')) return NodeFilter.FILTER_REJECT;
      return vis(p) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT; }});
    let n;
    while ((n = w.nextNode())) { const t = n.nodeValue.replace(/\s+/g, ' ').trim(); if (t) texts.push([t, n.parentElement]); }
  }
  const PATH = /(?:^|[\s"'(=,;:])(\/(?:var|tmp|home|opt|usr|etc|root|srv|private|Users)\/[^\s"'<>)]*)|(jenkins_home|JENKINS_HOME|StoreLocation=|WEB-INF\/|\b[A-Za-z]:\\[A-Za-z])/;
  const ENT = /&(?:lt|gt|amp|quot|apos|#\d+|#x[0-9a-fA-F]+);/;
  const desc = el => el.tagName.toLowerCase() + (el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.') : '');
  const out = {paths: [], entities: [], script_text: [], empty_cells: [], probe: [], enums: [], results: []};
  const RAW_RESULT = /\b(SUCCESS|FAILURE|UNSTABLE|NOT_BUILT|ABORTED|IN PROGRESS|UNKNOWN)\b/;
  const resultCell = (c, how, caption) => { const t = (c.innerText || '').replace(/\s+/g, ' ').trim(); const m = RAW_RESULT.exec(t);
    out.results.push({text: t.slice(0, 60), raw: m ? m[1] : null, how, caption: caption.trim().slice(0, 40),
      badge: !!(c.matches('.batch-control-status, [data-batch-control-tone]') || c.querySelector('.batch-control-status, [data-batch-control-tone]'))}); };
  // Wave D D3 (#6): a run or incident table without a Result column shows the result in its Run cell: the icon, the job,
  // the "#n" run, then the label as text (and "Aborted by ..." on its own line). The text right after "#n" is the label.
  const runCell = (c, caption) => { const texts = []; const w = document.createTreeWalker(c, NodeFilter.SHOW_TEXT);
    for (let x = w.nextNode(); x; x = w.nextNode()) { const v = x.data.replace(/\s+/g, ' ').trim(); if (v && !x.parentElement.closest('svg')) texts.push(v); }
    const i = texts.findIndex(v => /^#\d+$/.test(v)); const label = i >= 0 && texts[i + 1] && !/^Aborted by\b/.test(texts[i + 1]) ? texts[i + 1] : null;
    const m = RAW_RESULT.exec(texts.join(' '));
    out.results.push({text: label ? label.slice(0, 60) : '(no result label)', raw: m ? m[1] : null, how: 'run', caption: caption.trim().slice(0, 40),
      badge: !!c.querySelector('.batch-control-status, [data-batch-control-tone]')}); };
  const SNAKE = /\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/g;
  const SCOPE = /\b(ITEM|JOB|FOLDER_ONLY|FOLDER): ?(?=\S)/g;
  const WORDS = /\b(PENDING|APPROVED|REJECTED|CANCELLED|EXPIRED|EXECUTED|INVALIDATED|CONSUMED|SUPERSEDED|OPEN|ACKNOWLEDGED|RESOLVED|ACTIVATE|HOLD|CONFIGURE|CREATE|DELETE)\b/g;
  for (const [t, el] of texts) {
    let m;
    for (const re of [SNAKE, SCOPE, WORDS]) { re.lastIndex = 0; while ((m = re.exec(t))) out.enums.push({match: re === SCOPE ? m[1] + ':' : m[0], text: t.slice(0, 160), el: desc(el)}); }
    if ((m = PATH.exec(t))) out.paths.push({match: m[1] || m[2], text: t.slice(0, 160), el: desc(el)});
    if ((m = ENT.exec(t))) out.entities.push({match: m[0], text: t.slice(0, 160), el: desc(el)});
    if (/<script/i.test(t)) out.script_text.push({text: t.slice(0, 160), el: desc(el)});
  }
  const full = texts.map(x => x[0]).join(' ');
  for (let i = full.indexOf('bc-markup-probe'); i >= 0; i = full.indexOf('bc-markup-probe', i + 1)) out.probe.push(full.slice(i, i + 80));
  out.canary = !!window.__bcCanary;
  out.injected = document.querySelectorAll('#main-panel img[src="x"], #side-panel img[src="x"]').length;
  const empty = c => !c.innerText.trim() && !c.querySelector('svg, img, input, button, select, textarea, progress, meter, canvas');
  const mp = document.querySelector('#main-panel');
  if (mp) mp.querySelectorAll('table').forEach((tb, ti) => {
    if (!vis(tb)) return;
    const hrow = tb.tHead && tb.tHead.rows[0];
    const heads = hrow ? [...hrow.cells].map(c => c.innerText.trim()) : [];
    const caption = ((tb.closest('section, div') || mp).querySelector('h2, h3') || {}).innerText || '';
    [...tb.tBodies].forEach(b => [...b.rows].forEach(r => {
      const cells = [...r.cells];
      if (cells.length === 2 && cells[0].tagName === 'TH' && cells[1].tagName === 'TD') {
        if (cells[0].innerText.trim() === 'Result') resultCell(cells[1], 'row', caption);
        if (empty(cells[1])) out.empty_cells.push({table: ti, caption: caption.trim().slice(0, 40), column: cells[0].innerText.trim(), row: ''});
        return;
      }
      if (!heads.length || cells.length !== heads.length) return;
      cells.forEach((c, ci) => {
        if (c.tagName === 'TD' && heads[ci] === 'Result') resultCell(c, 'column', caption);
        if (c.tagName === 'TD' && heads[ci] === 'Run' && !heads.includes('Result')) runCell(c, caption);
        if (c.tagName === 'TD' && heads[ci] && empty(c))
          out.empty_cells.push({table: ti, caption: caption.trim().slice(0, 40), column: heads[ci], row: (cells[0].innerText || '').trim().slice(0, 40)});
      });
    }));
  });
  return out;
}"""
# Columns (and th/td row labels) whose value always exists by definition: identity, the job/scope, the person who
# filed, times, state and result. Every other column of the plugin's tables is optional by meaning (Decided, Decided by,
# Approved by, Aborted by, Comment, Parameters, Request, Grant, Detail, Names, Value, User of a timer run, ...).
EXPECTED_COLUMNS = {"ID", "Job", "Job or folder", "Requester", "Approvers", "Created", "Status", "State", "At", "Run", "Scope",
                    "Action", "Actions", "Type", "Kind", "Target", "Started", "Result", "Duration", "Granted", "Expires",
                    "Remaining", "Incident", "Holder", "Name", "Reason", "Changed by", "Cause", "Build", "User or group"}
PROBE_AS_TYPED = 'bc-markup-probe <b>b</b> & "q" <img src=x onerror=window.__bcCanary=1>'
_content_seen = set()


def content_checks(s, path, pt):
    """The content checks of the docstring; one FAIL line per (check, pattern, finding), every finding logged."""
    try:
        c = s.page.evaluate(CONTENT_JS)
    except Exception as e:  # noqa
        c = None
        emit({"page": path, "pattern": pt, "kind": "content", "check": "content-js", "result": "ERROR", "detail": str(e)[:200]})
    if not c:
        return
    found = []
    if "/batch-control" in path:
        for x in c["empty_cells"]:
            if x["column"] in EXPECTED_COLUMNS:
                found.append(("empty-cell", f"{x['caption']} / {x['column']}", x))
            elif ("empty-optional", pt, x["column"]) not in _content_seen:  # optional value: once per pattern, UX review only
                _content_seen.add(("empty-optional", pt, x["column"]))
                emit({"page": path, "pattern": pt, "kind": "content", "check": "empty-optional", "key": x["column"], "result": "INFO", "detail": x})
        for x in c.get("results", []):  # #18.2: a build result reads as its label, plain text
            if x["raw"]:
                found.append(("raw-result", x["raw"], x))
            if x["badge"]:
                found.append(("result-badge", x["text"], x))
            if ("result-cell", pt, x["text"]) not in _content_seen:
                _content_seen.add(("result-cell", pt, x["text"]))
                emit({"page": path, "pattern": pt, "kind": "content", "check": "result-cell", "key": x["text"], "result": "INFO",
                      "detail": x})
    found += [("server-path", x["match"], x) for x in c["paths"]]
    found += [("double-escaped", x["match"], x) for x in c["entities"]]
    found += [("markup-text", "<script", x) for x in c["script_text"]]
    if c["canary"] or c["injected"]:
        found.append(("markup-live", "probe <img onerror> rendered as markup", {"canary": c["canary"], "injected": c["injected"]}))
    for x in c.get("enums", []):
        if x["match"] in ("JOB:", "FOLDER:", "FOLDER_ONLY:"):
            found.append(("legacy-scope", x["match"], x))  # D-71 removed these scope types
        elif ("raw-enum", pt, x["match"]) not in _content_seen:  # known UX-4/UX-5: once per pattern, for the UX section
            _content_seen.add(("raw-enum", pt, x["match"]))
            emit({"page": path, "pattern": pt, "kind": "content", "check": "raw-enum", "key": x["match"], "result": "INFO", "detail": x})
    for snip in c["probe"]:
        head = re.split(r"…|\.\.\.", snip)[0].rstrip()  # a list may shorten the text with an ellipsis
        if not (snip.startswith(PROBE_AS_TYPED) or (head != snip and PROBE_AS_TYPED.startswith(head))):
            found.append(("markup-live", "probe text not as typed", {"shown": snip}))
    for check, key, detail in found:
        emit({"page": path, "pattern": pt, "kind": "content", "check": check, "key": key, "result": "DEFECT", "detail": detail})
        sig = (check, pt, key)
        if sig not in _content_seen:
            _content_seen.add(sig)
            print("FAIL content " + json.dumps({"role": ROLE, "ui": UI, "check": check, "page": path, "key": key, "detail": detail})[:600], flush=True)


def check_url(s, url, cache):
    if url in cache:
        return cache[url]
    try:
        r = s.context.request.get(url, max_redirects=5, timeout=20000)
        cache[url] = (r.status, r.url.replace(BASE, ""))
    except Exception as e:  # noqa
        cache[url] = (0, str(e)[:80])
    return cache[url]


ADVANCED_JS = """() => { const bs = [...document.querySelectorAll('#main-panel button.advanced-button, #main-panel button.advancedButton')]
  .filter(b => !!(b.offsetWidth || b.offsetHeight || b.getClientRects().length) && b.dataset.expanded !== 'true');
  bs.forEach(b => b.click()); return bs.length; }"""


def expand_advanced(s):
    """Presses every core Advanced control of the main panel that is shown and not yet expanded (core moves the rows of
    its div.advancedBody out and keeps the button as a toggle). Returns how many were pressed."""
    try:
        pressed = s.page.evaluate(ADVANCED_JS)
    except Exception:  # noqa: a page that navigated away
        return 0
    if pressed:
        lib.until(s.page, lambda: not s.page.locator("#main-panel .advancedBody *").filter(visible=False).count(), 2000)
    return pressed


# Core's transient side-panel widgets (CI run 40175641627, manager new, /job/ops/job/mb/batch-control-activation/: the
# chevron of the Build Queue's "batch-cron" entry, "Started by timer, Waiting for next available executor", listed with
# the page and gone on the fresh load, where the pane read "No builds in the queue."). Core renders them as
# div.pane-frame#buildQueue (lib/hudson/queue.jelly, l:pane id="buildQueue") and div.pane-frame#executors
# (lib/hudson/executors.jelly) and replaces them in place (widget-refresh.js); their entries come and go with every
# queued or running build (batch-cron and fast run every minute). ELEMENTS_JS records that pane's id as "widget"; it is
# only ever core's markup there, no Batch Control control is rendered inside these panes.
# Its stop buttons (a.stop-button-link, lib/layout/stopButton.jelly) post to these core URLs:
CORE_STOP = re.compile(r"/queue/cancelItem\?id=\d+|/computer/[^/?]+/(executors|oneOffExecutors)/\d+/stopBuild\?runExtId=[^&]*")
CONTROLS = "a, button, input[type=submit], input[type=button], [role=button], select"
# The indices of the controls with the same signature as when they were listed (H1 C-18: the document index alone can
# point at another control once the page changed, e.g. batch-cron's build history after a timer run).
REFIND_JS = r"""
(sig) => {
  const regionOf = el => el.closest('#breadcrumbBar, .jenkins-breadcrumbs, [data-type=breadcrumb]') ? 'crumb'
     : el.closest('#side-panel') ? 'side' : el.closest('#main-panel, .app-page-body, main') ? 'main'
     : el.closest('header, #page-header, footer') ? 'chrome' : 'other';
  const text = el => (el.innerText || el.value || el.getAttribute('aria-label') || el.title || '').trim().replace(/\s+/g,' ').slice(0,80);
  const out = [];
  document.querySelectorAll(sig.sel).forEach((el, i) => {
    if (el.tagName.toLowerCase() === sig.tag && text(el) === sig.text && el.getAttribute('href') === sig.href
        && ((el.dataset || {}).href || null) === sig.dhref && regionOf(el) === sig.region) out.push(i);
  });
  return out;
}"""


def signature(el):
    return (el["tag"], el["text"], el.get("href"), (el.get("data") or {}).get("href"), el["region"])


# Core's build-history pager (the builds card of a job page, and of every job action page in the classic side panel,
# e.g. /job/<x>/batch-control-activation/): div.app-builds-container__controls holds button#up ("Newer builds") and
# button#down ("Older builds"); core's builds card script (core src/main/js/pages/project/builds-card.js) answers a click
# with GET buildHistory/ajax?older-than=<oldest entry id> (newer-than=<newest> for #up) and swaps the page of entries of
# div#jenkins-build-history in place: same URL, no dialog, no menu, and a page of 30 builds replacing another one barely
# changes the document's length (CI run 40158487092, reqonly classic, /job/fast/batch-control-activation/: builds #64..#35
# replaced by #34..#5, an HTML fragment 40743 -> 40721 bytes, read as NOTHING). The crawl reads that list instead: its
# entry ids (page-entry-id, a long that grows with the build) and build numbers, before and after the click.
PAGER_JS = """el => (el.matches('.app-builds-container__controls > button#up, .app-builds-container__controls > button#down')
  && document.getElementById('jenkins-build-history')) ? el.id : null"""
HISTORY_JS = r"""() => {
  const h = document.getElementById('jenkins-build-history');
  if (!h) return null;
  const pg = h.querySelector('[data-page-entry-newest]') || {dataset: {}};
  const items = [...h.querySelectorAll('.app-builds-container__item[page-entry-id]')];
  const nums = items.map(it => { const a = [...it.querySelectorAll('a[href]')].map(x => x.getAttribute('href'))
      .map(u => (u.match(/\/(\d+)\/(?:console)?$/) || [])[1]).find(Boolean); return a ? +a : null; });
  return {newest: pg.dataset.pageEntryNewest || null, oldest: pg.dataset.pageEntryOldest || null,
          has_up: pg.dataset.pageHasUp || null, has_down: pg.dataset.pageHasDown || null, count: items.length,
          first: nums[0] ?? null, last: nums[nums.length - 1] ?? null};
}"""


def _hist_key(h, end):
    """The newest/oldest entry of a history page as a number (its page-entry-id; its build number if core drops the id)."""
    if not h or not h.get("count"):
        return None
    v = h.get(end)
    try:
        return (0, int(v))
    except (TypeError, ValueError):
        return (1, h.get("first" if end == "newest" else "last"))


def pager_click(p, loc, which, before):
    """Click core's pager button `which` (down = Older builds, up = Newer builds) and wait until the history list shows a
    page strictly older (down) or newer (up) than `before`; core's periodic refresh of page 1 (a new batch-cron build)
    does not count. Returns the history after, or None when the list did not page within 3 s."""
    loc.click(timeout=5000)

    def moved():
        h = p.evaluate(HISTORY_JS)
        a, b = (_hist_key(h, "newest"), _hist_key(before, "oldest")) if which == "down" else \
            (_hist_key(h, "oldest"), _hist_key(before, "newest"))
        if a is None or b is None or a[0] != b[0]:
            return None
        return h if (a[1] < b[1] if which == "down" else a[1] > b[1]) else None
    got = until(p, moved, 3000)
    return got if isinstance(got, dict) else None


def classify_click(s, path, el):
    """Fresh load, Advanced expanded as when the controls were listed, find el again by its signature and occurrence,
    click it (a menu chevron after hovering its link), observe."""
    p = s.page
    s.go(path)
    expand_advanced(s)
    tag, text, href, dhref, region = signature(el)
    found = p.evaluate(REFIND_JS, {"sel": CONTROLS, "tag": tag, "text": text, "href": href, "dhref": dhref, "region": region})
    if len(found) <= el.get("occ", 0):
        return "gone-on-reload", {"found": len(found), "occ": el.get("occ", 0)}
    loc = p.locator(CONTROLS).nth(found[el.get("occ", 0)])
    if not loc.is_visible():
        return "not-visible-on-reload", {}
    before_url = p.url
    before_html = len(p.content())
    n_console = len(s.console)
    menus_before = p.locator(MENUS).count()
    info0 = {}
    pager = loc.evaluate(PAGER_JS)
    if pager:  # core's build-history pager: its effect is the list paging in place (see PAGER_JS)
        h0 = p.evaluate(HISTORY_JS)
        try:
            h1_ = pager_click(p, loc, pager, h0)
        except Exception as e:  # noqa
            return "click-failed", {"pager": pager, "err": str(e)}
        info = {"pager": pager, "history_before": h0, "console": s.console[n_console:][:10]}
        if p.url != before_url:
            return "navigated", dict(info, to=p.url.replace(BASE, ""))
        if not h1_:
            return "NOTHING", dict(info, history_after=p.evaluate(HISTORY_JS))
        info["history_after"] = h1_
        if pager == "down" and h1_.get("has_up") == "true":
            # on the older page core enables "Newer builds" (#up): it must bring the newer page back
            up = p.locator(".app-builds-container__controls > button#up")
            try:
                h2 = pager_click(p, up.first, "up", h1_)
                info["newer"] = {"result": "paged" if h2 else "NOTHING", "history_after": h2 or p.evaluate(HISTORY_JS)}
            except Exception as e:  # noqa
                info["newer"] = {"result": "click-failed", "err": str(e)}
            info["console"] = s.console[n_console:][:10]
        return "paged", info
    try:
        if el.get("chevron"):  # core: .model-link:hover .jenkins-menu-dropdown-chevron{pointer-events:all}
            host = loc.locator("xpath=ancestor::*[contains(concat(' ', normalize-space(@class), ' '), ' model-link ')][1]")
            (host if host.count() else loc.locator("xpath=..")).first.hover(timeout=5000)
            info0["hovered"] = True
        loc.click(timeout=5000)
    except Exception as e:  # noqa
        return "click-failed", dict(info0, err=str(e))
    # e2e-20: a dialog, a navigation or a new menu ends the wait at once (it was a fixed 1.2 s); a click that does none of
    # these is still observed for the same 1.2 s before it is classified as a DOM change or as doing nothing
    if react(p, before_url, timeout=1200, menus=MENUS, menus_before=menus_before) == "menu":
        try:  # the menu's entries (core's model-link menus fetch them); was covered by the fixed wait
            p.locator(MENUS).last.locator("a, button").first.wait_for(state="visible", timeout=2000)
        except Exception:
            pass
    info = dict(info0, console=s.console[n_console:][:10])
    dlg = p.locator("dialog[open]")
    if dlg.count():
        info["dialog"] = re.sub(r"\s+", " ", dlg.first.inner_text())[:160]
        p.keyboard.press("Escape")
        info["escape_closes"] = gone(p)  # e2e-20: was a fixed 0.5 s
        if not info["escape_closes"]:
            # some dialogs (preventCloseOnOutsideClick) may still close on Escape; record either way
            pass
        # reopen and use Cancel
        if info["escape_closes"]:
            try:
                before = p.url
                loc.click(timeout=5000)
                react(p, before, timeout=3000)  # e2e-20: was a fixed 1.2 s
            except Exception:
                pass
        d2 = p.locator("dialog[open]")
        if d2.count():
            # H1 C-19: Cancel and the title close button counted apart; core's parameters dialog (dialog.wizard) has
            # only button.jenkins-dialog__title__close-button, which the old selector did not know
            c = d2.first.locator("button[data-id=cancel], button:has-text('Cancel')")
            x = d2.first.locator("button.jenkins-dialog__title__close-button, .jenkins-dialog__close, button[aria-label='Close']")
            info["cancel_button"], info["close_button"] = c.count(), x.count()
            if c.count() or x.count():
                (c if c.count() else x).first.click()
                info["cancel_closes"] = gone(p)  # e2e-20: was a fixed 0.5 s
        info["url_unchanged"] = p.url == before_url
        return "dialog", info
    if p.url != before_url:
        st = None
        return "navigated", dict(info, to=p.url.replace(BASE, ""))
    menus = p.locator(MENUS)
    if menus.count():
        info["menu"] = re.sub(r"\s+", " ", menus.first.inner_text())[:200]
        p.keyboard.press("Escape")
        return "menu", info
    if abs(len(p.content()) - before_html) > 20:
        return "dom-changed", info
    return "NOTHING", info


def main():
    s = Session(ROLE)
    queue = list(START)
    seen, pat_count, cache, clicked = set(), {}, {}, set()
    while queue:
        path = queue.pop(0)
        if path in seen:
            continue
        seen.add(path)
        pt = pattern(path)
        if path not in START:
            pat_count[pt] = pat_count.get(pt, 0) + 1
            if pat_count[pt] > PATTERN_LIMIT:
                continue
        nc, nb = len(s.console), len(s.bad)
        try:
            r = s.go(path)
            status = r.status if r else None
        except Exception as e:  # noqa
            emit({"page": path, "kind": "page", "result": "load-error", "detail": str(e)[:200]})
            continue
        time.sleep(0.3)
        cons = list(s.console[nc:])
        bad = [b for b in s.bad[nb:] if not b.endswith(path) and "favicon" not in b]
        dropped = None
        if status and status >= 400 and f"{status} GET {BASE}{path}" in s.bad[nb:]:
            # H1 C-12: the browser repeats the document's own 4xx status as a console error; the status is judged itself
            own = f"error: Failed to load resource: the server responded with a status of {status} "
            dropped = next((c for c in cons if c.startswith(own)), None)
            if dropped:
                cons.remove(dropped)
        h1 = s.page.locator("h1").first.inner_text().strip()[:80] if s.page.locator("h1").count() else ""
        emit({"page": path, "kind": "page", "start": path in START, "status": status, "final": s.page.url.replace(BASE, ""),
              "h1": h1, "console": cons[:10], "bad_subrequests": bad[:5], "own_status_line_dropped": bool(dropped)})
        if status and status >= 400:
            continue
        content_checks(s, path, pt)
        adv = expand_advanced(s)
        if adv:  # D1 follow-up: what sits behind Advanced is checked too
            emit({"page": path, "pattern": pt, "kind": "advanced", "result": "expanded", "pressed": adv,
                  "revealed": re.sub(r"\s+", " ", s.page.locator("#main-panel").inner_text())[-300:]})
            content_checks(s, path, pt)
        els = s.page.evaluate(ELEMENTS_JS)
        is_bc = "/batch-control" in path
        occ = {}
        for el in els:
            el["occ"] = occ.get(signature(el), 0)
            occ[signature(el)] = el["occ"] + 1
            label = el["text"] or el.get("href") or ""
            relevant = is_bc or el["region"] == "crumb" or re.search(
                r"batch-control|Request|Activation|Approval|approval|Hold|Permission|Direct Build|Rebuild|Build",
                (el.get("href") or "") + " " + label + " " + json.dumps(el["data"]))
            if not relevant or (not el["visible"] and el["tag"] != "a") or el.get("anchor"):
                continue
            row = {"page": path, "pattern": pt, "region": el["region"], "visible": el["visible"], "tag": el["tag"], "label": label[:80],
                   "href": el.get("href"), "data": el["data"], "cls": (el.get("cls") or "")[:80]}
            if el.get("widget"):
                row["widget"] = el["widget"]  # core's build queue / executors pane (see CORE_STOP)
            href = el.get("href")
            # --- links
            if el["tag"] == "a":
                if el.get("helpurl") or "jenkins-help-button" in (el.get("cls") or "").split():
                    # H1 C-20: core's help "?" (Behaviour wires it through helpurl): its help text must load
                    st = check_url(s, urljoin(BASE + "/", el["helpurl"]), cache)[0] if el.get("helpurl") else None
                    emit(dict(row, kind="link", result="help-button", target=el.get("helpurl"), status=st))
                    continue
                if href is None or href.strip() in ("", "#") or href.startswith("javascript:"):
                    has_handler = bool(el["onclick"] or el["data"])
                    emit(dict(row, kind="link", result="handler-only" if has_handler else "DEAD-HREF"))
                    if has_handler:
                        key = (pt, label)
                        if key not in clicked:
                            clicked.add(key)
                            res, info = classify_click(s, path, el)
                            emit(dict(row, kind="click", result=res, detail=info))
                    continue
                url = el["abs"]
                if urlparse(url).netloc != urlparse(BASE).netloc:
                    emit(dict(row, kind="link", result="external", target=url))
                    continue
                tpath = url.replace(BASE, "")
                if SKIP.search(tpath):
                    continue
                st, fin = check_url(s, url, cache)
                posted = (el["data"].get("callback") or el["data"].get("taskPost") == "true"
                          or "jenkins-!-build-color" in (el.get("cls") or "").split())
                stop = (el.get("widget") and "stop-button-link" in (el.get("cls") or "").split()
                        and CORE_STOP.fullmatch(tpath))
                if st == 405 and stop:
                    # core's stop button in its build queue / executors pane (l:stopButton, posted by core's JS after its
                    # confirmation): a GET answers 405 like a POST form's action; never clicked (it cancels the build)
                    emit(dict(row, kind="link", status=st, target=tpath, final=fin, result="post-link"))
                elif st == 404 and stop and "/oneOffExecutors/" in tpath:
                    # a one-off (flyweight, e.g. a Pipeline run's) executor exists only while its build runs: the build
                    # ended between the listing and this GET (a regular executor slot stays and keeps answering 405)
                    emit(dict(row, kind="link", status=st, target=tpath, final=fin, result="transient-gone"))
                elif st == 405 and posted and re.search(r"/(build|rebuild)(/|\?|$)", tpath):
                    # H1 C-01/C-02: core posts this link (its JS); a GET answers 405 like a POST form's action
                    emit(dict(row, kind="link", status=st, target=tpath, final=fin, result="post-link"))
                else:
                    emit(dict(row, kind="link", status=st, target=tpath, final=fin,
                              result="OK" if 200 <= st < 400 else "BROKEN"))
                if el["data"].get("dialogUrl") or el["data"].get("callback") or "task-link" in (el["cls"] or "") and "batch-control" in tpath:
                    key = (pt, label)
                    if key not in clicked:
                        clicked.add(key)
                        res, info = classify_click(s, path, el)
                        emit(dict(row, kind="click", result=res, detail=info))
                if st == 200 and crawlable(tpath.split("#")[0]):
                    queue.append(tpath.split("#")[0])
                continue
            # --- buttons
            f = el["form"]
            is_submit = el["tag"] == "input" or (el["tag"] == "button" and (el["type"] in (None, "submit")))
            if f and is_submit:
                act = f["action"]
                if f["method"] == "post":
                    st, fin = check_url(s, act, cache)
                    emit(dict(row, kind="post-form", target=act.replace(BASE, ""), status_get=st,
                              result="OK(405 on GET)" if st == 405 else ("BROKEN" if st in (404, 0) or st >= 500 else f"get={st}")))
                else:
                    emit(dict(row, kind="get-form", target=act.replace(BASE, ""), result="checked-in-actions"))
                continue
            if el["tag"] == "select":
                emit(dict(row, kind="select", result="present"))
                continue
            if el.get("disabled") or (el.get("pe") == "none" and "disabled" in (el.get("cls") or "") and not el.get("chevron")):
                # H1 C-16: a disabled control (core's "Newer builds" on the first page: its --disabled class sets
                # pointer-events:none) takes no click; a menu chevron takes pointer events only while its link is
                # hovered and is clicked below, after the hover
                emit(dict(row, kind="click", result="inert", detail={"disabled": el.get("disabled"), "pointer_events": el.get("pe")}))
                continue
            if re.search(r"/administrativeMonitor/[^/]+/disable$", el["data"].get("url") or ""):
                # CI run 40080830269: core's monitor "Dismiss" posts at once (no confirmation) and disables the monitor for
                # good, so the crawl's own click removed the Batch Control monitor and its Mark as reviewed entries
                # before they were clicked (gone-on-reload). Nothing is POSTed here (see above): not clicked.
                emit(dict(row, kind="click", result="not-clicked", detail={"why": "posts at once: disables the monitor"}))
                continue
            key = (pt, label, el["data"].get("href", "")[:0] or el["region"])
            if key in clicked:
                emit(dict(row, kind="click", result="same-as-checked"))
                continue
            clicked.add(key)
            res, info = classify_click(s, path, el)
            emit(dict(row, kind="click", result=res, detail=info))
            if res in ("gone-on-reload", "not-visible-on-reload") and el.get("widget"):
                # the queue/executor entry ended before the fresh load (judged as such in ci/h1_rules.py): the next
                # control with the same key (another side chevron, e.g. Built-In Node's) is clicked instead
                clicked.discard(key)
    s.done()
    close()
    lib.h1.report("crawl", ROWS, {"role": ROLE, "ui": UI, "ids": ids, "prefix": urlparse(BASE).path.rstrip("/"),
                                  "profile": "role" if os.environ.get("BC_CRAWL_LOG") == "crawl-role" else "matrix"})


if __name__ == "__main__":
    main()
