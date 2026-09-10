# 代码 Review 记录：全量并行 Code Review（2026-08-14）

> 输出标准：`docs/newList/ai-code-review-standard.md`
> 审查方式：两轴并行（Standards + Spec），由两个 reviewer 子代理并行审查后主线程汇总
> 审查范围：全量代码库（约 422 个源码文件 / 11 个模块 + docker/mysql/init 32 个 SQL）
> 已知基线：2026-08-13 WIP review（`.ai/docs/20260813-project-code-review/codereview.md`）

## 一、Review 概览

```
功能名称：全量并行 Code Review
Review 范围：全量代码库（非 diff 增量）
涉及模块：st-common / st-core / st-sync / st-search / st-team / st-share / st-auth / st-admin / st-api / st-preview / st-web / st-desktop / docker/mysql/init
审查人：reviewer（Standards 轴）+ reviewer（Spec 轴），并行执行
章节报告：standards.md（21.8KB）/ spec.md（15.9KB）
```

## 二、Standards 轴（标准符合度 + 坏味道 + 优化建议）

详见 [standards.md](E:/code/st-cloud/.ai/docs/20260814-project-code-review/standards.md)。要点：

### 硬性违规 H1-H7

- **H1（新）敏感信息硬编码**：`application.yml:53-54` 直接写入 S3 对象存储凭证，无环境变量通道。
- **H2（新 6 个 + 已知 1 个）迁移脚本幂等缺口**：`07/08/16/22/23/25_file_*.sql` 新增无 `IF NOT EXISTS` 守卫；`28_file_object.sql` 为已知未修复。
- **H3（新）迁移编号重复**：存在两个编号为 09 的迁移脚本，执行顺序歧义。
- **H4（新）文件编码违规**：73 个文件带 UTF-8 BOM（70 个 `st-web` + 3 个 SQL），违反"UTF-8 无 BOM"约定。
- **H5（已知）字段注入**：`@Autowired(required=false)` 仍存在（FileServiceImpl / FolderPermissionService 等）。
- **H6（已知 + 新）魔法数字**：`SyncBlockServiceImpl:201` 用 `2` 代替 `UploadStatus.COMPLETED`；Preview/Share 等新发现多处。
- **H7（新）测试分层违规**：st-share / st-auth / st-admin / st-preview 零测试；st-team 无集成测试。

### 坏味道 W1-W8（判断项）

- W1 Duplicated Code：`UserTransferLimiter` 双令牌桶逐行重复；`FileServiceImpl` 个人/团队双套方法组重复（已知，仍存在）。
- W2 Long Method / God Class：`AuditAspect.buildDetail` 约 260 行；`TeamServiceImpl` 856 行。
- W3 Primitive Obsession：状态裸数字遍布 9 个文件。
- W4 Mysterious Name / 注释漂移：`FileNode.uploadStatus` 注释未同步新增状态；`isTerminal` 与 `claimMerging` 语义矛盾（已知 m3 未修复）。
- W5 前端命名与文档约定漂移、W6 XML Mapper 文档与实现漂移、W7 SQL 层魔法数字、W8 其它小项。

### 通过项（12 条，节选）

分层结构清晰（controller/service/impl/mapper/entity/dto/enums/outbox）、核心逻辑中文注释覆盖权限/状态流转/配额、事件发布收敛到 `ReliableEventPublisher`、Mapper 原子 SQL（配额条件更新/claimMerging/refCou…）等。

## 三、Spec 轴（文档-代码一致性）

详见 [spec.md](E:/code/st-cloud/.ai/docs/20260814-project-code-review/spec.md)。要点：

- **P0 分享可选过期时间迭代整体未落地**：`UpdateShareRequest` 无 `clearExpireAt`、创建/更新无未来时间校验、管理页无过期展示、前端仍 `toISOString()`、`ShareAccessVO.isExpired` 死代码、st-share 无测试、H2 schema 缺 `file_share` 表——design 的 P2/P3/P4/P5/P7 及测试全部缺失。
- **P0 团队角色/统计端点断裂**：Service 层已实现 `listRoles/getStats/createRole` 等，但 TeamController 未暴露 `/roles`、`/role`、`/stats` 端点；前端 StatsPanel / RoleManageDialog 已在调用，运行期 404（静态比对结论，建议联调复核）。
- **P1 知识库落后**：`api-reference.md` 缺录中转/块级同步/同步排除冲突/团队 P0/通知等新端点；`data-model.md` 缺 `file_block` 表。
- **P2 文档管理漂移**：block-sync design 脚本编号 27→32（实际 `32_file_block.sql`）、favorites 文档未归档迭代子文件夹、移动端 `android/` 未生成。
- **已核对一致（无漂移）**：中转全链路、块级同步后端+桌面端、同步全量对账、schema_version 版本管理、团队 P0 八端点、分享过期访问 `SHARE_EXPIRED(3002)` 校验。

