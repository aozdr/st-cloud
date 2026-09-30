# Review 整改独立测试核对报告

执行者：`/root/test_verification`。Dispatch：`DISPATCH-20260930-REVIEW-TEST_PASS-b93b6e`。核对日期：2026-09-30（Asia/Shanghai）。当前代码修订：`review-fixes-code-3ae1050e4646f449`。

## 背景

核对本次 S1、F1–F8 整改的真实证据，并向主线程提出 TEST_PASS 建议。本报告不修改 Loop State，不判定最终 ACCEPT。主线程执行共享构建和运行测试；独立核对者读取代码、用例、日志与 XML，实际执行只读哈希、Envelope 字段和 XML 计数核对。

## 输入

本次 TASK、requirement.md、design.md、testcases.md、原始 review.md；当前 State 中本任务 revision、完成标准、评审依赖和 Dispatch 条目；本任务 baseline/hashes.json 与源码副本；source-revision.json；本目录 backend-regression.log、backend-xml、mysql-migration.log、schema-before.log、schema-after.log、mysql-verify.ps1、desktop-review.log、desktop-regression.log、desktop-types.log、web-build.log 及 browser-filmstrip 的脚本、日志和请求记录。

未将历史 State、原报告的通过结论或原任务授权作为本次通过依据。skillRefs 为 `-`，没有附加技能。

## 分析

### 修订和证据有效性

- Envelope 必填字段、未知顶层字段、schemaVersion、role、forbidSpawn 已用 PowerShell 检查，ACK 三元组与文件一致；其他结构按当前 dispatch.schema.json 人工核对。没有声称执行第三方 JSON Schema 库校验。
- source-revision.json 的 15 项 SHA-256 全部与当前文件匹配；8 项 baseline 副本均与其保存哈希匹配。sync-reconcile.ts 本次未改；其余 7 项 baseline 范围文件已变更。
- 后端最终 XML 10 套共 117 条，Failures=0、Errors=0、Skipped=0；对应 backend-regression.log 的 13（auth）+21（core）+83（team）和 BUILD SUCCESS。MySQL 两套 XML 明示本机独立测试库 URL，注册 3 条、团队 16 条没有跳过。
- 初次 backend-initial.log 只覆盖较早子集，不合并计数，也不以它替代最终修订证据。

### 已完成验证

| 验证 | 已核对结果 | 证据 |
| --- | --- | --- |
| AuthService / UserSecurity | 8+2 条通过 | backend-xml 下对应 XML、backend-regression.log |
| MySQL 注册并发 | 3 条通过 | TEST-com.stcloud.auth.RegisterMysqlConcurrencyIntegrationTest.xml |
| H2 SchemaConsistency / FileService | 3+18 条通过 | TEST-com.stcloud.core.schema.SchemaConsistencyTest.xml、TEST-com.stcloud.core.service.impl.FileServiceFlowIntegrationTest.xml |
| 团队 API / H2 并发 / MySQL 并发 / 服务 / 权限 | 23+16+16+9+19 条通过 | backend-xml 下五套 team XML |
| MySQL 迁移与 schema 对比 | 两次对比 exit=0；已有 security_version=17 保留；schema_version 登记 20260930.901 与 .902 | mysql-migration.log、schema-before.log、schema-after.log、mysql-verify.ps1 |
| 新增桌面失败路径 | 13/13，fail/cancelled/skipped=0 | desktop-review.log、sync-review.test.cjs |
| 桌面类型检查 | tsc --noEmit 日志；退出码由主线程运行记录补充 | desktop-types.log |
| Web 生产构建 | tsc -b、Vite 与 PWA 构建结束 | web-build.log |
| 分享胶卷浏览器验收 | 503、损坏图片、WebP/SVG 图标；正常图片、名称、click/Enter、分享 URL 变更重新请求；无 pageerror | browser-filmstrip.py、browser-filmstrip.log、browser-filmstrip-requests.json |

### 九项范围对照

