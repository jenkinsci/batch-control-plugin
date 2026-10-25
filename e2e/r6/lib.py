"""e2e-06 driver (hosting review 2026-10-03), Jenkins under the context path /jenkins.

Python port of the Node driver legacy/extra/lib.mjs for machines without Node.js:
  python3 -m venv venv && venv/bin/pip install playwright requests
Playwright drives the installed Google Chrome (channel="chrome"), no browser download.

Rules as in legacy/extra: one browser context per account (real login form), a
clipped, red-boxed screenshot per step (screenshots/run-6/<name>.png, never
committed), server state read separately with basic auth; the script console is
used only to arrange or read state.

Every CI driver (r7 ... r19) gets its browser through this file, so the shared behaviour of e2e-20 lives here:
login reuse per account (BC_AUTH_DIR), a Playwright trace per context (BC_TRACE_DIR) and the condition waits
react(), gone() and poll() that replace fixed sleeps.
"""
import atexit
import json
import os
import pathlib
import re
import time

import requests
from playwright.sync_api import sync_playwright

HERE = pathlib.Path(__file__).resolve().parent
SHOTS = HERE.parent / "screenshots" / "run-6"
OUT = HERE / "out"
SHOTS.mkdir(parents=True, exist_ok=True)
OUT.mkdir(parents=True, exist_ok=True)
BASE = os.environ.get("BC_BASE", "http://localhost:8080/jenkins")

ENV = {}
for line in (HERE.parent / ".env").read_text().splitlines():
    if "=" in line and not line.startswith("#"):
        k, v = line.split("=", 1)
        ENV[k] = v
PW = {"admin": ENV["BC_ADMIN_PASSWORD"], "requester": ENV["BC_REQUESTER_PASSWORD"],
      "approver-1": ENV["BC_APPROVER_PASSWORD"], "approver-2": ENV["BC_APPROVER_PASSWORD"]}


def pw(user):
    return PW.get(user, ENV["BC_OTHER_PASSWORD"])


_pw = None
_browser = None
_OPEN = []
_STATS = {"logins": 0, "reused": 0}
TRACE_DIR = os.environ.get("BC_TRACE_DIR") or None
AUTH_DIR = (os.environ.get("BC_AUTH_DIR") or None) if os.environ.get("BC_LOGIN_REUSE", "1") != "0" else None


def browser():
    """Headless Google Chrome by default. BC_BROWSER_CHANNEL=chromium uses Playwright's own Chromium instead
    (`python -m playwright install --with-deps chromium`, pinned by the playwright version: the CI runner, ci/run.sh);
    another value is passed through as the channel (e.g. msedge). BC_HEADED=1 shows the window."""
    global _pw, _browser
    if _browser is None:
        _pw = sync_playwright().start()
        channel = os.environ.get("BC_BROWSER_CHANNEL", "chrome")
        _browser = _pw.chromium.launch(channel=None if channel in ("", "chromium") else channel,
                                       headless=os.environ.get("BC_HEADED") != "1")
    return _browser


def close():
    global _pw, _browser
    for sess in list(_OPEN):
        sess.done()
    if _browser:
        _browser.close()
        _pw.stop()
    _browser = _pw = None


def _safe(name):
    return re.sub(r"[^A-Za-z0-9_.-]", "_", name)


def _auth_file(user):
    if not AUTH_DIR:
        return None
    d = pathlib.Path(AUTH_DIR)
    d.mkdir(parents=True, exist_ok=True)
    return d / (_safe(user) + ".json")


def _who(context):
    """The account a context's cookies authenticate (None when anonymous or on any error)."""
    try:
        r = context.request.get(f"{BASE}/whoAmI/api/json", timeout=15000, max_redirects=0)
        if r.status != 200:
            return None
        j = r.json()
        return None if j.get("anonymous") or not j.get("authenticated") else j.get("name")
    except Exception:
        return None


