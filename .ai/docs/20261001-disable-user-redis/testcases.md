# 用例 disable-redis-design-r1

1. A1/A2：key-only 单元测试提供跨空间/节点本用户 key、其他用户、1010/1101、用户 ID 在节点/空间段、共享/未知格式；只删允许名单，不调用 value/集合 API。
2. A1：超过两批并含重复 key 的扫描，核实分批删除、Cursor 关闭。
3. A2：null/0/负用户 ID 不扫描、不删除。
4. A1/A3：真实管理事务停用提交，隔离真实 Redis 三类 key 消失；共享/其他用户保留，旧 access/refresh 拒绝，其他用户仍可访问。
5. A3：真实外层事务回滚，三类 key 保留、status/version 不变、旧会话有效；提交前 key 仍存在。
6. A3：再次停用幂等；SCAN 注入失败后数据库停用已提交、旧会话无效；Redis 恢复后再次停用完成清理。
7. A2/A4：改昵称/配额/密码、启用等非停用不扫描额外 key；回归既有 UserManageSecurityIntegrationTest / UserSecurityRedisIntegrationTest，编译受影响模块。

用新的临时 Redis 容器/独立端口和独立 H2 库，不扫描开发 Redis，不输出令牌。

TC0122 四种模式按已有契约分别验证 register 独立提交与 admin 调用者事务提交/回滚；观察线程核实可见性。打包与本机开发启动探针只验证新组件成功装配，不用实际账号作停用实验。
