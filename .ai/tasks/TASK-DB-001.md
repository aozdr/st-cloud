# TASK：数据库设计专项审查（只读）

> 本文件是唯一编码输入。只读审查，禁止修改任何代码或文件。
> 关联 State：无独立 Loop State 文件（独立专项审查，以本文件与 Dispatch Envelope 为准）。

## 元信息

- Task ID: `TASK-DB-001`
- 归属 Agent: database-reviewer
- 任务类型: review（数据库设计审查，只读）
- 创建者: workflow-manager
- 日期: 2026-08-13
- 模式: verify（只读，禁止修改任何文件）

## 目标

只审查 st-cloud 项目的数据库设计，输出可追溯的审查结论，必须覆盖以下六项：

1. 表结构
2. 字段类型
3. 索引
4. 唯一约束
5. Mapper
6. SQL

## 审查范围（只读白名单）

- `docker/mysql/init/*.sql`：MySQL schema / 迁移脚本（表结构、字段类型、索引、唯一约束、外键、约束、版本记录表）
- `st-core/src/test/resources/schema.sql`：H2 schema（与 MySQL schema 的一致性，可参考 `.ai/scripts/compare-schema.ps1` 的校验逻辑）
- 各模块 Mapper 接口（`st-auth` / `st-core` / `st-common` / `st-admin` / `st-sync` / `st-team` / `st-share` 下的 `*Mapper.java`）：检查自定义 SQL（注解 / XML / 动态 SQL）与表映射、字段映射的一致性；若确为 MyBatis-Plus BaseMapper 无自定义 SQL，则仅核对其注解与泛型映射
- `.ai/scripts/compare-schema.ps1`（可选参考）

## 禁止范围（黑名单）

- 安全审查
- 前端审查（`st-web/**`、`st-desktop/**`、`st-preview/**`）
- 架构审查
- Java 业务代码审查（实体类、Service、Controller、配置类、工具类等；Mapper 接口仅限查看其中 SQL/映射相关部分）
- 修改任何文件（只读审查）
- 读取其他 TASK 文件（如 TASK-SEC-001、TASK-FE-001）与父线程上下文
- 创建/派发任何子 Agent

## 验收标准

- [ ] 首条回复声明：已读取 TASK + State + Scope；我是 database-reviewer（子 Agent），任务类型 review，开始执行 TASK-DB-001
- [ ] 输出数据库设计审查结论：按 表结构 / 字段类型 / 索引 / 唯一约束 / Mapper / SQL 六类分类，列出发现的问题（含严重级别 P0/P1/P2 与具体文件位置）
- [ ] 明确声明本次审查覆盖范围与未覆盖项
- [ ] 未修改任何文件
- [ ] 未读取禁止范围（黑名单目录）内的内容
- [ ] 未创建/派发任何子 Agent
- [ ] 未向用户发起任何确认请求

## 验证

- 审查结论引用的文件路径均可复核（只读）
- 由 workflow-manager 使用 list_agents 核验无后续子 Agent

## 输出要求

- 不写文件、不修改代码；直接在最终回复中按以下结构返回：
  背景 / 输入 / 分析 / 决策 / State Delta / 风险 / 下一步 / 变更影响
