# 代码 Review 记录：项目级 Code Review（2026-08-13）

> 输出标准：`docs/newList/ai-code-review-standard.md`
> 审查方式：两轴（标准符合度 + 需求符合度）并行审查；本次因子代理线程上限由主 Agent 扮演 Reviewer 完成两轴审查后汇总。

## 一、Review 概览

```
功能名称：项目级 Code Review（当前工作区全部未提交变更）
Review 范围：git diff HEAD（213136c → 工作区，43 个已跟踪文件 + 未跟踪源码）
涉及模块：st-common / st-core / st-sync / st-search / st-team / st-web / st-desktop / docker/mysql/init
修改文件：85 个变更项（含 5 个新增 SQL、st-core 上传/事件/对象模块、st-sync 块级同步、前端中转体验）
规格依据：.ai/docs/20260813-upload-rate-throttle、20260813-code-review-fix、20260813-block-sync、20260812-schema-versioning、20260812-sync-full-reconcile
标准依据：.ai/knowledge/conventions.md、architecture.md、frontend.md、testing.md、AGENTS.md
```

## 二、标准符合度轴（Standards）

### 硬性违规（违反文档化标准）

1. **`@Autowired(required=false)` 字段注入违反依赖注入约定**（conventions.md：「使用 @RequiredArgsConstructor + final 字段（构造器注入）；不使用 @Autowired 字段注入」）
   - `st-core/.../service/impl/FileServiceImpl.java:67`（cacheFactory）
   - `st-team/.../service/FolderPermissionService.java:40`（cacheFactory）
   - 建议：改用 `ObjectProvider<CacheFactory>` 构造器注入（与 CacheFactory 自身一致），消除字段注入。
2. **迁移脚本幂等约定被违反**（conventions.md：「所有 DDL 使用 IF NOT EXISTS / IF EXISTS 保证幂等」；20260813-code-review-fix 完成标准明确要求「28/30 迁移脚本可重复执行」）
   - `docker/mysql/init/28_file_object.sql:22`：`ALTER TABLE file_node ADD COLUMN object_id` 无 information_schema 守卫，第二次执行报 duplicate column（30 号脚本已用守卫，28 号未用）。
   - 建议：按 30 号脚本模式加列存在性守卫，或拆分为独立幂等脚本。
3. **硬编码魔法数字代替枚举**（UploadStatus 枚举已定义）
   - `st-sync/.../SyncBlockServiceImpl.java:201`：`node.setUploadStatus(2); // 已完成` 应使用 `UploadStatus.COMPLETED.getCode()`。

### 判断项（Fowler 坏味道基线，仓库文档未覆盖处生效）

4. **Duplicated Code**：`UserTransferLimiter.java` 中 `DownloadBucket` 与 `UploadPaceBucket` 两个令牌桶类逐行重复（仅注释不同）；`FileServiceImpl.java` 中个人/团队两套 `copy / move / recycle / createFolder` 方法组重复实现，本次 diff 对两套同时做了相同修改（Divergent Change 风险）。建议抽取公共桶实现与公共文件操作编排。
5. **Mysterious Name / 命名漂移**：`FileNode.java` 的 `uploadStatus` 字段注释仍为「0-待上传 1-上传中 2-已完成 3-失败」，未同步新增的 4-合并中 / 5-已删除；`UploadStatus.isTerminal()` 将 FAILED 判为终态，但 `claimMerging` 允许从 FAILED(3) 重试合并，语义相互矛盾。建议统一状态机文档与注释。
6. **Speculative Generality / 冗余**：`UploadChunkManager.listUploadedChunks`、`FileObjectService.deletePhysical` 等新增能力当前无调用方（或仅测试使用），建议确认是否有真实需求，避免悬空抽象。

### 通过项

- 分层结构（controller/service/impl/mapper/entity/dto/enums/outbox）符合约定；新增 upload 子包、outbox 子包组织清晰。
- 核心逻辑中文注释覆盖权限校验、状态流转、配额计算、去重逻辑、文件处理规则（AGENTS.md 要求）。
- 事件发布统一收敛到 `ReliableEventPublisher` + `UploadEventPublisher`，消除各上传路径重复拼装。
- Mapper 原子 SQL（配额条件更新、claimMerging、markChunkUploaded、refCount 增减）语义正确，含非负守卫。
- 新增文件均为 UTF-8 无 BOM。

## 三、需求符合度轴（Spec）

