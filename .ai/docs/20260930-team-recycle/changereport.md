# 团队文件夹回收站修复

根因：团队删除已设置回收态，但列表强制 personal scope，恢复又调用个人所有者入口。修改现有 core 回收站列表和核权，当前空间拥有者/role=0 管理员可处理团队回收项；恢复保持团队、目录和混合上传者子孙，父目录失效回到同空间根，重名按团队判断；永久删除完整团队子树且只扣团队配额，清空隔离到可管理范围。禁止永久删除正常节点。

只修改 TeamStorageMapper.java 和 RecycleBinServiceImpl.java 两个生产文件。H2 schema 镜像生产已有 team_member/team_external_config；新建 H2/本机 MySQL 各18条集成用例。用户指定自行完成，当前评审/测试/验收均为 workflow-manager 自检，无子线程。

验证：最终后端67条（18 H2团队+18真实本机MySQL团队+18个人流程+5个人权限+5物理删除+3结构）通过，fail/error/skipped均0。完整st-api构建通过；18082测试实例连接独立本机MySQL库、Redis14、隔离真实MQ、独立RustFS桶和现有ES，真实HTTP删除/列表/恢复、拥有者和管理员及编辑者拒绝、混合owner子目录、个人流程、永久删除均通过。真实S3上传/最终MQ消费者删除成功，75条Outbox已投递、32条同步日志；HTTP脚本创建的文件均已清理。两次schema对比exit0。各证据详见testreport.md。

兼容：接口地址及响应字段不变；团队项新增可见，个人规则保留；已经回收的团队节点无需数据迁移，重启现有后端即生效。未操作共享stcloud库的用户数据。原review整改任务保持暂停。
