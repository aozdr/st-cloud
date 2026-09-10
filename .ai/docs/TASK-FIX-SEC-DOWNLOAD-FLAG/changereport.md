# Change Report — TASK-FIX-SEC-DOWNLOAD-FLAG（file_share 加 allow_download 下载标识）

## 元信息

- Task ID: `TASK-FIX-SEC-DOWNLOAD-FLAG`
- Agent: executor（taskType=implement）
- dispatchId: `sec-flag-001`
- claimedFile: `inbox-sec-flag-001.md`
- 日期: 2026-08-14
- 来源: S-02 加固——用分享表专用标识统一控制"下载/流式"权限

## 背景

此前下载控制以 `permission`（0-查看 1-下载 2-上传 3-编辑）兼任，语义混杂：`permission=0` 的分享仍可流式预览并计数，下载拒绝逻辑散落在 `getDownloadUrl` 与 `streamShareFile` 两处。本次按 TASK 定版方案为 `file_share` 新增 `allow_download` 专用标识（0-禁止下载/流式，1-允许），作为 `getDownloadUrl` 与 `streamShareFile` 的统一下载开关；`permission` 保留用于前端展示/兼容，下载控制以 `allow_download` 为权威。

## 输入

- `TASK-FIX-SEC-DOWNLOAD-FLAG.md`（定版修改点 1-6 与验收标准）
- `docker/mysql/init/02_create_tables.sql`（file_share 定义：permission/download_limit 等）
- `docker/mysql/init/30_sync_change_log_event_log_id.sql`（information_schema 幂等守卫模式）
- `st-core/src/test/resources/schema.sql` 与 `st-share/src/test/resources/schema.sql`（H2 file_share 列集）
- `FileShare` / `CreateShareRequest` / `UpdateShareRequest` / `ShareVO` / `ShareServiceImpl` 现有实现
- `st-web/src/types/index.ts`（FileShare / CreateShareRequest 类型）
- 既有分享安全/过期集成测试（H2 真实 Mapper + Mock 外部服务）

## 分析

1. **迁移方案**：33 号脚本沿用 30 号脚本的 `information_schema.COLUMNS` 存在性守卫（列不存在才 `ALTER TABLE`），配合 `PREPARE/EXECUTE/DEALLOCATE`，重复执行不报错。历史数据联动 `UPDATE file_share SET allow_download = 0 WHERE permission = 0`，保证"仅查看"旧分享迁移后默认禁止下载，避免语义反转。
2. **默认值策略**：`CreateShareRequest.allowDownload` 默认 1；`createShare` 未显式传时与 `permission` 联动（`permission>=1 → 1`，`permission=0 → 0`）。新创建"仅查看"分享即默认禁止下载；存量分享由迁移脚本统一对齐。
3. **下载/流式统一开关**：`getDownloadUrl` 与 `streamShareFile` 均在 `validateShareAccess` 之后先检查 `allowDownload==0` → `SHARE_ACCESS_DENIED("该分享不可下载")`；原 `permission==0` 检查保留（双保险）。流式链路同样受开关约束，堵住绕过 `getDownloadUrl` 的下载通道。
4. **测试语义对齐**：原 `permission=0` 流式预览成功用例（permission=0 允许 inline 预览）与"仅查看默认禁止下载"新语义冲突，改为显式 `allowDownload=1`（permission=1）验证流式成功并计数；原"仅查看拒绝下载"用例命中新的 `allow_download` 开关（消息由"仅查看不可下载"变为"该分享不可下载"，断言同步对齐）。

## 决策

严格按 TASK 定版方案实施，未越界修改：

- 新增 `docker/mysql/init/33_share_allow_download.sql`（幂等守卫 + 历史数据联动 UPDATE）。
- H2 schema：`st-core` 与 `st-share` 的 `file_share` 补 `allow_download TINYINT NOT NULL DEFAULT 1`。
- `FileShare` / `CreateShareRequest` / `UpdateShareRequest` / `ShareVO` 增加 `allowDownload` 字段（含 `@Schema` 与 toVO 赋值）。
- `ShareServiceImpl`：`createShare` 默认联动、`updateShare` 可切换、`getDownloadUrl`/`streamShareFile` 以 `allowDownload==0` 拒绝。
- 前端 `st-web/src/types/index.ts`：`FileShare.allowDownload`（必填）与 `CreateShareRequest.allowDownload`（可选）；未改任何下载按钮逻辑（仍以 permission>=1 展示）。
- 测试：新增 allowDownload=0 下载/流式拒绝、allowDownload=1 正常、createShare 默认联动、updateShare 切换 4 组用例；对齐"仅查看拒绝下载"与流式计数用例。

