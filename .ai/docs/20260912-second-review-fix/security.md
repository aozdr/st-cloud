# 第二轮 Review 修复：独立安全审查

## 审查范围与证据

基线为 `11ef6c2836af548c52052e5860511242741d9d1d`。审查本轮 `st-core` 上传、同名 scope、回收站和 ZIP 输入限制的工作区差异；只读检查 `st-team` 对上传的权限门禁。采用 `git diff`、`rg` 和源代码调用链核验；本文不把其他 Agent 的测试报告当作本人已运行的安全测试。

## 发现与修复复核

1. **P1 已修复：中转异常和超时清理曾仅终止会话，遗留 pending 节点与分片。** `RelayBufferManager.appendChunk` 写临时文件失败、`flushPart` 的 I/O 失败及超时扫描都会调用 `abortSession`（`RelayBufferManager.java:116,179,209`）。初次审查时新 CAS 将 ACTIVE/FAILED 变为 ABORTED 后只 abort S3 和删除本地缓冲，pending DB 记录无清理路径。现 `abortSession` 在 CAS 获胜后调用独立事务的 `UploadManager.cleanupClaimedRelayAbort`，锁定 node、核对旧 session 的 `storagePath` 后回滚节点，并只删除旧 `uploadId` 的分片（`RelayBufferManager.java:237-251`；`UploadManager.java:164-174`）。S3 abort 保持在事务外。H2 的超时及关闭缓冲输出流故障测试验证 ABORTED、调用 S3 abort、节点和分片清理；I/O 故障用例额外断言 abort 恰一次（`RelayUploadIntegrationTest.java:352-386`）。

2. **P1 已修复（问题在基线即存在）：普通上传入口曾可操作本人创建的团队会话，绕过团队权限复核。** `TeamUploadController.requireSessionUpload` 通过 `teamService.requirePermissions` 重查团队节点上传权限（`TeamUploadController.java:117-120,162-165`），普通 `FileController` 的 status/merge/abort/chunk/relay 路由只要求通用 `file:upload`（`FileController.java:161-241`）。原 `UploadServiceImpl.requireOwnedSession` 仅绑定 tenant+user，未拒绝 `spaceId>0`；`git show 11ef6c2:.../UploadServiceImpl.java` 也确认 generic merge/abort 调用结构在基线已有。现普通路由在 session 加载后统一拒绝团队 `spaceId`，团队路由显式传入 `teamRoute=true` 并继续绑定路由 `spaceId`（`UploadServiceImpl.java:744-790`）；`genericUploadEndpointsRejectTeamSessionBeforeSideEffects` 覆盖 status、chunk URL/confirm、merge、abort、relay chunk/finalize，且未触发 S3 complete/abort（`UploadSessionConcurrencyIntegrationTest.java:243-259`）。未改 HTTP 契约。

## 已核验的安全边界

- `FileNodeMapper.countActiveByScope` 查询同时约束 `tenant_id`，个人使用 `owner_id` 且排除团队 `space_id`，团队使用指定 `space_id`（`FileNodeMapper.java:35-45`）。上传命名按空间调用 `resolveTeamNameConflict` 或个人版（`UploadServiceImpl.java:809-814`）。同名查询并不替代调用方的父目录 ACL。
- Upload Init 的 S3 `initMultipart` 在 `UploadInitCommitManager.commitInit` 之前，失败后 `abortMultipart` 在外层 catch；事务 Bean 只有 DB 锁、版本、节点、会话、分片操作（`UploadServiceImpl.java:263-296`；`UploadInitCommitManager.java:37-123`）。
- Merge 的会话 `ACTIVE/FAILED→MERGING`、abort 的 `ACTIVE/FAILED→ABORTED` 使用影响行数等于 1 认领；`UploadCommitManager.finalizeMerge` 将节点、配额和 `MERGING→COMPLETED` 放在同一 DB 事务（`UploadServiceImpl.java:458-471,519-525`；`UploadCommitManager.java:154-202`）。`completeMultipart` 在事务外执行。
- S3 complete 已成功而 DB finalize 失败时，另一个短事务锁定原节点、清理旧上传的节点/分片、CAS `MERGING→ABORTED`，外层检查对象引用后尽力删除已合并对象（`UploadServiceImpl.java:495-505,938-948`；`UploadManager.java:177-189`）。H2 故障注入覆盖这一不可续传分支（`UploadSessionConcurrencyIntegrationTest.java:261-276`）。
- 解压路径在写入 ZIP 临时文件前检查累计输入字节，并在失败路径删除临时文件（`ArchiveServiceImpl.java:281-309`）；默认上限 1GB（`ArchiveSafetyProperties.java:12`）。

## 遗留边界

- **P2：列出 ZIP 内容不受新增压缩输入字节上限约束。** `ArchiveServiceImpl.listArchiveContents` 直接从 S3 流读取，受条目数、展开大小等既有限制，却不经过 `downloadZipToTemp` 的 1GB 输入硬限制（`ArchiveServiceImpl.java:62-98,281-309`）。本轮 Phase 6 明确要求的是解压输入临时文件上限；这一接口的残余流量风险需单独评估，不应为关闭本轮计划而扩大代码范围。
- MD5 是内容指纹，不是权限凭据。当前 `requireOwnedSession` 仍基于服务端 session 对 uploadId/S3 uploadId/fileId 做绑定；客户端完整 MD5 修复没有替换服务器授权判断。
- S3 abort/delete 为事务外 best effort；网络补偿失败有日志，仍可能形成待后台清理的 multipart/对象残留。需以现有后台清理证据判断运维收敛性。
- 中转会话已经 CAS 到 ABORTED 后，如独立的节点/分片清理事务失败，当前仅记录错误，没有自动重试该 DB 清理；这属于故障叠加时的 P2 残余孤儿元数据风险（`RelayBufferManager.java:245-250`）。

## 验证证据

主线程运行的定向 Maven 测试结果为 41 通过、0 失败；本人读取的 Surefire 报告中 `RelayUploadIntegrationTest` 为 15/0、`UploadSessionConcurrencyIntegrationTest` 为 6/0。本人另外用 `git diff` 和源代码调用链复核上述修补；未独立重跑 Maven，以避免与主线程共享构建缓存争用。

## 结论

本轮差异中的两项 P1 均已修复并有定向 H2 证据；在上述代码范围内未发现尚未修复的新增 P0/P1。建议 `SECURITY_REVIEW` 以当前工作区 revision 判为通过，同时将 ZIP 列表输入与补偿失败的 P2 边界纳入最终遗留清单。