| Finding | 核对事实 | 验证边界 |
| --- | --- | --- |
| S1 | 45 首次回放及再次执行成功；46 在已有列上两次成功；原值 17 保留；H2 结构测试与 MySQL 前后两次对比通过；版本登记包含主题、SQL、执行者和备注 | 46 在无列库上的分支与 45 相同，但没有单独清空列运行 46 的证据。schema 对比为 H2 的 19 张共有表列及迁移登记检查，MySQL 独有表作 INFO |
| F1 | 默认连接 RR；注册写事务运行断言 RC；管理员持租户锁撤权限后注册 JWT 和响应权限为空，版本为新用户 0；refresh 观察到数据库提交且不在事务内；禁用租户/角色写失败不创建有效用户或写 refresh | 使用生产 AuthService/Mapper/真实 MySQL/真实 JWT，Redis 受控；回滚测试断言抛错、无用户和无 refresh，未 spy JWT 方法调用次数；事务外签发由代码核对 |
| F2 | MySQL 16 项覆盖角色/ACL 提交或回滚及 direct/link/member/accept 两种锁順序；角色与成员/有效邀请最终引用数正确；H2 同矩阵和角色 API/权限回归通过；七个入口 RC 注解与锁后重读已核对 | 当前团队矩阵没有 SELECT 实际服务事务隔离级别；锁后接受邀请撤销/过期由重读代码和现有 H2 串行失效邀请用例覆盖，尚无等待锁期间撤销/过期的专项运行 |
| F3 | 生产 FileService+真实 H2 Mapper 对回收节点/回收祖先返回 2008；不同所有者明确断言 403；跨租户断言 BusinessException 拒绝。实际桌面引擎在受控 2008 下保留原字节、清映射并推进游标，403 保留原文件且不推进 | H2 FileService 与桌面受控 API 是分段契约组合，不是 HTTP 全链路；跨租户测试只证明拒绝，未断言 403。恢复节点后旧 DELETE 保护有代码，但尚无专项运行日志 |
| F4 | MOVE/RENAME × missing/preserved/rename 共 6 项；缺源或保全后用当前长度 2/MD5 下载，保全用户原件；普通 rename 保持旧本地 MD5，再 UPDATE 下载新内容 | 生产引擎/下载/恢复模块与真实临时文件；API、内存映射、watcher/timer 受控；普通目录移动的既有场景待完整桌面回归 |
| F5 | exclusions 是首个请求；被排除旧路径原字节/映射保留且未读该节点；排除获取失败不运行 reconcile/watcher/timer，旧游标和版本保留 | 新增用例仅一个历史排除路径；本地扫描函数受控，启动前顺序同时由 manager/engine 代码核对 |
| F6 | 成功目标已落地、旧源映射仍在时重放，只保留目标映射并推进游标，不再下载；旧路径属于其他节点时保留该映射 | 新增用例以内存映射构造持久化形态，未在此新增用例实际杀进程；跨进程回归另核对 |
| F7 | exclusions/reconcile 启动失败均显式抛出且 manager 缓存清理；无 watcher/timer；失败前游标/版本保留；恢复后的第二次 start 执行全量并启动资源 | 新增用例对 fullReconcile 的失败返回受控，成功调用真实模块；共享完整跨进程回归尚待结束 |
| F8 | 主线程真实 React/Chromium 受控 API 脚本断言缩略图失败图标可见、img 移除、按钮名保留、click/Enter 可选中新节点、换分享 URL 重新请求；Web 构建完成 | 未重跑浏览器；不是后端 HTTP 端到端。Tab/Space/左右键、同一挂载组件 updatedAt 变化、异步旧 error 及量化对比度没有专项运行证据 |

## 决策

暂不提出 TEST_PASS 通过。desktop-regression.log 的第二段完整测试没有结束汇总，主线程正在重跑；上述额外定版用例覆盖边界已反馈主线程。现有日志证明已完成测试均无失败，但不能把未结束进程计为通过。

## State Delta（proposal）

待主线程完成串行回归并提供退出码、最终日志和当前修订，再生成本 Dispatch 的 criterionProposal。未写 State，未把任何标准标为 done。

## 风险

本任务仅独立核对证据，不新增运行测试。共享 stcloud 开发库、生产迁移、真实 Redis/HTTP/S3、Electron 外壳、弱网设备与其他分辨率未执行。本机 MySQL 测试目标为 stcloud_review_fixes_20260930，不能把独立库验证解释为共享库已迁移。

## 下一步

主线程完成桌面完整回归，决定上述定版用例的补充运行；若源码/测试变化，更新修订及有效证据后再核对。主线程负责 Evaluate 和最终 ACCEPT。

## 变更影响

本子 Agent 只写白名单 testreport.md 及独立结果 JSON；没有修改源码、配置、数据库、Loop State，没有 Git 操作、共享构建或派生 child。
