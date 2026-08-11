# 业务领域

> 本文档描述 st-cloud 的核心领域对象与业务规则。

## 核心领域对象

| 对象 | 实体 | 所属模块 | 职责 |
|------|------|----------|------|
| 租户 Tenant | `SysTenant` | st-auth | SaaS 多租户隔离单元，含默认配额与云盘总容量 |
| 用户 User | `SysUser` | st-auth | 系统用户，含个人配额、登录信息 |
| 文件节点 FileNode | `FileNode` | st-core | 文件/文件夹统一抽象，树形结构 |
| 文件分片 FileChunk | `FileChunk` | st-core | 分片上传的物理分片记录 |
| 文件版本 FileVersion | `FileVersion` | st-core | 文件历史版本快照 |
| 文件分享 FileShare | `FileShare` | st-share | 分享链接，含提取码/有效期/权限 |
| 团队空间 TeamSpace | `TeamSpace` | st-team | 团队协作空间，独立配额 |
| 团队成员 TeamMember | `TeamMember` | st-team | 空间-用户关联，含角色 |
| 同步根 SyncRoot | `SyncRoot` | st-sync | 本地文件夹与云端文件夹的同步绑定 |
| 审计日志 AuditLog | `AuditLog` | st-admin | 操作审计记录 |
| 角色 SysRole | `SysRole` | st-auth | RBAC 角色，按租户隔离 |
| 权限 SysPermission | `SysPermission` | st-auth | RBAC 权限定义，全局系统级 |
| 限速规则 SysRateLimit | `SysRateLimit` | st-common | 传输限速规则，按用户或角色 |

## 业务规则

### 1. 文件上传（分片 + 秒传）

完整上传流程：

```
1. upload/check    前端计算 MD5，检查是否可秒传
                   -> MD5 已存在且 refCount 可复用 -> 返回秒传成功
                   -> 不存在 -> 返回需要上传

2. upload/init     初始化分片上传
                   -> 创建 file_node (uploadStatus=0 待上传)
                   -> 返回 uploadId、分片大小策略

3. chunk-url       获取分片预签名上传 URL (S3 presigned URL)
                   -> 前端直传分片到 S3 chunk-bucket

4. chunk-confirm   确认分片上传完成
                   -> 记录 file_chunk (status=已完成)

5. upload/merge    合并所有分片
                   -> S3 合并分片为完整对象到主 bucket
                   -> 更新 file_node (uploadStatus=2 已完成)
                   -> 发布 FileIndexEvent(INDEX) 触发搜索索引
                   -> 配额校验与扣减

6. upload/abort    取消上传 (DELETE)
                   -> 清理已上传分片
                   -> 删除 file_node 记录
```

- **断点续传**：通过 `upload/status` 查询已上传分片列表，前端跳过已完成分片
- **门控限速**：`UserTransferLimiter` 基于 Redis 实现滑动窗口限速
- **分片原文件大小**：`file_chunk.original_size` 记录原始大小，用于按差值计费

### 2. 秒传去重

基于 MD5 内容寻址：

- 上传前 `upload/check` 查询是否存在相同 `file_md5` 的正常文件
- 命中时：创建新的 `file_node` 指向同一 `storage_path`，`refCount++`，不重复存储
- 删除时：`refCount--`，降为 0 时才删除 S3 物理对象

### 3. 三重配额校验

所有写入路径（上传、秒传、复制、版本恢复）均校验三层配额：

| 层级 | 字段 | 说明 |
|------|------|------|
| 个人配额 | `sys_user.storage_quota` | 单用户文件总量上限 |
| 团队配额 | `team_space.storage_quota` | 单团队空间文件总量上限 |
| 云盘总容量 | `sys_tenant.cloud_total_capacity` | 个人与团队共享的物理存储总上限 |

配额以增量方式记账：上传加、删除减、版本恢复按差值调整。回收站永久删除时退还配额。

### 4. 文件版本管理

- 每次覆盖上传同一文件时，旧版本存入 `file_version` 表
- `file_version` 记录：版本号、文件大小、MD5、存储路径、修改人
- 支持版本列表查看与一键恢复
- 版本恢复按差值调整配额

### 5. 回收站

- 删除文件 -> `file_node.status = 1`（回收站），逻辑保留 30 天
- 回收站操作：恢复（status=0）、永久删除（status=2 + 删除 S3 对象 + 退还配额）、清空
- `RecycleBinPurgeTask`：定时任务自动清理过期回收站文件

### 6. 文件分享

| 属性 | 说明 |
|------|------|
| 分享码 | `share_code`，短链唯一标识 |
| 分享类型 | 0-公开 / 1-私密（提取码） |
| 提取码 | `password`，BCrypt 加密存储 |
| 有效期 | `expire_at`，NULL=永久 |
| 权限 | 0-查看 / 1-下载 / 2-上传 / 3-编辑 |
| 下载限制 | `download_limit`，NULL=不限 |
| 访问统计 | `view_count`、`download_count` |
| 状态 | 0-已取消 / 1-有效 |

- 访问流程：输入分享码 -> 如私密则验证提取码 -> 校验有效期与下载限制 -> 按权限提供查看/下载/上传
- 分享访问接口公开（无需登录认证）

