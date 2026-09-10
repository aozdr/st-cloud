# Standards 审查记录：全库 Standards 审查（2026-08-14）

> 输出标准：docs/newList/ai-code-review-standard.md（Standards 轴）
> 审查模式：只读审查，未修改任何业务代码 / 数据库脚本 / `.ai/` 其它文件
> 任务来源：TASK-REVIEW-STANDARDS-001（dispatchId: project-review-standards-001）
> 审查人：reviewer（taskType=review）

## 一、审查概览

### 审查范围与规模（排除 node_modules / dist / target）

| 模块 | 源码文件数 | 说明 |
|------|-----------|------|
| st-common | 36 | 基座：BaseEntity / Result / ResultCode / JWT / 限速 |
| st-core | 91 | 文件管理核心（含 upload 子包 / outbox） |
| st-sync | 35 | 同步 / 块级同步 |
| st-search | 13 | ES 全文搜索 |
| st-team | 50 | 团队空间 / 成员 / 评论 / 权限 / 角色 |
| st-share | 10 | 分享 / 提取码 |
| st-auth | 21 | 认证 / 用户 / 角色 / 租户 |
| st-admin | 34 | 管理后台 / 审计 / 限速 / 容量 |
| st-api | 4 | 启动聚合与配置 |
| st-preview | 4 | 预览（缩略图 / 转码） |
| st-web/src | 113 | React Web 端 |
| st-desktop/src | 18 | Electron 主进程 |
| docker/mysql/init | 32 个 SQL | 迁移脚本 02~32 |

### 标准来源清单（逐条对照）

- `.ai/knowledge/conventions.md`：编码规范 / 命名约定 / 配置管理 / 数据库迁移幂等 / 文件编码 UTF-8 无 BOM / 依赖注入约定
- `.ai/knowledge/architecture.md`：分层架构 / 统一响应 Result<T> / 错误码分段 / 跨模块通信
- `.ai/knowledge/frontend.md`：函数组件 + Hooks / Zustand / Axios 统一封装 / 命名约定
- `.ai/knowledge/testing.md`：单元测试（*Test）与集成测试（*IntegrationTest）分层、主路径覆盖要求
- `AGENTS.md`：工程约束 7 条（TASK 文件 / 修改范围 / 测试验证 / 迁移方案 / API 兼容）
- 已知问题基线：`.ai/docs/20260813-project-code-review/codereview.md`（2026-08-13 WIP 两轴 review）

### Fowler 坏味道基线

按 TASK 指定的 12 项基线扫描：Mysterious Name / Duplicated Code / Feature Envy / Data Clumps / Primitive Obsession / Repeated Switches / Shotgun Surgery / Divergent Change / Speculative Generality / Message Chains / Middle Man / Refused Bequest。仓库文档已覆盖处按仓库标准优先，未覆盖处按 Fowler 基线判断（判断项，非硬性违规）。

---

## 二、硬性违规（违反文档化标准）

### H1. 敏感信息硬编码：S3 对象存储凭证写入主配置（新发现）

- 标准出处：`conventions.md`「配置规范」——"敏感信息（密钥、密码）使用环境变量覆盖，不硬编码"；"生产环境必须通过环境变量覆盖：JWT 密钥、数据库密码、对象存储凭证、CORS 来源"
- 代码位置：`st-api/src/main/resources/application.yml:53-54`
  ```yaml
  storage:
    endpoint: http://127.0.0.1:9000
    access-key: stcloud
    secret-key: stcloud123
  ```
- 分析：`application.yml` 为跨环境主配置（含生产）。同文件中 JWT `master-key` 与 CORS `allowed-origins` 均使用 `${STCLOUD_MASTER_KEY:}` / `${STCLOUD_CORS_ORIGINS:}` 占位符，唯独 S3 凭证为明文且无环境变量覆盖通道，生产无法通过环境变量替换，违反"生产环境必须通过环境变量覆盖：对象存储凭证"的强制要求。
- 建议：改为 `${STCLOUD_S3_ACCESS_KEY:}` / `${STCLOUD_S3_SECRET_KEY:}`，开发默认值下沉到 `application-dev.yml`；若 S3 兼容本地开发为约定值，需在文档中明确生产覆盖义务。

