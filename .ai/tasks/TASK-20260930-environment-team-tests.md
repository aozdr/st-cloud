# V02 团队真实事务并发验证

Task ID: TASK-20260930-environment-team-tests。
State: .ai/state/20260930-environment-remediation.yaml。
输入：本任务 design.md、testcases.md、requirement.md，当前 TeamRoleMysqlConcurrencyIntegrationTest 与服务源码。
include：st-team/src/test/java/** 的 V02 专项；.ai/docs/20260930-environment-remediation/team-*；本 dispatch 结果。
exclude：生产代码、其他模块、State、历史文档、数据库写入/迁移、Maven/Git 操作。
目标：审视现有真实 MySQL 测试，补实际 Spring 服务事务内的隔离级别断言、等待空间锁期间邀请撤销/过期、角色删除与成员/邀请两种顺序。测试使用独立库环境变量，锁控制确定、连接/线程能释放。提供主线程运行命令，主线程统一执行 Maven。
若已有覆盖直接说明具体证据，不写镜像实现测试。不得调用外网查软件版本。返回中文正式结果与 TESTCASES proposal，仅表示专项用例部分。
