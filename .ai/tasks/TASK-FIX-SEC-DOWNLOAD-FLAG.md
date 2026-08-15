# TASK-FIX-SEC-DOWNLOAD-FLAG（分享表加 allow_download 下载标识 — executor/implement）

## 元信息

- Task ID: `TASK-FIX-SEC-DOWNLOAD-FLAG`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: S-02 加固——用分享表专用标识统一控制"下载/流式"权限

## 目标

在 `file_share` 表新增 `allow_download` 标识（0-禁止下载/流式，1-允许），作为 `getDownloadUrl` 与 `streamShareFile` 的统一下载开关；`permission` 字段保留（前端展示/兼容），下载控制以 `allow_download` 为权威。

## 修改（已定版）

1. **迁移脚本** `docker/mysql/init/33_share_allow_download.sql`（对齐 30 号 information_schema 幂等守卫模式）：
   - `ALTER TABLE file_share ADD COLUMN allow_download TINYINT NOT NULL DEFAULT 1 COMMENT '允许下载：0-禁止 1-允许（下载URL与流式统一开关）'`（守卫：列不存在才执行）
   - 历史数据联动：`UPDATE file_share SET allow_download = 0 WHERE permission = 0`（仅查看的旧分享迁移后默认禁止下载，避免语义反转）
2. **H2 schema**：`st-core/src/test/resources/schema.sql` 与 `st-share/src/test/resources/schema.sql` 的 `file_share` 补 `allow_download TINYINT NOT NULL DEFAULT 1`。
3. **实体/DTO**：
   - `FileShare`：`private Integer allowDownload;`
   - `CreateShareRequest`：`private Integer allowDownload = 1;`（`@Schema` 注释）
   - `UpdateShareRequest`：`private Integer allowDownload;`
   - `ShareVO`：`private Integer allowDownload;`（toVO 赋值）
4. **ShareServiceImpl**：
   - `createShare`：`share.setAllowDownload(request.getAllowDownload() != null ? request.getAllowDownload() : (share.getPermission() >= 1 ? 1 : 0))`——未显式传时与 permission 联动（仅查看默认禁止下载）。
   - `updateShare`：`request.getAllowDownload() != null` → wrapper.set（加入 hasChanges）。
   - `getDownloadUrl`：在 validateShareAccess 后检查 `share.getAllowDownload() == null || share.getAllowDownload() == 0` → `SHARE_ACCESS_DENIED("该分享不可下载")`（现有 permission==0 检查保留或合并，保持双保险）。
   - `streamShareFile`：同样检查 allowDownload==0 拒绝（堵住流式绕过）。
5. **前端**：`st-web/src/types/index.ts` 的 `FileShare` 加 `allowDownload: number;`（页面下载按钮逻辑保持 permission>=1 不变，后端 allow_download 兜底）。
6. **测试（st-share）**：新增用例——`allowDownload=0` 时 `getDownloadUrl` 拒绝、`streamShareFile` 拒绝；`allowDownload=1` 正常；既有"仅查看拒绝下载"用例对齐 allow_download 语义。

## 范围

- include：`docker/mysql/init/33_share_allow_download.sql`（新增）、`st-core/src/test/resources/schema.sql`、`st-share/**`、`st-web/src/types/index.ts`
- exclude：修改既有迁移脚本（02-32）、`st-team`/`st-auth`/`st-admin`/`st-sync`/`st-core` 主代码、创建子 Agent

## 验收标准

- 33 号脚本含幂等守卫 + 历史数据联动 UPDATE
- 后端下载/流式路径以 allowDownload 为开关（rg 复核）
- `mvn -q -pl st-share -am test` EXIT=0（新增 allow_download 用例全绿）
- 前端类型含 allowDownload；未改前端下载按钮逻辑

## 验证

- 主线程执行迁移到 MySQL + `compare-schema.ps1` PASS + 登记 `schema_version` 20260814.2
