# TASK：TASK-004 st-search/st-sync 模块级测试

> 依据《code-and-security-review.md》遗留建议 ④。优先级 P3。

## 元信息
- Task ID: `TASK-004`
- 关联 State: `.ai/state/20260812-review-followups.yaml`
- 归属 Agent: tester

## 目标
st-search / st-sync 无独立测试基建，消费链路由 st-core 集成测试间接覆盖。为两模块补齐测试依赖与模块级单元测试。

## 修改范围
- `st-sync/pom.xml`、`st-search/pom.xml`：补 `spring-boot-starter-test`（test scope）
- `st-sync/src/test/.../SyncChangeMessageConsumerTest.java`：新写/幂等跳过/异常重抛（含 TASK-001 行为）
- `st-search/src/test/.../FileIndexMessageConsumerTest.java`：mock ElasticsearchClient/SearchService，验证 INDEX/DELETE/UPDATE_META 动作与幂等

## 禁止修改范围
- 不改消费端业务逻辑（除非 TASK-001 已处理）
- 不引入真实外部基础设施（ES/RocketMQ 用 mock）

## 验收标准
- 两模块测试可独立运行（`mvn test -pl st-sync,st-search`）
- 覆盖消费端关键路径与幂等/异常语义

## 测试要求
- 复用现有 `EventMessage`/`FileNodeSnapshot` 构造消息；mock 依赖

## 输出要求
- 完成后产出 `.ai/docs/20260812-review-followups/changereport-t004.md` 与测试报告
