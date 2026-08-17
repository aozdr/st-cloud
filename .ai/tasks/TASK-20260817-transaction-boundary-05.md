# TASK：第二迭代 TX-05 — 回收站永久删除异步补偿（F4）

> 依据 `.ai/docs/20260817-transaction-boundary/design.md` 3.2/3.3 节 F4 与 P1/D3 决策。

## 元信息

- Task ID: `TASK-20260817-transaction-boundary-05`
- 关联任务 State: `.ai/state/20260817-transaction-boundary.yaml`
- 关联文档: `.ai/docs/20260817-transaction-boundary/design.md`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-17

## 目标

回收站永久删除系列（`permanentDelete` / `emptyRecycleBin` / `purgeNode` / `permanentDeleteAdmin`）：S3 物理删除移出事务，改为提交后异步补偿：

1. `RecycleBinServiceImpl.permanentDeleteNodeAndChildren`：事务内只做 DB（引用归零、配额退还、删除记录、发布事件）；S3 `deletePhysical` / `deleteObject` 移除
2. `ReliableEventPublisher` 新增 `publishPhysicalDelete(FileNode)`：写 `event_log`（`eventType=PHYSICAL_DELETE`，payload 含 storagePath/md5/tenantId），沿用 AFTER_COMMIT 投递（EventRelay 的 `topicOf` 已按事件类型映射主题，无需改投递器）
3. `EventMessage` 新增 `fromPhysicalDelete` 构建器
4. 新增物理删除消费端（参照 `FileIndexMessageConsumer` 模式）：`@RocketMQMessageListener(topic="PHYSICAL_DELETE", consumerGroup="stcloud-core")` + `@ConditionalOnProperty(name="rocketmq.name-server")`，删除 S3 对象；幂等（`deleteObjectQuietly`）
5. 本地兜底：`@TransactionalEventListener(AFTER_COMMIT)` 处理器删除 S3（幂等），保证无 MQ 配置的单实例部署仍能清理
6. 仅在对象引用归零（ref_count==0 或 `remaining <= 0`）时发布事件；删除前不重复删被引用对象

## 修改范围

- `st-core`：`RecycleBinServiceImpl.java`、`ReliableEventPublisher.java`、`EventMessage.java`、新增物理删除消费者/监听器、对应测试

## 禁止修改范围

- 其它模块；回收站接口契约与业务规则（引用归零/配额退还语义）不变
- 数据库表/字段/迁移不变（复用 event_log）
- 不运行 mvn/npm/git；不写 `.ai/` 除 changereport 外内容

## 验收标准

- [ ] 永久删除事务内无 S3 调用（测试断言）
- [ ] `PHYSICAL_DELETE` 事件在引用归零时发布（event_log 断言）
- [ ] 消费端/本地兜底删除 S3 幂等；失败不阻塞主流程
- [ ] 未修改 API/DB/业务规则

## 测试要求

- 本任务不自行运行构建；主线程合并后统一 `mvn test`
- 新增测试：引用归零才发布事件；消费端删除调用；删除失败重试/日志不抛

## 输出要求

完成后追加 `.ai/docs/20260817-transaction-boundary/changereport.md` 的「TX-05」章节，并返回 State Delta。
