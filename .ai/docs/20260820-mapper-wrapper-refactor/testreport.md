# 测试报告 - Mapper 简单 SQL 改造为 MyBatis-Plus Wrapper

> 任务：将 st-core 5 个 Mapper 共 17 个简单注解 SQL 改造为 LambdaQueryWrapper/LambdaUpdateWrapper（default 方法保签名），复杂 SQL 与跨模块/物理删除类保留原生并补注释。
> 测试日期：2026-08-20。测试执行环境：Windows，JDK 17，Maven Surefire 3.1.2，H2 内存库（MODE=MySQL）。

## 执行方式

按 AGENTS.md 流程标准执行全量 H2 测试（`mvn test`，含 `SchemaConsistencyTest` 三层校验），覆盖全部 11 个 reactor 模块。

## 测试结果总表（以各模块 surefire 报告统计为准）

| 模块 | Tests run | Failures | Errors | 结果 |
|------|-----------|----------|--------|------|
| st-common | 25 | 0 | 0 | PASS |
| st-auth | 8 | 0 | 0 | PASS |
| st-core | 149 | 0 | 0 | PASS |
| st-share | 43 | 0 | 0 | PASS |
| st-team | 36 | 0 | 0 | PASS |
| st-sync | 14 | 0 | 0 | PASS |
| st-search | 46 | 0 | 1 | 1 个环境依赖 Error（见下） |
| st-preview | 5 | 0 | 0 | PASS |
| st-admin | 9 | 0 | 0 | PASS |
| st-api | 1 | 0 | 1 | 1 个环境依赖 Error（见下） |
| **合计** | **336** | **0** | **2** | **334 通过 / 2 环境依赖** |

## 改造方法 ↔ 集成测试覆盖映射（H2 真实执行验证）

| 改造的 Mapper 方法 | 覆盖证据（直接断言或 Service 调用链） |
|--------------------|----------------------------------------|
| FileObjectMapper.selectByTenantAndMd5 / getRefCount | `FileObjectIntegrationTest`、`ConcurrentUploadIntegrationTest`（并发引用计数断言）、`TextFileOverwriteTransactionBoundaryTest`、`ArchiveExtractTransactionBoundaryTest`、`ArchiveServiceIntegrationTest`（revive 场景）、`RecycleBinPhysicalDeleteIntegrationTest` 直接断言 |
| FileObjectMapper.incrementRefCount / decrementRefCount / markDeleted | 上述测试经 Service 上传/删除/回收站流程真实执行（引用计数终值断言即验证 SQL 正确性） |
| FileChunkMapper.markChunkUploaded / selectUploadedChunkIndexes / deleteByUploadId | `UploadStateMachineIntegrationTest`、`RelayUploadIntegrationTest`、`ConcurrentUploadIntegrationTest`（断点续传/秒传/并发上传全链路） |
| FileNodeMapper.selectByMd5 / countByParentAndName / claimMerging / findByMd5 / countOtherRefsByStoragePath | `UploadStateMachineIntegrationTest`（状态机流转含 claimMerging 原子认领）、`FileServiceFlowIntegrationTest`（重名校验/秒传/删除引用判定） |
| FileFavoriteMapper.selectFavoriteNodeIds | `FavoriteServiceIntegrationTest`（testing.md 点名的范式测试） |
| EventLogMapper.markSent / markFailed / selectRetryable | `EventOutboxIntegrationTest`（Outbox 投递/重试链路） |

全部通过，SQL 语义与改造前一致（逻辑删除自动附加 `deleted=0` 条件、`LIMIT 1`、幂等守卫、原子自增 setSql 均经真实 H2 执行验证）。

## 2 个 Error 的定性：存量环境依赖，与本次改造无关

### 1. st-search `NgramSearchIntegrationTest`（setup 阶段 ConnectionClosed）

- 失败点：`ElasticsearchIndicesClient.create` 连接 127.0.0.1:9200 失败
- 定性依据：该测试为连真实 ES 的端到端测试，类注释明确前置条件"ES 8.x + IK 插件 + ingest-attachment 插件已运行"，当前环境 ES 未运行
- 与改造无关的证据：st-search 的 pom 不依赖 st-core/st-common；本次改造未触碰 st-search 任何文件；失败发生在外部网络连接，不涉及 SQL
- 该模块其余 45 个测试全部通过

### 2. st-api `ReindexIntegrationTest`（ApplicationContext 加载失败）

- 失败点：Bean `physicalDeleteMessageConsumer` 初始化时 RocketMQ Remoting 连接 127.0.0.1:9876 失败
- 定性依据：该测试为 `@SpringBootTest` 全应用上下文测试，需本地 RocketMQ NameServer 运行，当前环境未运行
- 与改造无关的证据：失败发生在 Spring Bean 创建阶段的中间件连接，不涉及任何 Mapper/SQL；st-core 全部 149 个测试（含改造 Mapper 的全部集成测试）通过

## 结论

- **判定：TEST_PASS（有条件通过）** -- 334/336 通过；仅有的 2 个 Error 均为外部中间件（Elasticsearch / RocketMQ）未运行导致的存量环境依赖失败，失败链路不经过任何被改造代码，且对应模块的其余测试全部通过
- 本次改造无数据库 schema 变更，不触发 `compare-schema.ps1` 迁移门禁（AGENTS.md「数据库版本管理」适用条件为涉及 schema 变更的迭代）
- 验证命令记录：`mvn test`（首轮，1-8 模块）+ `mvn test -pl st-preview,st-admin,st-api -am "-Dmaven.test.failure.ignore=true"`（补跑 9-11 模块，忽略已知环境依赖失败）
