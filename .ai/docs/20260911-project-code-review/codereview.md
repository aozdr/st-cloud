# 代码 Review 记录 — st-cloud 项目级全量双轴审查

> 方法论：`code-review` skill（Standards 轴 + Spec 轴，分列不合并）
> 固定基点：`HEAD = b97faa5`（工作区改动全在 `.ai/` 编排目录，无未提交业务代码）
> 审查方式：4 个并行只读子 Agent 分区审查；本次**不修改任何业务代码、不运行构建**

---

# 一、Review 概览

## 基本信息

```
功能名称：st-cloud 项目级全量代码审查
Review 范围：全部已提交代码（HEAD = b97faa5），按模块分区
涉及模块：st-core / st-auth / st-admin / st-share / st-team / st-sync /
          st-search / st-preview / st-common / st-api / st-web / st-desktop
          + docker/mysql/init/*.sql（迁移脚本）
规范依据：.ai/knowledge/conventions.md、architecture.md、frontend.md、
          testing.md、data-model.md、business-domain.md、ui-design-system.md
          + Fowler《重构》第 3 章坏味道基线（12 条）
需求依据：docs/PRD-云盘系统-v2.0.md、PRD-用户权限系统.md、
          docs/IMPROVEMENT-PLAN-安全与架构改进.md、DESIGN-st-sync-同步引擎.md、
          .ai/docs/<各迭代>/requirement.md + design.md
```

## 分区与规模

| 分区 | 模块 | 规模 | 审查焦点 |
|------|------|------|---------|
| A | st-core | 108 文件 / 10095 行 | 上传链路、对象去重引用计数、事务 Outbox、归档、回收站、版本、在线编辑 |
| B | st-auth + st-admin + st-share + st-team | 123 文件 / 8352 行 | 认证鉴权、越权、审计、限速、分享防爆破、团队权限 |
| C | st-sync + st-search + st-preview + st-common + st-api + 迁移脚本 | 86 文件 / 5333 行 + 38 份 SQL | 块级同步、MQ 幂等、WebSocket、ES、缓存、迁移脚本 |
| D | st-web + st-desktop | 142 文件 / 21666 行 + Electron 主进程 | 组件职责、状态管理、内存泄漏、Electron 安全、IPC 校验 |

## 问题总量

| 轴 | Critical | Major | Minor | Suggestion | 合计 |
|----|---------|-------|-------|-----------|------|
| Standards | 5 | 17 | 12 | 3 | **37** |
| Spec | 5 | 13 | 7 | 2 | **27** |
| **合计** | **10** | **30** | **19** | **5** | **64** |

---

# 二、Standards 轴（是否符合项目规范 + 坏味道基线）

## A. st-core

- **[Critical] 对象引用归零判定与 S3 物理删除之间存在「墓碑复活」窗口，可产生悬空引用** — `st-core/.../service/impl/FileObjectServiceImpl.java:93-100` + `mapper/FileObjectMapper.java:44-52` — 依据：conventions.md 事务边界 2「DB 事务内引用归零 + 记录待删状态（outbox）」，但 `release()` 先 `decrementRefCount` 再独立 `getRefCount` 读判，非同一原子操作；`permanentDeleteNodeAndChildren` 判定 `remaining <= 0` 后在同一事务写 Outbox，AFTER_COMMIT 才异步删 S3。并发上传在「事务提交后、S3 删除前」调 `acquireByPath` 命中墓碑 → `revive()` 把 `ref_count` 置 0 且 `storage_path` 指向新临时路径，随后 S3 删除事件按旧路径删对象 → 复活的 file_node 指向已删除的物理对象（下载 500）。
- **[Major] 归档解压无容量层校验，且 `commitExtract` 只有个人配额分支** — `ArchiveServiceImpl.java:124`、`UploadCommitManager.java:334-336` — 依据：conventions.md 事务边界 1；`commitExtract` 无条件 `userQuotaMapper.updateStorageUsed(userId, size)`，而同类中 `commitTextOverwrite`（:220-222）与 `commitEditorSave`（:263-265）均正确区分 `spaceId` → **同文件三种落库口径不一致**，团队空间解压错扣个人配额。
- **[Major] `finalizeMerge` 去重命中后临时对象归属判断语义脆弱** — `UploadCommitManager.java:160-184` + `UploadServiceImpl.java:392-395` — 依据：坏味道 **Shotgun Surgery**（对象归属规则散落在 `UploadServiceImpl` / `UploadCommitManager` / `VersionServiceImpl` 三处，靠注释对齐）。`node` 是事务内被修改的同一实例，事务外再用 `mergedPath.equals(node.getStoragePath())` 判断是否删临时对象，读到的已是新路径，判断恒真或恒假。
- **[Major] `restoreVersion` 读-判-写非原子（无行锁、无 `@Version`）** — `VersionServiceImpl.java:109,116,137-149` — 依据：conventions.md 事务边界 3；`versionNum` 由 `getLatestVersion` 读取后计算，并发恢复可产生重复版本号。
- **[Major] 长事务无显式 `timeout`，且回收站单事务内递归遍历 + 每节点多次 DB 往返** — `RecycleBinServiceImpl.java:72-137,182-191` — 依据：conventions.md 事务边界 4「长事务显式 timeout，全局默认 30s」。`permanentDeleteNodeAndChildren` 每个叶子执行 `release` + `countOtherRefsByStoragePath` + `updateStorageUsed` + `publishFileIndex`（Outbox INSERT）+ `deleteById` + `invalidateAccessible` + `syncRefCountByMd5`（全表 UPDATE）→ 删 1000 文件即上千次往返，方法上无 `@Transactional(timeout=)`。
- **[Major] 客户端传入的 `s3UploadId` 直接用于 S3 操作，未与服务端持久化记录校验** — `UploadServiceImpl.java:375,415,441`、`RelayBufferManager.java:213` — 依据：conventions.md 安全约定 + `20260813-upload-rate-throttle/design.md`「每个请求校验 uploadId 归属当前用户」。`s3UploadId` 在 `initChunkedUpload` 返回后**未持久化**（`file_chunk` 表无该列），服务端只能盲信客户端参数。
- **[Minor] 依赖注入方式不统一 + 常量重复定义** — `UploadServiceImpl.java:78-83` / `UploadCommitManager.java:44-45` — 依据：conventions.md「`@RequiredArgsConstructor` + final，不使用 `@Autowired` 字段注入」+ 坏味道 **Duplicated Code**（`REF_COUNT_INITIAL` / `SIMPLE_UPLOAD_THRESHOLD` 两处定义）。同时混用 `@Resource` 与 `@Autowired(required=false)`。
- **[Minor] 半成品对象未统一 `tmp/` 前缀** — `UploadServiceImpl.java:188`、`ArchiveServiceImpl.java:156`、`EditorCallbackServiceImpl.java:190`、`TextFileServiceImpl.java:89` — 依据：conventions.md 事务边界 5。全模块 `grep 'tmp/'` **零命中**，定期清理无法按前缀识别半成品。
- **[Minor] 配额预检逻辑在 5 处复制粘贴** — `TextFileServiceImpl.java:130-148`、`EditorCallbackServiceImpl.java:373-391`、`ArchiveServiceImpl.java:294-302`、`UploadManager.java:71-89`、`NewFileServiceImpl.java:203-224` — 依据：坏味道 **Duplicated Code** + **Shotgun Surgery**（配额规则变更需改 5 处）。
- **[Minor] 文件转换未校验团队 `upload` 权限点** — `FileConvertServiceImpl.java:79,113-115` — 依据：`20260815-onlyoffice-editor/requirement.md`「编辑判定 = 具备 upload 能力」+ 坏味道 **Feature Envy**（权限判断应属团队权限服务）。仅 `validateAccessible` + owner 隐含判定。
- **[Suggestion] `FileServiceImpl.decrementRefCount` 是死代码** — `FileServiceImpl.java:497-505` — 依据：坏味道 **Speculative Generality** / **Middle Man**。`if (newRefCount <= 0) { /* 空 */ }` 空分支，包级可见但无调用点。

