# 程序设计文档

## 一、需求分析

### 功能名称

st-core 文件资源安全与一致性整改（CORE-P0-01～P1-08）

### 功能描述

```text
用户：个人用户、团队成员、租户管理员
操作：读取/写入文件，创建目录，上传，版本操作，下载，解压和并发提交
系统行为：先确定 personal/team scope，再由 Service 校验资源归属、父子关系、会话和资源上限；数据库更新成功后才产生副作用
最终结果：个人与团队边界不可绕过，目录结构不混合/不成环，并发和大资源失败可见且不产生部分成功
```

## 二、系统影响分析

| 类型 | 模块 | 是否修改 |
|---|---|---|
| 前端 | st-web 上传 hook、团队上传上下文 | 是 |
| 客户端 | st-desktop upload-manager、任务 metadata | 需要时修改 |
| 后端 | st-core File/Upload/Download/Version/Recycle/Archive | 是 |
| 后端 | st-team TeamUploadController | 新增 |
| 数据库 | MySQL migration、H2 schema | 是 |
| 测试 | 权限、流程、上传、并发、Archive/Download | 是 |

### 影响文件预测

新增文件：

- `st-core/src/main/java/com/stcloud/core/entity/UploadSession.java`
- `st-core/src/main/java/com/stcloud/core/mapper/UploadSessionMapper.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/UploadSessionServiceImpl.java`（或保持在 UploadServiceImpl，按现有依赖最小化）
- `st-core/src/main/java/com/stcloud/core/config/UploadProperties.java`
- `st-core/src/main/java/com/stcloud/core/config/ArchiveSafetyProperties.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/FileNodeMutationGuard.java`
- `st-team/src/main/java/com/stcloud/team/controller/TeamUploadController.java`
- `docker/mysql/init/40_file_scope_constraints.sql`
- `docker/mysql/init/41_upload_session.sql`

修改文件：

- `st-core` 的 FileController、FileService/Impl、FileNodeMapper、UploadService/Impl、UploadChunkManager、VersionService/Impl、DownloadService/Impl、RecycleBinService/Impl、ArchiveController/Impl、DTO/VO 和 schema。
- `st-team` 的上传路由和必要测试。
- `st-web/src/hooks/useUpload.tsx`、相关类型；`st-desktop/src/upload-manager.ts`及其 metadata 类型。
- P0/P1 对应集成测试。

删除文件：无。

## 三、整体设计方案

### 3.1 Scope 规则

引入内部 scope 语义，不改变 `FileNode` 的外部 JSON：

```java
personal = spaceId == null || spaceId <= 0
team = spaceId != null && spaceId > 0
```

新增/复用以下 Service 方法：

```java
String validatePersonalParent(Long parentId, Long currentUserId);
String validateTeamParent(Long spaceId, Long parentId);
FileNode getNodeByIdAndOwner(Long nodeId); // 只允许 personal + owner
FileNode getTeamNode(Long spaceId, Long nodeId); // 只检查 node scope
void assertTargetScope(FileNode node, FileNode parent, FileScope scope);
```

`validateAndGetParentPath` 只保留结构状态校验，调用方必须先选择 personal/team validator，不能把它当授权方法。

### 3.2 P0-01：个人/团队授权边界

- `getNodeByIdAndOwner` 查询后必须满足 personal scope 且 owner=current user；team node 直接 FORBIDDEN。
- `FileServiceImpl` 的个人 rename/move/copy/delete/detail 使用该方法。
- `VersionServiceImpl`、`RecycleBinServiceImpl` 改用该方法；`DownloadServiceImpl` 不再手写“团队由 Controller 校验”的放行逻辑，个人下载使用严格 personal 校验。
- 团队详情/文件操作使用 `getTeamNode(spaceId,nodeId)`，并要求 caller 已执行 Team ACL；core 仍检查节点 spaceId 和正常态。
- `TeamUploadController` 是团队上传 ACL 入口；不在 st-core 引入 st-team 依赖。

### 3.3 P0-02：父子 scope 不变量

- personal parent：parentId=0/null 返回 personal root；否则 parent 必须 folder、正常、personal、owner=current user。
- team parent：parentId=0/null 返回 team root；否则 parent 必须 folder、正常、spaceId=requested spaceId。
- create folder、copy、move、simple/chunk upload、new blank file 全部先调用对应 validator。
- 新建节点的 owner/uploader 取当前用户，spaceId 只取已确定的 scope，不取混合 parent 的值。
- root path 统一按现有约定生成，禁止把另一 scope 的 root 节点当 parent。

### 3.4 P0-03：scope-safe path

