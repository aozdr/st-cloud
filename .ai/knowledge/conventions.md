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

- 复杂查询以注解式 SQL 为主（含 `<script>` 动态 SQL）；XML Mapper 为可选（`mapper-locations: classpath*:mapper/**/*.xml` 配置保留兼容）
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
| 组件文件 | 大驼峰 .tsx；`components/ui/` 基础组件允许小驼峰/连字符（shadcn 风格，如 `button.tsx`、`time-wheel-picker.tsx`） | `FileManager.tsx` |
| 工具文件 | 小驼峰 .ts | `server-config.ts` |
| Store 文件 | 小驼峰 .ts | `auth.ts` |
| Hook | use 前缀；默认 .ts，含 JSX 的 Hook 允许 .tsx | `useUpload.tsx` |
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

### 配置规范

- 敏感信息（密钥、密码）使用环境变量覆盖，不硬编码
- 开发环境使用 `application-dev.yml` 的默认值
- 生产环境必须通过环境变量覆盖：JWT 密钥、数据库密码、对象存储凭证
- `spring.profiles.active: dev` 默认开发环境

## 数据库迁移

- 脚本目录：`docker/mysql/init/`
- 命名规则：两位数字前缀 + 下划线描述，如 `15_add_new_feature.sql`
- 编号严格递增，不可复用已存在的编号
- **所有脚本首行必须为 `SET NAMES utf8mb4;`**：容器内 mysql 客户端默认按 latin1 连接
  （服务器为 utf8mb4），缺失该行时脚本中的中文会被双重编码成乱码（2026-08-15 实测）
- 所有 DDL 使用 `IF NOT EXISTS` / `IF EXISTS` 保证幂等
- 新增字段使用 `ALTER TABLE ... ADD COLUMN ...`
- 逻辑删除统一使用 `deleted` 字段（TINYINT，0/1），由 MyBatis-Plus `@TableLogic` 管理

## 安全约定

- 密码使用 `BCryptPasswordEncoder` 加密存储
- JWT 签名密钥通过 `STCLOUD_MASTER_KEY` 加密后存入 `sys_jwt_secret` 表，不入源码
- 分享提取码使用 BCrypt 加密
- CORS 由 `SecurityConfig.corsConfigurationSource()` 统一允许所有来源（`setAllowedOriginPatterns("*")`），无运行时白名单配置
- 无状态认证：`SessionCreationPolicy.STATELESS`
- 敏感操作使用 `@Auditable` 注解记录审计日志

## 测试规范

项目采用两层测试分层，详见 .ai/knowledge/testing.md：

- **单元测试**（*Test）：JUnit 5 + Mockito，Mock Mapper 测试纯业务逻辑分支
- **集成测试**（*IntegrationTest）：Spring Boot Test + H2 内存库，验证真实 SQL/表结构/Mapper 映射/租户隔离
- 只有变更真实 SQL、表结构、租户隔离或关键数据写路径时，才要求集成测试覆盖主路径；纯业务分支可用单元测试验证
- 新增数据库表/字段的迭代，集成测试启动即验证 schema 完整性（表缺失则启动失败）

## 事务边界

核心写路径的事务边界原则（以下列为准；原任务文档未落盘，见 `.ai/state/20260817-transaction-boundary.yaml` 的回填记录）：

1. S3/外部网络调用一律在事务外执行；DB 写一律在事务内
2. 删除类：DB 事务内引用归零 + 记录待删状态（outbox 事件），提交后异步删 S3，失败进补偿队列重试
3. 只读查询方法禁止 `@Transactional`；确需一致性快照用 `readOnly=true`
4. 长事务显式 `timeout`，全局默认 `spring.transaction.default-timeout=30s`
5. 半成品对象统一 `tmp/` 前缀，失败尽力删除 + 定时清理兜底

已知反例（第二迭代逐项改造，见 design.md 3.3 节）：

