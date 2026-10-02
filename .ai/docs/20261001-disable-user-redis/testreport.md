# 测试 disable-redis-code-r1

主线程自验，非独立测试报告。串行Maven Java17，-pl st-admin -am，显式test.redis.port=16381。

| 类 | 执行 | 失败/错误/跳过 |
|---|---:|---|
| UserRedisKeyCleanupServiceTest | 4 | 0/0/0 |
| UserManageSecurityIntegrationTest | 17 | 0/0/0 |
| UserSecurityRedisIntegrationTest | 57 | 0/0/0 |

共78次执行通过，真实Redis类包含继承的管理安全用例，不与先前全量批次相加。test-run-r2.log和test-evidence/保存当前证据；test-result.json由JUnit XML计数。第一轮78次执行中的两条旧register用例失败，test-run-r1.log保留脱敏证据；修正测试与已有注册独立提交契约后完整复验78通过，未跳过失败用例。

新增4单测覆盖固定用户段/误匹配防护、不读内容、批量128/128/45与重复、非法ID、删除异常关闭Cursor。新增7次真实隔离Redis执行覆盖提交前不删/提交后独占key删除、共享和其他用户保留、重复停用、回滚会话不变、SCAN失败停用仍提交及重试、四种非停用操作不扫描。当前所有旧安全/并发/多进程用例也执行通过。

API全依赖package -DskipTests通过（package.log），没有把打包描述为额外全量测试。新开发8080进程装配成功、ping200、受保护接口无凭据401；运行详情runtime-check.json。本轮没有修改数据库结构，未作MySQL迁移，不重复无关schema验收。真实停用行为验证来自隔离Redis+H2管理事务，本机开发MySQL没有被停用实验修改。
