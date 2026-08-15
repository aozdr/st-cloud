# TASK-P1-TEST-PREVIEW-TEAM（st-preview 测试 + st-team 集成测试 — executor/implement）

## 元信息

- Task ID: `TASK-P1-TEST-PREVIEW-TEAM`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: 全量 Code Review H7（st-preview 零测试；st-team 无集成测试）

## 目标

1. st-preview：补测试基础设施 + 主路径集成测试（预览缩略图/转码，S3 用 Mock 隔离）≥3 条。
2. st-team：在现有测试目录基础上补 H2 集成测试（加 h2 依赖 + schema + AbstractIntegrationTest），覆盖空间创建/成员邀请/角色端点（含 C2 新增 roles/stats）≥5 条。

## 方法

- 两个模块分别：pom 加 `spring-boot-starter-test` + `h2`（test scope）；`application-test.yml`（H2 MODE=MySQL）；`schema.sql`（st-preview 如无独立表则用最小 schema；st-team 补 team_space/team_member/team_invite/team_role/team_activity/team_comment/team_folder_permission 等，列对齐 `docker/mysql/init/` 对应脚本）；`TestApplication` + `AbstractIntegrationTest`。
- st-team 现有 `src/test` 保留，集成测试放 `src/test/java/.../integration/` 或按既有命名。
- 不改业务主代码（除 pom 测试依赖）。

## 范围

- include：`st-preview/**`、`st-team/**`（pom + 测试目录）；只读 st-core/st-share 测试范式与 `docker/mysql/init/` 建表脚本
- exclude：修改两模块业务主代码；其它 `st-*` 模块；创建子 Agent

## 验收标准

- `mvn -q -pl st-preview -am test` EXIT=0（≥3 条）
- `mvn -q -pl st-team -am test` EXIT=0（集成 ≥5 条）

## 验证

- 主线程复跑两个模块测试