- Mapper 将 `updateChildrenPath(oldPath,newPath)` 改为带 `ownerId`/`spaceId` 的方法；personal 同时 `owner_id` 且 `space_id IS NULL/0`，team 使用 `space_id`。
- 优先新增 `findDescendantIds(rootId, tenantId, ownerId, spaceId)`，以 parent_id CTE 取得 ID 集合；批量按 ID 更新 path。
- `publishMetaUpdate` 使用 source node + descendant ID 集合，不再用 `newPath` 裸前缀查询 affected nodes。
- path 更新前后的 parent_id、scope 不改变；路径失败时事务回滚，不发布事件。

### 3.5 P0-04：上传会话

实体字段：`id、tenantId、uploadId、userId、fileNodeId、spaceId、storagePath、s3UploadId、status、expireAt、createdAt、updatedAt、deleted`。

状态：`INIT(0) / UPLOADING(1) / MERGING(2) / COMPLETED(3) / ABORTED(4) / FAILED(5)`。

接口策略：

```java
UploadInitResponse initChunkedUpload(UploadInitRequest request); // personal-only
UploadInitResponse initTeamChunkedUpload(Long spaceId, UploadInitRequest request);
UploadCheckResponse checkTeamInstantUpload(Long spaceId, UploadCheckRequest request);
UploadSession requireOwnedSession(String uploadId);
```

- `FileController` 的 generic upload init/check 只接受 personal scope；request.spaceId 为空/0，否则拒绝。
- `TeamUploadController` 取 path spaceId，先 `teamService.requirePermissions(spaceId,parentId,"upload")`，再调用 team upload 方法；request 中 spaceId 被忽略。
- init 在 S3 multipart 创建成功后持久化 session；nodeId、storagePath、S3 uploadId 写入 session。
- status/chunk-url/chunk-confirm/merge/abort/relay/finalize 第一行执行 `requireOwnedSession(uploadId)`，检查 tenant、user、未过期和状态。
- 客户端 s3UploadId 只保留兼容字段：服务端不使用它查找或构造 S3 请求；实现可选择不一致即 BAD_REQUEST。fileId 若存在必须等于 session.fileNodeId。
- team 后续接口从 session 读取 nodeId/spaceId，先复核 `spaceId` 相等和 `teamService.requirePermissions(spaceId,nodeId,"upload")`，再调用 core 会话方法。
- abort/merge 的 S3 操作使用 session.s3UploadId；数据库状态和清理按状态条件更新。

### 3.6 P0-05：目录环防护

- Mapper 新增 `existsDescendant(rootId,targetId,scope)`，使用 parent_id CTE；target=self 单独拒绝。
- personal `move` 和 team `moveTeamFiles` 共用 `assertMoveTargetValid`。
- 同目录移动直接 no-op，不发 MOVE event。
- `collectDescendants`、`buildTreeNode`、ZIP 遍历使用 `visited Set<Long>`，并限制 `MAX_TREE_DEPTH=20`、`MAX_TREE_NODES` 配置默认 500000。
- 检测到重复 ID、超深或超节点时抛 BusinessException，禁止 StackOverflow。

### 3.7 P1-01：FileNode 乐观锁

新增小型共享组件：

```java
void updateNodeOrThrowConflict(FileNode node) {
    if (fileNodeMapper.updateById(node) != 1) {
        throw new BusinessException(ResultCode.CONFLICT);
    }
}
```

所有涉及 `@Version` 的 FileNode 写路径改用该组件，包括 rename/move/team rename/team move、recycle restore、version restore、upload finalize/editor overwrite 和 quota 相关状态更新。调用顺序为：主节点 update 成功 → 子树 path → quota/cache → event；任何冲突都阻断后续步骤。

### 3.8 P1-02：同级有效名称唯一

MySQL 使用生成列：

```sql
scope_key  = CASE WHEN COALESCE(space_id,0) > 0
                  THEN CONCAT('S:', space_id)
                  ELSE CONCAT('U:', owner_id) END
active_name = CASE WHEN status = 0 AND deleted = 0 THEN name ELSE NULL END
```

建立 `UNIQUE(tenant_id,scope_key,parent_id,active_name)`。Java 仍保留友好重名检查和 `resolveNameConflict`，最终 insert/update 捕获 `DuplicateKeyException` 转 `FILE_ALREADY_EXISTS`。回收站节点的 active_name 为 null，允许同名重建。

### 3.9 P1-03：版本号分配

- 增加 `UNIQUE(tenant_id,file_node_id,version_num)`。
- 新增 `FileVersionAllocator`，在事务中 `SELECT file_node ... FOR UPDATE`，再读取该节点的最大 version_num，分配 max+1 并插入。
- `snapshotCurrentVersion`、编辑器保存、覆盖上传和恢复统一走 allocator。
- 任何唯一键冲突转 CONFLICT；锁行保证正常路径不重复，唯一键是最终兜底。

### 3.10 P1-04：批量子树

