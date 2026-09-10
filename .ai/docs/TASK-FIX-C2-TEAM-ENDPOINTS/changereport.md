# Change Report — TASK-FIX-C2-TEAM-ENDPOINTS

## 元信息

- Task ID: `TASK-FIX-C2-TEAM-ENDPOINTS`
- Agent: executor（taskType=implement）
- dispatchId: `fix-c2-001`
- 日期: 2026-08-14
- 来源: 全量 Code Review C2（Critical：团队角色/统计端点断裂，前端调用 404）

## 背景

Service 层已实现 `listRoles / createRole / updateRole / deleteRole / getStats`，但 `TeamController` 未暴露对应端点，前端 `RoleManageDialog` 与 `StatsPanel` 调用 `/team/{spaceId}/roles`、`/team/{spaceId}/role[/{roleId}]`、`/team/{spaceId}/stats` 运行期 404。

## 修改文件清单

- 修改：`st-team/src/main/java/com/stcloud/team/controller/TeamController.java`

## 修改内容

在 `@RequestMapping("/api/team")` 前缀下新增 5 个端点（纯新增，未改动既有端点）：

| 端点 | Service 映射 | 返回类型 |
|------|-------------|---------|
| `GET /api/team/{spaceId}/roles` | `teamService.listRoles(spaceId)` | `Result<List<TeamRoleVO>>` |
| `POST /api/team/{spaceId}/role` | `teamService.createRole(spaceId, request)` | `Result<TeamRoleVO>` |
| `PUT /api/team/{spaceId}/role/{roleId}` | `teamService.updateRole(spaceId, roleId, request)` | `Result<Void>` |
| `DELETE /api/team/{spaceId}/role/{roleId}` | `teamService.deleteRole(spaceId, roleId)` | `Result<Void>` |
| `GET /api/team/{spaceId}/stats?days=` | `teamService.getStats(spaceId, days)` | `Result<TeamStatsVO>` |

说明：
- `days` 参数默认 30，前端不传时也能正常工作。
- 创建/编辑角色请求体使用 `@Valid @RequestBody TeamRoleRequest`（`name`、`permissions` 非空校验）。
- 角色写操作附加 `@Auditable` 审计注解，与既有团队写操作风格一致。

## 与验收标准对照

| 验收项 | 结果 |
|--------|------|
| 五个端点均映射到对应 Service 方法，参数/返回类型正确 | PASS |
| 路径与 spec 4.1 / 前端调用一致 | PASS（前端 `api` baseURL=`{server}/api`，`/team/{spaceId}/roles|role|stats` 与新增映射完全一致） |
| 未改既有端点 | PASS（纯新增） |
| 编译通过 | PASS（`mvn -q -pl st-team -am compile` EXIT=0） |

## 测试结果

- 命令：`mvn -q -pl st-team -am compile`
- 结果：EXIT=0，无编译错误

## 风险

- 角色写操作依赖 Service 层权限校验（`checkPermission(spaceId, 0)` 要求空间管理员），未在 Controller 层重复加 `@PreAuthorize`，与既有团队端点风格一致；如需更细粒度权限可后续补充。
- 本次未做运行期接口探测（无运行中 MySQL/应用），404 消除结论基于路径比对与编译验证。

## 下一步

- 主线程复跑 `mvn compile` 确认 EXIT=0；抽查端点路径与前端 api 一致（本报告已列对照）。
- 建议后续联调验证角色 CRUD 与统计面板实际请求。
