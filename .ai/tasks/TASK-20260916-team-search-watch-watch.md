# TASK：文件关注与可靠变更提醒后端

- taskId：TASK-20260916-team-search-watch-watch
- 模型：GPT-5.6-Luna max；不得更换模型。
- State：.ai/state/20260916-team-search-watch.yaml；design revision：tsw-design-r1。
- 输入：.ai/docs/20260916-team-search-watch/{requirement,uispec,impact,architecture-review,design,testcases}.md。

## 目标
按 design §4～§6 实现关注 API、事务内捕获、持久投递队列、通知读取/跳转核权、数据库增量和测试，补齐所有成功文件写路径事件。不得删减目录/重订阅/失权/重试语义。

## 写入范围
- st-core/**（仅变更捕获、新事件、移动旧父ID、缺失的内容更新事件、对应测试和 H2 schema）。
- st-team/**（仅关注/通知服务、控制器、entity/mapper/任务及对应测试）。
- docker/mysql/init/43_file_watch.sql（编号若占用先报主线程）。
- st-sync/**（仅确认缺失的成功写事件需补齐时；不得重构同步引擎）。
- [planned-output] .ai/docs/20260916-team-search-watch/watch-changereport.md。
- .ai/runtime/results/DISPATCH-20260916-TSW-WATCH-A2.json。

禁止修改：st-team 的 TeamFileAccessPolicyImpl.java、FolderPermissionService.java 及上述授权测试（由搜索任务负责）；st-common、st-search、st-web、State、TASK、其他文档。不得 Git 提交/重置、删除业务数据、派生 Agent。若真实依赖要求扩范围，向主线程返回请求。

## 协作契约
搜索 Agent 实现 common.access.TeamFileAccessPolicy：boolean isActiveMember(Long tenantId,Long userId,Long spaceId) 与 boolean canView(Long tenantId,Long userId,Long spaceId,Long nodeId)。你可按接口使用，文件暂未到达不影响先编写；不能自行创建冲突版本。删除专用授权是你任务内部逻辑，严格按设计 fail closed。
API、SQL、notification 字段与本文档保持一致；匿名 actor 不伪装文件 owner。捕获加入文件事务，仅 SQL 不调用 Redis/S3/网络；通知 worker 同样使用 fresh DB 授权。

## 验证
编写 testcases W01～W14 所需实质测试；同步 H2 schema。不运行 Maven/共享缓存构建，不执行数据库迁移，由主线程串行运行 H2/SchemaConsistencyTest 与迁移前后对比和授权开发库迁移。报告静态检查及未执行项，不能声称测试已通过。每约60秒回报进度。

结果 by=/root/luna_watch_resume，criterion=IMPLEMENTED，revision=tsw-code-r1，附写入口事件覆盖表和 schema 影响。只返回 proposal，不改 State 或声明整体完成。

