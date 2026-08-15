# API 参考

> 本文档列出 st-cloud 全部 REST API 端点。API 文档（Knife4j）：`http://localhost:8080/doc.html`

## 全局约定

### 请求

- 所有 API 以 `/api` 为前缀
- 认证头：`Authorization: Bearer <accessToken>`
- 请求体：JSON（`Content-Type: application/json`）
- 文件上传：部分接口使用 S3 预签名 URL 直传，非 multipart

### 响应

统一 `Result<T>` 封装：

```json
{
  "code": 200,
  "message": "成功",
  "data": { ... }
}
```

错误时 `data` 为 null，`code` 为 `ResultCode` 业务错误码，`message` 为错误描述。详见 [data-model.md](./data-model.md) 错误码分段。

### 认证机制

- **登录**：`POST /api/auth/login` -> 返回 accessToken + refreshToken
- **刷新**：`POST /api/auth/refresh` -> 用 refreshToken 换取新 accessToken
- **密钥管理**：JWT 签名密钥存储在 `sys_jwt_secret` 表，通过环境变量 `STCLOUD_MASTER_KEY` 加解密，不入源码
- **Token 有效期**：accessToken 7d，refreshToken 30d

## 公开接口（无需认证）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 用户注册 |
| POST | `/api/auth/login` | 用户登录 |
| POST | `/api/auth/refresh` | 刷新 Token |
| GET | `/api/auth/ping` | 连通性探测 |
| POST | `/api/share/access/access` | 分享访问（验证提取码） |
| GET | `/api/share/access/download/{shareCode}` | 分享文件下载 |
| GET | `/api/share/access/list` | 分享文件列表 |
| GET | `/api/share/access/stream/{shareCode}` | 分享文件流式预览 |
| GET | `/doc.html` | Knife4j API 文档 |
| GET | `/actuator/**` | 健康检查 |

## 认证模块（st-auth）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 注册 |
| POST | `/api/auth/login` | 登录 |
| POST | `/api/auth/refresh` | 刷新 Token |
| GET | `/api/auth/me` | 获取当前用户信息 |
| POST | `/api/auth/logout` | 登出 |
| GET | `/api/auth/ping` | 连通性探测 |

## 文件模块（st-core）

### 文件管理（FileController）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/file/folder` | 创建文件夹 |
| POST | `/api/file/new` | 新建空白文件（个人；type=txt/docx/xlsx/pptx） |
| GET | `/api/file/by-path` | 按路径获取节点 |
| GET | `/api/file/list` | 文件列表 |
| GET | `/api/file/{nodeId}` | 获取节点详情 |
| PUT | `/api/file/{nodeId}/rename` | 重命名 |
| POST | `/api/file/move` | 移动 |
| POST | `/api/file/copy` | 复制 |
| POST | `/api/file/delete` | 删除（移入回收站） |
| GET | `/api/file/tree` | 文件树 |
| GET | `/api/file/storage` | 存储信息 |

### 上传流程

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/file/upload/check` | 秒传检查（MD5 去重） |
| POST | `/api/file/upload/init` | 初始化分片上传 |
| GET | `/api/file/upload/status` | 查询上传状态（断点续传） |
| POST | `/api/file/upload/merge` | 合并分片 |
| DELETE | `/api/file/upload/abort` | 取消上传 |
| GET | `/api/file/upload/chunk-url` | 获取分片预签名上传 URL |
| POST | `/api/file/upload/chunk-confirm` | 确认分片上传完成 |
| POST | `/api/file/upload/relay-chunk` | 中转上传：接收一个小块（限速 pacing，限速低于分片下限时启用） |
| POST | `/api/file/upload/relay-finalize` | 中转上传：完成末片并合并 |

#### UploadInitResponse（分片上传初始化响应，st-core UploadInitResponse）

| 字段 | 类型 | 说明 |
|------|------|------|
| uploadId | String | 上传唯一标识 |
| s3UploadId | String | S3 multipart upload ID |
| fileId | Long | 文件节点 ID |
| presignedUrls | List&lt;String&gt; | 各分片预签名 URL |
| transferMode | String | 传输模式：`direct`=预签名直传，`relay`=服务端中转 |
| relayChunkSize | Long | 中转模式小块大小（字节），relay 模式生效 |
| relayRateKb | Long | 中转模式实际生效限速（KB/s），relay 模式生效（服务端与客户端取最严格值） |

> 当限速低于分片下限时，`transferMode` 自动切换为 `relay`（服务端中转）。

### 下载

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/file/{nodeId}/stream` | 文件流式下载 |
| POST | `/api/file/download/zip` | 批量打包下载 |
| POST | `/api/file/{nodeId}/download-token` | 获取下载令牌 |

