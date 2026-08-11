# 数据模型

> 本文档描述 st-cloud 的数据库表结构、实体关系与枚举定义。
> 数据库：MySQL 8.0，字符集 utf8mb4，引擎 InnoDB。

## BaseEntity 基类

所有业务实体继承 `BaseEntity`（`com.stcloud.common.entity.BaseEntity`），自动包含：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT | 主键，雪花算法 `IdType.ASSIGN_ID` |
| `tenant_id` | BIGINT | 租户 ID（多租户隔离） |
| `created_at` | DATETIME | 创建时间，插入时自动填充 |
| `updated_at` | DATETIME | 更新时间，插入/更新时自动填充 |
| `deleted` | TINYINT | 逻辑删除标志（0-未删除 / 1-已删除），`@TableLogic` |

> `MyMetaObjectHandler` 负责自动填充 `tenantId`、`createdAt`、`updatedAt`。

## 表清单

### 核心表（02_create_tables.sql）

#### sys_tenant — 租户表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| tenant_name | VARCHAR | 租户名称 |
| tenant_code | VARCHAR | 租户编码 |
| domain | VARCHAR | 域名 |
| status | TINYINT | 状态 |
| default_quota | BIGINT | 默认用户配额 |
| cloud_total_capacity | BIGINT | 云盘总容量（字节），NULL=不限 |
| expire_at | DATETIME | 过期时间 |

#### sys_user — 用户表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| username | VARCHAR | 用户名 |
| password | VARCHAR | 密码（BCrypt） |
| nickname | VARCHAR | 昵称 |
| email | VARCHAR | 邮箱 |
| phone | VARCHAR | 手机号 |
| avatar | VARCHAR | 头像 |
| status | TINYINT | 状态 |
| storage_used | BIGINT | 已用存储 |
| storage_quota | BIGINT | 存储配额 |
| last_login_at | DATETIME | 最后登录时间 |
| last_login_ip | VARCHAR | 最后登录 IP |

#### file_node — 文件节点表（核心）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| parent_id | BIGINT | 父节点 ID（根目录为 NULL/0） |
| node_type | TINYINT | 0-文件夹 / 1-文件 |
| name | VARCHAR | 名称 |
| path | VARCHAR | 完整路径 |
| file_size | BIGINT | 文件大小（字节） |
| file_md5 | VARCHAR | 文件 MD5（秒传去重） |
| content_type | VARCHAR | MIME 类型 |
| suffix | VARCHAR | 文件后缀 |
| storage_path | VARCHAR | S3 对象 key |
| status | TINYINT | 0-正常 / 1-回收站 / 2-已删除 |
| upload_status | TINYINT | 0-待上传 / 1-上传中 / 2-已完成 / 3-失败 |
| uploader_id | BIGINT | 上传者 ID |
| owner_id | BIGINT | 所有者 ID |
| space_id | BIGINT | 团队空间 ID（NULL=个人空间） |
| ref_count | INT | 物理对象引用计数 |
| version | INT | 乐观锁版本号（`@Version`） |
| thumbnail_path | VARCHAR | 缩略图 S3 路径 |

#### file_chunk — 文件分片表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| upload_id | VARCHAR | S3 分片上传 ID |
| file_node_id | BIGINT | 关联文件节点 |
| chunk_index | INT | 分片序号 |
| chunk_size | BIGINT | 分片大小 |
| chunk_md5 | VARCHAR | 分片 MD5 |
| storage_path | VARCHAR | S3 分片存储路径 |
| original_size | BIGINT | 原始大小（按差值计费） |
| status | TINYINT | 分片状态 |

#### file_version — 文件版本表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| file_node_id | BIGINT | 关联文件节点 |
| version_num | INT | 版本号 |
| file_size | BIGINT | 版本文件大小 |
| file_md5 | VARCHAR | 版本 MD5 |
| storage_path | VARCHAR | 版本存储路径 |
| modifier_id | BIGINT | 修改人 ID |
| modifier_name | VARCHAR | 修改人名称 |

#### file_share — 文件分享表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| share_code | VARCHAR | 分享码（短链） |
| file_node_id | BIGINT | 分享的文件节点 |
| creator_id | BIGINT | 创建者 ID |
| share_type | TINYINT | 0-公开 / 1-私密（提取码） |
| password | VARCHAR | 提取码（BCrypt） |
| expire_at | DATETIME | 过期时间（NULL=永久） |
| permission | TINYINT | 0-查看 / 1-下载 / 2-上传 / 3-编辑 |
| download_limit | INT | 下载次数限制（NULL=不限） |
| download_count | INT | 已下载次数 |
| view_count | INT | 访问次数 |
| status | TINYINT | 0-已取消 / 1-有效 |

