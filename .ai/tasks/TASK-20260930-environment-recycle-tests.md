# V06 当前并发专项

目标：补团队管理员撤权与恢复/永久删除、恢复与永久删除竞争的真实 MySQL 可控交错用例。
include: st-core/src/test/java/com/stcloud/core/service/impl/TeamRecycle*Test.java；.ai/docs/20260930-environment-remediation/recycle-*；.ai/runtime/results/DISPATCH-env-recycle-01.json。
exclude: 产品实现源码、State、数据库迁移、共享数据、历史文档、Git、Maven。主线程串行运行Maven。
可创建独立测试类，MySQL URL显式门禁仅 stcloud_team_recycle_20260930_env；凭据环境变量不记录。两连接实际锁等待，分别确保锁顺序/权威检查时点；断言最终权限状态、节点、配额、outbox、物理删除调用（单元受控边界明确）。现有TeamRecycleBinIntegrationTest与Mysql测试仅参考。遇到实现缺陷报告主线程，不越界修改实现。不要并行改其他测试。
输出中文TESTCASES部分proposal env-design-r1，by=/root/recycle_tests；真实运行待主线程，不能声称通过。
