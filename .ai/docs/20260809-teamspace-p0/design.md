# 技术设计：团队空间 P0 基础协作补齐

> 归属 exitCriteria: TECH_DESIGN（依赖 EXP_DESIGN）
> 关联文档: requirement.md / impact.md / exp-review.md

## 1. 背景与目标

P0 为团队空间补齐 5 项基础协作能力。技术设计目标是在现有 `st-team` 模块架构上最小化扩展，不侵入 st-core 上传/文件核心链路，通过新增 Entity/Service/接口 + 前端组件增强实现全部需求。

## 2. 架构设计

```
st-team 模块扩展（不新增模块）
│
├── Entity:     TeamInvite / TeamActivity（新增）
├── Mapper:     TeamInviteMapper / TeamActivityMapper（新增）
├── DTO:        CreateInviteRequest / TeamInviteVO / TeamActivityVO（新增）
├── Service:    TeamService 接口扩展 7 个方法 + TeamServiceImpl 实现
├── Controller: TeamController 新增 7 个接口
│
├── 活动日志写入:  TeamActivityHelper（新增工具类，封装异步写入）
├── 活跃追踪:      ActiveTracker（新增工具类，封装去重更新）
│
数据库: docker/mysql/init/
│   ├── 17_team_invite.sql   （新增 team_invite 表）
│   └── 18_team_activity.sql （新增 team_activity 表）
│
前端 st-web:
│   ├── TeamSpacePage.tsx     （扩展：设置表单 + 动态 Tab + 成员弹窗增强）
│   ├── TeamInvitePage.tsx    （新增：邀请落地页）
│   ├── App.tsx               （新增路由）
│   └── types/index.ts        （新增类型）
```

## 3. 前端设计

### 3.1 路由变更（App.tsx）

```tsx
const TeamInvitePage = lazy(() => import('./pages/TeamInvitePage'));
// ...
<Route path="team/invite/:code" element={<TeamInvitePage />} />
```

### 3.2 类型定义（types/index.ts 新增）

```ts
export interface TeamInvite {
  id: string;
  spaceId: string;
  inviteCode: string;
  role: number;
  createdBy: string;
  createdByName: string;
  expireAt: string | null;
  status: number; // 0-已撤销 1-有效
  createdAt: string;
}

export interface TeamActivity {
  id: string;
  userId: string;
  username: string;
  nickname: string;
  action: string;
  targetType: string;
  targetId: string | null;
  targetName: string | null;
  detail: string | null;
  createdAt: string;
}
```

### 3.3 TeamSpacePage 改动

**新增状态：**
- `activeTab: 'files' | 'activity'` -- Tab 切换
- `activities: TeamActivity[]` -- 动态列表
- `activityFilter: string` -- 筛选
- `invites: TeamInvite[]` -- 邀请链接列表
- `showTransfer: boolean` -- 移交弹窗

**设置弹窗重构：**
- 表单状态扩展：`{ spaceName, description, icon, storageQuota }`
- emoji 网格组件（内联，24 个预设 emoji）
- 保存调用 `PUT /api/team/{spaceId}` 提交全部字段

**成员弹窗扩展：**
- 邀请链接分区：生成 / 列表 / 复制 / 撤销
- 成员行新增活跃状态圆点 + 相对时间
- 非拥有者行新增「退出空间」按钮
- 拥有者新增「移交所有权」入口

**动态 Tab：**
- `activeTab === 'activity'` 时渲染活动列表
- FileBrowser 用 `hidden` 类控制显隐（不卸载，保持状态）
- 滚动到底部触发加载下一页

### 3.4 TeamInvitePage（新增）

- `useParams` 获取 `code`
- 挂载时调用 `POST /api/team/invite/{code}` 尝试加入
- 根据返回结果（成功/已是成员/无效）渲染对应状态
- 未登录时后端返回 401，前端引导登录（`navigate('/login?redirect=...')`）

## 4. 后端设计

### 4.1 新增接口清单

