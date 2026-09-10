# 影响范围分析：Code Review 修复迭代

## 变更影响面

| 模块 | 影响 | 风险等级 |
|------|------|---------|
| st-core 上传链路（UploadServiceImpl/FileObjectServiceImpl/FileObjectMapper/UploadManager） | C1 去重复活、M1 merge 权限、M2 配额顺序、M3 事务代理 | 高 |
| st-core 容量（CloudStorageServiceImpl/CloudCapacityMapper） | M6 容量锁解耦 | 中 |
| st-sync（SyncBlockServiceImpl/SyncBlockController/新会话组件/DTO） | M5 会话绑定、M7 abort 清理、M8 测试 | 高 |
| docker/mysql/init（28_file_object.sql + 新增 33 号脚本） | M4 幂等 + C1 数据修复兜底 | 中 |
| st-core schema.sql（H2 同步） | 表结构同步（如无表变化则仅校验） | 低 |
| st-web（useUpload.tsx/UploadPanel.tsx/types） | FE-S1 取消/abort | 中 |
| st-desktop（sync-engine.ts） | FE-S2 对账保护 | 中 |

## 依赖与接口

- 新增 `DELETE /api/sync/block-abort`（或 POST）端点契约（M7）。
- `BlockUploadRequest` 语义变化：storagePath/s3UploadId/fileMd5/fileSize 不再直接可信，服务端以会话为准（M5）。
- 数据库版本 `20260813.2`：修改 28 号脚本 + 新增 `33_code_review_rework.sql`（如需表结构变化；若 C1 仅靠 acquire 复活则无需表变更，33 号仅登记版本记录），H2 schema 同步。

## 兼容策略

- block-upload 请求体保持兼容：客户端可继续传原字段，但服务端以会话为准，字段不一致不报错（向后兼容），仅缺失会话时拒绝（新行为，需客户端先调 block-check）。
- 存量 in-flight 会话：旧客户端未调用 block-check 直接 block-upload 将因无会话被拒，属预期收紧；同步客户端总是先 block-check。
- Web relay 取消为纯增量 UI/行为，不影响直传路径。
