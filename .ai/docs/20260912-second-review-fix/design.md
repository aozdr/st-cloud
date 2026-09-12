# 程序设计文档

## 一、需求分析

本轮仅实现外部修复计划列出的 P0/P1 与必要回归项，严格按 Phase 1→7 执行。业务 API 结构保持兼容，UI 不改，数据库目标无 migration。

## 二、系统影响分析

| 类型 | 模块 | 是否修改 |
|---|---|---|
| 前端 | `st-web/src/hooks/useUpload.tsx` | 是，仅 hash 实现 |
| 桌面端 | `st-desktop/src/utils/md5.ts`, `src/upload-manager.ts` | 是，仅 hash 调用/contract |
| 后端 | `st-core` File/Upload/Recycle/Archive | 是 |
| 团队 | `st-team` | 仅必要调用/测试，若共享 service 已覆盖则不改 |
| 数据库 | 现有表与 H2/MySQL schema | 默认不改 |
| UI | 页面/样式 | 否 |

## 三、整体设计方案

### Phase 1：HASH

- Web 将 `calculateMd5` 改为分块读取 Blob、按顺序 append 全部 bytes，不追加 size。
- Desktop 上传入口及同步状态的内容 MD5 计算与比较改为 `calculateFileMd5`；`calculateSampledMd5` 不再用于 `fileMd5` 或内容相等判断。
- 增加跨端 contract fixture/单测，覆盖 0B、1KB、5MB、11MB、100MB+ 和相同前缀不同尾部。

### Phase 2：NAME_SCOPE

- 废弃无 scope 的 `countByParentAndName` 调用路径，新增明确参数的 active scoped query。
- personal 调用传 ownerId、spaceId null/非团队；team 调用传 spaceId。
- 统一覆盖 createFolder、rename、move、copy、resolveNameConflict、upload check/init/simple、restore、archive extract、team create/upload。
- 保留数据库唯一索引作为最终兜底；不自动修复既有重复数据。

### Phase 3：UPLOAD_INIT_TX

- 新增独立 `UploadInitCommitManager` Bean，暴露 `@Transactional commitInit(...)`。
- UploadService 先完成参数、scope、配额/容量校验并调用 S3 init；S3 返回后调用 manager。
- manager 内按顺序执行 replacement node `selectByIdForUpdate`、scope/status/type 二次校验、当前 version snapshot、node insert/update、session insert、chunk batch insert。
- 任一 DB 异常回滚全部 DB 记录；外层 catch 调用 S3 abort best-effort 并记录失败。
- 不使用 `REQUIRES_NEW`，不在事务内调用 S3。

### Phase 4：SESSION_STATE_MACHINE

- UploadSessionMapper 新增 `transitionStatus(id, expectedStatuses, targetStatus)`，SQL 带 `status IN (...)`，调用方要求 affected rows 为 1。
- merge：owned session → `ACTIVE/FAILED -> MERGING` → claim node → S3 complete → 短事务 finalize → `MERGING -> COMPLETED`。
- S3 complete 失败：`MERGING -> FAILED`，node 恢复为 FAILED/既有可重试状态，记录补偿失败。
- abort：仅 `ACTIVE/FAILED -> ABORTED`；若 `MERGING` 返回 409/CONFLICT；只有 CAS 获胜者 cleanup。
- relay/timeout/expiry 使用同一 session 状态门禁，不允许普通 `updateById` 绕过状态机。

### Phase 5：RECYCLE_IDEMPOTENCY

- `emptyRecycleBin` 改为使用通用 `findRecycleRoots` 语义：RECYCLED 且无任意层级的 RECYCLED ancestor；不能只排除直接回收的父节点。
- `permanentDeleteNodeAndChildren` 在 quota/object/ref side effect 前以有效 delete/update affected rows 作为首次删除依据；重复调用跳过 side effect。
- 维持事务、审计、索引/outbox 和权限校验，不删除存量数据之外的记录。

### Phase 6：ARCHIVE_INPUT_LIMIT

- `ArchiveSafetyProperties` 增加 `maxArchiveInputSize`，默认 1GB，并支持 `stcloud.archive.max-archive-input-size`。
- `downloadZipToTemp` 使用 8KB/固定 buffer 循环读取并累计字节，超过上限立即抛 `FILE_TOO_LARGE`。
- 所有异常路径删除临时 ZIP；只有 copy 成功才调用 `summarizeArchive`。