- `FileNodeMapper.findDescendantIds(rootId, tenantId, ownerId, spaceId)` 使用 MySQL 8/H2 可执行的 recursive CTE。
- `collectDescendants` 一次获取 ID/节点，必要时按 1000～5000 ID 分批处理。
- copy/delete/ZIP 统计复用批量结果；复制落库和事件仍按批次提交，单 HTTP 请求遇到超大规模转为受控 job 或拒绝。
- 结构遍历仍执行 scope 条件和 visited 防御，避免 CTE 只保护正常数据而 Java 侧失控。

### 3.11 P1-05：大小统计

`FolderSizeVO` 增加：

```java
private boolean complete;
private LocalDateTime calculatedAt;
```

统计超过 `MAX_FOLDER_SIZE_SCAN_NODES=500000` 时停止并返回 `complete=false`，不再伪装完整；正常完成为 true。缓存 value 包含完整性字段，key 加 tenant/scope。新增批量 descendant 聚合方法，`folder-sizes` 不再串行调用 200 次单树扫描；若无法一次批量完成，明确返回不完整状态。

### 3.12 P1-06：ZIP preflight

- `downloadAsZip` 在创建 `ZipOutputStream` 和写 response body 前，使用批量 descendant 结果计算总大小和节点数。
- 总大小 > 500 MiB 直接抛 FILE_TOO_LARGE；不写 body。
- `addFileToZip` 遇 IOException 记录 nodeId 并重新抛出；不得返回 0 伪成功。
- 目录/文件缺失、未完成或 scope 不一致均 fail-fast；输出可能损坏时不继续写后续 entry。
- 团队 ZIP 通过显式团队下载入口复核 ACL；当前 generic download 只处理 personal scope。

### 3.13 P1-07：Archive 安全

配置默认值（均可按环境覆盖）：

```yaml
stcloud:
  archive:
    safety:
      max-entries: 10000
      max-entry-size: 268435456
      max-total-uncompressed-size: 1073741824
      max-depth: 20
      max-compression-ratio: 100
      max-queued-tasks: 50
      max-active-tasks-per-user: 2
```

- browse 要求 `file:preview`，extract 要求 `file:upload`；Service 仍校验资源归属。
- 统一 `/` 与 `\\`，拒绝空 segment、`.`、`..`、绝对路径和盘符路径；每个 segment 调 `FileNameSanitizer.sanitize`，null 直接拒绝。
- 预检阶段统计 entries、压缩/解压大小和深度；entry size 未知时流式读取并实时限制，不信任 ZIP header。
- entry 内容采用临时文件 + 流式 Digest，不使用无上限 ByteArrayOutputStream；临时文件在 finally 清理。
- 使用有界 executor；每用户活动任务计数超过 2 时拒绝，不把请求无限放入队列。

### 3.14 P1-08：上传参数

新增 `UploadProperties` 默认值：

```yaml
stcloud:
  upload:
    max-file-size: 10737418240
    max-total-chunks: 20000
    min-chunk-size: 5242880
    max-chunk-size: 104857600
    max-client-limit-kb: 1048576
```

- fileSize `0..max-file-size`。
- totalChunks `1..max-total-chunks`。
- chunkSize 在 min/max 区间；空文件仍需要 1 个逻辑分片。
- clientLimit `0..max-client-limit-kb`。
- MD5 必须是 32 位十六进制。
- `totalChunks == ceil(fileSize/chunkSize)`；若现有协议允许最后一片特殊大小，只允许最后一片小于 chunkSize，不放宽总数。
- `createChunkRecords` 使用 500～1000 行 batch insert，仍由 upload_session 保护调用者。

## 四、前端设计

- `useUpload` 增加 `uploadScope`/`spaceId` 路由选择：个人走 `/file/upload`，团队走 `/team/{spaceId}/files/upload`。
- `UploadInitRequest`/任务 metadata 保存 scope 类型和 spaceId；恢复时不从 nodeId 反推空间。
- 所有后续请求保留现有参数以兼容旧客户端，但优先使用服务端返回的 session 关联；服务端错误转换为会话失效/权限/冲突/超限提示。
- `st-desktop` 个人同步不改变默认路由；团队同步若已有 spaceId 上下文则使用团队路由，否则不得把 team node 送进 generic API。
- 不改变页面布局和视觉样式。

## 五、后端设计

### API

团队上传新增以下路由，path `spaceId` 为服务端 scope 来源：

```text
POST   /api/team/{spaceId}/files/upload/check
POST   /api/team/{spaceId}/files/upload/init
GET    /api/team/{spaceId}/files/upload/status
GET    /api/team/{spaceId}/files/upload/chunk-url
POST   /api/team/{spaceId}/files/upload/chunk-confirm
POST   /api/team/{spaceId}/files/upload/merge
DELETE /api/team/{spaceId}/files/upload/abort
POST   /api/team/{spaceId}/files/upload/relay-chunk
POST   /api/team/{spaceId}/files/upload/relay-finalize
```

