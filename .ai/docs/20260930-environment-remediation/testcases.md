# 验证用例

| ID | 场景与预期 |
| --- | --- |
| ENV01 | 实际 MySQL 版本/库/列/索引/schema_version 可审计且不含凭据。 |
| ENV02 | H2/SchemaConsistency 通过，迁移前发现差异，迁移后 compare-schema=0；幂等且原值保留。 |
| MERGE01 | 无冲突标记；上传/文本/编辑器/解压网络在事务外，失败/去重败者/GC/晚到 PUT 引用安全。 |
| AUTH01 | 连续两轮用最新 refresh，旧 token 拒绝；成对同步与持久恢复。 |
| AUTH02 | 并发 401 一次刷新，全部重放；失败无循环；换服/退出/新登录不被旧结果覆盖。 |
| TEAM01 | V02 实际服务事务 READ_COMMITTED；等待锁时邀请撤销/过期不可接受，角色/成员/邀请无悬空引用。 |
| SYNC01 | V03 删除恢复旧 DELETE 保留字节/映射，MOVE/RENAME+UPDATE、重启与失败重试推进游标。 |
| WEB01 | V04 键盘、updatedAt、旧 error 与对比度；Sidebar 功能与 Web 构建。 |
| DB01 | V05 46 无列分支与重复运行，新值为 0，原值保留。 |
| RECYCLE01 | V06 撤权/恢复/删除竞争权限、配额、Outbox 与状态一致。 |
| OPS01 | O01–O04 运行版本、租户路径、依赖健康、schema；缺真实环境不得通过。 |
| BUILD01 | 串行后端测试/构建、桌面类型/回归、Web 构建，独立审查无高风险遗留。 |