### 版本管理（VersionController）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/file/{nodeId}/versions` | 版本历史列表 |
| POST | `/api/file/{nodeId}/versions/{versionId}/restore` | 恢复指定版本 |

### 在线编辑（OnlyOffice）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/file/{nodeId}/editor/config` | 获取个人文件编辑配置（owner/租户管理员） |
| GET | `/api/team/{spaceId}/files/{nodeId}/editor/config` | 获取团队文件编辑配置（upload 权限点） |
| GET | `/api/share/access/editor-config/{shareCode}` | 获取分享文件编辑配置（分享 upload 权限点） |
| POST | `/api/file/{nodeId}/editor/callback` | OnlyOffice 保存/关闭回调（匿名 + JWT 验签） |

> 仅支持 docx/xlsx/pptx 在线编辑；只读预览保持现有组件（D2 决策）。保存行为见 business-domain。
> config 响应 `editorUrl` 为 OnlyOffice 基础地址（浏览器可达），前端拼接 `/web-apps/apps/api/documents/api.js` 加载编辑器。

### 回收站（RecycleBinController）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/recycle/list` | 回收站列表 |
| POST | `/api/recycle/restore` | 恢复文件 |
| POST | `/api/recycle/delete` | 永久删除 |
| POST | `/api/recycle/empty` | 清空回收站 |

### 传输限速（TransferController）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/transfer/speed-limit` | 获取当前用户限速配置 |

## 分享模块（st-share）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/share/create` | 创建分享 |
| GET | `/api/share/list` | 我的分享列表 |
| PUT | `/api/share/{shareId}` | 更新分享 |
| DELETE | `/api/share/{shareId}` | 删除分享 |
| POST | `/api/share/access/access` | 访问分享（验证提取码） |
| GET | `/api/share/access/download/{shareCode}` | 分享文件下载 |
| GET | `/api/share/access/list` | 分享文件列表 |
| GET | `/api/share/access/stream/{shareCode}` | 分享文件流式预览 |
| GET | `/api/share/effective-permissions?fileNodeId=` | 当前用户对该文件的有效权限集（需登录；个人文件本人返回 {view,download}，团队文件按权限链计算） |

> 分享访问接口（`/api/share/access/**`）为公开接口，无需登录认证。
> 分享权限上限：创建/更新分享时服务端校验「分享权限 ⊆ 当前用户对该文件的有效权限 + share 权限点前置」，超权拒绝。

## 团队模块（st-team）

### 空间管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/team/space` | 创建团队空间 |
| GET | `/api/team/spaces` | 我的空间列表 |
| GET | `/api/team/{spaceId}` | 空间详情 |
| PUT | `/api/team/{spaceId}` | 更新空间 |
| DELETE | `/api/team/{spaceId}` | 删除空间 |

### 成员管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/team/{spaceId}/member` | 邀请成员 |
| GET | `/api/team/{spaceId}/users/search` | 搜索可邀请用户 |
| GET | `/api/team/{spaceId}/members` | 成员列表 |
| PUT | `/api/team/{spaceId}/member/{memberId}` | 更新成员角色 |
| DELETE | `/api/team/{spaceId}/member/{memberId}` | 移除成员 |

