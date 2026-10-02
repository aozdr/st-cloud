# 独立代码复核 review-fixes-code-r2

执行者：/root/review_fixes_code_r2；dispatchId：DISPATCH-review-fixes-code-02。

## 背景

复核浏览器多标签续期与软删除团队空间回收清理，只评价本轮修复承诺及当前实现。未修改产品或测试源码，不把工作区其它既有改动计入本轮问题。

## 输入

本次 Envelope 字段、身份、scope 和禁止项符合当前协议；skillRefs=-。已读 TASK、design/testcases、相关最小 State、changereport、r2 corrections、r1 两份独立复核及当前调用链。开始与结束各核对一次 source-manifest-r2.json：六个 SHA256 全部匹配，validatedRevision=review-fixes-code-r2。

## 分析

事实：独立运行 `node --test st-web/src/store/auth-tabs.test.mjs`，退出码0，21/21通过，失败/跳过0。用例15/16/18/19确认两种锁模式的启动续期/退出交错已修正；17/20确认成功 pair 写入失败后另一页旧拒绝不会清空成功页内存，保存恢复后共享最新 pair 并清理无 token marker。r1 两个已报告根因均关闭。

事实：另以 Node stdin 分别确认 locks/tickets 正常401仍清空当前无效会话及 marker，未把不确定故障的保守恢复行为作为问题。

### [P2] 拒绝清理检查途中完成的新登录仍被旧会话删除

位置：st-web/src/auth-session.ts:336–341、368–370，业务码拒绝分支352–354具有相同窗口；实际删除在146–152及persist。

事实：旧请求的401在365–366行检查当前会话后，`mayClear()` 还要读取协调票据与全部 rotation marker。另一浏览器进程可在这些 localStorage 原子读取之间完成新账号登录。marker判断只比较submitted旧session，不复核当前共享pair身份；随后`beginAuthSession()`使用旧页内存无条件删除共享记录。该动作会删除已完成的新登录，传播后两页均退出。Web Locks和bakery仅协调refresh，B的新登录不会等待它们。

可复现顺序：

1. A/B共享old-session与old-refresh；暂停storage事件，模拟真实异步派发。
2. A请求真实本机HTTP `/api/auth/refresh`，服务端收到old-refresh并返回401。
3. A进入mayClear，读到自己的rotation marker。在这次读取后、继续计算前注入另一进程已完成登录：B调用公开beginAuthSession/saveLoginCredentials，成功写入new-session/new-refresh。
4. A继续marker判断，结果允许清理；beginAuthSession删除B的新pair。派发排队事件后A/B均无refresh。

注入使用当前auth-test-harness加载原始TS，不改源码；每种模式的HTTP服务监听127.0.0.1随机端口，返回真实401。注入核心如下，get为A原getItem，B为独立VM：

```js
a.localStorage.getItem = (key) => {
  const raw = get(key);
  if (!injected && key.startsWith('stcloud:auth:rotation:') && raw !== null) {
    injected = true;
    const context = b.auth.beginAuthSession();
    login = b.auth.saveLoginCredentials({ token: 'new-access', refreshToken: 'new-refresh' }, context);
    newPairBeforeClear = JSON.parse(hub.storage.getItem('stcloud:auth'));
  }
  return raw;
};
await assert.rejects(a.auth.refreshSession());
await login;
hub.flushEvents();
assert.equal(newPairBeforeClear.refreshToken, 'new-refresh');
assert.equal(hub.storage.getItem('stcloud:auth'), null);
assert.equal(a.auth.getCurrentAuth().refreshToken, null);
assert.equal(b.auth.getCurrentAuth().refreshToken, null);
```

真实执行退出码0，断言确认问题存在，原输出：

```json
{"mode":"locks","httpStatus":401,"calls":["old-refresh"],"newPairBeforeClear":"new-refresh","sharedAfter":null,"aAfter":null,"bAfter":null}
{"mode":"tickets","httpStatus":401,"calls":["old-refresh"],"newPairBeforeClear":"new-refresh","sharedAfter":null,"aAfter":null,"bAfter":null}
```

影响：换账号/重新登录响应与旧401清理检查重叠时，刚成功登录的用户被误登出，违反A2的旧会话拒绝不能清理新登录。21项回归的换账号用例在旧响应释放前已完成新登录，未覆盖marker扫描与删除之间的交错。建议清理针对已提交session执行，并让新登录发布与旧拒绝清理的所有权检查/删除形成一致的协调关系；补上上述两种模式回归。仅增加较早的身份读取不能证明后续删除仍属于旧会话。

事实：回收调用链仍为租户扫描→保留期过期根→purgeNode→空间/节点锁→RECYCLED状态检查→同租户/同空间子树删除→对象引用释放→提交后事件。墓碑锁保留id/tenant_id；用户入口仍通过有效空间/成员查询授权。新增测试检查混合上传者、其它空间、正常节点、引用释放及重复幂等。本轮回收补丁未发现新增可复现问题。

推测与限制：没有测量真实浏览器中上述窄窗口的发生频率；跨进程合法交错以独立VM受控存储注入，HTTP真实。未独立运行H2/MySQL/S3或真实UI；不把其它执行者报告当成本child执行证据。全部协调存储不可写时请求前临时失败、成功pair未保存时保留内存/marker，均符合已给定恢复边界。

## 决策

CODE_REVIEW建议fail。r1两项根因已关闭，但当前r2的旧拒绝清理仍可删除另一页刚保存的新登录；没有追加不相关故障可用性要求。

## State Delta（仅 proposal）

CODE_REVIEW / fail；by=/root/review_fixes_code_r2；dispatchId=DISPATCH-review-fixes-code-02；validatedRevision=review-fixes-code-r2；evidenceRef=.ai/runtime/results/DISPATCH-review-fixes-code-02.json。未写State，未判定整个Goal。

## 风险

接受当前修订仍可能在新登录与旧拒绝清理交错时误登出。未观察到凭据泄漏、跨租户删除或新增事务内网络调用。

## 下一步

主线程定向修正拒绝清理与新登录发布的交错，补回归并冻结新修订，再派发独立复核。

## 变更影响

本child仅新增本文件与自己的独立result；没有修改源码、测试源码、State、数据库、Git index、部署，未运行Maven或派发。
