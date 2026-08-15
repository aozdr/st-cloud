# TASK-FIX-C2-TEAM-ENDPOINTS（暴露团队角色/统计端点 — executor/implement）

## 元信息

- Task ID: `TASK-FIX-C2-TEAM-ENDPOINTS`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: 全量 Code Review C2（Critical：团队角色/统计端点断裂，前端调用 404）

## 目标

在 `st-team` 的 `TeamController` 中暴露角色管理与统计端点（Service 层已实现，仅缺 Controller 映射）。

## 修改范围（唯一文件）

- `st-team/src/main/java/com/stcloud/team/controller/TeamController.java`：新增以下端点，路由前缀沿用 `@RequestMapping("/api/team")`：
  - `GET /api/team/{spaceId}/roles` → `teamService.listRoles(spaceId)`
  - `POST /api/team/{spaceId}/role` → `teamService.createRole(spaceId, request)`
  - `PUT /api/team/{spaceId}/role/{roleId}` → `teamService.updateRole(spaceId, roleId, request)`
  - `DELETE /api/team/{spaceId}/role/{roleId}` → `teamService.deleteRole(spaceId, roleId)`
  - `GET /api/team/{spaceId}/stats?days=` → `teamService.getStats(spaceId, days)`
- 端点路径与前端调用对齐：先读 `.ai/docs/20260814-project-code-review/spec.md` 4.1 节与 `st-web/src/api/` 中团队相关调用路径，若前端已用其它路径则以后端按前端路径暴露为准（或两者一致），确保 404 消除。

## 兼容策略

- 纯新增端点，不修改既有端点与 Service 签名；向后兼容。

## 范围

- include：`st-team/src/main/java/com/stcloud/team/controller/TeamController.java`；只读 `st-team/src/main/java/com/stcloud/team/service/TeamService.java`、`st-web/src/api/**`、`.ai/docs/20260814-project-code-review/spec.md`
- exclude：修改 `st-team` 其它文件、`st-web` 任何文件、其它 `st-*` 模块、创建子 Agent

## 验收标准

- 五个端点均已映射到对应 Service 方法，参数/返回类型正确
- 路径与 spec.md 4.1 / 前端调用一致；未改既有端点
- 编译通过：`mvn -q -pl st-team -am compile`

## 验证

- 主线程跑 `mvn -q -pl st-team -am compile` 确认 EXIT=0；抽查端点路径与前端 api 一致
