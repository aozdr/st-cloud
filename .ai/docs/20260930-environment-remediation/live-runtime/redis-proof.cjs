const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const assert = require('node:assert/strict');
async function redisGet(key) {
  return new Promise((resolve,reject) => {
    const socket = net.connect(6379, '127.0.0.1'); let buffer=Buffer.alloc(0);
    socket.setTimeout(5000, () => socket.destroy(new Error('Redis timeout')));
    socket.on('error', reject);
    socket.on('connect', () => socket.write(`*2\r\n$3\r\nGET\r\n$${Buffer.byteLength(key)}\r\n${key}\r\n`));
    socket.on('data', (chunk) => {
      buffer=Buffer.concat([buffer,chunk]); const end=buffer.indexOf('\r\n'); if (end<0) return;
      if (buffer[0] !== 36) { socket.destroy(); reject(new Error('Redis bulk response expected')); return; }
      const size=Number(buffer.subarray(1,end));
      if (size === -1) { socket.end(); resolve(null); return; }
      if (buffer.length >= end+2+size+2) { const value=buffer.subarray(end+2,end+2+size).toString(); socket.end(); resolve(value); }
    });
  });
}
async function main() {
  const saved = JSON.parse(JSON.parse(fs.readFileSync(path.join(__dirname,'auth-storage.json'),'utf8'))['stcloud:auth']);
  const fixture = JSON.parse(fs.readFileSync(path.join(__dirname,'fixture.json'),'utf8'));
  const current = await redisGet('stcloud:refresh:' + fixture.userId);
  assert.ok(current === saved.refreshToken, '真实 Redis 当前 refresh 必须匹配持久 pair');
  console.log(JSON.stringify({ check:'redis_actual_refresh_matches_persisted_pair', userId:fixture.userId, endpoint:'127.0.0.1:6379', match:true }));
  // 仅轮换本轮首次调试创建的独立账号，废止曾进入断言诊断的专用令牌。
  const debugId = '2105297131776712706';
  const prior = await redisGet('stcloud:refresh:' + debugId);
  if (prior) {
    const response = await fetch('http://127.0.0.1:8080/api/auth/refresh', { method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({refreshToken:prior}) });
    const result = await response.json(); assert.ok(result.code === 200, '调试账号令牌轮换应成功');
    const currentDebug = await redisGet('stcloud:refresh:' + debugId);
    assert.ok(currentDebug !== prior, '调试输出中的旧令牌应失效');
    console.log(JSON.stringify({ check:'isolated_debug_refresh_invalidated', userId:debugId, rotated:true }));
  }
}
module.exports = { redisGet };
if (require.main === module) main().catch(e => { console.log(JSON.stringify({ failed:e.message })); process.exitCode=1; });
