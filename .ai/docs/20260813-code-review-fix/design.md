# 程序设计文档：Code Review 修复迭代

## 1. 端点设计（F1）
- `POST /api/file/upload/relay-chunk`：query 参数 uploadId / s3UploadId / seq；请求体 `application/octet-stream`。
  校验顺序：权限（owner/租户管理员）→ 会话存在 → Content-Length ≤ relayChunkSize → seq 幂等 → pacing 读流 → appendChunk。
  返回 `RelayChunkResponse{confirmed, partUploaded, partNumber}`。
- `POST /api/file/upload/relay-finalize`：query 参数 uploadId / s3UploadId。
  校验顺序：权限 → finalize 末片 → 复用 mergeChunks；异常 abort + cleanup。
- 权限注解与既有上传接口一致：`hasAuthority('file:upload') or hasRole('ADMIN')`。

## 2. seq 幂等（F2）
- `RelaySession` 增加 `lastSeq`；`appendChunk(uploadId, seq, ...)` 在 synchronized 内判断 `seq <= lastSeq` 直接返回（不写字节、不触发 uploadPart）。
- 前端顺序发送，lastSeq 单调即可；响应丢失后客户端重试同 seq 被忽略，文件内容不重复。

## 3. Content-Length 校验（F4）
- 控制器读取请求 Content-Length；服务层以会话 relayChunkSize 校验，超限抛 `BUSINESS_ERROR`。
- 若请求为 chunked（无 Content-Length），以流读取并累计校验，超限即中断（兜底）。

## 4. 超时清理 + S3 abort（F5）
- `createSession(uploadId, rateBytes, storagePath, s3UploadId)` 记录会话上下文。
- 新增定时任务（`@Scheduled(fixedDelay=60s)`，间隔可配 `stcloud.upload.relay.cleanup-interval-ms`）扫描 `lastActiveMs` 超时会话：先移除会话（cleanup），再 `abortMultipart`，删临时文件。
- `@PreDestroy destroy` 对未完成会话尽力 abort + 清理。

## 5. 失败 abort 策略（F6）
- `relayFinalize` catch RuntimeException：`storageManager.abortMultipart` + `relayBufferManager.cleanup` + 重抛。
- 与 mergeChunks 的 handleMergeFailure 兼容（S3 abort 幂等，重复 abort 不报错）；新建上传失败不再保留分片，符合「MVP 失败重来」。

## 6. 迁移脚本幂等（F8）
- `28_file_object.sql`：回填 `INSERT` 改为 `INSERT IGNORE`。
- `30_sync_change_log_event_log_id.sql`：用 `information_schema.COLUMNS` / `STATISTICS` 存在性守卫 + PREPARE/EXECUTE，重复执行不报错。
- H2 schema.sql 无结构变化（列已存在），仅修复 file_block 注释乱码。

## 7. 前端（F10）
- st-web / st-desktop relay 分支：任务状态文案「限速中转上传中 · 限速 X KB/s」（限速值取 init 时 effective.uploadSpeedLimit），进度按字节平滑推进；不改直传路径。

## 8. 遗留任务补齐（来自 .ai/docs/20260813-* 今日文档）
### simpleUpload 限速（F6，TASK-06）
- `simpleUpload` 解析有效限速 rateBytes；`rate > 0` 时以 `ThrottledInputStream`（或读取循环内 `acquireUploadPace` 每 8KB 步进阻塞）包装 `file.getInputStream()` 后 `uploadObject`；`rate=0` 零开销不变。
### 前端体验（F7，TASK-05 扩展）
- 限速徽标「限速 X KB/s」；预估剩余时间 = 剩余字节 / 限速，格式化 Xh Ym、>24h 显示「>24h」；失败文案「传输超时，当前限速值过低」；取消 relay 任务时 POST /upload/abort。
### 自动化测试（TASK-04 扩展）
- TC-012 客户端自限速中转（clientLimit=100 时 init 返回 relay）；TC-013 simpleUpload 限速（mock 验证 pacing）。
### 中转 markChunkUploaded（P2，TASK-01 附带）
- relay 路径在服务端 uploadPart 完成后调用 `chunkManager.markChunkUploaded(uploadId, 对应块)`，使 file_chunk DB 状态与 S3 listParts 一致。

## 本轮裁剪（明确非目标）
- 中转断点续传（架构评审标注「留作扩展」）
- pacing 异步化（优化建议，后续评估）
- 桌面端 tsc 预存错误（既有技术债，单独跟进）
- 桌面端 relay 暂停/恢复（P2，依赖断点续传）

## 风险点
- relayFinalize abort 与 mergeChunks 失败处理叠加：S3 abort 幂等，安全。
- 定时清理与活跃写入竞态：清理先移除会话再 abort，后续写入因会话缺失抛 CHUNK_NOT_FOUND（幂等可重试）。
- chunked 请求无 Content-Length：流式累计校验兜底。
