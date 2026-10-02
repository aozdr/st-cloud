# O03 当前依赖运行保障（DISPATCH-env-ops-02）

## 背景

旧 attempt 因运行容量结束，留下配置和脚本但没有正式结果。本 attempt 独立读取实际文件并运行探针，只负责依赖配置与证据，不判定完整 Loop。历史“进程被杀/不健康”的故障时日志不可得，根因仍为 UNKNOWN，不能由本次启动的 OOMKilled=false 推断历史未发生 OOM。

## 输入

TASK-20260930-environment-ops、定版设计 env-design-r1、当前 State 最小修订信息及本机现有 Docker。Envelope 新 resultRef 覆盖 TASK 内旧 attempt 路径；不使用旧 attempt 自述作为通过证据。目标代码 proposal 修订为 env-code-r1，正式修订由主线程登记。

## 分析

2026-09-30 14:04 UTC Docker 29.7.2 读取到 8 个运行容器，全部没有 Config.Healthcheck。现有运行镜像和 Compose 固定镜像均由实际 image inspect 比对为同一 image ID，见 ops-environment-02.json 与 ops-dependencies-final.log。固定了 MySQL、Redis、RustFS、RocketMQ NameServer/Broker、Dashboard、Elasticsearch、OnlyOffice 当前 digest；没有拉取新镜像。自建 Elasticsearch 的 digest 只在本机可用，配置 pull_policy=never 并允许 STCLOUD_ELASTICSEARCH_IMAGE 覆盖；新机器仍需构建、核验插件并提供固定镜像。官方 Elasticsearch 基础镜像在本机没有独立缓存，未验证插件下载产物哈希或远程可复现性。

最终定义的容器内探针实际运行成功：MySQL SELECT 1；Redis PING；RustFS ready=true；NameServer 配置协议；Broker brokerActive=true；Dashboard HTTP；Elasticsearch 状态 green/yellow 且 timed_out=false；OnlyOffice healthcheck=true。ES 使用 256 MiB 堆，MQ 探针直接使用 64 MiB Java 工具调用，避免 mqadmin/tools.sh 额外固定申请 1 GiB 堆。真实运行版本来自固定镜像身份，当前日志不能证明历史内存峰值。

宿主机 MQ 协议读取确认 broker-a 的 8 条路由队列，实际访问 Broker minOffset。写探针以随机独立 topic 收到 SEND_OK，再按返回队列与 offset 拉取并逐字节核对 57 字节；没有提交用户消费位点。S3 使用实际 Compose 解析凭据完成签名 HEAD、随机对象 PUT/GET 及内容核对；对象 57 字节，SHA256 c59a30ea9757d21e1065155f5b5d5e35eb3df634ea90570ae2e5c499682a4536。测试消息和对象保留审计，名称见 ops-dependencies.log。本 attempt 写探针一次，最终配置只读复验一次，均退出 0。

## 决策

保留旧 attempt 的有效配置与探针，补 Get-DockerEvidence.ps1 只保存无凭据运行身份，补 ES timed_out=false 断言和按实际日期组织 S3 测试对象。Test-Dependencies.ps1 默认只读，可作为后端启动前检查；-WriteProbe 才创建随机专用测试资源。探针用容器实有工具和本机已有客户端缓存，不运行 Maven、不下载依赖。

没有重启、删除或重建任何容器。Broker 的 Mounts=[]，/home/rocketmq/store 当前约 3664 KiB；给旧容器新增卷或直接 compose up 会隐藏或丢失当前消息/位点，因此配置和 .env.example 明示保护边界，没有擅自修改存储挂载。持久化迁移需在授权的停机窗口备份、验证消息与位点后实施。

## State Delta（proposal）

仅提交本 TASK 的部分 IMPLEMENTED pass proposal，by=/root/ops_retry、validatedRevision=env-code-r1；没有写 State，没有把 IMPLEMENTED 或 O03 整体直接标为 done。自动 healthcheck 尚未进入旧容器，主线程须将此事实纳入集成结论。

## 风险

1. 历史故障根因没有原始时间线，仍未知。
2. 当前容器不会自动执行新增 healthcheck；现阶段由启动前脚本/手动检查提供当前可用证据。
3. Broker 无持久卷；直接重建不可接受。固定镜像及健康检查的自动启用仍需安全迁移窗口。
4. 自建 ES 固定 digest 为本地产物，远程仓库可用性及完整构建供应链复现未验证。

## 下一步

主线程合并结果并完成当前后端验证。后续容器更新应先解决 Broker 持久化迁移，再启用自动健康检查；保留当前容器和专用审计测试资源。本 TASK 不要求主线程立即重建运行容器。

## 变更影响

docker-compose.yml 固定当前镜像并新增功能 healthcheck；.env.example 解释 ES 本地固定镜像、检查入口和 Broker 保护边界；docker/scripts 下新增/保留可复用证据及功能探针。产品 API、数据库、用户资源未修改。无 Git/Maven 操作。

## 验证证据

- ops-environment-02.json：本 attempt 当前实际 Docker 身份、挂载和健康检查配置。
- ops-environment.json：旧 attempt 原始快照，仅作审计。
- ops-dependencies.log：8 个容器功能检查、MQ 实际收发、S3 签名读写；退出 0。
- ops-dependencies-final.log：最终 ES 超时断言与最终配置只读复验；退出 0。
