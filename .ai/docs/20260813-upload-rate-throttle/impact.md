# 影响分析

> 落盘路径：`.ai/docs/20260813-upload-rate-throttle/impact.md`
> 归属 IMPACT_ANALYSIS（dependsOn: REQ_ANALYSIS）

# 一、影响范围总览

| 类型 | 模块 | 影响 |
|------|------|------|
| 后端 | st-common/ratelimit | UserTransferLimiter 新增按字节 pacing 接收方法（复用令牌桶，上传方向） |
| 后端 | st-core/service | UploadServiceImpl 增中转模式判定与接收逻辑；新增 RelayBufferManager 缓冲管理器 |
| 后端 | st-core/service/storage | StorageService/Impl 新增服务端 uploadPart（写入字节）方法 |
| 后端 | st-core/controller | FileController 新增中转小块接收端点 |
| 后端 | st-core/dto | UploadInitResponse 新增 transferMode + relayChunkSize 字段 |
| 后端 | st-core/config | 新增中转临时文件目录配置 |
| 前端 | st-web | useUpload.tsx 增中转模式分支；types 新增 transferMode |
| 桌面端 | st-desktop | upload-manager.ts 增中转模式分支；types 新增 transferMode |
| 数据库 | - | 无表结构/字段/索引变更（中转临时文件不持久化，按 uploadId 内存+磁盘隔离） |
| 缓存 | - | 无影响（限速缓存逻辑不变） |

# 二、影响文件预测

## 新增文件

- `st-core/src/main/java/com/stcloud/core/service/impl/upload/RelayBufferManager.java` -- 中转缓冲管理器：按 uploadId 隔离临时文件，累积小块至 multipart 下限(5MB)后调 uploadPart，合并/失败后清理
- `st-core/src/main/java/com/stcloud/core/config/UploadRelayConfig.java` -- 中转临时目录与超时配置（`@ConfigurationProperties(prefix="stcloud.upload.relay")`）

## 修改文件

- `st-common/src/main/java/com/stcloud/common/ratelimit/UserTransferLimiter.java` -- 新增 `acquireUploadBytes(userId, bytes, rateBytesPerSec)` 按字节阻塞 pacing（复用 UploadGate 令牌桶，中转接收时逐块节流）
- `st-core/src/main/java/com/stcloud/core/service/StorageService.java` -- 接口新增 `uploadPart(key, s3UploadId, partNumber, InputStream, size)`
- `st-core/src/main/java/com/stcloud/core/service/impl/StorageServiceImpl.java` -- 实现 uploadPart（`s3Client.uploadPart` + `RequestBody.fromInputStream`）
- `st-core/src/main/java/com/stcloud/core/service/impl/upload/UploadStorageManager.java` -- 暴露 uploadPart 供中转使用
- `st-core/src/main/java/com/stcloud/core/service/impl/UploadServiceImpl.java` -- init 解析限速判定模式；新增 relayChunk 接收（pacing + 缓冲 + uploadPart）；relayComplete 确认
- `st-core/src/main/java/com/stcloud/core/controller/FileController.java` -- 新增 `POST /file/upload/relay-chunk` 端点
- `st-core/src/main/java/com/stcloud/core/dto/UploadInitResponse.java` -- 新增 `transferMode`("direct"|"relay")、`relayChunkSize` 字段
- `st-web/src/hooks/useUpload.tsx` -- init 响应判定模式；relay 模式走小块 POST 中转端点分支
- `st-web/src/types/index.ts` -- UploadInitResponse / UploadTask 新增 transferMode、relayChunkSize
- `st-desktop/src/upload-manager.ts` -- 同 st-web 中转分支
- `st-desktop/src/types.ts` -- TransferTask / UploadInitResponse 新增字段

## 不修改文件（禁止越界）

- 下载相关：`DownloadServiceImpl.java`、`UserTransferLimiter.DownloadBucket`（已达标）
- 限速管理：`SpeedLimitController`、`SpeedLimitManageServiceImpl`、`SpeedLimitCache`、`SysRateLimit`
- 分享链路：`ShareController`、`ShareServiceImpl`（独立问题）
- 存储初始化、配额、版本、合并幂等逻辑（`UploadManager` claimMerging/consumeQuota 等共用，不改动其内部）
- 数据库迁移脚本（无 DB 变更）

# 三、接口契约变化与兼容策略

## init 响应新增字段（向后兼容）

`UploadInitResponse` 新增 `transferMode`、`relayChunkSize`。旧客户端不读这两个字段时，默认走 direct 逻辑（presigned 直传），行为不变。新客户端依据 transferMode 分支。

> 兼容策略：新增字段为可选，旧客户端忽略后仍可正常直传上传。无破坏性变更。

## 新增中转端点

`POST /api/file/upload/relay-chunk`（参数：uploadId、s3UploadId、chunkIndex、partNumber；请求体：application/octet-stream 小块字节）。仅 relay 模式调用，direct 模式不涉及。不影响现有 `/chunk-url`、`/chunk-confirm`、`/merge` 端点。

## merge 端点不变

中转模式与直传模式最终都调 `/merge`（completeMultipartUpload），merge 逻辑不变。

# 四、数据流变化

### 直传模式（不变）

```
client --presigned PUT--> S3  (服务端不经字节流)
client --confirm--> server (释放令牌)
```

### 中转模式（新增）

```
client --POST 小块--> server[pacing 接收令牌桶] --> 临时文件缓冲(累积)
                                         累积≥5MB --> S3 uploadPart
全部小块接收完 --> merge(completeMultipartUpload) --> 删临时文件
```

# 五、风险与关键约束

| 风险点 | 说明 | 缓解 |
|--------|------|------|
| 跨请求缓冲一致性 | 中转小块跨多次 HTTP 请求累积到同一临时文件 | 按 uploadId 隔离 + 同步控制；并发同 uploadId 不允许（UPLOAD_WINDOW=1） |
| 临时文件泄漏 | 中转失败/断连/超时未清理 | RelayBufferManager 注册清理钩子；uploadId 超时清理；失败即删 |
| 服务端带宽占用 | 中转模式服务端承担接收带宽 | 仅低速率触发，带宽本身受限；正常速率仍直传 |
| multipart 5MB 下限 | 非末片 <5MB 会被 S3 拒 | 缓冲累积至 ≥5MB 才 uploadPart；末片无下限 |
| 请求体流式读取 | Spring multipart 默认缓存，无法 pacing | 中转端点用 `HttpServletRequest.getInputStream()` 直接流式读取，非 multipart/form-data |
| 令牌桶复用 | 中转 pacing 与直传门控共用 UploadGate 令牌 | 中转模式不签发 presigned URL，不占 outstanding 窗口；用独立 pacing 方法按字节扣令牌 |

# 六、数据库影响

**无变更**。中转临时文件按 uploadId 在本地磁盘隔离，不落库。file_chunk 记录在中转模式下由服务端 uploadPart 完成后 markChunkUploaded（复用现有逻辑），无需新字段。
