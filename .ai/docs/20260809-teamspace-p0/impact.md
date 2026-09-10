# 影响分析：团队空间 P0 基础协作补齐

> 归属 exitCriteria: IMPACT_ANALYSIS（依赖 REQ_ANALYSIS）
> 关联需求文档: `.ai/docs/20260809-teamspace-p0/requirement.md`

## 背景

团队空间 P0 迭代新增 5 项功能，涉及后端 st-team 模块扩展、前端页面重构、数据库新增 2 张表。本文档评估各需求对现有模块、数据模型、调用链的影响范围，为技术设计提供输入。

## 输入

- 需求文档 PRD（`.ai/docs/20260809-teamspace-p0/requirement.md`）
- 现有代码：`st-team` 模块全部源码、`st-web` 前端 TeamPage/TeamSpacePage/App.tsx/types/fileSource
- 数据库表结构：`team_space`、`team_member`、`audit_log`

## 分析

### 1. 后端 st-team 模块影响

**新增 Entity + Mapper：**
- `TeamInvite`（对应 `team_invite` 表）+ `TeamInviteMapper`
- `TeamActivity`（对应 `team_activity` 表）+ `TeamActivityMapper`

**TeamController 新增接口（7 个）：**

| 接口 | 方法 | 说明 | 权限 |
|------|------|------|------|
| `/api/team/{spaceId}/invite` | POST | 生成邀请链接 | 管理员 |
| `/api/team/{spaceId}/invites` | GET | 邀请链接列表 | 管理员 |
| `/api/team/{spaceId}/invite/{inviteId}` | DELETE | 撤销邀请链接 | 管理员 |
| `/api/team/invite/{code}` | POST | 通过邀请码加入空间 | 已认证 |
| `/api/team/{spaceId}/leave` | POST | 退出空间 | 成员 |
| `/api/team/{spaceId}/transfer` | POST | 移交所有权 | 拥有者 |
| `/api/team/{spaceId}/activities` | GET | 空间活动日志 | 查看者+ |

**TeamService 新增方法（7 个）：** 与上述接口一一对应

**TeamServiceImpl 现有方法改动：**
- `checkPermission`：无变化，但被新增方法复用
- `getSpace` / `listFiles`：需更新 `last_active_at`（P0-5 活跃追踪）
- `inviteMember`：现有的按用户名邀请保留，与邀请链接机制并存
- `removeMember`：现有移除逻辑保留，新增 leave 退出逻辑（成员自助退出）

**活动日志写入点（P0-3）：**

| 现有方法 | 活动记录 action |
|----------|----------------|
| createSpace | SPACE_CREATE（非本空间操作，可选记录） |
| updateSpace | SPACE_UPDATE |
| deleteSpace | 不需要（空间已删） |
| inviteMember | MEMBER_INVITE |
| updateMemberRole | MEMBER_ROLE_CHANGE |
| removeMember | MEMBER_REMOVE |
| leave（新增） | MEMBER_LEAVE |
| transfer（新增） | SPACE_TRANSFER |
| createInvite（新增） | INVITE_CREATE |
| revokeInvite（新增） | INVITE_REVOKE |
| createFolder | FOLDER_CREATE |
| deleteFiles | FILE_DELETE |
| renameFile | FILE_RENAME |
| moveFiles | FILE_MOVE |
| copyFiles | FILE_COPY |

> 文件上传的活动日志：上传走 st-core 的 `FileService`，TeamController 不直接处理上传。上传完成后的活动记录需在 TeamController 的文件列表/详情接口间接感知，或由前端上传完成后回调通知。**建议方案**：前端上传成功后调用一个轻量 `POST /api/team/{spaceId}/activity` 上报 FILE_UPLOAD 活动，后端校验文件确实属于该空间后写入。避免侵入 st-core 上传链路。

### 2. 后端 st-core 模块影响

- **无直接改动**：团队文件操作已委托 `FileService` 的 `*Team*` 系列方法（listTeamFiles/createTeamFolder/deleteTeamFiles 等），这些方法保持不变
- 间接影响：P0-3 活动日志不侵入 st-core，由 st-team 层在 Controller 操作后写入

### 3. 数据库影响

**新增表（2 张）：**

`team_invite`（见 PRD 3.1）- 新增迁移脚本 `17_team_invite.sql`
`team_activity`（见 PRD 3.3）- 新增迁移脚本 `18_team_activity.sql`

**现有表变更：** 无字段变更
- `team_member.last_active_at`：字段已存在，代码需补充更新逻辑（P0-5）

### 4. 前端 st-web 影响

| 文件 | 改动 |
|------|------|
| `src/App.tsx` | 新增路由 `/team/invite/:code`（邀请落地页） |
| `src/pages/TeamPage.tsx` | 无重大改动（空间列表页保持） |
| `src/pages/TeamSpacePage.tsx` | **核心改动**：设置弹窗扩展完整表单、新增动态 Tab、成员弹窗新增邀请链接管理 + 退出/移交按钮 |
| `src/pages/TeamInvitePage.tsx` | **新增文件**：邀请链接落地页 |
| `src/types/index.ts` | 新增 `TeamInvite`、`TeamActivity` 类型定义 |
| `src/lib/fileSource.ts` | 无改动（文件操作接口不变） |

### 5. 安全影响

- 邀请链接：需防止遍历攻击（invite_code 用 32 位加密随机串）、防止越权（撤销需管理员权限、加入需校验链接有效性）
- 退出/移交：需防止非拥有者移交、防止最后管理员退出导致空间无人管理
- 活动日志：查看者可读，不可篡改/删除（仅系统写入）

## 决策

1. 活动日志新建独立 `team_activity` 表，不复用 `audit_log`（关注点不同）
2. 邀请链接直接加入（P0 不做审批流），降低实现复杂度
3. 文件上传活动记录由前端回调上报，不侵入 st-core 上传链路
4. 活跃追踪在 `getSpace` 和 `listFiles` 入口更新，5 分钟去重
5. 退出与移交作为独立接口，不合并到现有 removeMember

## State Delta

- 新增 artifact: `impact`（本文件），归属 IMPACT_ANALYSIS
- 建议勾选 exitCriteria: `IMPACT_ANALYSIS = done`
- 无新增 blockers

## 风险

| 风险 | 等级 | 说明 |
|------|------|------|
| 活动日志写入点遗漏 | 中 | 需确保所有成员/文件/空间操作都写入，遗漏导致动态不完整 |
| 前端上传回调上报可靠性 | 中 | 上传失败或页面关闭导致活动未记录，但属可接受的弱一致性 |
| 邀请链接直接加入安全风险 | 中 | 链接泄露即可加入，P0 可接受，P1 可加审批 |

## 下一步

建议编排器并行派发：
- **Experience Reviewer**：基于 PRD 产出 UI/UX 设计文档（uispec.md），归属 EXP_DESIGN
- EXP_DESIGN 完成后派发 **Architect** 产出技术设计文档（design.md），归属 TECH_DESIGN

## 变更影响

- 影响下游 TECH_DESIGN：本文档的接口清单与数据模型为技术设计输入
- 影响下游 TESTCASES：验收标准需覆盖所有新增接口与边界条件
- 不影响现有个人文件功能（st-core 无改动）
- 不影响现有分享功能（st-share 无改动）