class Session:
    """A browser context logged in as one account; records console errors and failed requests.

    Login reuse (e2e-20): when BC_AUTH_DIR is set (ci/shard.py sets one directory per shard run, outside the
    artefacts), the first Session of an account logs in through the real login form and saves the context's
    storage_state to BC_AUTH_DIR/<user>.json; later Sessions of that account, in this or a later step, start from that
    state in a new context and only confirm with /whoAmI/api/json that it still authenticates that account. A restart,
    a logout or a deleted and re-created account ends the server session: then it logs in again and saves the new
    state. fresh=True (or BC_LOGIN_REUSE=0) always logs in. Every Session is still its own browser context.

    Tracing (e2e-20): when BC_TRACE_DIR is set, every context records a Playwright trace (screenshots, DOM snapshots),
    started after the login so that the typed password is not in it. It is written to BC_TRACE_DIR/<n>-<user>.zip when
    the context is closed (done(), close() or at interpreter exit, also after an exception); ci/shard.py keeps the
    directory only for a step that did not pass (`playwright show-trace <zip>` or https://trace.playwright.dev)."""

    _n = 0

    def __init__(self, user, dark=False, fresh=False):
        self.user = user
        self.console = []
        self.bad = []
        self._closed = False
        Session._n += 1
        self._seq = Session._n
        opts = dict(viewport={"width": 1280, "height": 900}, locale="en-US", color_scheme="dark" if dark else "light")
        state = _auth_file(user) if user and not fresh else None
        self.context = None
        self.reused = False
        if state is not None and state.exists():
            ctx = browser().new_context(storage_state=str(state), **opts)
            if _who(ctx) == user:
                self.context, self.reused = ctx, True
                _STATS["reused"] += 1
            else:
                ctx.close()
        if self.context is None:
            self.context = browser().new_context(**opts)
        self.page = self.context.new_page()
        self.page.set_default_timeout(20000)
        self.page.on("console", lambda m: self.console.append(f"{m.type}: {m.text}") if m.type in ("error", "warning") else None)
        self.page.on("pageerror", self._record_pageerror)
        self.page.on("response", lambda r: self.bad.append(f"{r.status} {r.request.method} {r.url}") if r.status >= 400 else None)
        if user and not self.reused:
            p = self.page
            p.goto(f"{BASE}/login")
            p.fill("#j_username", user)
            p.fill('input[name="j_password"]', pw(user))
            p.click('button[name="Submit"], button[type="submit"]')
            p.wait_for_load_state("load")
            if "loginError" in p.url:
                raise RuntimeError(f"login failed for {user}")
            _STATS["logins"] += 1
            if state is not None:
                tmp = state.with_suffix(".tmp")
                self.context.storage_state(path=str(tmp))
                tmp.replace(state)
        _OPEN.append(self)
        if TRACE_DIR:
            self.context.tracing.start(screenshots=True, snapshots=True, title=f"{user or 'anonymous'} #{self._seq}")

    def _record_pageerror(self, e):
        """Record a page error with its stack trace and the URL of the page it fired on (#87): the bare message
        alone ("Cannot read properties of undefined (reading 'replace')") names neither the throwing script nor
        the page, so the def07 FAIL row could not say where the error came from. The entry keeps the old first
        line, so existing readers of self.console (prefix and substring checks) see the same text as before.
        Recording must never fail a step, so a page that is already navigating or closed still leaves the
        message behind on its own."""
        entry = f"pageerror: {e}"
        try:
            stack = (getattr(e, "stack", None) or "").strip()
            if stack:
                entry += f"\nstack: {stack[:1000]}"
            entry += f"\nurl: {self.page.url}"
        except Exception:
            pass
        self.console.append(entry)

    def go(self, path):
        r = self.page.goto(BASE + path)
        self.page.wait_for_load_state("load")
        return r

    def shot(self, target, name, pad=20):
        return shot(self.page, target, name, pad)

    def text(self):
        m = self.page.locator("#main-panel")
        return m.inner_text() if m.count() else self.page.locator("body").inner_text()

    def done(self):
        if self._closed:
            return
        self._closed = True
        if self in _OPEN:
            _OPEN.remove(self)
        if TRACE_DIR:
            try:
                d = pathlib.Path(TRACE_DIR)
                d.mkdir(parents=True, exist_ok=True)
                self.context.tracing.stop(path=str(d / f"{self._seq:03d}-{_safe(self.user or 'anonymous')}.zip"))
            except Exception as e:  # noqa: a trace must never fail a step
                print(f"WARN trace not written: {e!r}"[:300], flush=True)
        try:
            self.context.close()
        except Exception:
            pass


