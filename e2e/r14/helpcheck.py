"""e2e-12: every help '?' button on the Batch Control forms: its helpURL answers 200 with text, and a click
expands the help area with that text. Pages: configuration, job configure (job property), request forms.

H1 (2026-10-24): judged at the end (ci/h1_rules.py judge_targeted "help"): status 200, at least 20 characters, expands.
"expands" is measured the way helpcheck2.py does (triage T-01): the click must make one more help block visible on the
page (.help with text and a height). The old check looked for the help block in the button's form-item ancestor, which
does not reach core's help block of a checkbox, and called seven working checkbox helps "no-help-area". Each page's
console holds only that page's messages (T-02); warnings are recorded and never judged.

#31 (E2E-D4, docs/reports/e2e-01.md): on the two global configuration pages (the Batch Control page and the Batch Control
section of Manage Jenkins > System), after every help is open, each form item's label box must not overlap the box of
its description or open help text, and that box must start below the label: the defect laid the description out on the
checkbox caption's line, read as "Enable run controlWhen enabled, protected jobs require ...". Recorded as the page row's "layout" (rows before #31 have none and are not judged)."""
import re
from lib import Session, close, BASE
import lib
PAGES = ["/manage/batch-control-configuration/", "/manage/configure", "/job/batch-daily/configure", "/job/batch-daily/batch-control/",
         "/job/batch-cron/batch-control-activation/", "/batch-control/grants/new", "/job/ops/job/mb/configure"]
SHOWN = "() => [...document.querySelectorAll('.help')].filter(e => e.offsetHeight > 0 && e.innerText.trim().length > 20).length"
# #31 E2E-D4: the global configuration pages whose Batch Control section is measured (LAYOUT JS, judged as help "layout").
LAYOUT_PAGES = ("/manage/batch-control-configuration/", "/manage/configure")
LAYOUT = r"""() => {
  const title = s => { const t = s.querySelector(':scope > .jenkins-section__title'); return t ? t.textContent.trim() : ''; };
  const scopes = [...document.querySelectorAll('section.jenkins-section')].filter(s => title(s) === 'Batch Control'
      && !(s.parentElement && s.parentElement.closest('section.jenkins-section')));
  const box = e => { const b = e.getBoundingClientRect(); return {l: b.left, t: b.top, r: b.right, b: b.bottom}; };
  const shown = e => { const b = e.getBoundingClientRect(); return b.width > 0 && b.height > 0; };
  const r1 = b => [b.l, b.t, b.r, b.b].map(Math.round);
  const out = {sections: scopes.length, labels: 0, pairs: 0, overlap: [], runin: []};
  for (const sc of scopes) {
    const labels = [...sc.querySelectorAll('.jenkins-form-label, .jenkins-checkbox label, label.attach-previous, .jenkins-radio__label')]
        .filter(l => shown(l) && l.textContent.trim());
    out.labels += labels.length;
    const descs = [...sc.querySelectorAll('.jenkins-form-description, .setting-description, .help')]
        .filter(d => shown(d) && d.innerText.trim().length > 0);
    for (const d of descs) {
      const fi = d.closest('.jenkins-form-item');
      if (!fi) continue;
      const db = box(d);
      for (const l of labels.filter(l => l.closest('.jenkins-form-item') === fi)) {
        const lb = box(l), text = l.textContent.trim().replace(/\s+/g, ' ');
        out.pairs++;
        const ix = Math.min(lb.r, db.r) - Math.max(lb.l, db.l), iy = Math.min(lb.b, db.b) - Math.max(lb.t, db.t);
        if (ix > 0.5 && iy > 0.5) out.overlap.push({label: text, labelBox: r1(lb), descBox: r1(db), desc: d.innerText.trim().slice(0, 60)});
        if (db.t < lb.b - 1) out.runin.push({label: text, labelBox: r1(lb), descBox: r1(db), desc: d.innerText.trim().slice(0, 60)});
      }
    }
  }
  return out;
}"""
ROWS = []
s = Session("admin")
for page in PAGES:
    nc = len(s.console)
    s.go(page)
    helps = s.page.locator("a.jenkins-help-button, a[helpurl], .jenkins-help-button")
    rows = []
    for i in range(helps.count()):
        h = helps.nth(i)
        url = h.get_attribute("helpurl") or h.get_attribute("helpURL")
        if not url or "batchcontrol" not in url.lower() and "batch" not in url.lower():
            continue
        r = s.context.request.get(BASE.split("/jenkins")[0] + url if url.startswith("/") else url)
        body = re.sub(r"<[^>]+>", " ", r.text())
        clicked = None
        try:
            if not h.is_visible():
                h.scroll_into_view_if_needed()
            before = s.page.evaluate(SHOWN)
            h.click()
            lib.until(s.page, lambda: s.page.evaluate(SHOWN) > before, 2000)
            clicked = s.page.evaluate(SHOWN) > before
        except Exception as e:  # noqa
            clicked = "click-error " + str(e)[:200]
        rows.append({"url": url.replace("/jenkins", ""), "status": r.status, "chars": len(body.strip()), "expands": clicked})
    row = {"page": page, "helps": rows, "console": s.console[nc:][:10]}
    if page in LAYOUT_PAGES:
        row["layout"] = s.page.evaluate(LAYOUT)
        s.shot("#main-panel", "help-layout-" + ("system" if page == "/manage/configure" else "batch-control"))
        print(page, "layout", {k: v for k, v in row["layout"].items() if k in ("sections", "labels", "pairs")},
              "overlap", len(row["layout"]["overlap"]), "runin", len(row["layout"]["runin"]))
    ROWS.append(row)
    lib.log("help", row)
    print(page, len(rows), [x for x in rows if x["status"] != 200 or x["chars"] < 20 or x["expands"] not in (True,)])
s.done(); close()
lib.h1.report("help", ROWS)
