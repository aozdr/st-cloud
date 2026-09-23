# 集成验证记录（进行中）

执行者：GPT-6 主线程；应用及测试代码由 GPT-5.6 Luna max 编写。当前 revision：tsw-code-r1，尚未通过 TEST_PASS 或最终验收。

| 验证 | 结果 | 证据 |
|---|---|---|
| 团队搜索及既有搜索回归 | 57 个测试通过，含 2 个真实 Elasticsearch 集成测试 | search-tests.log |
| core H2 与 SchemaConsistencyTest | 187 个测试通过 | core-schema-tests.log |
| 初始团队与同步回归 | team 47、sync 14 通过；早于最新关注修复，不能替代最终回归 | team-sync-tests-initial.log |
| 前端 TypeScript 与生产构建 | 退出码 0 | frontend-build.log |
| 前端 Lint | 退出码 0 | frontend-lint.log |
| 前端哈希契约 | 6 个测试通过 | frontend-hash.log |
| 关注链路测试 | 首次 testCompile 有 4 处错误，Luna 正在完成修复后的复验准备 | watch-tests.log |
| MySQL 迁移前对比 | 退出码 1；仅新增 2 张关注表、通知 4 个字段与 SQL43 未登记的预期差异 | schema-before-r2.log |

当前未执行 SQL43 迁移；须在关注 H2 通过后执行本地开发库迁移、登记唯一版本并完成迁移后对比。尚未进行浏览器功能验收、375px 体验验收和独立代码/安全审查。

搜索补充测试派发 DISPATCH-20260921-TSW-SEARCH-A2 被 Runtime 拒绝（agent thread limit reached），没有创建 child，不计入已实现或已验证证据。

## 关注测试第二次执行

`watch-tests-r2.log`：退出码 1。core SchemaConsistencyTest 3 项通过；team 35 项中 1 失败、5 错误。4 处编译错误已经消除，当前失败涉及 SysUser Lambda 元数据、不可变集合、Mockito 参数匹配、租户上下文与撤权夹具。已创建 WATCH-A3 和 RELIABILITY-A3，由 Luna max 定向修复；SEARCH-A3 仍因运行时线程限制未创建。

## 关注测试第三次执行

`watch-tests-r3.log`：退出码 1。8 个 FileWatchReliabilityIntegrationTest 全部通过；team 共 35 项，仅 FileWatchAccessServiceTest 的参数检查失败。撤权失败已证实为夹具未真实删除成员，改用 deleteById 后保持抑制与无通知断言通过。剩余 MyBatis 元数据测试初始化交给 WATCH-A4，尚未满足迁移前关注测试门禁。

## 关注测试与数据库门禁完成

- `watch-tests-r4.log`：退出码 0，team 35 项全部通过（包括 8 项可靠性集成测试），core SchemaConsistencyTest 3 项通过。
- `backend-package.log`：全模块 `mvn -pl st-api -am package -DskipTests` 退出码 0；这是打包证据，不代替测试。
- `schema-before-r2.log`：迁移前对比退出码 1，差异仅属于 SQL43。
- 本地开发 MySQL `127.0.0.1:3306/stcloud` 已应用 `43_file_watch.sql`；版本 `20260921.1` 已登记。SQL SHA256：`3DB0C803457363189DF9E1945943C2FE78224C4F79424B62025A9D03E731B99E`。
- 初次迁移的管道编码导致中文备注乱码、版本登记失败；表结构已成功建立，没有重复执行 ALTER ADD。随后明确 UTF-8 输入恢复 SQL43 原备注并成功登记版本。证据：`schema-migration.log`、`schema-version-registration.log`、`schema-comments-repair.log`。
- `schema-after.log`：迁移后对比退出码 0，全部 SQL 已登记，无字段差异。
- 搜索边界补测 SEARCH-A6 已成功派发给 Luna max，之前失败 attempt 不计作证据。
- 为本地联调启动 API，游标密钥仅注入进程环境且不落盘；正式部署仍需配置稳定的 STCLOUD_SEARCH_TEAM_CURSOR_SECRET。

## 浏览器联调（2026-09-21 晚间）

通过 CUA 操作本地 `127.0.0.1:5173` 与本地 API，使用项目已有开发管理员登录。

- 我的关注空状态正确加载。
- 在既有个人空文件夹 `test_folder` 的详情页关注，控件变为“已关注”，提示保存成功；列表显示 1 项及当前路径。
- 将视口设置为 375×812，检查截图：标题、记录、取消按钮及移动导航清晰，无横向溢出；DOM innerWidth 与 scrollWidth 均为 375。
- 列表记录通过键盘 Enter 打开正确的 test_folder，面包屑及空目录状态正确。
- 返回列表取消关注，显示“已取消关注”且恢复 0 项。测试订阅已撤销，文件内容没有改动。
- 已恢复默认视口。团队搜索与通知真实页面验收仍在进行中，此段不代表整体 EXP_ACCEPT。

## 浏览器发现的新建回归（未关闭）

团队 test（spaceId 2083074660910899202）的搜索入口正确带入 scope/spaceId，查询123响应0项。随后尝试创建独立目录 codex-tsw-20260921-check 以验证新索引，真实接口报错：FileWatchCaptureListener 捕获事件缺少租户；后端日志 backend-runtime.log 2828/2999 行。页面报告创建失败，事务应回滚，待Luna修复并复验。WATCH-A5 负责可信租户快照与真实发布入口测试；不得据此前H2通过判定整体实现完成。
