# 九项整改独立安全审查

## 背景

用户要求解决 `20260930-worktree-review/review.md` 中 S1、F1–F8。本审查只评估本任务相对 baseline 的新增改动与直接相关的安全边界，不继承历史通过结论。

执行者：`/root/security_review`。Dispatch：`DISPATCH-20260930-REVIEW-SECURITY_REVIEW-f79eaf`。修订：`review-fixes-code-3ae1050e4646f449`。

## 输入

已校验 Envelope 必填字段、role、scope 与 forbidSpawn；skillRefs 为 `-`。读取 TASK、当前 State 中本任务的 Goal/revision/SECURITY_REVIEW 依赖及 requirement、design、testcases、原始 review、changereport。使用 `baseline/hashes.json` 与 baseline 源码副本比较本次改动。

独立执行 SHA256 校验：baseline 8 个文件全部一致；`source-revision.json` 的当前 15 个文件全部一致。审查没有调用 Git、Maven、共享构建或数据库写入。写入仅限本报告和独立结果 JSON，没有修改 State。

## 分析

以下“代码事实”来自当前源码及 baseline 对比；日志验证由主线程运行，本执行者独立读取日志和测试代码，未声称重新执行这些测试。

| 项目 | 代码事实与安全判断 | 已有验证证据 |
| --- | --- | --- |
| S1 | 45/46 仅在当前数据库缺少 security_version 时 ADD COLUMN，已有列分支 SELECT 1，不重置既有版本。DDL 字符串没有外部输入。 | mysql-migration.log：45 首次及重复、46 两次成功，SCHEMA_BEFORE_EXIT=0、SCHEMA_AFTER_EXIT=0，已有版本 17 保留；schema-before.log/schema-after.log 均 PASS；schema_version 登记 20260930.901/.902。 |
| F1 | AuthService:59–95 使用独立 RC 写事务，等待租户锁后重查租户/用户名；用户及角色提交后，独立 RR 只读事务共同读取用户版本、租户及权限。签发前 isCurrent 再校验；JWT 和 Redis 写入均在写事务完成之后。写事务异常会阻止进入签发阶段。 | backend-regression.log 第 112 行：RegisterMysqlConcurrencyIntegrationTest 3/3，覆盖真实 MySQL RR 默认连接下服务 RC、持锁撤权后真实 JWT 权限为空、提交后事务外 refresh、禁用租户与回滚。Redis 使用受控输出。 |
| F2 | 七个角色/引用写入口明确 RC，空间锁在权限、角色及引用决策之前；joinByCode 锁前仅定位空间，锁后重新读取邀请并检查撤销、到期、空间及角色状态。权限解析读取当前成员与角色，不依赖展示缓存。 | backend-regression.log 第 4757 行：TeamRoleMysqlConcurrencyIntegrationTest 16/16，含成员/邀请/角色修改/接受邀请两种锁顺序，以及未提交授权不可见和提交/回滚隔离。 |
| F3 | FileServiceImpl:445–453 先通过已有个人节点/租户/所有者边界，再对回收链返回 2008；已删除自身使用 2001。其他所有者仍 403。桌面 readCloudNode 仅把 2001/2008 当缺失，403 和其他错误仍抛出；DELETE 使用已有保全流程后清理状态，失败阻止本页确认。 | FileServiceFlowIntegrationTest 18/18（backend-regression.log 第 176 行），新增用例检查回收文件/祖先 2008、同租户他人 403、跨租户拒绝。desktop-review.log：2008 原字节保留并推进，403 不删不推进。 |
| F4 | sync-engine.ts:517–540、578 的补下载分支使用当前节点大小/哈希/时间，下载模块仍先校验临时文件再落地。直接 rename 保持旧本地 md5/size，避免把云端新内容冒充本地内容；原有本地修改先保全。 | desktop-review.log：MOVE/RENAME 各 missing/preserved/rename，共 6 个场景通过，包含保全字节、当前下载及后续 UPDATE。 |
| F5 | manager 在 engine.start 前严格加载 exclusions；业务码、数组结构或路径类型错误均抛出，不能静默以空规则扫描。 | desktop-review.log：排除请求第一条、排除原件/映射保持，获取失败未对账、未启 watcher/timer；恢复后可启动。 |
| F6 | 已有目标仅在身份相同且 status=synced 时确认；只删除 nodeId 相同的旧映射。源身份被其他节点复用时仍走既有对账，不因本次清理逻辑删除它。 | desktop-review.log：同节点重放仅留新映射、无二次下载；其他节点旧映射保留。 |
| F7 | 全量失败抛出，manager 删除 engines 缓存并 stop；旧游标与版本保留，失败不能固化成功标记。 | desktop-review.log：全量失败未启 watcher/timer、保留 cursor=1/syncVersion=3，第二次 start 完成对账并正常运行。 |
| F8 | 新组件只记录失败 URL 并渲染 React 图标；未新增 HTML 注入或权限绕过。分享来源、内容版本与既有鉴权参数保留在 URL，改变 URL 后允许重试。 | browser-filmstrip.log：真实 React 组件在受控 API 下验证 503/损坏、WebP/SVG 降级及名称、点击、Enter 导航、新 URL 请求、无 pageerror。 |

安全判断：本次改动改善锁后权限决策与已提交快照一致性；回收业务码在所有权检查之后产生；桌面补下载、排除、保全与重放没有扩大访问权限或放宽校验。对于快照完成后的并发撤权，版本核验及每请求当前版本检查仍会拒绝旧版本令牌，这是结合 UserSecurityService 当前源码作出的判断，不冒充新增并发端到端测试。

## 决策

建议 `SECURITY_REVIEW = pass`。本任务新增改动中未发现需要整改的安全问题。该建议只代表此修订的安全审查，最终 Goal 验收与 State Evaluate 由主线程执行。

## State Delta（proposal）

建议 criterionId `SECURITY_REVIEW`、outcome `pass`、by `/root/security_review`、validatedRevision `review-fixes-code-3ae1050e4646f449`，证据为本 Dispatch 独立结果；blockerProposals 为空。未写 State。

## 风险与验证边界

- 未独立重跑测试；以独立源码审查、哈希检查及本任务实际日志为证据。真实 MySQL 用例不代表 Redis/HTTP 全栈端到端；桌面为实际引擎、下载、恢复代码与真实临时文件，API 受控；浏览器 API 受控。
- 新增跨租户详情用例只断言拒绝，未断言具体业务码。既有租户过滤可能隐藏节点为 2001；本次未改所有权查询，因此不把“所有跨租户均为 403”写成验证事实。
- 注册提交成功后签发/Redis 失败，已创建账号仍保留；这是提交后签发边界的预期结果。审查范围不包含报告明确排除的 refresh token 历史问题。

## 下一步

主线程核对 attempt、scope、产物及修订后 Evaluate；若业务源码哈希改变，应按新 revision 重审。

## 变更影响

本执行者新增两份审查产物，不修改业务源码、数据库、配置或 Loop State。通过建议没有改变 API；业务改动的正常 API 结构保持既有契约，个人回收详情使用已有 2008。
