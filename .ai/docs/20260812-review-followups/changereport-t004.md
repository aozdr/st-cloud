# Change Report：TASK-004 st-search/st-sync 模块级测试

> 关联 Task: .ai/tasks/TASK-004.md  归属: IMPLEMENTED

## 修改文件清单
| 文件 | 变更类型 | 说明 |
|------|----------|------|
| st-sync/pom.xml | 修改 | 新增 spring-boot-starter-test（test scope） |
| st-sync/src/test/.../SyncChangeMessageConsumerTest.java | 新增 | 5 用例：新写/幂等跳过/唯一键冲突/异常重抛/空消息 |
| st-search/src/test/.../FileIndexMessageConsumerTest.java | 新增 | 5 用例：INDEX/DELETE/UPDATE_META 分发/异常捕获/空消息 |

## 与验收标准对照
- [x] 两模块测试可独立运行（mvn test -pl st-sync,st-search）— BUILD SUCCESS
- [x] 覆盖消费端关键路径与幂等/异常语义 — 10 用例覆盖 5+5 条路径

## 测试结果
- st-sync SyncChangeMessageConsumerTest：5 用例全通过
- st-search FileIndexMessageConsumerTest：5 用例全通过
- 全量回归 124 用例 0 失败

## 风险
- 测试用 Mockito mock 依赖，不依赖真实 ES/RocketMQ 基础设施
