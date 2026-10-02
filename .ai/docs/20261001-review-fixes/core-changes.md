# 回收清理修复

本轮只移除 TeamStorageMapper.lockRecycleSpace 查询中的 deleted=0 条件，保留租户条件和 FOR UPDATE。RecycleBinServiceImpl 仅补充注释：空间墓碑用于系统清理加锁，成员权限仍由有效空间/成员查询决定。没有 API、DDL、保留期、普通文件删除策略或 S3/Outbox 生命周期变化。

新增三项真实 Mapper/H2 事务回归：软删除空间的过期回收目录（混合上传者）能清理、最后对象引用释放并触发一次物理删除事件、其他空间和个人配额不受影响且重复清理幂等；用户不能恢复/删除软删除空间的回收节点；系统不会清理正常状态节点。测试外部事件为 Mock，验证事件提交入口，不实际删除云端对象。

2026-10-01 19:52（Asia/Hong_Kong），主线程离线 Maven -pl st-core -am 指定以下测试，退出码 0 / BUILD SUCCESS：TeamRecycleBinIntegrationTest 21、SchemaConsistencyTest 3、TenantRecycleScanIntegrationTest 1、RecycleBinPurgeTenantTest 1，共 26，通过且无跳过。当前 surefire XML 是该次运行产物。

首次新增用例因对象表字段误写 file_md5/file_size 报错，改为现有 md5/size 后重跑全部四套通过。首次默认沙箱编译因依赖 JAR 访问限制失败，授权自动审核允许本机离线测试；未下载依赖、未迁移数据库、未连接共享开发或生产数据。

本轮未提供 TEST_TEAM_RECYCLE_MYSQL_URL/USER/PASSWORD，条件检查均为空，因此不运行专库 MySQL 矩阵。本轮无数据库结构变化。H2 测试不声明为真实 S3 删除或 MySQL 行锁证据。
