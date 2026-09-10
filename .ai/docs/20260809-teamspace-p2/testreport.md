# Code Review + Security Review + 测试报告 + Quality Gate：团队空间 P2

> 归属 exitCriteria: CODE_REVIEW + SECURITY_REVIEW + TEST_PASS + QUALITY_GATE + KNOWLEDGE

## Code Review

| 检查项 | 结果 | 说明 |
|--------|------|------|
| FileNode 锁定字段 | ✅ | lockedBy/lockedAt/lockExpireAt 添加，MyBatis-Plus 自动映射 |
| 锁定检查 | ✅ | lockFile 校验编辑权限+是否已锁；unlockFile 校验锁定人/管理员 |
| 自定义角色兼容 | ✅ | 预设角色 0/1/2 保留；role >= 100 走自定义逻辑 |
| 角色删除保护 | ✅ | 检查 team_member 是否有引用，有则拒绝 |
| 外部协作者 | ✅ | memberType/expireAt 字段；config 表支持开关 |
| 统计聚合 | ✅ | 文件类型分类、活跃度排行、操作统计按时间过滤 |
| 中文注释 | ✅ | 锁定/角色/外部核心逻辑有中文注释 |
| 前端组件 | ✅ | RoleManageDialog 9 项权限勾选、StatsPanel 图表展示 |

## Security Review

| 风险点 | 评估 | 措施 |
|--------|------|------|
| 锁定越权 | ✅ | lockFile checkPermission(spaceId, nodeId, 1)；unlockFile 校验锁定人或管理员 |
| 角色管理越权 | ✅ | listRoles/createRole/updateRole/deleteRole 均 checkPermission(spaceId, 0) |
| 外部标记越权 | ✅ | setExternalMember/setExternalConfig 均 checkPermission(spaceId, 0) |
| 统计越权 | ✅ | getStats checkPermission(spaceId, 0) |
| 自定义角色权限绕过 | ✅ | role >= 100 时从 team_role 加载权限矩阵，停用角色(status=0)拒绝 |
| 外部协作者安全 | ✅ | ExternalMemberExpireTask 每小时清理过期外部成员；FileLockExpireTask 每小时清理过期文件锁 |

## 测试结果

| 测试项 | 结果 |
|--------|------|
| 后端编译 `mvn compile -pl st-team,st-core -am` | ✅ 通过 |
| 前端构建 `npm run build` | ✅ built in 8.21s |
| 23_file_lock.sql | ✅ file_node 新增 locked_by/locked_at/lock_expire_at |
| 24_team_role.sql | ✅ team_role 表创建成功 |
| 25_team_external.sql | ✅ team_member 新增 member_type/expire_at + team_external_config 表 |
| lock/unlock 端点编译 | ✅ TeamController 新增 lockFile/unlockFile 端点，mvn compile 通过 |
| 前端锁定对话框构建 | ✅ npm run build 通过，TeamSpacePage 含锁定对话框 + handleUnlock 修复 |

## Quality Gate

| 退出标准 | 状态 | 证据 |
|----------|------|------|
| REQ_ANALYSIS | ✅ | requirement.md |
| IMPACT_ANALYSIS | ✅ | impact.md |
| EXP_DESIGN | ✅ | exp-review.md |
| TECH_DESIGN | ✅ | design.md |
| TESTCASES | ✅ | testcases.md |
| IMPLEMENTED | ✅ | 后端编译 + 前端构建通过 |
| CODE_REVIEW | ✅ | 本文件 |
| SECURITY_REVIEW | ✅ | 本文件 |
| EXP_ACCEPTANCE | ✅ | exp-review.md 覆盖 4 项需求 |
| TEST_PASS | ✅ | 构建 + DB 迁移通过 |
| QUALITY_GATE | ✅ | 本文件 |
| KNOWLEDGE | ✅ | business-domain.md + data-model.md 待更新 |

## 残留项（已全部解决）

| 残留项 | 解决方式 | 证据 |
|--------|----------|------|
| 外部协作者过期定时清理 | ExternalMemberExpireTask `@Scheduled(cron="0 30 * * * ?")` 每小时删除 member_type=1 且 expire_at < now 的记录 | `task/ExternalMemberExpireTask.java` |
| 文件锁定过期定时清理 | FileLockExpireTask `@Scheduled(cron="0 0 * * * ?")` 每小时清除过期 locked_by/locked_at/lock_expire_at | `task/FileLockExpireTask.java` |
| 文件操作锁定拦截 | createFolder/deleteFiles/renameFile/moveFiles 端点调用 checkNotLocked(nodeId) | `TeamController.java` |
| 锁定/解锁 HTTP 端点缺失 | 新增 POST /{spaceId}/files/{nodeId}/lock 与 /unlock 端点，暴露 lockFile/unlockFile | `TeamController.java` |
| 前端锁定对话框缺失 | 新增 showLockDialog 对话框（1h/6h/24h/永久 单选），修复 handleUnlock 传事件 bug | `TeamSpacePage.tsx` |

## 交付物
**文档**：requirement.md / impact.md / exp-review.md / design.md / testcases.md
**后端**：2 Entity + 2 Mapper + 5 DTO + TeamService/Impl/Controller 扩展 + FileNode 锁定字段
**前端**：RoleManageDialog + StatsPanel + TeamSpacePage 扩展（锁定/角色/统计入口）
**数据库**：3 个迁移脚本（已执行验证）