# 独立代码复核 review-fixes-code-r3

执行者：/root/review_fixes_code_r3；dispatchId：DISPATCH-review-fixes-code-03。

## 背景与输入

复核本轮多标签续期和软删除空间回收清理。Envelope字段、身份、写入白名单与禁止项符合当前协议，skillRefs=-。读取TASK、design/testcases、r3 corrections、changereport、manifest及相关当前实现；r1/r2报告仅用于核对旧失败。State只读，未执行Evaluate。

开始与结束核对source-manifest-r3.json，六个SHA256均匹配，验证修订为review-fixes-code-r3。

## 分析

事实：独立执行`node --test st-web/src/store/auth-tabs.test.mjs`，退出0，25/25通过，失败/跳过0。启动旋转/退出快照、成功pair写失败与另一页旧拒绝、marker扫描期间新登录及普通拒绝重载对照均通过；r1/r2原报告根因已关闭。

### [P2] 较旧共享修订挡住本页当前修订的永久拒绝

位置：st-web/src/auth-session.ts:88–90；第72行已得到`rejectedCurrent=true`，第92行的拒绝处理却尚未执行就返回。

事实：A完成R0→R1，但完整pair写入单独失败，共享仍为R0；存储恢复后，服务器因会话撤销等原因永久拒绝A的R1。`rejectBrowserRevision`成功写入R1拒绝墓碑。随后`syncBrowserAuth`先比较共享R0与内存R1，直接返回，忽略本页R1已经被拒绝。认证订阅者没有收到失效，旧context依旧有效。

独立只读Node stdin复现使用当前harness原始TS、真实127.0.0.1 HTTP服务及真实认证Store/API拦截器：首次refresh返回完整R1，仅一次`stcloud:auth`写入抛错；恢复写入后`/api/files`与`/api/auth/refresh`均返回HTTP401。业务请求触发R1 refresh拒绝。无写失败对照正确退出；写失败组仍`isAuthenticated=true`且保留用户资料。两种锁模式结果相同，断言全部通过，退出0：

```json
{"mode":"locks","failPair":false,"httpStatus":401,"calls":["refresh-0","refresh-1"],"refreshAfter":null,"isAuthenticated":false,"userAfter":null,"oldContextStillCurrent":false,"rejectedLatest":true}
{"mode":"locks","failPair":true,"httpStatus":401,"calls":["refresh-0","refresh-1"],"refreshAfter":"refresh-1","isAuthenticated":true,"userAfter":"cloud-user","oldContextStillCurrent":true,"rejectedLatest":true}
{"mode":"tickets","failPair":false,"httpStatus":401,"calls":["refresh-0","refresh-1"],"refreshAfter":null,"isAuthenticated":false,"userAfter":null,"oldContextStillCurrent":false,"rejectedLatest":true}
{"mode":"tickets","failPair":true,"httpStatus":401,"calls":["refresh-0","refresh-1"],"refreshAfter":"refresh-1","isAuthenticated":true,"userAfter":"cloud-user","oldContextStillCurrent":true,"rejectedLatest":true}
```

复现关键步骤（`a/hub`使用现有harness；HTTP正常阶段返回R1、revoked阶段返回401）：

```js
const store = a.loadStore();
store.setState({ user: { id: 'cloud-user' }, isAuthenticated: true, authReady: true });
const set = a.localStorage.setItem;
let failOnce = true;
a.localStorage.setItem = (key, value) => {
  if (key === 'stcloud:auth' && failOnce) {
    failOnce = false;
    throw Error('one pair write failure');
  }
  return set(key, value);
};
await a.auth.refreshSession();
const context = a.auth.captureAuthContext();
revoked = true;
await assert.rejects(a.api.get('/files'));
assert.equal(a.auth.getCurrentAuth().refreshToken, 'refresh-1');
assert.equal(store.getState().isAuthenticated, true);
assert.equal(store.getState().user.id, 'cloud-user');
assert.equal(a.auth.isCurrentAuth(context), true);
```

影响：已经永久失效的用户仍停留在已登录状态，业务请求反复401，旧资料与请求上下文没有按契约失效。该场景不同于“另一页拒绝旧R0不能清除有效R1”：此处被服务器拒绝并已标记的正是本页当前R1。建议优先处理本页当前修订墓碑，再决定是否忽略较旧共享pair；仍须保护未被标记的新修订/新登录，并补两模式回归。

事实：回收墓碑锁保留租户条件与FOR UPDATE，用户授权仍要求有效空间/成员，系统锁后只清理RECYCLED，子树删除和引用释放保留提交后事件。已核对三项新增回收用例及本轮26项H2证据，未发现本轮回收新增可复现问题。H2是主线程执行证据，本child未运行Maven。

## 决策与State Delta（仅proposal）

CODE_REVIEW建议fail，唯一新增P2如上。by=/root/review_fixes_code_r3；dispatchId=DISPATCH-review-fixes-code-03；validatedRevision=review-fixes-code-r3；evidenceRef=.ai/runtime/results/DISPATCH-review-fixes-code-03.json。未写State或判定整个Goal。

## 风险与下一步

未测量真实浏览器发生频率；锁/存储/事件为独立VM模拟，HTTP与认证Store/API调用真实。未独立运行H2、MySQL、S3或浏览器UI。主线程定向处理已拒绝当前修订被低共享修订遮挡的分支，增加对照并冻结新修订。

## 变更影响

本child仅新增本文件与独立result，没有修改源码/测试源码/State/数据库/Git index/部署，未运行Maven或派发。
