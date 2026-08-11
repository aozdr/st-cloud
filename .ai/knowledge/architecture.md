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

## 跨模块通信

### 文件索引事件（core → search）

```
st-core                    st-search
FileServiceImpl            FileIndexEventListener
    │                           │
    │  publish FileIndexEvent   │
    │  (Spring ApplicationEvent)│
    ├──────────────────────────→│ @EventListener
    │  ActionType.INDEX          │  → ES 索引/更新文档
    │  ActionType.DELETE         │  → ES 删除文档
    │  ActionType.UPDATE_META    │  → ES 仅更新元数据（path/name）
    │                           │
```

- **触发点**：文件上传完成、文件删除、文件移动/重命名
- **机制**：Spring `ApplicationEventPublisher` + `@EventListener`，同进程异步
- ** ActionType 枚举**：`INDEX`（索引/更新）、`DELETE`（删除索引）、`UPDATE_META`（仅更新元数据，不重新解析内容）

### RocketMQ（预留）

配置了 RocketMQ name-server（`127.0.0.1:9876`）和 producer group，用于事件消息。当前核心索引通过 Spring 事件实现，RocketMQ 作为可选的异步消息扩展。

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

### 秒传去重

基于 **MD5 + 引用计数** 的去重机制：

```
上传前检查 (upload/check)
  → 计算 MD5
  → 查询 file_node 是否存在相同 MD5 的正常文件
  → 存在：秒传成功，refCount++，不重复上传 S3
  → 不存在：进入分片上传流程

删除文件
  → refCount--
  → refCount == 0 时删除 S3 物理对象
  → refCount > 0 时保留物理对象
```

- `FileNode.fileMd5`：文件内容 MD5
- `FileNode.refCount`：物理对象引用计数
- `FileNode.storagePath`：S3 对象 key

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
- **Token 刷新**：Access Token（2h）+ Refresh Token（30d）

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

### 错误码分段

| 段 | 范围 | 领域 |
|----|------|------|
| HTTP 标准 | 200-409 | 通用 HTTP |
| 业务 | 1000-1008 | 用户/认证/角色/权限 |
| 文件 | 2001-2009 | 文件/上传/配额/容量 |
| 分享 | 3001-3004 | 分享/过期/密码/权限 |
| 团队 | 4001-4004 | 团队/成员/权限 |
| 系统 | 5000-5002 | 内部错误/服务不可用/存储异常 |