| 方法 | 路径 | 说明 | 权限校验 |
|------|------|------|----------|
| GET | `/api/team/{spaceId}/users/search` | 搜索可邀请用户 | `checkPermission(spaceId, 0)` |

| POST | `/api/team/{spaceId}/invite` | 生成邀请链接 | `checkPermission(spaceId, 0)` |
| GET | `/api/team/{spaceId}/invites` | 邀请链接列表 | `checkPermission(spaceId, 0)` |
| DELETE | `/api/team/{spaceId}/invite/{inviteId}` | 撤销邀请链接 | `checkPermission(spaceId, 0)` |
| POST | `/api/team/invite/{code}` | 通过邀请码加入 | 已认证（无需空间权限） |
| POST | `/api/team/{spaceId}/leave` | 退出空间 | `checkPermission(spaceId, 2)` |
| POST | `/api/team/{spaceId}/transfer` | 移交所有权 | `checkPermission(spaceId, 0)` + 拥有者校验 |
| GET | `/api/team/{spaceId}/activities` | 空间活动日志 | `checkPermission(spaceId, 2)` |

### 4.2 核心方法实现

**生成邀请链接（createInvite）：**
```java
// 生成 32 位随机邀请码
String inviteCode = SecureRandomStringUtils.randomAlphanumeric(32);
TeamInvite invite = new TeamInvite();
invite.setSpaceId(spaceId);
invite.setInviteCode(inviteCode);
invite.setRole(request.getRole());
invite.setCreatedBy(UserContext.getUserId());
invite.setExpireAt(request.getExpireAt()); // null=永久
invite.setStatus(1);
teamInviteMapper.insert(invite);
// 写入活动日志
activityHelper.log(spaceId, "INVITE_CREATE", "INVITE", invite.getId(), "邀请链接");
```

**通过邀请码加入（joinByCode）：**
```java
// 查询邀请码
TeamInvite invite = teamInviteMapper.selectOne(...eq inviteCode);
if (invite == null || invite.getStatus() == 0) throw TEAM_INVITE_NOT_FOUND;
if (invite.getExpireAt() != null && invite.getExpireAt().isBefore(now)) throw TEAM_INVITE_EXPIRED;
// 校验是否已是成员
Long exists = teamMemberMapper.selectCount(...eq spaceId, eq userId);
if (exists > 0) return Result.success(已加入); // 前端根据标识跳转
// 加入空间
TeamMember member = new TeamMember();
member.setSpaceId(invite.getSpaceId());
member.setUserId(UserContext.getUserId());
member.setRole(invite.getRole());
member.setJoinedAt(now);
teamMemberMapper.insert(member);
// 活动日志
activityHelper.log(invite.getSpaceId(), "MEMBER_JOIN", "MEMBER", userId, nickname);
return Result.success(spaceId); // 前端跳转空间
```

**退出空间（leave）：**
```java
TeamMember member = teamMemberMapper.selectOne(...eq spaceId, eq userId);
if (member == null) throw TEAM_MEMBER_NOT_FOUND;
if (member.getRole() == 0) {
    // 管理员退出校验
    TeamSpace space = teamSpaceMapper.selectById(spaceId);
    if (space.getOwnerId().equals(userId)) throw "请先移交所有权";
    long adminCount = teamMemberMapper.selectCount(...eq spaceId, eq role 0);
    if (adminCount <= 1) throw "请先提升其他成员为管理员";
}
teamMemberMapper.deleteById(member.getId());
activityHelper.log(spaceId, "MEMBER_LEAVE", "MEMBER", userId, nickname);
```

**移交所有权（transfer）：**
```java
TeamSpace space = teamSpaceMapper.selectById(spaceId);
if (!space.getOwnerId().equals(UserContext.getUserId())) throw "仅拥有者可移交";
TeamMember target = teamMemberMapper.selectById(targetMemberId);
if (target == null || !target.getSpaceId().equals(spaceId)) throw TEAM_MEMBER_NOT_FOUND;
if (target.getRole() != 0) throw "目标必须是管理员";
// 事务更新
space.setOwnerId(target.getUserId());
teamSpaceMapper.updateById(space);
activityHelper.log(spaceId, "SPACE_TRANSFER", "SPACE", target.getUserId(), "空间所有权移交");
```

