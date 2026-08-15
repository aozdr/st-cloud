# TASK：TASK-01 中转上传 HTTP 端点 + seq 幂等 + Content-Length 校验

> 开发前置产物。编码输入只接受本文件。
> 关联 State: `.ai/state/20260813-code-review-fix.yaml`

## 元信息
- Task ID: `TASK-20260813-code-review-fix-01`
- 关联文档: `.ai/docs/20260813-code-review-fix/design.md` / `testcases.md`
- 归属 Agent: backend-engineer

## 目标
修复 Code Review P0：`/relay-chunk` 与 `/relay-finalize` 端点缺失导致 relay 上传 404；`seq` 未使用导致重复请求重复写字节；未校验单请求大小。

## 修改范围
- `st-core/src/main/java/com/stcloud/core/controller/FileController.java`：新增 `POST /upload/relay-chunk`（参数 uploadId/s3UploadId/seq，请求体字节流）与 `POST /upload/relay-finalize`（参数 uploadId/s3UploadId）；权限注解与既有上传接口一致（`hasAuthority('file:upload') or hasRole('ADMIN')`）。
- `st-core/src/main/java/com/stcloud/core/service/impl/UploadServiceImpl.java`：`relayChunk` 使用 seq 幂等（重复 seq 跳过追加并返回 confirmed）；基于会话 `relayChunkSize` 校验单请求 Content-Length，超限抛业务异常。
- `st-core/src/main/java/com/stcloud/core/service/impl/upload/RelayBufferManager.java`：会话记录 lastSeq / relayChunkSize / storagePath / s3UploadId；appendChunk 对 `seq <= lastSeq` 幂等跳过。

## 禁止修改范围
- 不改 direct 预签名直传路径与 getChunkUrl/confirmChunk
- 不改数据库表结构（无 DB 变更）
- 不改 UploadPaceBucket 限速语义与 DownloadBucket

## 验收标准
- [ ] POST /api/file/upload/relay-chunk 与 /relay-finalize 存在映射，不再 404
- [ ] 同一 uploadId 重复 seq 第二次调用返回 confirmed 且字节数不增加
- [ ] Content-Length > relayChunkSize 的请求被拒绝
- [ ] relay-finalize 端点存在，前端调用链路可通（功能级）

## 测试要求
- 由 TASK-04 RelayUploadIntegrationTest 覆盖；本任务完成 `mvn compile -pl st-core,st-common -am` 通过

## 输出要求
- 更新 `.ai/docs/20260813-code-review-fix/changereport.md`（与 TASK-02/03 合并）
