# TASK-20260912-stcore-security-consistency-P1-07-08

## 任务类型

P1-07～P1-08 Archive 安全与分片上传参数边界。

## 前置条件

- P0-04 已完成上传 session 归属绑定。
- P1-01～06 已集成或已明确不共享写入文件。

## 允许写入

- `st-core/src/main/java/com/stcloud/core/service/impl/ArchiveServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/controller/ArchiveController.java`
- `st-core/src/main/java/com/stcloud/core/config/*Archive*.java`
- `st-core/src/main/java/com/stcloud/core/config/UploadProperties.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/UploadServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/upload/UploadChunkManager.java`
- `st-core/src/main/java/com/stcloud/core/mapper/FileChunkMapper.java`
- `st-core/src/main/java/com/stcloud/core/dto/Upload*.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/ArchiveServiceIntegrationTest.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/ArchiveExtractTransactionBoundaryTest.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/UploadStateMachineIntegrationTest.java`
- `.ai/runtime/results/DISPATCH-20260912-P1-07-08-01.json`

## 禁止写入

- 数据库 SQL/schema、`st-team`、前端、Loop State、共享报告。
- 不把 archive 限制放在单个请求参数中让客户端覆盖，不解除事务/外部网络边界。

## 实现要求

1. Archive 解压限制条目数、单条展开大小、总展开大小、最大深度、压缩比；路径规范化并拒绝 `..`、绝对路径和路径穿越。
2. 采用流式/临时文件和有界 executor，限制排队任务与每用户并发；异常清理临时资源，不能用无界 ByteArrayOutputStream。
3. 浏览预览和解压上传均执行对应权限；控制器不接受跨用户/空间 nodeId。
4. 上传初始化校验 fileSize、totalChunks、chunkSize、clientLimit、MD5 格式及 ceil 关系；批量写入 chunk，服务端 session 值权威。
5. 为 zip bomb、路径穿越、超深目录、超条目、超总量、超并发、非法分片关系和超限 clientLimit 补测试。

## 验收标准

- Archive 与 chunk upload 均具备明确资源上限、失败语义和资源清理。
- Archive 不存在路径穿越、压缩炸弹或无界内存风险。
- 分片参数不能绕过文件/配额/会话约束。

## 验证

- `mvn -pl st-core -am -DskipTests compile`
- 运行 Archive、事务边界和上传状态测试。
- `git diff --check`、`git diff --name-only`。

## 结果契约

结果写入 `.ai/runtime/results/DISPATCH-20260912-P1-07-08-01.json`，包含 `criterionProposal.id=IMPLEMENTED`，不得修改 Loop State。
