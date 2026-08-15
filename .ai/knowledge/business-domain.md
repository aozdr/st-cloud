# 业务领域

> 本文档描述 st-cloud 的核心领域对象与业务规则。

## 核心领域对象

| 对象 | 实体 | 所属模块 | 职责 |
|------|------|----------|------|
| 租户 Tenant | `SysTenant` | st-auth | SaaS 多租户隔离单元，含默认配额与云盘总容量 |
| 用户 User | `SysUser` | st-auth | 系统用户，含个人配额、登录信息 |
| 文件节点 FileNode | `FileNode` | st-core | 文件/文件夹统一抽象，树形结构 |
| 文件对象 FileObject | `FileObject` | st-core | 物理对象（同租户 MD5 去重，引用计数） |
| 文件分片 FileChunk | `FileChunk` | st-core | 分片上传的物理分片记录 |
| 文件版本 FileVersion | `FileVersion` | st-core | 文件历史版本快照 |
| 文件收藏 FileFavorite | `FileFavorite` | st-core | 用户收藏文件 |
| 文件分享 FileShare | `FileShare` | st-share | 分享链接，含提取码/有效期/权限 |
| 团队空间 TeamSpace | `TeamSpace` | st-team | 团队协作空间，独立配额 |
| 团队成员 TeamMember | `TeamMember` | st-team | 空间-用户关联，含角色 |
| 团队邀请 TeamInvite | `TeamInvite` | st-team | 空间邀请链接 |
| 团队活动 TeamActivity | `TeamActivity` | st-team | 空间级操作动态流 |
| 团队评论 TeamComment | `TeamComment` | st-team | 文件评论与 @提及 |
| 文件夹权限 TeamFolderPermission | `TeamFolderPermission` | st-team | 文件夹级授权（role/member） |
| 团队自定义角色 TeamRole | `TeamRole` | st-team | 空间级自定义角色（9 项权限矩阵） |
| 站内通知 Notification | `Notification` | st-team | 提及/邀请/变更通知 |
| 同步根 SyncRoot | `SyncRoot` | st-sync | 本地文件夹与云端文件夹的同步绑定 |
| 同步排除 SyncExclusion | `SyncExclusion` | st-sync | 选择性同步排除路径 |
| 同步冲突 SyncConflict | `SyncConflict` | st-sync | 冲突记录（pending/resolved） |
| 文件块 FileBlock | `FileBlock` | st-sync | 文件版本分块布局（块级增量） |
| 审计日志 AuditLog | `AuditLog` | st-admin | 操作审计记录 |
| 角色 SysRole | `SysRole` | st-auth | RBAC 角色，按租户隔离 |
| 权限 SysPermission | `SysPermission` | st-auth | RBAC 权限定义，全局系统级 |
| 限速规则 SysRateLimit | `SysRateLimit` | st-common | 传输限速规则，按用户或角色 |
| 事件日志 EventLog | `EventLog` | st-core | 事务性 Outbox 事件（FILE_INDEX/SYNC_CHANGE） |

## 业务规则

### 1. 文件上传（分片 + 秒传）

完整上传流程：

