# 架构设计评审

> 输出标准：`docs/newList/ai-architecture-review-standard.md`
> 落盘路径：`.ai/docs/20260813-upload-rate-throttle/architecture-review.md`
> 归属 TECH_DESIGN（大型任务前置），由 Architect 主笔。评审通过后进入程序设计。

# 一、架构评审基本信息

```
功能名称：上传低速率强制限速中转
业务背景：presigned 直传在限速<5MB 时瞬时突破限速，强制限速须真实达成
目标：rate<分片下限时上传改走服务端中转，瞬时速率不超限速值
涉及模块：st-common/ratelimit、st-core(service/controller/dto/config)、st-web、st-desktop
```

## 影响范围

| 类型 | 模块 | 影响 |
|------|------|------|
| 前端 | st-web/useUpload、st-desktop/upload-manager | 新增 relay 模式分支 |
| 后端 | st-core 上传服务/控制器/存储层 | 新增中转接收+缓冲+uploadPart |
| 后端 | st-common/ratelimit | 新增字节级 pacing 桶 |
| 数据库 | - | 无变更 |
| 缓存 | - | 无影响 |

# 二、需求理解评审

```
业务目标：强制限速（含极低速率）在瞬时维度真实生效
核心流程：init 判定模式 -> relay 小块逐块 POST -> 服务端 pacing 接收+缓冲 -> 攒 5MB uploadPart -> merge
成功标准：任意速率下瞬时速率不超限速值（突发 ≤ relayChunkSize = rate×窗口）
限制条件：S3 multipart 非末片≥5MB；presigned 直传不可 pacing
```

# 三、整体架构设计

### 直传模式（不变）

```
client --GET chunk-url--> server[UploadGate 门控签发] --presigned--> client --PUT--> S3
client --confirm--> server(释放)
```

### 中转模式（新增）

```
client --POST init--> server[判定 rate<5MB -> relay 模式, 返回 relayChunkSize]
client --POST relay-chunk(小块, seq)--> server[UploadPaceBucket pacing 阻塞接收] --> RelayBufferManager 临时文件累积
                                                                  累积≥5MB --> S3 uploadPart(partNumber 自增)
client --POST relay-finalize--> server[末片 uploadPart(余量<5MB)] --> completeMultipartUpload --> 删临时文件
```

# 四、技术方案评估

## 方案对比

| 方案 | 瞬时突发量 | 客户端改动 | 服务端复杂度 | 选用 |
|------|-----------|-----------|-------------|------|
| A. 客户端小块+服务端逐块 pacing 确认+跨请求缓冲 | relayChunkSize(=rate×窗口，最小 8KB) | 中（切小块+顺序 POST） | 中（缓冲管理器） | ✅ 选用 |
| B. 客户端发整 part(5MB)+服务端 pacing 流式接收 | TCP buffer(64~256KB) | 小（仅换端点） | 低 | ✗ 突发仍显著 |
| C. 改 presigned 直传为全程服务端中转 | 0 | 大（所有上传改） | 高 | ✗ 影响正常速率性能 |

**选 A 的理由**：用户明确要求「必须达成」瞬时速率。方案 A 突发量 = relayChunkSize = rate×窗口秒数（如 1KB/s×2s=2KB），真正瞬时。方案 B 突发为 TCP buffer 级（64~256KB），对 1KB/s 限速仍是数百秒配额的突发，不达标。方案 C 牺牲正常速率性能，过度。

## 关键参数

- `relayChunkSize = clamp(rate_bytes_per_sec × 2, 8KB, 1MB)`：平衡节流精度（突发小）与请求频率（2s 一个请求）
- multipart part 攒批阈值：5MB（S3 非末片下限）
- pacing 步进：8KB（与下载 pacedTransfer 一致）

## 评估

- 符合当前技术栈：✅ 复用 S3 SDK multipart + 现有 pacedTransfer 模式
- 不增加不必要复杂度：✅ 仅低速率触发，正常速率零影响
- 方便维护：✅ 模式判定集中 init，传输路径分支清晰

# 五、后端架构评审

## 分层设计

- **Controller**：`FileController` 新增 `/relay-chunk`、`/relay-finalize` 端点，鉴权+参数校验后委托 UploadService
- **Service**：`UploadServiceImpl` 增 `relayChunk`（pacing 接收+缓冲+uploadPart）、`relayFinalize`（末片+merge）；init 增模式判定
- **RelayBufferManager**：新增组件，按 uploadId 隔离临时文件，累积小块、攒批 uploadPart、清理
- **StorageService**：新增 `uploadPart(key, uploadId, partNumber, InputStream, size)` 服务端写入
- **UserTransferLimiter**：新增 `UploadPaceBucket`（阻塞式字节 pacing，仿 DownloadBucket）

