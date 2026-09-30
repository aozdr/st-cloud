# 修复回归报告

原 10 项 desktop-probes 全部通过，TC04-30 由失败转为通过。npm test 的既有 19 项与新增首批 10 项通过；最终 11 项恢复测试全部通过，无跳过，含实际 C/E 跨卷验证。tsc --noEmit 与 build:main 均退出 0。

mvn -q test -DskipITs 退出 0，10 模块合计 483 项，失败、错误、跳过均为 0。明细 java-counts.json，执行日志 maven-full.log；st-api 定向测试日志 maven-targeted.log。

此前未执行的 105 条和部分覆盖的 26 条仍未计通过。完整应用启动与真实 ES 端到端测试未执行。
