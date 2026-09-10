# Change Report — TASK-P1-TEST-AUTH-ADMIN（st-auth + st-admin 测试补齐）

- Task ID: `TASK-P1-TEST-AUTH-ADMIN`
- Agent: executor（taskType=implement）
- dispatchId: p1-testauthadmin-001
- 日期: 2026-08-14

## 背景

全量 Code Review H7 发现 st-auth / st-admin 两个模块零测试。本任务为两模块补齐测试基础设施与主路径集成测试，模板对齐 st-core 的 CoreTestApplication / AbstractIntegrationTest 与 st-share 新增范式。

## 输入

- Dispatch Envelope：`.ai/dispatch/archived/inbox-p1-testauthadmin-001.md`
- TASK 文件：`.ai/tasks/TASK-P1-TEST-AUTH-ADMIN.md`
- 测试范式：`st-core/src/test/`（CoreTestApplication / AbstractIntegrationTest / application-test.yml / schema.sql）、`st-share/src/test/`（ShareTestApplication 范式）
- 建表脚本：`docker/mysql/init/02_create_tables.sql`（sys_tenant / sys_user / audit_log）、`04_rbac_tables.sql`（sys_role / sys_permission / sys_user_role / sys_role_permission）、`05_rate_limit_tables.sql`（sys_rate_limit）、`09_jwt_secret.sql`

## 分析

两模块均无测试目录、无测试依赖（pom 缺 spring-boot-starter-test / h2）。对照 st-core / st-share 范式：

- 测试启动类禁用 @ComponentScan，排除 Redis/Security/Web 自动配置，@MapperScan 覆盖本模块 + st-common mapper，@Import MyBatis-Plus 配置（租户拦截器 + 自动填充）；
- H2 `MODE=MySQL` 内存库 + 模块相关表 schema.sql，列定义对照 docker/mysql/init 脚本，去除 ENGINE/CHARSET/COMMENT；
- 关键外部依赖隔离：st-auth 的 Redis（refresh token 存储）以 Mock StringRedisTemplate 替代；st-admin 依赖的 st-core / RocketMQ / S3 不参与自动装配。

特殊发现（不改业务代码，仅在测试侧处理）：

- `JwtUtils` 需要 `stcloud.jwt.master-key`（≥32 字节）与 `sys_jwt_secret` 表，测试 yml 配置测试主密钥、schema 建表，JwtUtils 首启随机生成签名密钥入库（真实加解密链路）；
- JWT 的 iat 为秒级精度：同一秒内相同 claims 签发的 access/refresh token 字节相同，刷新 Token 轮换断言前等待 1.1s，保证新 iat 使"轮换签发"真实可验证。

## 决策

按两模块各自执行，只加测试依赖与测试目录，不改业务主代码：

1. `st-auth/pom.xml` / `st-admin/pom.xml`：加 `spring-boot-starter-test` + `com.h2database:h2`（test scope，写法对齐 st-core）；
2. `src/test/resources/application-test.yml`：H2 MODE=MySQL 内存库 + schema.sql + MyBatis-Plus 配置（st-auth 另配 JWT 测试主密钥）；
3. `src/test/resources/schema.sql`：st-auth 建 sys_tenant/sys_user/sys_role/sys_permission/sys_user_role/sys_role_permission/sys_jwt_secret + 种子数据（默认租户、admin 用户、admin/user 角色、权限、角色-权限/用户-角色关联）；st-admin 建 sys_tenant/sys_rate_limit/audit_log；
4. `AuthTestApplication`：禁用 Redis/Security/Web，@MapperScan auth+common mapper，@Import MyBatis 配置 + AuthService + JwtUtils，Mock StringRedisTemplate（ValueOperations 暴露为 Bean 供测试桩定）；
5. `AdminTestApplication`：禁用 Redis/Security/Web，@MapperScan admin+common mapper，@Import MyBatis 配置 + SpeedLimitCache + SpeedLimitManageServiceImpl；
6. `AbstractAuthIntegrationTest` / `AbstractAdminIntegrationTest`：@SpringBootTest + @ActiveProfiles("test") + @Transactional 回滚，统一设置租户上下文，提供测试数据构造辅助；
7. 集成测试用例：
   - st-auth（8 条，登录/认证主路径）：注册创建用户+默认角色+签发可校验 Token、登录成功（BCrypt 校验+lastLogin 更新）、密码错误、用户不存在、账号禁用、刷新 Token 轮换、刷新 Token 未缓存拒绝、当前用户角色权限加载；
   - st-admin（9 条）：审计日志写入/按主键查询/按 action 查询/组合条件查询/失败状态与 detail 落库（4 条）；限速创建+列表+详情、更新持久化、启停切换、删除、非法 scope/双零限速参数校验（5 条）。

