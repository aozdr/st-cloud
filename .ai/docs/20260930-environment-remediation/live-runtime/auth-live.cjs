const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const repo = path.resolve(__dirname, '../../../..');
const axios = require(path.join(repo, 'st-desktop/node_modules/axios/dist/node/axios.cjs'));
const { loadDesktop, loadWeb, makeBridge } = require(path.join(repo, 'st-desktop/src/auth-test-harness.cjs'));
const base = 'http://127.0.0.1:8080';
const storage = path.join(__dirname, 'auth-storage.json');
const api = axios.create({ baseURL: base + '/api', timeout: 30000 });
const authSources = ['st-desktop/src/api-client.ts','st-desktop/src/auth-test-harness.cjs','st-web/src/auth-session.ts','st-web/src/lib/api.ts'];
function sourceManifest() { return authSources.map(file => ({ file, sha256:require('node:crypto').createHash('sha256').update(fs.readFileSync(path.join(repo,file))).digest('hex') })); }
const startedManifest = sourceManifest();
function safeError(error) { const message = String(error.response?.data?.message || error.message || '').replace(/[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, '[REDACTED_TOKEN]'); return error.response ? { status:error.response.status, code:error.response.data?.code, message } : { message }; }
async function rejectedOld(refreshToken) {
  const res = await api.post('/auth/refresh', { refreshToken });
  assert.notEqual(res.data.code, 200, '旧 refresh 必须拒绝');
  console.log(JSON.stringify({ check:'old_refresh_rejected', http:res.status, businessCode:res.data.code }));
}
async function concurrentRound(web, desktop, label) {
  const prior = desktop.getAuth().refreshToken;
  let count = 0;
  const originalPost = axios.post;
  axios.post = (...args) => { if (args[0] === base + '/api/auth/refresh') count++; return originalPost(...args); };
  try {
    const responses = await Promise.all([
      desktop.apiClient.get('/auth/me'), desktop.apiClient.get('/auth/me'),
      web.api.get('/auth/me'), web.api.get('/auth/me'),
    ]);
    responses.slice(0, 2).forEach((r) => assert.equal(r.data.code, 200));
    responses.slice(2).forEach((r) => assert.ok(r?.userId || r?.username));
    assert.equal(count, 1, '主进程/渲染器并发只能轮换一次');
    assert.ok(desktop.getAuth().refreshToken !== prior, '刷新令牌应轮换');
    assert.ok(web.localStorage.getItem('refreshToken') === desktop.getAuth().refreshToken, '页面/主进程 refresh 应一致');
    assert.ok(web.sessionStorage.getItem('accessToken') === desktop.getAuth().token, '页面/主进程 access 应一致');
    await rejectedOld(prior);
    console.log(JSON.stringify({ check:label, requests:4, refreshCalls:count, pairPersisted:true }));
  } finally { axios.post = originalPost; }
}
async function main() {
  if (process.argv[2] === 'restart') {
    const desktop = loadDesktop(base);
    const web = loadWeb({ base, file: storage, bridge:makeBridge(desktop) });
    await web.auth.syncDesktopAuth();
    const before = desktop.getAuth();
    assert.ok(before.token && before.refreshToken);
    assert.ok(web.auth.getCurrentAuth().refreshToken === before.refreshToken, '恢复 pair 应一致');
    await web.auth.refreshSession();
    assert.ok(desktop.getAuth().refreshToken !== before.refreshToken, '恢复后刷新应轮换');
    await rejectedOld(before.refreshToken);
    const me = await desktop.apiClient.get('/auth/me');
    assert.equal(me.data.code, 200);
    console.log(JSON.stringify({ check:'fresh_node_process_restored_pair_and_rotated', process:process.pid, accessValidated:true }));
    return;
  }
  let username = 'env_live_muo6b5r2';
  let credentials;
  if (fs.existsSync(storage)) {
    const stored = JSON.parse(JSON.parse(fs.readFileSync(storage, 'utf8'))['stcloud:auth']);
    if (stored.refreshToken) credentials = stored;
    else {
      // 审计文件已经脱敏时，只从已记录的本轮专用账号恢复新的真实凭据，不触及既有用户。
      const fixture = JSON.parse(fs.readFileSync(path.join(__dirname,'fixture.json'),'utf8'));
      const current = await require('./redis-proof.cjs').redisGet('stcloud:refresh:' + fixture.userId);
      assert.ok(current, '本轮专用账号真实 Redis refresh 应存在');
      const refreshed = await api.post('/auth/refresh', { refreshToken:current });
      assert.ok(refreshed.data.code === 200, '审计脱敏后专用账号真实 refresh 应成功');
      credentials = refreshed.data.data;
    }
  } else {
    username = 'env_live_' + Date.now().toString(36);
    const password = 'LiveFixture!' + require('node:crypto').randomBytes(12).toString('hex');
    const register = await api.post('/auth/register', { username, password, nickname:'本轮真实认证同步验证' });
    assert.equal(register.data.code, 200, JSON.stringify({ registerCode:register.data.code, message:register.data.message }));
    credentials = register.data.data;
  }
  const userId = Buffer.from(credentials.token.split('.')[1], 'base64url').toString().match(/"userId"\s*:\s*(?:"([0-9]+)"|([0-9]+))/);
  username = JSON.parse(Buffer.from(credentials.token.split('.')[1], 'base64url')).sub;
  console.log(JSON.stringify({ check:'dedicated_fixture_registered', username, userId:userId?.[1] || userId?.[2] }));
  const desktop = loadDesktop(base);
  const web = loadWeb({ base, accessToken:'invalid-access-round-1', refreshToken:credentials.refreshToken, file:storage, bridge:makeBridge(desktop) });
  await web.auth.saveLoginCredentials({ token:'invalid-access-round-1', refreshToken:credentials.refreshToken }, web.auth.beginAuthSession());
  await web.auth.syncDesktopAuth();
  await concurrentRound(web, desktop, 'real_http_redis_concurrent_round_1');
  const latest = desktop.getAuth();
  await web.auth.saveLoginCredentials({ token:'invalid-access-round-2', refreshToken:latest.refreshToken }, web.auth.beginAuthSession());
  await concurrentRound(web, desktop, 'real_http_redis_concurrent_round_2');
  const restarted = spawnSync(process.execPath, [__filename, 'restart'], { encoding:'utf8', timeout:45000 });
  process.stdout.write(restarted.stdout);
  assert.equal(restarted.status, 0, restarted.stderr);
  fs.writeFileSync(path.join(__dirname, 'fixture.json'), JSON.stringify({ username, userId:userId?.[1] || userId?.[2], createdAt:new Date().toISOString(), base }));
  assert.ok(JSON.stringify(startedManifest) === JSON.stringify(sourceManifest()), '验证期间认证源码未发生变化');
  fs.writeFileSync(path.join(__dirname,'auth-source-manifest.json'), JSON.stringify({ executedAt:new Date().toISOString(), process:process.pid, backend:base, files:startedManifest }, null, 2));
  console.log(JSON.stringify({ check:'AUTH01_AUTH02_PASS', realBackend:true, realRedisRotation:true, realElectron:false }));
}
main().catch((error) => { console.error(JSON.stringify({ failed:safeError(error) })); process.exitCode=1; });
