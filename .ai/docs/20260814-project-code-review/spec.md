# Spec 审查报告：文档-代码一致性（全库）20260814

> Task: TASK-REVIEW-SPEC-001（reviewer / review）
> 审查方式：只读。对照 `.ai/docs/` 各迭代 requirement/design/uispec 与 `.ai/tasks/`，抽样核对 `st-*/**` 代码与 `docker/mysql/init/*.sql`。
> 结论性质：无单一 PRD，本轴按"文档承诺 vs 代码实现"分类漂移；所有漂移点均附文档路径与代码现状。

## 1. 审查概览

### 1.1 核对文档清单

| 迭代文档 | 路径 | 核对范围 |
|------|------|---------|
| 分享可选过期时间 | `.ai/docs/20260813-share-expiry/{requirement,design,testcases}.md` | 接口契约、过期校验、前端时间格式、测试、H2 schema |
| 块级增量同步 | `.ai/docs/20260813-block-sync/design.md` | file_block 表、block-check/block-upload、uploadPartCopy、桌面端块哈希 |
| 上传低速率中转 | `.ai/docs/20260813-upload-rate-throttle/{requirement,design}.md` | 中转 API、relayChunkSize、pacing、simpleUpload 限速、前端分支 |
| Schema 版本管理 | `.ai/docs/20260812-schema-versioning/design.md` | schema_version 表、compare-schema.ps1、H2 同步 |
| 同步全量对账 | `.ai/docs/20260812-sync-full-reconcile/design.md` | FileNodeVO.fileMd5、fullReconcile |
| 团队空间 P0/P2 | `.ai/docs/20260809-teamspace-p0/design.md`、`20260809-teamspace-p2/design.md` | 邀请/活动/退出/移交/统计端点 |
| 移动端 PWA+Capacitor | `.ai/docs/20260809-mobile-pwa-capacitor/design.md` | runtime/capacitor/PWA/移动组件/android |
| 收藏功能完善 | `.ai/docs/favorites-enhancement-*.md`（根目录） | 收藏 API、页面、排序、遗留清理 |
| 知识库 | `.ai/knowledge/api-reference.md`、`data-model.md`、`business-domain.md` | 端点清单、数据模型、业务规则与代码一致性 |

### 1.2 核对代码范围

- 后端：`st-core`（FileController/UploadServiceImpl/StorageService/FileServiceImpl/FileNodeVO/UploadInitResponse）、`st-share`（ShareController/ShareServiceImpl/DTO）、`st-sync`（SyncController/SyncBlockController/DTO/FileBlock）、`st-team`（TeamController/NotificationController/Service/Entity）、`st-common`（UserTransferLimiter）。
- 数据库：`docker/mysql/init/*.sql`（02~32）、`st-core/src/test/resources/schema.sql`（H2）。
- 前端：`st-web`（ShareDialog/ShareManagePage/useUpload/UploadPanel/TransferManager/TeamSpacePage/StatsPanel/RoleManageDialog/TeamInvitePage/FavoritesPage/Sidebar/MobileTabBar/ActionSheet/MultiSelectBar/runtime/capacitor）、`st-desktop`（sync-engine/database/block-hash/upload-manager）。

## 2. 文档承诺未实现（文档写了，代码缺失/半成品）

### 2.1 分享可选过期时间迭代（P0，最大缺口群）

引用文档：
- `.ai/docs/20260813-share-expiry/requirement.md`「功能范围」「验收标准 1~7」
- `.ai/docs/20260813-share-expiry/design.md`「待修复问题 P1~P7」「§2.2 后端改动」「§2.3 前端改动」「§2.4 测试基础设施」
- `.ai/docs/20260813-share-expiry/testcases.md`（S1~S14 全部未落地）