### H2. 迁移脚本 DDL 幂等守卫缺失（6 个脚本新发现 + 1 个已知）

- 标准出处：`conventions.md`「数据库迁移」——"所有 DDL 使用 IF NOT EXISTS / IF EXISTS 保证幂等"；`AGENTS.md`「数据库版本管理」——每次迭代脚本需可重复执行。
- 代码位置（均为无 information_schema 守卫的裸 `ALTER TABLE ... ADD COLUMN`）：
  - `docker/mysql/init/07_cloud_capacity.sql:6`（sys_tenant.cloud_total_capacity）
  - `docker/mysql/init/08_chunk_original_size.sql:4`（file_chunk.original_size）
  - `docker/mysql/init/16_add_file_hidden.sql:5`（file_node.hidden）
  - `docker/mysql/init/22_team_member_pinned.sql:4`（team_member.is_pinned）
  - `docker/mysql/init/23_file_lock.sql:4-6`（locked_by / locked_at / lock_expire_at，3 列）
  - `docker/mysql/init/25_team_external.sql:4-5`（member_type / expire_at，2 列）
  - `docker/mysql/init/28_file_object.sql:22`（file_node.object_id）——**已知/待整改**（20260813 问题 m4）
- 对照范式：`docker/mysql/init/30_sync_change_log_event_log_id.sql:9-31` 已采用 information_schema 存在性守卫 + PREPARE 动态 SQL，重复执行安全。7/8/16/22/23/25/28 号脚本第二次执行会报 duplicate column。
- 建议：按 30 号脚本模式统一改造（或合并为幂等脚本），并纳入 compare-schema 门禁复核。

### H3. 迁移脚本编号重复（新发现）

- 标准出处：`conventions.md`「数据库迁移」——"编号严格递增，不可复用已存在的编号"。
- 代码位置：`docker/mysql/init/09_jwt_secret.sql` 与 `docker/mysql/init/09_remove_two_factor.sql` 均使用编号 09；且序号 01、03 缺失（历史基线）。
- 分析：同号脚本在 `schema_version.applied_sql_files` 记录与人工核对时易混淆；`09_remove_two_factor.sql` 自身幂等实现（存储过程 + 列存在性守卫）是正确的，但编号冲突违反命名规则。
- 建议：后续脚本编号严格递增、不复用；对历史 09 双脚本在 schema_version 记录中显式注明；若允许，可将 `09_remove_two_factor.sql` 重编号为独立序号并记录迁移。

### H4. 文件编码违反 UTF-8 无 BOM（新发现，73 个文件）

- 标准出处：`conventions.md`「文件编码规范」——"所有文本文件统一使用 UTF-8（无 BOM）"。
- 代码位置：
  - `st-web/src` 共 **70 个文件**带 UTF-8 BOM（60 个 .tsx + 10 个 .ts），覆盖 `components/`、`hooks/`、`lib/`、`pages/`、`store/`（抽样验证 `st-web/src/components/file/FileBrowser.tsx` 首字节 EF BB BF）
  - `docker/mysql/init/08_chunk_original_size.sql`、`16_add_file_hidden.sql`、`17_team_invite.sql` 带 BOM（3 个 SQL）
- 分析：.java / .md / .yml / .json / .xml 未检出 BOM，问题集中在 st-web 与少量 SQL。BOM 可能导致 Windows 工具链拼接/脚本解析异常，且违反统一编码约定（.ps1 带非 ASCII 用 BOM 的例外不适用于 .tsx/.ts/.sql）。
- 建议：批量去 BOM 转码（PowerShell 读 UTF8 写 UTF8NoBOM），提交前用脚本校验全库。

### H5. `@Autowired(required=false)` 字段注入（已知/待整改）

- 标准出处：`conventions.md`「依赖注入」——"使用 @RequiredArgsConstructor + final 字段（构造器注入）；不使用 @Autowired 字段注入"。
- 代码位置：
  - `st-core/src/main/java/com/stcloud/core/service/impl/FileServiceImpl.java:64`（cacheFactory）
  - `st-team/src/main/java/com/stcloud/team/service/FolderPermissionService.java:38`（cacheFactory）