## B. st-auth / st-admin / st-share / st-team

- **[Major] 分享提取码明文存储与明文比对，与安全约定直接冲突** — `ShareServiceImpl.java:634`、`entity/FileShare.java:19` — 依据：conventions.md「分享提取码使用 BCrypt 加密」— 证据：`if (password == null || !password.equals(share.getPassword()))`，实体注释自认「提取码明文(旧数据可能为 BCrypt)」。**`20260823-share-bruteforce` 设计侧认了明文比较，但 conventions.md 未同步修订 → 规范与实现已漂移**。
- **[Major] `AuditAspect` 30+ 分支巨型 switch** — `AuditAspect.java:239-484` — 依据：坏味道 **Repeated Switches** + **Divergent Change**。`switch (action)` 与 `@Auditable.action()` 字符串耦合，新增业务动作需同时改注解值与切面。
- **[Major] 服务层使用 `@Resource` / `@Autowired(required=false)` 字段注入** — `TeamServiceImpl.java:47-76`（19 个字段）、`FolderPermissionService.java:74-89`（:88 用 `@Autowired(required=false)`）、`ShareServiceImpl.java:86-122` — 依据：conventions.md 依赖注入约定。
- **[Major] 权限缓存返回可变集合的共享实例** — `FolderPermissionService.java:217-229,255` — 依据：可变共享状态。`Set<String> cached = ...; if (cached != null) return cached;` 直接暴露缓存内部实例，调用方 `result.addAll` 前未隔离 → 可污染全局权限缓存。
- **[Minor] Feature Envy：成员查询在 4 处重复** — `TeamServiceImpl.java:319-320,434-435,546-547,792-793` — 依据：Fowler **Feature Envy** / **Duplicated Code**。同一 `selectOne(spaceId + userId)` 重复，与 `checkPermission` / `resolveMyPermissions` 职责重叠。
- **[Minor] Primitive Obsession：裸 `String` / `Integer` 传权限语义** — `ShareServiceImpl.java:157-182`（`shareType` 0/1、`permission` 0-3）、`TeamServiceImpl.java:100`（`member.setRole(0)`）— 依据：Fowler **Primitive Obsession**，魔法值无类型约束。
- **[Minor] `TeamServiceImpl` 单文件 1089 行承载 7 类职责** — `TeamServiceImpl.java` — 依据：Fowler **Divergent Change**。类内 `// ===== 空间/成员/邀请/评论/锁/角色/统计 =====` 分节。
- **[Suggestion] `AuditAspect.java:528-531` 注释残留空方法体** — 依据：**Speculative Generality** 痕迹。

## C. st-sync / st-search / st-preview / st-common / st-api

