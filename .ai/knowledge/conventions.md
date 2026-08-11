# 开发约定

> 本文档描述 st-cloud 项目的编码规范、命名约定、配置管理与 AI 协作流程。

## 编码规范

### 后端

#### 实体层

- 所有实体继承 `BaseEntity`（`com.stcloud.common.entity`），自动获得 `id`、`tenantId`、`createdAt`、`updatedAt`、`deleted`
- 使用 Lombok `@Data` + `@EqualsAndHashCode(callSuper = true)` 简化代码
- MyBatis-Plus 注解：
  - `@TableName("表名")`：指定表名
  - `@TableId(type = IdType.ASSIGN_ID)`：雪花算法主键（BaseEntity 已定义）
  - `@TableLogic`：逻辑删除（BaseEntity 已定义 `deleted` 字段）
  - `@Version`：乐观锁版本号
  - `@TableField(fill = FieldFill.INSERT/INSERT_UPDATE)`：自动填充

```java
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("file_node")
public class FileNode extends BaseEntity {
    private Long parentId;
    private Integer nodeType;
    // ...
}
```

#### 分层结构

每个业务模块遵循固定包结构：

```
com.stcloud.{module}/
├── controller/    @RestController，REST API
├── service/       业务接口
│   └── impl/      业务实现
├── mapper/        MyBatis-Plus BaseMapper
├── entity/        数据库实体
├── dto/           数据传输对象（Request/VO 后缀）
├── enums/         模块枚举（如有）
├── config/        模块配置（如有）
├── event/         跨模块事件（如有）
├── listener/      事件监听器（如有）
├── task/          定时任务（如有）
└── aspect/        AOP 切面（如有）
```

#### 响应封装

所有 Controller 方法返回 `Result<T>`：

```java
@GetMapping("/{nodeId}")
public Result<FileNodeVO> getNode(@PathVariable Long nodeId) {
    return Result.success(fileService.getNode(nodeId));
}
```

- 成功：`Result.success(data)` -> code=200
- 业务异常：`throw new BusinessException(ResultCode.FILE_NOT_FOUND)`
- 异常由 `GlobalExceptionHandler` 统一捕获并转为 `Result`

#### Mapper

- 继承 `BaseMapper<T>`，基本 CRUD 无需写 SQL
- 复杂查询使用 XML Mapper（`classpath*:mapper/**/*.xml`）
- 驼峰映射：`map-underscore-to-camel-case: true`（数据库下划线 <-> Java 驼峰）

#### 依赖注入

- 使用 `@RequiredArgsConstructor` + `final` 字段（构造器注入）
- 不使用 `@Autowired` 字段注入

### 前端

- 组件使用函数组件 + Hooks
- 状态管理统一使用 Zustand（`create((set, get) => ({ ... }))`）
- API 调用通过 `src/lib/api.ts` 的 Axios 实例
- 样式使用 Tailwind CSS 类名
- UI 基础组件使用 Radix UI + 自定义封装（`src/components/ui/`）

## 命名约定

### 后端

| 对象 | 约定 | 示例 |
|------|------|------|
| 包名 | `com.stcloud.{module}.{layer}` | `com.stcloud.core.service.impl` |
| 类名 | 大驼峰 | `FileServiceImpl` |
| 方法名 | 小驼峰 | `uploadCheck` |
| 变量名 | 小驼峰 | `fileNode` |
| 常量 | 全大写下划线 | `MAX_FILE_SIZE` |
| 数据库表 | 全小写下划线 | `file_node`、`sys_user` |
| 数据库字段 | 全小写下划线 | `parent_id`、`created_at` |
| 枚举 | 大驼峰，含 code + desc | `NodeType.FILE(1, "文件")` |
| API 路径 | 小写连字符，`/api` 前缀 | `/api/file/upload/check` |

### 前端

| 对象 | 约定 | 示例 |
|------|------|------|
| 组件文件 | 大驼峰 .tsx | `FileManager.tsx` |
| 工具文件 | 小驼峰 .ts | `server-config.ts` |
| Store 文件 | 小驼峰 .ts | `auth.ts` |
| Hook | use 前缀 | `useUpload.tsx` |
| CSS 类 | Tailwind 实用类 | `flex items-center` |

## 配置管理

### 配置文件

| 文件 | 说明 |
|------|------|
| `st-api/src/main/resources/application.yml` | 主配置（所有环境共用） |
| `st-api/src/main/resources/application-dev.yml` | 开发环境覆盖 |

### 关键环境变量

| 环境变量 | 配置项 | 说明 |
|----------|--------|------|
| `STCLOUD_MASTER_KEY` | `stcloud.jwt.master-key` | JWT 主密钥（加解密 DB 中的签名密钥） |
| `STCLOUD_CORS_ORIGINS` | `stcloud.cors.allowed-origins` | CORS 允许来源（逗号分隔） |

### 配置规范

