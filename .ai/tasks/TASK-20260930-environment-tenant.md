# O02 租户默认路径核查

Task ID：TASK-20260930-environment-tenant。State：.ai/state/20260930-environment-remediation.yaml。
输入：本任务 design/requirement/testcases；旧 O02 仅问题线索。
include：st-auth/src/main/java/** 公开认证/上下文路径、st-auth/src/test/java/** 相应测试；st-core/src/main/java/** 定时扫描的上下文明确修复及对应测试；st-common/src/main/java/com/stcloud/common/context/** 及对应测试；本任务 tenant-* 与 dispatch 结果。
exclude：数据库、API 契约、其他业务规则、State、历史文档、Maven/Git、真实外部服务写入。
目标：列出正常登录/公开接口及定时扫描触发默认租户告警的实际路径。确认允许的默认入口显式设置/清理上下文；多租户扫描按已有显式租户选择，不把全局扫描隐式缩成租户1。保留真正遗漏告警，不修改角色或权限行为。优先给主线程报告，再在明确确认的缺陷上做最小修复并补线程复用/隔离用例。无明确缺陷则只交核查报告。不得降级全局日志或静默兜底。
返回中文独立结果与部分 IMPLEMENTED proposal；测试仅编写，由主线程串行运行。
