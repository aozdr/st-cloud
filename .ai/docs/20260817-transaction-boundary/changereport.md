# Change Report：20260817-transaction-boundary

## TX-06 解压/OnlyOffice 回调/文本覆盖改造（F5）

> taskCode: tx06 / dispatchId: WT-TX06-20260817 / executor（taskType=implement）

### 修改文件清单

| 文件 | 变更 |
|------|------|
| `.ai/worktrees/tx06/st-core/.../upload/UploadCommitManager.java` | 新增 userQuotaMapper/teamStorageMapper/reliableEventPublisher 依赖与三个事务提交方法：`commitTextOverwrite`、`commitEditorSave`、`commitExtract`；`createFolderNode`/`createFileNode` 承接原 ArchiveServiceImpl 逻辑 |
| `.ai/worktrees/tx06/st-core/.../text/TextFileServiceImpl.java` | `overwriteContent` 移除 `@Transactional`：去重预查 + S3 上传移至事务外，DB 写委托 commitTextOverwrite；事务失败按引用归零规则尽力清理 |
| `.ai/worktrees/tx06/st-core/.../editor/EditorCallbackServiceImpl.java` | `handleCallback` 移除 `@Transactional`：URL 下载 + S3 上传移至事务外，DB 写委托 commitEditorSave；编辑标记移除移至提交后事务外 |
| `.ai/worktrees/tx06/st-core/.../service/impl/ArchiveServiceImpl.java` | `extractArchive` 移除 `@Transactional`：ZIP 下载到临时文件 → 预检 → 逐条目 S3 上传（事务外）→ commitExtract 一个事务落库；失败清理 + 定时兜底 |
| `.ai/worktrees/tx06/st-core/src/test/.../ArchiveServiceIntegrationTest.java` | 测试配置补齐 UploadCommitManager/UploadStorageManager 及依赖 bean |
| `.ai/worktrees/tx06/st-core/src/test/.../EditorCallbackIntegrationTest.java` | 测试配置补齐同样 bean 集合 |
| `.ai/worktrees/tx06/st-core/src/test/.../TextFileOverwriteTransactionBoundaryTest.java`（新增） | 断言文本覆盖 S3 上传在事务外、DB 提交、DB 失败清理孤儿对象 |
| `.ai/worktrees/tx06/st-core/src/test/.../EditorCallbackTransactionBoundaryTest.java`（新增） | 断言回调 S3 上传在事务外、DB 更新在事务内、DB 失败清理孤儿对象 |
| `.ai/worktrees/tx06/st-core/src/test/.../ArchiveExtractTransactionBoundaryTest.java`（新增） | 断言解压 S3 上传在事务外、单事务落库、DB 失败清理全部本次上传对象 |

### 与验收标准对照

- [x] 三处方法的 S3/外部网络调用全部在事务外：边界测试在 S3 上传回调内断言 `TransactionSynchronizationManager.isActualTransactionActive()=false`
- [x] 失败清理：已上传对象按"无记录或引用归零且路径一致才删除"规则尽力清理（`deleteObjectQuietly`），失败不阻断主流程，残留定时任务兜底；三处失败路径均有测试
- [x] 配额/引用/去重语义不变：文本与回调差值配额 + 旧引用释放 + 去重归属；解压逐条目原子扣配额 + `syncRefCountByMd5` 校正 + 去重复用/墓碑 revive
- [x] 解压进度回调语义不变：`begin(totalFiles)` 预检后触发，`onFileExtracted()` 按落库文件数在全部成功后触发
- [x] 未修改 API/业务规则：无表结构变更；解压按 design.md 不新增事件（旧实现无事件）
- [x] 主工作树源码零改动；未运行 git/mvn/npm（forbidGitMvn）

### 测试结果

- 本任务遵守 forbidGitMvn，未运行 mvn/npm/git；`mvn test` 由主线程合并后统一执行
- 新增边界测试 3 类共 6 用例；既有 ArchiveServiceIntegrationTest / EditorCallbackIntegrationTest 配置补齐 bean（@Bean + @Resource，与 UploadTransactionBoundaryTest 同款模式）
- 静态自检：三处服务 `@Transactional` 已移除；UploadCommitManager 新方法 public 非 final，经跨 bean 调用由 Spring 代理保证事务；新增依赖在全部相关测试上下文均有 bean

