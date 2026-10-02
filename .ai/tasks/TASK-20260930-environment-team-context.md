# 独立审查发现的 O02 团队异步/后台路径

目标：修复 TeamActivityHelper 异步租户错误、ExternalMemberExpireTask/FileLockExpireTask 默认只扫1与扫描后快照竞态。
include: st-team/src/main/java/com/stcloud/team/util/TeamActivityHelper.java、st-team/src/main/java/com/stcloud/team/task/ExternalMemberExpireTask.java、FileLockExpireTask.java；对应测试和必要TeamTestApplication注册；.ai/docs/20260930-environment-remediation/team-context-*；.ai/runtime/results/DISPATCH-env-team-context-01.json。
exclude: TeamServiceImpl、其他helper、core TaskRunner、迁移、State、历史、Git、Maven。不要实际启动调度/清理生产数据。
要求：捕获原始tenantId/mode，异步作用域精确finally恢复；activity显式tenantId；无租户调用保持真实遗漏诊断。两个任务复用core TenantTaskRunner按启用租户运行。文件锁按当前lockedBy/expireAt条件原子清理，已延期或重新锁定不受旧快照影响。外部成员删除需当前memberType/expireAt条件，且与空间回收权限同锁序（space→member）；如果需要新Mapper方法/事务边界白名单不够，先返回主线程最小patch建议而不越界。测试实证两个租户线程复用、延期/重锁后的旧扫描不清理；实现不改API/权限规则。
不运行Maven，主线程串行运行。中文部分IMPLEMENTED env-code-r2 proposal，by=/root/team_context。尽快交付最小实现与可运行测试。
