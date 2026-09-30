# 未完成验收

包括 PARTIAL 与 NOT RUN，完整验收标准仍见原始用例。

- TC04-02 (PARTIAL)：SyncServiceProjectionTest：普通与排除路径双向投影、排除后代及相似前缀不误匹配通过；实际目录引擎不列举排除子树。同路径改名和 watcher 上传链尚未完整覆盖。
- TC04-14 (NOT RUN)：本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。
- TC04-15 (PARTIAL)：desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。
- TC04-16 (PARTIAL)：desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。
- TCDB-04 (PARTIAL)：SchemaConsistencyTest 3 项、auth/team H2 测试及 MySQL 列对比通过；未覆盖所有大 ID 存读子场景。
- TCDB-06 (PARTIAL)：独立 MySQL 8：大角色 9007199254740995 精确存储，回退 TINYINT 因越界失败且原值保留；旧 Integer 服务/客户端未演练。证据 db-chaos.log。
- TCDB-07 (NOT RUN)：本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。
- TCDB-11 (NOT RUN)：本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。
- TCDB-12 (NOT RUN)：本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。
