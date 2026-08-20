import { spawn } from 'node:child_process';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9223;
const BASE = 'http://127.0.0.1:5173';
const OUT_DIR = 'C:\\Users\\aoz\\.codex\\visualizations\\2026\\08\\20\\01a01f24-f5dc-79f1-80c2-8ec426a0b8f8';
const userDataDir = mkdtempSync(path.join(tmpdir(), 'stc-sidebar-'));

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
    const res = await this.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
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

for (let i = 0; i < 40; i++) {
  try { await getJson(`http://127.0.0.1:${PORT}/json/version`); break; } catch { await sleep(250); }
}
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
await cdp.eval(`(() => {
  localStorage.setItem('accessToken', ${JSON.stringify(parsedData.token)});
  localStorage.setItem('refreshToken', ${JSON.stringify(parsedData.refreshToken)});
  return true;
})()`);
await cdp.send('Page.navigate', { url: `${BASE}/files` });
await waitFor(cdp, `!!document.querySelector('aside')`, 40000);
await sleep(1500);

const state = await cdp.eval(`(() => {
  const aside = document.querySelector('aside');
  const cs = getComputedStyle(aside);
  const header = document.querySelector('header');
  const hcs = getComputedStyle(header);
  return {
    sidebarBg: cs.backgroundColor,
    borderRight: cs.borderRightWidth,
    radiusTL: cs.borderTopLeftRadius,
    radiusTR: cs.borderTopRightRadius,
    radiusBR: cs.borderBottomRightRadius,
    headerRadiusTL: hcs.borderTopLeftRadius,
    headerBg: hcs.backgroundColor,
    mainRadiusBL: getComputedStyle(document.querySelector('main')).borderBottomLeftRadius,
    mainBg: getComputedStyle(document.querySelector('main')).backgroundColor,
    navText: (() => { const a = aside.querySelector('nav a'); return a ? getComputedStyle(a).color : null; })(),
  };
})()`);

const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
const shotPath = path.join(OUT_DIR, 'sidebar-f4f6fc.png');
writeFileSync(shotPath, Buffer.from(shot.data, 'base64'));

console.log(JSON.stringify({ state, screenshot: shotPath }, null, 2));
cdp.close();
process.exit(0);