| 编号 | 文档承诺 | 代码现状 | 严重度 |
|------|---------|---------|--------|
| 2.1.1 | design P2：创建/更新分享时校验 `expireAt.isAfter(now)`，过去时间返回 BAD_REQUEST"过期时间必须晚于当前时间" | `st-share/.../service/impl/ShareServiceImpl.java`：`createShare` L78 `share.setExpireAt(request.getExpireAt())` 无校验；`updateShare` L130-131 仅 `if (request.getExpireAt() != null)` 直接 set，无未来时间校验 | 高 |
| 2.1.2 | design P3：`UpdateShareRequest` 新增 `clearExpireAt` 布尔字段，`true` 时清除过期恢复永久 | `st-share/.../dto/UpdateShareRequest.java` 无 `clearExpireAt` 字段；更新接口无法恢复永久 | 高 |
| 2.1.3 | design P4：分享管理页已过期分享展示琥珀色"已过期"徽标 | `st-web/src/pages/ShareManagePage.tsx` 状态列仅 `status === 1` 即绿色"有效"，无 `expireAt` 过期判断 | 中 |
| 2.1.4 | design P5：前端改提交本地时间 `yyyy-MM-ddTHH:mm:ss`（无时区后缀） | `st-web/src/components/share/ShareDialog.tsx` L38-43 `computeExpireAt` 仍 `d.toISOString()`（UTC 带 Z），存在 8 小时偏移/解析风险 | 中 |
| 2.1.5 | design P7：删除 `ShareAccessVO.isExpired` 死代码（后端恒 false、前端未用） | `st-share/.../dto/ShareAccessVO.java` 字段 `isExpired` 仍在；`ShareServiceImpl` L169 `vo.setIsExpired(false)` 仍在；`st-web/src/types/index.ts` L216 `isExpired: boolean` 仍在 | 低 |
| 2.1.6 | design P1/P6：新增 st-share 测试基础设施 + H2 schema 补 `file_share` 表 | `st-share` 无 `src/test`；`st-core/src/test/resources/schema.sql` 无 `file_share`（grep 0 命中，有 file_chunk/file_block/file_favorite）；testcases S1~S14 全部无对应代码 | 高 |
| 2.1.7 | requirement 验收 7：产出 design/testcases/changereport/codereview/security/testreport 归档 | `.ai/docs/20260813-share-expiry/` 仅有 requirement.md / design.md / testcases.md | 低 |

> 注：`SHARE_EXPIRED(3002)` 访问/下载/列表/流式预览校验已在 `ShareServiceImpl.validateShareAccess`（L299-300）实现，与 requirement 验收 2 一致；缺口集中在"创建/更新校验、清除过期、前端格式与展示、测试"。

### 2.2 团队空间角色/统计端点未暴露（P0，前端调用必然失败）

引用文档：
- `.ai/docs/20260809-teamspace-p2/design.md` L30「Controller: GET /api/team/{spaceId}/stats」、L92 返回结构
- `.ai/knowledge/api-reference.md` 团队模块：`GET /api/team/{spaceId}/roles`、`GET /api/team/{spaceId}/stats`

代码现状：
- Service 层已实现：`st-team/.../service/TeamService.java` `listRoles`(L120)/`createRole`(L123)/`updateRole`(L126)/`deleteRole`(L129)/`getStats`(L145)，`TeamServiceImpl` L760/L844 有完整实现与 VO。
- **Controller 层未暴露任何对应端点**：`TeamController.java` 全量映射清单中无 `/roles`、`/role`、`/stats`。
- 前端已在调用这些不存在的端点：`st-web/src/components/team/StatsPanel.tsx` L31 `GET /team/${spaceId}/stats`；`RoleManageDialog.tsx` L27 `GET /team/${spaceId}/roles`、L47-48 `PUT/POST /team/${spaceId}/role[/{id}]`、L55 `DELETE /team/${spaceId}/role/{roleId}`。

影响：角色管理弹窗与空间统计面板运行期 404/失败（前端 catch 静默，功能不可用）。

### 2.3 块级同步迁移脚本编号（低）

引用文档：`.ai/docs/20260813-block-sync/design.md`「修改范围」写 `docker/mysql/init/27_file_block.sql`。

代码现状：实际文件为 `docker/mysql/init/32_file_block.sql`（内容一致：`file_block` 表、`idx_node_ver` 索引、5MB 块约束注释；`27_sync_exclusion_conflict.sql` 已被其它迭代占用）。文档编号未同步。

### 2.4 移动端 android/ 工程未生成（低/按需）

引用文档：`.ai/docs/20260809-mobile-pwa-capacitor/design.md` 新增文件清单含 `android/ - Capacitor Android 工程(命令行生成)`。

代码现状：`st-web/android/` 不存在（`Test-Path` = False）；runtime.ts、capacitor.ts、capacitor.config.ts、pwa-192/512.png、vite-plugin-pwa、MobileTabBar/ActionSheet/MultiSelectBar 均已落地。按设计标注"命令行生成"，可视为待 `cap add android` 按需生成；若验收含 APK 出包则未达。

## 3. 代码存在但文档未同步（实现已变，文档/知识库还写旧契约）

### 3.1 api-reference.md 缺录新端点（P1，知识库落后）

引用文档：`.ai/knowledge/api-reference.md`（文件/上传/同步/团队各章节）。

以下端点已存在于代码、api-reference.md 未收录：

