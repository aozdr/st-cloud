# 验证记录：限速多节点化与 Nginx 负载均衡

> 归属 exitCriteria: VERIFIED

## 1. 变更摘要

### 新增
- `st-common/ratelimit`：RateLimitProperties、CreditStore、CreditGrant、RedisCreditStore（两段 Lua）、
  CreditBucket（跨节点信用本地缓存）、RateLimitStats、RateLimitRedisConfig（失效广播）。
- `st-core`：RelaySessionRegistry（中转配置跨节点登记）、HealthController（/api/health）。
- `st-sync`：SyncPushRedisConfig（变更推送跨节点扇出）。
- 部署：docker/Dockerfile、.dockerignore、docker/nginx/nginx.conf、docker/nginx/conf.d/stcloud.conf、
  docker/docker-compose.cluster.yml。

### 修改
- TokenBucket：睡眠移出临界区，保留单节点语义。
- UserTransferLimiter：mode=local/redis 双模式；上传窗口按 chunkKey 幂等释放；空闲桶回收；指标计数。
- SpeedLimitCache/Service/Result：配置版本（Redis INCR）+ 可选 Redis + `/api/transfer/speed-limit` 新增 version 字段。
- SpeedLimitManageServiceImpl：事务提交后递增版本并广播失效（不在事务内发 Redis 命令）。
- UploadServiceImpl：confirm 按 uploadId:chunkIndex 幂等释放；relay 惰性会话；finalize 缺缓冲返回 409。
- DownloadServiceImpl/UploadServiceImpl：把配置版本透传给限速器。
- SecurityConfig：放行 /api/health，actuator 收紧为 /actuator/health。
- application.yml：rate-limit 配置、优雅停机。
- SyncChangeMessageConsumer：Redis 广播推送，失败降级本地推送。
- README/.env.example：多节点部署说明。

## 2. 命令与结果

| 命令 | 结果 |
|------|------|
| `mvn test`（全部 11 模块） | exit 0；合计 854 用例，0 failure / 0 error，79 skipped，3 分 12 秒 |
| `mvn -pl st-common test -Dtest=UserTransferLimiterTest,CreditBucketTest,RedisCreditStoreTest -Dtest.redis.port=6379` | PASS：14 用例（限速器 8、CreditBucket 2、真实 Redis 4） |
| `mvn -q -pl st-api -am -DskipTests package` | PASS：产出 `st-api/target/st-api-1.0.0-SNAPSHOT.jar` |
| `docker compose -f docker/docker-compose.yml -f docker/docker-compose.cluster.yml config --quiet` | PASS（exit 0；仅有未设置变量与 version 过时告警） |

模块用例数：Common 43、Auth 38、Core 285、Search 77、Team 175、Preview 18、Share 113、Sync 45、Admin 55、API 5。

## 3. 维度检查

### tests（pass）
- 单节点行为不变：TokenBucket 重写后原有 6 个用例仍通过，新增 chunkKey 幂等用例。
- 多节点不超发：真实 Redis 下 4 线程并发 1 秒，总发放 <= rate + quantum + 20%。
- 版本收敛：旧 ver 返回 stale 且回读新 rate，不覆盖新配置。
- 回归：relay/上传状态机/事务边界/并发上传/限速管理/同步消费者全部通过。

### selfReview（pass）
- Lua 全部使用 Redis TIME 与毫字节整数，避免节点时钟与浮点截断。
- 窗口判定在扣令牌之前，拒绝不扣额度；ZADD score 由 Redis 端 nowMs + ttl 计算。
- 释放使用 ZREM member，重复 confirm 返回 false，不误伤其他分片。
- relay finalize 若需要新建缓冲（节点故障/超时）直接 409，避免丢未 flush 余量。
- 管理端版本递增与广播放在 afterCommit，避免回滚仍变更版本。
- Redis 故障 fail-open：下载走本地 rate，上传门控/confirm 回落本地窗口，relay 配置登记失败回落本地会话；
  均计数；failOpen=false 时才上抛。
- 配置版本 stale：信用桶通过 LimitSupplier 重新解析并按新速率重试，不再把 stale 当故障降级；
  下载循环每秒重新解析一次，长连接也能生效。

### security（pass）
- `/api/health` 仅返回进程状态，不含依赖细节；`/actuator/**` 收紧为 `/actuator/health`。
- relay 的 nginx 哈希键只用于路由，服务端仍按 uploadId 查询持久化会话并校验 owner/tenant/状态，不作为授权凭据。
- 预签名 URL 需要客户端可达的 S3 地址，已在 README 明确，避免把内网地址泄露给浏览器。
- X-Forwarded-* 仅由 nginx 注入；应用侧未新增信任该头做鉴权的逻辑。

### apiContract（additive）
- `SpeedLimitResult` 新增 `version` 字段，客户端可忽略；`/api/transfer/speed-limit` 语义不变。
- 新增 `/api/health`（公开只读）与 `/api/transfer/rate-limit/stats`（ADMIN）。
- 上传/下载既有接口、状态机、预签名协议未变。

### database（无变更）
- 未新增/修改表结构，未执行数据库迁移。

### knowledge（无稳定规则变化）
- 本次只增加部署与运维说明（README、design.md），未改变仓库级稳定规则，`.ai/knowledge` 无需更新。

