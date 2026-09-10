# 技术设计：团队空间 P1 协作增强

> 归属 exitCriteria: TECH_DESIGN（依赖 EXP_DESIGN）
> 关联文档: requirement.md / impact.md / exp-review.md

## 1. 背景与目标

P1 为团队空间增加文件夹级权限、文件评论、站内通知、空间搜索排序四项协作增强。核心挑战是 P1-1 文件夹权限的校验链重构，需在不破坏现有 14 处 checkPermission 调用的前提下扩展节点级权限。

## 2. 架构设计

```
st-team 模块扩展
│
├── P1-3 通知体系（基础设施，先行）
│   ├── Entity:     Notification
│   ├── Mapper:     NotificationMapper
│   ├── Service:    NotificationService + NotificationServiceImpl
│   ├── Controller: NotificationController（/api/notification/*）
│   └── Helper:     NotificationHelper（便捷创建通知）
│
├── P1-2 文件评论
│   ├── Entity:     TeamComment
│   ├── Mapper:     TeamCommentMapper
│   ├── Service:    CommentService（内嵌于 TeamService 或独立）
│   └── Controller: TeamController 新增评论端点
│
├── P1-1 文件夹权限
│   ├── Entity:     TeamFolderPermission
│   ├── Mapper:     TeamFolderPermissionMapper
│   ├── Service:    FolderPermissionService（权限链计算）
│   └── 重载:       checkPermission(spaceId, nodeId, minPermission)
│
├── P1-4 空间搜索排序
│   ├── 字段:       team_member.is_pinned
│   ├── 扩展:       listSpaces 支持 keyword/sortBy
│   └── 新增:       togglePin 接口
│
数据库: docker/mysql/init/
│   ├── 19_notification.sql
│   ├── 20_team_comment.sql
│   ├── 21_team_folder_permission.sql
│   └── 22_team_member_pinned.sql
│
前端 st-web:
│   ├── components/team/NotificationBell.tsx    （新增）
│   ├── components/team/CommentPanel.tsx        （新增）
│   ├── components/team/FolderPermissionDialog.tsx（新增）
│   ├── pages/TeamPage.tsx                      （扩展）
│   ├── pages/TeamSpacePage.tsx                 （扩展）
│   ├── components/layout/TopBar.tsx            （挂载铃铛）
│   └── types/index.ts                          （新增类型）
```

## 3. 前端设计

### 3.1 类型定义（types/index.ts 新增）

```ts
export interface Notification {
  id: string; userId: string; type: string;
  title: string; content: string;
  refType: string | null; refId: string | null;
  read: number; createdAt: string;
}

export interface TeamComment {
  id: string; spaceId: string; nodeId: string;
  userId: string; username: string; nickname: string; avatar: string | null;
  content: string; parentId: string | null;
  mentions: string | null; createdAt: string;
}

export interface FolderPermission {
  id: string; spaceId: string; folderNodeId: string;
  subjectType: string; subjectId: string;
  subjectName: string; permission: number; createdAt: string;
}
```

### 3.2 NotificationBell 组件

- 挂载到 TopBar，`useEffect` 每 30s 轮询 `GET /api/notification/unread-count`
- 铃铛图标 + 未读数红色角标
- 点击展开下拉：`GET /api/notification?page=1&size=20`
- 点击通知：`PUT /api/notification/{id}/read` + 跳转
- "全部已读"：`PUT /api/notification/read-all`

### 3.3 CommentPanel 组件

- 从右侧滑入的侧边栏（`fixed right-0 top-0 h-full w-80`）
- 评论列表 `GET /api/team/{spaceId}/comments/{nodeId}`
- 发表评论 `POST /api/team/{spaceId}/comments`
- @提及：输入 `@` 触发成员搜索浮层（复用 P0 搜索逻辑）
- 回复：`parentId` 关联，二级缩进

### 3.4 FolderPermissionDialog 组件

- 文件夹右键菜单"权限设置"入口（仅管理员）
- 权限规则列表 + 添加规则 + 删除规则
- 权限级别下拉：查看(2)/编辑(1)/管理(0)/无权限(-1)

### 3.5 TeamPage 扩展