| 模块 | 缺失端点 | 代码位置 |
|------|---------|---------|
| 上传中转 | `POST /api/file/upload/relay-chunk`、`POST /api/file/upload/relay-finalize` | `st-core/.../controller/FileController.java` L192/L206 |
| 上传响应 | `UploadInitResponse` 新增 `transferMode`/`relayChunkSize`/`relayRateKb` | `st-core/.../dto/UploadInitResponse.java` L27/L30/L33 |
| 块级同步 | `POST /api/sync/block-check`、`POST /api/sync/block-upload` | `st-sync/.../controller/SyncBlockController.java` |
| 同步排除/冲突 | `GET/POST /api/sync/roots/{rootId}/exclusions`、`DELETE /api/sync/roots/{rootId}/exclusions/{exclusionId}`、`PUT /api/sync/roots/{rootId}/conflict-strategy` | `st-sync/.../controller/SyncController.java` L69/L76/L85/L96 |
| 团队 P0 | `GET /api/team/{spaceId}/users/search`、`POST /api/team/{spaceId}/invite`、`GET /api/team/{spaceId}/invites`、`DELETE /api/team/{spaceId}/invite/{inviteId}`、`POST /api/team/invite/{code}`、`POST /api/team/{spaceId}/leave`、`POST /api/team/{spaceId}/transfer`、`GET /api/team/{spaceId}/activities`、`POST /api/team/{spaceId}/activity`、`POST /api/team/{spaceId}/pin` | `st-team/.../controller/TeamController.java` |
| 通知模块 | `GET /api/notification/unread-count`、`GET /api/notification`、`PUT /api/notification/{id}/read`、`PUT /api/notification/read-all` | `st-team/.../controller/NotificationController.java` |

> 已核对：api-reference.md 已含 favorites/隐藏/重复检测/存储 by-type/versions/count/预览 thumbnail/video/管理模块等新端点（L200 之后），上述 6 组为确认缺录项。

### 3.2 数据模型/业务领域知识库缺 file_block（P2）

引用文档：`.ai/knowledge/data-model.md`（有 file_chunk L77、file_share L104、schema_version L347）、`.ai/knowledge/business-domain.md`。

代码现状：`file_block` 表已存在（`docker/mysql/init/32_file_block.sql`、H2 `st-core/src/test/resources/schema.sql` L211、`st-sync` FileBlock 实体），data-model.md 未收录该表；business-domain.md 无块级增量同步、收藏功能章节。知识库与代码不同步。

### 3.3 收藏功能文档落盘位置不合规范（P2）

引用文档：`.ai/knowledge/document-management.md`（按迭代建子文件夹归档要求）。

现状：`favorites-enhancement-{requirement,design,testcases}.md` 直接位于 `.ai/docs/` 根目录而非迭代子文件夹；代码侧已实现 FavoritesPage、侧边栏"我的收藏"、排序、后端 favorite API，遗留 `lib/favorites.ts` 已清理。属文档管理漂移，非功能缺失。

## 4. 接口契约不一致

### 4.1 团队角色/统计：文档与前端契约均无后端支撑（P0）

- api-reference.md 记录 `GET /api/team/{spaceId}/roles`、`GET /api/team/{spaceId}/stats`；p2 design 承诺 `GET /api/team/{spaceId}/stats`。
- 后端 TeamController 无任何 `/roles`、`/role`、`/stats` 端点（Service 实现存在但未暴露）。
- 前端 StatsPanel/RoleManageDialog 调用这些路径 → 实际运行 404。

结论：文档契约、前端调用、后端实现三者不一致，需补端点或下线前端入口。

### 4.2 块级同步契约字段为升级版，文档仍为简版（低，需同步文档）

引用文档：`.ai/docs/20260813-block-sync/design.md` block-check 响应 `{ reusable: [blockIndex...], missing: [blockIndex...] }`。

代码现状：
- `BlockCheckRequest` 在 `{ fileNodeId, blocks:[{index,md5,size}] }` 基础上新增 `fileMd5/fileSize/blockSize`（additive）。
- 响应为 `BlockCheckResponse`：`s3UploadId/storagePath/reusableBlocks[{blockIndex,sourceKey,rangeStart,rangeEnd}]/missingBlocks[{blockIndex,presignedUrl}]`。
- 桌面端 `st-desktop/src/sync-engine.ts`（L290-350）已按升级版契约适配（presignedUrl 直传缺失块 + block-upload 复用块）。

结论：实现与前端一致，属文档落后于实现；非缺陷，但 design 需同步。

### 4.3 已核对一致、无漂移的契约（记录在案）

