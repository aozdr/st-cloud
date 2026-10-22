import { spawn } from 'node:child_process';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9226;
const BASE = 'http://127.0.0.1:5173';
const OUT_DIR = path.resolve('.ai/docs/20261017-ui-ux-p1-a');
const userDataDir = mkdtempSync(path.join(tmpdir(), 'stc-p1a-'));
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
  await waitFor(cdp, `!!document.querySelector('[role="row"][data-file-id]')`, 30000); await sleep(1000);

  const root = await cdp.eval(`(() => {
    const title = document.querySelector('h1')?.textContent?.trim();
    const breadcrumb = document.querySelector('[aria-label="路径导航"]');
    const toolbarUpload = Array.from(document.querySelectorAll('button')).find(b => b.className.includes('btn-primary') && b.textContent?.includes('上传'));
    const toolbarNew = Array.from(document.querySelectorAll('button')).find(b => b.className.includes('btn-secondary') && b.textContent?.trim().startsWith('新建'));
    return {
      title,
      documentTitle: document.title,
      favicon: document.querySelector('link[rel="icon"]')?.getAttribute('href'),
      themeColor: document.querySelector('meta[name="theme-color"]')?.getAttribute('content'),
      hasBreadcrumb: !!breadcrumb,
      breadcrumbBeforeToolbar: !!(breadcrumb && toolbarUpload && (breadcrumb.compareDocumentPosition(toolbarUpload) & Node.DOCUMENT_POSITION_FOLLOWING)),
      uploadPrimary: !!toolbarUpload && toolbarUpload.className.includes('btn-primary'),
      newSecondary: !!toolbarNew && toolbarNew.className.includes('btn-secondary'),
      sortVisible: !!Array.from(document.querySelectorAll('button')).find(b => b.textContent?.trim() === '名称' && b.getBoundingClientRect().width > 0),
    };
  })()`);
  check('root title is 全部文件', root.title === '全部文件', root);
  check('document title follows route', root.documentTitle === '全部文件 - 星云盘', root);
  check('PWA favicon/theme-color updated', root.favicon === '/favicon.svg' && root.themeColor === '#F7F8FA', root);
  check('breadcrumb exists before toolbar', root.hasBreadcrumb && root.breadcrumbBeforeToolbar, root);
  check('upload primary + new secondary', root.uploadPrimary && root.newSecondary, root);
  await screenshot(cdp, 'p1a-files-root-1440.png');

  // folder title
  const folderOpened = await cdp.eval(`(() => { const row = Array.from(document.querySelectorAll('[role="row"][data-file-id]')).find(r => r.textContent?.includes('uu')); if (!row) return false; row.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true })); return true; })()`);
  if (folderOpened) {
    await sleep(1200);
    const folder = await cdp.eval(`(() => ({ title: document.querySelector('h1')?.textContent?.trim(), breadcrumb: !!document.querySelector('[aria-label="路径导航"]') }))()`);
    check('folder title uses current folder name', folder.title === 'uu', folder);
    await screenshot(cdp, 'p1a-files-folder-1440.png');
  } else {
    check('folder title uses current folder name', false, 'folder row not found');
  }

  // category page no breadcrumb
  await cdp.send('Page.navigate', { url: `${BASE}/files/category/image` });
  await waitFor(cdp, `document.querySelector('h1')?.textContent?.trim() === '图片'`, 30000); await sleep(800);
  const category = await cdp.eval(`(() => ({ title: document.querySelector('h1')?.textContent?.trim(), breadcrumb: !!document.querySelector('[aria-label="路径导航"]') }))()`);
  check('category title + no breadcrumb', category.title === '图片' && !category.breadcrumb, category);

  // standalone /admin route title (outside AppLayout)
  await cdp.send('Page.navigate', { url: `${BASE}/admin` });
  await waitFor(cdp, `document.title === '系统管理 - 星云盘'`, 30000); await sleep(500);
  const adminTitle = await cdp.eval(`document.title`);
  check('admin document title works outside AppLayout', adminTitle === '系统管理 - 星云盘', { adminTitle });

  // mobile 390
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await cdp.send('Page.navigate', { url: `${BASE}/files` });
  await waitFor(cdp, `!!document.querySelector('[role="row"][data-file-id]')`, 30000); await sleep(1000);
  const mobile = await cdp.eval(`(() => {
    const table = document.querySelector('[role="table"]');
    const scroller = table?.parentElement;
    const upload = Array.from(document.querySelectorAll('button')).find(b => b.className.includes('btn-primary') && b.textContent?.includes('上传'));
    const star = document.querySelector('[role="row"][data-file-id] button[aria-label="收藏"]');
    const more = document.querySelector('[role="row"][data-file-id] button[aria-label="更多操作"]');
    const viewBtn = document.querySelector('button[aria-label="列表视图"]');
    return {
      pageOverflow: document.documentElement.scrollWidth - window.innerWidth,
      tableOverflow: table && scroller ? table.scrollWidth - scroller.clientWidth : null,
      uploadRight: upload?.getBoundingClientRect().right ?? null,
      viewRight: viewBtn?.getBoundingClientRect().right ?? null,
      innerWidth: window.innerWidth,
      starOpacity: star ? getComputedStyle(star).opacity : null,
      moreOpacity: more ? getComputedStyle(more).opacity : null,
      starSize: star ? [star.getBoundingClientRect().width, star.getBoundingClientRect().height] : null,
      moreSize: more ? [more.getBoundingClientRect().width, more.getBoundingClientRect().height] : null,
    };
  })()`);
  check('mobile page/table no horizontal overflow', mobile.pageOverflow <= 0 && mobile.tableOverflow !== null && mobile.tableOverflow <= 0, mobile);
  check('mobile toolbar controls not clipped', mobile.uploadRight !== null && mobile.viewRight !== null && mobile.uploadRight <= mobile.innerWidth && mobile.viewRight <= mobile.innerWidth, mobile);
  check('mobile row actions visible', mobile.starOpacity === '1' && mobile.moreOpacity === '1', mobile);
  check('mobile row action hit area >=36', mobile.starSize?.[0] >= 36 && mobile.moreSize?.[0] >= 36, mobile);
  await screenshot(cdp, 'p1a-files-mobile-390.png');

  results.finishedAt = new Date().toISOString();
  results.passed = results.checks.every((c) => c.pass);
  writeFileSync(path.join(OUT_DIR, 'browser-p1a-results.json'), JSON.stringify(results, null, 2));
  console.log(JSON.stringify({ passed: results.passed, checks: results.checks.length }));
  cdp.close(); chrome.kill(); process.exit(results.passed ? 0 : 1);
} catch (err) {
  results.finishedAt = new Date().toISOString(); results.passed = false; results.error = err instanceof Error ? err.stack || err.message : String(err);
  writeFileSync(path.join(OUT_DIR, 'browser-p1a-results.json'), JSON.stringify(results, null, 2));
  console.error(err); try { chrome.kill(); } catch { /* ignore */ } process.exit(1);
}
