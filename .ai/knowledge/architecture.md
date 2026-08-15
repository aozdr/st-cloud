# 架构设计

> 本文档描述 st-cloud 的整体架构、分层模式、跨模块通信与关键技术决策。

## 分层架构

每个业务模块遵循统一的四层结构：

```
Controller  (REST API，@RestController，/api 前缀)
    ↓
Service     (业务逻辑接口 + impl 实现)
    ↓
Mapper      (MyBatis-Plus BaseMapper，数据访问)
    ↓
Entity      (数据库实体，继承 BaseEntity)
```

辅助层：

- **DTO**：请求/响应数据传输对象（Request/VO 后缀）
- **Config**：模块级配置类（如 `ElasticsearchConfig`、`StorageInitializer`）
- **Enums**：模块级枚举（如 `UploadStatus`）
- **Event**：跨模块事件（如 `FileIndexEvent`）
- **Task**：定时任务（如 `RecycleBinPurgeTask`）
- **Aspect**：AOP 切面（如 `AuditAspect`）
- **Listener**：事件监听器（如 `FileIndexEventListener`）

## 模块依赖关系

```
                    st-api (启动聚合)
                 ┌─────┼─────┐
        ┌────────┼─────┼─────┼────────┐
        ↓        ↓     ↓     ↓        ↓
    st-share  st-team st-sync st-search st-preview  st-admin
        │        │       │       │         │           │
        └────────┴───┬───┴───────┴─────────┘           │
                     ↓                                 │
                  st-core ←────────────────────────────┘
                     │
                ┌────┴────┐
                ↓         ↓
            st-auth    st-common (基座)
                │
                ↓
            st-common
```

- **st-common**：零内部依赖，提供 `BaseEntity`、`Result<T>`、`ResultCode`、`UserContext`、`TenantContext`、S3 配置、限速服务、全局异常处理
- **st-auth**：仅依赖 st-common，提供 JWT、Security 链、用户/角色/权限实体
- **st-core**：仅依赖 st-common，是文件管理核心，被所有上层模块依赖
- **上层功能模块**（st-share/team/sync/search/preview/admin）：依赖 st-common + st-core（部分还依赖 st-auth）
- **st-api**：聚合全部模块，包含 `StCloudApplication` 主类和所有配置文件

## 同步引擎 V2（桌面端 + 服务端）

- **桌面端 SyncEngine**（`st-desktop/src/sync-engine.ts`）：
  - 版本门控：`sync_config.sync_version` 与引擎常量 `SYNC_ENGINE_VERSION` 不一致或 `last_sync_at` 为空 → 全量重建一次（清本地机器格式垃圾 → 清表（保留游标）→ 云端快照对账，完整成功才固化版本）；否则只走增量
  - 增量以 `sync_change_log.id` 游标为准（单调、无时钟漂移），游标仅在全部变更处理成功后推进
  - 事件合并（pending 不丢弃）+ 引擎自写 30s TTL 过滤，防自激循环
  - `upsertSyncState` 为合并语义（COALESCE），局部更新不擦 `local_mtime`
  - keep_both 冲突：`(冲突-时间戳)` 本地副本 + `(本地-时间戳)` 云端副本，落盘即登记；本地版副本经系统临时目录上传，同步目录内零临时文件
- **服务端**：rename 同名 / move 同目录 no-op 守卫；delta 兜底过滤 `oldPath == path`；管理员清理接口 `POST /api/admin/sync/cleanup-junk`（复用回收站永久删除：引用归零删 S3、退还配额、清 ES）
- 详细规则见 `.ai/knowledge/sync-engine-v2.md`

### 同步实时推送（WebSocket）

```
文件变更写入 sync_change_log 后
    → SyncPushService 向文件所有者全部在线会话推送 {"event":"change","userId":..,"logId":..}
    → 桌面端 ws-client.ts 收到通知 → 立即拉取 /delta 增量（近实时同步）
```