### 已实现且符合规格

- **upload-rate-throttle F1~F7**：init 判定中转模式并返回 relayChunkSize/relayRateKb；服务端逐块 pacing 节流（`acquireUploadPace`）；缓冲至 5MB 后 uploadPart、末片无下限；临时文件生命周期（失败/超时/完毕即删）；客户端自限速统一走中转；simpleUpload 接 `pacedInputStream` 节流（F6）；前端「限速中转上传中 · 限速 X KB/s」+ ETA + 超时提示（F7，`UploadPanel.tsx` / `TransferManager.tsx` / `useUpload.tsx`）。
- **code-review-fix**：relay 端点存在且前端调用（不 404）；seq 幂等（`tryAcquireSeq`）；relay-finalize owner/租户管理员权限 + 失败 abort S3 并清理；Content-Length 超 relayChunkSize 拒绝；超时会话由 `@Scheduled` 清理并 abort multipart；`RelayUploadIntegrationTest` 14 个用例覆盖 TC-001~013。
- **block-sync（迭代 5）**：file_block 表、uploadPartCopy、block-check/block-upload 两 API、桌面端块哈希缓存与上传分叉（≥8MB 更新走块级，失败回退全量）均落地；与 design.md 相比 block-upload 改为服务端按客户端全量 blocks 重派生可复用块（更简洁，属合理演进）。
- **schema-versioning**：schema_version 表、版本记录、compare-schema.ps1 门禁脚本齐备；H2 schema.sql 与迁移脚本表/列/唯一键一致（file_object/event_log/schema_version/file_block/object_id/event_log_id/uk_event_log_id 均在）。

### 缺失 / 部分实现

1. **「取消时 abort」未覆盖 Web 中转路径**（code-review-fix 完成标准 + uispec）：`useUpload.tsx` relay 循环无取消/中止处理；失败后仅提示，服务端靠 10 分钟超时兜底；桌面端 pause 同样直接放弃会话（服务端超时 abort）。建议 relay 循环接入取消信号并调用 abort API。
2. **「28/30 迁移脚本可重复执行」未完全满足**：28 号脚本不可重复执行（见标准轴 #2）。
3. **块级同步绕过上传限速门控**（与 upload-rate-throttle 的「服务端强制限速不可绕过」原则冲突）：block-check 直接签发预签名 URL（2 小时），块级直传路径未接入 `tryAcquireUpload` 门控与 pacing；低速率用户经块级同步可绕过分片限速。建议块级路径同样按有效限速分流或门控。

### 范围蔓延（规格未要求但已实现）

- `sync-engine.ts` 新增 `fullReconcile` 全量对账（启动递归列举云端全量文件下载缺失项）——未见于 block-sync design.md，但属于 20260812-sync-full-reconcile 迭代范围，非本次新增蔓延；记录其成本：每次启动全量列举 + syncOnce 双扫描，大目录下有性能压力。
- `FileNodeVO` 新增 `fileMd5` 字段（为对账所需），同时向所有客户端暴露内容指纹，需明确信息暴露口径。

### 实现疑似有误

详见问题清单 C1/M1/M2/M3/M5（均为实现与规格/安全预期不符点）。

## 四、代码结构检查

```
通过：上传编排收敛为 UploadServiceImpl 门面 + UploadManager/UploadChunkManager/UploadStorageManager/UploadEventPublisher/RelayBufferManager 职责单一；
问题：FileServiceImpl 个人/团队双份方法组重复（历史遗留，本次 diff 重复修改两套）；UserTransferLimiter 两个桶类逐行重复；
建议：块级同步与会话类资源（S3 multipart、中转缓冲）统一生命周期管理，避免多路径各自为政。
```

## 五、后端代码检查

### Controller 层
- 新增 relay-chunk / relay-finalize 端点仅做参数接收与委托，职责正确；`@PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")` 一致。
- 块级同步端点 `@Auditable` 记录到位。

### Service 层
- 状态机（INIT→UPLOADING→MERGING→COMPLETED，异常→FAILED，中止→DELETED）落地；claimMerging 原子认领 + 重复 merge 幂等良好。
- 事务与外部副作用顺序问题（见问题清单 M2/M4）：S3 完成/上传先于配额扣减，失败后不可恢复。
- `relayFinalize` 自调用 `mergeChunks` 绕过事务代理（M3）。

