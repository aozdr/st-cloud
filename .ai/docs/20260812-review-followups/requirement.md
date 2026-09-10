# 需求：审查遗留建议跟进（20260812）

> 产出者：product-manager。依据《code-and-security-review.md》遗留建议 ①-④。关联 State：`.ai/state/20260812-review-followups.yaml`。

## 背景
TASK-001..006 已收敛，审查记录 4 条不阻断交付的遗留建议，涉及事件可靠性、Outbox 可维护性、权限缓存多实例一致性、消费端测试覆盖。本批在不动前端契约、不引入外部运行时依赖（Redis 可配置）前提下按序解决。

## 需求项
1. **事件消费者异常重抛（P2）**：`SyncChangeMessageConsumer` 非幂等冲突异常重抛触发 MQ 重投，避免消息被 ACK 丢失。
2. **event_log 清理/归档（P2）**：可配置保留期，定时清理已投递/重试耗尽的历史行，在途保留。
3. **权限缓存 Redis 化（P3）**：缓存实现抽象可切换，`stcloud.cache.redis.enabled` 开启走 Redis，默认内存行为不变。
4. **st-search/st-sync 模块级测试（P3）**：补测试依赖与消费端单测。

## 范围
- 改动模块：st-sync / st-search / st-core / st-common / st-team（仅缓存注入类型）
- 禁止：改前端接口契约、改事件/权限/配额业务语义、默认引入 Redis 运行时依赖

## 验收标准
- ① 唯一键冲突跳过、其它异常重抛；② 超期清理、在途/重试中保留；③ 默认内存全绿、启用 Redis 语义等价；④ 两模块测试独立可跑
- 全量回归（TASK-001..006 的 56 用例）+ verify-loop PASS
