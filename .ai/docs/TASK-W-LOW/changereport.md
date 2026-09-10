# Change Report — W4-W8 低风险治理（注释/文档同步，不改业务逻辑）

- Task ID: TASK-W-LOW（dispatchId: w-low-001）
- 执行角色: executor（taskType=implement）
- 日期: 2026-08-14

## 背景

全量 Code Review（W4/W5/W6/W7/W8）发现注释漂移与文档-实现脱节：`FileNode.uploadStatus` 字段注释未同步 4-合并中/5-已删除；`lockExpireAt` 残留 hidden 字段的「0-正常 1-隐藏」注释；`UploadStatus.isTerminal()` 注释/实现与 `claimMerging` SQL 的 FAILED→MERGING 流转矛盾；前端文档（shadcn 命名、useUpload 扩展名、favorites store 路径）与实现不符；XML Mapper 文档约定与实际注解式 SQL 脱节；Mapper SQL 状态含义缺少注释；流式下载 void 直写与前端 any 缺少文档说明。

## 输入

- TASK-W-LOW.md（本任务唯一编码输入）
- `.ai/docs/20260814-project-code-review/standards.md`（W4-W8 审查记录）
- 实际代码：`FileNode.java`、`UploadStatus.java`、6 个 Mapper、`application.yml`
- 知识库：`frontend.md` / `conventions.md` / `architecture.md`
- 业务事实核验：`UploadServiceImpl.java:385`、`UploadManager.java:94-125`、`UploadStateMachineIntegrationTest.java:285-292` 均印证「合并失败标记 FAILED 供重试」，`claimMerging` SQL 为 `upload_status IN (1, 3)`

## 分析

1. W4 状态机矛盾：业务上 FAILED(3) 允许流转到 MERGING(4)（失败后重试合并），故 `isTerminal()` 把 FAILED 判为终态与业务矛盾；且全库无任何 `isTerminal()` 调用方，修正实现不影响运行时行为，纯语义对齐。
2. W5 文档漂移：`st-web/src/store/` 实际存在 `favorites.ts`（frontend.md API 表误写 `src/lib/store/favorites.ts` 且 store 表未收录）；`components/ui/` 下 10 个 shadcn 风格基础组件为小驼峰/连字符；`useUpload.tsx` 因含 JSX 使用 .tsx。
3. W6：全库无 XML Mapper，45 个自定义 SQL 均为注解式（含 `<script>` 动态 SQL），文档应改为「注解式为主、XML 可选」。
4. W7：关键 SQL 状态字面量（node_type / upload_status / status / deleted 等）旁补充中文状态含义注释，SQL 语句零改动。
5. W8：`FileController.streamFile` / `downloadAsZip` void 直写响应属流式下载合理例外；前端 `any` 属待改进项，均需文档显式说明。

## 决策

按 TASK 范围执行注释/文档同步；唯一逻辑改动为 `UploadStatus.isTerminal()` 从 `COMPLETED || FAILED || DELETED` 修正为 `COMPLETED || DELETED`（TASK W4 第 1 条授权，以业务为准），并同步类注释与 `claimMerging` 方法注释。

## 修改文件清单

| 文件 | 改动 |
|------|------|
| `st-core/.../entity/FileNode.java` | uploadStatus 注释补 4-合并中/5-已删除；lockExpireAt 删除误挂的 hidden 注释，改为「锁过期时间（P2 文件锁定）」 |
| `st-core/.../enums/UploadStatus.java` | 类注释补「合并失败可重试 FAILED→MERGING」；isTerminal() 语义修正（FAILED 非终态）并更新 javadoc |
| `st-core/.../mapper/FileNodeMapper.java` | 11 处关键 SQL 补状态含义注释；claimMerging 注释修正为「上传中(1)或失败(3)时置为合并中(4)」 |
| `st-core/.../mapper/FileObjectMapper.java` | 6 处关键 SQL 补状态含义注释 |
| `st-core/.../mapper/EventLogMapper.java` | 3 处关键 SQL 补状态含义注释 |
| `st-core/.../mapper/FileChunkMapper.java` | 2 处关键 SQL 补状态含义注释 |
| `st-core/.../mapper/CloudCapacityMapper.java` | 6 处关键 SQL 补状态含义注释 |
| `st-admin/.../mapper/StatsMapper.java` | 9 处统计 SQL 补状态含义注释 |
| `st-api/src/main/resources/application.yml` | mapper-locations 旁补注释（保留配置，说明当前以注解式 SQL 为主） |
| `.ai/knowledge/frontend.md` | 补 shadcn 命名豁免、ErrorBoundary 类组件豁免、useUpload.tsx 说明；favorites store 修正为 `src/store/favorites.ts` 并收录；补 W8 待改进项说明 |
| `.ai/knowledge/conventions.md` | XML Mapper 约定更新为「注解式 SQL 为主、XML 可选」；组件文件/Hook 命名约定补豁免说明 |
| `.ai/knowledge/architecture.md` | 统一响应章节补流式下载 void 直写合理例外（W8） |

> 说明：`application.yml` 中 S3 access-key/secret-key 环境变量化（`${STCLOUD_S3_ACCESS_KEY:stcloud}` 等）为工作区已有改动（对应 H1 修复），非本任务改动；本任务仅新增 mapper-locations 注释。

## 验证

- `mvn -pl st-core -am test`：BUILD SUCCESS，16 个测试类 / 63 个用例，failures+errors = 0
- Mapper SQL 语句零改动（仅新增 Java 注释行）；`application.yml` 仅新增注释行
- 知识库三份文档与实现核对一致（favorites 路径、shadcn 命名、XML Mapper 现状、流式下载例外）
- 所有修改文件保持 UTF-8 无 BOM（frontend.md 顺带去除原 BOM，符合文件编码规范）
- 未触碰 st-web 组件文件、未重命名任何文件、未创建子 Agent、未修改业务 SQL

## State Delta

- 新增 artifact：`.ai/docs/TASK-W-LOW/changereport.md`
- 解除 blocker：W4（注释漂移/状态机语义矛盾）、W5（前端文档漂移）、W6（XML Mapper 文档脱节）、W7（SQL 状态注释缺失）、W8（文档豁免说明缺失）
- 归档：`.ai/dispatch/inbox-w-low-001.md` 已原子认领并移至 `.ai/dispatch/archived/`

## 风险

- `isTerminal()` 语义变化虽当前无调用方，未来若有人按旧语义（FAILED 终态）使用可能产生误判；已通过 javadoc 与 claimMerging 注释明确「FAILED 可重试流转」业务规则
- 文档改动以审查记录为准，若后续业务调整状态机需同步三份知识库

## 下一步

主线程抽查注释与文档一致性；如需将 FAILED 重试语义落到正式状态机文档（`docs/newList/` 或数据模型），可另派 knowledge 任务。

## 变更影响

- 影响范围：仅注释/文档；`UploadStatus.isTerminal()` 行为变化对运行时零影响（无调用方）
- 无数据库/接口契约变更，无迁移脚本需求
- 后续 Code Review 复核 W4-W8 时可引用本报告对应项