### 风险

- "去重预查（事务外）→ 事务内 acquireByPath"存在并发窗口：冗余上传同 key 同内容不损坏数据，按引用归零规则清理 + 定时兜底（与 D1 决策一致）
- 解压先下载整包到本地临时文件，超大 ZIP 增加磁盘占用与下载耗时；进度回调在事务提交后触发，回调异常不再回滚已落库数据（数据正确，仅异常语义变化）
- `commitEditorSave`/`commitTextOverwrite` 的 object==null 防御分支异常类型由 `EditorCallbackRejectedException` 改为 `BusinessException`（正常流程不可达，对外均 5xx）
- 主线程合并后重点回归：ArchiveServiceIntegrationTest、EditorCallbackIntegrationTest、三个新增边界测试、既有上传事务边界测试

## TX-04 块上传 S3 复制移出事务（F3）

> taskCode: tx04 / dispatchId: WT-TX04-20260817 / executor（taskType=implement）

### 修改文件清单

| 文件 | 变更 |
|------|------|
| `.ai/worktrees/tx04/st-sync/src/main/java/com/stcloud/sync/service/impl/SyncBlockServiceImpl.java` | `blockUpload` 移除 `@Transactional`：S3 `uploadPartCopy` 循环 + `completeMultipartUpload` 全部在事务外执行；DB 落库委托 `SyncBlockCommitManager.commitBlockUpload`；去重命中清理移至事务提交后 |
| `.ai/worktrees/tx04/st-sync/src/main/java/com/stcloud/sync/service/impl/SyncBlockCommitManager.java`（新增） | 独立事务协作 bean：`commitBlockUpload` 单事务内完成 去重归属(acquireByPath) + 节点更新 + 旧对象引用释放 + 版本快照 + 块布局重建 + 同步事件 + 差值配额 |
| `.ai/worktrees/tx04/st-sync/src/test/java/com/stcloud/sync/service/impl/SyncBlockServiceImplBlockUploadTest.java`（新增） | 单元测试：S3 复制/合并在事务外、DB 落库委托独立事务方法、去重命中清理在提交后且仅一次、S3 失败不落库不清理 |
| `.ai/worktrees/tx04/st-sync/src/test/java/com/stcloud/sync/service/impl/SyncBlockCommitManagerTest.java`（新增） | 单元测试：commitBlockUpload 为 `@Transactional` 方法，单次调用完成全部 DB 写（含块布局重建），不触发任何 S3 调用 |

> 设计决策：采用「新增等价协作 bean」而非扩展 `UploadCommitManager`——`UploadCommitManager` 位于 st-core，而 `FileBlock`/`FileBlockMapper` 属于 st-sync（st-sync 依赖 st-core，反向不可），块布局重建必须与其余 DB 写在同一事务内，因此按 design.md 3.3 F3 的「复用 UploadCommitManager 或新增等价协作 bean」取后者。st-sync 依赖 st-core 的 FileObjectService/VersionService/UploadEventPublisher/UploadManager 均正常注入。

### 与验收标准对照

- [x] `blockUpload` 的 S3 调用（UploadPartCopy 循环、completeMultipartUpload）全部在事务外（方法无 `@Transactional`；单测断言调用前后 `isActualTransactionActive()=false`）
- [x] DB 写入单事务原子：节点 + 版本快照 + 块布局 + 配额 + 事件全部收敛进 `SyncBlockCommitManager.commitBlockUpload`（`@Transactional`，跨 bean 调用经 Spring 代理生效）
- [x] 去重命中清理（`deleteObjectQuietly`）在事务提交后执行、仅清理本次合并产物路径、幂等（不误删被引用对象），与 `mergeChunks`/`finalizeMerge` 清理模式一致
- [x] 未修改 API/业务规则：接口签名、md5 去重归属语义（`acquire` 改为等价的 `acquireByPath`，supplier 恒返回请求路径）、版本快照/块布局/差值配额/事件逻辑与改造前一致