**活跃追踪更新（ActiveTracker）：**
```java
// 在 getSpace / listFiles 入口调用
public void touchActive(Long spaceId, Long userId) {
    String key = "team:active:" + spaceId + ":" + userId;
    // Redis SETNX + 5分钟过期，存在则跳过
    if (redisTemplate.opsForValue().setIfAbsent(key, "1", 5, TimeUnit.MINUTES)) {
        teamMemberMapper.updateLastActiveAt(spaceId, userId, LocalDateTime.now());
    }
}
```
> 若 Redis 不可用则降级为查库判断 last_active_at 与当前时间差是否超 5 分钟。

### 4.3 活动日志写入（TeamActivityHelper）

```java
@Component
public class TeamActivityHelper {
    private final ExecutorService executor = Executors.newFixedThreadPool(2, ...);
    
    // 异步写入，避免阻塞请求
    public void log(Long spaceId, String action, String targetType, 
                    Long targetId, String targetName) {
        Long userId = UserContext.getUserId();
        executor.execute(() -> {
            TeamActivity activity = new TeamActivity();
            activity.setSpaceId(spaceId);
            activity.setUserId(userId);
            // 冗余用户名/昵称
            SysUser user = sysUserMapper.selectById(userId);
            activity.setUsername(user.getUsername());
            activity.setNickname(user.getNickname());
            activity.setAction(action);
            activity.setTargetType(targetType);
            activity.setTargetId(targetId);
            activity.setTargetName(targetName);
            teamActivityMapper.insert(activity);
        });
    }
}
```

> 在 TeamServiceImpl 各操作方法末尾调用 `activityHelper.log()`，参照影响分析的活动写入点表。

### 4.4 文件上传活动记录

文件上传走 st-core FileService，TeamController 不拦截。方案：
- 前端上传成功回调后调用 `POST /api/team/{spaceId}/activity`（action=FILE_UPLOAD, targetName=文件名）
- 后端校验调用者是空间成员后写入活动日志
- 弱一致性：上传中断/页面关闭则不记录，可接受

### 4.5 新增 ResultCode

```java
TEAM_INVITE_NOT_FOUND(4005, "邀请链接不存在"),
TEAM_INVITE_EXPIRED(4006, "邀请链接已过期"),
TEAM_LAST_ADMIN(4007, "空间至少保留一名管理员"),
TEAM_TRANSFER_TARGET_INVALID(4008, "移交目标必须是空间管理员"),
```

## 5. 数据设计

### 5.1 `17_team_invite.sql`

```sql
-- ============================================================
-- 团队空间邀请链接表（st-team 模块）
-- ============================================================
CREATE TABLE IF NOT EXISTS team_invite (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '邀请ID',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    space_id        BIGINT          NOT NULL                 COMMENT '空间ID',
    invite_code     VARCHAR(32)     NOT NULL                 COMMENT '邀请码（32位随机串）',
    role            TINYINT         NOT NULL DEFAULT 2       COMMENT '默认角色：0-管理员 1-编辑者 2-查看者',
    created_by      BIGINT          NOT NULL                 COMMENT '创建者ID',
    expire_at       DATETIME        DEFAULT NULL             COMMENT '过期时间，NULL=永久',
    status          TINYINT         NOT NULL DEFAULT 1       COMMENT '状态：0-已撤销 1-有效',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_invite_code (invite_code),
    KEY idx_space_status (space_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队空间邀请链接表';
```

### 5.2 `18_team_activity.sql`

