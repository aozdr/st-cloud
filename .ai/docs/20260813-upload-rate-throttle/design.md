# 程序设计文档

> 输出标准：`docs/newList/ai-design-document-standard.md`
> 前置：架构设计评审（`.ai/docs/20260813-upload-rate-throttle/architecture-review.md`）已通过
> 落盘路径：`.ai/docs/20260813-upload-rate-throttle/design.md`

# 一、需求分析

## 功能名称

上传低速率强制限速中转

## 功能描述

```
用户：被强制限速（rate<5MB）或自限速低于 5MB 的用户
操作：上传文件
系统行为：init 判定 relay 模式 -> 客户端按 relayChunkSize 切小块顺序 POST -> 服务端 pacing 阻塞接收 + 缓冲 -> 攒 5MB uploadPart -> finalize 末片+merge
最终结果：瞬时速率不超限速值（突发 ≤ relayChunkSize）
```

# 二、系统影响分析

## 影响模块

| 类型 | 模块 | 是否修改 |
|------|------|---------|
| 后端 | st-common/ratelimit | 是（新增 UploadPaceBucket） |
| 后端 | st-core/service/storage | 是（新增 uploadPart） |
| 后端 | st-core/service/impl/upload | 是（新增 RelayBufferManager、relay 逻辑） |
| 后端 | st-core/controller | 是（新增端点） |
| 后端 | st-core/dto/config | 是（字段、配置） |
| 前端 | st-web | 是（relay 分支） |
| 桌面端 | st-desktop | 是（relay 分支） |
| 数据库 | - | 否 |

## 影响文件预测

- 新增：`RelayBufferManager.java`、`UploadRelayConfig.java`
- 修改：`UserTransferLimiter.java`、`StorageService.java`、`StorageServiceImpl.java`、`UploadStorageManager.java`、`UploadServiceImpl.java`、`FileController.java`、`UploadInitResponse.java`、`useUpload.tsx`、`types/index.ts`、`upload-manager.ts`、`types.ts`

# 三、整体设计方案

## 模式判定（init 阶段）

```
rateKb = capRate(serverUploadLimit, clientLimit)
rateBytes = rateKb * 1024
chunkSize = request.chunkSize  // 客户端传入，=5MB
if (rateKb > 0 && rateBytes < chunkSize) {
    relayChunkSize = max(8192, min(rateBytes * 2, 1MB))
    transferMode = "relay"
} else {
    transferMode = "direct"  // 不限速或 rate>=5MB，保持 presigned 直传
}
```

## 直传模式（不变）

init 返回 transferMode=direct + presignedUrls=[]，客户端走现有 chunk-url/confirm/merge。

## 中转模式数据流

```
client init(relay) --> server: 创建 FileNode(UPLOADING) + S3 initMultipart + RelayBufferManager 创建临时文件
client 循环 POST relay-chunk(seq, 小块字节)
   server: 校验 -> acquireUploadPace(字节级阻塞 pacing) -> 追加临时文件 -> 累积≥5MB uploadPart(partNumber++) -> 确认
client POST relay-finalize
   server: 末片 uploadPart(余量) -> completeMultipartUpload -> FileNode COMPLETED -> 删临时文件
```

# 四、前端设计

## useUpload.tsx（st-web）

init 响应判定 `transferMode`：

- `direct`：现有逻辑不变（chunk-url -> PUT S3 -> confirm -> merge）
- `relay`：
  1. 按 `relayChunkSize` 切小块，顺序 POST `/file/upload/relay-chunk`（params: uploadId, s3UploadId, seq；body: Blob）
  2. 进度按已发送字节平滑推进（`seq * relayChunkSize / fileSize`）
  3. 全部小块发完，POST `/file/upload/relay-finalize`
  4. 任务状态显示「限速中转上传中 · 限速 X KB/s」

## types/index.ts

```ts
UploadInitResponse 新增: transferMode: 'direct' | 'relay'; relayChunkSize?: number;
UploadTask 新增: transferMode?: 'direct' | 'relay';
```

## 桌面端

`upload-manager.ts` 同 st-web relay 分支；`types.ts` 同步字段。

# 五、后端设计

## API 设计

### 1. POST /api/file/upload/init（已有，改响应）

响应 `UploadInitResponse` 新增：
- `transferMode`（String，"direct"|"relay"）
- `relayChunkSize`（Long，relay 模式生效，direct 为 null）

### 2. POST /api/file/upload/relay-chunk（新增）

```
参数: uploadId, s3UploadId, seq(int, 从1)
请求体: application/octet-stream（小块字节，大小=relayChunkSize，末块可小）
响应: { confirmed: true, partUploaded: boolean, partNumber: int }
```