### Phase 7：回归与文档

- 每个 Phase 完成后运行其最小测试，再进入下一个 Phase。
- 最后运行计划第 9 节所有门禁，并更新 changereport/testreport/knowledge 证据。

## 四、前端设计

- 页面设计：不变。
- 组件设计：不变。
- 状态设计：不变；仅 hash 阶段耗时可能增加。
- UI 规范：沿用现有错误展示，不新增视觉。

## 五、后端设计

### 内部接口

```java
int countActiveByScope(Long tenantId, Long parentId, Long ownerId, Long spaceId, String name);
int transitionStatus(Long id, Collection<Integer> expectedStatuses, Integer targetStatus);
UploadInitDbResult commitInit(UploadInitDbCommand command);
List<FileNode> findRecycleRoots(Long tenantId, Long ownerId, Long spaceId);
```

以上为内部 Mapper/Bean 能力，不改变现有公开 HTTP API。具体命名按当前代码风格落地，避免重复抽象。

### 事务与 side effect

```text
precheck
  → S3 init（无 DB 事务）
  → UploadInitCommitManager.commitInit（短 DB 事务）
  → DB 失败时 S3 abort（事务外、best effort）

merge session CAS
  → S3 complete（无 DB 事务）
  → finalize DB（短事务）
```

上传、删除、Archive 路径的 quota/object/index side effect 继续遵守现有 outbox/after-commit 约束；补偿失败必须日志可检索。

## 六、数据库设计

- 目标：不新增 migration，不改表结构。
- 复用 `file_node` 的 tenant/owner/space/parent/status/deleted、`upload_session.status`、现有 chunk/version 表。
- 只新增 SQL 条件更新/查询，不改变唯一索引。
- 完成后仍运行 SchemaConsistencyTest 和 `.ai/scripts/compare-schema.ps1`，记录 no schema drift。

## 七、安全设计

- 每个查询仍带 tenant；个人带 owner，团队带 route space。
- Session 查询仍验证当前用户/tenant/space；客户端 S3 ID 不替代服务端 session。
- 完整 MD5 只用于内容指纹语义，不作为权限凭据。
- Archive 输入、条目和解压总量限制同时保留。

## 八、性能设计

- 客户端 hash 流式读取，固定内存 buffer。
- scoped name 查询使用现有 scope 索引/唯一约束条件，避免全表业务过滤。
- DB 事务不包网络 IO；chunk 继续批量写入。
- Archive 在临时盘写入前限制总字节，避免先写满再检查。

## 九、开发计划

```text
Task 1 / Phase 1：Web/Desktop 完整 MD5 + contract tests
Task 2 / Phase 2：name scope 查询与调用链 + scope tests
Task 3 / Phase 3：UploadInitCommitManager + rollback/abort tests
Task 4 / Phase 4：UploadSession CAS +真实并发 tests
Task 5 / Phase 5：recycle roots + delete idempotency tests
Task 6 / Phase 6：Archive input bound + cleanup tests
Task 7 / Phase 7：串行集成验证、文档和独立 review/test evidence
```

每个任务的实现边界由同一 TASK 约束；不并行修改共享模块，不在未通过当前 Phase 测试前进入下一 Phase。

## 十、遗留问题点（Grill Me 拷打收敛）

无待用户裁决项。用户在当前任务明确要求实施本方案且本轮计划无需再次确认，证据见 `confirmation.md`。实现中若发现必须改公开 API、schema 或 migration，视为范围/方案变化，立即暂停并重新确认，不自行扩大范围。

## 十一、风险分析

| 风险 | 影响 | 解决方案 |
|---|---|---|
| 完整 hash 影响启动延迟 | 用户等待增加 | 流式计算、保持现有并行上传策略 |
| 调用链遗漏 | 跨 scope 仍冲突 | 全仓搜索、逐调用点测试 |
| 事务/代理错误 | 数据半成功 | 独立 Bean + 失败注入集成测试 |
| 并发回归 | 重复 complete/cleanup | 20 并发和 CountDownLatch 真测试 |
| archive 清理异常 | 临时盘残留 | finally 删除 + 日志，测试删除调用 |