### 数据访问层
- 配额条件 UPDATE（含 quota 上限与非负守卫）正确；CloudCapacity FOR UPDATE 并发语义正确但持锁范围过大（M6）。
- `file_object` 唯一键与逻辑删除冲突导致去重死锁（C1）。

## 六、前端代码检查

- Web：relay 状态徽标/ETA/超时文案符合 uispec；progress 计算正确；`TransferManager.tsx` 错误块缩进错乱（格式）。
- Desktop：relay 小块上传含重试退避、进度/速度上报、pause/cancel 检查；`database.ts` 块哈希表先删后插幂等。
- 块级上传预签名直传无限速门控（见 Spec #3）；Web relay 无取消 abort（Spec #1）。

## 七、安全检查

- 中转接口权限校验（owner/租户管理员）一致且正确；临时文件路径净化（`sanitize` 防路径穿越）。
- **mergeChunks 无权限校验**（M1）：同文件内 relay/confirm 均校验而 merge 未校验，凭据可猜场景下可完成他人 multipart、改动他人节点并返回其元数据。
- **block-upload 客户端可控 storagePath/s3UploadId/fileMd5/fileSize**（M5）：元数据/配额/引用计数可被操纵；S3 的 uploadId-key 绑定缓解了「任意 key 完成/删除」，但 deleteObjectQuietly 仍以客户端路径为删除目标，一旦绑定校验被绕过即成越权删除，必须服务端绑定会话。
- `FileNodeVO` 暴露 fileMd5：内容指纹可被跨用户比对（Suggestion）。

## 八、性能检查

- CloudCapacity 行锁持有跨 S3 上传 I/O（M6）：同租户上传串行化。
- fullReconcile 启动全量列举 + syncOnce 双扫描（Suggestion）。
- 块级同步未限制预签名 URL 数量/时长（2h）且无 abort，S3 残留风险（M7）。
- 中转缓冲磁盘占用按 uploadId 隔离、上限约 5MB/part，设计合理。

## 九、测试检查

- 新增测试：RelayUploadIntegrationTest(14)、UploadStateMachineIntegrationTest(8)、EventOutboxIntegrationTest(7)、FileObjectIntegrationTest(6)、QuotaConcurrencyIntegrationTest(2)、AccessibleCacheTest、FileServiceFlow/Permission 集成测试、SchemaConsistencyTest、st-search/st-sync 消费者测试、st-team 权限测试。
- **缺失**：`SyncBlockServiceImpl`（block-check/block-upload，含去重删除/配额/版本/块布局主路径）无任何测试，违反 testing.md「涉及 Mapper 的 Service 方法必须有集成测试覆盖主路径」（M8）。
- 构建/全量回归（mvn test / npm build / tsc / verify-loop）属 TEST_PASS 阶段，本次未执行，需在修复后统一验证。

## 十、问题清单

