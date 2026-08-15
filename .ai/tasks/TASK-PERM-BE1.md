# TASK-PERM-BE1（st-team 权限模型核心 — executor/implement）

## 元信息

- Task ID: `TASK-PERM-BE1`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: 权限模型重设计（design.md，已确认）

## 定版接口契约（st-share 依赖，勿改签名）

```java
// FolderPermissionService
Set<String> resolvePermissions(Long spaceId, Long nodeId, Long userId, Set<String> rolePerms);
// 返回用户对节点有效权限点集合（并集）：rolePerms ∪ 沿父链收集的 all/member/role 规则；
// 隐含 upload/download → 补 view；-1 规则不参与（仅增强）

// TeamService
void requirePermissions(Long spaceId, Long nodeId, String... perms);
// 成员校验（非成员拒绝）→ 管理员直通（roleId==0 或权限集含 manage_settings）→ resolvePermissions 并集 → 校验包含 perms，否则 TEAM_PERMISSION_DENIED
Set<String> resolveMyPermissions(Long spaceId, Long nodeId);
// 当前用户对该节点有效权限集（st-share 分享上限校验用）
```

## 目标

1. **权限集解析**：新增权限点集合的解析/补全工具（`view/upload/download/delete/rename/move/share/manage_members/manage_settings`；隐含 upload→view、download→view），JSON 与 Set<String> 互转。
2. **FolderPermissionService**：
   - 新增 `resolvePermissions(spaceId, nodeId, userId, rolePerms)`：并集收集；支持 `subject_type=all`（全体，非管理员）/`member`/`role`（role 规则 subject_id 匹配成员角色 ID，含 >=100 自定义）；沿父链向上收集（最多 20 层）；`permissions` JSON 优先，空则回退旧 `permission` 单值映射。
   - 保留旧 `resolvePermission`（内部映射到权限集首值）或迁移调用方。
3. **TeamService**：
   - 成员角色 → 权限集解析：role 0/1/2 用 `presetPerms`（**查看者(2) 改为 view=true、download=false**）；>=100 从 `team_role.permissions` JSON 读取（角色停用则回退查看者）。
   - 新增 `requirePermissions` / `resolveMyPermissions`（契约如上）。
   - 团队文件操作调用点改造：现有 `checkPermission(spaceId, nodeId, minPermission)` 调用点映射到按权限点校验（下载→download、上传→upload、删除→delete、重命名→rename、移动→move、成员管理→manage_members、设置→manage_settings）；`checkPermission(spaceId, minRole)` 空间级保留或映射。
4. **测试（st-team）**：权限集解析；并集（用户例子：上传者 {view,upload} + 文件夹 member 规则 {download} → {view,upload,download}）；`all` 规则；自定义角色（>=100）；管理员直通；查看者无 download。

## 范围

- include：`st-team/**`（FolderPermissionService、TeamService/Impl、TeamRoleVO/Request 如需要、测试）
- exclude：`st-share`、`st-core`、前端、`docker/mysql/init`、创建子 Agent

## 验收标准

- 契约方法签名与上一致；`resolvePermissions` 并集 + all + 自定义角色生效
- 查看者预设 download=false
- 团队文件操作按权限点校验（rg 复核关键调用）
- `mvn -q -pl st-team -am test` EXIT=0

## 验证

- 主线程复跑 st-team 测试；抽查权限集计算
