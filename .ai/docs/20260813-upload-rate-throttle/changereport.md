# Change Report：上传低速率中转限速（20260813-upload-rate-throttle）

## 背景
低速率（rate<5MB）上传走服务端中转，瞬时速率不超限速。本迭代实现 relay 核心链路。

## 实现范围
- `st-common/.../UserTransferLimiter.java`：新增 UploadPaceBucket（字节级 pacing）
- `st-core/.../service/StorageService(Impl).java`：新增 uploadPart / uploadPartCopy
- `st-core/.../service/impl/upload/RelayBufferManager.java`、`UploadRelayConfig.java`：中转缓冲与会话
- `st-core/.../service/impl/UploadServiceImpl.java`：init 模式判定 + relayChunk + relayFinalize
- `st-core/.../dto/UploadInitResponse.java`：transferMode / relayChunkSize
- 前端 st-web / st-desktop：relay 分支

## Review 后续
2026-08-13 Code Review 发现 P0/P1（relay 端点缺失、seq 幂等缺失、权限/Content-Length/超时清理、测试缺失），已在 `20260813-code-review-fix` 迭代全部修复并回归（见该迭代 changereport/testreport）。

## 遗留（已记录，后续迭代）
- simpleUpload 限速：本迭代修复（F6）
- 中转断点续传：MVP 不支持，失败重来
- 桌面端 relay 暂停/恢复绕回直传：P2
