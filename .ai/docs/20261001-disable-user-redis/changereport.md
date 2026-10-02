# 变更 disable-redis-code-r1

停用原先只撤销 refresh，现在 updateUser 提交后追加删除从 key 固定段能识别用户归属的团队活跃/权限缓存 key。UserRedisKeyCleanupService 使用两个限定 SCAN 模式，候选完整格式复核、128批量DELETE、Cursor关闭；不读 value/集合，不碰共享 key。数据库 status/security_version 和既有 refresh 撤销继续生效，回滚不清理，再次停用可幂等重试。

新增4条key-only单测与7条隔离Redis事务/兼容场景执行；现有管理安全测试配置导入清理组件并提供空SCAN替身。旧TC0122 register断言对齐已有独立注册事务契约；AuthService与原已验收manifest哈希一致，没有改注册实现。

变更精确限 UserRedisKeyCleanupService、UserManageServiceImpl、UserRedisKeyCleanupServiceTest、UserManageSecurityIntegrationTest、UserSecurityRedisIntegrationTest 及本任务文档/知识。无数据库/HTTP契约变更，不停用实际账号。

78项执行通过、API全依赖打包通过。开发后端从本轮自建PID27708精确替换为PID24264，新JAR SHA256 B6082CC9A65FA68BF11264CA9F1C9F4068FB87CE3A02DFA013478BBF601D4734；新组件成功装配、8080启动，证据backend-running.json、runtime-check.json。原JAR保留可回退。