- 搜索框（防抖 300ms）+ 排序下拉 + 置顶图钉
- 调 `GET /api/team/spaces?keyword=xxx&sortBy=createdAt`

## 4. 后端设计

### 4.1 P1-3 通知体系

**NotificationController（/api/notification）：**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/unread-count` | 未读通知数 |
| GET | `/` | 通知列表（分页） |
| PUT | `/{id}/read` | 标记单条已读 |
| PUT | `/read-all` | 全部已读 |

**NotificationHelper：**
```java
@Component
public class NotificationHelper {
    public void notify(Long userId, String type, String title,
                       String content, String refType, Long refId) {
        Notification n = new Notification();
        n.setUserId(userId); n.setType(type); n.setTitle(title);
        n.setContent(content); n.setRefType(refType); n.setRefId(refId);
        n.setRead(0);
        notificationMapper.insert(n);
    }
}
```

**通知触发点注入：**
- `inviteMember` / `joinByCode`：notify(userId, "TEAM_INVITE", ...)
- `removeMember`：notify(userId, "MEMBER_CHANGE", ...)
- 评论 @提及：notify(mentionedUserId, "MENTION", ...)

### 4.2 P1-2 文件评论

**新增端点（TeamController）：**

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/{spaceId}/comments/{nodeId}` | 评论列表 | 查看者+ |
| POST | `/{spaceId}/comments` | 发表评论 | 查看者+ |
| PUT | `/{spaceId}/comments/{commentId}` | 编辑评论 | 本人 |
| DELETE | `/{spaceId}/comments/{commentId}` | 删除评论 | 本人/管理员 |

**发表评论逻辑：**
```java
public Result<TeamCommentVO> addComment(Long spaceId, CommentRequest req) {
    checkPermission(spaceId, 2); // 空间查看者以上可评论
    // 校验文件属于该空间
    fileService.validateTeamNode(spaceId, req.getNodeId());
    TeamComment comment = new TeamComment();
    comment.setSpaceId(spaceId); comment.setNodeId(req.getNodeId());
    comment.setUserId(UserContext.getUserId()); comment.setContent(req.getContent());
    comment.setParentId(req.getParentId());
    comment.setMentions(req.getMentions()); // "1,2,3" 用户ID列表
    teamCommentMapper.insert(comment);
    // @提及发通知
    if (req.getMentions() != null && !req.getMentions().isEmpty()) {
        for (String uid : req.getMentions().split(",")) {
            notificationHelper.notify(Long.parseLong(uid), "MENTION",
                "你在评论中被@提及", comment.getContent(), "comment", comment.getId());
        }
    }
    return Result.success(toCommentVO(comment));
}
```

### 4.3 P1-1 文件夹级权限

**新增端点（TeamController）：**

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/{spaceId}/folder/{nodeId}/permissions` | 权限规则列表 | 管理员 |
| PUT | `/{spaceId}/folder/{nodeId}/permissions` | 设置权限规则 | 管理员 |

**checkPermission 重载（核心）：**
```java
/**
 * 校验节点级权限：先空间级成员校验，再文件夹权限链
 * @param nodeId 文件/文件夹节点ID，null=空间根
 * @param minPermission 最低权限：0-管理 1-编辑 2-查看
 */
public Integer checkPermission(Long spaceId, Long nodeId, Integer minPermission) {
    Long userId = UserContext.getUserId();
    // 1. 空间级成员校验
    TeamMember member = teamMemberMapper.selectOne(...);
    if (member == null) throw TEAM_PERMISSION_DENIED;
    int spaceRole = member.getRole();

    // 2. nodeId 为空或空间根，直接用空间级角色
    if (nodeId == null) {
        if (spaceRole > minPermission) throw TEAM_PERMISSION_DENIED;
        return spaceRole;
    }

    // 3. 权限链计算：从当前节点向上遍历至空间根
    int effectivePermission = folderPermissionService.resolvePermission(
        spaceId, nodeId, userId, spaceRole);

    // 4. 校验
    if (effectivePermission == -1) throw TEAM_PERMISSION_DENIED; // 无权限
    if (effectivePermission > minPermission) throw TEAM_PERMISSION_DENIED;
    return effectivePermission;
}
```

**FolderPermissionService.resolvePermission：**
```java
/**
 * 权限链计算：
 * 1. 从当前节点向上遍历 parent_id 至空间根
 * 2. 在每个层级查 team_folder_permission 是否有匹配规则（先 member 后 role）
 * 3. 取最近一条匹配规则的 permission
 * 4. 无匹配则回退空间级角色权限
 */
