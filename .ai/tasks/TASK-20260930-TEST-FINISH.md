# 136 条验收续接
目标：核实交接、补齐剩余 DB-ID 与同步证据，修复真实失败并完成相称回归及验收。
规模：large。主线程独占执行。
include：CR-01～07/DB-ID 对应测试、必要最小修复；.ai/docs/20260930-test-finish/；本 TASK/新 State；.ai/schema/loop-state.schema.json 中 EXP_DESIGN 单人授权枚举一致性修复及针对性校验。
exclude：历史 State/产物改写，生产/共享资源、部署、提交、推送、无关重构。
用户授权（本轮交接作为前置上下文）：不要开子线程或子 Agent，全部由当前主线程完成。
已定方案：20260925-test-remaining/resolution-plan.md、execution-plan.md；20260927-test-resume/design.md。不重新设计。
现状：127 PASS，4 条同步补强已有证据待核对，5 条 DB-ID 待补；LargeIdSchema 4 与 SchemaConsistency 3 已生成零失败报告，旧日志为空不能作为证据。
完成标准：136 条逐项全部完整可追溯；核心修复相称回归通过；UI 自检与安全自检如实记录；当前 State 合法验收；隔离资源清理。
流程修复依据：协议允许当前任务逐项单人授权，loopctl 对 reviewer EXP_DESIGN 要求授权，而 schema enum 遗漏该项。只补 enum，不降低依赖/证据/确认门禁。
`n搜索回归环境修复：NgramSearchIntegrationTest 默认硬连共享9200且删除固定索引；改为显式 test.es.port，任务专用ES创建/清理流水线，未配置时条件跳过，最终单独实际执行。无业务代码变化。
