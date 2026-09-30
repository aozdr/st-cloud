# TASK：修复 worktree Review 的九项问题

- Task ID: TASK-20260930-REVIEW-FIXES
- State: .ai/state/20260930-review-fixes.json
- 输入：.ai/docs/20260930-review-fixes/design.md / testcases.md
- 执行者：workflow-manager；日期：2026-09-30

目标：按定版设计修复 S1、F1–F8，保留用户现有修改，以真实验证证据逐项验收。

include：st-auth 注册服务及必要测试；st-team 角色/成员/邀请事务及必要测试；st-core 文件详情及必要测试；st-desktop 同步引擎/manager及测试；st-web PreviewModal及胶卷降级验证；docker/mysql/init/45_user_security_version.sql及递增修复脚本；本任务 .ai/docs/20260930-review-fixes、.ai/state/20260930-review-fixes.json、.ai/tasks/TASK-20260930-REVIEW-*、.ai/runtime 中本任务独有文件；知识库与该行为直接相关的说明。

exclude：其他业务模块与需求；历史 State/报告；全局配置、共享数据库、生产数据、Git 提交/推送/部署及无需求重构。

验收：requirement.md 九项逐条通过，执行 testcases.md，代码/安全/体验与测试由独立任务返回证据，主线程判定完成。

输出：[planned-output] .ai/docs/20260930-review-fixes/changereport.md、codereview.md、security.md、testreport.md、acceptance.md。
