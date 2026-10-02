# 独立安全复核 review-fixes-code-r2

执行者：`/root/review_fixes_security_r2`。Dispatch：`DISPATCH-review-fixes-security-02`。复核完成：2026-10-01 12:22 UTC。

## 背景

复核跨标签凭据完整性、旧响应/退出竞态和墓碑空间回收权限，重点确认 r1 的启动双读及成功 pair 写失败跨页误登出两项可复现问题已关闭。只评价本轮修正，不将工作区既有改动纳入新 finding。

## 输入

Envelope 的字段、身份、scope 和 forbidSpawn 符合当前 Dispatch V2；skillRefs=-。读取本次 TASK/main TASK、design/testcases、最小相关 State、changereport、source-manifest-r2、r2/corrections 及 r1 两份失败报告，沿认证 API/Store 和回收/租户/提交后删除调用链复核。

开始与结束核对清单六个 SHA256，均 6/6 匹配。当前证据绑定 `review-fixes-code-r2`；未运行 Maven，未改源码、测试、State、数据库、Git index 或部署，未派发。

## 分析

事实：启动时 `observedStorage` 绑定 `loadCredentials()` 实际解析的同一次读取（auth-session.ts:24）。随后读取上下文或 storage 事件能够采用启动镜像写入期间出现的新 pair 或退出，不再把尚未采用的新 raw 标记为已观察。

事实：刷新前先持久化按页/会话/修订的无 token marker（auth-session.ts:313）。成功 pair 保存失败时保留 marker，别页旧 refresh 的永久拒绝不能清空成功页；恢复保存后按新修订清理旧 marker。全部协调存储不可写时请求前返回临时错误，保留原 pair，不消费一次性 refresh。普通永久拒绝在无 pending marker 且排他性可证时仍清空会话；显式退出和新登录继续推进 generation，旧成功/拒绝不回写新身份。

事实：完整 pair 仍单次 JSON 保存；迟到事件重读当前存储，同会话低修订不回滚内存。API 401 重放及 Store 用户资料提交沿用上下文校验。协调记录仅含身份、修订和锁信息，没有新增令牌副本。

事实：TeamStorageMapper.lockRecycleSpace 只移除 `deleted=0`，保留 `id + tenant_id + FOR UPDATE`。用户列表和写入口仍通过 findRecycleManagedSpaceIds 限定有效空间/有效拥有者或管理员成员、成员期限和外部协作规则。getAuthorizedRecycleNode 在空间→节点锁后重新授权；墓碑可锁不会授予恢复或永久删除权限。purgeNode 锁后只删除 RECYCLED 根，递归保留租户及团队空间/个人 owner 边界；过期扫描仍由每日逐租户任务调用。物理对象删除继续由已有 AFTER_COMMIT/Outbox 路径处理，事务内未新增外部网络调用。

后端结论来自源码和当前测试断言的独立静态复核，未把主线程的 H2 结果表示为本 child 执行。当前新增测试明确断言墓碑目录混合上传者清理、其它空间保留、最后对象引用释放与一次事件、重复幂等、用户403及正常态节点保留。

## 验证证据

1. 本 child 执行 `node --test st-web/src/store/auth-tabs.test.mjs`：退出0，21/21 通过，失败/取消/跳过均0。测试加载当前 TS 到独立 VM，共享存储/锁/事件受控，续期使用真实本机 HTTP/CAS fixture。
2. 本 child 在 st-desktop 目录用 `node` stdin 独立复放 r1 的两项失败，附加 B 连续拒绝和正常永久拒绝检查：退出0，8个模式/场景断言组通过。未写测试文件；HTTP 响应为内存 CAS fixture，浏览器模型使用当前 harness。

独立复放输出（两种协调模式相同）：

```json
{"mode":"locks","case":"r1-startup-rotation","before":"refresh-1","calls":["refresh-1"],"after":"refresh-2","closed":true}
{"mode":"locks","case":"r1-startup-logout","before":null,"calls":[],"after":null,"closed":true}
{"mode":"locks","case":"r1-pair-write-failure","aBefore":"refresh-1","aAfter":"refresh-1","recovered":"refresh-2","calls":["refresh-0","refresh-0","refresh-0","refresh-1"],"closed":true}
{"mode":"locks","case":"clean-permanent-rejection","memory":null,"shared":null,"markers":0}
{"mode":"tickets","case":"r1-startup-rotation","before":"refresh-1","calls":["refresh-1"],"after":"refresh-2","closed":true}
{"mode":"tickets","case":"r1-startup-logout","before":null,"calls":[],"after":null,"closed":true}
{"mode":"tickets","case":"r1-pair-write-failure","aBefore":"refresh-1","aAfter":"refresh-1","recovered":"refresh-2","calls":["refresh-0","refresh-0","refresh-0","refresh-1"],"closed":true}
{"mode":"tickets","case":"clean-permanent-rejection","memory":null,"shared":null,"markers":0}
```

启动复放在首次读取 R0 的 refresh 镜像写入中注入 R1 或删除共享记录，暂停事件后调用当前读取/刷新。落盘失败复放仅使第一笔完整 pair 写入失败；A 已轮换为 R1，B 两次提交残留 R0 均拒绝，送达事件后 A 仍持有 R1。marker 恰一条且属性集合为 id/revision/serverUrl/sessionId；A 下一次使用 R1 保存 R2 后，B 内存/sessionStorage 同步 R2，marker 清空。正常401的独立对照确认自动拒绝清理未被一概禁用。

## 决策

建议 `SECURITY_REVIEW / pass`。r1 的两项可复现竞态在 r2 均关闭，本轮未发现新增可复现安全或权限回归。

## State Delta（仅 proposal）

id=`SECURITY_REVIEW`，outcome=`pass`，by=`/root/review_fixes_security_r2`，dispatchId=`DISPATCH-review-fixes-security-02`，validatedRevision=`review-fixes-code-r2`，evidenceRef=`.ai/runtime/results/DISPATCH-review-fixes-security-02.json`。未写 State，未判定全局 Goal。

## 风险

事实边界：成功 pair 未保存时只保证本页内存，重新加载仍依赖最后成功共享记录；网络结果不确定时保留 marker，后续可能需要显式登录。协调存储不可写会临时阻止浏览器续期，这是定版 r2 的保守恢复行为。未测试真实浏览器 UI、MySQL 行锁或 S3 物理删除，不声明这些环境已通过。发生频率未知，不将其推测为新增 finding。

## 下一步

主线程核对 attempt、白名单、独立结果及当前 revision 后执行 Evaluate；本 child 不代替 TEST_PASS/ACCEPT 或宣布整个任务完成。

## 变更影响

仅新增本审查文件和本 dispatch 独立 JSON。没有产品、接口、数据模型、授权或部署变更。
