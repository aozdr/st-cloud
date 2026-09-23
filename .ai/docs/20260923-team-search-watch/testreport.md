# 测试报告

- Task：`TASK-20260923-team-search-watch`
- 当前 revision：`tsw-design-r4` / `tsw-code-r8`；下表后端日志来自 r6，r7 追加前端修正，r8 追加搜索权限读取优化与游标规范化
- 日期：2026-09-23
- 测试执行者：GPT-6 主线程；没有创建子 Agent

## 结果

| 范围 | 命令/证据 | 结果 |
|---|---|---|
| 核心 Outbox 与 schema | `mvn -pl st-core -am test -Dtest=SchemaConsistencyTest,EventOutboxIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`；[core-tests-r4.log](../20260923-team-search-watch-core-tests-r4.log) | 14 项通过，0 失败；含新建索引租户补齐、租户冲突拒绝、无可信租户拒绝；多列 ALTER 解析后无漂移警告 |
| 关注、权限、投递、通知 | `mvn -pl st-team -am test -Dtest=NotificationControllerTest,FileWatchReliabilityIntegrationTest,FileWatchServiceImplTest,FolderPermissionFreshTest,TeamFileAccessPolicyTest,FileWatchAccessServiceTest,FileWatchCaptureListenerTest,FileWatchDeliveryProcessorTest,FileWatchDeliveryRetryServiceTest -Dsurefire.failIfNoSpecifiedTests=false`；[watch-tests-r3.log](../20260923-team-search-watch-watch-tests-r3.log) | 40 项通过，0 失败；含通知分页参数拒绝及 H2 事务/重试路径 |
| 搜索 | `mvn -pl st-search -am test -Dtest=TeamSearchServiceTest,TeamSearchElasticsearchIntegrationTest,SearchServiceImplTest,FileIndexEventListenerTest,FileIndexMessageConsumerTest -Dsurefire.failIfNoSpecifiedTests=false`；[search-tests-r2.log](../20260923-team-search-watch-search-tests-r2.log) | 64 项通过，0 失败；其中真实本机 Elasticsearch 临时索引测试 2 项通过，未写入业务索引 |
| 后端聚合构建 | `mvn -pl st-api -am package -DskipTests`；[backend-package-r3.log](../20260923-team-search-watch-backend-package-r3.log) | Parent、Common、Auth、Core、Team、Share、Sync、Search、Preview、Admin、API 全部成功 |
| H2/MySQL schema | `SchemaConsistencyTest` + `powershell -ExecutionPolicy Bypass -File .ai/scripts/compare-schema.ps1`；[schema-check-r2.log](../20260923-team-search-watch-schema-check-r2.log) | 测试通过；MySQL 对比退出码 0、无结构差异，SQL 均已登记 |
| 前端构建与 lint | [frontend-build-escalated.log](../20260923-team-search-watch-frontend-build-escalated.log)、[frontend-lint-escalated.log](../20260923-team-search-watch-frontend-lint-escalated.log) | build 成功；lint 0 error、11 个 warning（报告中的 UI 通用组件/既有 hooks 警告） |
| 浏览器交互 | [browser-verification.md](browser-verification.md) | 375px 搜索筛选、键盘操作、通知弹层边界和 Escape 焦点恢复通过 |

## 重试记录

核心新用例首轮因断言全局 Spring 事件列表必须完全为空而失败；全局监听还会捕获与索引无关的容器事件。将断言改为仅禁止 `FileIndexEvent`/`OutboxRelayEvent` 后重跑，14 项全通过。随后修正多列 `ALTER TABLE` 静态解析的误报并再次运行，仍为 14 项全通过且不再报告 notification 列漂移。首次失败见 `../20260923-team-search-watch-core-tests-r2.log`，以 r4 为最终结果。

## 覆盖边界

搜索包含真实 Elasticsearch 临时索引集成测试；Team/核心集成测试使用 H2。没有执行由真实前端会话、MySQL、ES、对象存储和消息队列组成的完整部署级端到端流程，也没有生产 reindex 或生产迁移。前端 lint 的 11 个 warning 不是本次新增错误，但仍保留在日志中。

