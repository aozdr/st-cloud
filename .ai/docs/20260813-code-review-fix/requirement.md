# 需求文档：Code Review 修复迭代（20260813-code-review-fix）

## 背景
2026-08-13 对 HEAD→工作区全量变更的 Code Review 发现中转上传（relay）P0/P1 缺陷与标准违规。本迭代按 Agent Loop 流程修复并收敛。

## 需求来源
- Code Review 报告（2026-08-13，Standards + Spec 双轴）
- 既有迭代文档：`.ai/docs/20260813-upload-rate-throttle/`、`.ai/tasks/TASK-007.md`、`.ai/tasks/TASK-008.md`

## 功能需求
- F1 补齐 /relay-chunk 与 /relay-finalize HTTP 端点（P0）
- F2 relay-chunk seq 幂等：重复 seq 忽略，不重复写字节（P0）
- F3 relay-finalize 权限校验：仅 owner/租户管理员（P1）
- F4 relay-chunk Content-Length ≤ relayChunkSize 校验（P1）
- F5 超时清理定时任务 + S3 multipart abort（P1）
- F6 relay-finalize 失败 abort + 临时文件清理（P1）
- F7 修复核心注释乱码（database.ts / sync-engine.ts / StorageService.java / schema.sql）
- F8 28/30 迁移脚本幂等化（无 schema 变化）
- F9 新增 RelayUploadIntegrationTest（TC-001~011）
- F10 前端中转状态展示「限速中转上传中 · 限速 X KB/s」

## 影响范围
- 后端：st-core（FileController / UploadServiceImpl / RelayBufferManager / UploadRelayConfig / StorageService）
- 前端：st-web useUpload.tsx + 任务列表文案；st-desktop upload-manager.ts
- 数据库：docker/mysql/init 28/30 仅幂等加固；H2 schema.sql 注释修复（无结构变化）
- 测试：st-core 新增集成测试；全量回归

## 非目标（明确裁剪）
- 中转断点续传（MVP 不支持，失败重来）
- pacing 异步化（架构评审优化建议，后续评估）
- 桌面端 tsc 预存错误（既有技术债，单独跟进）
- 桌面端 relay 暂停/恢复绕回直传（P2，后续迭代）
- 0 字节文件 relay 边界（与直传既有行为一致）

## 遗留任务并入说明（2026-08-13 今日文档盘点）
- F6 simpleUpload 限速接入：由本迭代 TASK-06 执行（原 upload-rate-throttle 遗留）
- uispec/exp-review 体验要点（限速徽标 / ETA / 失败文案 / 取消 abort）：由 TASK-05 执行
- TC-012 客户端自限速自动化：由 TASK-04 执行

## 体验要求
中转上传过程中用户应看到明确的「限速中转上传中 · 限速 X KB/s」状态与平滑进度，而非仅百分比；direct 模式展示保持不变。

## 验收标准
见 `.ai/state/20260813-code-review-fix.yaml` completionCriteria 与各 TASK 验收标准。