@atexit.register
def _at_exit():
    """A driver that raised or never called close() still writes its traces; the login counts go to the step log."""
    try:
        close()
    except Exception:
        pass
    if AUTH_DIR and (_STATS["logins"] or _STATS["reused"]):
        print(f"SESSIONS logins={_STATS['logins']} reused={_STATS['reused']}", flush=True)


# ------------------------------------------------------------------ condition waits (e2e-20)
MENUS = ".tippy-box, .jenkins-dropdown, [data-tippy-root]"


def react(page, before_url=None, timeout=3000, menus=MENUS, menus_before=None):
    """After a click: wait until the page reacted, at most `timeout` ms, and say how: "dialog" (a dialog[open]),
    "navigated" (the URL changed or the page is navigating), "menu" (more `menus` elements than before the click), or
    None when nothing of that happened in time (the caller then classifies as before: DOM change or nothing). Replaces
    the fixed wait_for_timeout(1200..1500) after a click; a navigation is then waited for to `load` as before."""
    before_url = page.url if before_url is None else before_url
    navs = []

    def on_nav(frame):
        if frame == page.main_frame:
            navs.append(frame.url)

    def what():
        if navs or page.url != before_url:
            return "navigated"
        if page.locator("dialog[open]").count():
            return "dialog"
        if menus and menus_before is not None and page.locator(menus).count() > menus_before:
            return "menu"
        return None

    page.on("framenavigated", on_nav)
    try:
        how = until(page, what, timeout)
    finally:
        page.remove_listener("framenavigated", on_nav)
    if how == "navigated":
        try:
            page.wait_for_load_state("load", timeout=10000)
        except Exception:
            pass
    elif how == "dialog":
        dialog_ready(page)
    return how


DIALOG_READY_JS = """() => { const d = document.querySelector('dialog[open]'); if (!d) return 'gone';
  if (d.querySelector('form, textarea, select, input:not([type=hidden])')) return 'form';
  return d.querySelectorAll('button').length >= 2 ? 'buttons' : null; }"""


def dialog_ready(page, timeout=5000):
    """Core opens a form dialog (dialog.wizard) as an empty shell with only its Close button and fills it when the
    form has been fetched; wait until the open dialog holds a form or a field, or at least two buttons (a confirmation
    dialog: OK and Cancel), or is gone. Returns which, or None after `timeout` ms."""
    return until(page, lambda: page.evaluate(DIALOG_READY_JS), timeout)


def until(page, pred, timeout=3000):
    """Poll the Python predicate pred() every 50 ms (letting Playwright deliver events) until it is truthy or `timeout`
    ms pass; returns its value, or None on timeout. An exception in pred() (the page navigated away under it) counts as
    a result ("navigated")."""
    deadline = time.monotonic() + timeout / 1000
    while True:
        try:
            v = pred()
        except Exception:
            v = "navigated"
        if v or time.monotonic() >= deadline:
            return v or None
        page.wait_for_timeout(50)  # poll interval of a condition wait, not a fixed sleep


