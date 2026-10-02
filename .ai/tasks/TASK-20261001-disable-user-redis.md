# 停用账号删除用户独占 Redis key

Task/State：20261001-disable-user-redis；规模 medium，主线程单人执行。用户明确限定“删除独占key就行”“不能直接从key拿到的内容都不用处理”，并持续要求“你不用开子线程，你自己完成”。评审与验收标为自检，不伪称独立审查。

include：st-admin 的 UserManageServiceImpl、新增 UserRedisKeyCleanupService、对应单元与事务/隔离 Redis 测试；.ai/docs/20261001-disable-user-redis/**、本 TASK、本任务 State、.ai/knowledge/user-disable-redis.md。

exclude：Redis value/集合成员读取与处理、共享 key、其他用户数据、实际开发账号停用、数据库模型/迁移、HTTP 契约、用户删除/改密行为扩大、前端/OnlyOffice 改造、历史 State 改写、用户既有改动和生产部署。

目标及完成标准见 design.md；编码前按当前 exit-criteria.yaml 完成 DESIGN、TESTCASES。串行验证，禁止争用构建缓存。

收尾 include：只替换本轮由主线程启动且精确命令/8080监听校验匹配的开发后端，保存新JAR/源码清单/启动探针。本轮安全回归旧 TC0122 断言与已有注册独立提交契约不符，允许修正同一测试类断言；不修改注册实现。