#### team_space — 团队空间表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| space_name | VARCHAR | 空间名称 |
| description | VARCHAR | 描述 |
| icon | VARCHAR | 图标 |
| owner_id | BIGINT | 创建者 ID |
| storage_used | BIGINT | 已用存储 |
| storage_quota | BIGINT | 存储配额 |
| status | TINYINT | 0-禁用 / 1-正常 |

#### team_member — 团队成员表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| space_id | BIGINT | 团队空间 ID |
| user_id | BIGINT | 用户 ID |
| role | TINYINT | 0-管理员 / 1-编辑者 / 2-查看者 |
| joined_at | DATETIME | 加入时间 |
| last_active_at | DATETIME | 最后活跃时间 |

#### sync_device — 同步设备表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| user_id | BIGINT | 用户 ID |
| device_name | VARCHAR | 设备名称 |
| device_type | VARCHAR | 设备类型 |
| device_id | VARCHAR | 设备标识 |
| sync_path | VARCHAR | 同步路径 |
| last_sync_at | DATETIME | 最后同步时间 |
| status | TINYINT | 状态 |

#### audit_log — 审计日志表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| user_id | BIGINT | 用户 ID |
| username | VARCHAR | 用户名 |
| action | VARCHAR | 操作动作 |
| target_type | VARCHAR | 目标类型 |
| target_id | BIGINT | 目标 ID |
| target_name | VARCHAR | 目标名称 |
| detail | TEXT | 详情 |
| ip_address | VARCHAR | IP 地址 |
| user_agent | VARCHAR | User-Agent |
| status | TINYINT | 状态 |

### RBAC 表（04_rbac_tables.sql）

#### sys_role — 角色表（按租户隔离）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| role_code | VARCHAR | 角色编码 |
| role_name | VARCHAR | 角色名称 |
| status | TINYINT | 0-禁用 / 1-启用 |
| built_in | TINYINT | 内置角色（不可删除） |
| data_scope | TINYINT | 数据范围：1-本人 / 2-租户 / 3-全部 |
| data | JSON | 扩展数据（如限速配置） |

#### sys_permission — 权限表（全局，不按租户隔离）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| permission_code | VARCHAR | 权限编码（模块:操作） |
| permission_name | VARCHAR | 权限名称 |
| module | VARCHAR | 所属模块 |

#### sys_user_role — 用户-角色关联表

| 字段 | 类型 | 说明 |
|------|------|------|
| user_id | BIGINT | 用户 ID |
| role_id | BIGINT | 角色 ID |

#### sys_role_permission — 角色-权限关联表

| 字段 | 类型 | 说明 |
|------|------|------|
| role_id | BIGINT | 角色 ID |
| permission_id | BIGINT | 权限 ID |

### 其他表

#### sys_rate_limit — 传输限速规则表（05_rate_limit_tables.sql）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| rule_name | VARCHAR | 规则名称 |
| scope | TINYINT | 0-按用户 / 1-按角色 |
| target_id | BIGINT | 用户ID 或 角色ID |
| target_code | VARCHAR | 匹配标识 |
| target_name | VARCHAR | 展示名 |
| upload_speed_limit | INT | 上传限速 KB/s（0=不限） |
| download_speed_limit | INT | 下载限速 KB/s（0=不限） |
| enabled | TINYINT | 0-禁用 / 1-启用 |

#### sync_root — 同步根配置表（06_sync_tables.sql）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| user_id | BIGINT | 所属用户 ID |
| cloud_folder_node_id | BIGINT | 云端文件夹节点 ID（file_node.id） |
| local_path_hint | VARCHAR | 本地路径提示 |
| status | TINYINT | 0-启用 / 1-暂停 |
| sync_cursor | BIGINT | 上次同步游标（epoch ms） |

#### sys_jwt_secret — JWT 密钥表（09_jwt_secret.sql）

存储加密后的 JWT 签名密钥，通过 `STCLOUD_MASTER_KEY` 环境变量加解密。

## 实体关系