### 邀请链接

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/team/{spaceId}/invite` | 生成邀请链接（32 位邀请码，可设角色/有效期） |
| GET | `/api/team/{spaceId}/invites` | 邀请链接列表 |
| DELETE | `/api/team/{spaceId}/invite/{inviteId}` | 撤销邀请 |
| POST | `/api/team/invite/{code}` | 通过邀请码加入空间 |

### 空间管理（进阶）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/team/{spaceId}/leave` | 退出空间 |
| POST | `/api/team/{spaceId}/transfer` | 移交空间所有权 |
| POST | `/api/team/{spaceId}/pin` | 置顶/取消置顶空间 |
| GET | `/api/team/{spaceId}/activities` | 活动日志列表 |
| POST | `/api/team/{spaceId}/activity` | 手动写入活动日志（内部/异步场景） |

### 团队文件操作

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/team/{spaceId}/files` | 文件列表 |
| GET | `/api/team/{spaceId}/files/by-path` | 按路径获取 |
| GET | `/api/team/{spaceId}/files/{nodeId}` | 文件详情 |
| GET | `/api/team/{spaceId}/tree` | 文件树 |
| POST | `/api/team/{spaceId}/folder` | 创建文件夹 |
| POST | `/api/team/{spaceId}/files/new` | 新建空白文件（团队；需 upload 权限） |
| POST | `/api/team/{spaceId}/files/delete` | 批量删除 |
| PUT | `/api/team/{spaceId}/files/{nodeId}/rename` | 重命名 |
| POST | `/api/team/{spaceId}/files/move` | 移动 |
| POST | `/api/team/{spaceId}/files/copy` | 复制 |
| POST | `/api/team/{spaceId}/files/{nodeId}/lock` | 锁定文件/文件夹（hours=0 永久） |
| POST | `/api/team/{spaceId}/files/{nodeId}/unlock` | 解锁文件/文件夹 |
| GET | `/api/team/{spaceId}/folder/{nodeId}/permissions` | 文件夹权限列表 |
| PUT | `/api/team/{spaceId}/folder/{nodeId}/permissions` | 设置文件夹权限 |
| GET | `/api/team/{spaceId}/comments/{nodeId}` | 文件评论列表 |
| POST | `/api/team/{spaceId}/comments` | 发表评论（支持 @提及） |
| PUT | `/api/team/{spaceId}/comments/{commentId}` | 编辑评论 |
| DELETE | `/api/team/{spaceId}/comments/{commentId}` | 删除评论 |
| GET | `/api/team/{spaceId}/roles` | 自定义角色列表 |
| POST | `/api/team/{spaceId}/role` | 创建自定义角色 |
| PUT | `/api/team/{spaceId}/role/{roleId}` | 编辑自定义角色 |
| DELETE | `/api/team/{spaceId}/role/{roleId}` | 删除自定义角色 |
| GET | `/api/team/{spaceId}/stats` | 空间统计（文件分类/活跃度/操作） |

## 通知模块（st-team）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/notification/unread-count` | 未读通知数 |
| GET | `/api/notification` | 通知列表（分页，page/size） |
| PUT | `/api/notification/{id}/read` | 标记单条已读 |
| PUT | `/api/notification/read-all` | 全部已读 |

## 同步模块（st-sync）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/sync/roots` | 创建同步根 |
| GET | `/api/sync/roots` | 同步根列表 |
| DELETE | `/api/sync/roots/{rootId}` | 删除同步根 |
| PUT | `/api/sync/roots/{rootId}/pause` | 暂停/恢复同步 |
| GET | `/api/sync/roots/{rootId}/delta` | 获取增量变更（游标；过滤 old==new 的 MOVE/RENAME） |
| GET | `/api/sync/roots/{rootId}/exclusions` | 列出同步根的排除路径（选择性同步） |
| POST | `/api/sync/roots/{rootId}/exclusions` | 添加排除路径 |
| DELETE | `/api/sync/roots/{rootId}/exclusions/{exclusionId}` | 删除排除路径 |
| PUT | `/api/sync/roots/{rootId}/conflict-strategy` | 更新冲突策略 |
| POST | `/api/sync/block-check` | 块级检查：对比块布局并初始化上传 |
| POST | `/api/sync/block-upload` | 块级组装：复制可复用块 + 合并新版本 |
| POST | `/api/admin/sync/cleanup-junk` | （管理员）清理同步机器格式冲突副本：数据库删除 + S3 物理对象（引用归零） |
| WS | `/api/sync/ws?token={jwt}` | 同步变更 WebSocket 推送（原生 WebSocket，握手校验 JWT；收到 change 通知后拉取 delta） |

