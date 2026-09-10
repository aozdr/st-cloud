# Change Report：Code Review 修复迭代（20260813-code-review-fix）

## 背景
2026-08-13 Code Review 发现 relay 中转上传 P0/P1 缺陷与标准违规；另盘点 `.ai/docs/20260813-*` 遗留任务（simpleUpload 限速 F6、前端体验要点、TC-012）。本迭代按 Loop 修复并验证。

## 修改文件清单

### 后端（TASK-01/02/03-Java/06）
- `st-core/.../controller/FileController.java`：新增 `POST /upload/relay-chunk` 与 `POST /upload/relay-finalize`（鉴权 + 流式接收）
- `st-core/.../service/impl/UploadServiceImpl.java`：relayChunk 请求级 seq 幂等 + Content-Length 校验 + markChunkUploaded；relayFinalize 权限校验 + 失败 abort + markChunkUploaded；simpleUpload 限速 pacing（F6）
- `st-core/.../service/impl/upload/RelayBufferManager.java`：会话记录 storagePath/s3UploadId/relayChunkSize/lastSeq；tryAcquireSeq 原子认领；定时清理 + abort S3
- `st-core/.../config/UploadRelayConfig.java`：新增 cleanupIntervalMs 配置
- `st-core/.../dto/UploadInitResponse.java`：新增 relayRateKb
- `st-core/.../service/StorageService.java`：修复 uploadPartCopy Javadoc 乱码
- `docker/mysql/init/28_file_object.sql`：INSERT IGNORE 幂等
- `docker/mysql/init/30_sync_change_log_event_log_id.sql`：information_schema 守卫幂等

### 前端（TASK-05）
- `st-web/src/hooks/useUpload.tsx`：relay 状态文案/限速快照/超时失败文案/IPC 透传
- `st-web/src/components/file/UploadPanel.tsx`：限速中转上传中 + 限速徽标 + 预估剩余时间（Xh Ym / >24h）
- `st-web/src/pages/TransferManager.tsx`：桌面传输列表 relay 状态与限速徽标
- `st-web/src/types/index.ts`、`st-desktop/src/types.ts`：transferMode/relayLimitKb/relayRateKb
- `st-desktop/src/upload-manager.ts`：relay 任务字段与文案（取消 abort 已有）
- `st-desktop/src/database.ts`、`sync-engine.ts`：修复注释/日志乱码（TASK-03 TS）
- `st-desktop/src/types.ts`：TransferStatus 补 `cancelled`（既有类型缺口）
- `st-desktop/src/database.ts`：updateTask values 类型修复（既有类型缺口）

### 测试（TASK-04）
- `st-core/src/test/.../RelayUploadIntegrationTest.java`（新增 13 例 TC-001~013）
- `UploadStateMachineIntegrationTest.java`：补 RelayBufferManager/UploadRelayConfig/SpeedLimitService 测试基建

## 与验收标准对照
| 验收标准 | 结果 |
|----------|------|
| relay 端点存在、不再 404 | PASS（FileController 新增映射） |
| 重复 seq 幂等不重复写字节 | PASS（TC-011） |
| relay-finalize 权限校验 + 失败 abort | PASS（TC-007/009） |
| Content-Length 超限拒绝 | PASS（代码 + TC 校验） |
| 超时定时清理 + S3 abort | PASS（TC-010） |
| 注释乱码清零 + 28/30 幂等 | PASS（rg 扫描） |
| RelayUploadIntegrationTest 全绿 | PASS（13/13） |
| 前端限速中转状态/徽标/ETA/失败文案 | PASS（npm run build / tsc 通过） |
| simpleUpload 限速（F6） | PASS（TC-013） |
| mvn test 全绿 + 前端构建 + verify-loop | PASS |

## 测试结果
- `mvn test -pl st-core,st-sync,st-search,st-team,st-common -am`：全绿
- st-web `npm run build`：通过；st-desktop `tsc --noEmit`：通过
- verify-loop：PASS（本报告补齐后）

## 风险
- relay 断点续传不支持（MVP 语义，失败重来）；暂停/恢复绕回直传为 P2 遗留
- `mergeChunks`/`abortUpload` 无 owner 权限校验为既有问题（非本迭代引入），建议后续迭代补齐