- **[Critical] 块级同步缺失 multipart 中止/清理路径** — `SyncBlockServiceImpl.java:56,72,108` — 依据：conventions.md 事务边界 5。`blockCheck` 只 `initMultipartUpload`，`blockUpload` 异常直接抛出；`abortMultipartUpload` 定义于 `StorageServiceImpl.java:206`，全仓唯一调用点是 `UploadStorageManager.java:52`（st-core 中转路径），**st-sync 无任何调用** → 失败遗留 S3 垃圾分片，永久泄漏。
- **[Critical] `blockUpload` 完全信任客户端 `storagePath` / `s3UploadId` / `fileMd5` / `fileSize`** — `SyncBlockServiceImpl.java:132-178`、`SyncBlockCommitManager.java:67` — 依据：conventions.md 安全约定 + **Primitive Obsession**（信任边界缺失）。`request.getStoragePath()` 直接进 `completeMultipartUpload` 与 `acquireByPath(tenantId, md5, fileSize, storagePath)`；`acquireByPath`（`FileObjectServiceImpl.java:44-59`）仅按 `tenantId+md5` 查重并原样落库，不校验 key 归属、不与 `blockCheck` 签发值比对 → 可写任意 key、污染配额与元数据。
- **[Major] `blockCheck` 新对象路径未做 `tmp/` 隔离；`blocks.index/size` 无校验** — `SyncBlockServiceImpl.java:70-71,93`、`BlockCheckRequest.java:38-44` — 依据：conventions.md 事务边界 5 + **Primitive Obsession**。`rangeStart = blockIndex * BLOCK_SIZE_5MB` 未校验 index 连续性与 `totalBlocks` 一致性。
- **[Major] `delta` 声明 `page` 参数却完全未使用** — `SyncServiceImpl.java:137,151`、`SyncController.java:62` — 依据：**Mysterious Name**。分页靠 `since` 游标而非页码，二者语义冲突。
- **[Major] `SyncBlockServiceImpl` 无集成测试，仅 Mockito 单测** — `st-sync/src/test/.../SyncBlockServiceImplNoTransactionTest.java` — 依据：testing.md「Service 涉及 Mapper 调用的至少要有集成测试覆盖主路径」。无 `*IntegrationTest`，`st-sync/src/test/resources/` 目录不存在，块布局 INSERT/DELETE SQL 从未被真实执行验证。
- **[Major] 缓存 key 缺租户维度，多租户下跨租户污染** — `FileServiceImpl.java:585` + `RedisTtlCache.java:32` — 依据：MyBatisPlusConfig 已按 `tenant_id` 隔离 DB，缓存层应对齐 + **Data Clumps**。`String key = ACCESSIBLE_KEY_PREFIX + nodeId`，Redis key 前缀 `stcloud:cache:` 全局共享、无 `tenantId` 段。
- **[Major] `TtlCache` 无容量上限** — `TtlCache.java:27,49` — 依据：缓存需有界。`ConcurrentHashMap` + 无 size 检查、无 LRU、无过期清扫线程，仅 `get` 时惰性删单项 → 冷 key 永久驻留。
- **[Major] `SpeedLimitCache` 无容量上限 + 全局 `synchronized(this)` 锁粒度** — `SpeedLimitCache.java:28,58` — 依据：**Duplicated Code**（重复双重检查）+ 无界 Map。`Map<Long,TenantEntry> entries` 按租户无限增长；所有租户读刷新串行。
- **[Minor] 迁移脚本编号复用：`09_jwt_secret.sql` 与 `09b_remove_two_factor.sql` 共用前缀 `09`** — 依据：conventions.md「编号严格递增，不可复用已存在的编号」。
- **[Minor] `26_sync_change_log.sql:27` 的 `ALTER TABLE ADD COLUMN` 无幂等守卫** — 依据：conventions.md「所有 DDL 使用 `IF NOT EXISTS` 保证幂等」。同类写法在 `30_sync_change_log_event_log_id.sql:8-17` 已用 `information_schema` + `PREPARE` 守卫修复，26 号未回填。
- **[Suggestion] `SyncWebSocketHandler` 无连接数上限与空闲超时；推送仅按 userId 不校验租户** — `SyncWebSocketHandler.java:26,36`、`SyncChangeMessageConsumer.java:69` — 依据：资源无界。
- **[Suggestion] JWT 校验未显式限定算法** — `JwtUtils.java:199-207` — 依据：安全约定。`verifyWith(signingKey)` 未显式声明 `sig().add(...)` 白名单（jjwt 现代 API 已强约束，属加固建议）。

## D. st-web / st-desktop

- **[Critical] 渲染层拼接 Windows 反斜杠路径交给主进程写盘** — `st-web/src/hooks/file-browser/useFileDownload.ts:39`、`components/file/DownloadDialog.tsx:32` — 依据：conventions.md「API 调用通过 lib/api.ts」+ **Primitive Obsession**（路径拼接落在渲染层）；`20260823-frontend-desktop-review-fix/design.md` D-2 要求「文件路径仅允许对话框/下载目录返回值」。云端返回的 `n.name` 未做非法字符/分隔符校验即拼进保存路径。
- **[Critical] `sandbox: false` + `webSecurity: false` 双开** — `st-desktop/src/main.ts:63-64`、`mini-window.ts:182` — 依据：设计 D-1「改回 `webSecurity:true`；生产启用 sandbox」。注释标 `// TODO(P1#5 revisit)` → P0 项未落地，渲染层完全失去同源与 OS 沙箱保护。
- **[Major] `useFileKeyboard` 未清理 `setTimeout`** — `useFileKeyboard.ts:203-205` — 依据：React Hooks 清理约定。effect cleanup（:218 仅 `removeEventListener`）未 `clearTimeout`，切路由后定时器仍驻留写 ref。
- **[Major] `useLongPress` 全项目零引用（死代码）** — `useLongPress.ts:22,26,42` — 依据：frontend.md Hooks 表列为移动端能力，但实际长按走 `contextmenu` 分支（`useFileBrowser.ts:436`）+ **Speculative Generality**。`triggeredRef` 只写不读。
- **[Major] 上传分片进度无节流，每个分片触发全量 `setTasks` 映射** — `useUpload.tsx:217-221` — 依据：`20260822 评审 W13`「建议节流到约 250ms」（未实施）。5 并发 × 大文件分片数 = 数百次整表 copy + Provider 全下游重渲染。
- **[Major] 服务端数据双份真相（Zustand + 组件本地 state）** — `store/favorites.ts:8` 与 `useFileBrowser` 本地 `files` 并存 — 依据：conventions.md「状态管理统一使用 Zustand」+ **Duplicated Code** / **Divergent Change**。`toggleFavorite` 只更新 store 的 `favoriteIds`，不同步列表。
- **[Major] `api.ts` 401 刷新失败用 `window.location.href` 硬跳转** — `lib/api.ts:111,117`、`store/auth.ts:50` — 依据：`20260822 评审 W1`（未修复）。绕过 React Router，Electron `app://` 下整页状态丢失；`_retry` 已置位时 `refreshSubscribers` 队列不再重试。
- **[Minor] `permission.ts` 与 `permissions.ts` 命名仅差一字母、语义完全不同** — `lib/permission.ts:9`（`usePermission()` hook）vs `lib/permissions.ts:10`（`PERMISSION_KEYS` 常量） — 依据：`20260822 评审 W14`。极易误引。
- **[Minor] `sanitizeHighlight` 依赖 `dangerouslySetInnerHTML`** — `lib/utils.ts:24-30` — 依据：`20260822 评审 W14`「更好做法是后端返回结构化片段」。虽经 DOMPurify 白名单，链路仍是 HTML 注入面。
- **[Minor] `frontend.md` W8 关于 `any` 的描述已过期** — `types/index.ts`（752 行，全文件无 TODO） — 依据：frontend.md:71「`src/types/index.ts` 的 TODO（isPinned 需后端加入 VO）」— 文档与代码漂移。
- **[Suggestion] `useFileKeyboard` 的 `FileKeyboardActions` 定义 20+ 回调字段** — `useFileKeyboard.ts:43-49`、`useFileBrowser.ts:472-483` — 依据：**Data Clumps** / Long Parameter List。