- 状态：**已知/待整改**（20260813 问题 m2，至今未修复）。
- 建议：改用 `ObjectProvider<CacheFactory>` 构造器注入（与 CacheFactory 自身注入方式一致）。

### H6. 魔法数字代替既有枚举（1 项已知 + 新发现多处）

- 标准出处：20260813 review 先例将"硬编码魔法数字代替枚举（UploadStatus 枚举已定义）"列为硬性违规；`conventions.md` 命名约定要求枚举"含 code + desc"。NodeStatus / NodeType 枚举均已存在（`st-common/.../enums/`）。
- 代码位置：
  - `st-sync/.../SyncBlockServiceImpl.java:201`：`node.setUploadStatus(2)` ——**已知/待整改**（20260813 问题 m5）
  - `st-preview/.../PreviewServiceImpl.java:199`（`getStatus() != 0`）、`:203`（`getNodeType() == 0`）
  - `st-share/.../ShareServiceImpl.java:61,186,226,252`（`getStatus() != 0`）、`:216,252`（`getNodeType() != 0/1`）
  - 测试代码：`st-core/.../AbstractIntegrationTest.java:74`、`EventOutboxIntegrationTest.java:120`（`setUploadStatus(2)`）
- 建议：上述位置改用 `NodeStatus.NORMAL`、`NodeType.FOLDER/FILE`、`UploadStatus.COMPLETED.getCode()`；测试基类同步替换。

### H7. 测试分层规范违反：4 个模块无任何测试 + st-team 无集成测试（新发现）

- 标准出处：`testing.md`——"Service 方法涉及 Mapper 调用的，至少有一个集成测试覆盖主路径"；"涉及租户隔离的查询，必须通过集成测试验证 TenantLineInnerInterceptor 生效"。
- 代码位置：
  - `st-share`：无 `src/test`，`ShareServiceImpl`（318 行，多 Mapper 调用）零测试
  - `st-auth`：无 `src/test`，`AuthService`（265 行，用户/租户状态校验）零测试
  - `st-admin`：无 `src/test`，`UserManageServiceImpl` / `RoleServiceImpl` / `SpeedLimitManageServiceImpl` 零测试
  - `st-preview`：无 `src/test`，`PreviewServiceImpl` 零测试
  - `st-team`：仅 2 个单元测试（`FolderPermissionServiceTest` / `FolderPermissionServiceRuleTest`），无任何集成测试；`TeamServiceImpl`（856 行、40+ 公开方法、大量 Mapper 调用）主路径零集成覆盖
  - `st-sync`：仅 `SyncChangeMessageConsumerTest`，`SyncBlockServiceImpl` 主路径无测试 ——**已知/待整改**（20260813 问题 M8）
- 对照范式：`st-core` 13 个测试（10 个集成测试，含 SchemaConsistencyTest 三层校验）；`st-search` 有 1 个集成测试。
- 建议：按 testing.md 范式为 st-share / st-auth / st-admin / st-preview 补最小集成测试（AbstractIntegrationTest + 主路径用例），st-team 补 TeamServiceImpl 集成测试（含租户隔离）。

---

## 三、坏味道（Fowler 基线判断项）

### W1. Duplicated Code（已知 s1，仍存在）

- `st-common/.../ratelimit/UserTransferLimiter.java:159`（UploadPaceBucket）与 `:191`（DownloadBucket）：`tokens / lastRefillNs / acquire(...)` 逐行重复，仅注释不同。
- `st-core/.../service/impl/FileServiceImpl.java`：个人/团队两套方法组重复——`createFolder`（:101/:625）、`copy`（:237/:866）、`setRefCount(0/1)`（:104/:629/:240/:870）等成对出现；20260813 已指出"本次 diff 对两套同时做了相同修改"的 Divergent Change 风险。
- 建议：抽取公共令牌桶实现与公共文件操作编排（如 FileOperationService），消除双份维护。

### W2. Long Method / God Class