```
sys_tenant 1───* sys_user          (租户包含多用户)
sys_user   *───* sys_role           (通过 sys_user_role 关联)
sys_role   *───* sys_permission     (通过 sys_role_permission 关联)

file_node  1───* file_node          (parent_id 树形自引用)
file_node  1───* file_chunk         (一个文件多个分片)
file_node  1───* file_version       (一个文件多个历史版本)
file_node  1───* file_share         (一个文件可创建多个分享)

team_space 1───* team_member        (空间包含多成员)
team_space 1───* team_invite        (空间邀请链接)
team_space 1───* team_activity      (空间活动日志)
team_member *───1 sys_user          (成员关联用户)
team_space 1───* file_node          (space_id 关联空间内文件)

sync_root  *───1 file_node          (同步根绑定云端文件夹)
sync_root  *───1 sys_user           (同步根属于用户)

sys_user   1───* audit_log          (用户操作审计)
sys_rate_limit *───1 sys_user/role  (限速规则按用户或角色)
```

## 枚举定义

### NodeType（com.stcloud.common.enums）

| 值 | 名称 | 说明 |
|----|------|------|
| 0 | FOLDER | 文件夹 |
| 1 | FILE | 文件 |

### NodeStatus（com.stcloud.common.enums）

| 值 | 名称 | 说明 |
|----|------|------|
| 0 | NORMAL | 正常 |
| 1 | RECYCLED | 回收站 |
| 2 | DELETED | 已删除 |

### UploadStatus（com.stcloud.core.enums）

| 值 | 名称 | 说明 |
|----|------|------|
| 0 | 待上传 | PENDING |
| 1 | 上传中 | UPLOADING |
| 2 | 已完成 | COMPLETED |
| 3 | 失败 | FAILED |

### ResultCode 错误码分段（com.stcloud.common.response）

| 段 | 范围 | 领域 | 示例 |
|----|------|------|------|
| HTTP | 200-409 | 通用 | 200 SUCCESS, 401 UNAUTHORIZED, 409 CONFLICT |
| 业务 | 1000-1008 | 用户/认证 | 1001 USER_ALREADY_EXISTS, 1004 TOKEN_EXPIRED |
| 文件 | 2001-2009 | 文件/配额 | 2001 FILE_NOT_FOUND, 2007 STORAGE_QUOTA_EXCEEDED, 2009 CLOUD_CAPACITY_EXCEEDED |
| 分享 | 3001-3004 | 分享 | 3001 SHARE_NOT_FOUND, 3002 SHARE_EXPIRED, 3003 SHARE_PASSWORD_ERROR |
| 团队 | 4001-4008 | 团队 | 4001 TEAM_NOT_FOUND, 4004 TEAM_PERMISSION_DENIED, 4005 TEAM_INVITE_NOT_FOUND, 4006 TEAM_INVITE_EXPIRED, 4007 TEAM_LAST_ADMIN, 4008 TEAM_TRANSFER_TARGET_INVALID |
| 系统 | 5000-5002 | 系统 | 5000 INTERNAL_ERROR, 5002 STORAGE_SERVICE_ERROR |

## 权限码清单

| 权限码 | 名称 | 模块 |
|--------|------|------|
| file:upload | 文件上传 | file |
| file:download | 文件下载 | file |
| file:preview | 文件预览 | file |
| file:delete | 文件删除 | file |
| file:rename | 文件重命名 | file |
| file:move | 文件移动 | file |
| file:copy | 文件复制 | file |
| file:share | 文件分享 | file |
| share:create | 创建分享 | share |
| share:delete | 删除分享 | share |
| share:access | 访问分享 | share |
| team:create | 创建团队 | team |
| team:manage | 管理团队 | team |
| team:invite | 邀请成员 | team |
| search:file | 文件搜索 | search |
| admin:user:manage | 用户管理 | admin |
| admin:role:manage | 角色管理 | admin |
| admin:audit:view | 审计查看 | admin |
| admin:stats:view | 统计查看 | admin |
| admin:storage:manage | 存储管理 | admin |
| transfer:speed:limit | 传输限速 | transfer |

## 内置角色

| 角色编码 | 角色名称 | data_scope | 权限 |
|----------|----------|------------|------|
| admin | 系统管理员 | 3-全部 | 全部权限 |
| user | 普通用户 | 1-本人 | 基础权限（排除 admin:* 和 transfer:speed:limit） |

## 数据库迁移规范

- 脚本目录：`docker/mysql/init/`
- 命名规则：两位数字前缀 + 描述，如 `02_create_tables.sql`、`14_add_preview_permission.sql`
- 执行顺序：按文件名编号升序自动执行
- 首次启动：Docker 容器自动执行 `init/` 下全部脚本
- 已有数据库：手动按顺序执行增量脚本
- 所有表使用 `IF NOT EXISTS`，支持幂等执行
- 逻辑删除：统一使用 `deleted` 字段（0/1），由 MyBatis-Plus `@TableLogic` 管理