## 四、问题清单

| 编号 | 问题 | 等级 | 来源 | 建议 |
|------|------|------|------|------|
| C1 | S3 凭证硬编码进主配置 | Critical | S/H1（新） | 凭证移到环境变量/密钥管理，配置仅留占位符 |
| C2 | 团队 roles/stats 端点未暴露，前端调用 404 | Critical | P/4.1（新） | TeamController 补 `/roles`、`/role`、`/stats` 端点（Service 已就绪） |
| M1 | 分享过期迭代 P2/P3/P4/P5/P7 未落地 | Major | P/2.1（新） | 按 20260813-share-expiry design 补齐剩余功能与测试 |
| M2 | 迁移脚本幂等守卫缺失（07/08/16/22/23/25 + 28） | Major | S/H2 | 统一加 `IF NOT EXISTS` 守卫，compare-schema 复核 |
| M3 | 迁移编号 09 重复 | Major | S/H3 | 重新编号唯一化，更新 schema_version 记录 |
| M4 | 73 个文件 UTF-8 BOM | Major | S/H4 | 独立 TASK 批量去 BOM + 构建回归 |
| M5 | 4 个模块零测试、st-team 无集成测试 | Major | S/H7 | 按 testing.md 补主路径集成测试 |
| M6 | api-reference / data-model 知识库落后 | Major | P/3.1-3.2 | 同步新端点与 file_block 表 |
| m1 | `@Autowired(required=false)` 字段注入 | Minor | S/H5（已知） | 改构造器注入 |
| m2 | 魔法数字代替枚举（多处） | Minor | S/H6（已知+新） | 统一用既有枚举 |
| m3 | 双令牌桶/双套方法组重复 | Minor | S/W1（已知） | 抽取公共实现 |
| m4 | God Class / Long Method | Minor | S/W2 | TeamServiceImpl / AuditAspect 拆分 |
| m5 | 状态裸数字遍布 9 文件 | Minor | S/W3 | 状态机枚举化 |
| m6 | 文档管理漂移（favorites 归档、block-sync 编号） | Minor | P/3.3、2.3 | 文档归档与编号同步 |
| s1 | 命名/注释漂移、XML Mapper 文档漂移、SQL 魔法数字等 | Suggestion | S/W4-W8 | 随改随修 |

## 五、Review 结论

**总体评分**：Standards 轴存在 1 项 Critical + 5 项 Major 硬性违规与 8 组坏味道；Spec 轴存在 2 项 P0 漂移。代码结构、分层与核心逻辑注释整体合格，但**安全配置、接口完整性、测试覆盖与文档同步存在明确欠账**，当前状态不建议直接合并发布。

**必须修改项（P0）**：
1. S3 凭证环境变量化（C1）
2. 暴露团队 roles/stats 端点（C2）
3. 分享过期迭代剩余功能落地（M1）

**优化建议（按优先级）**：
- P0：上述必须修改项 + 迁移幂等/编号唯一化
- P1：全库去 BOM、补缺失模块测试、知识库同步（api-reference/data-model）
- P2：坏味道治理（抽取公共桶实现、拆分 God Class、状态枚举化）、命名与注释同步

> 已知问题（20260813 review）已在对应条目标注"已知/待整改"，未重复计为新问题。修复完成后需复检对应轴条目。

## 六、P0 修复复检（2026-08-14 执行）

按 P0 优先级拆分 4 个并行修复任务（executor），全部完成并经主线程复跑验证：

| 原编号 | 问题 | 修复 | 验证结果 |
|--------|------|------|----------|
| C1 | S3 凭证硬编码 | `application.yml` 改为 `${STCLOUD_S3_ACCESS_KEY:stcloud}` / `${STCLOUD_S3_SECRET_KEY:stcloud123}`（环境变量 + 本地默认值） | 通过（grep + YAML 缩进确认） |
| C2 | 团队 roles/stats 端点缺失 | `TeamController` 新增 5 端点（roles GET / role POST/PUT/DELETE / stats GET），路径与前端一致 | 通过（`mvn -pl st-team -am compile` EXIT=0） |
| M1 | 分享过期迭代 P2-P7 未落地 | 后端过期校验 + `clearExpireAt` + 删 `isExpired` 死代码；前端本地时间格式 + 已过期徽标 + types 清理；H2 `file_share` 补齐（st-core + st-share）；st-share 测试基础设施 + 10 条集成测试 | 通过（`mvn -pl st-share -am test` EXIT=0、tsc/npm build 通过） |
| M2 | 迁移脚本幂等缺口 | 07/08/16/22/23/25/28 按 30 号模式补 `information_schema` 守卫 | 通过（内容核对；MySQL 不可连，compare-schema 待环境可用） |
| M3 | 迁移编号 09 重复 | `09_remove_two_factor.sql` → `09b_remove_two_factor.sql` | 通过（无同号残留） |

