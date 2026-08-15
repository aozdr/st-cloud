# TASK-P1-TEST-AUTH-ADMIN（st-auth + st-admin 测试补齐 — executor/implement）

## 元信息

- Task ID: `TASK-P1-TEST-AUTH-ADMIN`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: 全量 Code Review H7（st-auth / st-admin 零测试）

## 目标

为 st-auth、st-admin 补测试基础设施 + 主路径集成测试（模板对齐 st-core 的 CoreTestApplication / AbstractIntegrationTest 与 st-share 新增范式）。**不改任何业务主代码**（除 pom.xml 加测试依赖）。

## 方法（两模块各自执行）

1. `pom.xml`：加 `spring-boot-starter-test` + `com.h2database:h2`（test scope，写法对齐 st-core）。
2. `src/test/resources/application-test.yml`：H2 `MODE=MySQL` 内存库 + schema.sql + MyBatis-Plus 配置。
3. `src/test/resources/schema.sql`：模块相关表（st-auth: sys_user/sys_role/sys_user_role/sys_role_permission/sys_permission 等；st-admin: audit_log/sys_rate_limit/sys_tenant 等），列定义对照 `docker/mysql/init/` 对应脚本。
4. `TestApplication`：禁用 Redis/Security/Web，`@MapperScan` 覆盖本模块 mapper（+ 依赖模块 core mapper），`@Import` MyBatis 配置。
5. `AbstractIntegrationTest` + 主路径集成测试：
   - st-auth：登录/认证主路径（密码校验、Token 生成/校验）≥5 条
   - st-admin：审计记录写入/查询、限速配置主路径 ≥5 条

## 范围

- include：`st-auth/**`、`st-admin/**`（pom + 新增测试目录）；只读 `st-core` 测试范式与 `docker/mysql/init/` 建表脚本
- exclude：修改 st-auth/st-admin 业务主代码；其它 `st-*` 模块；创建子 Agent

## 验收标准

- `mvn -q -pl st-auth -am test` EXIT=0
- `mvn -q -pl st-admin -am test` EXIT=0
- 各模块集成测试 ≥5 条且覆盖主路径

## 验证

- 主线程复跑两个模块测试