- `st-admin/.../aspect/AuditAspect.java:232-493`：`buildDetail` 单方法约 260 行，内含 30+ 个 action 字符串 case（switch），反射 + 字符串分支叠加，属 Repeated Switches + Long Method 复合坏味道。同文件 `extractTargetInfo`（:135）、`reflectTarget`（:179）亦偏长。
- `st-team/.../service/impl/TeamServiceImpl.java`（856 行、40+ 公开方法）：成员 / 邀请 / 评论 / 权限 / 角色 / 外部协作 / 统计全部收敛在一个类，Divergent Change 风险。
- `st-core/.../service/impl/FileServiceImpl.java`（967 行）与 `UploadServiceImpl.java`（547 行）：体量偏大（后者部分源于已知 M3 的 merge 复用链）。
- 建议：AuditAspect 按 action 拆策略处理器（Map<action, Handler>）；TeamServiceImpl 按域拆分（MemberService / InviteService / CommentService / RoleService / PermissionService）。

### W3. Primitive Obsession：无枚举状态用裸数字

- `st-share/.../ShareServiceImpl.java:83,111,296`：share 状态 `setStatus(1/0)` / `getStatus() == 0` 裸数字
- `st-team/.../TeamServiceImpl.java:85,336,363,376,385,778`：space / invite / role 状态裸数字
- `st-auth/.../AuthService.java:66,79,117,122,163`：user / tenant 状态裸数字
- `st-sync/.../SyncServiceImpl.java:71,118`：sync root 启用状态 `0/1` 切换
- `st-admin/.../UserManageServiceImpl.java:84,135`：用户状态裸数字
- `st-core/...`：`UploadChunkManager.java:30`（chunk status）、`FileObjectServiceImpl.java:45`（object status）、`ReliableEventPublisher.java:83`（outbox status）、`EventRelay.java:35`（outbox status == 1）
- `setRefCount(1)` / `setRefCount(0)`：`UploadServiceImpl.java:112,179,256`、`ArchiveServiceImpl.java:177,199`、`FileServiceImpl.java:104,240,629,870`
- `st-core/.../controller/FileController.java:237`：`response.setStatus(500)` 硬编码 HTTP 状态（建议 `HttpStatus.INTERNAL_SERVER_ERROR`）
- 建议：为 ShareStatus / TeamSpaceStatus / InviteStatus / SyncRootStatus / EventOutboxStatus / FileChunkStatus / FileObjectStatus 建立枚举；引用计数语义常量化。

### W4. Mysterious Name / 注释漂移（已知 m3 部分未修复）

- `st-core/.../entity/FileNode.java:27`：`uploadStatus` 注释仍为「0-待上传 1-上传中 2-已完成 3-失败」，未同步新增的 4-合并中 / 5-已删除（UploadStatus 枚举注释已修正，字段注释未跟）。
- `st-core/.../entity/FileNode.java:39`：`lockExpireAt  // 0-正常 1-隐藏` 注释明显是从 hidden 字段复制残留，与字段语义无关。
- `st-core/.../enums/UploadStatus.java:37`：`isTerminal()` 注释称「失败不可再流转」，但 `st-core/.../mapper/FileNodeMapper.java:121` `claimMerging` 为 `upload_status IN (1, 3)`，允许 FAILED(3)→MERGING(4)，注释与实现语义矛盾 ——**已知/待整改**（20260813 问题 m3，仍未修复）。

### W5. 前端命名与文档约定漂移

- `frontend.md` 命名约定「组件文件大驼峰 .tsx」，但 `st-web/src/components/ui/` 下 `badge.tsx / button.tsx / calendar.tsx / card.tsx / input.tsx / popover.tsx / select.tsx / switch.tsx / table.tsx / time-wheel-picker.tsx` 为小驼峰/连字符命名（shadcn 风格，但与文档约定不符）。
- `st-web/src/components/ErrorBoundary.tsx:12`：类组件（`extends Component`）。React 错误边界必须用类组件实现，属合理例外，但建议在 frontend.md 中显式豁免说明。
- `st-web/src/hooks/useUpload.tsx`：Hook 用 .tsx 扩展名（其余 Hook 均为 .ts），建议统一或文档说明。
- 文档漂移：`frontend.md` 称收藏 store 位于 `src/lib/store/favorites.ts`，实际为 `src/store/favorites.ts`；`frontend.md` store 表未收录 favorites。

### W6. 文档标准与实现漂移：XML Mapper

