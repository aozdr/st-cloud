# ADR-008：中转上传加固（端点 / seq 幂等 / 超时清理）

> 状态：本 ADR 关于 seq 提交语义和桌面端恢复策略的内容已由 [ADR-010](ADR-010-code-review-round3-safety.md) supersede；本 ADR 仍保留端点、鉴权、Content-Length 和超时清理等历史决策。

## 背景
Code Review 发现 relay 上传存在 P0/P1：HTTP 端点缺失（前端 404）、seq 未实现幂等（重试重复写字节）、relay-finalize 无权限校验、Content-Length 未校验、超时清理不 abort S3。

## 决策
1. FileController 新增 `POST /upload/relay-chunk`（uploadId/s3UploadId/seq + 字节流）与 `POST /upload/relay-finalize`，鉴权与既有上传接口一致。
2. seq 幂等采用**请求级原子认领**（历史版本为 `lastSeq` 单调确认；当前版本采用 `committedSeq` / `inFlightSeq`，只有完整请求成功后才提交）：重复 seq 直接返回 confirmed，不写字节。
3. relay-finalize 增加 owner/租户管理员校验；失败统一 abort S3 + 清理临时文件（S3 abort 幂等，重复 abort 容错）。
4. relay-chunk 校验 Content-Length ≤ relayChunkSize（chunked 无长度时流式累计兜底）。
5. 超时清理：`@Scheduled` 定时扫描（可配 cleanup-interval-ms），超时会话 abort S3 + 删临时文件；`@PreDestroy` 尽力清理。
6. simpleUpload（F6 遗留）接入限速流：`pacedInputStream` 按 8KB 步进 `acquireUploadPace`，修复小文件绕过限速。

## 放弃的方案
- 在 appendChunk 内部按 8KB 读片段判重：同一 HTTP 请求被拆成多次 8KB 读，后续片段会被误判为重复而丢弃（测试捕获的 P0 缺陷）。
- 中转断点续传：MVP 不支持，失败重来。

## 理由
客户端严格顺序发送（上一 seq 确认后才发下一 seq），lastSeq 单调认领即可覆盖「响应丢失重试同 seq」场景，且天然防并发重复写入。

## 后续限制
- relay 会话为内存态：多实例部署需粘性路由或共享存储。
- 应用重启后的 relay 任务由当前策略要求重新开始，不得绕回直传；`mergeChunks`/`abortUpload` 无 owner 校验（既有问题）建议后续统一补齐。
