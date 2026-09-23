# 主线程直接评估测试用例

1. `REQ_ANALYSIS` 当前设计修订、有真实文档与相符 taskId/by 时，直接评估为 done 并记 direct 来源。
2. `CODE_REVIEW`、`SECURITY_REVIEW`、`EXP_ACCEPT`、`TEST_PASS`、`ACCEPT` 或 reviewer owner 标准拒绝直接通过。
3. 旧修订、缺失文件、错误 taskId、错误 actor、未完成依赖和重复事件不得新增伪通过。
4. 旧 dispatch Evaluate 保持原行为；现有有效 State 仍可 validate；无 dispatchId 也无 direct executionId 的 done 状态拒绝。
