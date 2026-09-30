# 团队回收站测试报告

执行者 workflow-manager。用户明确要求“不要开子线程，你自己独立完成”，本报告为同一执行者测试/自检。修订 team-recycle-code-8d9bce85490e655a，source-revision.json 保存5项源码/测试哈希与已运行完整后端JAR哈希。

| 测试 | 最终结果 | 证据 |
| --- | --- | --- |
| H2团队回收矩阵 | 18/18，失败/异常/跳过均0 | backend-xml/TEST-com.stcloud.core.service.impl.TeamRecycleBinIntegrationTest.xml |
| 本机MySQL团队回收矩阵 | 18/18，失败/异常/跳过均0；127.0.0.1:3306/stcloud_team_recycle_20260930，事务回滚 | backend-xml/TEST-com.stcloud.core.service.impl.TeamRecycleBinMysqlIntegrationTest.xml |
| 个人流程/权限/物理删除/结构 | 18+5+5+3通过 | 对应4份 backend-xml、backend-tests.log |
| st-api聚合生产构建 | exit0，BUILD SUCCESS | api-build.log |
| 独立本机库结构比对 | 两次exit0，无生产结构变化，H2补已有成员/外部表 | schema-before.log、schema-after.log、mysql-prepare.log |
| 完整真实后端启动 | 18082就绪，实际JWT/Redis14/隔离MQ生产和3消费者/专用RustFS桶/现有ES初始化成功 | api-runtime-final.log、dependency-health.log |
| 真实HTTP回归 | exit0；删除→回收→恢复、拥有者与空间管理员、普通编辑者403、不同owner子目录保留、个人兼容、永久删除通过 | http-test.py、http-test.log、http-requests.json |
| MQ与存储副作用 | 独立库75条Outbox状态1；32条同步变更；S3真实上传（首轮日志）与PHYSICAL_DELETE消费者成功删除对象；本脚本文件最终0个存活 | runtime-db-proof.log、api-runtime.log、api-runtime-final.log |

最终后端测试合计67条，全部成功。早期63条运行及HTTP脚本URL编码、响应VO无spaceId字段、mysql客户端默认3308、重复夹具默认配额用尽的调试失败不是最终通过证据；脚本已修正，最终从注册/空间创建开始全流程执行，退出码0。没有为这些脚本问题修改业务代码。最后增加2个纯测试场景只改变测试源码，运行JAR的生产源码与当前两生产文件一致。

用例核对：团队仅根置回收，正常子孙访问随祖先拒绝且恢复后放行；拥有者/管理员不受上传者限制；普通成员/失效/禁用/跨租户/外部关闭拒绝；角色或外部开关撤销后重新核对；正常但祖先回收的父目录也回退同空间根；有效不同owner父目录保留，其他空间重名不影响；同团队重名更新混合owner子孙路径；独立回收子目录保留；永久删除混合owner子树与重复ID配额正确，其他空间子节点保留；清空隔离到授权团队和个人；个人历史回归通过。

真实HTTP不使用受控API或mock组件。H2/本机MySQL服务矩阵则采用真实Mapper+事务，StorageService/ReliableEventPublisher等外部协作受控，两类证据明确区分。未执行浏览器/Electron/UI布局验收或长期并发压力；本次前端未修改。真实HTTP覆盖核心目录状态及权限，不把受控测试结果当作完整环境验证。共享stcloud库和现有8080后端均未修改/重启，测试数据留独立库用于审计；本脚本创建的文件已通过真实接口清理。
