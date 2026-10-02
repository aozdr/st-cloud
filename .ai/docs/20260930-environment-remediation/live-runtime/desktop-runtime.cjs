const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const repo = path.resolve(__dirname, '../../../..');
const src = path.join(repo, 'st-desktop/src');
const nativeRequire = createRequire(path.join(repo, 'st-desktop/package.json'));
const ts = nativeRequire('typescript');
function createDesktopRuntime(directory, log) {
  fs.mkdirSync(directory, { recursive:true });
  const cache = new Map();
  const sourceHashes = new Map();
  const emit = (channel, value) => { if (channel === 'sync:event' && value.event === 'log') log?.({ type:value.data.type, message:value.data.message }); };
  const electron = { app:{ getPath:() => directory, getAppPath:() => path.join(repo, 'st-desktop') }, BrowserWindow:{ getAllWindows:() => [{ webContents:{ send:emit } }] } };
  function load(relative) {
    const filename = path.resolve(src, relative);
    if (cache.has(filename)) return cache.get(filename);
    const exports = {};
    cache.set(filename, exports);
    const rawSource = fs.readFileSync(filename);
    sourceHashes.set(filename, require('node:crypto').createHash('sha256').update(rawSource).digest('hex'));
    const code = ts.transpileModule(rawSource.toString('utf8'), { compilerOptions:{ module:ts.ModuleKind.CommonJS, target:ts.ScriptTarget.ES2022, esModuleInterop:true } }).outputText;
    const localRequire = (name) => {
      if (name === 'electron') return electron;
      if (name.startsWith('.')) { const target = path.resolve(path.dirname(filename), name) + '.ts'; return load(path.relative(src, target)); }
      return nativeRequire(name);
    };
    const safeConsole = { ...console, error:(...args) => console.log(JSON.stringify({ diagnostic:String(args[0]), message:String(args[1]?.message || '') .replace(/[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, '[REDACTED_TOKEN]') })) };
    new Function('exports', 'require', '__dirname', '__filename', 'console', code)(exports, localRequire, path.dirname(filename), filename, safeConsole);
    return exports;
  }
  return { load, sourceManifest:() => [...sourceHashes].map(([file, sha256]) => ({ file:path.relative(repo,file).replaceAll('\\','/'), sha256 })).sort((a,b) => a.file.localeCompare(b.file)) };
}
module.exports = { createDesktopRuntime, repo };
