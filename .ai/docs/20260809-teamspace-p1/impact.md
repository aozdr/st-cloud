# 影响分析：团队空间 P1 协作增强

> 归属 exitCriteria: IMPACT_ANALYSIS（依赖 REQ_ANALYSIS）
> 关联需求文档: `.ai/docs/20260809-teamspace-p1/requirement.md`

## 背景

P1 迭代新增 4 项功能，其中 P1-1 文件夹级权限涉及核心权限校验重构，是最高风险项。本文档评估各需求对现有模块、数据模型、调用链的影响范围。

## 分析

### 1. P1-1 文件夹级权限影响（最高风险）

**现有 checkPermission 调用链：**
- `TeamServiceImpl.checkPermission(spaceId, minRole)` 查 `team_member` 表校验空间级角色
- `TeamController` 所有文件操作端点先调 `checkPermission` 再委托 `FileService.*Team*` 方法
- 共 14 处调用点（TeamServiceImpl 内 10 处 + TeamController 内 4 处文件操作）

**改动方案：**
- 新增重载方法 `checkPermission(Long spaceId, Long nodeId, Integer minPermission)`
  - 先校验空间级成员身份（原有逻辑）
  - 再查 `team_folder_permission` 表，从当前节点向上遍历至空间根，取最近一条匹配规则
  - 无覆盖规则时回退空间级角色权限
- **保留原 `checkPermission(spaceId, minRole)` 签名不变**，空间级操作（成员管理、设置等）仍用原方法
- 文件操作端点改为调用带 nodeId 的重载版本

**受影响的 TeamController 端点（6 个文件操作）：**

| 端点 | 现 minRole | 改为 nodeId 校验 |
|------|-----------|-----------------|
| listFiles | 2 (查看) | `checkPermission(spaceId, parentId, 2)` |
| getNodeById | 2 (查看) | `checkPermission(spaceId, nodeId, 2)` |
| createFolder | 1 (编辑) | `checkPermission(spaceId, parentId, 1)` |
| deleteFiles | 1 (编辑) | `checkPermission(spaceId, nodeId, 1)` 逐个校验 |
| renameFile | 1 (编辑) | `checkPermission(spaceId, nodeId, 1)` |
| moveFiles | 1 (编辑) | 校验源 + 目标父文件夹权限 |

**st-core 影响：**
- `FileService` 的 `*Team*` 方法签名不变，权限校验仍在 Controller 层完成
- `listTeamFiles` 需扩展：过滤掉"无权限"子文件夹（P1-1 需求：无权限文件夹不可见）
  - 方案：Controller 层获取有权限的文件夹 ID 集合后传给 FileService 过滤，或在 Service 层后置过滤

### 2. P1-2 文件评论与@提及影响

**完全新增模块，无现有代码改动：**
- 新增 `TeamComment` Entity + Mapper + DTO
- 新增评论 CRUD 接口（5 个：列表/发表/回复/编辑/删除）
- @提及解析：前端选择成员后传入 mentions 字段，后端为每个被@用户创建通知

**依赖 P1-3 通知体系：**
- 评论发表时如有 mentions，调用 NotificationService 创建 MENTION 通知

### 3. P1-3 站内通知体系影响

**完全新增模块：**
- 新增 `Notification` Entity + Mapper + DTO + Service
- 新增通知接口（4 个：未读数/列表/标记已读/全部已读）
- 前端新增通知铃铛组件，挂载到顶部导航栏

**通知触发点：**

| 触发场景 | 通知类型 | 触发位置 |
|----------|----------|----------|
| 评论@提及 | MENTION | TeamCommentService 发表评论时 |
| 邀请加入空间 | TEAM_INVITE | TeamServiceImpl.inviteMember / joinByCode |
| 被移除空间 | MEMBER_CHANGE | TeamServiceImpl.removeMember |

**前端轮询：**
- 通知铃铛组件每 30s 轮询未读数
- 展开时拉取最近 20 条通知

### 4. P1-4 空间搜索与排序影响

**后端改动（轻量）：**
- `team_member` 新增 `is_pinned` 字段
- `listSpaces` 方法扩展：支持 keyword 搜索 + sortBy 排序 + 置顶优先
- 新增 `togglePin(spaceId)` 接口

**前端改动：**
- `TeamPage` 新增搜索框、排序选择器、置顶图钉按钮

### 5. 数据库影响

**新增表（3 张）：**
- `notification`（19_notification.sql）
- `team_comment`（20_team_comment.sql）
- `team_folder_permission`（21_team_folder_permission.sql）

**字段变更（1 个）：**
- `team_member.is_pinned`（22_team_member_pinned.sql）

### 6. 前端影响

| 文件 | 改动 |
|------|------|
| `src/components/layout/TopBar.tsx` | 新增通知铃铛组件 |
| `src/components/team/NotificationBell.tsx` | **新增**：通知铃铛 + 下拉列表 |
| `src/components/team/CommentPanel.tsx` | **新增**：文件评论侧边栏 |
| `src/components/team/FolderPermissionDialog.tsx` | **新增**：文件夹权限设置弹窗 |
| `src/pages/TeamPage.tsx` | 扩展：搜索框 + 排序 + 置顶 |
| `src/pages/TeamSpacePage.tsx` | 扩展：评论入口 + 文件夹权限入口 + 权限控制按钮显隐 |
| `src/types/index.ts` | 新增 Notification/Comment/FolderPermission 类型 |

## 决策

1. P1-1 文件夹权限：保留原 checkPermission 签名，新增带 nodeId 重载，避免破坏现有空间级操作
2. 无权限文件夹过滤：在 Controller 层后置过滤，不侵入 FileService
3. P1-3 通知：用轮询而非 WebSocket，降低复杂度，预留升级空间
4. @提及解析：前端选择成员后传 mentions 用户 ID 列表，后端直接用，不解析正文文本
5. 权限链计算：从当前节点向上遍历 parent_id 至空间根，取最近覆盖规则，无覆盖则回退空间级角色

## State Delta

- 新增 artifact: `impact`（本文件），归属 IMPACT_ANALYSIS
- 建议勾选 exitCriteria: `IMPACT_ANALYSIS = done`
- 无新增 blockers

## 风险

| 风险 | 等级 | 说明 |
|------|------|------|
| checkPermission 重构回归 | 高 | 14 处调用点，需确保空间级操作不受影响 |
| 权限链遍历性能 | 中 | 深层嵌套文件夹时需多次查库，可缓存 |
| 通知轮询服务器压力 | 低 | 30s 间隔 + 仅查未读数，压力可控 |

## 下一步

建议编排器并行派发 Experience Reviewer 产出 UI/UX 设计文档，随后 Architect 产出技术设计。