- `conventions.md` / `architecture.md` 约定「复杂查询使用 XML Mapper（classpath*:mapper/**/*.xml）」，但全库无任何 XML Mapper 文件，45 个自定义 SQL 全部为注解式 `@Select/@Update`；`application.yml` 的 `mapper-locations: classpath*:mapper/**/*.xml` 指向不存在的目录。
- 判断：注解式 SQL 本身合理（含 `<script>` 动态 SQL），属文档标准与实现脱节。建议二选一：更新知识库文档为"注解式为主"，或按文档落 XML。

### W7. SQL 层魔法数字（判断项）

- 大量 Mapper 注解 SQL 直接写 `node_type = 1 / upload_status = 2 / status = 0/1/2 / deleted = 0/1`：`FileNodeMapper.java:19,25,40,47,83,96,107,121`、`FileObjectMapper.java:21,34,40,52`、`EventLogMapper.java:21,25,29,39-40`、`FileChunkMapper.java:32`、`CloudCapacityMapper.java:17-48`、`StatsMapper.java:12-39`、`FileObjectMapper.java` 等。
- 分析：SQL 字面量无法直接引用 Java 枚举，属可接受折中；但无注释文档化时与 Java 层魔法数字形成对照混乱。建议在关键 SQL 旁补充状态含义注释，或在迁移脚本中保持与枚举注释一致。

### W8. 其它小项

- 前端 `any` 类型：`st-web/src/pages/DuplicateFilesPage.tsx:43`、`TeamInvitePage.tsx:21`、`TeamSpacePage.tsx:164`（TS 严格性建议，非文档硬标准）。
- TODO 残留：`st-web/src/types/index.ts:251,313`（isPinned 需后端加入 VO，属待办标记）。
- Controller 非 Result 返回：`FileController.java:219`（streamFile）、`:227`（downloadAsZip）为 void 直写响应——流式下载场景合理例外，建议在文档中显式豁免。

---

## 四、通过项（符合标准的结构与做法）

1. **实体层**：全部实体继承 `BaseEntity`（`@TableId(ASSIGN_ID)`、`@TableLogic`、`@TableField(fill=...)` 自动填充），`@Version` 乐观锁（FileNode）使用正确；未发现未继承 BaseEntity 的实体。
2. **分层与包结构**：各模块 controller / service / impl / mapper / entity / dto / enums / config / event / listener / task / aspect 目录符合 `architecture.md`；DTO Request/VO 后缀命名全部合规。
3. **统一响应**：Controller 以 `Result<T>` 为主（FileController 28 个 Result 方法），业务异常走 `BusinessException + GlobalExceptionHandler`；下载流直写响应为合理例外。
4. **API 路径**：全部 `@RequestMapping("/api/...")` 小写连字符前缀，符合命名约定。
5. **依赖注入主体**：绝大多数 Service 使用 `@RequiredArgsConstructor + final` 构造器注入；仅 2 处 `@Autowired(required=false)` 例外（见 H5）。
6. **核心逻辑中文注释**：状态机（UploadStatus）、去重/引用计数（FileObject / refCount）、配额（CloudCapacity）、权限校验、秒传、回收站等关键路径均带中文注释，符合 AGENTS.md 专项规则。
7. **审计**：敏感操作（分享创建/取消、同步根增删、回收站清空、版本恢复等）统一 `@Auditable`，`AuditAspect` 收敛实现。
8. **前端架构**：函数组件 + Hooks 为主体；Zustand store 5 个（auth / favorites / folderFilter / storage / transfer / theme）均用 `create(...)`；路由 19 处 `React.lazy` 懒加载 + Suspense；`src/lib/api.ts` 统一 Axios 实例 + Bearer 注入 + 刷新重试。
9. **数据库**：全部 `CREATE TABLE` 使用 `IF NOT EXISTS`；`30_sync_change_log_event_log_id.sql` 的 information_schema 守卫为幂等范式；`schema_version` 记录 + `compare-schema.ps1` 门禁 + `SchemaConsistencyTest` 三层校验齐备。
10. **测试范式**：st-core 集成测试 10 个（RelayUpload 14 用例、UploadStateMachine、EventOutbox、FileObject、QuotaConcurrency、FileServiceFlow/Permission 等）覆盖主路径，符合 testing.md 范式；20260813 新增测试质量良好。
11. **日志与调试残留**：主代码未发现 `System.out.println` / `printStackTrace` 残留（日志走 slf4j/log 体系）。
12. **安全基础**：JWT 主密钥仅环境变量 `STCLOUD_MASTER_KEY`，不入库不入源码；CORS 生产留空即拒绝；BCrypt 密码存储约定落地（详见安全审查章节，本次仅标准轴）。

