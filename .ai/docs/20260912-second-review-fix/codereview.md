# 第二轮 Review 修复独立代码审查

## 范围与结论

对照基线 `11ef6c2836af548c52052e5860511242741d9d1d`、本轮 `design.md` 和 `testcases.md`，复审 Phase 1～6、100MB 文案、A1 的两项 P1，以及追加的 Desktop 传输任务落库修复。A1 所指的正常失败路径均已定向修复，新增团队 generic 路由门禁也已核实；当前未发现新的 P0/P1，提议 `CODE_REVIEW` 通过。此结论仅针对代码审查，不代表 Phase 7 全部门禁通过：Team 测试仍有基线失败；Desktop 安装包构建已由用户明确交其自行完成，不计入本轮门禁。未改产品代码或 Loop State。

## A1 问题复审

1. **中转超时清理：已修复。** `RelayBufferManager.abortSession` 在 `st-core/src/main/java/com/stcloud/core/service/impl/upload/RelayBufferManager.java:219-255` 仅由 `ACTIVE/FAILED→ABORTED` CAS 胜者调用 S3 abort，然后调用 `UploadManager.cleanupClaimedRelayAbort`。后者在短事务中锁节点，先核对节点仍为 pending 且 `storagePath` 与该会话一致，才回滚节点；分片按该 uploadId 删除。这避免旧会话超时误回滚新会话节点。`RelayUploadIntegrationTest` 的超时、临时写入失败、旧会话/新节点和 MERGING 竞争测试覆盖了节点、分片、状态与 S3 结果，报告为 16/0。

2. **S3 complete 后 DB finalize 失败：已修复单点失败路径。** `UploadServiceImpl.mergeChunksInternal` 在 `UploadServiceImpl.java:491-505` 捕获 finalize 事务异常，调用 `UploadManager.handleFinalizationFailure`；后者在独立短事务中锁节点、回滚仍属于该会话的 pending 节点/分片，并将 `MERGING→ABORTED`。随后按对象引用状态尽力删除已经合并的 S3 对象。`UploadSessionConcurrencyIntegrationTest.finalizeDbFailureClosesNonResumableSessionAndCleansMergedObject` 通过配额不足注入 DB finalize 失败，断言会话 ABORTED、节点/分片清空、S3 complete 一次、对象删除一次且无 S3 abort；该类报告 6/0。

3. **团队 generic 路由：已加门禁。** 普通 check/init/simple 在任何存储副作用前拒绝正数 `spaceId`；普通状态、分片、merge、abort、中转入口在加载会话后拒绝团队会话，团队控制器继续先做团队 ACL，再调用显式 spaceId 的团队 Service 方法。`UploadStateMachineIntegrationTest.genericUploadRejectsTeamScopeBeforeAnyStorageWork` 和 `UploadSessionConcurrencyIntegrationTest.genericUploadEndpointsRejectTeamSessionBeforeSideEffects` 覆盖上述拒绝和无 S3 副作用。

4. **Desktop 传输任务 SQL：已修复。** `st-desktop/src/db/transfer-tasks.ts:60-86` 的 `INSERT INTO transfer_tasks` 列、占位符和值均为 20 项，修正此前 20 列/21 占位符导致创建任务失败。`st-desktop/src/transfer-tasks.test.ts` 使用真实 sql.js 创建上传和下载任务，持久化后从文件重载，并逐字段比较含大整数 ID 的字符串值。此次独立复跑 `npm test` 为 18/0，`npm run lint` 与 `npm run build:main` 均退出码 0。该补丁只修正 Desktop 内部任务持久化，不变更 HTTP 契约、UI 或 schema。

## 已核实的实现与证据

- HASH：Web `file-md5.ts` 按顺序读取全部 Blob 分块；Desktop `calculateFileMd5` 为文件流，上传及同步调用不再使用 sampled hash。Phase 1 结果文件记录跨端 contract 6/0、Desktop 17/0、Web build 通过。
- NAME_SCOPE：`FileNodeMapper.countActiveByScope` 依据 tenant、parent、owner/space、active/deleted 约束查询；个人/团队创建、重命名、移动、复制、上传、空白文件与解压按相应路由调用。回收站公开恢复入口通过个人 `getNodeByIdAndOwner` 限制，团队同名生成器不在该公开入口使用。Phase 2 结果文件记录受影响 H2 测试 55/0。
- UPLOAD_INIT_TX：`UploadInitCommitManager.commitInit` 为独立 `@Transactional` Bean，锁、版本快照、node/session/chunk 同事务；S3 init/abort 均在外层。Phase 3 的第 501 条分片失败注入记录 36/0。
- SESSION_STATE_MACHINE：Mapper CAS 带预期状态，merge/abort 胜者执行 S3；`UploadCommitManager.finalizeMerge` 中会话完成状态与节点、配额同事务。真实 H2 并发测试包含 20 merge、20 abort、merge/abort 对抗与 FAILED 重试；Phase 4 阶段结果见 `testreport.md`，38/0。审查回退修补后，相关四类测试的本地 Surefire XML 分别为 Relay 16/0、SessionConcurrency 6/0、StateMachine 12/0、TransactionBoundary 8/0，合计 42/0；`testreport.md` 另记录最新 st-core 全量 181/0。
- RECYCLE_IDEMPOTENCY：清空/过期根筛选遍历回收祖先；递归删除先检查 `deleteById` 的实际影响行数，再退配额、释放对象引用、发事件。阶段测试 5/0。
- ARCHIVE_INPUT_LIMIT：默认 1GB 且属性可配置；8KB 输入复制在写盘前限额并于失败时删临时 ZIP。阶段测试 4/0。
- `git diff --check` 本次审查重跑退出码 0。Phase 7 报告记录最新 `st-core` 181/0、Web build、Desktop typecheck/main bundle、SchemaConsistencyTest 3/0 和 schema 对比 `PASS (no diff)`；Team 测试仍为 2 失败加 1 错误。Desktop `npm run build` 曾因安装包缓存目录权限失败，用户随后明确本轮跳过安装包并自行打包；本次独立复跑 Desktop `npm test` 18/0、lint 和 build:main 通过。

## 变更边界与风险

本轮产品差异集中在六项修复和 P2 文案；工作区 `.codex` 删除及 `.workbuddy-ai/` 是用户原有变更，不纳入本轮审查结论。历史采样 MD5 未迁移。S3 abort/对象删除仍为事务外 best effort，失败有日志但可能留下外部残留。若 S3 complete 后的补偿 DB 事务也失败，`UploadServiceImpl.java:499-504` 会记录错误并保留 `MERGING`，目前无自动重试；中转 CAS 中止后的 DB 清理若失败，也会记录错误而保留 `ABORTED` 的残留节点/分片。这属于二次失败的运维恢复风险，现有注入测试只覆盖单点 DB finalize 失败。未发现本轮 HTTP 契约、UI 或 schema 变更。

## State Delta proposal

`CODE_REVIEW`: 提议通过（当前代码无新增 P0/P1）；保留上述二次失败风险与 Team 测试的既有失败。用户自行完成 Desktop 安装包构建。子 Agent 不修改 Loop State。
