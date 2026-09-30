# 本轮核对及定版边界
当前任务 TASK-20260930-TEST-FINISH；2026-09-30。
使用者、场景、权限与失败处理沿用 ../20260927-test-resume/requirement.md、uispec.md、design.md；原始136条见 ../20260924-code-review-testcases/testcases.md。
已核对源码与交接一致，无新业务方案或未决风险。旧 State 不作为流程输入，本轮重新 Evaluate。
影响：同步源缺失成功后的映射清理需最终回归；鉴权/团队/预览既有核心修复需要当前代码回归。剩余 DB-ID 用真实DDL、生产JSON配置/DTO、实际客户端与独立进程验证。浏览器与后端分层证据明确标注。
流程修复：schema 的 singleAgentAuthorization.criteria 增加 EXP_DESIGN，与协议与 loopctl reviewer 门禁一致；保留当前任务/actor/证据/依赖检查。用户授权本任务全部由主线程执行，自检不能称独立评审。
无数据库或 API 契约新增；不迁移生产/共享库；不提交或部署。
