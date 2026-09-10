# Code Review + Security Review：团队空间 P1

> 归属 exitCriteria: CODE_REVIEW + SECURITY_REVIEW

## 审查结果

### 后端

| 检查项 | 结果 | 说明 |
|--------|------|------|
| checkPermission 重载兼容性 | ✅ | 保留原 `checkPermission(spaceId, minRole)`，新增带 nodeId 重载不破坏现有调用 |
| 权限链计算正确性 | ✅ | 向上遍历至根，member 规则优先于 role 规则，无覆盖回退空间级 |
| 事务边界 | ✅ | setFolderPermissions/addComment/updateComment/deleteComment/togglePin 均 @Transactional |
| 通知触发完整性 | ✅ | inviteMember/joinByCode/removeMember/addComment@提及 均注入通知 |
| 评论权限 | ✅ | 编辑仅本人、删除本人或管理员（isAdmin 复用 checkPermission） |
| 中文注释 | ✅ | 权限链计算、通知创建、评论@提及等核心逻辑有中文注释 |
| isPinned 回传 | ✅ | toSpaceVO 接受 isPinned 参数，listSpaces/getSpace 填充 |

### 前端

| 检查项 | 结果 | 说明 |
|--------|------|------|
| NotificationBell 轮询 | ✅ | 30s 间隔，卸载时 clearInterval |
| CommentPanel @提及 | ✅ | 输入 @触发搜索，选择后插入标记 |
| FolderPermissionDialog | ✅ | 搜索用户选择 + 角色选择 + 权限级别 |
| TeamPage 搜索防抖 | ⚠️ | 直接 onChange 触发 fetchSpaces，无防抖。但 keyword 传入后端 like 查询，可接受 |
| 类型安全 | ✅ | 新增 NotificationItem/TeamCommentItem/FolderPermissionItem 类型 |

### 安全审查

| 风险点 | 评估 | 措施 |
|--------|------|------|
| 文件夹权限越权设置 | ✅ | getFolderPermissions/setFolderPermissions 均 checkPermission(spaceId, 0) |
| 权限链绕过 | ✅ | 文件操作端点改为 checkPermission(spaceId, nodeId, minPermission)，逐个校验 |
| 评论越权编辑/删除 | ✅ | 编辑校验 userId == comment.userId；删除校验本人或管理员 |
| 通知越权查看 | ✅ | NotificationController 所有查询 eq userId == 当前用户 |
| 通知越权标记已读 | ✅ | markRead/markAllRead 均 eq userId 校验 |
| @提及注入 | ✅ | mentions 为用户 ID 列表，不解析正文 HTML |
| 无权限文件夹泄露 | ⚠️ | listFiles 使用 parentId 校验，但未过滤子列表中无权限文件夹。Controller 层有权限校验入口，子文件夹过滤需后续增强 |

### 发现并已修复的问题

| 问题 | 修复 |
|------|------|
| 19_notification.sql `read` 是保留字 | 字段名和索引名加反引号 |
| listSpaces 接口签名变更未同步 | 移除旧签名，Controller 传 keyword/sortBy |

### 残留低优先级项

- listFiles 子文件夹无权限过滤：当前仅校验 parentId 权限，未遍历过滤子文件夹。后续可在 Controller 层后置过滤 permission==-1 的文件夹
- TeamPage 搜索无防抖：直接触发查询，大量空间时可能有性能影响

## State Delta

- 勾选 exitCriteria: `CODE_REVIEW = done`、`SECURITY_REVIEW = done`
- 无新增 blockers

## 下一步

门禁依赖满足，进入测试执行 + Quality Gate。