```
1. upload/check    前端计算 MD5，检查是否可秒传
                   -> file_object 存在同租户同 MD5 对象且可复用 -> 返回秒传成功
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

### 2. 秒传去重（FileObject 对象模型）

基于 **file_object 表**（同租户 MD5 唯一）+ 引用计数：

- 上传完成时 upsert `file_object`（`(tenant_id, md5)` 唯一），`file_node.object_id` 引用之
- 命中时：新 `file_node` 引用同一物理对象，`file_object.ref_count++`，不重复上传 S3
- 删除时：`ref_count--`，降为 0 才删除 S3 物理对象并逻辑删除 file_object
- 删除复用：回收站永久删除 / 同步清理接口均走同一引用归零逻辑

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
| 权限 | 单值 `permission`：0-查看 / 1-下载 / 2-上传 / 3-编辑（兼容展示） |
| 权限点 | `permissions` JSON 为权威：view/download/upload/delete/rename/move 等 |
| 下载开关 | `allow_download`：0-禁止 / 1-允许（下载 URL 与流式统一） |
| 下载限制 | `download_limit`，NULL=不限 |
| 访问统计 | `view_count`、`download_count` |
| 状态 | 0-已取消 / 1-有效 |

- 访问流程：输入分享码 -> 如私密则验证提取码 -> 校验有效期与下载限制 -> 按权限提供查看/下载/上传
- 分享访问接口公开（无需登录认证）
- **超权防线**：创建/更新分享时校验「分享权限 ⊆ 当前用户有效权限」，超权拒绝
- **下载与流式一致**：`allow_download=0` 时下载接口与流式预览接口同时拒绝

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
- **文件夹权限**（P2）：`team_folder_permission` 按 `(folder, subject_type, subject_id)` 授权；
  `permissions` JSON（9 权限点：view/upload/download/delete/rename/move/share/manage_members/manage_settings）
  为权威，单值 `permission` 保留兼容；权限继承沿文件夹树向上取最大集（具体见 FolderPermissionService 规则）
- **评论与提及**（P2）：`team_comment` 支持顶级/嵌套评论，`mentions` 逗号分隔 @提及用户，
  提及触发 `notification`（type=MENTION）；评论/删除/编辑均有权限校验
- **通知**：`notification` 表按接收者分页查询，类型 MENTION / TEAM_INVITE / FILE_CHANGE / MEMBER_CHANGE；
  支持未读数统计与单条/全部已读
- **空间统计**（P2）：按文件类型分类、成员活跃度排行、操作统计，支持按时间过滤
- **定时任务**：FileLockExpireTask（每小时清理过期文件锁）、ExternalMemberExpireTask（每小时清理过期外部成员）

### 8. 文件同步

- **同步根**（SyncRoot）：绑定一个云端文件夹节点（`cloud_folder_node_id`）与本地路径
- **增量同步**：基于 `sync_change_log.id`（自增单调游标，26 号脚本起不再用时间戳），客户端通过
  `delta?since={id}` 获取变更；服务端按同步根文件夹范围过滤（含 oldPath）
- **同步状态**：0-启用 / 1-暂停
- **实时推送**：变更日志写入后服务端经 `/api/sync/ws` WebSocket 向所有者在线会话推送通知，
  客户端立即拉取 delta；断线时 30s 定时轮询兜底
- **块级增量**（V2+）：`file_block` 持久化每个文件版本的分块布局（5MB/块），
  `/api/sync/block-check` 对比可复用块，`/api/sync/block-upload` 通过 UploadPartCopy 复制未变块，
  仅传输变化块，避免整文件重传
- **冲突策略**：keep_both / latest_wins / server_wins / local_wins（`sync_root.conflict_strategy`），
  冲突记录落 `sync_conflict`（pending/resolved）
- **选择性同步**：`sync_exclusion` 排除同步根下子路径
- 实际同步引擎在桌面端实现（`st-desktop/src/sync-engine.ts`），详见 `.ai/knowledge/sync-engine-v2.md`

### 8.1 可靠事件（Outbox）

- 文件变更（上传/删除/移动/重命名）在业务事务内写 `event_log`（Outbox），事务提交后投递 RocketMQ
  （topic=FILE_INDEX / SYNC_CHANGE），投递失败由 `EventRetryTask` 定时重投
- 消费者按 `event_log.id`（eventLogId）幂等，重复投递不产生重复索引/重复变更日志
- RocketMQ 未配置时退化为同进程 Spring 事件（本地兜底），链路等价

### 8.2 文件收藏

- `file_favorite` 按 `(user_id, file_node_id)` 唯一，切换收藏（toggle）为幂等操作
- 收藏列表过滤回收站文件（status=0）；提供全量列表（首页）/轻量 ID 列表/分页三种查询
- 取消收藏为逻辑删除，重复收藏同节点不会产生重复记录

### 8.3 在线文档编辑（OnlyOffice 社区版）

- 支持格式：docx/xlsx/pptx 在线编辑；只读预览保持现有组件（D2：本迭代不统一迁移）
- 编辑权限：个人 owner / 团队文件夹权限含 upload / 分享 permissions 含 upload；其余只读或拒绝
- 保存：OnlyOffice 自动保存（status=2）覆盖 file_node 不生成版本；关闭保存（status=6/7）
  覆盖 + 生成 file_version(source=1)，编辑器版本上限 20 条自动裁剪（上传覆盖版本 source=0 不受影响）
- 并发保护：编辑标记（Redis Set，多人协同不互斥）存在时，删除/移动/重命名/覆盖上传/版本恢复
  被拦截（FILE_EDITING）；保存回调按文件串行化 + 幂等键防重复落盘
- 安全：回调 JWT 验签（STCLOUD_ONLYOFFICE_SECRET）+ key/status 一致性 + 文件归属复核；
  回调下载主机白名单 + 大小上限，防 SSRF 与投毒
- 部署：docker-compose 内置 onlyoffice 容器；生产配置 `stcloud.onlyoffice.public-base-url` 为后端可达地址
- 字体：compose 只读挂载宿主机 `C:/Windows/Fonts` 到容器 `/usr/share/fonts/winfonts`，
  docx 中文按宋体/微软雅黑/黑体/楷体/仿宋/等线原字体渲染（与 Word 一致）；
  生产 Linux 主机需自行安装对应字体（可复制到 `docker/onlyoffice/fonts` 挂载或装 Noto CJK）
- 格式转换：`POST /api/file/{nodeId}/convert`（Word doc/docx <-> PDF），调 OnlyOffice
  `ConvertService.ashx`（XML 响应，EndConvert 轮询），转换结果走 `NewFileService.createCompletedFile`
  落库（重名自动序号/配额/去重/事件与新建一致）；前端右键菜单「转换为 PDF/Word」+ 文件名可编辑
  对话框（默认「原文件名-转换.目标后缀」）

### 9. 文件预览

- 支持格式：
  - Office（docx/xlsx/pptx）：**OnlyOffice 只读查看**（`/file/{nodeId}/editor?mode=view`，
    全屏页 + 顶部返回按钮，排版/图片与 Word 一致）；分享场景无编辑器配置，回退 docx-preview
  - PDF：pdf.js（连续滚动 + 左侧缩略图 + 适应宽度 + 缩放，worker 独立资源打包）
  - 图片 / 视频（Plyr）/ 音频 / 文本
- OnlyOffice 查看模式：后端 `/api/file/{nodeId}/editor/config?mode=view` 强制 `canEdit=false`，
  以 `mode=view` 打开（只读不占编辑位）；编辑入口仍走默认 edit 模式
- 预览流程：`PreviewController` -> `PreviewService` -> 生成/获取预览缓存（preview-bucket）
- 缩略图：`FileNode.thumbnailPath` 存储缩略图 S3 路径

### 10. 全文搜索

- **索引**：`FileIndexEventListener` 监听 `FileIndexEvent`，异步写入 Elasticsearch（索引名 `file_content`）
- **索引内容**：文件名、路径、文件类型、大小、时间等元数据 + **文档正文**（附件内容）
- **内容提取**（st-search `SearchServiceImpl`）：可索引类型 `txt/pdf/doc/docx/xls/xlsx/ppt/pptx`（≤20MB），下载 S3 对象 → Base64 → ES Ingest Pipeline（`file-content-pipeline`，Tika attachment 插件）提取文本；文件夹、图片/视频/音频、超大文件只索引元数据
- **中文分词**：IK 分词器（索引 `ik_max_word` / 搜索 `ik_smart`），`fileName` 与 `attachment.content` 叠加 `standard`/`ngram` 子字段兜底英文子串召回
- **搜索**：`/api/search?keyword=...` 匹配文件名 + 正文（`attachment.content`），返回高亮片段；支持按 nodeType/suffix/大小/日期过滤；按 `ownerId` 权限过滤
- **重建**：`/api/search/reindex` 手动触发全量重建
- **SearchIndexInitializer**：启动时幂等创建 pipeline + 索引；旧索引自动增量补子字段映射

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

### 17. 新建文件

- 支持新建 txt/docx/xlsx/pptx 空白文件（默认命名「新建文本文档/新建文档/新建表格/新建演示」+ 重名序号）
- 服务端内置 OOXML 模板，新建即完成（不经过分片上传）；Office 文件可进 OnlyOffice 编辑
- PPT 模板必须用 OnlyOffice 官方空白模板（56 部件、含 theme1.xml）——自制极简模板缺少主题/备注母版，
  OnlyOffice 打开时在 sdkjs/slide 报 `createDuplicate` undefined（20260815 实测）；docx/xlsx 自制极简模板可用
- 权限：个人 owner / 团队 upload；配额按模板字节计入（原子扣减，失败无半成品）
- 新建发布 FileIndexEvent + SyncChangeEvent(CREATE)，桌面端自动同步
