# 独立代码复核 review-fixes-code-r1

执行者：/root/review_fixes_code；dispatchId：DISPATCH-review-fixes-code-01。

## 背景

本轮修复浏览器多标签续期误登出与软删除团队空间的过期回收清理。审查只评价本轮补丁及其真实云盘调用链，不将工作区其他既有修改归为本轮问题。

## 输入

已校验本次 Envelope 的必填字段、身份与白名单；skillRefs=-。读取 TASK-20261001-review-fixes-code/main、定版设计与用例、changereport/source-manifest 和相关源码。State 仅读取任务、revision 及 CODE_REVIEW 依赖，未修改。

核对时六个产品/测试文件 SHA256 均与 source-manifest.json 一致（6/6），验证修订为 review-fixes-code-r1。后续新修订不能沿用本结果作为通过证据。

## 分析

### [P2] 启动时将未采用的共享记录标记为已观察，仍会误清有效会话

位置：st-web/src/auth-session.ts:45–47、53。

事实：`loadCredentials()` 先读取并采用 R0，随后第47行再次读取共享记录来初始化 `observedStorage`。另一浏览器页若恰在两次读取之间完成 R0→R1 落盘，启动页就处于“内存 R0 / observedStorage R1”。之后共享存储仍是 R1，`syncBrowserAuth()` 在第53行提前返回，连锁内复核也无法采用 R1。该页提交已消费的 R0，收到 401 后 `beginAuthSession()` 删除有效 R1，造成所有页退出。Web Locks 与 bakery ticket 都只能串行后续请求，不能修正这个错误的启动快照。

同一根因还会遗漏启动窗口中的跨页退出：若第二次读取是 null，随后 storage 事件与上下文读取均提前返回，旧内存 refresh 与 sessionStorage access 留存。

真实复现：只读 Node 使用当前 auth-test-harness 加载原始 TS，两个独立会话模型共享内存 storage；在启动页 `loadCredentials()` 镜像写旧 refreshToken 后，注入另一浏览器进程此刻对共享完整 pair 的提交。续期复现连接真实本机 HTTP 服务，服务端仅接受 refresh-1，旧 refresh-0 返回 HTTP 401。没有改源码、写测试文件、运行 Maven 或访问实际云盘数据。

可复现顺序：

1. 共享 pair 为 `{token: access-0, refreshToken: refresh-0, sessionId: session, revision: 0}`，启动页首次读取它。
2. 在该页 `localStorage.setItem('refreshToken', 'refresh-0')` 后，另一进程把完整 pair 写为 access-1/refresh-1/revision=1；服务端已经接受这次轮换。暂停 storage 事件，符合浏览器异步派发行为。
3. 启动页执行第47行，观察 R1 却保留 R0；调用 `getCurrentAuth()` 仍返回 refresh-0。
4. 调用 `refreshSession()`。两种协调模式分别运行，真实 HTTP 均收到 refresh-0 并返回401；有效共享 pair 被删除。

注入点核心代码（其余使用现有 `sharedStorage` / `loadWeb` / `webLocks`）：

```js
const storage = hub.connect(deliver);
const originalSet = storage.setItem;
storage.setItem = (key, value) => {
  originalSet(key, value);
  if (!injected && key === 'refreshToken') {
    injected = true;
    hub.storage.setItem('stcloud:auth', JSON.stringify(latest));
    hub.storage.setItem('refreshToken', latest.refreshToken);
  }
};
```

两种模式的独立 Node 复现退出0，断言确认问题仍存在；原始输出：

```json
{"mode":"locks","injected":true,"before":"refresh-0","sharedBefore":"refresh-1","calls":["refresh-0"],"rejected":true,"sharedAfter":null,"memoryAfter":null}
{"mode":"tickets","injected":true,"before":"refresh-0","sharedBefore":"refresh-1","calls":["refresh-0"],"rejected":true,"sharedAfter":null,"memoryAfter":null}
```

退出变体在同一注入点删除完整 pair 与旧 refresh 镜像，再手动派发 storage 删除事件，独立 Node 退出0，输出：

```json
{"injected":true,"shared":null,"memoryRefresh":"refresh-0","sessionAccess":"access-0"}
```

建议：让 `observedStorage` 对应实际被 `loadCredentials()` 解析采用的同一原始记录，随后同步最新共享记录；覆盖启动读取途中完成续期与退出两种交错。单纯再读一次，或只将基线设为 undefined，却继续把 null 当作从未存在记录，不能完整覆盖退出变体。

### 其他已核对行为

事实：独立执行 `node --test st-web/src/store/auth-tabs.test.mjs`，退出0，14/14通过、无跳过。已有测试覆盖的是模块加载完成后的并发、迟到事件、换账号、503与落盘失败，没有覆盖上述初始化窗口。

事实：已沿回收调用链核对每日租户扫描 → 过期回收根 → purgeNode → 空间/节点锁 → 删除子树 → 对象 release → PHYSICAL_DELETE。此次 Mapper 只放宽空间墓碑锁，保留租户条件；用户入口仍重新查有效空间/成员，系统入口锁后检查 RECYCLED。已有 AFTER_COMMIT 监听/Outbox 路径承担物理删除，事务内没有新增网络调用。未发现本轮回收补丁新增的可复现用户影响问题。

推测与限制：本审查没有测量真实浏览器在启动窗口中的发生频率；注入的是跨进程合法交错，结论来自执行当前源码的真实结果。未独立运行 H2/MySQL/S3，不将主线程旧报告替代本 child 的独立执行证据。

## 决策

CODE_REVIEW 建议 fail。单一 P2 根因仍违反 A1/A2：启动期间完成的最新续期或退出不能可靠同步。该问题已将位置、顺序、两种模式的实际输出反馈主线程；不新增无关范围。

## State Delta（仅 proposal）

`CODE_REVIEW / fail`，by=/root/review_fixes_code，dispatchId=DISPATCH-review-fixes-code-01，validatedRevision=review-fixes-code-r1，evidenceRef=.ai/runtime/results/DISPATCH-review-fixes-code-01.json。未写 State，未判定全局 Goal。

## 风险

若直接接受当前修订，打开/重载标签页恰遇其他页续期时仍可清空有效登录；恰遇退出时可残留旧认证状态。回收部分的真实 MySQL 行锁与物理删除仍需相应环境证据，不据此增加本轮代码 finding。

## 下一步

主线程修复启动快照一致性，增加两个启动交错回归并冻结新修订后重新派发独立复核。

## 变更影响

本 child 仅新增本文件及自己的独立 result；没有修改源码、测试源码、State、数据库、Git index、构建缓存或部署。
