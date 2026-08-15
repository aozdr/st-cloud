# TASK-01：上传链路修复（C1/M1/M2/M3/M6）

## 元信息
- Task ID: `TASK-20260813-code-review-rework-01`
- 关联 State: `.ai/state/20260813-code-review-rework.yaml`
- 关联文档: `.ai/docs/20260813-code-review-rework/design.md`、`testcases.md`、`architecture-review.md`
- 归属 Agent: backend-engineer
- 创建者: workflow-manager
- 日期: 2026-08-13

## 目标

修复上传链路的 5 项 Code Review blocker：
- **C1**：file_object 唯一键与逻辑删除冲突，同 md5 重传 NPE/去重失效。
- **M1**：mergeChunks 缺 owner/租户管理员权限校验。
- **M2**：S3 complete 先于配额扣减，失败后不可恢复。
- **M3**：relayFinalize 自调用 @Transactional mergeChunks 绕过事务代理。
- **M6**：CloudCapacity FOR UPDATE 行锁跨 S3 I/O 串行化上传。

## 修改范围（include）

- `st-core/src/main/java/com/stcloud/core/mapper/FileObjectMapper.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/FileObjectServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/UploadServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/CloudStorageServiceImpl.java`
- 对应测试：`st-core/src/test/java/com/stcloud/core/service/impl/` 下新增/修改测试（FileObjectIntegrationTest 或新增 C1/M2/M3/M6 用例）

## 禁止修改（exclude）

- st-sync / st-web / st-desktop / st-team / docker 目录
- `.ai/` 流程文档（只读）
- 不允许改动 FileServiceImpl、FolderPermissionService 等未授权文件
- 不提交 git

## 实施要求（决策完备，无需再问）

### C1 去重复活（FileObjectMapper + FileObjectServiceImpl）
1. `FileObjectMapper` 新增：
   - `@Select("SELECT * FROM file_object WHERE tenant_id = #{tenantId} AND md5 = #{md5} AND deleted = 1 LIMIT 1") FileObject selectDeletedByTenantAndMd5(@Param("tenantId") Long tenantId, @Param("md5") String md5);`
   - `@Update("UPDATE file_object SET deleted = 0, status = 0, ref_count = 1, size = #{size}, storage_path = #{storagePath}, updated_at = NOW() WHERE tenant_id = #{tenantId} AND md5 = #{md5} AND deleted = 1") int reviveDeleted(@Param("tenantId") Long tenantId, @Param("md5") String md5, @Param("size") long size, @Param("storagePath") String storagePath);`
2. `FileObjectServiceImpl.acquire` 冲突分支改为：
   - insertIgnore 返回 0 且 active 查询为空 → 查 deleted 行；存在则 `reviveDeleted(...)` 并返回复活后的行（重新 select 或直接构造）；复活影响行数为 0 则抛 `BusinessException(ResultCode.FILE_UPLOAD_FAILED)`（不得返回 null 让调用方 NPE）。
   - 保留并发首传兜底：insertIgnore=0 时先查 active，命中则 incrementRefCount 返回。
3. 保持唯一键 `(tenant_id, md5)` 不变；不新增表字段。

### M1 mergeChunks 权限（UploadServiceImpl）
`mergeChunks` 读取 node 后（COMPLETED 幂等判断之前）插入：
```java
Long userId = UserContext.getUserId();
if (!userId.equals(node.getOwnerId()) && !UserContext.canAccessTenant()) {
    throw new BusinessException(ResultCode.PERMISSION_DENIED);
}
```

### M2 配额顺序（UploadServiceImpl.mergeChunks）
调整事务内顺序为：权限校验 → claimMerging → **配额预检/预扣** → completeMultipart → 去重归属 → markCompleted → 事件。
- 在 completeMultipart 之前调用 `uploadManager.checkQuotaForUpload(...)` 与 `uploadManager.consumeQuota(ownerId, spaceId, delta)`（delta 计算逻辑不变：newSize - original）。
- completeMultipart 抛错分支保持既有 abort + handleMergeFailure；此时配额扣减随事务回滚。
- 移除末尾重复的 consumeQuota 调用。

### M3 relayFinalize 事务代理（UploadServiceImpl）
- 类内新增 `@Lazy @Resource private UploadService self;`（spring-context 已提供 @Lazy，无需新增依赖）。
- `relayFinalize` 内 `mergeChunks(mergeRequest)` 改为 `self.mergeChunks(mergeRequest)`，确保走 Spring 代理使 @Transactional 生效。
- 注意避免循环依赖：@Lazy 注解必须保留。

### M6 容量锁解耦（CloudStorageServiceImpl）
- `checkCapacity` 正常路径改用 `cloudCapacityMapper.getCloudTotalCapacity(tenantId)`（无锁读，方法已存在）+ `sumCloudStorageUsed` 校验。
- 仅当剩余容量低于阈值时（`remaining < Math.max(total * 0.1, delta * 2)` 且 `total != null && total > 0`）调用 `getCloudTotalCapacityForUpdate` 复核一次，超限抛 `CLOUD_CAPACITY_EXCEEDED`。
- `validateQuotaAssignment` 保持 FOR UPDATE 不变（管理操作低频）。

## 验收标准

- acquire 对 deleted 行复活成功；重传不 NPE；去重仍有效。
- mergeChunks 非 owner/非租户管理员被拒。
- merge 配额预扣在 S3 前完成，S3 失败事务回滚且走 abort。
- relayFinalize 经代理调用 merge（可用测试断言事务边界或 Mock 代理调用）。
- checkCapacity 正常路径不调用 FOR UPDATE（Mock 验证），接近阈值时复核。

## 验证命令（必须真实执行并记录结果）

```text
mvn -pl st-core -am test -Dtest='FileObjectIntegrationTest,UploadStateMachineIntegrationTest,QuotaConcurrencyIntegrationTest,ConcurrentUploadIntegrationTest' 
```
随后 `mvn test`（全量，由 Workflow Manager 在 TEST_PASS 阶段统一执行，本任务至少完成相关模块测试）。

## 输出要求

按 Agent 输出规范回复（背景/输入/分析/决策/State Delta/风险/下一步/变更影响），并追加 `.ai/docs/20260813-code-review-rework/changereport.md` 的 TASK-01 章节。
