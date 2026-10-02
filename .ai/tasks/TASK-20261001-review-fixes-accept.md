# TASK-20261001-review-fixes-accept

目标：独立对照 A1–A4 验收当前 review-fixes-code-r4 修复结果，不修改 Goal/State、不宣布整个 Loop 完成。

输入 requirement.md/design.md/testcases.md/changereport.md/source-manifest-r4.json、独立 codereview.md/security.md/testreport.md、knowledge-sync.md 以及 .ai/knowledge/review-fixes-auth-recycle.md。源代码只读。核对当前哈希、测试证据和 State 依赖；A1 多页协调、A2 旧请求/退出/网络/Electron 兼容、A3 墓碑回收与权限/正常文件保护、A4 无 API/DDL 漂移且回归/独立审查充分。边界：H2 和模拟外部事件，无本轮 MySQL/S3 实测，明确不能据此声称部署或生产删除。若边界符合本任务有限源码修复范围可通过；真实未完成需求 fail。

写入白名单 .ai/docs/20261001-review-fixes/acceptance.md、.ai/runtime/results/DISPATCH-review-fixes-accept-01.json。禁止其他文件、Maven/数据库/Git index/部署、禁止派发。完成中文八项结果与 criterionProposal id=ACCEPT、by canonical child identity、evidenceRef=.ai/runtime/results/DISPATCH-review-fixes-accept-01.json、validatedRevision=review-fixes-code-r4。


