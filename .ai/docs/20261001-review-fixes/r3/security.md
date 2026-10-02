# 独立安全复核 review-fixes-code-r3

执行者：`/root/review_fixes_security_r3`。Dispatch：`DISPATCH-review-fixes-security-03`。完成时间：2026-10-01 12:35 UTC。

## 背景与输入

复核跨标签凭据完整性、旧响应/退出交错和墓碑空间的租户、成员权限，确认 r1/r2 已复现的三条竞态已关闭。Envelope 的字段、身份、角色、写白名单和 forbidSpawn 符合 Dispatch V2，skillRefs 为 `-`。输入为本次 TASK/main TASK、定版 design/testcases、相关 State 修订/标准、changereport、source-manifest-r3、r3/corrections、六个当前文件及本轮 r1/r2 失败证据。

开始和结束各核对一次 SHA256，均六项匹配 r3 清单。后端三项 SHA256 与本轮 r1 清单相同。只新增本报告和独立结果；未修改源码、测试、State、数据库、Git index 或部署，未运行 Maven、未派发。

## 分析与事实

认证完整 pair 仍由一个 JSON 记录保存。初始化 `observedStorage` 使用实际采用的同一次读取（auth-session.ts:35）；同步重新读当前存储，低修订不回滚。会话变化推进 generation，使旧结果不能提交新身份。

刷新前写入不含 token 的轮换 marker（361–370）；成功 pair 保存失败仍保留 marker，别页提交残留 R0 的重复拒绝不能清空 A 的有效内存 R1，恢复保存 R2 后能够共享。协调存储完全不可写时不消费 refresh。

r3 自动拒绝改为按 server/session/revision 写拒绝标记（29、292），不删除完整共享 pair。即使 B 在 marker 扫描或旧拒绝标记写入期间完成新登录，旧请求也不会使 B 的新 pair 失效。普通永久拒绝仍清空对应旧修订的内存/sessionStorage；启动恢复检查该标记，不能恢复留在共享存储中的已拒绝记录。显式退出继续使用 beginAuthSession，传播行为保持。

墓碑锁查询保留 `id + tenant_id + FOR UPDATE`（TeamStorageMapper.java:19），用户授权查询仍限制有效空间、有效拥有者/管理员成员、成员期限及外部协作配置（24–32）。用户入口在空间→节点锁后再次核对租户和成员权限（RecycleBinServiceImpl.java:280–304）。系统 purge 锁后只处理 RECYCLED 根（321–328），递归保留 tenant 和 space/个人 owner 边界（161–175），物理删除仍通过既有事件路径（183–189）。墓碑可锁不等于用户可恢复或永久删除。

新增后端断言明确覆盖混合上传者目录清理、其他空间保留、对象引用归零且仅一次事件、重复 purge 幂等、墓碑用户403和正常节点保留（TeamRecycleBinIntegrationTest.java:208–257）。本轮26项XML计数与主线程证据一致；这是已有当前任务执行证据的独立核对，不是本 child 重新执行 H2。

## 验证证据

本 child 执行 `node --test st-web/src/store/auth-tabs.test.mjs`：退出0，25/25通过，失败/取消/跳过均0。测试用当前源码的独立 VM、共享存储/事件/锁模拟与真实本机 HTTP CAS 服务。

另在 `st-desktop` 目录通过 `node` stdin 独立执行12组断言，退出0。夹具为真实本机 HTTP/CAS、当前 harness、内存存储，无文件或云端数据写入。两种模式各执行下列6组：

| 场景 | 独立复放方法与实际结果 |
| --- | --- |
| r1 启动续期 | 首次 refresh 镜像写入期间注入共享R1；新页采用R1，只提交refresh-1，恢复R2 |
| r1 启动退出 | 同一窗口删除共享pair/镜像；新页内存和sessionStorage清空，没有HTTP调用 |
| r1 pair写失败 | 只让A第一次pair写入失败；B连续两次提交残留R0被401拒绝；A仍为R1，下一次用R1保存R2，B采用R2 |
| r2 marker扫描换账号 | 旧HTTP401后的rotation marker读取中完成B新登录，暂停storage事件；A/B/新加载页均采用new-refresh，共享pair保留 |
| 拒绝标记写入换账号 | 在旧rejected marker写入前完成B新登录；A/B/新加载页仍采用new-refresh，共享pair保留 |
| 普通永久拒绝对照 | 401使A/B/重载页均未登录；旧共享pair留存但revision 0有匹配拒绝标记，refresh镜像为空，不能恢复旧pair |

每种模式的写失败HTTP序列为 `[refresh-0, refresh-0, refresh-0, refresh-1]`；两项换账号交错各仅提交 `[refresh-0]`。协调marker恰一条且没有 token/refreshToken 属性。逐组实际输出保存在独立结果的 validation 中。

## 决策与 State Delta（仅 proposal）

建议 `SECURITY_REVIEW / pass`，by=`/root/review_fixes_security_r3`，dispatchId=`DISPATCH-review-fixes-security-03`，validatedRevision=`review-fixes-code-r3`，evidenceRef=`.ai/runtime/results/DISPATCH-review-fixes-security-03.json`。r1/r2 的已复现竞态在当前修订关闭，本轮未发现可复现新增凭据或权限回归。未写 State，未判定整个 Goal。

## 风险、下一步与变更影响

事实边界：本 child 未运行真实浏览器UI、MySQL/S3或Maven；后端动态证据由主线程本轮26项报告及未变化的core三SHA支撑。持久化失败后重载仍依赖最后成功写入，符合当前设计记载。没有测量真实环境故障概率，不据此推断上线表现。

下一步由主线程核对独立结果与当前修订后 Evaluate；其他标准不由本 child 判定。本 child 的变更影响限上述两份审查产物。
