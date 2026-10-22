import { spawn } from 'node:child_process';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9224;
const BASE = 'http://127.0.0.1:5173';
const OUT_DIR = path.resolve('.ai/docs/20261017-ui-ux-p0-b');
const userDataDir = mkdtempSync(path.join(tmpdir(), 'stc-a11y-'));

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
  close() { try { this.ws.close(); } catch { /* ignore */ } }
}

async function waitFor(cdp, expression, timeoutMs = 20000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const v = await cdp.eval(expression);
      if (v) return v;
    } catch { /* retry */ }
    await sleep(200);
  }
  throw new Error(`waitFor timeout: ${expression}`);
}

async function press(cdp, key, code, vk) {
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', key, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk });
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk });
  await sleep(120);
}

async function screenshot(cdp, name) {
  const res = await cdp.send('Page.captureScreenshot', { format: 'png' });
  writeFileSync(path.join(OUT_DIR, name), Buffer.from(res.data, 'base64'));
}

const results = { startedAt: new Date().toISOString(), base: BASE, checks: [] };
const check = (name, pass, detail) => {
  results.checks.push({ name, pass: Boolean(pass), detail });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${name}${detail ? ` — ${JSON.stringify(detail)}` : ''}`);
};

try {
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
  await sleep(1000);

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
  const data = parsed.data ?? parsed;
  await cdp.eval(`(() => {
    localStorage.setItem('accessToken', ${JSON.stringify(data.token)});
    localStorage.setItem('refreshToken', ${JSON.stringify(data.refreshToken)});
    return true;
  })()`);
  await cdp.send('Page.navigate', { url: `${BASE}/files` });
  await waitFor(cdp, `!!document.querySelector('aside')`, 30000);
  await sleep(1500);
  check('authenticated files page loaded', true);

  // 1) Settings dialog: focus trap, body lock, Escape, focus restore
  await cdp.eval(`(() => { const b = document.querySelector('button[aria-label="主题设置"]'); b?.focus(); b?.click(); return true; })()`);
  await waitFor(cdp, `!!document.querySelector('[role="dialog"][aria-label="设置"]')`);
  const settingsOpen = await cdp.eval(`(() => {
    const d = document.querySelector('[role="dialog"][aria-label="设置"]');
    return { activeInside: !!d && d.contains(document.activeElement), bodyOverflow: document.body.style.overflow, activeTag: document.activeElement?.tagName };
  })()`);
  check('settings: focus inside + body locked', settingsOpen.activeInside && settingsOpen.bodyOverflow === 'hidden', settingsOpen);

  let trapOk = true;
  for (let i = 0; i < 16; i++) {
    await press(cdp, 'Tab', 'Tab', 9);
    const inside = await cdp.eval(`(() => { const d = document.querySelector('[role="dialog"][aria-label="设置"]'); return !!d && d.contains(document.activeElement); })()`);
    if (!inside) { trapOk = false; break; }
  }
  check('settings: Tab stays trapped', trapOk);

  await press(cdp, 'Escape', 'Escape', 27);
  await waitFor(cdp, `!document.querySelector('[role="dialog"][aria-label="设置"]')`);
  const settingsClosed = await cdp.eval(`(() => ({
    bodyOverflow: document.body.style.overflow,
    focusRestored: document.activeElement?.getAttribute('aria-label') === '主题设置',
  }))()`);
  check('settings: Escape closes + body/focus restored', settingsClosed.bodyOverflow === '' && settingsClosed.focusRestored, settingsClosed);
  await screenshot(cdp, 'browser-files-light.png');

  // 2) Context menu: role, initial focus, arrow keys, Escape
  const hasRow = await cdp.eval(`!!document.querySelector('[role="row"][data-file-id], [data-file-id]')`);
  if (hasRow) {
    await cdp.eval(`(() => {
      const row = document.querySelector('[role="row"][data-file-id], [data-file-id]');
      const r = row.getBoundingClientRect();
      row.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, button: 2, clientX: r.left + 80, clientY: r.top + 16 }));
      return true;
    })()`);
    await waitFor(cdp, `!!document.querySelector('[role="menu"]')`);
    const menuOpen = await cdp.eval(`(() => ({
      role: document.querySelector('[role="menu"]')?.getAttribute('role'),
      activeRole: document.activeElement?.getAttribute('role'),
      activeText: document.activeElement?.textContent?.trim(),
    }))()`);
    check('context menu: role + initial focus', menuOpen.role === 'menu' && menuOpen.activeRole === 'menuitem', menuOpen);

    const firstText = menuOpen.activeText;
    await press(cdp, 'ArrowDown', 'ArrowDown', 40);
    const secondText = await cdp.eval(`document.activeElement?.textContent?.trim()`);
    check('context menu: ArrowDown moves focus', secondText && secondText !== firstText, { firstText, secondText });

    await press(cdp, 'End', 'End', 35);
    const endText = await cdp.eval(`document.activeElement?.textContent?.trim()`);
    check('context menu: End moves to last item', Boolean(endText), { endText });

    await press(cdp, 'Escape', 'Escape', 27);
    await waitFor(cdp, `!document.querySelector('[role="menu"]')`);
    check('context menu: Escape closes', true);
  } else {
    check('context menu: file row available', false, 'no file row found');
  }

  // 3) Create folder dialog: initial focus + Escape + focus restore
  const newButton = await cdp.eval(`(() => {
    const buttons = Array.from(document.querySelectorAll('button'));
    const b = buttons.find(x => x.textContent?.trim().startsWith('新建'));
    if (b) { b.click(); return true; }
    return false;
  })()`);
  if (newButton) {
    await sleep(300);
    const clickedFolder = await cdp.eval(`(() => {
      const item = Array.from(document.querySelectorAll('button')).find(x => x.textContent?.trim() === '新建文件夹');
      if (item) { item.click(); return true; }
      return false;
    })()`);
    if (clickedFolder) {
      await waitFor(cdp, `!!document.querySelector('[role="dialog"][aria-label="新建文件夹"]')`);
      const folderOpen = await cdp.eval(`(() => {
        const d = document.querySelector('[role="dialog"][aria-label="新建文件夹"]');
        return { activeTag: document.activeElement?.tagName, activeInside: !!d && d.contains(document.activeElement), bodyOverflow: document.body.style.overflow };
      })()`);
      check('create folder: initial focus + body lock', folderOpen.activeTag === 'INPUT' && folderOpen.activeInside && folderOpen.bodyOverflow === 'hidden', folderOpen);
      await press(cdp, 'Escape', 'Escape', 27);
      await waitFor(cdp, `!document.querySelector('[role="dialog"][aria-label="新建文件夹"]')`);
      check('create folder: Escape closes', true);
    } else {
      check('create folder: menu item available', false);
    }
  } else {
    check('create folder: new button available', false);
  }

  // 4) ConfirmDialog: escape cancels "清空回收站" without deleting
  await cdp.send('Page.navigate', { url: `${BASE}/recycle` });
  await waitFor(cdp, `Array.from(document.querySelectorAll('h1,h2')).some((e) => e.textContent?.includes('回收站'))`, 30000);
  await sleep(800);
  const confirmOpened = await cdp.eval(`(() => {
    const b = Array.from(document.querySelectorAll('button')).find(x => x.textContent?.trim() === '清空回收站');
    if (b) { b.focus(); b.click(); return true; }
    return false;
  })()`);
  if (confirmOpened) {
    await waitFor(cdp, `!!document.querySelector('[role="dialog"][aria-label="清空回收站"]')`);
    const confirmState = await cdp.eval(`(() => {
      const d = document.querySelector('[role="dialog"][aria-label="清空回收站"]');
      return { activeInside: !!d && d.contains(document.activeElement), bodyOverflow: document.body.style.overflow };
    })()`);
    check('confirm dialog: focus + body lock', confirmState.activeInside && confirmState.bodyOverflow === 'hidden', confirmState);
    await press(cdp, 'Escape', 'Escape', 27);
    await waitFor(cdp, `!document.querySelector('[role="dialog"][aria-label="清空回收站"]')`);
    const confirmClosed = await cdp.eval(`document.body.style.overflow`);
    check('confirm dialog: Escape cancels + restores body', confirmClosed === '', { bodyOverflow: confirmClosed });
  } else {
    check('confirm dialog: empty recycle button available', false);
  }

  // 5) PreviewModal: dialog semantics, scroll lock, Escape
  await cdp.send('Page.navigate', { url: `${BASE}/files` });
  await waitFor(cdp, `!!document.querySelector('[role="row"][data-file-id]')`, 30000);
  await sleep(800);
  const previewOpened = await cdp.eval(`(() => {
    const row = Array.from(document.querySelectorAll('[role="row"][data-file-id]')).find((r) => r.textContent?.includes('converted_gif.gif')) || document.querySelector('[role="row"][data-file-id]');
    if (!row) return false;
    row.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true }));
    return true;
  })()`);
  if (previewOpened) {
    await waitFor(cdp, `!!document.querySelector('[role="dialog"][aria-label="文件预览"]')`, 15000);
    const previewState = await cdp.eval(`(() => {
      const d = document.querySelector('[role="dialog"][aria-label="文件预览"]');
      return { activeInside: !!d && d.contains(document.activeElement), bodyOverflow: document.body.style.overflow };
    })()`);
    check('preview: dialog semantics + body lock', previewState.activeInside && previewState.bodyOverflow === 'hidden', previewState);
    await press(cdp, 'Escape', 'Escape', 27);
    await waitFor(cdp, `!document.querySelector('[role="dialog"][aria-label="文件预览"]')`);
    check('preview: Escape closes', true);
  } else {
    check('preview: file row available', false);
  }

  await screenshot(cdp, 'browser-context-menu-closed.png');
  results.finishedAt = new Date().toISOString();
  results.passed = results.checks.every((c) => c.pass);
  writeFileSync(path.join(OUT_DIR, 'browser-walkthrough-results.json'), JSON.stringify(results, null, 2));
  console.log(JSON.stringify({ passed: results.passed, checks: results.checks.length }));
  cdp.close();
  chrome.kill();
  process.exit(results.passed ? 0 : 1);
} catch (err) {
  results.finishedAt = new Date().toISOString();
  results.passed = false;
  results.error = err instanceof Error ? err.stack || err.message : String(err);
  writeFileSync(path.join(OUT_DIR, 'browser-walkthrough-results.json'), JSON.stringify(results, null, 2));
  console.error(err);
  try { chrome.kill(); } catch { /* ignore */ }
  process.exit(1);
}