- `ArchiveServiceImpl.extractArchive`
- `UploadServiceImpl.simpleUpload` / `mergeChunks`
- `EditorCallbackServiceImpl.handleCallback`
- `SyncBlockServiceImpl.blockCheck` / `blockUpload`
- `RecycleBinServiceImpl` 永久删除系列
- `TextFileServiceImpl.overwriteContent`

## AI 协作约定

### 任务入口（Workflow Manager）

需要落地的修改请求先经 Workflow Manager 分类，再决定路径；只读咨询、诊断和审查直接交付结论：

- 小型任务（Bug 修复、配置调整、样式微调）直接执行，不走开发流程
- 中型任务（边界明确的模块增强或兼容改动）默认主线程执行，按 `.ai/loop/exit-criteria.yaml` 走精简流程
- 大型或高风险任务（复杂跨模块、核心写路径、安全/权限、数据库、API 契约或不可逆变更）保留独立评审
- 用户指定交付方式时优先遵循；工程安全与真实验证仍须满足

详见 .ai/agents/workflow-manager.md 和 .ai/workflows/feature-development.md。

### 开发流程（AGENTS.md）

遵循星云盘 AI 研发总规则，采用 **Loop 编排 + 退出标准**，按任务规模选择标准集：

- **小型任务**：直接实现与相称验证，默认不创建 TASK/State/Dispatch
- **中型任务**：顺序和条件以 `.ai/loop/exit-criteria.yaml` 为准
- **大型任务**：顺序和条件以 `.ai/loop/exit-criteria.yaml` 为准

门禁依赖（不可降级）：

- 测试在实现后即可完成，不等待独立评审；最终验收仍等待 canonical `dependsOn`，证据绑定当前 revision
- 未通过验收（ACCEPT）不得标记 done；验收不通过打回 IMPLEMENTED 级联重跑

明确目标与风险、实现、验证并修复、对照目标交付；这些是内部决策，不要求逐轮输出固定模板，不按门禁数量派发 Agent。
详见 `.ai/knowledge/loop-state-model.md`。

### AI Agent 角色（.ai/agents/）

| Agent | 职责 | 是否必须 |
|-------|------|---------|
| Workflow Manager | 统一入口，任务分类与调度 | 必须（入口） |
| executor（执行者） | 有独立交付与隔离收益的实现/设计工作 | 按需委派，普通任务主线程完成 |
| reviewer（审查者） | 独立代码评审，合并安全与适用风险 | large 必需，与实现者分离 |
| tester（测试者） | 需要专业或环境隔离的验证 | 按需，普通测试与验收由主线程完成 |

> 职责要点见 `.ai/knowledge/role-context.md`。

### 文档维护

- 知识库（.ai/knowledge/）基于代码扫描生成，代码变更后需同步更新
- 需求文档使用 .ai/templates/requirement-template.md，**产出后落盘到 `.ai/docs/<task-id>/requirement.md`**
- 设计文档使用 .ai/templates/design-template.md，**产出后落盘到 `.ai/docs/<task-id>/design.md`**
- 测试计划默认合入 design.md，修改、自检、实际测试和知识同步统一记录 verification.md；模板可裁剪，额外文件按用户要求或独立决策需要产出
- 需用户裁决或用户要求查看时在对话中告知文档路径；其余产物在最终报告集中列出，文档长期留存供回顾
- 文档命名、存放、可见性、留存细则见 `.ai/knowledge/document-management.md`
- 开发流程参考 .ai/workflows/feature-development.md
- 测试分层规范参考 .ai/knowledge/testing.md

## 文件编码规范

- 所有文本文件统一使用 **UTF-8（无 BOM）** 编码；含非 ASCII 内容的 `.ps1` 脚本使用 **UTF-8 with BOM** 以兼容 Windows PowerShell 5.1
- 禁止 GBK/GB2312 编码的文档或代码文件入库；发现混用编码时需转码为 UTF-8 后再提交
- 文档/模板/State/任务文件的产出与修改统一遵循 `.ai/knowledge/document-management.md` 的编码规范
