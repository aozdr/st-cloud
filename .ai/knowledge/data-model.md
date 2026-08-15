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
| object_id | BIGINT | 文件对象 ID（file_object 去重引用，文件夹/未完成上传为 NULL） |
| status | TINYINT | 0-正常 / 1-回收站 / 2-已删除 |
| upload_status | TINYINT | 0-待上传 / 1-上传中 / 2-已完成 / 3-失败 / 4-合并中 / 5-已删除 |
| uploader_id | BIGINT | 上传者 ID |
| owner_id | BIGINT | 所有者 ID |
| space_id | BIGINT | 团队空间 ID（NULL=个人空间） |
| ref_count | INT | 物理对象引用计数 |
| version | INT | 乐观锁版本号（`@Version`） |
| thumbnail_path | VARCHAR | 缩略图 S3 路径 |
| hidden | TINYINT | 是否隐藏：0-正常 / 1-隐藏（16 号脚本） |
| locked_by | BIGINT | 文件锁定人 ID，NULL=未锁定（23 号脚本） |
| locked_at | DATETIME | 锁定时间 |
| lock_expire_at | DATETIME | 锁过期时间，NULL=永久 |

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
| password | VARCHAR | 提取码（新写入为 BCrypt，历史数据可能为明文） |
| expire_at | DATETIME | 过期时间（NULL=永久） |
| permission | TINYINT | 0-查看 / 1-下载 / 2-上传 / 3-编辑 |
| permissions | VARCHAR(500) | 分享权限点 JSON：`{"view":true,"download":true,...}`（权威字段，35 号脚本） |
| allow_download | TINYINT | 下载/流式统一开关：0-禁止 / 1-允许（33 号脚本） |
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
| is_pinned | TINYINT | 是否置顶：0-否 / 1-是（22 号脚本） |
| member_type | TINYINT | 0-内部 / 1-外部协作者（25 号脚本） |
| expire_at | DATETIME | 外部协作者有效期，NULL=永久 |

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
| id | BIGINT | 主键 |
| tenant_id | BIGINT | 租户 ID |
| user_id | BIGINT | 用户 ID |
| role_id | BIGINT | 角色 ID |

#### sys_role_permission — 角色-权限关联表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| tenant_id | BIGINT | 租户 ID |
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
| sync_cursor | BIGINT | 上次同步游标（sync_change_log.id，26 号脚本起由 timestamp 归零迁移） |
| conflict_strategy | VARCHAR(16) | 冲突策略：keep_both/latest_wins/server_wins/local_wins（默认 keep_both） |
| last_sync_at | DATETIME | 最后同步时间 |

#### sync_change_log — 同步变更日志表（26_sync_change_log.sql）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 自增主键，即同步游标 |
| tenant_id / user_id | BIGINT | 租户 ID / 文件所有者 ID |
| file_node_id | BIGINT | 文件节点 ID |
| change_type | VARCHAR(16) | CREATE / UPDATE / MOVE / RENAME / DELETE |
| path | VARCHAR(1024) | 变更后完整路径 |
| old_path | VARCHAR(1024) | 变更前完整路径（MOVE / RENAME） |
| name / node_type | VARCHAR / TINYINT | 节点名 / 类型（0-文件夹 1-文件） |
| file_md5 / file_size | VARCHAR / BIGINT | 内容指纹 / 大小 |
| event_log_id | BIGINT | Outbox 事件日志 ID（MQ 幂等键，本地兜底为 NULL） |
| created_at | DATETIME | 默认 CURRENT_TIMESTAMP |

> 索引：`idx_user_id (user_id, id)`。增量查询按 `id > since` 升序分页。服务端对 `oldPath == path` 的无意义 MOVE/RENAME 在 delta 层过滤（20260815 起）。

> 客户端（st-desktop，sql.js）另有 `sync_config`（cursor / sync_version / last_sync_at，全量门控）与 `sync_state`（合并语义，`local_mtime` 不可被局部更新擦除），详见 `.ai/knowledge/sync-engine-v2.md`。

#### file_block — 文件块布局表（32_file_block.sql，st-sync 块级增量同步）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键（自增） |
| tenant_id | BIGINT | 租户 ID |
| file_node_id | BIGINT | 文件节点 ID |
| version | INT | 文件版本号（对齐 file_node.version） |
| block_index | INT | 块序号（0-based） |
| block_md5 | VARCHAR(64) | 块 MD5 |
| block_size | BIGINT | 块大小（字节，固定 5MB，最后一块除外） |
| storage_path | VARCHAR(512) | 块所属文件对象的 S3 存储路径 |
| created_at | DATETIME | 创建时间（默认 CURRENT_TIMESTAMP） |