public int resolvePermission(Long spaceId, Long nodeId, Long userId, int spaceRole) {
    Long currentId = nodeId;
    while (currentId != null) {
        // 查当前节点是否有权限规则
        List<TeamFolderPermission> perms = teamFolderPermissionMapper.selectList(
            new LambdaQueryWrapper<TeamFolderPermission>()
                .eq(TeamFolderPermission::getFolderNodeId, currentId));
        // 优先匹配 member 规则，其次 role 规则
        for (TeamFolderPermission p : perms) {
            if ("member".equals(p.getSubjectType()) && p.getSubjectId().equals(userId)) {
                return p.getPermission();
            }
        }
        for (TeamFolderPermission p : perms) {
            if ("role".equals(p.getSubjectType()) && p.getSubjectId() == spaceRole) {
                return p.getPermission();
            }
        }
        // 向上遍历
        FileNode node = fileNodeMapper.selectById(currentId);
        if (node == null || node.getParentId() == null) break;
        currentId = node.getParentId();
    }
    // 无覆盖规则，回退空间级角色
    return spaceRole;
}
```

**无权限文件夹过滤（listFiles）：**
- Controller 层获取子节点列表后，对每个文件夹调用 resolvePermission
- permission == -1 的文件夹从结果中过滤掉

### 4.4 P1-4 空间搜索排序

**listSpaces 扩展：**
```java
public Result<IPage<TeamSpaceVO>> listSpaces(int page, int size, String keyword, String sortBy) {
    // 查用户的空间成员记录，支持 is_pinned 排序
    LambdaQueryWrapper<TeamMember> wrapper = new LambdaQueryWrapper<>()
        .eq(TeamMember::getUserId, userId)
        .orderByDesc(TeamMember::getIsPinned); // 置顶优先
    // ... 查 spaceIds
    LambdaQueryWrapper<TeamSpace> spaceWrapper = new LambdaQueryWrapper<>()
        .in(TeamSpace::getId, spaceIds)
        .eq(TeamSpace::getStatus, 1);
    if (keyword != null && !keyword.isEmpty()) {
        spaceWrapper.like(TeamSpace::getSpaceName, keyword);
    }
    // 排序
    if ("memberCount".equals(sortBy)) { /* 需要特殊处理 */ }
    else if ("storageUsed".equals(sortBy)) { spaceWrapper.orderByDesc(TeamSpace::getStorageUsed); }
    else { spaceWrapper.orderByDesc(TeamSpace::getCreatedAt); }
    // ...
}
```

**togglePin：**
```java
public Result<Void> togglePin(Long spaceId) {
    Long userId = UserContext.getUserId();
    TeamMember member = teamMemberMapper.selectOne(...);
    if (member == null) throw TEAM_PERMISSION_DENIED;
    member.setIsPinned(member.getIsPinned() == 1 ? 0 : 1);
    teamMemberMapper.updateById(member);
    return Result.success();
}
```

## 5. 数据设计

### 5.1 19_notification.sql
```sql
CREATE TABLE IF NOT EXISTS notification (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '通知ID',
    tenant_id    BIGINT       NOT NULL COMMENT '租户ID',
    user_id      BIGINT       NOT NULL COMMENT '接收者ID',
    type         VARCHAR(20)  NOT NULL COMMENT '类型：MENTION/TEAM_INVITE/FILE_CHANGE/MEMBER_CHANGE',
    title        VARCHAR(200) NOT NULL COMMENT '标题',
    content      VARCHAR(500) DEFAULT NULL COMMENT '正文',
    ref_type     VARCHAR(20)  DEFAULT NULL COMMENT '关联类型：team/comment/file',
    ref_id       BIGINT       DEFAULT NULL COMMENT '关联ID',
    read         TINYINT      NOT NULL DEFAULT 0 COMMENT '0-未读 1-已读',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_user_read_created (user_id, read, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='站内通知表';
```

### 5.2 20_team_comment.sql
```sql
CREATE TABLE IF NOT EXISTS team_comment (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '评论ID',
    tenant_id    BIGINT       NOT NULL COMMENT '租户ID',
    space_id     BIGINT       NOT NULL COMMENT '空间ID',
    node_id      BIGINT       NOT NULL COMMENT '文件节点ID',
    user_id      BIGINT       NOT NULL COMMENT '评论人ID',
    content      TEXT         NOT NULL COMMENT '评论内容',
    parent_id    BIGINT       DEFAULT NULL COMMENT '父评论ID',
    mentions     VARCHAR(500) DEFAULT NULL COMMENT '@提及用户ID列表(逗号分隔)',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_node_created (node_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队空间文件评论表';
```

### 5.3 21_team_folder_permission.sql
```sql
CREATE TABLE IF NOT EXISTS team_folder_permission (
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '权限记录ID',
    tenant_id       BIGINT      NOT NULL COMMENT '租户ID',
    space_id        BIGINT      NOT NULL COMMENT '空间ID',
    folder_node_id  BIGINT      NOT NULL COMMENT '文件夹节点ID',
    subject_type    VARCHAR(10) NOT NULL COMMENT '授权对象：role/member',
    subject_id      BIGINT      NOT NULL COMMENT '角色值或用户ID',
    permission      TINYINT     NOT NULL COMMENT '权限：-1-无权限 0-管理 1-编辑 2-查看',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_folder_subject (folder_node_id, subject_type, subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队文件夹权限表';
```

### 5.4 22_team_member_pinned.sql
```sql
ALTER TABLE team_member ADD COLUMN is_pinned TINYINT NOT NULL DEFAULT 0 COMMENT '是否置顶：0-否 1-是';
```

## 6. 性能与安全

**性能：**
- 权限链遍历：从节点向上至根，通常 ≤10 层，每层一次 DB 查询；可用 Redis 缓存 `folder:perm:{nodeId}:{userId}`
- 通知轮询：30s 间隔仅查 count，走索引 `idx_user_read_created`
- 评论查询：走 `idx_node_created` 索引，分页 20 条

**安全：**
- 文件夹权限设置仅管理员（checkPermission spaceId, 0）
- 评论编辑仅本人（校验 comment.userId == 当前用户）
- 评论删除本人或管理员
- 通知仅查自己的（user_id = 当前用户）
- 权限链计算在 Service 层，Controller 不可绕过

## 7. 修改文件范围

### 后端

| 文件 | 操作 |
|------|------|
| st-team: Notification/TeamComment/TeamFolderPermission Entity + Mapper | 新增 6 个文件 |
| st-team: NotificationService + NotificationController + NotificationHelper | 新增 |
| st-team: FolderPermissionService | 新增 |
| st-team: CommentRequest/CommentVO/NotificationVO 等 DTO | 新增 |
| st-team: TeamService + TeamServiceImpl | 修改：checkPermission 重载 + 评论/权限/搜索/置顶方法 + 通知触发 |
| st-team: TeamController | 修改：新增评论/权限/置顶/搜索端点 |
| st-common: ResultCode | 修改：新增评论/权限错误码 |
| docker/mysql/init: 19-22 脚本 | 新增 4 个 |

### 前端

| 文件 | 操作 |
|------|------|
| types/index.ts | 修改：新增 3 类型 |
| components/team/NotificationBell.tsx | 新增 |
| components/team/CommentPanel.tsx | 新增 |
| components/team/FolderPermissionDialog.tsx | 新增 |
| components/layout/TopBar.tsx | 修改：挂载铃铛 |
| pages/TeamPage.tsx | 修改：搜索/排序/置顶 |
| pages/TeamSpacePage.tsx | 修改：评论入口/权限入口/按钮显隐 |

## 8. 测试方案

- 后端编译 `mvn compile -pl st-team -am`
- 前端构建 `npm run build`
- 数据库迁移执行验证
- Code Review + Security Review
- 重点测试：权限链计算正确性、通知触发完整性、评论@提及流程