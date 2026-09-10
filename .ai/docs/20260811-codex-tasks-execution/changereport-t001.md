# Change Report：TASK-001 文件对象模型优化

> 编码完成后的变更汇总。归属 exitCriteria：IMPLEMENTED。产出者：backend-engineer。关联 State：`.ai/state/20260811-codex-tasks-execution.yaml`。

## 背景
原系统以 `file_node.file_md5 / storage_path` 直接存物理对象路径，去重仅靠 `selectByMd5`（全局无租户作用域），秒传需复制物理对象、删除需按 storage_path 判重，扩展性与引用一致性差。TASK-001 引入 `file_object` 对象层，同租户内按 md5 唯一，引用计数归零才删物理对象。

## 修改文件清单
**新增**
- `docker/mysql/init/28_file_object.sql` — 建 `file_object` 表（tenant_id+md5 唯一键、ref_count、status），`file_node` 加 `object_id`，按 `(tenant_id, md5)` 回填已完成文件
- `st-core/src/main/java/com/stcloud/core/entity/FileObject.java` — 文件对象实体
- `st-core/src/main/java/com/stcloud/core/mapper/FileObjectMapper.java` — `selectByTenantAndMd5` / `insertIgnore` / `incrementRefCount` / `decrementRefCount` / `getRefCount` / `markDeleted`
- `st-core/src/main/java/com/stcloud/core/service/FileObjectService.java` — 对象服务接口（acquire/release/deletePhysical/findByTenantAndMd5）
- `st-core/src/main/java/com/stcloud/core/service/impl/FileObjectServiceImpl.java` — 对象服务实现（并发首传 `INSERT IGNORE` + 复用）
- `st-core/src/test/java/com/stcloud/core/service/impl/FileObjectIntegrationTest.java` — 5 个集成用例

**修改**
- `st-core/src/main/java/com/stcloud/core/entity/FileNode.java` — 新增 `objectId` 字段
- `st-core/src/main/java/com/stcloud/core/service/impl/UploadServiceImpl.java` — 秒传/简单上传/mergeChunks 归属对象，替换上传释放旧引用
- `st-core/src/main/java/com/stcloud/core/service/impl/FileServiceImpl.java` — 复制文件走对象引用 +1，旧数据回退 `incrementRefCount`
- `st-core/src/main/java/com/stcloud/core/service/impl/RecycleBinServiceImpl.java` — 永久删除引用归零才删物理对象，旧数据回退 `countOtherRefsByStoragePath`
- `st-core/src/main/java/com/stcloud/core/service/impl/VersionServiceImpl.java` — 版本恢复复用目标对象，旧对象减引用（保留物理对象）
- `st-core/src/test/resources/schema.sql` — 测试库补 `file_object` 表与 `object_id` 列（顺带补锁列修复既有测试套件）

## 与 TASK 验收标准对照
| TASK-001 验收标准 | 实现 | 状态 |
|---|---|---|
| 同租户同 MD5 秒传：仅新增 file_node 引用，不新增 S3 对象 | `checkInstantUpload` 走 `fileObjectService.acquire` 复用对象，取消物理复制 | ✅ |
| 跨租户同 MD5 各自独立 object | 对象唯一键 `(tenant_id, md5)`，存储路径 `tenantId/md5` 归一化 | ✅ |
| 删除最后引用才删 S3 对象；引用未归零不误删 | 永久删除 `release` 归零才 `deletePhysical`；`decrementRefCount` 含 `ref_count > 0` 保护 | ✅ |
| 复制文件/版本恢复引用计数正确 | copy +1、restoreVersion 目标对象 +1 / 旧对象 -1 | ✅ |
| 迁移回填后 ref_count 与实际引用一致 | `28_file_object.sql` 回填 `ref_count=COUNT(*)`，`file_node.object_id` 关联 | ✅ |

## 测试结果
- `mvn test -pl st-core -am`：退出码 0，15 个测试全绿（新增 `FileObjectIntegrationTest` 5 用例 + 既有收藏测试 10 用例）
- 覆盖场景：同租户去重复用、跨租户隔离、`acquire` 仅在对象缺失时上传、引用归零物理删除后不可复用、仅减引用不删对象可复用、`insertIgnore` 并发重复键单行
- 迁移脚本本地执行通过（回填后引用一致）

## 明确未改动项（符合 TASK 禁止范围）
- `DownloadServiceImpl` / `FileIndexEvent` 未改：`file_node.storage_path` 保留，下载/ES 索引继续走原路径，向前兼容
- 前端 `FileNodeVO` 与所有 REST 接口签名未变
- 分片上传链路未重构（属 TASK-002）
- 未引入跨租户共享物理对象

## 风险
- **并发首传竞态**：两请求同 md5 同时首传可能产生一次冗余上传（`INSERT IGNORE` 失败后复用胜出行），物理对象按规范化 key 覆盖，可接受
- **版本历史路径未纳入对象体系**：`restoreVersion` 对无对象的历史版本路径不建对象，物理对象长期保留，可能产生孤儿存储（后续 TASK 可纳入）
- **mergeChunks 去重命中时**尽力删除临时对象，删除失败仅告警，不阻断主流程
- 回填脚本需在存量生产库灰度执行并备份

## 下一步
- 由 Workflow Manager 记录本报告，待全部 6 个 TASK 完成后统一走 CODE_REVIEW / SECURITY_REVIEW / TEST_PASS / QUALITY_GATE
- TASK-002 上传状态机（`UploadStatus` 扩展、UploadServiceImpl 拆分）