---

# 三、Spec 轴（是否符合 PRD / 迭代需求）

## A. st-core

- **[Critical] 永久删除后同 md5 重传的去重/引用计数在并发下指向已物理删除的对象** — 依据：`architecture.md`「ref_count == 0 时删除 S3 物理对象并逻辑删除 file_object；ref_count > 0 时保留物理对象」— 证据：`FileObjectMapper.java:58-59` `markDeleted` 置 `status=1, deleted=1`，`selectByTenantAndMd5` 过滤 `status=0 AND deleted=0`；`revive`（:66-67）依赖 `(deleted=1 OR status<>0)` 命中并**重置 ref_count=0**，与并发 `insertIgnore` 竞争（insertIgnore 返 0 → 走 revive → `ref_count=0` → `incrementRefCount`）。若 `PhysicalDeleteEventListener` 已按 AFTER_COMMIT 删除该对象，新节点即指向不存在的物理对象。**任务点名的验证项 → 仍存在**。
- **[Critical] `relayFinalize` 失败时对同一 uploadId 重复 abort，双重状态写入** — 依据：`.ai/state/20260817-transaction-boundary.yaml` completionCriteria「F3/F4/F5 完成：块复制移出事务、回收站异步删除补偿、解压/回调/文本覆盖改造」+ conventions.md 已知反例 `UploadServiceImpl.mergeChunks` — 证据：`UploadServiceImpl.java:506` `return mergeChunks(mergeRequest);` 内部直调；`relayFinalize:507-517` 失败先 `relayBufferManager.cleanup` + `abortMultipart`，而 `mergeChunks` 已按 `isReplaceUpload` 分支 abort + `handleMergeFailure` → 重复 abort；替换上传时 `handleMergeFailure` 已恢复旧版本，外层再 abort 属冗余。
- **[Major] 归档解压团队空间错扣个人配额（同请求内三种落库口径不一致）** — 依据：归档需求「复用上传配额口径」— 证据：`UploadCommitManager.java:334` 无条件写个人配额，而同文件 `commitTextOverwrite`（:220-222）、`commitEditorSave`（:263-265）均区分 `spaceId`。
- **[Major] `RelayBufferManager` 缺启动扫描** — 依据：`20260813-upload-rate-throttle/design.md` 风险表「临时文件泄漏 | 磁盘占用 | 超时清理 + 失败即删 + **启动扫描**」— 证据：仅有 `@Scheduled scheduledCleanup`（惰性 + 定时），无 `@PostConstruct` / `ApplicationRunner` 扫描 `config.getTempDirFile()` 残留 → 进程崩溃后 `.tmp` 永久残留。
- **[Minor] `relayChunk` 的 Content-Length 校验可被绕过** — 依据：`20260813-upload-rate-throttle/design.md`「relay-chunk 校验 Content-Length ≤ relayChunkSize」— 证据：`UploadServiceImpl.java:440-443` 用 `getRelayChunkSize(uploadId)`，会话缺失时返回 **0 表示「不限制」**（注释 :90 明示）；超时清理后客户端继续 POST，`getFirstChunk` 仍从 DB 成功 → 校验被绕过，直到 `appendChunk` 才抛 `CHUNK_NOT_FOUND`（先读流后拒绝）。
- **[Minor] 新建空 txt 的 `new byte[0]`** — 依据：`20260815-new-file/requirement.md` 验收「新建 txt 内容为空文本」— 证据：`NewFileServiceImpl.java:190-192` 返回 `new byte[0]`，md5 为 d41d8c…，多用户同租户共享同一 `file_object`，引用计数逻辑正确 → **属合理去重，非缺陷**（记录备查）。

## B. st-auth / st-admin / st-share / st-team

- **[Critical] 外部协作者「全局开关」只写不读，形同虚设** — `TeamServiceImpl.java:384-426,941-970` — 依据：`20260809-teamspace-p2/requirement.md`「管理员可全局开关『是否允许外部协作者』」+「外部协作者仅可见被邀请的空间」— 证据：`setExternalConfig` 仅写 `team_external_config.allow_external`，`joinByCode` / `inviteMember` / `setExternalMember` **全程未读取该配置**。
- **[Major] 外部协作者项需求未完成** — `TeamServiceImpl.java:941` — 依据：`20260809-teamspace-p2/requirement.md` 验收清单「- [ ] 管理员可将成员标记为外部协作者 / - [ ] 外部协作者可设有效期」未勾选 — 证据：`setExternalMember` 已实现标记与 `expireAt`，但 `toMemberVO:1046-1058` 未返回 `memberType` / `expireAt` → 需求「成员列表区分内部/外部，外部成员显示标签和有效期」缺失。
- **[Major] 过期外部成员清理不做权限缓存失效** — `ExternalMemberExpireTask.java:33-36` — 依据：`20260809-teamspace-p0` 权限模型「成员变更：权限缓存失效」+ P2「定时任务清理过期外部协作者」— 证据：`for (TeamMember member : expired) { teamMemberMapper.deleteById(member.getId()); }` 未调 `folderPermissionService.invalidateSpace` → 被清理成员在缓存 TTL（60s）内仍持有效权限。
- **[Major] 邀请链接无使用次数上限** — `TeamServiceImpl.java:336-351,384-408` — 依据：邀请链接应可控（`CreateInviteRequest` 含 `expireAt` 但**无 `maxUses`**）— 证据：`joinByCode` 校验后直接 `teamMemberMapper.insert`，`TeamInvite` 无次数字段 → 链接泄漏 = 无限拉人。
- **[Minor] `reportFileActivity` 允许客户端自报任意 action 字符串** — `TeamServiceImpl.java:507-512` — 依据：`20260809-teamspace-p0` 活动日志用于审计 — 证据：`action` 来自 `@RequestParam String action` 未做白名单，仅校验 `checkPermission(spaceId,1)`。

## C. st-sync / st-search / st-preview / st-common

