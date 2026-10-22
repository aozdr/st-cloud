import { spawn } from 'node:child_process';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9225;
const BASE = 'http://127.0.0.1:5173';
const OUT_DIR = path.resolve('.ai/docs/20261017-ui-ux-p0-b');
const userDataDir = mkdtempSync(path.join(tmpdir(), 'stc-visual-'));

const chrome = spawn(CHROME, [
  '--headless=new',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${userDataDir}`,
  '--no-first-run',
  '--no-default-browser-check',
  '--disable-gpu',
  '--window-size=1440,900',
  '--force-device-scale-factor=1',
  'about:blank',
], { stdio: 'ignore' });
process.on('exit', () => { try { chrome.kill(); } catch { /* ignore */ } });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function getJson(url, method = 'GET') {
  const res = await fetch(url, { method });
  if (!res.ok) throw new Error(`${method} ${url} -> ${res.status}`);
  return res.json();
}
class CDP {
  constructor(wsUrl) {
    this.ws = new WebSocket(wsUrl); this.id = 0; this.pending = new Map();
    this.ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id); this.pending.delete(msg.id);
        if (msg.error) reject(new Error(JSON.stringify(msg.error))); else resolve(msg.result);
      }
    });
  }
  async open() { if (this.ws.readyState === WebSocket.OPEN) return; await new Promise((res, rej) => { this.ws.addEventListener('open', res, { once: true }); this.ws.addEventListener('error', rej, { once: true }); }); }
  send(method, params = {}) { const id = ++this.id; this.ws.send(JSON.stringify({ id, method, params })); return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject })); }
  async eval(expression) { const res = await this.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }); if (res.exceptionDetails) throw new Error(JSON.stringify(res.exceptionDetails)); return res.result.value; }
  close() { try { this.ws.close(); } catch { /* ignore */ } }
}
async function waitFor(cdp, expression, timeoutMs = 20000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) { try { const v = await cdp.eval(expression); if (v) return v; } catch { /* retry */ } await sleep(200); }
  throw new Error(`waitFor timeout: ${expression}`);
}
async function screenshot(cdp, name) {
  const res = await cdp.send('Page.captureScreenshot', { format: 'png' });
  writeFileSync(path.join(OUT_DIR, name), Buffer.from(res.data, 'base64'));
}
async function setTheme(cdp, label) {
  await cdp.eval(`(() => { const b = document.querySelector('button[aria-label="主题设置"]'); b?.focus(); b?.click(); return true; })()`);
  await waitFor(cdp, `!!document.querySelector('[role="dialog"][aria-label="设置"]')`);
  await cdp.eval(`(() => { const d = document.querySelector('[role="dialog"][aria-label="设置"]'); const b = Array.from(d.querySelectorAll('button')).find(x => x.textContent?.trim() === ${JSON.stringify(label)}); if (!b) throw new Error('theme button not found'); b.click(); return true; })()`);
  await sleep(250);
  await cdp.eval(`(() => { const d = document.querySelector('[role="dialog"][aria-label="设置"]'); const b = Array.from(d.querySelectorAll('button')).find(x => x.textContent?.trim() === '完成'); if (b) b.click(); return true; })()`);
  await waitFor(cdp, `!document.querySelector('[role="dialog"][aria-label="设置"]')`);
  await sleep(300);
}

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
  const data = (JSON.parse(login.body).data ?? JSON.parse(login.body));
  await cdp.eval(`(() => { localStorage.setItem('accessToken', ${JSON.stringify(data.token)}); localStorage.setItem('refreshToken', ${JSON.stringify(data.refreshToken)}); return true; })()`);
  await cdp.send('Page.navigate', { url: `${BASE}/files` });
  await waitFor(cdp, `!!document.querySelector('aside')`, 30000); await sleep(1200);

  await setTheme(cdp, '深色');
  const dark = await cdp.eval(`document.documentElement.classList.contains('dark')`);
  check('dark mode applied', dark);
  await screenshot(cdp, 'browser-files-dark.png');

  const whiteAreas = await cdp.eval(`(() => Array.from(document.querySelectorAll('body *')).map((el) => {
    const s = getComputedStyle(el); const r = el.getBoundingClientRect();
    return { tag: el.tagName, cls: String(el.className || '').slice(0, 80), bg: s.backgroundColor, area: r.width * r.height };
  }).filter((x) => x.bg === 'rgb(255, 255, 255)' && x.area > 20000).slice(0, 8))()`);
  check('dark files: no large white background', whiteAreas.length === 0, whiteAreas);

  await cdp.send('Page.navigate', { url: `${BASE}/recycle` });
  await waitFor(cdp, `Array.from(document.querySelectorAll('h1,h2')).some((e) => e.textContent?.includes('回收站'))`, 30000); await sleep(1000);
  await screenshot(cdp, 'browser-recycle-dark.png');
  const recycle = await cdp.eval(`(() => ({
    dark: document.documentElement.classList.contains('dark'),
    headerBg: getComputedStyle(document.querySelector('.flex.flex-col.h-full > div')).backgroundColor,
    bodyText: document.body.innerText.slice(0, 120),
  }))()`);
  check('recycle page renders in dark', recycle.dark, recycle);

  await cdp.send('Page.navigate', { url: `${BASE}/shares` });
  await waitFor(cdp, `Array.from(document.querySelectorAll('h1,h2')).some((e) => e.textContent?.includes('分享管理'))`, 30000); await sleep(1000);
  await screenshot(cdp, 'browser-shares-dark.png');
  const shares = await cdp.eval(`(() => ({
    dark: document.documentElement.classList.contains('dark'),
    headerBg: getComputedStyle(document.querySelector('.flex.flex-col.h-full > div')).backgroundColor,
    bodyText: document.body.innerText.slice(0, 120),
  }))()`);
  check('shares page renders in dark', shares.dark, shares);

  await setTheme(cdp, '浅色');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await cdp.send('Page.navigate', { url: `${BASE}/files` });
  await waitFor(cdp, `!!document.querySelector('aside')`, 30000); await sleep(1000);
  const mobile = await cdp.eval(`(() => {
    const table = document.querySelector('[role="table"]');
    const scroller = table?.parentElement;
    return {
      innerWidth: window.innerWidth,
      docScrollWidth: document.documentElement.scrollWidth,
      bodyScrollWidth: document.body.scrollWidth,
      tableScrollWidth: table?.scrollWidth ?? null,
      scrollerClientWidth: scroller?.clientWidth ?? null,
      pageOverflow: document.documentElement.scrollWidth - window.innerWidth,
      tableOverflow: table && scroller ? table.scrollWidth - scroller.clientWidth : null,
      hasMobileMeta: !!document.querySelector('[role="row"] [class*="md:hidden"]'),
    };
  })()`);
  check('mobile files: no page-level horizontal overflow', mobile.pageOverflow <= 0, mobile);
  check('mobile files: table fits scroller', mobile.tableOverflow !== null && mobile.tableOverflow <= 0, mobile);
  await screenshot(cdp, 'browser-files-mobile-light.png');

  results.finishedAt = new Date().toISOString();
  results.passed = results.checks.every((c) => c.pass);
  writeFileSync(path.join(OUT_DIR, 'browser-visual-results.json'), JSON.stringify(results, null, 2));
  console.log(JSON.stringify({ passed: results.passed, checks: results.checks.length }));
  cdp.close(); chrome.kill(); process.exit(results.passed ? 0 : 1);
} catch (err) {
  results.finishedAt = new Date().toISOString(); results.passed = false; results.error = err instanceof Error ? err.stack || err.message : String(err);
  writeFileSync(path.join(OUT_DIR, 'browser-visual-results.json'), JSON.stringify(results, null, 2));
  console.error(err); try { chrome.kill(); } catch { /* ignore */ } process.exit(1);
}