```sql
-- ============================================================
-- 团队空间活动日志表（st-team 模块）
-- ============================================================
CREATE TABLE IF NOT EXISTS team_activity (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '活动ID',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    space_id        BIGINT          NOT NULL                 COMMENT '空间ID',
    user_id         BIGINT          DEFAULT NULL             COMMENT '操作人ID',
    username        VARCHAR(100)    DEFAULT NULL             COMMENT '操作人用户名',
    nickname        VARCHAR(100)    DEFAULT NULL             COMMENT '操作人昵称',
    action          VARCHAR(50)     NOT NULL                 COMMENT '操作类型',
    target_type     VARCHAR(30)     DEFAULT NULL             COMMENT '目标类型：FILE/FOLDER/MEMBER/SPACE/INVITE',
    target_id       BIGINT          DEFAULT NULL             COMMENT '目标ID',
    target_name     VARCHAR(255)    DEFAULT NULL             COMMENT '目标名称',
    detail          TEXT            DEFAULT NULL             COMMENT '操作详情(JSON)',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_space_created (space_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4_unicode_ci COMMENT='团队空间活动日志表';
```

> `team_activity` 不设逻辑删除字段（日志只增不改不删，由定时任务清理 90 天前记录）。

### 5.3 现有表变更

无。`team_member.last_active_at` 字段已存在，仅需补充更新逻辑。

## 6. 性能与安全

**性能：**
- 活动日志异步写入（独立线程池），不阻塞请求
- 活动查询走 `idx_space_created` 索引，分页 20 条
- 活跃追踪 Redis 去重，5 分钟内同一用户仅一次 DB 写入
- 邀请码查询走 `uk_invite_code` 唯一索引

**安全：**
- 邀请码 32 位加密随机串（`SecureRandom`），防遍历
- 邀请链接撤销需管理员权限校验
- 加入空间需校验链接有效性（状态 + 过期时间）
- 退出/移交需事务保证原子性
- 移交需校验目标必须是管理员，防越权
- 活动日志仅系统写入，用户不可篡改

## 7. 修改文件范围

### 后端（st-team + st-common）

| 文件 | 操作 |
|------|------|
| `st-common/.../ResultCode.java` | 修改：新增 4 个团队错误码 |
| `st-team/.../entity/TeamInvite.java` | 新增 |
| `st-team/.../entity/TeamActivity.java` | 新增 |
| `st-team/.../mapper/TeamInviteMapper.java` | 新增 |
| `st-team/.../mapper/TeamActivityMapper.java` | 新增 |
| `st-team/.../dto/CreateInviteRequest.java` | 新增 |
| `st-team/.../dto/TeamInviteVO.java` | 新增 |
| `st-team/.../dto/TeamActivityVO.java` | 新增 |
| `st-team/.../util/TeamActivityHelper.java` | 新增 |
| `st-team/.../util/ActiveTracker.java` | 新增 |
| `st-team/.../service/TeamService.java` | 修改：新增 7 方法声明 |
| `st-team/.../service/impl/TeamServiceImpl.java` | 修改：实现 7 方法 + 各操作注入活动日志 + 活跃追踪 |
| `st-team/.../controller/TeamController.java` | 修改：新增 7 个接口端点 |
| `docker/mysql/init/17_team_invite.sql` | 新增 |
| `docker/mysql/init/18_team_activity.sql` | 新增 |

### 前端（st-web）

| 文件 | 操作 |
|------|------|
| `src/types/index.ts` | 修改：新增 TeamInvite / TeamActivity 类型 |
| `src/pages/TeamSpacePage.tsx` | 修改：设置表单 + 动态 Tab + 成员弹窗增强 + 退出/移交 |
| `src/pages/TeamInvitePage.tsx` | 新增 |
| `src/App.tsx` | 修改：新增路由 |

## 8. 测试方案

- **后端单元测试**：TeamServiceImpl 新增方法的边界条件（邀请过期、最后管理员退出、移交目标非管理员等）
- **后端集成测试**：邀请链接完整流程（生成->加入->撤销）、活动日志写入验证、活跃追踪去重验证
- **前端验证**：设置表单提交、动态 Tab 切换、邀请落地页各状态、退出/移交弹窗交互
- **构建验证**：`mvn compile` 后端编译 + `npm run build` 前端构建通过
- **数据库验证**：迁移脚本执行成功，表结构正确