- **[Critical] 冲突策略字段可配置但服务端无任何冲突判定/副本生成实现** — `SyncServiceImpl.java:275-286,74` — 依据：PRD Story 3.3「检测到冲突时，保留两份文件（本地版和云端版）」「系统通知提示用户冲突发生」；`DESIGN-st-sync-同步引擎.md` §5「冲突不丢数据」「原 node_id 指向云端版」— 证据：`root.setConflictStrategy(request.getConflictStrategy())` 后 `updateById` 即返回，**无策略消费方**；全模块无 `文件名 (冲突-yyyyMMddHHmmss).扩展名` 生成代码 → 客户端若据此字段认为已有服务端保底，会静默丢版本。
- **[Major] 变更日志双写通道未互斥，可产生重复行并使游标重叠** — `SyncChangeLogListener.java:33-59` vs `SyncChangeMessageConsumer.java:31-79` — 依据：DESIGN 3.3「变更日志 id 作为游标，单调不重」+ testing.md「st-sync — SyncChangeMessageConsumerTest — MQ 幂等消费」— 证据：本地 `@EventListener` 写日志时 `eventLogId` 为 null（未 `setEventLogId`），而 MQ 路径有 `eventLogId` 唯一键 → 本地路径因 `event_log_id=NULL` 不受唯一键约束，同事件两路均触发会插入重复行。
- **[Major] 块级同步落库路径绕过 SyncRoot/Delta 主链路** — `SyncBlockCommitManager.java:75,105` — 依据：DESIGN 3.1「服务端不实现同步算法，只提供 SyncRoot 注册 + Delta 查询」；DESIGN 4.4「覆盖已有文件：服务端复用节点、生成历史版本」— 证据：`node.setVersion(oldVersion + 1); fileNodeMapper.updateById(node)` 后仅 `publishUpdated(node)`，未校验 node 是否位于任意 SyncRoot 内；DESIGN 未定义 `/block-check`、`/block-upload`（scope creep），文档与实现已分叉。
- **[Major] 搜索深分页无上限，超 `max_result_window` 时静默返回空页** — `SearchServiceImpl.java:157,164,306` — 依据：PRD Story 5.2「支持组合条件筛选」「按相关度排序」（隐含结果集可控）— 证据：`int from = Math.max(0, (page - 1) * size); s.from(from).size(size)`；`from+size` 超 10000 时 ES 抛错被 catch 吞掉返回空（:310-312）→ 用户翻页到深处得到静默空结果而非提示。
- **[Minor] 预览 Office 类型返回 `unsupported` 未区分「能力未就绪」与「格式不支持」** — `PreviewServiceImpl.java:122-125` — 依据：PRD Story 4.3「Office 文档服务端转换为 PDF 后预览」标记 `[ ]` 未完成；Story 6.1 称「Office 走 OnlyOffice 只读打开」已实现 — 证据：`OFFICE_TYPES.contains(suffix)` 与兜底 `unsupported(suffix)` 走同一返回，客户端无法区分。
- **[Suggestion] `searchContent` 的 `ownerId` 为 null 时不限制** — `SearchServiceImpl.java:212-217` — 依据：PRD Story 5.1「快速找到需要的文件」（隐含仅本人可见范围）— 证据：注释「null 表示不限制」，越权面取决于 Controller 是否恒传非 null → 隐式契约未在签名上约束。

## D. st-web / st-desktop

- **[Critical] 设计要求的 `webSecurity: true` 与生产 `sandbox: true` 未实施** — `st-desktop/src/main.ts:63-64` — 依据：`20260823-frontend-desktop-review-fix/design.md` §3.2 D-1 — 证据：仍为 `webSecurity: false` / `sandbox: false`，注释标 "TODO(P1#5 revisit)" → 回滚而非完成。
- **[Critical] 同步下载失败被吞但游标仍推进，造成云端变更永久漏同步** — `st-desktop/src/sync-engine.ts:227` — 依据：同文件 :227 注释「游标仅在全部变更处理成功后才推进，保证断网恢复后不丢不重」— 证据：`sync/sync-download.ts:47-51` catch 内仅 `insertSyncHistory` + `emitSyncEvent` 后正常 return，`processCloudDelta`（`sync-engine.ts:496`）无返回值判定，随后 `upsertSyncConfig({cursor: since})` 无条件推进 → **与注释直接矛盾**。
- **[Major] 分享提取码仍从 URL query 读取** — `st-web/src/pages/ShareAccessPage.tsx:25,27` — 依据：`20260823-frontend-desktop-review-fix/design.md` §3.2 W-3「分享 `pwd` 进 URL/历史/日志；密码不再写地址栏，改状态/header 传递」— 证据：`const urlPwd = searchParams.get('pwd') || ''` → `useState(urlPwd)`，提取码仍可从浏览器历史/日志还原。
- **[Major] 桌面端原生上传丢失团队空间 `spaceId`** — `useUpload.tsx:253`、`ipc-handlers.ts:130`、`upload-manager.ts:106-115` — 依据：`20260822 评审 W3`「桌面端原生选择器上传团队空间文件时，可能被后端当作个人空间上传」— 证据：`addFilePaths(..., _spaceId?)` 参数被下划线标记忽略；`upload:start` 仅传 `filePath, parentId, replaceFileId`，init 请求体不含 `spaceId`。
- **[Major] 上传暂停/取消不中止在途请求，设计要求的 AbortController 未实现** — `st-desktop/src/upload-manager.ts:371-381` — 依据：`20260822 评审 D15`「上传 pause 只改变状态标志，正在进行的 presign、S3 PUT、relay POST 不会被 abort」— 证据：`pauseUpload` 仅 `state.paused = true`；全文件 `grep AbortController|signal` 零命中；`relay-chunk` 单请求 timeout 达 300000ms（:219）。
- **[Major] 下载/同步完成无内容 hash 校验** — `st-desktop/src/download-manager.ts:168` — 依据：`20260822 评审 D13`「下载只按字节数判断完成……可能得到长度正确但内容错误的文件」— 证据：`if (totalBytes >= task.fileSize)` 即 `finalizeDownloadFile` 并置 `completed`，未比对服务端 MD5/ETag。
- **[Minor] `legacyToPermissions(2)` 仅返回 `{view:true}`，与 DB 迁移 34 号脚本注释不符** — `lib/permissions.ts:41` — 依据：`20260823-frontend-desktop-review-fix/design.md` §3.1 W-6「修正为 `{view, upload}`」— 证据：`if (permission === 2) return { view: true };` 仍未修正；且 :39 `permission === 1` 分支反而授予 download/upload/delete 全量权限，**语义倒置**。
- **[Minor] 移动端迭代目录为空，Spec 无依据** — `.ai/docs/20260809-mobile-interaction/`、`20260810-mobile-ux-polish/` — 证据：`ls -la` 仅 `.` 与 `..` → 移动端规则只能以 frontend.md 反推。
- **[Suggestion] PRD §9 明确「移动端 App 为范围外」，但代码已引入 Capacitor 6 全套** — `docs/PRD-云盘系统-v2.0.md:924` — 证据：`lib/capacitor.ts`、`runtime.ts:isCapacitor()`、`MobileTabBar.tsx`、`useMobile.ts`；若为 PWA 响应式则合理，Capacitor 原生打包能力已超 PRD 承诺范围。