### 测试结果

- 本任务遵守 forbidGitMvn，未运行 mvn/npm/git；构建与 `mvn test` 由主线程合并后统一执行
- 新增单元测试 7 个用例：`SyncBlockServiceImplBlockUploadTest` 4 个（S3 事务外 + 委托落库 / 去重命中清理时机 / complete 失败 / copy 失败）、`SyncBlockCommitManagerTest` 3 个（去重命中全量 DB 写 / 新建对象 / 空 md5 兜底）
- 静态自检：`SyncBlockServiceImpl` 已无 `@Transactional` 与事务内 S3 调用；`SyncBlockCommitManager` 方法 public 非 final、`@Transactional` 经跨 bean 调用生效；无新增依赖；未触碰其它模块

### 风险

- D1 并发窗口沿用设计决策：同 md5 并发上传冗余对象由失败方按自身 key 尽力清理 + 定时任务兜底，语义与改造前一致
- 若 `commitBlockUpload` 事务回滚：S3 合并产物已存在但无对象记录/引用，由调用方清理逻辑与定时任务兜底（与 F2-1/F2-2 相同失败语义）
- 主线程合并后需重点回归：`SyncBlockServiceImplBlockUploadTest` / `SyncBlockCommitManagerTest` / 既有 `SyncBlockServiceImplNoTransactionTest`，以及 st-sync 全量测试

## TX-03 事务超时配置与规范固化

> taskCode: tx03 / dispatchId: WT-TX03-20260817 / executor（taskType=implement）

### 修改文件清单

| 文件 | 变更 |
|------|------|
| `.ai/worktrees/tx03/st-api/src/main/resources/application.yml` | spring 配置下新增 `transaction.default-timeout: 30s`（含中文注释） |
| `.ai/worktrees/tx03/AGENTS.md` | 「代码修改强制约束」标题改为 8 条，新增第 8 条事务边界 |
| `.ai/worktrees/tx03/.ai/knowledge/conventions.md` | 新增「事务边界」小节（五条原则 + 反例清单） |

### 与验收标准对照

- [x] `application.yml` 含 `spring.transaction.default-timeout: 30s`，位于 spring 配置块下
- [x] `AGENTS.md`「代码修改强制约束」第 8 条就位：`8. 事务边界：核心写路径禁止在事务内执行 S3/外部网络调用（S3 先做、DB 后落；删除类走提交后异步补偿）`
- [x] `conventions.md` 事务边界小节就位：五条原则（网络调用在事务外 / 删除类异步补偿 / 只读方法不开事务 / 长事务显式 timeout / 半成品 tmp 前缀 + 清理兜底）+ 反例清单（ArchiveServiceImpl.extractArchive、UploadServiceImpl.simpleUpload/mergeChunks、EditorCallbackServiceImpl.handleCallback、SyncBlockServiceImpl.blockCheck/blockUpload、RecycleBinServiceImpl 永久删除系列、TextFileServiceImpl.overwriteContent）
- [x] 未触碰任何 st-* 产品代码（仅 application.yml 配置文件）；主工作树源码零改动

### 测试结果

- 本任务为配置与规范修改，无代码测试
- 已静态自检：YAML 层级正确（`transaction` 位于 `spring:` 下、两空格缩进、`30s` 为合法 Duration 写法）；三个文件均为 UTF-8 无 BOM
- 构建验证（配置不破坏启动）由主线程在合并后执行，本任务遵守 forbidGitMvn 未运行 mvn/npm/git

### 风险

- `spring.transaction.default-timeout` 为全局兜底，不覆盖显式 `@Transactional(timeout=...)` 的方法；第二迭代长事务改造按 design.md F6-1 显式标注
- 配置仅影响新开启的事务，对已有运行中事务无影响

## TX-02 只读方法去事务（F1-1 / F1-2）

> taskCode: tx02 / dispatchId: WT-TX02-20260817 / executor（taskType=implement）