## r7 修正后的主线程验证

独立体验方案评审指出游标过期恢复按钮和通知异步目标乱序问题。主线程修复后执行 `npm run build`，首次 Vite/esbuild 子进程在受限沙箱内报 `spawn EPERM`；按工具权限重跑后退出码 0，TypeScript 编译与 Vite 生产构建成功。`npm run lint` 退出码 0，0 error、11 条与本次修改文件无关的 warning。工作流引擎 `state-engine.tests.ps1` 再次全部通过。上述主线程运行不能替代独立 TEST_PASS；独立测试结果将追加于本节后。

当时全局 `.ai/scripts/verify-loop.ps1` 退出码 1，原因是六条历史 TASK 将未生成的预期报告写成普通路径引用（20260916/19/21/22）；当前 State 自身的 `loopctl validate` 通过。后续已把这六条准确标为 `[planned-output]`，没有补造历史产物；再次运行全局校验退出码 0，`FAIL=0`、结果 `PASS`。其余 18 条历史 WARN 保留。

## r8 主线程定向验证

`mvn -pl st-team -am test '-Dtest=TeamFileAccessPolicyTest,FolderPermissionFreshTest' '-Dsurefire.failIfNoSpecifiedTests=false'` 退出码 0，13 项通过。搜索首轮 18 项中 1 项失败，暴露 Base64URL 非规范末位可解码为原签名；修正游标规范化后，`mvn -pl st-search -am test '-Dtest=TeamSearchServiceTest,TeamSearchElasticsearchIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false'` 退出码 0，19 项通过（含 2 项真实 ES 临时索引测试）。该轮还验证候选扫描使用请求上下文、最终结果仍用 fresh 核权。其后的完整回归及用户授权单人 TEST_PASS 结果见下节。

## r8 全量回归尝试的边界

主线程尝试 `mvn -pl st-api -am test -q`。命令进入 `st-api` 集成测试后触发开发环境 Elasticsearch 索引重建，日志显示 `Reindexed 805 / 805 file nodes to ES`。为避免继续对共享开发服务产生副作用，主线程主动中断命令，退出码 1；这不是“全量测试通过”的证据，也不是已定位的断言失败。后续验证应将此类依赖真实服务的集成测试与纯测试环境隔离，不能直接重复当前命令。r8 定向测试结果仍按上节独立记录。

## r8 完整安全回归与前端验收

仅排除上述具有开发环境索引写入副作用的 `ReindexIntegrationTest`，运行 `mvn -pl st-api -am test '-Dtest=*,!ReindexIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' -q`，退出码 0。[后端日志](backend-tests-r8-safe.log)及同次生成的 Surefire XML 共 83 份报告、471 项测试，0 failure、0 error、0 skip。覆盖 common、auth、core、team、share、sync、search、preview、admin；`st-api` 唯一测试是被明确排除的重建测试。

`compare-schema.ps1` 再次对开发 MySQL 与 H2 迁移定义比较，退出码 0、结果 `PASS (no diff)`，所有 SQL 均在 `schema_version` 登记，见 [schema 日志](schema-check-r8.log)。

前端 `npm run build` 在受限沙箱中因 esbuild `spawn EPERM` 首次失败；经工具权限批准重跑退出码 0，TypeScript 和 Vite 生产构建成功，见 [构建日志](frontend-build-r8.log)。`npm run lint` 退出码 0，0 error、11 条既有 warning，见 [lint 日志](frontend-lint-r8.log)。

后端 `mvn -pl st-api -am package -DskipTests -q` 退出码 0，生成当前 r8 聚合包，见 [打包日志](backend-package-r8.log)；测试并未因该命令而跳过，上述 471 项是在单独的测试命令中运行的。

`python st-web/scripts/team-watch-ui-smoke.py` 退出码 0，覆盖 375px 搜索迟到响应、游标过期恢复、关注失效脱敏与取消、目标定位、旧通知兼容及通知目标乱序。脚本通过 API fixture 验证浏览器交互，不代表部署级全链路；后端服务逻辑由上述测试分别覆盖。工作流引擎单人授权的限定范围测试及既有状态机用例 [全部通过](state-engine-r8.log)。
