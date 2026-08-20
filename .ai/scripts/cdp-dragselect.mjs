import { spawn } from 'node:child_process';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9223;
const BASE = 'http://127.0.0.1:5173';
const OUT_DIR = 'C:\\Users\\aoz\\.codex\\visualizations\\2026\\08\\20\\01a01f24-f5dc-79f1-80c2-8ec426a0b8f8';
const userDataDir = mkdtempSync(path.join(tmpdir(), 'stc-drag-chrome-'));

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

process.on('exit', () => {
  try { chrome.kill(); } catch { /* ignore */ }
});

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function getJson(url, method = 'GET') {
  const res = await fetch(url, { method });
  if (!res.ok) throw new Error(`${method} ${url} -> ${res.status}`);
  return res.json();
}

async function waitDebugger() {
  for (let i = 0; i < 40; i++) {
    try {
      return await getJson(`http://127.0.0.1:${PORT}/json/version`);
    } catch {
      await sleep(250);
    }
  }
  throw new Error('Chrome CDP endpoint not available');
}

class CDP {
  constructor(wsUrl) {
    this.ws = new WebSocket(wsUrl);
    this.id = 0;
    this.pending = new Map();
    this.ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(JSON.stringify(msg.error)));
        else resolve(msg.result);
      }
    });
  }

  async open() {
    if (this.ws.readyState === WebSocket.OPEN) return;
    await new Promise((resolve, reject) => {
      this.ws.addEventListener('open', resolve, { once: true });
      this.ws.addEventListener('error', reject, { once: true });
    });
  }

  send(method, params = {}) {
    const id = ++this.id;
    this.ws.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }));
  }

  async eval(expression) {
    const res = await this.send('Runtime.evaluate', {
      expression,
      awaitPromise: true,
      returnByValue: true,
    });
    if (res.exceptionDetails) throw new Error(JSON.stringify(res.exceptionDetails));
    return res.result.value;
  }

  close() {
    try { this.ws.close(); } catch { /* ignore */ }
  }
}

async function waitFor(cdp, expression, timeoutMs = 30000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const v = await cdp.eval(expression);
      if (v) return v;
    } catch { /* retry */ }
    await sleep(400);
  }
  throw new Error(`waitFor timeout: ${expression}`);
}

await waitDebugger();
const page = await getJson(`http://127.0.0.1:${PORT}/json/new?about:blank`, 'PUT');
const cdp = new CDP(page.webSocketDebuggerUrl);
await cdp.open();
await cdp.send('Page.enable');
await cdp.send('Runtime.enable');

await cdp.send('Page.navigate', { url: `${BASE}/login` });
await waitFor(cdp, `document.readyState === 'complete'`);
await sleep(1500);
const login = await cdp.eval(`(async () => {
  const r = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }),
  });
  const body = await r.text();
  return { ok: r.ok, status: r.status, body };
})()`);
if (!login.ok) throw new Error('login failed: ' + JSON.stringify(login));
const parsed = JSON.parse(login.body);
const parsedData = parsed.data ?? parsed;
const token = parsedData.token;
const refreshToken = parsedData.refreshToken;
if (!token || !refreshToken) throw new Error('unexpected login payload: ' + login.body);

await cdp.eval(`(() => {
  localStorage.setItem('accessToken', ${JSON.stringify(token)});
  localStorage.setItem('refreshToken', ${JSON.stringify(refreshToken)});
  return true;
})()`);
await cdp.send('Page.navigate', { url: `${BASE}/files` });
await waitFor(cdp, `document.readyState === 'complete'`);
await waitFor(cdp, `document.querySelectorAll('tbody tr').length > 0`, 40000);
await sleep(1200);

// 收集几何信息：区域容器 / 内层容器 / 行
const geo = await cdp.eval(`(() => {
  const zone = [...document.querySelectorAll('div')].find((d) => [...d.classList].includes('pt-1') && [...d.classList].includes('pb-8') && d.className.includes('bg-surface'));
  const inner = [...zone.querySelectorAll('div')].find((d) => d.className.includes('bg-surface') && d.className.includes('overflow-hidden'));
  const rows = [...document.querySelectorAll('tbody tr')];
  const r = (el) => { const b = el.getBoundingClientRect(); return { top: b.top, bottom: b.bottom, left: b.left, right: b.right }; };
  const first = rows.length ? r(rows[0]) : null;
  const last = rows.length ? r(rows[rows.length - 1]) : null;
  return {
    zone: zone ? r(zone) : null,
    inner: inner ? r(inner) : null,
    firstRow: first,
    lastRow: last,
    rowCount: rows.length,
  };
})()`);

// 1) 从表格下方空白区向上拖拽（真实鼠标事件）
const startX = Math.round((geo.zone.left + geo.zone.right) / 2);
const startY = Math.round(geo.inner.bottom + 10);
const endY = Math.round((geo.firstRow.top + geo.firstRow.bottom) / 2);
await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: startX, y: startY, button: 'left', buttons: 1, clickCount: 1 });
for (let i = 1; i <= 4; i++) {
  const y = startY + Math.round((endY - startY) * i / 4);
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: startX, y, button: 'left', buttons: 1 });
  await sleep(30);
}
await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: startX, y: endY, button: 'left', buttons: 0, clickCount: 1 });
await sleep(400);

const dragResult = await cdp.eval(`(() => {
  const selected = [...document.querySelectorAll('tbody tr')].filter((tr) => tr.className.includes('bg-[#EEF0FF]')).length;
  const bar = document.body.innerText.includes('已选');
  return { selectedRows: selected, totalRows: document.querySelectorAll('tbody tr').length };
})()`);

// 2) 点击单行（不应触发框选，仅单选）
await cdp.eval(`(() => {
  const rows = [...document.querySelectorAll('tbody tr')];
  if (rows.length) rows[0].dispatchEvent(new MouseEvent('mousedown', { bubbles: true, button: 0 }));
  return true;
})()`);
await sleep(200);

// 3) 搜索框是否居中
const searchCheck = await cdp.eval(`(() => {
  const input = document.querySelector('input[aria-label="搜索文件"]');
  let wrap = input;
  while (wrap && ![...wrap.classList].some((c) => c.includes('max-w-'))) wrap = wrap.parentElement;
  const header = document.querySelector('header');
  const hr = header.getBoundingClientRect();
  const wr = wrap.getBoundingClientRect();
  return {
    searchCenter: Math.round(wr.left + wr.width / 2),
    headerCenter: Math.round(hr.left + hr.width / 2),
    delta: Math.abs((wr.left + wr.width / 2) - (hr.left + hr.width / 2)),
  };
})()`);

const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
const shotPath = path.join(OUT_DIR, 'files-dragselect-check.png');
writeFileSync(shotPath, Buffer.from(shot.data, 'base64'));

console.log(JSON.stringify({ geo, dragResult, searchCheck, screenshot: shotPath }, null, 2));

cdp.close();
process.exit(0);
