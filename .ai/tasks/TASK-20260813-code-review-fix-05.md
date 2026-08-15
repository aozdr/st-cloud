# TASK：TASK-05 前端中转体验（文案/徽标/ETA/失败文案/取消 abort）

> 开发前置产物。编码输入只接受本文件。
> 关联 State: `.ai/state/20260813-code-review-fix.yaml`

## 元信息
- Task ID: `TASK-20260813-code-review-fix-05`
- 关联文档: `.ai/docs/20260813-code-review-fix/design.md` / `requirement.md`
- 归属 Agent: frontend-engineer

## 目标
修复 Code Review P1 + uispec/exp-review 遗留体验要点：中转上传状态可感知、可预测、可中断。

## 修改范围
- `st-web/src/hooks/useUpload.tsx`：relay 分支任务状态携带中转文案与限速值（限速取 init 时 `useTransferStore` 的 effective.uploadSpeedLimit）
- `st-web` 上传任务列表/UploadPanel：relay 模式显示「限速中转上传中 · 限速 X KB/s」+ 平滑进度 + 限速徽标「限速 X KB/s」+ 预估剩余时间（格式 Xh Ym，>24h 显示「>24h」）
- `st-web` relay 失败文案：限速过低导致超时时明确「传输超时，当前限速值过低」
- `st-desktop/src/upload-manager.ts`：relay 分支 updateTask 文案一致 + 取消时 POST /upload/abort 通知服务端清理（含 relayBufferManager.cleanup）

## 禁止修改范围
- 不改直传路径、不改限速设置逻辑、不新增组件、不改下载代码

## 验收标准
- [ ] relay 上传时界面显示「限速中转上传中 · 限速 X KB/s」
- [ ] 显示限速徽标与预估剩余时间（Xh Ym / >24h）
- [ ] 限速超时失败文案明确「传输超时，当前限速值过低」
- [ ] 取消 relay 上传时通知服务端 abort，临时文件被清理
- [ ] direct 模式展示不变
- [ ] st-web `npm run build` 通过（无 TS 错误）
- [ ] st-desktop `tsc --noEmit` 通过

## 输出要求
- 更新 `.ai/docs/20260813-code-review-fix/changereport.md`（与后端合并）