## 4. 独立评审跟进（code-v2）

首次独立评审（`codereview.md`，DISPATCH-...-review-01）结论 REQUEST_CHANGES，阻塞 2 项；已修复：

| 编号 | 问题 | 修复 | 证据 |
|------|------|------|------|
| B-1 | 上传门控/释放/relay 配置直连 Redis 无降级；finalize finally 可把成功变失败 | fail-open 回落本地窗口；registry 全部 try/catch；finally 清理失败不影响结果 | `UserTransferLimiterTest#uploadGateFallsBackToLocalWhenStoreFails` |
| B-2 | stale 只降级不回读，在途下载永远按旧速率 | 新增 LimitSupplier，stale 时重新解析并重试；下载每秒刷新一次 | `CreditBucketTest#staleRefreshesLimitAndRetries` |
| H-1 | relay 会话丢失后可在空缓冲上完成合并 | chunk 新建会话且 seq>1 一律 409；finalize 新建会话也 409 | 代码审查 + 现有 relay 回归 |
| H-2 | upload-window=0 永久拒绝 | Lua `windowSize>0` 才判窗口；本地同步为不限 | `RedisCreditStoreTest#zeroWindowMeansUnlimited` |
| H-3 | 等版本写入回退 rate；relay 用冻结 rate 配新 ver | 仅 ver 更大时更新 rate，等版本 rate 不同按 stale；relay pacing 用当前 rate/ver | 代码审查 + Redis 版本用例 |
| M-1 | 版本与规则非原子读取 | 读库前后各读版本，不一致重载一次 | 代码审查 |
| M-2 | 本地模式提交前 evict | afterCommit 再 evict 一次 | 代码审查 |
| M-3 | 集群编排缺 S3/OnlyOffice 密钥 | compose 透传并改 `public-base-url=http://nginx` | `docker compose config --quiet` |
| M-5 | 生产 Redis 超时 60s | `spring.data.redis.timeout/connect-timeout=2s` | 配置审查 |
| M-6 | relay 配置 TTL 不续期 | 每个 chunk 成功后 refresh | 代码审查 |

## 5. 第二轮独立评审跟进（code-v3）

review-02 在 code-v2 上提出 2 个阻塞项，已修复：

| 编号 | 问题 | 修复 |
|------|------|------|
| B-1 | 共享桶接收请求级 rate（clientLimit 不同 → 同 ver 恒 stale → 无界重试/EVAL 风暴） | 共享桶只使用服务端 rate；`clientLimit` 改由本节点 `acquireClientLimit` 本地 pacing；stale 重试上限 2 次，刷新值未前进即退出 |
| B-2 | relay-finalize 先建空会话再 409，第二次 finalize 可在空缓冲上合并 | finalize 改为只读探测 `hasSession`，缺失直接 409，不再创建会话 |
| H-2 | stale 无刷新回调时直接返回导致 simpleUpload 不限速 | `pacedInputStream` 传入 LimitSupplier |
| M-1 | afterCommit 中 evict 早于版本递增 | 改为「INCR → evict → 广播」 |

补充说明：门控与 pacing 仍共用 `rl:{t:u}:up`，两者都使用服务端 rate/ver 后不会互相制造 stale；
共用会重复扣减（更严格，不会突破上限），保持现状。

## 6. 未执行与剩余风险

- 未在真实两节点 + Nginx 环境做端到端压测（本环境只验证了配置可解析与单节点/真实 Redis 用例）。
- 未构建 Docker 镜像（`docker build` 需要联网拉取基础镜像，耗时较长）。
- 分享流固定 5MB/s、预签名下载直连绕过用户桶属于设计文档明确的非目标，本次未处理。
- Redis 故障期间最坏为 N x rate，已有 `rate-limit/stats` 指标可用于监控。
- 全量测试 78 个跳过用例为原有条件执行（Redis/环境 gated），与本次改动无关。
## 7. 最终修订（code-v3）

review-02 的两个阻塞修复后重新执行全量测试：`mvn -q test` exit 0（854 用例，0 failure/0 error）。
本修订同时把管理端失效顺序调整为「INCR → evict → 广播」。
## 8. 第三轮复审（code-v3）

review-03 结论 APPROVE_WITH_CHANGES，CODE_REVIEW=pass，0 阻塞。两个原阻塞项复核通过：

- 共享桶只使用服务端 rate；clientLimit 由本地 acquireClientLimit 执行；stale 最多 3 次 EVAL，
  刷新值未前进即退出。
- relay finalize 只读探测 hasSession，缺失直接 409；chunk 新建会话且 seq>1 在读 body 前 409。

书面接受的非阻塞残留：

- R-1：客户端在 chunk 409 后仍调用 finalize 时，理论上可在已创建的空会话上完成合并；
  仓库内 web 客户端不会走该路径，后续补丁为抛 409 前 cleanup。
- R-2：resolve() 多次 getEntry 在 TTL 边界可能产生「新 ver + 旧 rate」，由有界 stale 重试兜底。
- R-3：stale 用尽时该块放行且未计入 degraded，后续可改为置 5s 本地降级。
- R-4/R-5：CreditBucket 同值退出、redis 模式 relay 409 的自动化用例缺口。