> 数据库说明（2026-08-14 已执行，本地 MySQL 8.0.44）：重跑 8 个被改迁移脚本验证幂等 8/8 通过；`schema_version` 已登记 `20260814.1`（迁移脚本幂等加固与编号治理，applied_sql_files=07,08,09b,16,22,23,25,28）；`.ai/scripts/compare-schema.ps1` 复跑 **PASS（退出码 0）**，H2 与 MySQL 列集无差异（含 M1 补齐的 `file_share` 16 列）。

**剩余未处理（非本轮 P0）**：去 BOM（H4）、补模块测试（H7，st-share 已补）、知识库同步（Spec P1/P2）、坏味道治理（W1-W8）、分享访问链路 SECURITY_REVIEW 复检。

## 七、P1 修复复检（2026-08-14 执行）

| 原编号 | 问题 | 修复 | 验证结果 |
|--------|------|------|----------|
| H4 | 73 文件带 UTF-8 BOM | 全仓去 BOM：84 个文本文件剥离 EF BB BF 前缀（69 st-web + 17_team_invite.sql + 杂项）；2 个含中文 `.ps1` 按规范保留 BOM | 通过（复扫非排除项 0；`npx tsc --noEmit` EXIT=0） |
| H7 | st-auth / st-admin / st-preview 零测试，st-team 无集成测试 | 补测试设施 + 主路径集成测试：st-auth 8 条、st-admin 9 条、st-preview 5 条、st-team 8 条（含角色/统计端点） | 通过（主线程复跑 `mvn -pl st-auth,st-admin,st-preview,st-team -am test` EXIT=0） |
| Spec P1/P2 | 知识库落后 + 文档漂移 | api-reference.md 补录 relay/block-check/block-upload/同步排除冲突/通知端点与 UploadInitResponse 字段；data-model.md 补 file_block 表；block-sync design 编号 27→32；favorites 文档归档 | 通过（抽查补录项与 Controller/DTO 一致） |

**剩余未处理**：坏味道治理（W1-W8，重构类，建议独立迭代）、分享访问链路 SECURITY_REVIEW 复检、st-team 成员角色细粒度权限（可选）。

## 八、坏味道治理复检（2026-08-14 执行）

| 原编号 | 问题 | 修复 | 验证结果 |
|--------|------|------|----------|
| W1 | 双令牌桶逐行重复 | 抽取公共 `TokenBucket`（行为等价）；**并修复原实现隐藏缺陷**：申请量 > 桶深时 tokens 封顶导致 acquire 死循环（生产大分块下载可复现），等待上限放宽为 max(capacity, bytes) | 通过（st-common 5 类 25 用例全绿；新增 UserTransferLimiterTest 6 条 2.2s） |
| W3 | 状态裸数字遍布 9 文件 | 新增 10 个状态枚举（ShareStatus/TeamSpaceStatus/InviteStatus/RoleStatus/UserStatus/TenantStatus/SyncRootStatus/FileChunkStatus/FileObjectStatus/EventOutboxStatus）+ refCount 常量 + HttpStatus 替换 | 通过（st-core,st-share,st-team,st-auth,st-sync,st-admin -am test 全绿；rg 复核无残留） |
| W4 | 注释漂移 / isTerminal 语义矛盾 | FileNode 注释同步；`isTerminal()` 修正为 COMPLETED/DELETED（业务确认 FAILED→MERGING 为合法重试）；claimMerging 注释同步 | 通过（st-core 16 类 63 用例全绿） |
| W5/W6/W7/W8 | 文档与实现脱节 | frontend.md（store 路径/命名豁免）、conventions/architecture（注解式 SQL 为主、流式下载豁免）、6 个 Mapper 37 处 SQL 状态注释、application.yml 注释 | 通过（内容核对） |

**仍未处理**：W2 God Class / Long Method 拆分（AuditAspect 260 行、TeamServiceImpl 856 行、FileServiceImpl 967 行）——高风险重构，建议独立迭代分批做。

## 九、分享访问链路安全复检（2026-08-14，结论 BLOCK）

详见 [security-recheck.md](E:/code/st-cloud/.ai/docs/20260814-project-code-review/security-recheck.md)。M1 改动后复检发现 **2 个 P0 阻断项**：