> 索引：`idx_node_ver (file_node_id, version, block_index)`。块存储路径 = 整文件对象路径（不单独存块对象），复用通过 UploadPartCopy 从整对象按字节范围复制。

#### file_favorite — 文件收藏表（15_file_favorite.sql）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| tenant_id / user_id | BIGINT | 租户 ID / 收藏者用户 ID |
| file_node_id | BIGINT | 被收藏的文件节点 ID |
| created_at / updated_at / deleted | DATETIME / TINYINT | 审计与逻辑删除 |

> 唯一键：`uk_user_node (user_id, file_node_id, deleted)`，同用户对同一节点仅一条有效收藏。

#### file_object — 文件对象表（28_file_object.sql）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| tenant_id | BIGINT | 租户 ID |
| md5 | VARCHAR(64) | 文件 MD5 |
| size | BIGINT | 文件大小（字节） |
| storage_path | VARCHAR(500) | 对象存储路径 |
| ref_count | INT | 引用计数（同租户同 md5 的 file_node 数） |
| status | TINYINT | 0-正常 / 1-已删除 |
| created_at / updated_at / deleted | DATETIME / TINYINT | 审计与逻辑删除 |

> 唯一键：`uk_tenant_md5 (tenant_id, md5)`。物理对象按「租户 + MD5」去重，`file_node.object_id` 引用本表。

#### event_log — 事件 Outbox 表（29_event_log.sql）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键（雪花，兼作消费者幂等键） |
| tenant_id | BIGINT | 租户 ID |
| event_type | VARCHAR(32) | 事件类型：FILE_INDEX / SYNC_CHANGE |
| payload | TEXT | 事件负载 JSON（EventMessage） |
| status | TINYINT | 0-待投递 / 1-已投递 / 2-投递失败 |
| retry_count | INT | 重试次数 |
| created_at / processed_at / updated_at | DATETIME | 创建/投递成功/更新时间 |
| deleted | TINYINT | 逻辑删除 |

> 事务性 Outbox：业务事务内写入，事务回滚即无事件；投递失败由 `EventRetryTask` 定时重投；消费者按 `event_log.id`（消息内 `eventLogId`）幂等。

### 团队协作表（17~25 号脚本，st-team）

#### team_invite — 空间邀请链接表（17_team_invite.sql）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| space_id | BIGINT | 团队空间 ID |
| invite_code | VARCHAR(32) | 邀请码（32 位随机串，唯一） |
| role | TINYINT | 默认角色：0-管理员 / 1-编辑者 / 2-查看者 |
| created_by | BIGINT | 创建者 ID |
| expire_at | DATETIME | 过期时间，NULL=永久 |
| status | TINYINT | 0-已撤销 / 1-有效 |

#### team_activity — 空间活动日志表（18_team_activity.sql）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| space_id | BIGINT | 团队空间 ID |
| user_id / username / nickname | BIGINT / VARCHAR | 操作人冗余信息 |
| action | VARCHAR(50) | FILE_UPLOAD / FILE_DELETE / MEMBER_JOIN / SPACE_UPDATE 等 |
| target_type / target_id / target_name | VARCHAR / BIGINT / VARCHAR | 目标信息 |
| detail | TEXT | 操作详情 JSON |
| created_at | DATETIME | 创建时间（索引 idx_space_created） |

> 活动日志异步写入，保留 90 天。

#### notification — 站内通知表（19_notification.sql）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| user_id | BIGINT | 接收者 ID |
| type | VARCHAR(20) | MENTION / TEAM_INVITE / FILE_CHANGE / MEMBER_CHANGE |
| title / content | VARCHAR | 标题与正文 |
| ref_type / ref_id | VARCHAR / BIGINT | 关联类型（team/comment/file）与 ID |
| read | TINYINT | 0-未读 / 1-已读 |

#### team_comment — 团队文件评论表（20_team_comment.sql）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| space_id / node_id | BIGINT | 空间 ID / 文件节点 ID |
| user_id | BIGINT | 评论人 ID |
| content | TEXT | 评论内容 |
| parent_id | BIGINT | 父评论 ID（NULL=顶级） |
| mentions | VARCHAR(500) | @提及用户 ID 列表（逗号分隔），触发 MENTION 通知 |

#### team_folder_permission — 团队文件夹权限表（21 + 34 号脚本）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| space_id / folder_node_id | BIGINT | 空间 ID / 文件夹节点 ID |
| subject_type | VARCHAR(10) | 授权对象：role / member |
| subject_id | BIGINT | 角色值或用户 ID |
| permission | TINYINT | -1-无权限 / 0-管理 / 1-编辑 / 2-查看（兼容字段） |
| permissions | VARCHAR(500) | 权限点 JSON：`{"view":true,"upload":true,...}`（权威字段，34 号脚本） |