### 修改文件清单

| 文件 | 变更 |
|------|------|
| `.ai/worktrees/tx02/st-share/src/main/java/com/stcloud/share/service/impl/ShareServiceImpl.java` | `getDownloadUrl` 删除 `@Transactional`（F1-1），逻辑不变，新增中文注释说明 |
| `.ai/worktrees/tx02/st-sync/src/main/java/com/stcloud/sync/service/impl/SyncBlockServiceImpl.java` | `blockCheck` 删除 `@Transactional`（F1-2）；S3 `initMultipartUpload` 调用移至块布局查询之前（事务外，方法整体无事务），逻辑不变 |
| `.ai/worktrees/tx02/st-share/src/test/java/com/stcloud/share/ShareServiceImplNoTransactionIntegrationTest.java` | 新增集成测试：H2 真实 Mapper + `@Transactional(NOT_SUPPORTED)` 挂起外层事务，断言 `getDownloadUrl` 调用前后 `isActualTransactionActive()=false`，并验证重复调用下载计数正常递增 |
| `.ai/worktrees/tx02/st-sync/src/test/java/com/stcloud/sync/service/impl/SyncBlockServiceImplNoTransactionTest.java` | 新增单元测试：纯 Mockito，断言 `blockCheck` 不开启事务、S3 init 成功返回 uploadId/存储路径；S3 init 失败直接抛错且不执行后续 DB 查询 |

### 与验收标准对照

- [x] `getDownloadUrl` 无 `@Transactional`，权限校验/下载计数/预签名 URL 逻辑不变
- [x] `blockCheck` 无 `@Transactional`，S3 init 在事务外（方法整体无 DB 写、无事务），S3 失败直接抛错返回
- [x] 新增/调整测试验证两方法不开启事务（TransactionSynchronizationManager.isActualTransactionActive 断言）
- [x] 未修改任何 API/业务规则；`@Transactional` import 保留（类内其它写方法仍在使用）

### 测试结果

- 本任务遵守 forbidGitMvn，未运行 mvn/npm/git；构建与 `mvn test` 由主线程合并后统一执行
- 静态自检：两方法注解已移除、方法体逻辑与改造前一致（仅 S3 init 位置前移）；测试编译依赖（spring-tx / mockito / junit5）均来自现有 test 依赖，无新增依赖

### 风险

- `getDownloadUrl` 内下载计数 UPDATE 为单条原子 SQL，无事务时独立自动提交，行为与改造前一致（无多写一致性需求）
- `blockCheck` S3 init 前移后，S3 失败时不再执行块布局查询，响应行为不变（失败直接返回错误）
- 主线程合并后需运行 `mvn test`（st-share / st-sync）验证新增测试与既有测试全绿

## TX-01 上传路径事务边界改造（F1-3 / F2-1 / F2-2）

## 迭代总结（主线程 / workflow-manager）

- 执行时间: 2026-08-17
- dispatch: 两迭代 × 3 worktree 并行批次，全部经 wait-claim 动态 ACK 门禁顺序准入、并发执行

### 验证结果

| 迭代 | 批次 | mvn test | 隔离断言 | 清理 |
|------|------|---------|---------|------|
| 第一迭代 | TX-01/TX-02/TX-03 | BUILD SUCCESS（含上传边界/去事务断言） | 主工作树零 st-* 改动 | REMAINING_WORKTREES=0 |
| 第二迭代 | TX-04/TX-05/TX-06 | BUILD SUCCESS（含 PHYSICAL_DELETE/归档/回调/块提交边界） | 主工作树零 st-* 改动 | REMAINING_WORKTREES=0 |

### 落地清单（F1-F7）

