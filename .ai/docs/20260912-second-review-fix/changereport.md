# 第二轮 Review 修复变更报告

## 背景与输入

以 `11ef6c2836af548c52052e5860511242741d9d1d` 为基线，按用户已授权的 Phase 1→7 顺序修复六项 P0/P1。未改公开 HTTP 契约、UI、表结构和 migration；未清理存量数据，也未拆分 `FileServiceImpl`。保留工作区原有 `.codex` 文件删除、`.workbuddy-ai/` 及本轮初始草稿。

## 变更与影响

1. **HASH**：Web 上传和 Desktop 上传、同步状态比较均使用全文件 MD5；新增跨端 hash contract，覆盖大于 100MB 文件和相同前缀、不同尾部。
2. **NAME_SCOPE**：同名内部查询按个人 `tenant+owner+parent` 或团队 `tenant+space+parent`；文件创建/重命名/移动/复制、上传、恢复、空白文件和解压沿用相应路由；原数据库唯一约束保留。
3. **UPLOAD_INIT_TX**：独立 `UploadInitCommitManager` 将行锁、旧版本快照、节点、会话和分片写入一个事务；S3 初始化和失败后的 abort 在事务外。第 501 个分片失败注入验证整个 DB 写入回滚。
4. **SESSION_STATE_MACHINE**：`UploadSessionMapper.transitionStatus` 以预期状态 CAS 认领 merge/abort/过期；仅胜者执行 S3 complete/abort。中转缓冲超时/错误清理走同一门禁，胜者在短事务内回滚原会话节点/分片；锁定节点并核对 storagePath，避免旧会话回滚新会话。`MERGING` 不清理其缓冲。合并 DB finalize 与 `MERGING→COMPLETED` 在一个短事务内；失败可续传的新上传走 `MERGING→FAILED→MERGING`。S3 complete 已成功而 DB finalize 失败时，另起短事务回滚节点/分片并转为 `ABORTED`，事务外清理无引用合并对象。普通上传入口拒绝团队会话，团队入口继续执行 ACL。
5. **RECYCLE_IDEMPOTENCY**：清空与过期根筛选遍历全部祖先；递归永久删除先检查节点实际删除影响行数，再进行配额退还、对象引用与事件副作用。重复 ID 或父子一起输入不重复处理。
6. **ARCHIVE_INPUT_LIMIT**：`ArchiveSafetyProperties.maxArchiveInputSize` 默认 1GB，可用 `stcloud.archive.max-archive-input-size` 覆盖；下载 ZIP 时流式计数，写盘前阻断超限并删除临时文件。
7. **P2 文案**：simpleUpload 超限提示由错误的 5MB 修正为实际 100MB。

主要文件：`st-web/src/lib/file-md5.ts`、`st-desktop/src/utils/md5.ts` 与三处上传/同步调用、`st-core/src/main/java/com/stcloud/core/mapper/{FileNodeMapper,UploadSessionMapper}.java`、`service/impl/{FileServiceImpl,UploadServiceImpl,RecycleBinServiceImpl,ArchiveServiceImpl}.java`、`service/impl/upload/{UploadInitCommitManager,UploadManager,UploadCommitManager,UploadStorageManager,RelayBufferManager}.java` 及对应 H2/并发测试。未改 `st-team` 产品代码。

## 状态迁移

```text
UploadSession: ACTIVE/FAILED --CAS--> MERGING --同一 DB 事务--> COMPLETED
               ACTIVE/FAILED --CAS--> ABORTED 或 EXPIRED
               MERGING --合并失败--> FAILED（可续传的新上传）
               MERGING --S3 已完成但 DB finalize 失败--> ABORTED（不可重试）
FileNode:      UPLOADING --认领--> MERGING --DB finalize--> COMPLETED
               MERGING --失败--> FAILED（替换上传恢复旧版本）
```

`MERGING` 时并发 abort 返回 `CONFLICT`。替换上传若 S3 multipart 已中止，恢复原文件后必须重新初始化上传；原会话重试仅适用于仍可续传的新上传。

## 验证与遗留风险

真实命令、通过数和失败数见 [testreport.md](testreport.md)。`st-core` 全量 181/0，Web build、Desktop typecheck/main bundle、SchemaConsistencyTest 3/0、schema 对比 `PASS (no diff)`、`git diff --check` 均通过。此前最终测试门禁未全绿：Team 测试有基线 `Long`/`String` setter 不匹配（2 失败、1 错误）；Desktop 安装包曾被系统 Electron 缓存权限阻断，用户已明确允许跳过并自行打包。用户随后授权修正 Team 测试夹具，最新 Team 36/0，含依赖模块共 256/0；原有测试阻塞已解除。

历史采样 MD5 不自动迁移，旧同步状态首次刷新前可能与新完整 MD5 不一致。S3 abort/对象删除是事务外尽力补偿，失败有可检索日志，但外部残留仍需既有清理机制处理。中转 CAS 已转 `ABORTED` 后如短事务 DB 清理再次失败，目前只有日志、没有自动重试。ZIP 内容列表入口仍直接流式读取，不受本次针对解压下载的 1GB 输入上限约束；其展开大小限额保持原状。

## 用户追加修复与下一步

Desktop 上传和下载任务创建共用 INSERT 多写一个占位符，已修正；新增真实 SQLite 写入、持久化读回回归，Desktop 测试 18/0、类型检查和主进程构建通过。影响限于任务创建 SQL、测试及测试入口，不改 schema。运行中的 Electron 需重启以加载新主进程 bundle；完整服务端上传/下载链路仍需实际环境复测。

## Team 测试授权修正

仅修改 TeamServicePermissionIntegrationTest 请求辅助方法及调用参数，使 subjectId 与 DTO String 类型一致；保留实体 Long 类型与权限断言。mvn -pl st-team -am test 共 256 项通过、0 失败/错误，git diff --check 通过。无生产代码、公开 API 或 schema 影响。
