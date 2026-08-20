import { spawn } from 'node:child_process';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9223;
const BASE = 'http://127.0.0.1:5173';
const OUT_DIR = 'C:\\Users\\aoz\\.codex\\visualizations\\2026\\08\\20\\01a01f24-f5dc-79f1-80c2-8ec426a0b8f8';
const userDataDir = mkdtempSync(path.join(tmpdir(), 'stc-del-'));

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
await waitFor(cdp, `document.querySelectorAll('tbody tr').length > 0`, 40000);
await sleep(1200);

const total = await cdp.eval(`document.querySelectorAll('tbody tr').length`);

// 1) Ctrl+A 全选
await cdp.eval(`document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', code: 'KeyA', ctrlKey: true, bubbles: true }))`);
await sleep(600);
const afterSelectAll = await cdp.eval(`(() => {
  const selected = [...document.querySelectorAll('tbody tr')].filter((tr) => tr.className.includes('bg-[#EEF0FF]')).length;
  const pill = [...document.querySelectorAll('button')].find((b) => b.textContent.includes('已选'))?.textContent.trim() || null;
  return { selectedRows: selected, pill };
})()`);

// 2) 按 Delete
await cdp.eval(`document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete', code: 'Delete', bubbles: true }))`);
await sleep(700);
const dialog = await cdp.eval(`(() => {
  const bodyText = document.body.innerText;
  const m = bodyText.match(/确定删除选中的 \\d+ 个文件/);
  return { dialogText: m ? m[0] : null, hasDialog: !!document.querySelector('[role="alertdialog"], .modal-content') };
})()`);

// 3) 关闭弹窗（取消）
await cdp.eval(`(() => {
  const btns = [...document.querySelectorAll('button')];
  const cancel = btns.find((b) => b.textContent.includes('取消'));
  if (cancel) cancel.click();
  return true;
})()`);
await sleep(400);

// 4) 工具栏删除按钮路径：全选后点“删除”按钮
await cdp.eval(`(() => {
  const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === '删除');
  if (btn) btn.click();
  return !!btn;
})()`);
await sleep(700);
const dialog2 = await cdp.eval(`(() => {
  const m = document.body.innerText.match(/确定删除选中的 \\d+ 个文件/);
  return { dialogText: m ? m[0] : null };
})()`);

const runScenario = async (name, setupExpr, selectExpr) => {
  await cdp.eval(setupExpr);
  await sleep(400);
  if (selectExpr) await cdp.eval(selectExpr);
  await sleep(600);
  const sel = await cdp.eval(`[...document.querySelectorAll('tbody tr')].filter((tr) => tr.className.includes('bg-[#EEF0FF]')).length`);
  await cdp.eval(`document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete', code: 'Delete', bubbles: true }))`);
  await sleep(600);
  const dlg = await cdp.eval(`(() => {
    const modal = [...document.querySelectorAll('div')].find((d) => d.className.includes('modal-content') || d.getAttribute('role') === 'alertdialog');
    const text = modal ? modal.innerText : document.body.innerText;
    const m = text.match(/确定删除选中的 \\d+ 个文件/);
    return m ? m[0] : null;
  })()`);
  // 关闭弹窗与清空选择
  await cdp.eval(`(() => {
    for (const b of [...document.querySelectorAll('button')]) {
      if (b.textContent.includes('取消')) b.click();
    }
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }));
    return true;
  })()`);
  await sleep(500);
  return { name, selectedBeforeDelete: sel, dialog: dlg };
};

const scenarios = [];
// B: Shift+点击范围选择
scenarios.push(await runScenario(
  'B: 点击+Shift点击',
  `true`,
  `(() => {
    const rows = [...document.querySelectorAll('tbody tr')];
    rows[0].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    rows[3].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, shiftKey: true }));
    return true;
  })()`,
));

// C: Ctrl+点击逐个加选
scenarios.push(await runScenario(
  'C: Ctrl+点击',
  `true`,
  `(() => {
    const rows = [...document.querySelectorAll('tbody tr')];
    rows[0].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    rows[2].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, ctrlKey: true }));
    rows[4].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, ctrlKey: true }));
    return true;
  })()`,
));

// D: Shift+方向键扩展
scenarios.push(await runScenario(
  'D: Shift+ArrowDown',
  `true`,
  `(() => {
    const rows = [...document.querySelectorAll('tbody tr')];
    rows[0].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    for (let i = 0; i < 3; i++) {
      document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', code: 'ArrowDown', shiftKey: true, bubbles: true }));
    }
    return true;
  })()`,
));

// E: 直接 Shift+点击（无先前单选锚点）
scenarios.push(await runScenario(
  'E: 直接Shift点击',
  `true`,
  `(() => {
    const rows = [...document.querySelectorAll('tbody tr')];
    rows[0].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, shiftKey: true }));
    rows[3].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, shiftKey: true }));
    return true;
  })()`,
));

// F: Ctrl+A 后再 Shift+点击
scenarios.push(await runScenario(
  'F: Ctrl+A后Shift点击',
  `true`,
  `(() => {
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', code: 'KeyA', ctrlKey: true, bubbles: true }));
    return true;
  })()`,
));
await sleep(500);
await cdp.eval(`(() => {
  const rows = [...document.querySelectorAll('tbody tr')];
  rows[3].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, shiftKey: true }));
  return true;
})()`);
await sleep(600);
const f = await cdp.eval(`[...document.querySelectorAll('tbody tr')].filter((tr) => tr.className.includes('bg-[#EEF0FF]')).length`);
scenarios.push({ name: 'F2: 全选后Shift点击选中数', selectedBeforeDelete: f, dialog: null });

// G: 真实时序 Shift+点击（两次点击分开任务）
await cdp.eval(`document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }))`);
await sleep(500);
await cdp.eval(`[...document.querySelectorAll('tbody tr')][0].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))`);
await sleep(500);
await cdp.eval(`[...document.querySelectorAll('tbody tr')][3].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, shiftKey: true }))`);
await sleep(600);
const g = await cdp.eval(`[...document.querySelectorAll('tbody tr')].filter((tr) => tr.className.includes('bg-[#EEF0FF]')).length`);
await cdp.eval(`document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete', code: 'Delete', bubbles: true }))`);
await sleep(600);
const gd = await cdp.eval(`(() => { const m = document.body.innerText.match(/确定删除选中的 \\d+ 个文件/); return m ? m[0] : null; })()`);
await cdp.eval(`(() => { for (const b of [...document.querySelectorAll('button')]) if (b.textContent.includes('取消')) b.click(); return true; })()`);
await sleep(400);

// H: 真实时序 Shift+ArrowDown（按键分开任务）
await cdp.eval(`document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }))`);
await sleep(500);
await cdp.eval(`[...document.querySelectorAll('tbody tr')][0].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))`);
await sleep(500);
for (let i = 0; i < 3; i++) {
  await cdp.eval(`document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', code: 'ArrowDown', shiftKey: true, bubbles: true }))`);
  await sleep(250);
}
const h = await cdp.eval(`[...document.querySelectorAll('tbody tr')].filter((tr) => tr.className.includes('bg-[#EEF0FF]')).length`);
await cdp.eval(`document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete', code: 'Delete', bubbles: true }))`);
await sleep(600);
const hd = await cdp.eval(`(() => { const m = document.body.innerText.match(/确定删除选中的 \\d+ 个文件/); return m ? m[0] : null; })()`);

console.log(JSON.stringify({ total, afterSelectAll, dialog, dialog2, scenarios, g: { selected: g, dialog: gd }, h: { selected: h, dialog: hd } }, null, 2));
cdp.close();
process.exit(0);