- F1：`getDownloadUrl` / `blockCheck` 去 `@Transactional`；`checkInstantUpload` 只读/写分离
- F2：`simpleUpload` / `mergeChunks` 的 S3 调用移出事务，DB 落库收敛进 `UploadCommitManager`
- F3：`blockUpload` 的 S3 复制/合并移出事务（`SyncBlockCommitManager`）
- F4：回收站永久删除 S3 物理删除改异步补偿（`PHYSICAL_DELETE` outbox + MQ 消费者 + 本地 AFTER_COMMIT 兜底，幂等）
- F5：`extractArchive` / `handleCallback` / `overwriteContent` 的 S3/外部下载移出事务
- F6：`spring.transaction.default-timeout: 30s` 全局兜底
- F7：AGENTS.md 第 8 条 + conventions.md 事务边界小节（五条原则 + 反例清单）

### 已知留痕

- 删除类补偿：MQ 路径失败走 event_log `status=2` 重投，耗尽保留记录供人工清理（D3）
- 本地兜底（无 MQ）失败仅日志，属单实例降级路径
- 解压改为先下载整包到临时文件，超大 ZIP 增加磁盘占用与下载耗时（design 已记录）
- 主分支 ahead 20，未推送；协调文件（state/tasks）随迭代提交

> taskCode: tx01 / dispatchId: WT-TX01-20260817 / executor（taskType=implement）

### 修改文件清单

| 文件 | 变更 |
|------|------|
| `.ai/worktrees/tx01/st-core/src/main/java/com/stcloud/core/service/FileObjectService.java` | 新增 `acquireByPath(tenantId, md5, size, storagePath)` 接口声明：仅 DB 操作、不触发 S3/上传 |
| `.ai/worktrees/tx01/st-core/src/main/java/com/stcloud/core/service/impl/FileObjectServiceImpl.java` | `acquire` 的"上传后归属"逻辑抽为 `acquireByPath`（select → 命中 incrementRefCount / 未命中 insertIgnore + 竞争复用 + 墓碑 revive），`acquire` 语义不变并委托之 |
| `.ai/worktrees/tx01/st-core/src/main/java/com/stcloud/core/service/impl/UploadServiceImpl.java` | `checkInstantUpload` / `simpleUpload` / `mergeChunks` 移除 `@Transactional`：只读检查与 S3 调用全部移出事务，DB 落库委托给 UploadCommitManager；`simpleUpload` 事务失败按 ref_count==0 规则尽力清理孤儿对象；`mergeChunks` 去重命中的 `deleteObjectQuietly` 移到事务提交后 |
| `.ai/worktrees/tx01/st-core/src/main/java/com/stcloud/core/service/impl/upload/UploadCommitManager.java`（新增） | 独立事务协作 bean：`createInstantNode`（秒传命中：引用+节点+配额+事件）、`commitSimpleUpload`（简单上传落库）、`finalizeMerge`（分片合并落库：markChunksMerged+归属+节点+版本快照+差值配额+事件） |
| `.ai/worktrees/tx01/st-core/src/main/java/com/stcloud/core/service/impl/upload/UploadManager.java` | `handleMergeFailure` 标注 `@Transactional`，收敛为独立小事务（仅 DB 状态写，S3 已在事务外） |
| `.ai/worktrees/tx01/st-core/src/test/java/.../FileObjectIntegrationTest.java` | 新增 `acquireByPath` 未命中创建/命中复用用例，断言零 S3 调用 |
| `.ai/worktrees/tx01/st-core/src/test/java/.../ConcurrentUploadIntegrationTest.java` | 新增 `acquireByPath` 并发竞争用例：单一对象 + 引用计数 = 请求数 |
| `.ai/worktrees/tx01/st-core/src/test/java/.../UploadTransactionBoundaryTest.java`（新增） | 独立事务边界测试：非秒传路径不开启事务、秒传创建在事务内、S3 complete 在事务外、simpleUpload DB 失败后清理孤儿对象 |
| `.ai/worktrees/tx01/st-core/src/test/java/.../RelayUploadIntegrationTest.java` / `UploadStateMachineIntegrationTest.java` | 测试配置补充 `UploadCommitManager` bean（@Resource 装配） |

### 与验收标准对照