## State Delta

- 新增测试基础设施与用例（文件清单见下），业务主代码零改动；
- `mvn -q -pl st-auth -am test` EXIT=0：st-auth 集成测试 8 条全部通过（Failures=0, Errors=0）；
- `mvn -q -pl st-admin -am test` EXIT=0：st-admin 集成测试 9 条（审计 4 + 限速 5）全部通过（Failures=0, Errors=0），依赖模块 st-common/st-auth/st-core 测试同步全绿；
- 各模块集成测试均 ≥5 条且覆盖主路径，满足 exitCriterion 与 acceptance。

### 修改文件清单

- `st-auth/pom.xml`（加测试依赖）
- `st-admin/pom.xml`（加测试依赖）
- `st-auth/src/test/resources/application-test.yml`（新增）
- `st-auth/src/test/resources/schema.sql`（新增）
- `st-auth/src/test/java/com/stcloud/auth/AuthTestApplication.java`（新增）
- `st-auth/src/test/java/com/stcloud/auth/AbstractAuthIntegrationTest.java`（新增）
- `st-auth/src/test/java/com/stcloud/auth/AuthServiceIntegrationTest.java`（新增，8 用例）
- `st-admin/src/test/resources/application-test.yml`（新增）
- `st-admin/src/test/resources/schema.sql`（新增）
- `st-admin/src/test/java/com/stcloud/admin/AdminTestApplication.java`（新增）
- `st-admin/src/test/java/com/stcloud/admin/AbstractAdminIntegrationTest.java`（新增）
- `st-admin/src/test/java/com/stcloud/admin/AuditLogIntegrationTest.java`（新增，4 用例）
- `st-admin/src/test/java/com/stcloud/admin/SpeedLimitManageIntegrationTest.java`（新增，5 用例）

## 风险

- 测试中的 Redis（refresh token）与 JWT 主密钥为 Mock/测试配置，未覆盖真实 Redis 集群行为；refresh token 吊销链路依赖的 Redis 持久化语义需在集成环境验证；
- `sys_permission` 种子数据为生产权限集的子集（9 条），注册/登录加载权限的断言基于该子集；若生产新增权限，不影响本测试；
- 刷新 Token 用例含 1.1s sleep（保证新 iat），单用例耗时约 1.3s，属可接受范围；
- 测试 H2 表结构对照 docker/mysql/init 手工维护，后续表结构变更需同步更新 schema.sql（与 st-core 测试同规则）。

## 下一步

主线程复跑两模块测试确认 EXIT=0 后，标记本环节 exitCriteria done；如需可补充真实 Redis 的刷新 Token 联调用例。

## 变更影响

- 对业务主代码：零影响（仅 pom 加 test scope 依赖）；
- 对其它模块：无（未触碰其它 st-* 模块）；
- 对后续迭代：st-auth/st-admin 获得与 st-core/st-share 一致的集成测试范式，Code Review 可复用；
- exitCriteria：两模块"主路径集成测试 ≥5 条且 mvn test 通过"已满足。
