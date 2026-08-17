# TASK：试点 FE-01 — st-web fileSize 工具函数

> 试点用实现任务，验证 V15 worktree 隔离。产物为独立新增 TS 工具文件，不修改任何既有代码。

## 元信息

- Task ID: `TASK-20260817-worktree-isolation-fe01`
- 关联任务 State: `.ai/state/20260817-worktree-isolation.yaml`
- 关联文档: `.ai/docs/20260817-worktree-isolation/design.md`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-17

## 目标

在 worktreeRoot 内新增 `st-web/src/lib/fileSize.ts`：导出 `formatFileSize(bytes: number): string`（B/KB/MB/GB/TB，1024 进制，1 位小数，0 值显示 "0 B"；核心逻辑使用中文注释）。

## 修改范围

- 新增：`st-web/src/lib/fileSize.ts`

## 禁止修改范围

- 其它任何文件（含 `src/components/**`、`src/pages/**`、`src/store/**` 等既有代码）
- 不运行 npm / mvn；不执行 git；不写 `.ai/` 除 changereport 外的内容
- 不修改主工作树 `D:\code\st-cloud` 下任何源码

## 验收标准

- [ ] 新增文件存在于 worktreeRoot，编码 UTF-8
- [ ] 函数逻辑正确（单位换算/小数位/0 值），中文注释，导出 `formatFileSize`
- [ ] 未修改任何既有文件

## 测试要求

- 本任务不自行运行构建；主线程合并后由 `npm run build`（tsc -b && vite build）统一验证

## 输出要求

完成后追加 `.ai/docs/20260817-worktree-isolation/changereport.md` 的「FE-01」章节（修改文件清单 / 与验收标准对照 / 测试结果 / 风险），并返回 State Delta。
