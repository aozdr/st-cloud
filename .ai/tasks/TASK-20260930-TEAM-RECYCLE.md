# TASK-20260930-TEAM-RECYCLE

目标：修复团队文件夹删除后现有回收站不可见、不可恢复的问题。规模 medium，单一 st-core 回收站模块，权限规则已经用户确认：空间拥有者和管理员可查看、恢复。

include：st-core/src/main/java/com/stcloud/core/mapper/TeamStorageMapper.java；st-core/src/main/java/com/stcloud/core/service/impl/RecycleBinServiceImpl.java；st-core/src/test/resources/schema.sql；新增 st-core/src/test/java/com/stcloud/core/service/impl/TeamRecycleBinIntegrationTest.java 和 TeamRecycleBinMysqlIntegrationTest.java；.ai/docs/20260930-team-recycle/**；.ai/knowledge/api-reference.md、business-domain.md。State 仅主线程可修改。

exclude：已有用户改动、其他模块业务源码、前端布局、生产数据库结构/数据、历史整改任务 State/产物、数据库共享 stcloud 的测试写入。旧整改任务保持暂停。

实施前 design.md、testcases.md 定版；共享构建与真实 HTTP 集成验证由主线程串行执行。独立评审/测试/验收仅写各自 TASK 白名单。

完成标准：团队回收根可见；拥有者/管理员可恢复并保留空间和混合所有者子树；其他成员/失效成员/跨租户不可查看操作；团队永久删除和清空只影响授权空间并按团队配额释放；个人回收站兼容；真实本机 MySQL 和正常 Docker 依赖下完整后端启动及 HTTP 删除→列表→恢复通过。