- 分享过期访问：`SHARE_EXPIRED(3002)` 覆盖 access/download/list/stream（`ShareServiceImpl` L299-300）与 requirement 验收 2 一致。
- 中转上传：`POST /api/file/upload/relay-chunk`（uploadId/s3UploadId/seq 从 1 起，octet-stream；响应 `confirmed/partUploaded/partNumber`）与 design §5 一致；`relayChunkSize = clamp(rate*2s, 8KB, 1MB)`（UploadServiceImpl L75-77/L275）与 design §4 公式一致。
- 分享 API 基础路径：`POST /api/share/create`、`GET /api/share/list`、`PUT/DELETE /api/share/{shareId}` 与 api-reference.md 一致。
- 同步 delta：`GET /api/sync/roots/{rootId}/delta` 与 api-reference.md 一致。
- Schema 版本管理：`schema_version` 表（MySQL 31_schema_version.sql + H2 schema.sql L197）、基线记录 20260811.1/20260812.1、`.ai/scripts/compare-schema.ps1` 均存在，与 design 一致。
- 同步全量对账：`FileNodeVO.fileMd5`（L38）、`FileServiceImpl.toVO`（L576）填充、`sync-engine.ts` `fullReconcile/reconcileFolder`（L457/L477）均落地，与 design 一致。
- 团队 P0：8 个新增端点 + TeamInvite/TeamActivity/ActiveTracker/TeamActivityHelper + TeamInvitePage + 路由均落地，与 design 一致（除 4.1 的 roles/stats）。
- 上传低速率中转：后端 relay 全链路、UploadPaceBucket 等价实现（`UserTransferLimiter.acquireUploadPace` L71）、simpleUpload 限速接入（`UploadServiceImpl` L566-586 LimitedInputStream）、前端 st-web/st-desktop relay 分支与"限速中转上传中 · 限速 X KB/s"状态均落地。

## 5. 结论与建议

### 5.1 结论

代码整体完成度高：上传限速中转、块级同步、同步全量对账、Schema 版本管理、团队 P0、移动端 PWA 基座、收藏功能均已落地且与各自迭代 design 基本一致。

漂移集中在 4 处：

1. **分享过期迭代整体未实现**（P0）：design 中 P2/P3/P4/P5/P7 全部未落地，测试与 H2 schema 缺失，与 requirement/design/testcases 形成大面积"文档承诺未实现"。
2. **团队角色/统计端点缺失**（P0）：Service 有实现但 Controller 未暴露，api-reference 与前端均已按存在端点编写，运行期 404。
3. **api-reference.md 知识库落后**（P1）：中转、块级同步、同步排除/冲突、团队 P0、通知共 6 组新端点未收录；data-model/business-domain 缺 file_block 等。
4. **文档管理类漂移**（P2）：block-sync 脚本编号 27→32、favorites 文档未归档子文件夹、mobile android 工程未生成（按设计为命令行生成）。

### 5.2 建议（按优先级）

| 优先级 | 动作 | 责任方 |
|-------|------|--------|
| P0 | 实现分享过期剩余功能：创建/更新未来时间校验、`clearExpireAt` 清除过期、管理页过期展示、前端本地时间格式、删除 `ShareAccessVO.isExpired`；补 st-share 测试 + H2 `file_share` 表；或如实降级文档并关闭该迭代 | workflow-manager + executor |
| P0 | 暴露团队 `/api/team/{spaceId}/stats` 与角色 CRUD 端点（Service 已就绪，补 Controller 映射即可），或下线 StatsPanel/RoleManageDialog 入口；同步 api-reference | workflow-manager + executor |
| P1 | 更新 `.ai/knowledge/api-reference.md`：补中转 2 端点 + UploadInitResponse 新字段、块级同步 2 端点、同步排除/冲突 3 端点、团队 P0 10 端点、通知 4 端点 | executor（文档） |
| P1 | 同步 `.ai/docs/20260813-block-sync/design.md` 脚本编号 27→32，并更新 block-check 响应契约（reusableBlocks/missingBlocks 升级版） | executor（文档） |
| P2 | `.ai/knowledge/data-model.md` 补 `file_block` 表；business-domain.md 补块级同步/收藏规则章节 | knowledge manager |
| P2 | favorites 三文档归档至迭代子文件夹；`android/` 按需执行 `cap add android` 生成 | workflow-manager |

### 5.3 审查局限

- 本次为文档-代码静态一致性审查，未执行 `mvn test` / `npm run build` / 运行期接口探测；4.1 的 404 结论基于端点清单比对（前端调用路径 vs Controller 映射全集），建议联调验证。
- 未读取其它审查章节（standards.md），遵循 TASK scope 隔离。
- 数据库侧以 init SQL 与 H2 schema.sql 静态比对为准，未连接 MySQL 执行 compare-schema.ps1。
