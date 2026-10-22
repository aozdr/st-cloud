# TASK-20261022-multi-node-ratelimit-01：限速多节点化与 Nginx 负载均衡

## 目标

把当前「进程内令牌桶」的限速改造为可多节点部署：本地桶负责 8KB 平滑，Redis 原子信用负责全局准入；
上传在途窗口与释放改为 Redis 幂等账本；限速配置跨节点收敛；Nginx 作为统一入口（中转上传亲和、
流式下载无缓冲、WebSocket 升级）。不新增数据库表，不改变对外 API 契约。

## 修改范围（include）

### st-common/ratelimit
- 新增 RateLimitProperties、CreditStore/CreditGrant/CreditLimit/LimitSupplier、RedisCreditStore（Lua 内联）、CreditBucket、RateLimitStats、RateLimitRedisConfig
- 改造 TokenBucket：睡眠移出临界区、支持信用模式、rate 变更重置
- 改造 UserTransferLimiter：模式选择、桶回收、上传窗口走 store、release 幂等
- 改造 SpeedLimitCache：ver（配置版本）与 pub/sub 失效

### st-admin
- SpeedLimitManageServiceImpl：写操作成功后发布失效消息

### st-core
- UploadServiceImpl：confirmChunk 按 uploadId+chunkIndex 幂等释放；中转会话惰性创建；relay 配置从 Redis 读取
- 新增 RelaySessionRegistry（Redis，TTL=sessionTimeout）
- RelayBufferManager：保持写盘逻辑不变，允许上层在会话缺失时创建
- DownloadServiceImpl：调用方式不变（内部切 CreditStore）
- 新增 HealthController（/api/health，permitAll）

### 配置与部署
- application.yml：stcloud.rate-limit.*、server.shutdown=graceful、multipart 上限对齐 nginx
- docker/nginx/nginx.conf + docker/nginx/conf.d/stcloud.conf
- docker/docker-compose.yml：nginx + app1/app2 示例（含健康检查、stop_grace_period）
- docker/.env.example、README 部署章节（多节点、S3 公网地址、OnlyOffice 回调地址）

### st-sync
- 同步变更推送改为 Redis pub/sub 扇出（DB 写入仍为单消费者），保证多节点 WS 都能推给本地会话

## 禁止修改范围（exclude）

- 不改 sys_rate_limit 表结构，不新增数据库迁移
- 不改 RBAC / JWT / 租户模型
- 不改上传下载状态机、S3 路径与分片协议
- 不改分享 / 团队 / 搜索业务逻辑
- 不引入 Redisson，不把逐字节限速改成请求级
- 不做 relay 外置存储（NFS / S3 staging）
- 无 UI 变更

## 验收标准

- mode=local：现有 UserTransferLimiterTest 全绿，单节点行为不变
- mode=redis：两实例共享 Redis，8 线程 x 5s，总发放 <= rate x 5 + 2 x quantum 且 >= 0.9 x rate x 5
- relay：nginx 两节点下同一 uploadId 的 chunk 全部落到同一节点；init 不再依赖本地会话
- 跨节点 confirm 能释放窗口；重复 confirm 不增加可签发数
- Redis 断开：传输不中断，降级指标可见，恢复后自动回到共享模式
- 管理端改 rate：两实例 1s 内（pub/sub）或 30s 内（TTL 兜底）生效
- nginx：下载不缓冲、Range 透传、WS 可升级、relay 请求不被重试到其他节点
- mvn 构建与 st-common/st-core 测试通过

## 验证命令

    mvn -pl st-common,st-core -am test -Dsurefire.failIfNoSpecifiedTests=false
    mvn -pl st-api -am package -DskipTests
    pwsh .ai/scripts/verify-loop.ps1 -StatePath .ai/state/20261022-multi-node-ratelimit.yaml

## 输出要求

- 设计：.ai/docs/20261022-multi-node-ratelimit/design.md
- 验证：.ai/docs/20261022-multi-node-ratelimit/verification.md（实现后）
- 评审：.ai/docs/20261022-multi-node-ratelimit/codereview.md（独立 reviewer）
- 核心逻辑（信用扣减、窗口幂等、降级）加中文注释