- 端点：`/api/sync/ws?token=<JWT>`（原生 WebSocket，握手拦截器校验 JWT，无效拒绝）
- 会话注册表：`userId -> Set<WebSocketSession>`，支持同一用户多设备同时在线
- 兜底：WebSocket 断线/未连接时由客户端定时轮询 delta（30s 定时兜底）

## 在线文档编辑（OnlyOffice 社区版，20260815 起）

```
浏览器 (st-web EditorPage, 全屏 /file/:nodeId/editor)
    │ iframe
    ▼
OnlyOffice Document Server (docker-compose :8081, 免费社区版)
    │ 1. GET document.url（编辑器下载令牌，5 分钟）
    │ 2. POST callbackUrl（保存/关闭回调，JWT 验签）
    ▼
st-api
    ├─ GET /api/file/{nodeId}/editor/config（个人，owner/租户管理员）
    ├─ GET /api/team/{spaceId}/files/{nodeId}/editor/config（团队 upload 权限点）
    ├─ GET /api/share/access/editor-config/{shareCode}（分享 upload 权限点）
    └─ POST /api/file/{nodeId}/editor/callback（OnlyOffice 回调，匿名 + JWT 验签）
```

- **保存**：status=2 自动保存覆盖 file_node（不生成版本）；status=6/7 关闭保存覆盖 + 生成
  file_version(source=1) + 上限 20 裁剪（仅 source=1，D1）+ 移除编辑标记
- **安全**：回调 JWT 验签（STCLOUD_ONLYOFFICE_SECRET）+ key/status 一致性 + 文件归属复核；
  回调下载主机白名单 + 200MB 上限（SSRF/投毒防护）
- **并发**：编辑标记 Redis Set（多人协同不互斥）+ 保存锁 SETNX 串行化 + 幂等键防重复落盘；
  编辑期间 delete/move/rename/覆盖上传/版本恢复被拦截（FILE_EDITING 2010）
- **部署**：docker-compose 内置 onlyoffice 容器（extra_hosts host-gateway）；生产须配置
  `stcloud.onlyoffice.public-base-url` 为后端可达地址

## 跨模块通信

### 可靠事件（Transactional Outbox + RocketMQ）

核心变更不再只依赖进程内 Spring 事件，而是走 **Outbox 可靠事件**（TASK-004）：

```
业务事务内（上传/删除/移动/重命名...）
    │
    ├─ ReliableEventPublisher.writeOutbox()
    │    → 事务内插入 event_log（Outbox 行，状态=待投递）
    │
    ├─ 发布 OutboxRelayEvent（仅 MQ 配置时）
    │    → @TransactionalEventListener(AFTER_COMMIT) 读取 event_log
    │    → EventRelay 投递 RocketMQ，topic = 事件类型（FILE_INDEX / SYNC_CHANGE）
    │    → 成功标 status=1；失败标 status=2 由 EventRetryTask 定时重投
    │
    └─ 兜底：rocketmq.name-server 未配置时，事务内直接发布本地事件
         （FileIndexEvent / SyncChangeEvent），同时 Outbox 标记本地投递

消费者（幂等）：
    st-search  FileIndexMessageConsumer  topic=FILE_INDEX  group=stcloud-search
    st-sync    SyncChangeMessageConsumer  topic=SYNC_CHANGE  group=stcloud-sync
```

- **Outbox 行随业务事务回滚**：事务回滚即不产生事件（“回滚即无事件”）
- **幂等键**：`event_log.id` 下发到消息（`EventMessage.eventLogId`），消费者先查后写，
  `sync_change_log.uk_event_log_id` 唯一键兜底，重复投递不产生重复日志
- **Retry**：`EventRetryTask` 定时扫描 `status=2`（投递失败）的 Outbox 行重投
- **本地兜底**：未配置 RocketMQ 时走 Spring ApplicationEvent 同进程异步，链路与 MQ 并存

