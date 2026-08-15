# TASK-FIX-M1-SHARE-EXPIRY（分享可选过期时间迭代落地 — executor/implement）

## 元信息

- Task ID: `TASK-FIX-M1-SHARE-EXPIRY`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: 全量 Code Review M1 / Spec P0（分享过期迭代 P2-P7 未落地）

## 目标

按 `.ai/docs/20260813-share-expiry/design.md`（2.1-2.4 节）补齐分享可选过期时间迭代的剩余功能：后端校验与清除、前端时间格式与过期展示、删除死代码、H2 schema、测试基础设施。

## 修改范围（已定版）

### 后端（st-share）
1. `UpdateShareRequest` 新增 `private Boolean clearExpireAt;`（null 视为 false，向后兼容）。
2. `ShareServiceImpl.createShare`：`expireAt != null && !expireAt.isAfter(LocalDateTime.now(ZoneId.of("Asia/Shanghai")))` → `BusinessException(BAD_REQUEST, "过期时间必须晚于当前时间")`。
3. `ShareServiceImpl.updateShare`：`clearExpireAt=true` → 置空 expireAt；否则有 expireAt 时先做未来时间校验再更新。
4. 删除 `ShareAccessVO.isExpired` 字段及 `accessShare` 中 `setIsExpired(false)` 调用。
5. 核心逻辑（过期校验/状态流转）补充中文注释。

### 前端（st-web）
6. `ShareDialog.tsx` `computeExpireAt`：`toISOString()` 改为本地时间 `yyyy-MM-ddTHH:mm:ss`（手写补零，无时区后缀）。
7. `ShareManagePage.tsx`：新增 `isExpiredShare`（status===1 && expireAt && new Date(expireAt.replace(' ','T')) <= new Date()），状态列已过期显示琥珀色"已过期"徽标（保留取消按钮）。
8. `types/index.ts`：删除 `ShareAccessVO.isExpired`。

### 测试与 schema
9. H2：`st-core/src/test/resources/schema.sql` 补齐 `file_share` 表（列定义对照 `docker/mysql/init/02_create_tables.sql` 只读参考）。
10. st-share 测试基础设施：`st-share/pom.xml` 加 `spring-boot-starter-test` + `h2`（test scope）；`st-share/src/test/resources/application-test.yml`、`schema.sql`（file_share + file_node）、`ShareTestApplication`、`AbstractShareIntegrationTest` 等（对齐 st-core 模式）；至少 1 条集成测试覆盖"未来时间合法 / 过去时间 BAD_REQUEST / clearExpireAt 清除过期"。

## 兼容策略

- `clearExpireAt` 为可选新增字段（null=false），老客户端行为不变；`isExpired` 前后端同步删除，无外部依赖。

## 范围

- include（修改/新增）：`st-share/**`、`st-web/src/components/share/ShareDialog.tsx`、`st-web/src/pages/ShareManagePage.tsx`、`st-web/src/types/index.ts`、`st-core/src/test/resources/schema.sql`
- include（只读）：`.ai/docs/20260813-share-expiry/design.md`、`docker/mysql/init/02_create_tables.sql`、`.ai/knowledge/testing.md`
- exclude：`st-team`、`st-api` 配置、`docker/mysql/init` 其它文件（禁止新增/修改迁移脚本）、`st-core` 主代码、其它 `st-*` 模块、创建子 Agent

## 验收标准

- 后端：过去时间被拒（BAD_REQUEST）、`clearExpireAt=true` 清除过期、`ShareAccessVO` 无 `isExpired`
- 前端：本地时间格式提交、管理页已过期徽标、types 无 `isExpired`
- H2 `file_share` 表存在且列对齐 MySQL 02 脚本
- st-share 集成测试通过：`mvn -q -pl st-share -am test`（或指定测试类）EXIT=0
- 前端类型检查通过：`npx tsc --noEmit`（如 st-web 可独立执行）

## 验证

- 主线程复跑 st-share 测试与 tsc；检查 schema.sql 对齐与死代码删除
