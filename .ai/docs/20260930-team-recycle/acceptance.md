# 团队回收站验收（自检）

当前修订 team-recycle-code-8d9bce85490e655a。执行者 workflow-manager；用户明确授权自己完成，未创建子线程，不称独立验收。

| Goal完成标准 | 结果与证据 |
| --- | --- |
| 团队回收根可见 | H2/MySQL删除后仅根回收列表可见；真实HTTP回收列表可见，http-test.log、两套团队XML |
| 拥有者管理员恢复保留团队及混合所有者子树 | H2/MySQL恢复、有效父目录保留、失效父回同空间根、重名更新混合owner路径；真实HTTP拥有者/管理员均恢复，数据库空间字段与子孙父ID断言通过 |
| 其他成员失效成员跨租户不可操作 | 普通节点owner、非成员、移除、过期、禁用、外部关闭、跨租户及权限撤销矩阵均无修改；真实编辑者restore/delete403；security.md、团队XML、http-requests.json |
| 团队永久删除清空隔离及配额正确 | H2/MySQL混合owner完整删除、团队退容量/个人不变、跨空间子节点保留、重复ID幂等、清空隔离通过；真实HTTP删除当前脚本树，S3最终真实MQ删除成功；runtime-db-proof.log、api-runtime-final.log |
| 个人回收站兼容 | 原有18流程+5权限+5物理删除回归通过，真实HTTP个人文件夹删除/恢复/永久删除通过 |
| 真实健康依赖下完整启动及HTTP回收恢复通过 | 实际18082完整st-api启动；Redis PONG、隔离MQ Broker注册及三消费者、ES200、独立RustFS桶初始化和真实上传/删除；http-test.log exit0、api-build.log exit0，结构两次比对exit0 |

源码/测试5项SHA-256与source-revision.json匹配，6份最终XML共67条且fail/error/skipped=0。CODE_REVIEW、SECURITY_REVIEW、TEST_PASS、KNOWLEDGE均为当前修订有效证据，State已按用户单人授权记录自检身份。接受本次修复完成；共享数据库没有写入，用户8080后端需重新启动加载新代码，已经回收的团队节点无需迁移即可显示。原Review整改任务保持暂停，不能把本任务验收当作原任务完成。