## 修改文件清单

- 新增：`docker/mysql/init/33_share_allow_download.sql`
- 修改：`st-core/src/test/resources/schema.sql`（file_share 补 allow_download）
- 修改：`st-share/src/test/resources/schema.sql`（file_share 补 allow_download）
- 修改：`st-share/src/main/java/com/stcloud/share/entity/FileShare.java`
- 修改：`st-share/src/main/java/com/stcloud/share/dto/CreateShareRequest.java`
- 修改：`st-share/src/main/java/com/stcloud/share/dto/UpdateShareRequest.java`
- 修改：`st-share/src/main/java/com/stcloud/share/dto/ShareVO.java`
- 修改：`st-share/src/main/java/com/stcloud/share/service/impl/ShareServiceImpl.java`
- 修改：`st-share/src/test/java/com/stcloud/share/ShareServiceImplSecurityIntegrationTest.java`
- 修改：`st-web/src/types/index.ts`

未改动既有迁移脚本（02-32）、`st-team`/`st-auth`/`st-admin`/`st-sync`/`st-core` 主代码。

## 与验收标准对照

| 验收项 | 结果 |
|--------|------|
| 33 号脚本含幂等守卫 + 历史数据联动 UPDATE（permission=0 → allow_download=0） | PASS |
| getDownloadUrl / streamShareFile 以 allowDownload==0 拒绝（rg 复核） | PASS |
| `mvn -q -pl st-share -am test` EXIT=0（新增 allow_download 用例全绿） | PASS（26 用例） |
| 前端类型含 allowDownload；未改前端下载按钮逻辑 | PASS |
| 核心逻辑中文注释（默认联动/下载开关/流式拒绝） | PASS |

## 测试结果

- 命令：`mvn -q -pl st-share -am test`
- 结果：EXIT=0；`ShareServiceImplExpiryIntegrationTest` 10/10、`ShareServiceImplSecurityIntegrationTest` 16/16，st-auth/st-core/st-team 依赖模块测试全部通过。
- 新增用例：allowDownload=0 时 getDownloadUrl 拒绝、allowDownload=0 时 streamShareFile 拒绝（堵住流式绕过）、allowDownload=1 时 getDownloadUrl 正常、createShare 未传 allowDownload 与 permission 联动、updateShare 可切换下载开关。

## 风险

- **行为收紧**：历史"仅查看"（permission=0）分享迁移后 `allow_download=0`，其流式预览也将被拒绝（此前允许 inline 预览）。属 TASK 定版语义（下载/流式统一开关），但前端 ShareAccessPage 对 `permission===0` 且不可预览的提示分支仍按原逻辑展示，行为不变。
- **前端展示依赖**：`ShareAccessVO` 未新增 allowDownload（TASK 范围未含），访问页下载按钮仍以 permission>=1 展示；若创建"可下载但 allowDownload=0"的分享，前端会展示下载入口但后端拒绝——由后端兜底，已通过测试覆盖。
- **迁移执行**：33 号脚本与 `schema_version` 登记（20260814.2）由主线程按 AGENTS.md 数据库强制流程执行（compare-schema 门禁）。

## State Delta

- 新增 artifact：`.ai/docs/TASK-FIX-SEC-DOWNLOAD-FLAG/changereport.md`；33 号迁移脚本；后端实体/DTO/Service 变更；H2 schema 同步；前端类型；4 组新增集成用例。
- 建议 exitCriteria：IMPLEMENTED（st-share + 迁移脚本）→ done；等待主线程执行 MySQL 迁移 + `compare-schema.ps1` + `schema_version` 登记后进入 CODE_REVIEW / SECURITY_REVIEW。

## 下一步

- 主线程执行 `docker/mysql/init/33_share_allow_download.sql` 到运行中 MySQL，运行 `compare-schema.ps1` 确认 PASS，并向 `schema_version` 登记 `20260814.2`。
- 重派 reviewer 复核 allow_download 开关覆盖（getDownloadUrl/streamShareFile）与迁移脚本幂等性。
- 前端可按需在分享创建/管理页增加 allowDownload 开关（本次未改下载按钮逻辑）。

## 变更影响

- 影响范围：`file_share` 表结构（+allow_download）、分享创建/更新/下载/流式四条链路、分享管理/创建接口响应与请求模型（新增字段，向后兼容）。
- 对其它模块：`st-web` 仅类型文件变更；`st-core` 仅测试 schema.sql 变更；其余模块无改动。
- 对测试：既有 25 个 st-share 用例保持通过（1 个用例语义对齐调整），新增 4 组用例后共 26 个全绿。
