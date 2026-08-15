# TASK-FIX-PERM-UI-MAJOR（Code Review Major 项修复 — executor/implement）

## 元信息

- Task ID: `TASK-FIX-PERM-UI-MAJOR`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 迭代: 20260814-permission-ui（CODE_REVIEW PASS，3 项 Major 需处理）

## 修复清单（定版）

1. **S1【类型失真】**：`ShareDialog.tsx` 中 `JSON.stringify(...) as unknown as Record<string, boolean>` 双重断言——将 `CreateShareRequest.permissions` 前端类型改为 `string`（JSON 字符串契约），移除断言；三处提交处统一 `JSON.stringify` 字符串。
2. **S2【权限点常量重复】**：把 9 权限点常量（view/upload/download/delete/rename/move/share/manage_members/manage_settings）抽取到共享模块（如 `st-web/src/lib/permissions.ts`），`FolderPermissionDialog`、`RoleManageDialog`、`ShareDialog` 三组件复用（含中文注释）。
3. **SP1【个人文件分享口径】**：后端 `createShare` 对个人文件分享权限上限统一为 `{view, download}`（与 `effective-permissions` 接口一致）——个人分享请求权限集 ⊆ {view,download}，超权拒绝；团队文件逻辑不变。

## 范围

- include（写）：`st-web/src/lib/permissions.ts`（新增）、`FolderPermissionDialog.tsx`、`RoleManageDialog.tsx`、`ShareDialog.tsx`、`types/index.ts`（仅 permissions 类型 string）、`st-share/**`（createShare 个人文件上限）
- include（读）：design.md、codereview.md、`.ai/dispatch/**`
- exclude：其它文件、创建子 Agent

## 验收标准

- 无 `as unknown as` 断言；权限点常量单源；个人分享上限 {view,download} 与接口一致
- `npx tsc --noEmit` 与 `mvn -pl st-share -am compile` 通过（主线程统一串行验证）

## 验证

- 主线程统一 tsc + mvn compile