---

# 四、安全专项核查（22 项）

| 检查项 | 结论 | 证据 |
|---|---|---|
| 横向越权（同租户他人文件） | ✅ 已修复 | `UserContext.java:70-72` `canAccessTenant()` 硬编码 `return false`；`ShareServiceImpl.java:138` 双重校验 |
| 纵向越权（普通用户调管理接口） | ✅ 已修复 | `UserManageController.java:20` 类级 `@PreAuthorize`；`StatsServiceImpl.java:20` 服务层二次 `hasPermission` 兜底 |
| 异常堆栈泄漏 | ✅ 合规 | 全部 Controller 返回 `Result<T>`；无 `printStackTrace` |
| JWT 密钥来源 | ✅ 已修复 | `JwtUtils.java:69-76` `@PostConstruct` fail-fast 校验 master key ≥32 字节；:82 AES-GCM 解密 DB 密文 |
| JWT 算法伪造 | ✅ 已修复 | `JwtUtils.java:117` `signWith(signingKey)`，jjwt 0.12 不接受 `none`；无 HS/RS 混淆面 |
| JWT 过期 / 续期 | ⚠️ **存在风险** | `JwtUtils.java:59` access token 仍 7 天（未执行 IMPROVEMENT-PLAN P0-2「缩短至 2h」）；`AuthService.java:155-185` refresh token 无旋转失效，旧 token 在被覆盖前仍有效 → **可无限续期** |
| 密码 BCrypt / 强度 | ⚠️ **存在风险** | 两套实现并存：`AuthService.java:78` Hutool `BCrypt.hashpw` vs `UserManageServiceImpl.java:95,131` `passwordEncoder.encode`；注册仅校验长度 6-100（`RegisterRequest.java:15`），无复杂度要求 |
| 用户可枚举 | ⚠️ **存在风险** | `AuthService.java:53-58,113-119` 登录失败文案区分 `USER_NOT_FOUND` / `PASSWORD_INCORRECT` → 可批量探测有效用户名 |
| 分享提取码存储 | ⚠️ **存在风险** | `FileShare.java:19` 明文存储 + `ShareServiceImpl.java:634` 明文比对；`ShareController.java:87,98,109` 明文经 query 传递进入访问日志 |
| 分享防枚举 / 防爆破 | ✅ 已修复 | `ShareServiceImpl.java:74-79` 12 位 57 字符集 SecureRandom；`ShareBruteForceGuard.java:61-74` 单码 + IP 双维度计数与锁定；`validateShareAccess:624-639` 统一入口 |
| 防爆破守卫内存上限 | ✅ 已修复 | `ShareBruteForceGuard.java:21` TTL 2h 上限；`CacheFactory.create(TTL)` 有界 |
| 分享过期 / 下载次数服务端强制 | ✅ 已修复 | `ShareServiceImpl.java:618-620` 过期校验；:457-464 下载次数原子条件更新（`download_count+1` 带 `where` 条件）消除 TOCTOU；流式路径 :580-588 同样处理 |
| 团队邀请码枚举 | ✅ 不可枚举 | `TeamServiceImpl.java:1018-1027` 32 位 SecureRandom（160bit） |
| 团队邀请码复用 | ⚠️ **存在风险** | 无使用次数上限（见 Spec）→ 泄漏后无限重复使用；`joinByCode` 无频率限制 |
| 文件夹权限绕过 | ✅ 已修复 | `TeamServiceImpl.java:603-609` `requireFolderNodeInSpace` 校验节点属空间；`FolderPermissionService.matches:268` 校验 `rule.spaceId == spaceId`；`validatePermissionRules:619-641` 白名单 + 禁止 all 主体含 manage_* |
| 外部成员过期清理 | ⚠️ **存在风险** | `ExternalMemberExpireTask.java:24-36` 每小时清理已实现，但**不做权限缓存失效**，且 `allow_external` 开关未强制 |
| 审计覆盖 | ✅ 部分合规 | 32 处 `@Auditable`；`AuditAspect.java:63-67` 失败记 `status=0` + error；`SENSITIVE_PARAMS:53-55` 脱敏 |
| 审计日志防篡改 | ⚠️ **存在风险** | `AuditLogController` 只读，但日志表**无 append-only / 防篡改机制**，具备 DB 权限即可删改 |
| 限速服务端强制 | ✅ 已修复 | `SpeedLimitManageServiceImpl.java:102-109` 校验范围且不能全 0；规则落 `sys_rate_limit` + `speedLimitCache.evict()` 即时生效；`transfer:speed:limit` 权限码 |
| SQL 注入（`${}`） | ✅ 零命中 | 四模块 main 源码 grep `${` 无结果；`setSql("download_count = download_count + 1")` 为常量字面量 |
| 角色/权限变更并发 | ✅ 基本合规 | `RoleServiceImpl.java:143-153,163-176` 先物理删后插入，均在 `@Transactional` 内；删插间有短暂空权限窗口，但 JWT 内嵌权限需 refresh 才生效，实际影响低 |
| 限速计数器线程安全 | ⚠️ 非阻塞 | `ShareBruteForceGuard.incr:89-94` 为「读-改-写」非原子，多实例无 Redis 时存在计数丢失 |

---

# 五、Electron 安全配置核查

