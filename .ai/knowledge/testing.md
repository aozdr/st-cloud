# 测试分层规范

> 本文档定义 st-cloud 项目的测试分层策略，明确单元测试与集成测试的职责边界，防止"纯 Mock 假通过"问题。

## 测试分层概览

| 层级 | 命名约定 | 框架 | 验证范围 | 依赖 |
|------|---------|------|---------|------|
| 单元测试 | `*Test` | JUnit 5 + Mockito | 纯业务逻辑分支 | Mapper 全 Mock，无数据库 |
| 集成测试 | `*IntegrationTest` | JUnit 5 + Spring Boot Test + H2 | 真实 SQL 执行、表结构、Mapper 映射、租户隔离 | H2 内存库（MySQL 兼容模式） |

## 单元测试（`*Test`）

- **目标**：测试 Service 的业务逻辑分支（if/else、异常抛出、参数校验），不验证 SQL 和表结构
- **做法**：Mockito mock 所有 Mapper 依赖，对 Service 方法输入/输出做断言
- **不能替代集成测试**：Mapper 被 mock 后，SQL 语法错误、表字段缺失、JOIN 条件错误都无法发现
- **示例**：`SearchServiceImplTest`、`FileIndexEventListenerTest`、`JwtUtilsTest`

## 集成测试（`*IntegrationTest`）

- **目标**：验证真实 SQL 执行、表结构完整性、Mapper 映射正确性、租户隔离生效
- **做法**：基于 H2 内存库（`MODE=MySQL`），启动 Spring 上下文，注入真实 Mapper 和 Service，对数据库实际读写做断言
- **核心价值**：
  - 表不存在时测试启动即失败（schema.sql 建表缺失 → H2 报 `Table not found`）
  - SQL 语法错误、JOIN 条件错误会被真实执行暴露
  - MyBatis-Plus 租户拦截器、自动填充、逻辑删除在真实环境下验证

### 何时必须编写集成测试

- Service 方法涉及 Mapper 调用（INSERT/UPDATE/DELETE/SELECT）的，至少有一个集成测试覆盖主路径
- 新增数据库表/字段的迭代，集成测试启动即验证 schema 完整性
- 使用自定义 `@Select` SQL（含 JOIN）的 Mapper 方法，必须通过集成测试验证 SQL 正确性
- 涉及租户隔离的查询，必须通过集成测试验证 `TenantLineInnerInterceptor` 生效

### 集成测试框架使用指南

以 st-core 模块为示范，其他模块（st-search、st-share 等）按相同模式扩展。

**1. 测试依赖（pom.xml）**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

**2. 测试配置（`src/test/resources/application-test.yml`）**

- H2 数据源：`jdbc:h2:mem:{模块名}-test;MODE=MySQL;DB_CLOSE_DELAY=-1`
- `spring.sql.init.mode: always` + `schema-locations: classpath:schema.sql`（启动时建表，表缺失即失败）
- 复用主配置的 `mybatis-plus` 设置（mapper-locations、type-aliases、逻辑删除）
- 关闭 Redis/ES/RocketMQ/S3 等无关依赖

**3. 建表脚本（`src/test/resources/schema.sql`）**

- 仅包含被测功能涉及的表（从 `docker/mysql/init/` 转换）
- 去除 `ENGINE`/`CHARSET`/`COLLATE` 子句（H2 忽略但保持干净）
- 若实体有 schema 未覆盖的字段，补齐对应列

**4. 测试启动类（`src/test/java/.../support/CoreTestApplication.java`）**

- 使用 `@Configuration` + `@EnableAutoConfiguration`（不含 `@ComponentScan`），避免扫描到依赖 S3/Redis 的 Service
- `@MapperScan` 扫描被测模块的 Mapper 接口
- `@Import` 导入 `MyBatisPlusConfig`（租户拦截器）+ `MyMetaObjectHandler`（自动填充）
- `@EnableAutoConfiguration(exclude = {...})` 排除 Redis/Security/WebMvc 等无关自动配置
- 手动 `@Bean` 注册被测 Service

**5. 集成测试基类（`AbstractIntegrationTest`）**

- `@SpringBootTest(classes = XxxTestApplication.class)` + `@ActiveProfiles("test")`
- `@Transactional` 保证每个测试方法执行后自动回滚，无需手动清理数据
- 提供 `setUpUser(userId, tenantId)` 工具方法设置 `UserContext`/`TenantContext`（Service 依赖 ThreadLocal）
- `@AfterEach` 清理 ThreadLocal
- 提供数据构造辅助方法（如 `insertFileNode`），走真实 Mapper 验证 INSERT SQL + 自动填充

### 收藏功能集成测试（范式）

文件：`st-core/src/test/java/.../service/impl/FavoriteServiceIntegrationTest.java`

