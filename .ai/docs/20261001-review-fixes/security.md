# 独立安全复核

执行者：`/root/review_fixes_security`。Dispatch：`DISPATCH-review-fixes-security-01`。复核修订：`review-fixes-code-r1`。时间：2026-10-01 12:04 UTC。

## 背景与输入

本次独立任务复核跨标签完整凭据、旧响应/退出交错，以及软删除空间清理后的租户和成员权限。读取本次 TASK、定版 design/testcases、当前 State 相关标准、changereport/source-manifest，并沿当前认证会话、API 拦截器、认证 Store 和回收服务调用链检查。Envelope 必填字段、角色、白名单及 forbidSpawn 均符合当前协议；skillRefs 为 `-`。

开始与结束复核各一次 SHA256：清单六个文件全部匹配 `review-fixes-code-r1`。未读取历史测试证据，未运行 Maven、修改源码/State/数据库/Git index 或派发。

## 分析与事实

正常路径中，共享记录使用单次 JSON 写入保存完整令牌对；storage 回调重读当前存储，低修订不回滚；身份变化推进 generation。API 的旧 401 先检查上下文，认证 Store 的 fetchUser 在提交用户状态前复核上下文。续期成功/拒绝均重新核对身份，因此正常换账号时旧请求不能覆盖或清理新会话。

墓碑空间锁查询保留 `id + tenant_id + FOR UPDATE`；用户列表、恢复、永久删除仍调用限定有效空间、有效管理员/拥有者成员、成员期限及外部协作权限的查询。系统 purge 锁后检查 RECYCLED，再按租户和空间遍历子树；保留期仍由扫描入口控制。源码和新增用例未显示本轮扩大用户删除权限、跨空间删除或事务内新增外部网络调用。后端结论来自静态调用链及当前测试断言审阅，本 child 未独立执行 H2/MySQL/S3。

## 发现：P2 写入失败后另一页拒绝会清除有效内存会话

事实：Web Locks 和普通 HTTP ticket 两种路径均可复现。A 成功在服务端将 R0 轮换为 R1；`persist()` 的共享 pair 写入暂时失败，但吞掉错误并返回（auth-session.ts:90–108、304–305），随后释放协调所有权。B 得到所有权后只能读到共享 R0，其 401 被视作可以清理的永久拒绝（241、273–276、313），删除共享记录。新增 storage 同步随后将 A 仍有效的内存 R1 替换为空会话（55–58、72–87）。

真实云盘触发条件：多个标签页打开同一账号；首次续期成功，但浏览器存储在提交新 pair 时临时拒绝写入；另一页先于 A 的下次恢复写入执行续期。影响是本来已在内存恢复成功的页也被误登出。没有观察到凭据泄漏或越权；问题是可用性和失败恢复语义。

现有写失败回归只让 A 自己再次续期和落盘，未覆盖 B 抢先使用残留 R0。现有 canClear 只证明排他权，不能证明上一次成功 pair 已落盘。该条件与定版设计要求保留临时故障/磁盘失败恢复语义冲突。

## 验证证据

- 本 child 执行 `node --test st-web/src/store/auth-tabs.test.mjs`：退出码 0，14/14 通过，失败/跳过均 0；真实本机 HTTP CAS fixture，浏览器事件/锁由独立 VM 模拟。
- 本 child 在 `st-desktop` 目录以 `node` stdin 执行下列只读复现，退出码 0；存储与 HTTP 均为内存受控 fixture，无文件/云端写入。输出：

```json
{"mode":"locks","aBefore":"refresh-1","bResult":"rejected","aAfter":null,"shared":null}
{"mode":"tickets","aBefore":"refresh-1","bResult":"rejected","aAfter":null,"shared":null}
```

可复现代码（`node` stdin，工作目录 `st-desktop`）：

```js
const axios = require('axios');
const { loadWeb, sharedStorage, webLocks } = require('./src/auth-test-harness.cjs');
(async () => {
  for (const mode of ['locks', 'tickets']) {
    const base = 'http://security-fixture';
    const seed = { token: 'access-0', refreshToken: 'refresh-0', sessionId: 'shared', serverUrl: base, revision: 0 };
    const hub = sharedStorage({ 'stcloud:auth': JSON.stringify(seed), refreshToken: 'refresh-0' });
    let refresh = 'refresh-0';
    const fakeAxios = { ...axios, post: async (_, body) => {
      if (body.refreshToken !== refresh) throw { isAxiosError: true, response: { status: 401 } };
      refresh = 'refresh-1';
      return { data: { code: 200, data: { token: 'access-1', refreshToken: refresh } } };
    } };
    const locks = mode === 'locks' ? webLocks() : undefined;
    const a = loadWeb({ base, storageHub: hub, locks, axiosOverride: fakeAxios });
    const b = loadWeb({ base, storageHub: hub, locks, axiosOverride: fakeAxios });
    const originalSet = hub.storage.setItem;
    let failed = false;
    hub.storage.setItem = (key, value) => {
      if (!failed && key === 'stcloud:auth') { failed = true; throw new Error('quota exceeded'); }
      originalSet(key, value);
    };
    await a.auth.refreshSession();
    const aBefore = a.auth.getCurrentAuth().refreshToken;
    let bResult;
    try { await b.auth.refreshSession(); bResult = 'success'; } catch { bResult = 'rejected'; }
    hub.flushEvents();
    console.log(JSON.stringify({ mode, aBefore, bResult, aAfter: a.auth.getCurrentAuth().refreshToken, shared: hub.storage.getItem('stcloud:auth') }));
  }
})();
```

该复现证明“单次共享 pair 写失败 + 下一页先续期”的行为；没有声称在真实浏览器上人为制造了磁盘故障。临时写失败发生概率未测量。

## 决策与 State Delta（仅 proposal）

建议 `SECURITY_REVIEW / fail`，validatedRevision=`review-fixes-code-r1`，by=`/root/review_fixes_security`。正常凭据/身份竞态及本轮回收权限静态复核未发现其他阻断问题；上述可复现恢复回归需要定向修复。未写 State，未判定整个 Goal。

## 风险、下一步与变更影响

建议在成功轮换尚未共享落盘时，保留可供其它页识别的不确定协调状态，或采用等效机制抑制该窗口下自动拒绝清理；不要改变显式退出或真实无效会话的既有契约。补充双标签写失败回归，在两种协调路径中确认 B 的旧拒绝不使 A 丢失有效 R1，并保留正常永久拒绝的验证。具体实现由主线程决定，新代码需新修订和对应独立复核。

本 child 只新增本文件及独立结果 JSON，未修改产品、测试或 State。剩余验证限制为真实浏览器 UI、MySQL 行锁和实际 S3 删除，本复核不以其它执行者的报告替代这些证据。