- 敏感信息（密钥、密码）使用环境变量覆盖，不硬编码
- 开发环境使用 `application-dev.yml` 的默认值
- 生产环境必须通过环境变量覆盖：JWT 密钥、数据库密码、对象存储凭证、CORS 来源
- `spring.profiles.active: dev` 默认开发环境

## 数据库迁移

- 脚本目录：`docker/mysql/init/`
- 命名规则：两位数字前缀 + 下划线描述，如 `15_add_new_feature.sql`
- 编号严格递增，不可复用已存在的编号
- 所有 DDL 使用 `IF NOT EXISTS` / `IF EXISTS` 保证幂等
- 新增字段使用 `ALTER TABLE ... ADD COLUMN ...`
- 逻辑删除统一使用 `deleted` 字段（TINYINT，0/1），由 MyBatis-Plus `@TableLogic` 管理

## 安全约定

- 密码使用 `BCryptPasswordEncoder` 加密存储
- JWT 签名密钥通过 `STCLOUD_MASTER_KEY` 加密后存入 `sys_jwt_secret` 表，不入源码
- 分享提取码使用 BCrypt 加密
- CORS 生产环境必须配置 `stcloud.cors.allowed-origins`，留空则拒绝所有跨域
- 无状态认证：`SessionCreationPolicy.STATELESS`
- 敏感操作使用 `@Auditable` 注解记录审计日志

## 测试规范

项目采用两层测试分层，详见 .ai/knowledge/testing.md：

- **单元测试**（*Test）：JUnit 5 + Mockito，Mock Mapper 测试纯业务逻辑分支
- **集成测试**（*IntegrationTest）：Spring Boot Test + H2 内存库，验证真实 SQL/表结构/Mapper 映射/租户隔离
- Service 方法涉及 Mapper 调用的，必须有集成测试覆盖主路径
- 新增数据库表/字段的迭代，集成测试启动即验证 schema 完整性（表缺失则启动失败）

## AI 协作约定

### 任务入口（Workflow Manager）

所有用户请求首先经 Workflow Manager 分类，再决定路径：

- 小型任务（Bug 修复、配置调整、样式微调）直接执行，不走开发流程
- 中型任务（单模块增强、新增 API）走精简流程（设计->编码->Review->测试）
- 大型任务（跨模块、新业务模块、数据模型变更）走完整开发流程（一至九阶段）
- 用户显式声明不走开发流程时，直接执行

详见 .ai/agents/workflow-manager.md 和 .ai/workflows/feature-development.md。

### 开发流程（AGENTS.md）

遵循星云盘 AI 研发总规则，完整流程 9 个阶段：

1. 需求分析 - 产品经理使用 Grill Me 拷打，输出需求初稿
2. 需求评审 - 多方评审（PM+UI+前端+后端+测试），定版 PRD + UI/UX 设计文档
3. 程序设计 - 前后端工程师各自技术设计
4. 程序设计评审 - 多方评审（PM+UI+前端+后端+测试），输出最终设计文档
5. 测试用例编写 - 测试编写用例（开发前完成）
6. 编码实现 - 前后端并行编码，核心逻辑加中文注释
7. Code Review - Reviewer 审查（前端/后端子任务并行）
8. 测试执行 - 测试逐项验证，全部通过则迭代完成
9. 知识库回顾 - 回顾是否需更新知识库

所有中大型功能必须先完成需求和设计文档。小型任务可直接执行。

### AI Agent 角色（.ai/agents/）

| Agent | 职责 | 是否必须 |
|-------|------|---------|
| Workflow Manager | 统一入口，任务分类与调度 | 必须（入口） |
| Product Manager | 需求分析（Grill Me 拷打），输出需求文档 | 完整流程必须 |
| Frontend Engineer | 前端技术设计与编码，核心逻辑加中文注释 | 涉及前端时必须 |
| Backend Engineer | 后端技术设计与编码，核心逻辑加中文注释 | 涉及后端时必须 |
| Tester | 测试用例编写与测试执行，全部通过才算迭代完成 | 完整/精简流程必须 |
| Reviewer | Code Review（子任务并行），检查质量与规范，通过后才测试 | 完整/精简流程必须 |
| Architect | 可选架构顾问，协助复杂架构设计 | 可选 |
| Requirement Discovery | 可选上游，竞品分析与功能借鉴 | 可选 |

### 文档维护

- 知识库（.ai/knowledge/）基于代码扫描生成，代码变更后需同步更新
- 需求文档使用 .ai/templates/requirement-template.md，**产出后落盘到 `.ai/docs/<task-id>/requirement.md`**
- 设计文档使用 .ai/templates/design-template.md，**产出后落盘到 `.ai/docs/<task-id>/design.md`**
- 测试用例使用 .ai/templates/test-case-template.md，产出后落盘到 `.ai/docs/<task-id>/testcases.md`
- 文档落盘后必须在对话中告知用户路径，确保可审阅；文档长期留存供回顾，不得删除
- 文档命名、存放、可见性、留存细则见 `.ai/knowledge/document-management.md`
- 开发流程参考 .ai/workflows/feature-development.md
- 测试分层规范参考 .ai/knowledge/testing.md