覆盖场景：
- `toggleFavorite` 新增收藏：插入 file_node → 调用 toggle → 断言返回 true → 查 file_favorite 表确认记录存在
- `toggleFavorite` 取消收藏：已有收藏 → toggle → 断言返回 false → 查表确认记录已逻辑删除
- `toggleFavorite` 文件不存在：断言抛 `BusinessException(FILE_NOT_FOUND)`
- `toggleFavorite` 文件在回收站：插入 status=1 的 file_node → 断言抛 `FILE_NOT_FOUND`
- `listFavorites`：验证 `@Select` JOIN SQL 正确（file_favorite JOIN file_node）
- `listFavorites` 过滤回收站：验证 JOIN 条件 `fn.status = 0` 生效
- `listFavoriteIds`：验证轻量查询 SQL
- 租户隔离：租户 A 的收藏，租户 B 查不到（验证 `TenantLineInnerInterceptor` 生效）

### 其他模块集成测试（同范式扩展）

| 模块 | 代表性测试 | 覆盖重点 |
|------|-----------|---------|
| st-core | `UploadStateMachineIntegrationTest` / `RelayUploadIntegrationTest` | 上传状态机、中转上传、断点续传 |
| st-core | `ConcurrentUploadIntegrationTest` / `QuotaConcurrencyIntegrationTest` | 并发上传、配额并发一致性 |
| st-core | `FileObjectIntegrationTest` / `EventOutboxIntegrationTest` | 去重引用计数、事务 Outbox（回滚即无事件） |
| st-core | `FileServicePermissionIntegrationTest` / `AccessibleCacheTest` | 权限过滤与权限缓存 |
| st-auth | `AuthServiceIntegrationTest` | 注册/登录/刷新、Token 生命周期 |
| st-share | `ShareServiceImplSecurityIntegrationTest` / `ShareServiceImplExpiryIntegrationTest` / `ShareServiceImplPermissionLimitIntegrationTest` | 分享越权、过期、下载限制 |
| st-team | `TeamServiceIntegrationTest` / `TeamServicePermissionIntegrationTest` / `FolderPermissionServiceRuleTest` | 团队成员/邀请/移交、文件夹权限规则 |
| st-admin | `AuditLogIntegrationTest` / `SpeedLimitManageIntegrationTest` | 审计查询、限速规则管理 |
| st-search | `NgramSearchIntegrationTest` | ES 检索与过滤 |
| st-preview | `PreviewServiceIntegrationTest` | 预览缓存与格式分支 |
| st-sync | `SyncChangeMessageConsumerTest` | MQ 幂等消费 |

## 运行集成测试

```bash
# 运行单个集成测试类
mvn test -pl st-core -Dtest=FavoriteServiceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false

# 运行模块全部测试
mvn test -pl st-core -Dsurefire.failIfNoSpecifiedTests=false
```

> `-Dsurefire.failIfNoSpecifiedTests=false`：当使用 `-am`（also make）构建依赖模块时，避免因依赖模块无匹配测试而报错。

## 数据库迁移验证（自动化门禁）

> H2 集成测试通过 ≠ MySQL 运行环境正常。H2 的 `schema.sql` 是手动维护的，可能与生产 MySQL schema 不一致。

### 自动化校验工具

项目提供两个自动化工具防止 schema 漂移：

1. **`SchemaConsistencyTest`**（`mvn test` 自动运行）：三层校验实体字段 ↔ schema.sql ↔ MySQL init SQL 的列覆盖
2. **`compare-schema.ps1`**（`.ai/scripts/compare-schema.ps1`）：对比 H2 schema.sql 与运行中 MySQL 的实际列集差异

### 每次迭代强制流程

按 AGENTS.md「数据库版本管理」章节执行（H2 测试通过后必须运行 `compare-schema.ps1`）：

```
1. 新建迁移脚本 (docker/mysql/init/NN_xxx.sql)
2. 同步 H2 schema (st-core/src/test/resources/schema.sql)
3. mvn test 全绿（含 SchemaConsistencyTest）
4. .ai/scripts/compare-schema.ps1  ← 对比 MySQL，确认无差异
5. 执行迁移到 MySQL
6. INSERT schema_version 记录（版本号 + SQL 文件清单）
7. 再次 compare-schema.ps1 确认 PASS
```

### compare-schema.ps1 输出说明

- `[PASS] [table] aligned`：共有表列集一致
- `[DIFF] [table] H2 only: xxx`：H2 有 MySQL 缺的列（需补迁移到 MySQL）
- `[DIFF] [table] MySQL only: xxx`：MySQL 有 H2 缺的列（需补 schema.sql 或清理 MySQL 残留列）
- `Pending SQL files`：`schema_version` 表中未记录的新 SQL 文件
- 退出码 0 = PASS，1 = 有差异待处理

### 版本表 schema_version

每次迭代执行迁移后，向 `schema_version` 表 INSERT 记录：
```sql
INSERT INTO schema_version (version_tag, iteration_name, applied_sql_files, applied_by, notes)
VALUES ('YYYYMMDD.N', '迭代名称', 'NN_xxx.sql,NN_yyy.sql', 'agent/user', '变更摘要');
```
