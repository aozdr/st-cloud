# 测试用例：审查遗留建议跟进（20260812）

> 产出者：tester。关联 State：`.ai/state/20260812-review-followups.yaml`。

## TASK-001 消费者异常重抛
- 新写消息：写入 sync_change_log + push（成功）
- 重复消息（eventLogId 已处理）：幂等跳过，不写入
- 唯一键冲突：静默跳过（DuplicateKeyException 兜底）
- 非唯一键异常：重抛异常（触发 MQ 重投）

## TASK-002 event_log 清理
- 已投递 status=1 且超保留期 → 删除
- status=1 未超保留期 → 保留
- 在途 status=0（任意时间）→ 保留
- 重试中 status=2 retry<5 → 保留
- 重试耗尽 status=2 retry>=5 且超期 → 删除

## TASK-003 权限缓存 Redis 化
- 默认（未启用 Redis）：内存 TtlCache，现有缓存测试全绿
- 启用 Redis：RedisTtlCache get/put/removeByPrefix/expire 语义与内存一致（mock StringRedisTemplate）
- key 前缀/序列化往返正确

## TASK-004 模块级测试
- st-sync 消费者：TASK-001 三类路径
- st-search 消费者：INDEX/DELETE/UPDATE_META 分发 + 空消息防御

## 回归
- TASK-001..006 既有 56 用例全绿；全模块 compile；verify-loop PASS