| 检查项 | 现状 | 风险 |
|---|---|---|
| `nodeIntegration` | `false`（`main.ts:60`，`mini-window.ts:181`） | 低，符合基线 |
| `contextIsolation` | `true`（`main.ts:59`，`mini-window.ts:180`） | 低 |
| `sandbox` | **`false`**（`main.ts:64`，`mini-window.ts:182`） | **高**：preload 与渲染进程共享 OS 权限，沙箱逃逸即直接触达 fs |
| `webSecurity` | **`false`**（`main.ts:63`） | **高**：同源策略关闭，任意 XSS 可读跨源响应；注释自述为绕过 CORS 的临时手段 |
| `shell.openExternal` | 限 `http/https`（`main.ts:83-89`），`setWindowOpenHandler` 默认 `deny` | 中：无域名白名单，任意 https 链接可唤起系统浏览器 |
| 路径穿越（`app://`） | ✅ 已修：`path.resolve` + `startsWith(webRoot + sep)`（`main.ts:234-237`） | 低，符合设计 D-3 |
| 路径穿越（同步） | ✅ 已修：`absPathFor()` 统一 containment 守卫（`sync-engine.ts:141-148`） | 低，符合设计 D-4 |
| 路径白名单 | 已生效但仅前缀/精确匹配（`path-allowlist.ts:39-42`）；`allowPath` 同时写 `allowedFiles` 与 `allowedDirs`；`norm` 仅 `path.resolve` 未解析 symlink | 中：`allowDownloadsDir()` 使下载目录整树可写；存在符号链接绕过面 |
| IPC 参数校验 | **缺失**：`transfer:setSettings`（`ipc-handlers.ts:91`）、`server:setUrl`（:81）、`mini:startDrag`（:178）无运行时校验 | 中：`transfer-settings.ts:86` 直接 `{...currentSettings, ...settings}`，`maxParallelTasks` / 限速值无范围钳制，可传负数或超大值 |
| IPC 来源校验 | ✅ `preload.ts:258-269` `isTrustedPage()` 已实现 | 低，符合设计 D-2 |
| `ws-client` 鉴权 | ✅ 已改 `new WebSocket(url, { headers: { Authorization } })`（`ws-client.ts:77`），不再用 `?token=`；指数退避 1s→60s | 低，符合设计 D-5 |
| `db-migrate` 版本门控 | **仅类型探测、无版本号**：`ensureIdColumnsText` 按 `PRAGMA table_info` type 判断（:47-59），重建走 `DROP TABLE` + `RENAME`（:62-68） | 中：不可逆；`database.ts:104-106,122-126` 大量 `try { ALTER TABLE } catch {}` 仅吞异常，无法区分「列已存在」与半迁移故障 |

---

# 六、any 类型专项核查

**结论：`frontend.md` W8 关于「`DuplicateFilesPage.tsx` / `TeamInvitePage.tsx` / `TeamSpacePage.tsx` 仍有 `any`」的描述已过期——三个文件均已无 `any`，全仓（排除 `components/ui/`）`any` 命中数为 0。**

替代写法已改为结构化逃逸：

| 文件 | 行 | 写法 |
|---|---|---|
| `TeamInvitePage.tsx` | 20 | `(e as { status?: number } \| null)?.status === 401` |
| `ShareAccessPage.tsx` | 93 | `(e as { code?: number }).code` |
| `lib/fileSource.ts` | 57, 92 | `as unknown as Promise<Blob>` |

建议 frontend.md 的 W8 待改进项同步更新或删除。

---

# 七、迁移脚本核查

| 脚本 | 编号递增 | 首行 `SET NAMES` | 幂等 | 问题 |
|---|---|---|---|---|
| 02~08、10~25、27~39 | ✅ | ✅（39 份首行全为 `SET NAMES utf8mb4;`） | ✅ CREATE 用 `IF NOT EXISTS`，ALTER 用 `information_schema` + `PREPARE` 守卫 | 无 |
| `09_jwt_secret.sql` / `09b_remove_two_factor.sql` | ⚠️ 前缀 `09` 复用 2 次 | ✅ | ✅ | 违反「编号不可复用」，以字母后缀规避 |
| `26_sync_change_log.sql` | ✅ | ✅ | ❌ `:27 ALTER TABLE ADD COLUMN` 无守卫 | 重复执行报错；30 号已修复同源问题但未回填 26 号 |
| `31_schema_version.sql` | ✅ | ✅ | ⚠️ 3 条固定 `INSERT` | `uk_version_tag` 使重复执行报错；作为一次性 baseline 可接受 |
| 数据清理类（`28:44`、`33/34/35/37` 的 UPDATE） | ✅ | ✅ | ✅ WHERE 条件自收敛 | 无 |

**整体健康度良好**：38 份脚本均满足「首行 `SET NAMES utf8mb4;`」，仅 2 处编号/幂等问题。

---

# 八、问题清单（按严重度汇总）

## Critical（10 条，必须修复）

| 编号 | 分区 | 问题 | 位置 | 轴 |
|------|------|------|------|-----|
| C1 | core | 引用归零判定与 S3 删除之间的「墓碑复活」窗口 → 悬空引用（下载 500） | `FileObjectServiceImpl.java:93-100` | Standards |
| C2 | core | 永久删除后同 md5 重传，并发下指向已物理删除对象（点名验证项，仍存在） | `FileObjectMapper.java:44-52,58-67` | Spec |
| C3 | core | `relayFinalize` 失败时对同一 uploadId 重复 abort，双重状态写入 | `UploadServiceImpl.java:506-517` | Spec |
| C4 | sync | 块级同步无 multipart abort/清理路径 → S3 垃圾分片永久泄漏 | `SyncBlockServiceImpl.java:56,72,108` | Standards |
| C5 | sync | `blockUpload` 全量信任客户端 `storagePath`/`fileMd5`/`fileSize` → 任意 key 写入 | `SyncBlockServiceImpl.java:132-178`、`SyncBlockCommitManager.java:67` | Standards |
| C6 | sync | 冲突策略「保留两份、不丢数据」服务端**完全未实现**，`conflict_strategy` 是死字段 | `SyncServiceImpl.java:275-286` | Spec |
| C7 | team | 外部协作者全局开关 `allow_external` 只写不读，形同虚设 | `TeamServiceImpl.java:384-426,941-970` | Spec |
| C8 | web/desktop | `webSecurity: false` + `sandbox: false` 双开（设计 P0 项未落地） | `st-desktop/src/main.ts:63-64` | 两轴 |
| C9 | web/desktop | 渲染层拼接 Windows 路径直送主进程写盘，云端文件名未校验 | `useFileDownload.ts:39` | Standards |
| C10 | desktop | 同步下载失败被吞但游标仍推进 → 云端变更永久漏同步 | `sync-engine.ts:227`、`sync/sync-download.ts:47-51` | Spec |

