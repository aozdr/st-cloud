# TASK-20260924-IMPLEMENT-CR-04

目标：按 .ai/docs/20260924-code-review-design/CR-04/requirement.md 与 design.md 实现 CR-04，维持最小必要变更。
当前用户指令：先不写测试用例，先根据需求和程序设计文档编码；不派发子 Agent。
规模：大型整改中的单项实现任务。
include：st-sync,st-desktop，以及 .ai/docs/20260924-code-review-design/CR-04/ 下实施记录。
exclude：其他 CR 无关文件、历史协议、生产数据删除、无关重构。
接口或数据库变化：以分项 design.md 为唯一实现约束，接口兼容和增量迁移必须同步完成。
验收：对应 requirement.md 的 A 编号；不将文档完成等同功能验收。
验证：本轮不新增测试用例；运行受影响模块已有测试、编译/前端构建及必要静态检查，如环境不可用记录 NOT RUN。
State：.ai/state/20260924-code-review-design.yaml；本实现 TASK 基于用户新指令创建，替代此前文档专用 TASK 的编码范围。