def submit_and_wait(page, act, timeout=10000):
    """Submit a dialog form with act() (a click or a key press) and wait for the outcome instead of a fixed time:
    "navigated" (the URL changed; waited to load), "replaced" (core's dialog replaced the submitted form with the
    server's re-rendered one, e.g. a 400 with field errors), "closed" (no dialog left) or None (nothing within
    `timeout` ms)."""
    before = page.url
    form = page.locator("dialog[open] form")
    handle = form.first.element_handle() if form.count() else None

    def outcome():
        if page.url != before:
            return "navigated"
        if handle is not None and not handle.evaluate("e => e.isConnected"):
            return "replaced" if page.locator("dialog[open]").count() else "closed"
        if not page.locator("dialog[open]").count():
            return "closed"
        return None

    act()
    how = until(page, outcome, timeout)
    if how == "replaced":
        dialog_ready(page)
    elif how in ("navigated", "closed"):
        try:
            page.wait_for_load_state("load", timeout=10000)
        except Exception:
            pass
    return how


def opened(page, timeout=5000):
    """A dialog is open with its buttons (core's confirmation and form dialogs); True if so within `timeout` ms.
    Replaces the fixed 0.7-1 s waits after clicking an entry that opens a dialog."""
    try:
        page.locator("dialog[open]").first.wait_for(state="visible", timeout=timeout)
    except Exception:
        return False
    return dialog_ready(page, timeout) not in (None, "gone")


def gone(page, selector="dialog[open]", timeout=2000):
    """Wait until no element matches `selector` (core removes a closed dialog after its animation); True if gone."""
    try:
        page.wait_for_selector(selector, state="detached", timeout=timeout)
        return True
    except Exception:
        return page.locator(selector).count() == 0


def poll(fn, timeout=60, interval=1.0, what="condition"):
    """Call fn() until it returns a truthy value or `timeout` seconds pass; returns the last value (falsy on timeout).
    For server state (REST, the script console) that becomes true at some point: replaces a fixed time.sleep(n)."""
    deadline = time.monotonic() + timeout
    while True:
        v = fn()
        if v or time.monotonic() >= deadline:
            if not v:
                print(f"WAIT timed out after {timeout}s: {what}", flush=True)
            return v
        time.sleep(interval)  # poll interval of a condition wait, not a fixed sleep


def shot(page, target, name, pad=20):
    f = SHOTS / f"{name}.png"
    targets = target if isinstance(target, list) else [target]
    locs = [page.locator(t).first if isinstance(t, str) else t for t in targets]
    boxes = []
    for t in locs:
        try:
            if t.count() == 0:
                continue
            t.evaluate("el => { el.style.outline = '3px solid red'; el.style.outlineOffset = '2px'; }")
            b = t.bounding_box()
            if b:
                boxes.append(b)
        except Exception:
            pass
    if boxes:
        x0 = max(0, min(b["x"] for b in boxes) - pad)
        y0 = max(0, min(b["y"] for b in boxes) - pad)
        x1 = max(b["x"] + b["width"] for b in boxes) + pad
        y1 = max(b["y"] + b["height"] for b in boxes) + pad
        page.screenshot(path=str(f), full_page=True, clip={"x": x0, "y": y0, "width": max(1, x1 - x0), "height": max(1, min(y1 - y0, 3000))})
    else:
        page.screenshot(path=str(f), full_page=True)
    for t in locs:
        try:
            if t.count():
                t.evaluate("el => { el.style.outline = ''; }")
        except Exception:
            pass
    return f.name


def api(user, path, method="GET", data=None, crumb=True, **kw):
    s = requests.Session()
    s.auth = (user, pw(user))
    headers = kw.pop("headers", {})
    if method == "POST" and crumb:
        c = s.get(BASE + "/crumbIssuer/api/json")
        if c.status_code == 200:
            j = c.json()
            headers[j["crumbRequestField"]] = j["crumb"]
    return s.request(method, BASE + path, data=data, headers=headers, allow_redirects=False, **kw)


def groovy(script):
    r = api("admin", "/scriptText", "POST", data={"script": script})
    return r.text.strip()


def log(name, obj):
    with open(OUT / f"{name}.jsonl", "a") as fh:
        fh.write(json.dumps(obj, default=str) + "\n")