- [x] `acquireByPath` 存在且不触发任何 S3 调用（FileObjectIntegrationTest `verifyNoInteractions(storageService)` 断言）
- [x] `checkInstantUpload` 非命中路径不开启事务（UploadTransactionBoundaryTest 在 `findByTenantAndMd5` 回调与调用后断言 `isActualTransactionActive()=false`）
- [x] `simpleUpload` S3 限速上传在事务外；事务失败按"无记录或 ref_count==0 才删除"规则清理，不误删被引用对象（UploadTransactionBoundaryTest `simpleUpload_dbFailure_cleansUploadedObject`）
- [x] `mergeChunks` 的 `completeMultipartUpload` / `abort` / 去重 `deleteObjectQuietly` 全部在事务外（S3 回调事务状态断言 + 去重清理移至提交后）
- [x] 并发幂等保持：`claimMerging` 原子认领不变，既有 UploadStateMachineIntegrationTest 合并成功/失败重试/幂等用例未改语义
- [x] 未修改 API 契约、数据库表结构、配额/引用/去重业务规则

### 测试结果

- 本任务遵守 forbidGitMvn，未运行 mvn/npm/git；构建与 `mvn test` 由主线程合并后统一执行
- 新增/调整测试共 9 个用例：FileObjectIntegrationTest +2、ConcurrentUploadIntegrationTest +1、UploadTransactionBoundaryTest +4、既有两上传测试配置适配
- 静态自检：新增 bean 无接口（CGLIB 代理）且方法 public、非 final，`@Transactional` 经跨 bean 调用生效；`UploadCommitManager` 依赖（fileObjectService/fileService/versionService/chunkManager/uploadManager/uploadEventPublisher/fileNodeMapper）在三个相关测试上下文均存在；上传 API/接口签名未变

### 风险

- D1 并发窗口（两请求同时上传同一 md5、均未命中后 insertIgnore 竞争）：沿用"维持现状容忍"，失败方按 ref_count==0 规则清理自身对象；同 key 冗余上传内容一致（同 md5），不会损坏数据，残留由定时清理兜底
- `simpleUpload` 预查与落库间存在检查-执行窗口，但配额以事务内 `consumeQuota` 原子 UPDATE 为最终守卫，语义与改造前一致
- `UploadManager.handleMergeFailure` 增加 `@Transactional` 后，在既有 @Transactional 测试类中并入外层测试事务，行为不变；生产环境 S3 失败路径落 FAILED 状态即提交，符合"独立小事务"设计
- 主线程合并后需重点回归：UploadStateMachineIntegrationTest（merge 成功/失败/幂等/恢复）、RelayUploadIntegrationTest（tc013 限速、tc009 失败清理）、ConcurrentUploadIntegrationTest（并发去重）

## TX-05 回收站永久删除异步补偿（F4）

> taskCode: tx05 / dispatchId: WT-TX05-20260817 / executor（taskType=implement）

### 修改文件清单