编号 | 问题 | 等级 | 位置 | 建议
--- | --- | --- | --- | ---
C1 | file_object 唯一键(tenant_id,md5)与逻辑删除(deleted=1)冲突：永久删除后同 md5 重传，insertIgnore 恒冲突、acquire 返回 null，simpleUpload 在 object.getStoragePath() 抛 NPE（500），去重永久失效 | Critical | docker/mysql/init/28_file_object.sql:17；FileObjectServiceImpl.acquire；UploadServiceImpl.java:161 | 唯一键含 status/deleted，或冲突时复活 deleted 行（ON DUPLICATE KEY UPDATE 置回 status=0/deleted=0/ref_count=1/新 path），或永久删除时物理删行
M1 | mergeChunks 无 owner/租户管理员权限校验（同文件 relay/confirm 均有），可完成他人 multipart、改他人节点、读他人元数据 | Major | UploadServiceImpl.java:368 | 与 relayFinalize 一致补 owner 或租户管理员校验
M2 | S3 完成/上传在事务内先于配额扣减：consumeQuota 失败后事务回滚但 S3 已 completed，重试 merge 报 NoSuchUpload，节点卡死；blockUpload 同构 | Major | UploadServiceImpl.java:405-440；SyncBlockServiceImpl.java:185-230 | 配额预校验/预扣后再执行 completeMultipart，失败走补偿 abort
M3 | relayFinalize 自调用 @Transactional mergeChunks 绕过 Spring 代理，relay 合并全程无事务；FOR UPDATE 容量锁在无事务下自动提交失效 | Major | UploadServiceImpl.java:552 | 拆内部非事务方法或注入自身代理/独立事务组件
M4 | 28_file_object.sql 不可重复执行（ADD COLUMN 无守卫），违背幂等约定与完成标准 | Major | docker/mysql/init/28_file_object.sql:22 | 加 information_schema 守卫或拆分幂等脚本
M5 | block-upload 信任客户端 storagePath/s3UploadId/fileMd5/fileSize：元数据/配额/引用计数可操纵，deleteObjectQuietly 以客户端路径为目标 | Major | SyncBlockServiceImpl.java:185-193 | block-check 服务端持久化 uploadId 与 path/md5/size 会话，block-upload 只收会话令牌；校验 md5 格式与 blocks 完整性
M6 | 云盘容量 FOR UPDATE 行锁持有跨 S3 上传 I/O，同租户上传串行化 | Major | CloudStorageServiceImpl.java:30,54 | 仅配额接近阈值时上锁，或改为容量预留原子 UPDATE
M7 | 块级同步 multipart 无 abort 路径：客户端放弃/失败回退全量后 S3 分片残留，无超时清理 | Major | SyncBlockServiceImpl.java:88-96（blockCheck） | 新增 block-abort 端点或 block-upload 前置校验失败即 abort；加超时清理任务
M8 | SyncBlockServiceImpl 主路径无测试覆盖（块级去重/配额/版本/布局），违反 testing.md | Major | st-sync/src/test/ | 补 SyncBlockServiceImpl 集成测试（含越权/去重/失败回退）
m1 | 秒传路径不校验 request.fileSize 与已有对象 size 一致性，配额可按虚报大小扣减 | Minor | UploadServiceImpl.java:93-113 | acquire/秒传时校验 size，不一致则走全量
m2 | @Autowired(required=false) 字段注入违反注入约定 | Minor | FileServiceImpl.java:67；FolderPermissionService.java:40 | ObjectProvider 构造器注入
m3 | FileNode.uploadStatus 注释未同步 4/5 状态；isTerminal 与 claimMerging(IN 1,3) 语义矛盾 | Minor | FileNode.java；UploadStatus.java | 同步注释，明确 FAILED 可重试合并
m4 | BlockCheckRequest.BlockHash 无字段校验（index/md5/size 可空、blocks 可空）导致服务端 NPE 风险 | Minor | BlockCheckRequest.java | 补 NotNull/Size/Pattern 校验
m5 | 硬编码 uploadStatus=2 魔法数字 | Minor | SyncBlockServiceImpl.java:201 | 用 UploadStatus.COMPLETED
m6 | Web relay 无取消/失败 abort（靠 10min 超时兜底） | Minor | useUpload.tsx:160-190 | 接入取消信号并调 abort
m7 | 块级直传绕过上传限速门控（低速率可绕过分片限速） | Minor(合规) | SyncBlockServiceImpl.java:96-100 | 按有效限速分流或接入 tryAcquireUpload 门控
s1 | FileServiceImpl 个人/团队双套方法重复、UserTransferLimiter 双桶重复 | Suggestion | FileServiceImpl.java / UserTransferLimiter.java | 抽取公共实现
s2 | fullReconcile 启动全量列举 + syncOnce 双扫描 | Suggestion | sync-engine.ts:119,449-545 | 合并首轮扫描或增量游标
s3 | FileNodeVO 暴露 fileMd5 内容指纹 | Suggestion | FileNodeVO.java | 明确产品口径或仅同步场景返回

## 十一、Review 结论

```
总体评分：不通过（存在 1 项 Critical、8 项 Major）
是否建议合并：否 —— 必须先修复 C1 与 M1/M2/M3/M5（数据一致性/越权/事务边界），再复检 M4/M6/M7/M8 后进入测试阶段
必须修改项：C1、M1、M2、M3、M4、M5
优化建议：M6~M8、m1~m7、s1~s3（可纳入后续迭代）
```

### 两轴汇总

- **Standards 轴**：3 项硬性违规（@Autowired 字段注入、28 号脚本幂等、魔法数字）+ 3 项坏味道判断项；最严重为迁移脚本幂等违规。
- **Spec 轴**：upload-rate-throttle 与 code-review-fix 主体需求全部落地；2 项完成标准未满（28 脚本可重复执行、Web 取消 abort）+ 块级同步绕过限速；最严重为「28/30 可重复执行」未达成与取消 abort 缺失。