### 7. 团队协作

- **团队空间**（TeamSpace）：独立存储配额，由创建者拥有，支持所有权移交
- **邀请链接**（TeamInvite）：管理员生成邀请码（32位随机串），支持角色/有效期/撤销，被邀请人直接加入
- **活动日志**（TeamActivity）：空间级操作动态流，异步写入，记录文件/成员/空间操作，保留90天
- **活跃追踪**：成员访问空间时更新 `last_active_at`，Redis 5分钟去重
- **成员角色**：

| 角色 | code | 权限 |
|------|------|------|
| 管理员 | 0 | 管理成员、管理文件、修改设置 |
| 编辑者 | 1 | 上传/下载/删除/重命名/移动/复制文件 |
| 查看者 | 2 | 仅查看和下载 |

- 团队空间内的文件操作复用 st-core 的 FileService，通过 `space_id` 区分个人/团队空间
- `space_id = NULL` 表示个人文件，`space_id > 0` 表示团队空间文件
- **文件锁定**（P2）：`file_node.locked_by/locked_at/lock_expire_at` 标记锁定状态；锁定后非锁定人无法创建子文件夹/删除/重命名/移动（checkNotLocked 拦截）；锁定人自己可操作；hours=0 为永久锁，否则按小时过期
- **自定义角色**（P2）：`team_role` 表存储空间级自定义角色（role >= 100），含 9 项权限矩阵；预设角色 0/1/2 保留，删除角色前检查 team_member 引用
- **外部协作者**（P2）：`team_member.member_type`（0=内部/1=外部）+ `expire_at` 标记外部成员有效期；`team_external_config` 表控制开关
- **空间统计**（P2）：按文件类型分类、成员活跃度排行、操作统计，支持按时间过滤
- **定时任务**：FileLockExpireTask（每小时清理过期文件锁）、ExternalMemberExpireTask（每小时清理过期外部成员）

### 8. 文件同步

- **同步根**（SyncRoot）：绑定一个云端文件夹节点（`cloud_folder_node_id`）与本地路径
- **增量同步**：基于 `sync_cursor`（epoch ms 游标），客户端通过 `delta` 接口获取变更
- **同步状态**：0-启用 / 1-暂停
- 实际同步引擎在桌面端实现（`st-desktop/src/sync-engine.ts`），服务端提供 delta 接口

### 9. 文件预览

- 支持格式：Office 文档（docx-preview）、PDF、图片、视频（Plyr）、音频
- 预览流程：`PreviewController` -> `PreviewService` -> 生成/获取预览缓存（preview-bucket）
- 缩略图：`FileNode.thumbnailPath` 存储缩略图 S3 路径

### 10. 全文搜索

- **索引**：`FileIndexEventListener` 监听 `FileIndexEvent`，异步写入 Elasticsearch
- **索引内容**：文件名、路径、文件类型、元数据
- **重建**：`/api/search/reindex` 手动触发全量重建
- **SearchIndexInitializer**：启动时检查并创建 ES 索引

### 11. 传输限速

- `SysRateLimit` 规则：按用户或角色配置上传/下载限速（KB/s）
- `SpeedLimitService` + `UserTransferLimiter`：基于 Redis 的滑动窗口限速
- 限速在服务端门控（分片上传 URL 生成、下载流传输时）

### 12. 审计日志

- `@Auditable` 注解标记需要审计的接口
- `AuditAspect` AOP 切面自动记录：用户、操作、目标类型/ID/名称、详情、IP、UserAgent
- 审计日志查询：`/api/admin/audit/list`
### 13. 在线解压

- 支持 ZIP 格式压缩包在线浏览与解压
- `ArchiveController` 提供浏览内容列表和一键解压接口
- 使用 JDK 内置 `ZipInputStream`，无需额外依赖
- 解压时自动创建嵌套文件夹结构，文件上传到 S3 并创建文件节点

### 14. 文件隐藏

- 文件/文件夹可标记为隐藏状态（`file_node.hidden = 1`）
- 隐藏文件从正常文件列表中过滤，仅在「隐藏文件」页面可见
- 右键菜单可切换隐藏状态，隐藏文件页面可取消隐藏

### 15. 重复文件检测与清理

- 按 `file_md5` 分组查询重复文件（count > 1）
- 返回每组重复文件的 MD5、数量、总占用大小、样本文件名
- **清理规则**：`POST /api/file/duplicates/cleanup?md5={md5}`
  - 查询同 MD5 的所有文件（按 `created_at ASC` 排序）
  - 保留创建时间最早的文件
  - 其余移入回收站（非永久删除，可恢复）
- **历史版本保护**：有历史版本的文件（`file_version` 表存在关联记录）跳过不删除
  - 清理时逐个检查 `file_node` 是否有历史版本
  - 如果所有文件都有历史版本，该组不可清理
  - 如果部分有历史版本，有版本的跳过，从无版本的文件中保留最早的

### 16. 存储空间分析

- 按文件类型（图片/视频/文档/音乐/压缩包/其他）分组统计存储占用
- 首页展示饼图 + 类型列表，帮助用户了解空间分布
