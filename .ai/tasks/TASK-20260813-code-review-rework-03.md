# TASK-03：前端修复（FE-S1/FE-S2）

## 元信息
- Task ID: `TASK-20260813-code-review-rework-03`
- 关联 State: `.ai/state/20260813-code-review-rework.yaml`
- 关联文档: `.ai/docs/20260813-code-review-rework/design.md`、`testcases.md`、`uispec.md`、`exp-review.md`
- 归属 Agent: frontend-engineer
- 创建者: workflow-manager
- 日期: 2026-08-13

## 目标

修复前端 2 项 Code Review blocker：
- **FE-S1**：Web relay 中转循环无取消/abort，onRemove 后上传仍继续并最终落库。
- **FE-S2**：sync-engine.ts reconcileFolder 对无 sync_state 的本地文件直接下载覆盖，数据丢失风险。

## 修改范围（include）

- `st-web/src/hooks/useUpload.tsx`
- `st-web/src/components/file/UploadPanel.tsx`
- `st-web/src/types/index.ts`
- `st-desktop/src/sync-engine.ts`

## 禁止修改（exclude）

- st-core / st-sync / st-team / st-common / st-search / docker 目录
- `.ai/` 流程文档（只读）
- 不提交 git

## 实施要求（决策完备，无需再问）

### FE-S1 Web relay 取消（st-web）
1. `st-web/src/types/index.ts`：`UploadTaskStatus` 增加 `'cancelling'`。
2. `useUpload.tsx`：
   - 增加取消机制：`useRef<Map<string, boolean>>` 记录每个 task 的取消标记；relay 循环前初始化 false，循环内每个 chunk 前检查，命中则置任务 `cancelling`、调用 `api.delete('/file/upload/abort', { params: { uploadId, s3UploadId, fileId } })`（try/catch 吞错），随后将任务移除（或置 failed 后移除），并 `break` 跳出循环（循环后代码需跳过 relay-finalize）。
   - 暴露 `cancelTask(id)` 上下文方法（供 UploadPanel 调用）：对 uploading/merging 且 transferMode==='relay' 且存在 uploadId 的任务执行上述中止逻辑；对其它状态直接 `removeTask`。
   - relay-finalize 阶段取消：finalize 请求进行中无法中断请求本身，等待其返回后若取消标记已置，则调用 abort（服务端 abort 已 completed 时幂等忽略）。
3. `UploadPanel.tsx`：X 按钮 `onRemove` 改为调用 `cancelTask`（不再直接 removeTask），UI 在 `cancelling` 状态短暂显示"取消中"（可复用 Loader2/文案）。

### FE-S2 对账保护（st-desktop）
`sync-engine.ts reconcileFolder`（约 505-554 行）：
- 文件分支：`localExists` 且 `state` 为 undefined（无 sync_state）时，**保留本地**：`syncLog('info', '保留本地未同步文件: ' + relPath)`，`continue`，不下载覆盖。
- 仅当 state 存在且 `state.md5 !== node.fileMd5` 时走既有 `localChanged` 逻辑（本地未修改才从云端更新），不改变既有语义。
- 目录分支不变。

## 验收标准

- Web relay 上传中点击 X：任务移除、abort API 被调用、服务端无残留（后续 TEST_PASS 用集成/手动验证）。
- 对账遇本地存在但无 sync_state 的文件：不覆盖、保留本地、日志提示。
- 既有直传/桌面对账行为不回归。

## 验证命令（必须真实执行并记录结果）

```text
cd st-web; npm run build
cd st-desktop; npx tsc --noEmit
```

## 输出要求

按 Agent 输出规范回复，并追加 `.ai/docs/20260813-code-review-rework/changereport.md` 的 TASK-03 章节。
