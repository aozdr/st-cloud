# 九项整改独立代码审查

## 背景

审查对象为原 review.md 的 S1、F1–F8 整改；执行者 `/root/code_review`，Dispatch `DISPATCH-20260930-REVIEW-CODE_REVIEW-c4e3c9`。结论：本次整改差异未发现阻断问题，建议 CODE_REVIEW 通过。此结论只针对代码审查，测试与最终验收由主线程按当前修订证据判定。

## 输入

- 当前 TASK、requirement.md、design.md、testcases.md、changereport.md，以及原 `.ai/docs/20260930-worktree-review/review.md`。
- 本任务开始时的 baseline 副本与 hashes.json；只对这些副本与当前文件作文本差异比较，没有把 HEAD 的其他未提交变化纳入本次整改。
- source-revision.json 指定 `review-fixes-code-3ae1050e4646f449`；审查结束前核对 15 个 manifest 文件 SHA-256 全部一致，8 个 baseline 副本 SHA-256 全部一致。State 只作输入，没有写入或 Evaluate。

## 分析

| Finding | 代码事实与判断 | 证据 |
| --- | --- | --- |
| S1 | 45、46 均以 INFORMATION_SCHEMA.COLUMNS 判断 security_version 是否已存在，再执行条件 DDL；不存在时新增 BIGINT NOT NULL DEFAULT 0，存在时不修改数据，首行 SET NAMES utf8mb4。既有 H2 字段无需重复变更。 | docker/mysql/init/45_user_security_version.sql:1；46_user_security_version_retry.sql:1；mysql-migration.log 的 SCHEMA_BEFORE_EXIT=0、SCHEMA_AFTER_EXIT=0 与 SECURITY_VERSION=17；schema-before.log、schema-after.log 均 PASS。 |
| F1 | register 外层暂停事务，注册写入使用独立 RC 事务；租户锁后重读状态与用户名，再读取当前默认角色。提交后另建 RR 只读事务，从同一快照读取用户版本、租户与权限，退出事务后校验当前安全版本并签发、保存 refresh。数据库写入失败不能到达签发阶段。 | AuthService.java:60–94、119–145；RegisterMysqlConcurrencyIntegrationTest.java:58–124；backend-regression.log 记录真实 MySQL 注册 3/3、无跳过。 |
| F2 | 七个角色引用写入口采用 RC；邀请成员、成员换角色、创建/更新/删除角色与创建邀请均先锁空间再做权限/角色/引用判断。joinByCode 的锁前查询仅定位空间，锁后重新读取邀请、校验撤销/过期及空间状态。 | TeamServiceImpl.java:194、281、350、402、982、1000、1015；TeamRoleConcurrencyIntegrationTest.java:67–125；真实 MySQL 子类运行继承矩阵，backend-regression.log 记录 16/16。 |
| F3 | getNodeDetail 先执行既有租户/所有者/个人空间边界，再将已删除节点映射为 FILE_NOT_FOUND，回收节点或祖先映射为 FILE_IN_RECYCLE。桌面既有详情解析仅对 2001/2008 返回不可用，403 继续抛错，因此回收删除进入保全分支，真实拒绝不能被当作删除。 | FileServiceImpl.java:444–454、656–669；FileServiceFlowIntegrationTest.java:146–161；sync-engine.ts:649–665；sync-review.test.cjs 的 2008/403 DELETE 用例。 |
| F4 | MOVE/RENAME 的源缺失及源已保全分支用刚读取的当前 size/md5/updatedAt 补下载；源文件直接 rename 则携带旧本地 md5/size/localMtime，后续 UPDATE 能识别云端新内容，不把云端当前哈希冒充本地基线。 | sync-engine.ts:517–543、577–581；sync-review.test.cjs 六项 MOVE/RENAME 的 missing/preserved/rename 用例，直接 rename 后再消费 UPDATE。 |
| F5 | manager 在 engine.start 之前等待 exclusions 获取成功并应用；响应必须成功且为含字符串 relativePath 的数组，失败会抛错。因此首次扫描、升级对账均已持有排除规则。 | sync-manager.ts:64–75、150–167；sync-review.test.cjs 验证首个请求为 exclusions，原件和映射未动，获取失败无 reconcile/watcher/timer。 |
| F6 | 目标已存在且属于同节点的文件重放，仅在目标状态 synced 时清理同节点旧映射；不完整目标阻止推进，其他节点复用的旧身份先走已有身份对账保护。清理条件没有扩大到所有旧路径映射。 | sync-engine.ts:483–487、554–565；sync-review.test.cjs 同节点清旧映射及其他节点保留用例。 |
| F7 | 全量失败保留旧游标/版本并显式抛错；manager 删除实例缓存、停止 watcher/timer 资源后再向调用者抛错。下次 startSync 能创建新实例重新对账。跨进程夹具只捕获注入失败，resume 模式仍会抛出意外失败。 | sync-engine.ts:81–96；sync-manager.ts:71–75；restart-worker.cjs:444–451；sync-review.test.cjs 两项恢复启动用例。 |
| F8 | ShareFilmstripThumbnail 的错误状态按完整 src 记录，失败与 WebP/SVG 共用 ImageIcon；src 包含分享来源、密码与 updatedAt 版本，来源/版本变化能再次加载。外层 button 的 title、aria-label、click 保持。 | PreviewModal.tsx:16–22、523–547；browser-filmstrip.log 的真实 React/受控 API 浏览器结果；web-build.log 成功。 |

核心权限、状态与文件保全的新逻辑有中文注释。差异没有引入 S3/外部网络进入注册写事务，没有新增业务列或扩大个人 API 权限。正常 API 结构保持，回收态沿用既有业务码，兼容策略已写在设计与变更报告中。

## 决策

建议 CODE_REVIEW `pass`，无 blockerProposal。逐项代码判断与本任务定版设计一致；未将历史 refresh token 问题或原有未修改代码作为新 finding。

## State Delta（proposal）

仅提议 CODE_REVIEW 在 `review-fixes-code-3ae1050e4646f449` 上通过，证据为本报告和本 Dispatch 独立结果。未修改 Loop State，未判断整个 Goal 完成。

## 风险与验证边界

本 child 执行了文件差异、哈希与 Envelope 字段/语义检查，没有执行 Maven、测试、迁移或浏览器。上表运行结果来自主线程本任务的现有日志，不能描述为 child 重新运行。

审查期间完整桌面回归日志曾包含加载夹具适配前代码的失败；已通知主线程，并核对当前冻结修订的 restart-worker 已适配。完整桌面回归正在重新运行，因此本报告不把该日志视为最终通过证据。后端日志显示本任务所选 reactor 的 BUILD SUCCESS；完整测试门禁仍须独立 tester 检查最终日志与当前修订。

## 下一步

主线程核对结果、执行 CODE_REVIEW Evaluate，并由独立 tester 核对最终串行回归结果及迁移证据后处理 TEST_PASS。

## 变更影响

本 child 只新增 codereview.md 和独立结果 JSON；没有改源码、数据库、TASK、State，也没有提交或启动子 Agent。
