# TASK：前端上传低速率中转分支

> 开发前置产物。编码输入只接受本文件。

## 元信息

- Task ID: `TASK-008`
- 关联任务 State: `.ai/state/20260813-upload-rate-throttle.yaml`
- 关联文档: `.ai/docs/20260813-upload-rate-throttle/design.md` / `uispec.md`
- 归属 Agent: frontend-engineer
- 创建者: workflow-manager
- 日期: 2026-08-13

## 目标

前端（st-web + st-desktop）根据 init 返回的 transferMode 分支：relay 模式按 relayChunkSize 切小块顺序 POST 中转端点，最后 finalize；direct 模式保持现状。

## 修改范围

- `st-web/src/types/index.ts`：UploadInitResponse + UploadTask 新增 transferMode、relayChunkSize
- `st-web/src/hooks/useUpload.tsx`：init 后判定 transferMode，relay 分支走 /relay-chunk 循环 + /relay-finalize
- `st-desktop/src/types.ts`：UploadInitResponse + TransferTask 新增字段
- `st-desktop/src/upload-manager.ts`：relay 分支

## 禁止修改范围

- UploadPanel 组件视觉（仅状态文案，不新增组件）
- 下载相关代码
- 后端代码（属 TASK-007）
- transfer store / 限速设置逻辑

## 验收标准

- [ ] direct 模式上传行为不变
- [ ] relay 模式：按 relayChunkSize 切小块顺序 POST /relay-chunk（带 seq）
- [ ] relay 进度按字节平滑推进
- [ ] relay 结束 POST /relay-finalize
- [ ] 任务状态显示「限速中转上传中 · 限速 X KB/s」
- [ ] npm run build 通过（无 TS 错误）

## 测试要求

- npm run build 类型检查通过
- 桌面端 tsc 编译通过

## 输出要求

编码完成后更新 Change Report。
