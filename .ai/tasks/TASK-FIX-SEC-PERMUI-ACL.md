# TASK-FIX-SEC-PERMUI-ACL（Security BLOCK P1/P2 修复 — executor/implement）

## 元信息

- Task ID: `TASK-FIX-SEC-PERMUI-ACL`
- taskCode: `SCFIX-01`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 迭代: 20260814-permission-ui（SECURITY_REVIEW BLOCK：P1 跨空间 ACL 注入、P2 all 越权）

## 修复清单（定版，依据 security.md）

1. **P1 节点归属校验**：`TeamServiceImpl.getFolderPermissions` / `setFolderPermissions` 增加 `folderNodeId → spaceId` 归属校验（查 FileNode 的 spaceId 与入参一致，否则 `TEAM_PERMISSION_DENIED("节点不属于该空间")`）；对齐同文件其它资源接口（L628/599 缺失处）。
2. **P2 subjectType 白名单 + all 上限**：`setFolderPermissions` 服务端校验 `subjectType ∈ {all, member, role}`（非法拒绝）；`all` 主体权限集**禁止包含 manage_members / manage_settings**（越权下放拒绝），且 `permissions` 为 null 时回退映射须同限。
3. **测试补充**（st-team）：`setFolderPermissions` 跨空间节点被拒；`all` 主体含 manage_* 被拒；合法 all/member/role 规则通过。

## 范围

- include（写）：`st-team/**`（TeamServiceImpl、FolderPermissionService、TeamController 如需要、测试）
- include（读）：security.md、design.md、`.ai/dispatch/**`
- exclude：st-share、st-web、其它模块、创建子 Agent

## 验收标准

- P1/P2 修复就位（rg 复核归属校验与白名单）；新增测试覆盖
- 验证由主线程统一串行执行（mvn 编译/测试）

## 验证

- 主线程串行跑 `mvn -q -pl st-team -am test`