| 文件 | 变更 |
|------|------|
| `.ai/worktrees/tx05/st-core/src/main/java/com/stcloud/core/service/impl/RecycleBinServiceImpl.java` | `permanentDeleteNodeAndChildren` 移除事务内 S3 调用（`deletePhysical` / `deleteObject`）：引用归零改为发布 `PHYSICAL_DELETE` outbox 事件；旧数据（无 objectId）按 storage_path 判重归零同样发布事件；移除不再使用的 StorageService 字段与 import |
| `.ai/worktrees/tx05/st-core/src/main/java/com/stcloud/core/event/ReliableEventPublisher.java` | 新增 `publishPhysicalDelete(FileNode)`：事务内写 `event_log`（eventType=PHYSICAL_DELETE，payload 含 storagePath/md5/tenantId/objectId/eventLogId）；MQ 配置时发布 OutboxRelayEvent 走 EventRelay AFTER_COMMIT 投递，MQ 未配置时发布本地 PhysicalDeleteEvent + markSent |
| `.ai/worktrees/tx05/st-core/src/main/java/com/stcloud/core/event/EventMessage.java` | 新增 `fromPhysicalDelete` 构建器，复用 FileNodeSnapshot 承载删除所需字段 |
| `.ai/worktrees/tx05/st-core/src/main/java/com/stcloud/core/event/PhysicalDeleteEvent.java`（新增） | 本地兜底事件载体（ApplicationEvent） |
| `.ai/worktrees/tx05/st-core/src/main/java/com/stcloud/core/event/PhysicalDeleteMessageConsumer.java`（新增） | MQ 消费者：`@RocketMQMessageListener(topic="PHYSICAL_DELETE", consumerGroup="stcloud-core")` + `@ConditionalOnProperty(rocketmq.name-server)`；objectId 路径委托 `fileObjectService.deletePhysical`（幂等 + 标记失效），旧数据按 storagePath 删；失败仅日志不重抛 |
| `.ai/worktrees/tx05/st-core/src/main/java/com/stcloud/core/event/PhysicalDeleteEventListener.java`（新增） | 本地兜底：`@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)` 删除 S3（幂等），失败不阻塞主流程 |
| `.ai/worktrees/tx05/st-core/src/test/java/com/stcloud/core/event/PhysicalDeleteMessageConsumerTest.java`（新增） | 单元测试 5 例：objectId 路径 / 旧数据路径 / 失败不重抛 / 空消息忽略 / 缺键跳过 |
| `.ai/worktrees/tx05/st-core/src/test/java/com/stcloud/core/event/PhysicalDeleteEventListenerTest.java`（新增） | 单元测试 4 例：两条删除路径 / 失败不重抛 / 缺键跳过 |
| `.ai/worktrees/tx05/st-core/src/test/java/com/stcloud/core/service/impl/RecycleBinPhysicalDeleteIntegrationTest.java`（新增） | 集成测试 2 例：引用归零才发布事件、事务内无 S3 调用、提交后本地兜底删除并标记对象失效、payload 断言（storagePath/md5/tenantId/eventLogId） |
| `.ai/worktrees/tx05/st-core/src/test/java/com/stcloud/core/service/impl/FileServiceFlowIntegrationTest.java` / `FileServicePermissionIntegrationTest.java` | 测试配置适配：RecycleBinServiceImpl 移除已删除的 storageService 字段装配（字段随重构移除，保留会抛 IllegalArgumentException） |

### 与验收标准对照

- [x] 永久删除事务内无 S3 调用：`RecycleBinServiceImpl` 已无 storageService / deletePhysical / deleteObject 引用；集成测试在事务内 `verify(never)`，提交后由 AFTER_COMMIT 兜底执行
- [x] `PHYSICAL_DELETE` 事件在引用归零时发布：`release()` 后 remaining<=0 或 `countOtherRefsByStoragePath==0` 才 `publishPhysicalDelete`；集成测试断言 event_log 行数与 payload
- [x] 消费端/本地兜底删除 S3 幂等：`deletePhysical` 幂等（对象已失效即跳过、S3 delete 幂等），失败仅日志不重抛，不阻塞主流程
- [x] 未修改 API/DB/业务规则：接口契约、`event_log` 表结构、引用归零/配额退还语义不变（仅删除时序从事务内同步改为提交后异步）

### 测试结果

- 本任务遵守 forbidGitMvn，未运行 mvn/npm/git；构建与 `mvn test` 由主线程合并后统一执行
- 新增单元测试 9 例 + 集成测试 2 例；静态自检：`EventRelay.topicOf` 已按事件类型映射主题，无需改投递器；MQ/本地双通道与 FileIndex 模式一致（MQ 配置时本地事件不发布、不重复消费）

### 风险

- 异步删除窗口：引用归零到 S3 实际删除之间存在短暂延迟；MQ 路径失败由 event_log status=2 重投补偿，重试耗尽保留记录供人工清理（D3 定版）
- 本地兜底（无 MQ）失败仅日志、无自动重试：属单实例降级路径，失败由运维按日志清理，符合"失败不阻塞主流程"验收
- 主线程合并后需重点回归：RecycleBinPhysicalDeleteIntegrationTest / PhysicalDeleteMessageConsumerTest / PhysicalDeleteEventListenerTest / FileServiceFlowIntegrationTest / FileServicePermissionIntegrationTest / EventOutboxIntegrationTest
