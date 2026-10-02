# 本地开发环境核对基线

本次 task 20260930-environment-remediation；执行从9月30日持续，客户端日期现为2026-10-01。此记录只描述实测本机，不代表生产部署。

实际 MySQL 位于Docker，版本8.0.46，库stcloud，hostname dd39e994c2df。旧库停在20260912迁移；当前缺少的42～46已执行并登记20260930.1，新增47补GC索引幂等。旧09b/32～37按实际结构和权限数据审计补记，未重放数据迁移。结构必须用compare-schema的真实MySQL比对，H2不能替代。无mysql CLI可传JdbcClasspath及JavaPath，凭据只用MYSQL_PWD/STCLOUD_TEST_MYSQL_PASSWORD环境变量。

Maven缓存和wrapper已安装，但mvn未入PATH；初始离线打包缺插件依赖，首次在线补齐后可离线构建。Docker CLI实际在用户AppData/Local/Programs/DockerDesktop/resources/bin，不在Program Files默认目录；读不到进程/WSL不等于依赖未安装。

开发MySQL连接/用户/密码、JWT主密钥和S3凭据均接受环境变量；生产JWT和游标签名密钥仍要求稳定专用值。容器镜像按实装digest固定。Docker/scripts/Test-Dependencies.ps1提供启动前真实功能检查，WriteProbe使用随机专用MQ/S3资源并保留审计；检查日志不得暴露Compose渲染凭据。

Broker现有store未持久卷，禁止盲目compose up重建导致容器层消息历史丢失。新增healthcheck已在现装容器手动实测，自动探针需受控更新容器；先备份/迁移Broker store。ES镜像为本机自建digest，远程恢复要按Docker/elasticsearch Dockerfile重新构建核验并设置环境变量，不把本机digest冒充可拉取镜像。历史137/OOM根因不能从当前OOM=false回推。

认证协调位于桌面api-client和Web auth-session；完整pair单个持久记录，旧会话/换服响应不能影响新会话。后端刷新用Redis轮换一次性refresh，旧值业务码1005拒绝。实际Electron窗口/本地Storage故障与headless Node桥接验证有不同边界。

后台按启用租户运行，PRIVATE单次；禁用租户保留数据。异步日志捕获tenantId/mode，线程结束精确恢复。公开兼容入口显式租户1，非默认租户匿名分享契约未扩展，真正缺上下文仍告警。

回收写入和团队撤权共用team_space→node锁序，READ_COMMITTED下等待后重读权限/状态。提交后Outbox处理物理删除，S3不能进入事务。桌面全量目录pages需兼容后端Long十进制字符串但拒绝损坏/不安全计数；根映射属于扫描结果，重复斜杠同节点别名只清映射，保留原字节。

历史审计资源保留，O05没有清理授权。本轮独立库/测试账号/文件/随机MQ与S3探针保留审计；令牌持久夹具在验收后必须脱敏，不能作为源码归档。

Web默认根资源base=/，Electron build:web使用--mode desktop保持./；全局相对base会令BrowserRouter深层刷新请求/team/assets而失败，与S3故障不同。最终8080 PID10028及不可变JAR身份记录于本任务backend-running-final.json。当前真实PNG上传、原图字节、缩略图签名GET/Chrome解码和MQ事件均已验证；仅当前采集窗口正常链路租户告警0，不推断未来所有任务。

测试团队不要沿用默认10GiB占满逻辑分配。此次两个0B无文件的EXP fixture按精确归属和CAS缩为各1MiB，恢复约20GiB可分配容量并保留审计资源；禁止据此调整其他团队。独立MySQL测试库须复制结构和所需默认租户/权限配置，不能用空配置库的注册失败断言产品回归；旧失败批次保留。