generic `/api/file/upload/*` 的请求和响应字段保持兼容，但 team spaceId 不再被接受为个人 scope。

### 异常

- personal/team 越界：FORBIDDEN。
- session owner/tenant/expiry/status 失败：FORBIDDEN 或 CHUNK_NOT_FOUND，统一不调用 S3。
- version/node update rows != 1：CONFLICT。
- 唯一索引冲突：FILE_ALREADY_EXISTS 或 CONFLICT。
- 资源超限：BAD_REQUEST/FILE_TOO_LARGE；响应体开始前返回。

## 六、数据库设计

### 迁移文件

1. `40_file_scope_constraints.sql`：首行 `SET NAMES utf8mb4;`；只包含生成列、唯一索引和 file_version 唯一索引。执行前由预检脚本查询冲突。
2. `41_upload_session.sql`：首行 `SET NAMES utf8mb4;`；创建 upload_session 及索引。
3. 同步 `st-core/src/test/resources/schema.sql`，H2 使用等价的 generated expression 或可验证的派生字段定义。
4. `.ai/scripts/compare-schema.ps1` 在迁移前后各运行一次；运行中的 MySQL 仅在预检通过后执行。

### SQL 安全

- path 更新必须有 tenant + personal owner/space 条件，优先 ID 集合。
- CTE anchor 和 recursive join 带 tenant 条件，避免跨租户 parent 关系参与结果。
- upload_session 查询以 tenant + uploadId + userId 为条件，客户端 s3UploadId 不进查询条件。

## 七、安全设计

- Service 是最终防线，Controller 不能成为唯一权限检查点。
- `validateAccessible`/`assertStructurallyAvailable` 只表示祖先结构状态，不表示用户授权；本轮保留 P2-01 作为后续命名整理，P0 实现不依赖该误导语义。
- 团队 ACL 在 st-team 入口执行；core 的 team 方法必须验证传入 node/parent 的 spaceId，禁止跨空间。
- 归属校验先于路径、版本、S3 和事件读取，降低资源存在性泄露。
- S3 key/uploadId 只能由持久化 session 或 server-created node 提供。

## 八、性能设计

- 目录递归使用 CTE/批量查询，避免新增长目录 N+1。
- 版本分配只锁单个 file_node 行，不锁整个版本表。
- 同级命名仍可先查以提供友好提示，但以数据库唯一键解决竞态。
- Archive 使用流式 hash + 临时文件，限制内存；线程池有界。
- folder size 缓存结果标识完整性，批量接口优先一次聚合。

## 九、开发计划

```text
Task1 CORE-P0-01：收紧 personal-only service，补 generic API team node 负向测试。
Task2 CORE-P0-02：增加 personal/team parent validator，覆盖 create/copy/move/upload/new-file。
Task3 CORE-P0-03：scope-safe path update 和 ID-based meta event。
Task4 CORE-P0-04：upload_session、TeamUploadController、客户端路由迁移。
Task5 CORE-P0-05：统一 move cycle guard 和遍历防御。
Task6 CORE-P1-01～03：乐观锁、同级唯一、版本 allocator 和数据库约束。
Task7 CORE-P1-04～06：批量子树、统计完整性、ZIP preflight。
Task8 CORE-P1-07～08：Archive 安全和上传参数边界。
Task9：串行集成测试、schema 对比、Code Review、安全 Review、知识库同步。
```

每个 Task：先读完整 Controller/Service/Mapper/Entity/DDL/Test 链路，补失败测试，实施，运行 `mvn -pl st-core test`，再由主线程串行集成验证。

## 十、风险分析

| 风险 | 影响 | 解决方案 |
|---|---|---|
| 旧团队客户端仍调用 generic upload | 上传失败 | 先提供团队路由并更新 Web/桌面；generic 入口明确拒绝 team scope |
| 存量同名阻止索引 | 迁移失败 | 只读预检，冲突清单交人工处理 |
| H2 与 MySQL 生成列不一致 | 测试假绿/假红 | schema consistency + H2/MySQL 双重执行 |
| 大批量操作超 HTTP 时限 | 请求失败 | 批次边界和规模上限；job 化作为后续扩展，不在 P0 机械拆分 |
| 旧 uploadId 无 session | 断点续传中断 | 不信任旧客户端 S3 ID；允许用户重新初始化，兼容策略记录在测试报告 |

## 十一、遗留问题点

无。数据库迁移、团队路由、P0/P1 范围和不自动删除存量冲突均已由用户确认。

## 十二、用户确认记录

- 确认内容：同意本技术设计进入测试用例与实现阶段。
- 确认时间：2026-09-12（本轮用户消息“确认”）。