---

## 五、优化建议（按优先级，可执行）

### P0（规范门禁，建议本迭代内整改）

1. **S3 凭证环境变量化**：`application.yml:53-54` 改 `${STCLOUD_S3_ACCESS_KEY:}` / `${STCLOUD_S3_SECRET_KEY:}`，开发默认值下沉 `application-dev.yml`（对应 H1）。
2. **迁移脚本幂等统一改造**：07 / 08 / 16 / 22 / 23 / 25 / 28 号 ADD COLUMN 全部套 information_schema 守卫（复刻 30 号脚本），随后 `compare-schema.ps1` 复核（对应 H2）。
3. **迁移编号唯一化**：修复 09 重复编号，schema_version 记录显式注明历史双 09 脚本（对应 H3）。
4. **全库去 BOM**：70 个 st-web 文件 + 3 个 SQL 批量转 UTF-8 无 BOM，并加入提交前校验脚本（对应 H4）。
5. **补测试门禁缺口**：st-share / st-auth / st-admin / st-preview 建最小集成测试（AbstractIntegrationTest 范式 + 主路径用例）；st-team 补 TeamServiceImpl 集成测试（含租户隔离）；修复 M8（SyncBlockServiceImpl）后统一 `mvn test`（对应 H7）。

### P1（代码质量）

6. **魔法数字收敛**：先替换 NodeStatus / NodeType / UploadStatus 三处既有枚举（H6 + W3 中涉及文件），再为 share / team / auth / sync / outbox / chunk / object 状态建枚举。
7. **重复代码抽取**：UserTransferLimiter 双桶合并为单一令牌桶；FileServiceImpl 个人/团队方法组抽取公共编排（W1）。
8. **AuditAspect 拆分**：buildDetail 的 action 字符串 switch 改为策略处理器映射，方法压到 50 行内（W2）。
9. **TeamServiceImpl 按域拆分**：成员 / 邀请 / 评论 / 权限 / 角色 / 外部协作 / 统计分文件（W2）。
10. **字段注入修复**：FileServiceImpl / FolderPermissionService 的 cacheFactory 改 ObjectProvider 构造器注入（H5，已知 m2）。

### P2（一致性 / 文档）

11. **状态机语义统一**：明确 FAILED 是否可重试合并，统一 UploadStatus.isTerminal 注释与 claimMerging SQL（W4，已知 m3）。
12. **知识库对齐**：frontend.md store 路径与 favorites 收录、ui 组件命名约定（或豁免说明）、XML Mapper 实际为注解式（W5 / W6）。
13. **前端类型收敛**：替换 DuplicateFilesPage / TeamInvitePage / TeamSpacePage 的 `any`；清理 types/index.ts TODO（W8）。
14. **命名/注释清理**：FileNode.uploadStatus 注释同步 4/5 状态；修正 lockExpireAt 误挂注释（W4）。

---

## 六、审查结论

- Standards 轴总体评分：**不通过**。新增 6 类硬性违规（H1~H4、H6、H7，其中 H5 为已知未修复），最严重为**生产配置硬编码 S3 凭证（H1）**、**7 个迁移脚本幂等缺口（H2）** 与 **4 个模块零测试（H7）**。
- 20260813 已知问题核验：m2（字段注入）、m3（状态机注释矛盾）、m4（28 号脚本幂等）、m5（uploadStatus=2 魔法数字）、M8（SyncBlockServiceImpl 无测试）均**仍存在，待整改**；20260813 其余代码层面缺陷（C1/M1/M2/M3/M5/M6/M7）属 Spec/安全轴，本报告不重复展开。
- 通过项：分层结构、实体规范、统一响应、API 命名、审计、前端技术栈、DDL CREATE 幂等、st-core 测试范式等符合文档化标准。

> 注：本审查为只读 Standards 轴审查，未修改任何业务代码；修复动作需由 Workflow Manager 另行派发 TASK 并按 AGENTS.md 工程约束执行。
