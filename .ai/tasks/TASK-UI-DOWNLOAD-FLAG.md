# TASK-UI-DOWNLOAD-FLAG（前端 allow_download 开关 — executor/implement）

## 元信息

- Task ID: `TASK-UI-DOWNLOAD-FLAG`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: 补充 `allow_download` 前端开关（后端字段已就绪）

## 目标

1. **分享创建对话框**（`st-web/src/components/share/ShareDialog.tsx`）：新增"允许下载"开关（Switch/Checkbox，默认开）；创建分享请求带 `allowDownload`；勾选时 `permission=1 + allowDownload=1`，不勾 `permission=0 + allowDownload=0`（与后端联动一致，中文注释）。
2. **分享管理页**（`st-web/src/pages/ShareManagePage.tsx`）：状态列或操作区展示"允许下载/仅查看"标识；若有编辑入口则加切换（无编辑入口则仅展示）。
3. **类型**（`st-web/src/types/index.ts`）：`CreateShareRequest` 补 `allowDownload?: number;`（`FileShare.allowDownload` 已有）。

## 范围

- include：`st-web/src/components/share/ShareDialog.tsx`、`st-web/src/pages/ShareManagePage.tsx`、`st-web/src/types/index.ts`
- exclude：后端代码、其它前端页面、`docker/mysql/init`、创建子 Agent

## 验收标准

- 创建对话框有"允许下载"开关且默认开；请求携带 allowDownload 并与 permission 联动
- 管理页展示 allowDownload 状态
- `npx tsc --noEmit`（st-web）通过

## 验证

- 主线程跑 tsc / npm build 抽查
