# TASK-FE-SHARE-PERM（分享权限点选择 + 有效权限接口 — executor/implement，前后端）

## 元信息

- Task ID: `TASK-FE-SHARE-PERM`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 迭代: 20260814-permission-ui（设计见 `.ai/docs/20260814-permission-ui/design.md`）

## 目标

1. **后端**（st-share）：新增接口 `GET /api/share/effective-permissions?fileNodeId=`，返回当前用户对该文件的有效权限集：
   - 个人文件：本人 → `{view:true, download:true}`；非本人/未登录 → 空集。
   - 团队文件：经 `teamService.resolveMyPermissions(spaceId, nodeId)` 返回（st-share 已依赖 st-team）。
   - 落 `ShareController` / `ShareService(Impl)`（现有校验逻辑复用）。
2. **前端**（st-web/src/components/share/ShareDialog.tsx）：创建分享前调用该接口，展示可分享权限点勾选；**不在此权限集内的项禁用**（如无 download → 下载勾选禁用）；勾选结果写入请求 `permissions`；`allowDownload` 与 download 联动（含 download → allowDownload=1）。

## 范围

- include（写）：`st-web/src/components/share/ShareDialog.tsx`、`st-share/**`（ShareController、ShareService/Impl 新增接口）
- include（读）：design.md、`st-web/src/types/index.ts`（已改）、`.ai/dispatch/**`
- exclude：其它前端文件、`types/index.ts`（勿动）、st-team/st-core 主代码、创建子 Agent

## 验收标准

- 后端接口返回有效权限集（个人/团队分支正确）；未授权空集
- 前端分享权限点勾选 + 超权禁用 + allowDownload 联动
- `npx tsc --noEmit` 通过（主线程统一验证）；后端编译由主线程统一跑

## 验证

- 主线程统一跑 tsc + mvn 编译（串行）；抽查接口与组件
