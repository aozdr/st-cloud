# 影响分析

## 1. 结论

本次整改跨越 `st-core`、`st-team`、Web/桌面上传调用、MySQL/H2 schema 和集成测试。P0-01～P0-05 是权限与树结构的前置依赖；P1-01～P1-08 在 P0 稳定后按并发、批量操作、资源安全、参数校验推进。

## 2. 模块影响

| 类型 | 模块 | 影响 | 是否修改 |
|---|---|---|---|
| 后端 | st-core Controller | 个人/团队上传入口、二进制下载和在线解压权限 | 是 |
| 后端 | st-core Service | FileService、Upload、Download、Version、Recycle、Archive | 是 |
| 后端 | st-core Mapper/Entity/DTO | scope 查询、上传会话、批量子树、版本和资源限制模型 | 是 |
| 后端 | st-team | 显式团队上传路由；每个后续会话操作复核团队 ACL | 是 |
| 前端 | st-web | 团队上传调用从 `/file/upload/*` 迁移到团队命名空间；错误状态保持可见 | 是 |
| 客户端 | st-desktop | 个人同步上传保持原路由；有团队上下文时使用团队路由 | 视现有调用上下文修改 |
| 数据库 | MySQL | upload_session、file_node 有效同级唯一、file_version 版本唯一 | 是 |
| 数据库 | H2 | 同步表、字段、索引和测试约束 | 是 |
| 缓存 | accessible/folder size | key 不再跨 scope；统计结果增加完整性标识 | 是 |
| 第三方 | S3/RustFS | 继续使用现有接口；只允许使用服务端会话上下文 | 不改协议 |
| 消息 | Outbox/ES/同步 | 仅在数据库更新成功后发布；子树事件来源改为 ID 集合 | 是 |

## 3. 关键调用链

### 个人/团队文件操作

```text
FileController / TeamController
  ↓
FileService / DownloadService / VersionService / RecycleBinService
  ↓
FileNode scope + parent_id + 祖先状态校验
  ↓
FileNodeMapper（tenant + owner 或 space 条件）
  ↓
数据库与 Outbox
```

### 上传

```text
个人入口或 TeamUploadController
  ↓
UploadService scope 校验 + UploadSessionService 会话校验
  ↓
FileNode / FileChunk / UploadSession
  ↓
S3 外部操作
  ↓
事务内 finalize、配额、版本、事件
```

## 4. 数据模型影响

- `file_node` 增加用于唯一约束的生成列或等价派生列：`scope_key`、`active_name`。
- `file_version` 增加 `(tenant_id, file_node_id, version_num)` 唯一索引。
- 新增 `upload_session`，保存 tenantId、userId、nodeId、spaceId、storagePath、s3UploadId、状态和过期时间。
- P1-05 为 `FolderSizeVO` 增加 `complete`、`calculatedAt`，属于向后兼容的响应字段扩展。
- P1-07/P1-08 采用配置属性，不新增用户可控的无限资源参数。

## 5. 权限影响

- `getNodeByIdAndOwner` 语义收紧为 personal-only，调用者包括详情、重命名、移动、复制、删除、版本、下载和个人回收站。
- 团队入口必须携带 path spaceId，并由 `TeamService.requirePermissions` 做 ACL；core service 再做 node/parent scope 一致性校验。
- 当前没有团队专用上传接口，需新增 `TeamUploadController`，否则收紧个人上传入口会中断现有团队上传。
- 不把 dataScope、nodeId、spaceId、uploadId、s3UploadId 中任一项单独当作授权凭据。

## 6. API 影响与兼容

| API | 变化 | 兼容策略 |
|---|---|---|
| `/api/file/upload/*` | 只允许 personal scope | 原个人客户端继续使用；携带有效 team spaceId 明确拒绝 |
| `/api/team/{spaceId}/files/upload/*` | 新增团队上传路由 | 新客户端使用；保留请求中旧 `s3UploadId/fileId` 字段但服务端不信任 |
| 上传状态/URL/确认/合并/中止/中转 | 以 upload_session 的 node/S3 ID 为准 | 旧字段可传但只做一致性校验或忽略 |
| FolderSizeVO | 增加完整性和计算时间字段 | JSON 新增字段，不破坏旧客户端解析 |
| 冲突错误 | 新增/复用 CONFLICT、FILE_ALREADY_EXISTS | 保持现有 Result 封装和错误码体系 |

## 7. 测试影响

- 扩展 `FileServicePermissionIntegrationTest`：team node 访问、跨 team scope、混合 parent、generic API 负向矩阵。
- 扩展 `FileServiceFlowIntegrationTest`：团队移动成环、同目录 no-op、scope-safe path。
- 扩展上传集成测试：会话归属、team upload、参数边界、批量分片。
- 新增 Mapper/数据库并发测试：同名节点、版本号、乐观锁。
- 新增 Archive/Download 安全测试：Zip Bomb、路径条目、ZIP preflight 和 fail-fast。
- 所有数据库变更同步 `st-core/src/test/resources/schema.sql`，并运行 H2 测试和 schema 对比。

## 8. 迁移和运维影响

1. 对 MySQL 存量数据执行只读重复检查：个人/team scope 下的有效同级重名、同一文件节点的重复 version_num。
2. 检查结果非空时迁移停止，不自动删除或改名。
3. 通过预检后依次执行递增 SQL，再写入 `schema_version` 唯一版本号和执行记录。
4. 回滚采用停用新入口/应用版本回滚；新增表和索引的物理回滚需人工审批，不在本任务自动执行。

## 9. 风险

| 风险 | 等级 | 应对 |
|---|---|---|
| 现有团队上传依赖 generic API | High | 先新增 TeamUploadController，再切客户端，最后收紧 generic service |
| 存量重复数据阻止唯一索引 | High | 迁移前预检，冲突即停并输出清单 |
| 旧上传会话没有 upload_session | High | 新会话强制绑定；旧会话不信任客户端 S3 ID，过渡策略单独记录 |
| 目录脏环造成批量 CTE异常 | High | visited、深度/节点上限和业务异常；修复数据另行审批 |
| 二进制响应开始后才发现 ZIP 超限 | High | response body 前完成 preflight |
