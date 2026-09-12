# 第二轮 Review 修复测试报告

基线 `11ef6c2836af548c52052e5860511242741d9d1d`；以下均为本工作区 2026-09-12 的实际执行结果。阶段测试严格按 Phase 1→6 完成后才进入 Phase 7。

| 阶段 | 命令 / 验证 | 通过 | 失败 / 错误 | 结果 |
|---|---|---:|---:|---|
| 1 | `st-web: npm run test:hash` | 6 | 0 | Web/Desktop/Node 完整 MD5；0B、1KB、5MB、11MB、100MB+ 与同前缀不同尾部 |
| 1 | `st-web: npm run build`; `st-desktop: npm run lint`, `npm run build:main`, `npm test` | Desktop 17 | 0 | 全部通过 |
| 2 | `mvn -pl st-core -am '-Dtest=FileServiceFlowIntegrationTest,NewFileServiceIntegrationTest,UploadTransactionBoundaryTest,UploadStateMachineIntegrationTest,RelayUploadIntegrationTest,ArchiveExtractTransactionBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 55 | 0 | 通过；补充单类复核 11/0、3/0、5/0 |
| 3 | `mvn -pl st-core -am '-Dtest=UploadTransactionBoundaryTest,UploadStateMachineIntegrationTest,RelayUploadIntegrationTest,ConcurrentUploadIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 36 | 0 | 通过；第 501 条 chunk 注入失败证明整组 DB 回滚 |
| 4 | `mvn -pl st-core -am '-Dtest=UploadSessionConcurrencyIntegrationTest,RelayUploadIntegrationTest,UploadStateMachineIntegrationTest,UploadTransactionBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 38 | 0 | 通过；含 20 merge、20 abort、失败会话重试、中转超时竞争 |
| 5 | `mvn -pl st-core -am '-Dtest=RecycleBinPhysicalDeleteIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 5 | 0 | 通过；根筛选、父子重复、过期清理、配额与对象事件 |
| 6 | `mvn -pl st-core -am '-Dtest=ArchiveExtractTransactionBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 4 | 0 | 通过；2MB 输入在 1MB 限额前停止并删除临时 ZIP |
| 7 | `mvn -pl st-core -am test` | st-common 31；st-core 181 | 0 | 最新修补后的全量测试通过 |
| 7 | `mvn -pl st-team -am test` | st-common 31；st-auth 8；st-core 181；st-team 33 | st-team 2 失败、1 错误 | **未通过**；三个失败均来自 `TeamServicePermissionIntegrationTest` 调用不存在的 `PermissionRule.setSubjectId(Long)`。基线 DTO 已为 `String subjectId`，基线测试仍传 `Long`；本轮未修改 `st-team`。修补前后各执行一次，结果相同。 |
| 7 | `mvn -pl st-team -am -DskipTests compile` | 编译通过 | 0 | Team 生产代码编译通过，不能代替有测试模块的测试门禁 |
| 7 | `st-web: npm run build`、`npm run test:hash` | build；6 | 0 | 通过 |
| 7 | `st-desktop: npm run lint`、`npm run build:main`、`npm test` | typecheck；main bundle；17 | 0 | 通过 |
| 7 | `st-desktop: npm run build` | main 与 Web 子构建通过 | 安装包 1 | **未通过**：`electron-builder` 写入 `C:\Users\Administrator\AppData\Local\electron\Cache\electron-v31.7.7-win32-x64.zip` 时 `Access is denied`；未到安装包产物阶段 |
| 7 | `mvn -pl st-core -am '-Dtest=SchemaConsistencyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 3 | 0 | 通过 |
| 7 | `powershell -NoProfile -ExecutionPolicy Bypass -File .ai/scripts/compare-schema.ps1` | 15 张共有表字段一致 | 0 | 退出码 0，`PASS (no diff)`；无新增 schema/migration |
| 7 | `git diff --check` | 退出码 0 | 0 | 仅有 Git 提示未来 LF/CRLF 转换；无 whitespace 错误 |

审查回退修补：Phase 7 独立审查发现中转 CAS 中止后未清理节点/分片、普通入口可接收团队会话，以及 S3 complete 后 DB finalize 失败会停留 MERGING。定向修补后受影响测试 41/0；新增旧会话不得回滚新节点用例后 `RelayUploadIntegrationTest` 16/0；最新全量 `st-core` 181/0。

失败后复测：Phase 6 首次测试因测试容器未注册配置 Bean 而产生 4 个注入错误，调整夹具后同组 4/0；Phase 7 首次 `st-core` 全量 177 个测试中 1 个并发错误（竞争者读取 `MERGING` 返回通用业务码），修正为 `CONFLICT` 后定向 4/0、全量 177/0。审查修补后的首次 `st-core` 全量 181 个测试中 4 个上下文装配错误（新注入未出现在两个测试容器），改用已有 Mapper 后定向 4/0、全量 181/0。上述失败均未隐去。

用户后续明确允许跳过 PC 安装包构建，由用户自行打包；该项历史失败保留作记录，不再作为本轮阻塞。Team 基线测试在下文授权修正后已通过；历史失败记录不代表当前结果。整轮 State 验收仍按审查与证据门禁执行。

## 用户追加：Desktop 任务创建错误

上传和下载共用 createTask 的 INSERT 为 20 列、21 个占位符，现已改为 20 个。新增真实 sql.js 测试，验证上传/下载元数据、字符串 ID、持久化重载。最终 npm test 18 通过、0 失败；npm run lint、npm run build:main 均退出 0。首次接入测试脚本时工作目录路径重复，未写入 package.json；纠正路径后重新执行，以上为纠正后的实际结果。未修改数据库结构或用户 transfers.db。

## Team 基线测试修正后复测（2026-09-12 21:32）

用户授权后，仅将 TeamServicePermissionIntegrationTest 的请求夹具 subjectId 改为 String，实体 Long 字段与全部权限断言保持不变。执行 mvn -pl st-team -am test，退出码 0：st-common 31/0、st-auth 8/0、st-core 181/0、st-team 36/0，共 256 通过、0 失败、0 错误、0 跳过；权限集成测试 11/0。git diff --check 退出码 0。历史失败保留，Team 测试阻塞现已解除。原始日志：.ai/team-baseline-retest.log。
