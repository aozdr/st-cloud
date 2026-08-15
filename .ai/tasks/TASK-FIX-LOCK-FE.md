# TASK-FIX-LOCK-FE（前端改用后端锁定字段 — executor/implement，前端）

## 元信息

- Task ID: `TASK-FIX-LOCK-FE`
- taskCode: `LOCK-FE-01`
- etaMinutes: 15
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14

## 目标（定版）

1. `types/index.ts` 的 `FileNode` 加 `lockedBy?: number | null; lockedAt?: string | null; lockExpireAt?: string | null;`。
2. 锁定状态改为**以后端字段为准**（替换会话内本地 `lockedNodeIds` 维护）：节点 `lockedBy != null && (lockExpireAt == null || new Date(lockExpireAt) > now)` 视为已锁定。
3. 文件右键菜单（FileBrowser/ContextMenu）：按节点后端锁定字段展示"锁定/解锁"其一；操作后刷新列表（或本地更新该节点字段）。
4. 文件列表显示锁图标（已锁定节点）。

## 范围

- include（写）：`st-web/src/types/index.ts`、`st-web/src/pages/TeamSpacePage.tsx`、`st-web/src/components/file/FileBrowser.tsx`、`st-web/src/components/file/ContextMenu.tsx`
- include（读）：`.ai/dispatch/**`
- exclude：后端、其它页面、创建子 Agent

## 验收标准

- 锁定状态来自后端字段（刷新后他人锁定可见）；右键按状态显示锁定/解锁；列表锁图标
- `npx tsc --noEmit` 通过（主线程统一验证）

## 验证

- 主线程统一 tsc/build
