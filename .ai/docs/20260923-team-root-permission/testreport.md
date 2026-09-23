# 测试报告

- `mvn -pl st-team -am test '-Dtest=TeamServicePermissionIntegrationTest,FolderPermissionFreshTest,FolderPermissionServiceRuleTest,FolderPermissionServiceTest,TeamFileAccessPolicyTest,FileWatchAccessServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' -q`：修复复核问题后退出码 0，Surefire 六份报告合计 48 项，0 failure、0 error。
- `mvn -pl st-api -am package -DskipTests -q`：退出码 0，聚合后端构建通过；测试已由上面的独立命令执行。
- `git diff --check`：退出码 0。

测试使用 H2 与单元测试桩，未对运行中的 MySQL 或桌面客户端执行端到端重放；本次无表结构变化。