### 文件索引事件（core → search）

```
st-core                    st-search
FileServiceImpl            FileIndexEventListener
    │                           │
    │  ReliableEventPublisher   │
    │  → event_log + MQ/本地    │
    ├──────────────────────────→│ @EventListener
    │  ActionType.INDEX          │  → ES 索引/更新文档
    │  ActionType.DELETE         │  → ES 删除文档
    │  ActionType.UPDATE_META    │  → ES 仅更新元数据（path/name）
    │                           │
```

- **触发点**：文件上传完成、文件删除、文件移动/重命名
- **机制**：`ReliableEventPublisher`（事务内写 event_log Outbox）→ RocketMQ 主题 `FILE_INDEX`
  → `FileIndexMessageConsumer` 消费写 ES；RocketMQ 未配置时退化为同进程 `@EventListener`
- ** ActionType 枚举**：`INDEX`（索引/更新）、`DELETE`（删除索引）、`UPDATE_META`（仅更新元数据，不重新解析内容）

### 同步变更事件（core → sync）

文件操作发布 `SyncChangeEvent`（含 oldPath），`SyncChangeLogListener`（本地兜底）或
`SyncChangeMessageConsumer`（MQ，group=stcloud-sync）将变更写入 `sync_change_log`，
随后 `SyncPushService` 推送 WebSocket 通知。变更日志自增 `id` 即同步游标。

## 多租户架构

### 租户模式

- **SAAS**（默认）：多租户隔离，所有数据通过 `tenant_id` 字段隔离
- **PRIVATE**：单租户模式，`tenant_id` 固定为默认值
- 配置项：`stcloud.tenant.mode`

### 租户上下文

```
请求进入 → JwtAuthenticationFilter 解析 JWT
         → 提取 userId / tenantId
         → 写入 UserContext / TenantContext (ThreadLocal)
         → 业务代码通过 Context 获取当前租户
         → MyBatis-Plus 查询自动附加 tenant_id 条件
         → 请求结束清理 ThreadLocal
```

- `TenantContext`：ThreadLocal 存储当前租户 ID
- `UserContext`：ThreadLocal 存储当前用户 ID
- `BaseEntity.tenantId`：所有业务表都有租户字段
- `MyMetaObjectHandler`：插入时自动填充 `tenantId`、`createdAt`、`updatedAt`

## 存储架构

### S3 对象存储

使用 AWS S3 SDK v2 连接 S3 兼容存储（RustFS / MinIO），配置在 `S3StorageConfig`。

| Bucket | 用途 |
|--------|------|
| `stcloud` | 主存储（完整文件对象） |
| `stcloud-chunks` | 分片存储（上传中分片） |
| `stcloud-preview` | 预览缓存（缩略图/转码文件） |

### 自动初始化

`StorageInitializer`（ApplicationRunner）启动时检查并自动创建三个 bucket，配置 CORS 规则。

### 秒传去重（FileObject 对象模型）

基于 **file_object 表 + 引用计数** 的对象级去重（同租户内按 MD5 唯一）：

```
上传完成
  → upsert file_object（(tenant_id, md5) 唯一）
  → file_node.object_id 引用 file_object.id（ref_count = 引用该对象的 file_node 数）
  → 同租户相同 MD5 的重复文件不重复上传 S3

删除文件
  → file_object.ref_count--
  → ref_count == 0 时删除 S3 物理对象并逻辑删除 file_object
  → ref_count > 0 时保留物理对象
```

- `file_object`：`(tenant_id, md5)` 唯一键，`storage_path` 为物理对象 key，`ref_count` 引用计数
- `FileNode.objectId`：去重引用（文件夹/未完成上传为 NULL）
- `FileNode.refCount`：历史遗留冗余字段，权威计数以 `file_object.ref_count` 为准
- 迁移：28 号脚本对存量已完成文件回填 file_object 并关联 object_id

## 安全架构

### JWT 认证链