## Major（30 条，应在下一迭代闭环）

**st-core（6）**：归档解压无容量层校验且团队错扣个人配额；`finalizeMerge` 对象归属判断脆弱；`restoreVersion` 读判写非原子；长事务无 `timeout` + 回收站单事务上千次往返；客户端 `s3UploadId` 未持久化校验；`RelayBufferManager` 缺启动扫描。

**权限安全（7）**：提取码明文存储/比对（与 conventions 冲突，规范漂移）；`AuditAspect` 30+ 分支巨型 switch；三模块服务层 `@Resource` 字段注入；权限缓存返回可变共享实例；外部协作者项需求未完成（VO 缺 `memberType`/`expireAt`）；过期外部成员清理不失效权限缓存；邀请链接无使用次数上限。

**sync/search（7）**：`blockCheck` 新路径未 `tmp/` 隔离 + blocks 无校验；`delta` 的 `page` 死参数；`SyncBlockServiceImpl` 零集成测试（违反 testing.md）；缓存 key 缺租户维度；`TtlCache` 无容量上限；`SpeedLimitCache` 无上限 + 全局锁；变更日志双写通道未互斥。

**web/desktop（7）**：`useFileKeyboard` 未清理 `setTimeout`；`useLongPress` 死代码；上传分片进度无节流；服务端数据双份真相；`api.ts` 401 硬跳转绕过 Router；分享 `pwd` 仍走 URL query；桌面端原生上传丢失 `spaceId`；上传暂停不中止在途请求；下载无 hash 校验。

> 注：web/desktop 实际 Major 为 7 条（上表已按模块归并，逐条明细见第三、四章）

## Minor（19 条）/ Suggestion（5 条）

详见第二、三章各模块小节。

---

# 九、Review 结论

```
总体评分：B-（架构方向正确，安全基线较前几轮显著收敛；核心链路的并发正确性与「设计已定但未落地」的存量缺口仍是主要扣分项）

是否建议合并：本次为存量全量审查，不涉及增量合并判定。
              结论为「不通过（需修复后复检）」——建议先闭环 10 条 Critical。

必须修改项（Critical，10 条）：
  C1  file_object 引用归零与 S3 删除的原子性（墓碑复活窗口）
  C2  永久删除后同 md5 重传的并发正确性
  C3  relayFinalize 重复 abort 与双重状态写入
  C4  st-sync 补 multipart abort/清理路径
  C5  blockUpload 校验 storagePath 归属与 blockCheck 签发一致性
  C6  冲突策略服务端落地（保留两份 + 通知），或明确降级并同步文档
  C7  allow_external 开关在加入链路强制校验
  C8  Electron 恢复 webSecurity:true + 生产 sandbox:true
  C9  下载保存路径改为主进程生成，渲染层不拼路径
  C10 同步下载失败不得推进游标

必须同步的文档（规范漂移，代码与文档已分叉）：
  1. conventions.md「分享提取码使用 BCrypt」→ 与 ShareServiceImpl 明文实现冲突，二选一修正
  2. frontend.md W8「三个页面仍有 any」→ 已全部消除，条目应删除
  3. frontend.md / types/index.ts 的 isPinned TODO → 代码中已无，条目应更新
  4. DESIGN-st-sync-同步引擎.md → 实现已远超文档（块级同步未在设计中），应修订文档
  5. .ai/docs/20260809-mobile-interaction/、20260810-mobile-ux-polish/ → 空目录，补文档或删除

优化建议（下一迭代）：
  1. 配额预检 5 处复制 → 抽一个 QuotaGuard 统一入口（含 spaceId 分支）
  2. 半成品对象统一 tmp/ 前缀，落地 conventions 第 5 条
  3. 缓存 key 加 tenantId 段；TtlCache / SpeedLimitCache 补容量上限与 LRU
  4. AuditAspect 巨型 switch → 策略表/Map 注册，删除字符串耦合
  5. 服务层统一 @RequiredArgsConstructor 构造器注入，清掉 @Resource / @Autowired 字段注入
  6. st-sync 补 SyncBlockServiceImpl 集成测试（testing.md 硬性要求）
  7. TeamServiceImpl（1089 行 / 7 类职责）拆分
  8. JWT access token 有效期收敛 + refresh token 旋转失效
```

---

## 已确认修复项（对比 20260813 那轮审查）

为免误伤，以下上一轮的 blocker 经本轮核查**已确认修复**：

| 上轮 blocker | 本轮结论 | 证据 |
|---|---|---|
| M1 `mergeChunks` 无权限校验 | ✅ 已修 | `UploadServiceImpl` 增加 `validateUploadOwnership` |
| M2 S3 完成先于配额扣减 | ✅ 已修 | `simpleUpload` 在事务内完成配额扣减 |
| M3 `relayFinalize` 自调用绕过事务代理 | ✅ 已修（但引入 C3 重复 abort） | 事务由 `UploadCommitManager.finalizeMerge` 承接 |
| M4 28 号迁移脚本不可重复执行 | ✅ 已修 | `28_file_object.sql` 已加条件守卫 |
| M6 云盘容量行锁持有跨 S3 I/O | ✅ 已修 | S3 调用已移出事务 |
| M7 块级同步 multipart 无 abort | ❌ **仍存在（本轮 C4）** | st-sync 全模块无 `abortMultipartUpload` 调用 |
| M8 `SyncBlockServiceImpl` 无测试覆盖 | ❌ **仍存在** | 仅 Mockito 单测，无集成测试 |
| 回收站只读查询误加 `@Transactional` | ✅ 已修 | `listRecycleBin` / `findExpiredRecycleRoots` 均无注解 |
| `simpleUpload`/`mergeChunks`/`handleCallback`/`overwriteContent`/`extractArchive` 的 S3 移出事务 | ✅ 基本落地 | 见各文件；`extractArchive:157` 循环内同步 S3 上传仍长占 HTTP 线程 |

> 注：`20260813-project-code-review.yaml` 已于 2026-09-10 因停滞 28 天被回填为 `abandoned`，其 9 个 blocker 中 7 项已闭环，2 项（M7/M8）遗留至本轮。

---

> 本轮审查为**只读**：未修改任何业务代码，未运行构建。
> 下一动作建议：将 10 条 Critical 转为 TASK 并进入修复迭代，修复后按本报告复检。
