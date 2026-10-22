import { spawn } from 'node:child_process';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9227;
const BASE = 'http://127.0.0.1:5173';
const OUT_DIR = path.resolve('.ai/docs/20261017-ui-ux-p1-b');
const userDataDir = mkdtempSync(path.join(tmpdir(), 'stc-p1b-'));
const chrome = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${userDataDir}`, '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--window-size=1440,900', '--force-device-scale-factor=1', 'about:blank'], { stdio: 'ignore' });
process.on('exit', () => { try { chrome.kill(); } catch { /* ignore */ } });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function getJson(url, method = 'GET') { const res = await fetch(url, { method }); if (!res.ok) throw new Error(`${method} ${url} -> ${res.status}`); return res.json(); }
class CDP {
  constructor(wsUrl) { this.ws = new WebSocket(wsUrl); this.id = 0; this.pending = new Map(); this.ws.addEventListener('message', (ev) => { const msg = JSON.parse(ev.data); if (msg.id && this.pending.has(msg.id)) { const { resolve, reject } = this.pending.get(msg.id); this.pending.delete(msg.id); if (msg.error) reject(new Error(JSON.stringify(msg.error))); else resolve(msg.result); } }); }
  async open() { if (this.ws.readyState === WebSocket.OPEN) return; await new Promise((res, rej) => { this.ws.addEventListener('open', res, { once: true }); this.ws.addEventListener('error', rej, { once: true }); }); }
  send(method, params = {}) { const id = ++this.id; this.ws.send(JSON.stringify({ id, method, params })); return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject })); }
  async eval(expression) { const res = await this.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }); if (res.exceptionDetails) throw new Error(JSON.stringify(res.exceptionDetails)); return res.result.value; }
  close() { try { this.ws.close(); } catch { /* ignore */ } }
}
async function waitFor(cdp, expression, timeoutMs = 30000) { const start = Date.now(); while (Date.now() - start < timeoutMs) { try { const v = await cdp.eval(expression); if (v) return v; } catch { /* retry */ } await sleep(200); } throw new Error(`waitFor timeout: ${expression}`); }
async function screenshot(cdp, name) { const res = await cdp.send('Page.captureScreenshot', { format: 'png' }); writeFileSync(path.join(OUT_DIR, name), Buffer.from(res.data, 'base64')); }
const results = { startedAt: new Date().toISOString(), checks: [] };
const check = (name, pass, detail) => { results.checks.push({ name, pass: Boolean(pass), detail }); console.log(`${pass ? 'PASS' : 'FAIL'} ${name}${detail ? ` — ${JSON.stringify(detail)}` : ''}`); };

try {
  for (let i = 0; i < 40; i++) { try { await getJson(`http://127.0.0.1:${PORT}/json/version`); break; } catch { await sleep(250); } }
  const page = await getJson(`http://127.0.0.1:${PORT}/json/new?about:blank`, 'PUT');
  const cdp = new CDP(page.webSocketDebuggerUrl);
  await cdp.open(); await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
  await cdp.send('Page.navigate', { url: `${BASE}/login` });
  await waitFor(cdp, `document.readyState === 'complete'`); await sleep(800);
  const login = await cdp.eval(`(async () => { const r = await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'admin123' }) }); return { ok: r.ok, body: await r.text() }; })()`);
  if (!login.ok) throw new Error('login failed');
  const parsed = JSON.parse(login.body); const data = parsed.data ?? parsed;
  await cdp.eval(`(() => { localStorage.setItem('accessToken', ${JSON.stringify(data.token)}); localStorage.setItem('refreshToken', ${JSON.stringify(data.refreshToken)}); return true; })()`);
  await cdp.send('Page.navigate', { url: `${BASE}/files` });
  await waitFor(cdp, `!!document.querySelector('[role="grid"] [role="row"][data-file-id]')`, 30000); await sleep(1000);

  const grid = await cdp.eval(`(() => {
    const g = document.querySelector('[role="grid"]');
    const rows = Array.from(g.querySelectorAll('[role="rowgroup"] [role="row"][data-file-id]'));
    const first = rows[0];
    const cells = first ? Array.from(first.querySelectorAll(':scope > [role="cell"]')) : [];
    const headers = Array.from(g.querySelectorAll('[role="columnheader"]'));
    return {
      role: g.getAttribute('role'),
      label: g.getAttribute('aria-label'),
      multiselect: g.getAttribute('aria-multiselectable'),
      rowcount: g.getAttribute('aria-rowcount'),
      colcount: g.getAttribute('aria-colcount'),
      rowgroups: g.querySelectorAll('[role="rowgroup"]').length,
      headerSorts: headers.map((h) => ({ text: h.textContent?.trim(), sort: h.getAttribute('aria-sort') })),
      firstRow: first ? {
        selected: first.getAttribute('aria-selected'),
        rowindex: first.getAttribute('aria-rowindex'),
        tabIndex: first.getAttribute('tabindex'),
        cells: cells.length,
      } : null,
      rowTabIndexes: rows.slice(0, 3).map((r) => r.getAttribute('tabindex')),
    };
  })()`);
  check('grid role/label/multiselect/rowcount/colcount', grid.role === 'grid' && grid.label === '文件列表' && grid.multiselect === 'true' && Number(grid.rowcount) >= 2 && Number(grid.colcount) === 7, grid);
  check('rowgroups >= 2', grid.rowgroups >= 2, grid);
  check('columnheader aria-sort present', grid.headerSorts.some((h) => h.sort && h.sort !== 'none'), grid.headerSorts);
  check('first data row has aria-selected + rowindex + roving tabindex', !!grid.firstRow && grid.firstRow.selected !== null && Number(grid.firstRow.rowindex) >= 2 && (grid.firstRow.tabIndex === '0' || grid.firstRow.tabIndex === '-1'), grid.firstRow);
  check('data row cells use role=cell', grid.firstRow?.cells >= 6, grid.firstRow);
  await screenshot(cdp, 'p1b-files-1440.png');

  // row selection updates aria-selected
  const rowSelected = await cdp.eval(`(() => {
    const row = document.querySelector('[role="grid"] [role="row"][data-file-id]');
    if (!row) return null;
    row.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    return row.getAttribute('data-file-id');
  })()`);
  await sleep(300);
  const selectedState = await cdp.eval(`(() => { const row = document.querySelector('[role="grid"] [role="row"][data-file-id]'); return row?.getAttribute('aria-selected'); })()`);
  check('row click updates aria-selected', selectedState === 'true', { rowSelected, selectedState });

  // mobile 390: sort entry + no overflow
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await cdp.send('Page.navigate', { url: `${BASE}/files` });
  await waitFor(cdp, `!!document.querySelector('[role="grid"] [role="row"][data-file-id]')`, 30000); await sleep(1000);
  const mobileBase = await cdp.eval(`(() => {
    const table = document.querySelector('[role="grid"]');
    const scroller = table?.parentElement;
    const trigger = document.querySelector('button[aria-label="排序"]');
    const r = trigger?.getBoundingClientRect();
    return {
      pageOverflow: document.documentElement.scrollWidth - window.innerWidth,
      tableOverflow: table && scroller ? table.scrollWidth - scroller.clientWidth : null,
      triggerVisible: !!trigger && r.width > 0 && r.height > 0,
      triggerSize: r ? [Math.round(r.width), Math.round(r.height)] : null,
      triggerRight: r?.right ?? null,
      innerWidth: window.innerWidth,
    };
  })()`);
  check('mobile sort trigger visible, >=36px, not clipped', mobileBase.triggerVisible && mobileBase.triggerSize?.[0] >= 36 && mobileBase.triggerRight <= mobileBase.innerWidth, mobileBase);
  check('mobile page/table no horizontal overflow', mobileBase.pageOverflow <= 0 && mobileBase.tableOverflow !== null && mobileBase.tableOverflow <= 0, mobileBase);

  // open sort menu, choose size, toggle direction
  const opened = await cdp.eval(`(() => { const b = document.querySelector('button[aria-label="排序"]'); if (!b) return false; b.focus(); b.click(); return true; })()`);
  if (opened) {
    await waitFor(cdp, `Array.from(document.querySelectorAll('button')).some((b) => b.textContent?.trim() === '大小' && !b.closest('[role="grid"]'))`, 10000);
    const clickedSize = await cdp.eval(`(() => { const b = Array.from(document.querySelectorAll('button')).find((x) => x.textContent?.trim() === '大小' && !x.closest('[role="grid"]')); if (!b) return false; b.click(); return true; })()`);
    await sleep(500);
    const sortState = await cdp.eval(`(() => {
      const h = Array.from(document.querySelectorAll('[role="columnheader"]')).find((x) => x.textContent?.includes('大小'));
      return { sort: h?.getAttribute('aria-sort'), text: h?.textContent?.trim() };
    })()`);
    check('mobile sort menu changes sort column', clickedSize && (sortState.sort === 'ascending' || sortState.sort === 'descending'), sortState);

    // reopen and toggle direction
    await cdp.eval(`(() => { const b = document.querySelector('button[aria-label="排序"]'); b?.click(); return true; })()`);
    await sleep(300);
    const toggled = await cdp.eval(`(() => { const b = Array.from(document.querySelectorAll('button')).find((x) => /升序|降序/.test(x.textContent || '') && !x.closest('[role="grid"]')); if (!b) return false; b.click(); return true; })()`);
    await sleep(500);
    const sortAfter = await cdp.eval(`(() => { const h = Array.from(document.querySelectorAll('[role="columnheader"]')).find((x) => x.textContent?.includes('大小')); return h?.getAttribute('aria-sort'); })()`);
    check('mobile sort direction toggles', toggled && sortAfter !== sortState.sort, { before: sortState.sort, after: sortAfter });
    await cdp.send('Page.captureScreenshot', { format: 'png' }).then((r) => writeFileSync(path.join(OUT_DIR, 'p1b-files-390-sorted.png'), Buffer.from(r.data, 'base64')));
  } else {
    check('mobile sort trigger can open', false);
  }

  results.finishedAt = new Date().toISOString();
  results.passed = results.checks.every((c) => c.pass);
  writeFileSync(path.join(OUT_DIR, 'browser-p1b-results.json'), JSON.stringify(results, null, 2));
  console.log(JSON.stringify({ passed: results.passed, checks: results.checks.length }));
  cdp.close(); chrome.kill(); process.exit(results.passed ? 0 : 1);
} catch (err) {
  results.finishedAt = new Date().toISOString(); results.passed = false; results.error = err instanceof Error ? err.stack || err.message : String(err);
  writeFileSync(path.join(OUT_DIR, 'browser-p1b-results.json'), JSON.stringify(results, null, 2));
  console.error(err); try { chrome.kill(); } catch { /* ignore */ } process.exit(1);
}