```
HTTP 请求
  → JwtAuthenticationFilter (前置过滤器)
    → 提取 Authorization: Bearer <token>
    → 验证 JWT 签名 & 过期时间
    → 加载用户信息到 SecurityContext
    → 设置 UserContext / TenantContext
  → Spring Security 授权检查
  → Controller 处理
```

- **无状态**：`SessionCreationPolicy.STATELESS`，不创建 HTTP Session
- **密钥管理**：JWT 签名密钥存储在 `sys_jwt_secret` 表，通过 `STCLOUD_MASTER_KEY` 环境变量加解密，不入源码
- **Token 刷新**：Access Token（7d）+ Refresh Token（30d）

### 公开接口白名单

以下接口无需认证（`SecurityConfig` 中 `permitAll`）：

- `/api/auth/register`、`/api/auth/login`、`/api/auth/refresh`、`/api/auth/ping`
- `/api/share/access/**`（分享访问，公开链接）
- `/doc.html`、`/webjars/**`、`/v3/api-docs/**`（Swagger/Knife4j）
- `/actuator/**`（健康检查）

### RBAC 权限

- **注解拦截**：`@EnableMethodSecurity` 启用方法级权限，通过 `@PreAuthorize` 控制
- **权限模型**：用户 → 角色 → 权限（多对多关联表）
- **数据范围**：角色 `data_scope` 字段控制数据可见范围（1-本人 / 2-租户 / 3-全部）
- **审计**：`@Auditable` 注解 + `AuditAspect` 切面自动记录操作日志

### 资源级权限（团队文件夹 + 分享）

除系统 RBAC 外，还有两层资源级权限，均以 **权限点 JSON** 为权威（20260814 权限模型重设计）：

- **团队文件夹权限**（`team_folder_permission`）：按 `(folder_node_id, subject_type, subject_id)`
  授权（role/member），`permissions` JSON 覆盖 9 个权限点
  （view/upload/download/delete/rename/move/share/manage_members/manage_settings），
  单值 `permission`（-1/0/1/2）保留兼容，不再作为计算依据
- **分享权限**（`file_share.permissions`）：分享访问的有效操作集合由 `permissions` JSON 决定，
  单值 `permission` 仅用于前端展示；`allow_download` 为下载/流式统一开关（下载与流式预览一致）
- **超权防线**：创建/更新分享与文件夹权限时校验「授权范围 ⊆ 当前用户有效权限」，超权拒绝

### CORS

- 配置项：`stcloud.cors.allowed-origins`（逗号分隔）
- 开发环境默认：`http://localhost:5173,http://127.0.0.1:5173`
- 生产环境必须配置，留空则拒绝所有跨域请求
- 允许凭证（`allowCredentials: true`）

## 统一响应与异常

### 响应封装

所有 API 返回 `Result<T>`：

```json
{
  "code": 200,
  "message": "成功",
  "data": { ... }
}
```

### 全局异常处理

`GlobalExceptionHandler` 统一捕获：
- `BusinessException` → 对应 `ResultCode` 业务错误码
- Spring MVC 参数校验异常 → 400
- 其他未捕获异常 → 5000 系统内部错误

> **合理例外（W8）**：文件流式下载接口（`FileController.streamFile` / `downloadAsZip`）直接向 HTTP 响应流写二进制数据，不经过 `Result<T>` JSON 包装，属大文件/二进制流下载场景的合理例外。

### 错误码分段

| 段 | 范围 | 领域 |
|----|------|------|
| HTTP 标准 | 200-409 | 通用 HTTP |
| 业务 | 1000-1008 | 用户/认证/角色/权限 |
| 文件 | 2001-2009 | 文件/上传/配额/容量 |
| 分享 | 3001-3004 | 分享/过期/密码/权限 |
| 团队 | 4001-4004 | 团队/成员/权限 |
| 系统 | 5000-5002 | 内部错误/服务不可用/存储异常 |