> `cleanup-junk` 需 `ADMIN` 角色；按 `(本地|冲突)-\d{14}(-\d+)?` 机器格式匹配同步根下的节点，复用回收站永久删除逻辑。

## 搜索模块（st-search）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/search?keyword=...` | 全文搜索（文件名+正文，支持类型/大小/日期过滤） |
| POST | `/api/search/reindex` | 重建索引 |

## 预览模块（st-preview）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/preview/{nodeId}` | 获取预览信息 |
| GET | `/api/preview/{nodeId}/thumbnail` | 获取缩略图 |
| GET | `/api/preview/{nodeId}/video` | 视频预览流 |

## 管理模块（st-admin）

### 用户管理（UserManageController）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/user/list` | 用户列表 |
| POST | `/api/admin/user` | 创建用户 |
| GET | `/api/admin/user/{userId}` | 用户详情 |
| PUT | `/api/admin/user/{userId}` | 更新用户 |
| DELETE | `/api/admin/user/{userId}` | 删除用户 |

### 角色管理（RoleController）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/role/list` | 角色列表 |
| POST | `/api/admin/role` | 创建角色 |
| GET | `/api/admin/role/{roleId}` | 角色详情 |
| PUT | `/api/admin/role/{roleId}` | 更新角色 |
| DELETE | `/api/admin/role/{roleId}` | 删除角色 |
| PUT | `/api/admin/role/{roleId}/permissions` | 分配角色权限 |
| GET | `/api/admin/role/user/{userId}` | 查询用户角色 |
| PUT | `/api/admin/role/user/{userId}` | 分配用户角色 |

### 权限管理（PermissionController）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/permission/list` | 权限列表 |
| GET | `/api/admin/permission/grouped` | 按模块分组 |

### 审计日志（AuditLogController）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/audit/list` | 审计日志列表 |

### 统计（StatsController）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/stats` | 系统统计数据 |

### 限速管理（SpeedLimitController）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/speed-limit/list` | 限速规则列表 |
| POST | `/api/admin/speed-limit` | 创建限速规则 |
| GET | `/api/admin/speed-limit/{id}` | 规则详情 |
| PUT | `/api/admin/speed-limit/{id}` | 更新规则 |
| DELETE | `/api/admin/speed-limit/{id}` | 删除规则 |
| PUT | `/api/admin/speed-limit/{id}/toggle` | 启用/禁用规则 |

### 云盘容量（CloudCapacityController）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/cloud-capacity` | 查询云盘总容量 |
| PUT | `/api/admin/cloud-capacity` | 更新云盘总容量 |
## 文件收藏

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/favorite/{nodeId} | 切换收藏状态 |
| GET | /api/favorite/list | 收藏全量列表（首页用） |
| GET | /api/favorite/ids | 收藏ID列表（轻量） |
| GET | /api/favorite/page | 收藏列表（分页，收藏页用） |

## 在线解压（ArchiveController）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/file/{nodeId}/archive/contents | 浏览 ZIP 压缩包内容列表 |
| POST | /api/file/{nodeId}/archive/extract | 解压到指定目录 |

## 文件隐藏

| 方法 | 路径 | 说明 |
|------|------|------|
| PUT | /api/file/{nodeId}/hide | 隐藏文件/文件夹 |
| PUT | /api/file/{nodeId}/unhide | 取消隐藏 |
| GET | /api/file/hidden | 隐藏文件列表 |

## 存储分析与重复检测

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/file/storage/by-type | 按类型统计存储占用 |
| GET | /api/file/duplicates | 重复文件检测 |
| GET | /api/file/duplicates/detail?md5={md5} | 重复文件详情列表 |
| POST | /api/file/duplicates/cleanup?md5={md5} | 清理重复文件（保留最早的，其余移入回收站） |
| GET | /api/file/{nodeId}/versions/count | 文件历史版本数量 |