逻辑：
1. 校验 uploadId 归属 + 文件 owner/租户权限
2. 从 `HttpServletRequest.getInputStream()` 读取字节（8KB 步进）
3. 每步 `acquireUploadPace(userId, n, rateBytes)` 阻塞 pacing
4. `RelayBufferManager.appendChunk(uploadId, bytes)` 追加临时文件
5. 累积 ≥5MB -> `uploadPart`（partNumber 自增），重置累积
6. 返回确认

### 3. POST /api/file/upload/relay-finalize（新增）

```
参数: uploadId, s3UploadId
响应: FileNodeVO
```

逻辑：
1. 校验 + claimMerging（复用现有原子认领）
2. `RelayBufferManager.finalize(uploadId)` -> 末片 uploadPart（余量<5MB，无下限）
3. `completeMultipartUpload`（复用现有，listParts 获取 ETag）
4. FileNode 状态 -> COMPLETED（复用现有 merge 后续逻辑：事件发布、配额扣减、版本快照）
5. 删除临时文件

## 数据模型（内存+磁盘，不落库）

### RelayBufferManager

```
RelaySession {
  String uploadId;
  Path tempFile;        // 临时缓冲文件
  long accumulated;     // 当前临时文件已累积字节
  int nextPartNumber;   // 下一个 uploadPart 的 partNumber（从1）
  long lastActiveMs;    // 最近活跃时间（超时清理用）
}
```

- `createSession(uploadId)`：创建临时文件（目录由 UploadRelayConfig 配置，文件名=uploadId，防穿越）
- `appendChunk(uploadId, byte[])`：追加，累积≥5MB 触发 uploadPart
- `finalize(uploadId, s3UploadId, storagePath)`：末片 uploadPart
- `cleanup(uploadId)`：删临时文件
- 超时清理：定时任务扫描 lastActiveMs 超时（如 10 分钟）的 session，cleanup + abort

### UserTransferLimiter.UploadPaceBucket

```
阻塞式按字节 pacing（仿 DownloadBucket）：
acquireUploadPace(userId, bytes, rateBytesPerSec):
  if rate<=0 or bytes<=0: return
  按 8KB 步进，令牌不足则 sleep，保证接收速率≤限速
```

与 UploadGate（presigned 门控）独立桶，互不干扰。

## 业务流程

- init：配额/容量校验（不变）-> S3 initMultipart -> 创建 FileNode -> relay 模式额外创建 RelaySession -> 返回模式参数
- relay-chunk：pacing 接收 + 缓冲 + 攒批 uploadPart
- relay-finalize：末片 + merge + 状态更新 + 清理

## 异常处理

- relay-chunk 失败：返回错误，客户端重试同 seq（服务端 seq 幂等：已确认 seq 忽略）
- relay-finalize 失败：abort multipart + 清理临时文件
- 超时未 finalize：定时清理 + abort

# 六、数据库设计

无变更。中转临时文件不持久化。

# 七、安全设计

- 中转端点 `@PreAuthorize("isAuthenticated")` + owner/租户校验（同 chunk-url）
- 临时文件路径服务端生成（uploadId），不接受客户端输入，防穿越
- relay-chunk 校验 Content-Length ≤ relayChunkSize，防超大请求
- 每个请求校验 uploadId 归属当前用户
- 临时文件清理：finalize/失败/超时即删

# 八、性能设计

- relay 模式仅低速率触发，服务端带宽开销受限
- 单 relay-chunk 请求时长 ≈ relayChunkSize/rate = 2s（窗口），阻塞式可控
- 临时文件 I/O：顺序追加，单文件 ≤5MB（攒批即 uploadPart 重置）
- 正常速率（direct）零影响

# 九、开发计划

```
Task-01（后端中转）:
  - StorageService.uploadPart + Impl
  - UserTransferLimiter.UploadPaceBucket
  - RelayBufferManager + UploadRelayConfig
  - UploadServiceImpl: init 模式判定 + relayChunk + relayFinalize
  - FileController: /relay-chunk + /relay-finalize
  - UploadInitResponse: transferMode + relayChunkSize

Task-02（前端中转）:
  - st-web: useUpload relay 分支 + types
  - st-desktop: upload-manager relay 分支 + types
```

# 十、风险分析

| 风险 | 影响 | 解决方案 |
|------|------|---------|
| 临时文件泄漏 | 磁盘占用 | 超时清理 + 失败即删 + 启动扫描 |
| pacing 阻塞线程 | 线程占用 | 单请求≤2s，低速率并发低 |
| 跨请求状态丢失(重启) | 中转上传失败 | MVP 不支持中转断点续传，失败重来 |
| seq 乱序 | 缓冲错乱 | 客户端顺序发送 + 服务端 seq 校验 |
