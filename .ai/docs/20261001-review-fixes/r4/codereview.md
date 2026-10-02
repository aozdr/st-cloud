# 独立代码增量复核 review-fixes-code-r4

执行者：/root/review_fixes_code_r4；dispatchId：DISPATCH-review-fixes-code-04。

## 背景与输入

按当前Envelope仅复核r4相对r3的低共享修订分支、拒绝墓碑及新回归。读取当前协议/schema、TASK、design/testcases、最小State、changereport、r4 corrections/manifest与r2/r3旧报告。Envelope身份、必填字段和scope有效，skillRefs=-；未写State、运行Maven或派发。

事实：开始及结束核对r4 manifest六个SHA256，均匹配。相对r3仅auth-session.ts与auth-tabs.test.mjs的SHA变化；harness及core三项未变，旧报告仅用于定位已报告根因及核对未变来源。

## 分析

事实：独立执行`node --test st-web/src/store/auth-tabs.test.mjs`，退出0，27/27通过，失败/跳过0。两模式启动快照、成功pair写失败后的另一页旧拒绝、marker扫描期间换账号、普通永久拒绝与新增T12均通过；r1/r2已报告真实失败没有重现。

事实：st-web/src/auth-session.ts:90–95仅在本页当前修订未被拒绝时保护较新内存；rejectedCurrent为真时转为空会话。第30–32行拒绝墓碑匹配同server/session的对应及更早修订，启动恢复和跨页同步共用此判断。更高修订与其它身份不匹配。

另以只读Node stdin加载当前原始TS，使用真实127.0.0.1 HTTP、当前认证Store和API拦截器复核r3原场景：R0成功轮换R1，随后业务`/api/files`真实401触发`/api/auth/refresh`对R1的真实401。分别运行两种锁模式与pair写失败/无失败对照，断言全部通过，退出0：

| 模式 | R1 pair写失败 | refresh调用 | 本页/另一页/重载refresh | 登录状态/用户 | 旧context仍有效 |
| --- | --- | --- | --- | --- | --- |
| locks | 否 | R0、R1 | 全部null | false、null | false |
| locks | 是 | R0、R1 | 全部null | false、null | false |
| tickets | 否 | R0、R1 | 全部null | false、null | false |
| tickets | 是 | R0、R1 | 全部null | false、null | false |

pair写失败组共享仍R0，R1墓碑成功持久化后本页sessionStorage清空，用户资料失效；另一页和新加载页均不再接受R0。拒绝marker只含id/server/session/revision，没有token字段。另三项只读内存存储对照确认：同会话R2、不同session的R0、不同server墓碑均保留合法pair。因此r3唯一P2关闭，未发现本轮新增可复现问题。

事实：core三项SHA与r3一致；依r3已完成的只读调用链复核，墓碑锁、有效空间/成员授权、RECYCLED门禁与提交后删除事件本次无增量。没有把主线程26项H2计作本child独立执行证据。

## 决策与State Delta（仅proposal）

建议CODE_REVIEW/pass；by=/root/review_fixes_code_r4；dispatchId=DISPATCH-review-fixes-code-04；validatedRevision=review-fixes-code-r4；evidenceRef=.ai/runtime/results/DISPATCH-review-fixes-code-04.json。未写State、执行Evaluate或判定Goal。

## 风险与下一步

未测量真实浏览器竞态频率；锁/存储/事件由独立VM模拟，HTTP及Store/API调用真实。未独立运行H2、MySQL、S3或浏览器UI，未重新审计无变更后端。主线程核验独立结果与当前revision并执行Evaluate。

## 变更影响

本child仅新增本文件和独立result；没有修改源码、测试源码、State、数据库、Git index或部署，未运行Maven或创建child。