- **S-01 越权分享**：`createShare` 仅校验回收链状态，不校验 `owner_id`/团队成员 → 同租户任意用户可分享他人文件。
- **S-02 下载链路断裂 + 绕过**：`getDownloadUrl` 对匿名用户 NPE（500）；`streamShareFile` 不经限流/权限直接回传，成为绕过通道。
- P1：S-03 路径边界越权（`startsWith` 无 `/` 边界）、S-04 提取码明文存储；其余 7 项 P2/P3。

**建议**：下一步派发 executor 修复 S-01/S-02（P0），S-05/S-08 需先定产品语义（"查看"是否含下载、多租户分享可达性），修复后重跑本安全复检。

## 十、安全 P0 修复复检（2026-08-14 执行）

按 security-recheck.md 定版方案派发 executor 修复（仅改 st-share/**）：

| 编号 | 修复 | 验证 |
|------|------|------|
| S-01 P0 越权分享 | `createShare` 增加资源级归属校验：个人文件必须本人（或租户管理员 canAccessTenant），团队文件走 `validateTeamNode` | 通过 |
| S-02 P0 下载 NPE/绕过 | `getDownloadUrl` 增加 permission=0 拒绝 + 改走 `storageService.generateDownloadUrl(storagePath)`（消除匿名 NPE）；`streamShareFile` 增加 downloadLimit 校验与计数 | 通过 |
| S-03 P1 路径边界 | 三处子树校验统一为 `isWithinShare`（path 等于根或 `根 + "/"` 前缀），rg 复核无无边界 startsWith 残留 | 通过 |

新增 `ShareServiceImplSecurityIntegrationTest`（11 条安全用例：越权分享被拒/匿名下载成功/仅查看拒绝下载/限次/同名前缀拒绝）；st-share 21 用例全绿（主线程复跑 `mvn -pl st-share -am test` EXIT=0）。

**S-01 团队对齐（2026-08-14 追加）**：团队文件分享创建从"空间成员级"（validateTeamNode）对齐到项目标准 `TeamService.checkPermission(spaceId, nodeId, 2)`——空间成员校验 + `FolderPermissionService.resolvePermission` 文件夹权限链（-1/越权拦截），至少"可查看"可分享；st-share 新增 `st-team` 依赖（无环），团队权限用例 2 条（非成员拒绝 / 权限链通过 verify）。主线程复跑 st-share 21 用例全绿。

**S-02 下载标识（2026-08-14 追加）**：按方案在 `file_share` 新增 `allow_download`（0-禁止 1-允许）作为下载/流式统一下载开关（`permission` 保留展示/兼容）。迁移 `33_share_allow_download.sql`（幂等守卫 + 历史 `permission=0` 分享联动置 0）；`getDownloadUrl` 与 `streamShareFile` 均以 `allowDownload==0` 拒绝；创建默认与 permission 联动、更新可切换；前端类型补 `allowDownload`。已执行迁移（3 条历史分享中 2 条联动为禁止下载）、`compare-schema.ps1` PASS、`schema_version` 登记 `20260814.2`；主线程复跑 st-share 26 用例全绿。

**S-06/S-07/S-09 与前端开关（2026-08-14 追加）**：
- S-04：按用户决策**接受提取码明文存储**（不引入 BCrypt），记录在案。
- S-05：反馈现状——`permission` 为单值枚举（0-查看 1-下载 2-上传 3-编辑），**非"角色+权限组合"模型**；方向待用户定版。
- S-06：分享码改为 **4 位数字字母**（`SecureRandom` + 32 字符集排除 0/O/1/I，约 20bit 熵）+ `uk_share_code` 冲突重试（最多 8 次）；历史 8 位码不变。
- S-07：下载计数改为**原子条件更新**（`download_limit IS NULL OR download_count < download_limit` 才递增，影响行数 0 即拒绝），消除 TOCTOU。
- S-09：`streamShareFile` 默认**限速 5MB/s**（`paceStream` 字节 pacing）。
- 前端：ShareDialog 新增"允许下载"开关（默认开，联动 permission+allowDownload）；ShareManagePage 展示"允许下载/仅查看"。

验证：st-share 33 用例全绿（Expiry 10 + Security 20 + ShareCode 单元 3）、`npx tsc --noEmit` EXIT=0（均主线程复跑）。

**遗留提示**：4 位分享码熵较低，建议后续为公开访问接口加 IP 限流（S-06 配套，未在本批范围）。

**行为变化提示**：`permission=0`（仅查看，新建默认）现在拒绝下载 URL，前端需按权限展示下载入口。

**仍待处理**：S-04 提取码明文存储（需 DB 迁移 + BCrypt，建议独立迭代）、S-06 分享码熵/限流、S-07 下载计数 TOCTOU、S-05/S-08 产品语义定版；完成后重跑 security 复检。