> 权限点覆盖 9 项：view / upload / download / delete / rename / move / share / manage_members / manage_settings。

#### team_role — 团队自定义角色表（24_team_role.sql）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 角色 ID（>=100 为自定义；0/1/2 为预设） |
| space_id | BIGINT | 团队空间 ID |
| name | VARCHAR(50) | 角色名称 |
| permissions | VARCHAR(500) | 权限 JSON（9 项权限矩阵） |
| status | TINYINT | 0-停用 / 1-启用 |

#### team_external_config — 空间外部协作配置表（25_team_external.sql）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| space_id | BIGINT | 团队空间 ID（唯一） |
| allow_external | TINYINT | 是否允许外部协作者：0-否 / 1-是 |

#### sync_exclusion — 同步排除路径表（27_sync_exclusion_conflict.sql）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| sync_root_id | BIGINT | 同步根 ID |
| user_id | BIGINT | 用户 ID |
| relative_path | VARCHAR(1024) | 相对同步根的路径（以 / 开头） |

> 唯一键：`uk_root_path (sync_root_id, relative_path(765), deleted)`，实现选择性同步。

#### sync_conflict — 同步冲突记录表（27_sync_exclusion_conflict.sql）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| sync_root_id | BIGINT | 同步根 ID |
| relative_path | VARCHAR(1024) | 相对同步根的路径 |
| local_md5 / cloud_md5 | VARCHAR(64) | 本地/云端 MD5 |
| status | VARCHAR(16) | pending / resolved |
| resolution | VARCHAR(32) | 解决方式：keep_both / server_wins / local_wins |

#### sys_jwt_secret — JWT 密钥表（09_jwt_secret.sql）

存储 AES-GCM 加密后的 JWT 签名密钥（`secret_ciphertext` + `secret_iv`），通过 `STCLOUD_MASTER_KEY` 环境变量加解密，密钥明文从不落盘。

## 实体关系

```
sys_tenant 1───* sys_user          (租户包含多用户)
sys_user   *───* sys_role           (通过 sys_user_role 关联)
sys_role   *───* sys_permission     (通过 sys_role_permission 关联)

file_node  1───* file_node          (parent_id 树形自引用)
file_node  1───* file_chunk         (一个文件多个分片)
file_node  1───* file_version       (一个文件多个历史版本)
file_node  1───* file_block         (一个文件每个版本的分块布局)
file_node  1───* file_share         (一个文件可创建多个分享)
file_node  *───1 file_object        (file_node.object_id 引用物理对象)
file_favorite *───1 file_node       (收藏指向文件节点)
file_favorite *───1 sys_user        (收藏属于用户)

team_space 1───* team_member        (空间包含多成员)
team_space 1───* team_invite        (空间邀请链接)
team_space 1───* team_activity      (空间活动日志)
team_space 1───* team_comment       (空间文件评论)
team_space 1───* team_role          (空间自定义角色)
team_space 1───1 team_external_config (空间外部协作配置)
team_space 1───* team_folder_permission (文件夹权限)
team_member *───1 sys_user          (成员关联用户)
team_space 1───* file_node          (space_id 关联空间内文件)

sync_root  *───1 file_node          (同步根绑定云端文件夹)
sync_root  *───1 sys_user           (同步根属于用户)
sync_root  1───* sync_change_log    (同步根范围内的变更日志/游标)
sync_root  1───* sync_exclusion     (同步根排除路径)
sync_root  1───* sync_conflict      (同步根冲突记录)
file_node  1───* sync_change_log    (文件节点变更日志)
event_log  1───1 sync_change_log    (event_log_id 幂等关联，可空)

sys_user   1───* audit_log          (用户操作审计)
sys_user   1───* notification       (用户站内通知)
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
| 4 | 合并中 | MERGING |
| 5 | 已删除 | DELETED |

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

### schema_version - Schema 版本记录表（31_schema_version.sql）

记录每次迭代的数据库版本号与执行的 SQL 文件清单，防止迁移遗漏。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| version_tag | VARCHAR(32) | 版本号，格式 `YYYYMMDD.N`，唯一 |
| iteration_name | VARCHAR(255) | 迭代名称/主题 |
| applied_sql_files | TEXT | 本次执行的 SQL 文件清单，逗号分隔 |
| applied_at | DATETIME | 执行时间 |
| applied_by | VARCHAR(64) | 执行人/Agent 标识 |
| notes | TEXT | 备注 |

> 每次迭代涉及 DB 变更须 INSERT 版本记录。详见 AGENTS.md「数据库版本管理」。