## 业务处理

- **事务边界**：中转接收不涉及 DB 事务（临时文件+S3）；merge 复用现有 @Transactional（FileNode 状态更新）
- **并发控制**：同一 uploadId 的 relay-chunk 请求串行（UPLOAD_WINDOW=1 语义延续），RelayBufferManager 对同一 uploadId 同步
- **幂等设计**：relay-chunk 按 seq 顺序接收；重复 seq 忽略（客户端重试安全）；merge 幂等不变
- **异常处理**：小块接收失败返回错误，客户端重试该 seq；服务端异常时 abort multipart + 清理临时文件

# 六、前端架构评审

```
页面：无新增（复用 UploadPanel）
状态：UploadTask 新增 transferMode('direct'|'relay')、relayChunkSize
接口依赖：init 响应判定模式 -> relay 走 /relay-chunk 循环 -> /relay-finalize
```

- useUpload/upload-manager 增 relay 分支：按 relayChunkSize 切小块顺序 POST，进度按字节平滑推进
- relay 模式不调 /chunk-url、/chunk-confirm（直传专用）
- 两种模式共用 /init、/merge

# 七、数据库设计评审

**无变更**。中转临时文件按 uploadId 在本地磁盘隔离，不持久化。file_chunk 记录由服务端 uploadPart 完成后 markChunkUploaded（复用现有）。无需新表/字段/索引。

# 八、缓存设计评审

本次不涉及缓存。限速缓存（SpeedLimitCache）逻辑不变。

# 九、高并发设计评审

```
预估压力：低速率用户并发数有限（限速本身限制吞吐）；正常速率不受影响
瓶颈：中转 pacing 占用请求线程（阻塞式 sleep），低速率下单请求 ~2s
解决方案：relay-chunk 请求时长 ≈ relayChunkSize/rate = 窗口秒数(2s)，可控；异步化非必须（MVP 阻塞式，与下载 pacedTransfer 一致）
```

# 十、安全设计评审

- 中转端点鉴权：`@PreAuthorize("isAuthenticated")` + owner/租户权限校验（同 chunk-url）
- 临时文件路径：服务端按 uploadId 生成，**不接受客户端路径**，防路径穿越
- 请求体大小限制：relay-chunk 单块 ≤ relayChunkSize（≤1MB），服务端校验 Content-Length，防超大请求打满磁盘
- uploadId 校验：每个小块请求校验 uploadId 归属当前用户+文件 owner
- 临时文件清理：finalize/失败/超时即删，防信息残留与磁盘泄漏

# 十一、可扩展性评审

- 模式判定集中 init，新增传输模式只需扩展 transferMode 枚举
- RelayBufferManager 与 UploadService 解耦，可独立演进
- 未来若需中转断点续传：服务端记录已 uploadPart 的 partNumber，init 返回跳过区间即可扩展

# 十二、异常和容错设计

| 异常 | 处理方案 |
|------|---------|
| 客户端中途中断 | uploadId 超时未活跃 -> RelayBufferManager 清理临时文件 + abort multipart |
| 临时文件写入失败 | 返回错误，删临时文件，abort |
| S3 uploadPart 失败 | 重试有限次后失败，清理 + abort |
| 限速值极低 | relayChunkSize 下限 8KB，单请求 ≤2s，不会无限阻塞 |
| 重复 seq | 忽略，返回已确认（幂等） |

# 十三、架构风险分析

| 风险 | 影响等级 | 解决方案 |
|------|---------|---------|
| 中转 pacing 阻塞请求线程 | Medium | 单请求 ≤2s（窗口），低速率并发低；必要时改异步 |
| 临时文件泄漏 | High | 超时清理+失败即删+启动扫描 |
| 跨请求缓冲状态丢失（服务重启） | Medium | 中转模式暂不支持断点续传，重启后该上传失败重来 |
| relayChunkSize 过小致请求过频 | Low | 下限 8KB + 窗口 2s，最频 500req/s（rate=4MB/s 边界，实际此速率走直传） |

# 十四、架构评审结论

```
架构评分：通过
技术方案：方案 A（客户端小块+服务端 pacing 接收+跨请求缓冲+攒批 uploadPart）
主要风险：临时文件泄漏（High，有清理方案）、pacing 阻塞线程（Medium，可控）
优化建议：MVP 阻塞式 pacing；后续可评估异步化；中转断点续传留作扩展
是否进入开发：是
```
