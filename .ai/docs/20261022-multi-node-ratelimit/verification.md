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

## 9. code-v4 实现（R-1/R-2/R-3 + R-4/R-5/T-12）

### 9.1 R-1：relay 缺会话不再创建空缓冲

- `UploadServiceImpl.ensureRelaySession(session, seq)`：本地会话缺失且 `seq > 1` 时**在创建之前**直接 409，
  不产生临时文件/内存会话；只有 `seq == 1` 才按 `relay:cfg` 惰性创建。旧实现先 create 再判 seq，
  409 分支留下的空会话会被后续 finalize 的 `hasSession` 命中并在空缓冲上合并（R-1）。
- 回归：`RelayUploadIntegrationTest#tc016`（mock RelaySessionRegistry：init 只登记 → 首片惰性创建 →
  模拟会话丢失 → seq=2 409 → 断言无本地会话/无临时文件 → finalize 409 → 未调用 completeMultipartUpload）。

### 9.2 R-2：限速规则与版本单次快照

- `SpeedLimitCache.snapshot(userId, roles)` 在一次 `getEntry()` 内同时返回用户规则、命中角色规则与 version；
  `SpeedLimitService.resolve()` 只调用该快照，消除 TTL 边界「新 ver + 旧 rate」的跨读窗口。
- 回归：`SpeedLimitServiceTest` 2 例（只读一次快照并取最严格值；匿名不访问缓存）。

### 9.3 R-3：stale 耗尽改为 5s 本地降级

- `CreditBucket`：连续 stale > 2、refresh 返回 null/异常、刷新值未前进三种情况统一
  `degrade()`（5s 本地按 rate 供给 + `degraded` 指标），不再直接 return 放行。
- 回归：`CreditBucketTest` 新增 3 例（同值 stale 只 1 次 EVAL 且记 degraded；重试耗尽 3 次 EVAL 后降级；
  denied 等待期中断立即退出并保留中断标记）。
- R-6 复核：`getChunkUrlInternal` 的 `clientLimit` 已重新参与本地门控（`tryAcquireClientLimit`），评审文档 R-6 关闭。

### 9.4 验收口径的双实例并发用例（T-12）

`RedisCreditStoreTest#twoInstancesEightThreadsRespectRateForFiveSeconds`：两个独立 RedisCreditStore 实例
（模拟两节点）共享同一 Redis key，8 线程（每实例 4）持续 5s：

    twoInstancesEightThreads: total=5000000 bytes, elapsed=5001ms, upper=5201000, lower=4500000

总发放 5,000,000 B <= rate(1,000,000 B/s) x 5.001s + 2 x quantum(100,000) = 5,201,000，
且 >= 0.9 x rate x 5s = 4,500,000，满足 TASK 验收。

### 9.5 部署与文档修复

- `docker/Dockerfile.web`：健康检查由 wget 改为 curl。`nginx:latest`（Debian trixie）不再自带 wget，
  实测 nginx 容器 FailingStreak=89、状态 unhealthy；重建后 healthy。
- `docker/nginx/nginx.conf`：access log 增加 `upstream=$upstream_addr`，作为 relay 粘滞/节点分布的运行证据。
- `application.yml`：`storage.endpoint` 支持 `STCLOUD_STORAGE_ENDPOINT`，新增
  `storage.public-endpoint`←`STCLOUD_STORAGE_PUBLIC_ENDPOINT`；README/.env.example 说明内网读写与预签名拆分、
  compose 别名 `STCLOUD_PUBLIC_S3_ENDPOINT`，以及不要使用 host.docker.internal。

## 10. code-v4 命令与结果

| 命令 | 结果 |
|------|------|
| `mvn -q -pl st-common test -Dtest=SpeedLimitServiceTest,CreditBucketTest,UserTransferLimiterTest` | exit 0；SpeedLimitServiceTest 2、CreditBucketTest 5、UserTransferLimiterTest 9，全 pass |
| `mvn -q -pl st-core -am test -Dtest=RelayUploadIntegrationTest` | exit 0；19 用例 0 failure/0 error（含 tc016） |
| `mvn -q -pl st-common test -Dtest=RedisCreditStoreTest -Dtest.redis.port=6379` | exit 0；5 用例全 pass，含 8 线程 x 5s 双实例用例（数值见 9.4） |
| `docker build -f docker/Dockerfile -t stcloud-app:local .` + compose 重建 app1/app2 | 两节点 healthy |
| `docker build -f docker/Dockerfile.web -t stcloud-web:local .` + 重建 nginx | nginx healthy，access log 含 upstream |
| `mvn -q test`（11 模块） | 全量结果见 §11（执行后回填） |

## 11. 两节点 + Nginx 端到端证据（code-v4 部署）

拓扑：`docker-app1-1`=172.18.0.11、`docker-app2-1`=172.18.0.10、`docker-nginx-1` 监听 80；
`STCLOUD_RATE_LIMIT_MODE=redis`；`STCLOUD_STORAGE_ENDPOINT=http://rustfs:9000`、
`STCLOUD_STORAGE_PUBLIC_ENDPOINT=http://192.168.2.5:9000`。

### 11.1 管理端改限速：两节点 1s 内收敛

在 nginx 容器内对 app1 发起 PUT `/api/admin/speed-limit/2113247739617079298`，随后直接查询两节点：

    before app1="version":"15" app2="version":"15"
    put_http=200
    after_400ms app1={...uploadSpeedLimit:16384, downloadSpeedLimit:20480, version:"16"}
                app2={...uploadSpeedLimit:16384, downloadSpeedLimit:20480, version:"16"}
    after_1000ms 同上

结论：400ms 内两节点均拿到新 rules + 新 version（pub/sub 失效生效），远小于 1s 验收线。
测试结束已把原规则恢复为 upload=0 / download=51200（version=20），两节点一致。

### 11.2 relay 按 uploadId 粘滞

经 Nginx 发送 `POST /api/file/upload/relay-chunk?uploadId=...`（未鉴权仅用于路由观察），读取 access log：

- AFFINITY-A / AFFINITY-B / AFFINITY-C 各 3 次，全部 `upstream=172.18.0.10:8080`（app2）；
- AFF-0..9 十个不同 uploadId 分布到 app1（AFF-1/2/6）与 app2（其余），同一 id 单节点。

结论：同一 uploadId 恒定命中同一节点（一致性哈希），不同 id 可分散到两节点。

### 11.3 跨节点 confirm 幂等释放

upload=16MB/s、下载规则不变，直传 10MB/2 片；窗口 key `rl:{1:1}:win` ZCARD：

| 步骤 | 请求节点 | 结果 | ZCARD |
|------|----------|------|-------|
| 初始 | - | - | 0 |
| chunk-url index=1 | app1 | allowed=true | 1 |
| chunk-url index=2 | app1 | allowed=false, retryAfterMs=500 | 1 |
| confirm index=1（首次） | app2 | code=200 | 0 |
| chunk-url index=2 重试 | app1 | allowed=true | 1 |
| confirm index=1（重复） | app2 | code=200 | 1 |

结论：跨节点 confirm 能释放共享窗口；重复 confirm 不重复释放（不增加可签发数）。

### 11.4 R-1 现场验证：缺会话 409 且不产生空会话

upload=4MB/s（< 5MB 分片）→ init 返回 relay、relayChunkSize=1048576；随后经 Nginx：

- 首次 upload 直接发 `seq=2`：`{"code":409,"message":"中转会话已丢失，请重新上传"}`
  （修复前会先建空会话再 409，留下可被 finalize 合并的空缓冲）；
- `relay-finalize` 连续两次：均 409（只读探测未命中会话，未进入 merge）。

### 11.5 relay 正常链路（含 pacing 与分片合并）

同一 relay 上传，1MB/片、seq=1..10，设置 `Content-Type: application/octet-stream`：

    seq=1..4  part=0   t=0.29/0.54/0.79/1.04s
    seq=5     part=1 uploaded=true  t=1.35s
    seq=6..9  part=0
    seq=10    part=2 uploaded=true  t=2.63s
    finalize: code=200，fileSize=10485760，name=e2e-relay-ok3.bin

10MB/2.63s ≈ 4MB/s，与服务端 4096KB/s 规则一致；跨节点粘滞 + 惰性会话 + 分片上传/合并全链路通过。
（首次测试用 curl 默认 `application/x-www-form-urlencoded`，Tomcat 提前解析表单导致 body 为空而 finalize 失败；
客户端 web/desktop 均显式发送 octet-stream，见 `useUpload.tsx` 与 `upload-manager.ts`，不是产品缺陷。）

### 11.6 Nginx 流式下载：Range 与不缓冲

`GET /api/file/2113247935864369154/stream`：

- Range 10MB：HTTP 206、`Content-Range: bytes 0-10485759/1671257357`、`Content-Length: 10485760`、
  TTFB=0.069s、total=0.572s、upstream=app2 —— 首字节远早于整包完成，说明未整体缓冲。
- Range 40MB（20480KB/s 规则）：HTTP 206、41,943,040B、2.066s、20.3MB/s、TTFB=0.040s。

### 11.7 WebSocket 升级

`GET /api/sync/ws`（Authorization: Bearer）：

    HTTP/1.1 101
    Connection: upgrade
    Upgrade: websocket
    Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
    upstream=172.18.0.10:8080

### 11.8 预签名 URL 走客户端可达地址

`GET /api/preview/2090440378057351169/thumbnail` → URL host `http://192.168.2.5:9000/stcloud-preview/...`，
直接 GET 该 URL 返回 HTTP 200。

### 11.9 Redis 断连 fail-open 与恢复

下载规则 10240KB/s 时执行：

| 阶段 | 操作 | 结果 |
|------|------|------|
| 故障 | `docker stop stcloud-redis` 后 20MB Range 下载 | HTTP 206、20,971,520B、7.963s，传输未中断，upstream=app2 |
| 故障 | app2 `/api/transfer/rate-limit/stats` | `degraded=1, storeErrors=1`；日志 `读取限速配置版本失败，降级为 0：Redis command timed out` |
| 恢复 | `docker start stcloud-redis` → PING | PONG；再次 20MB Range 下载 2.070s（≈10MB/s） |
| 恢复 | 两节点 speed-limit | 均 version=17、upload=4096/download=10240，恢复共享模式 |

### 11.10 两节点聚合不超发（活体）

下载规则 10240KB/s，4 个并发 20MB Range 经 Nginx（access log 记录同样由 app1/app2 各服务 2 个）：

    4 x 20MB，request_time=6.651 / 6.778 / 8.053 / 8.111s
    wall=8.166s，总 80MB → 约 9.8MB/s

若各节点独立限速应接近 2 x 10MB/s；实测 ≈ 单条规则 10MB/s，说明 Redis 共享信用在活体链路上生效。

## 12. 全量回归

| 命令 | 结果 |
|------|------|
| `mvn -q test`（11 模块，后台执行） | exit code 0；surefire 汇总 132 个测试类、862 用例、0 failure / 0 error、80 skipped（Redis/环境 gated），耗时约 3 分 20 秒 |

分模块：Common 50、Auth 38、Core 286、Search 77、Team 175、Preview 18、Share 113、Sync 45、
Admin 55、API 5。本轮新增用例：CreditBucket +3、SpeedLimitServiceTest +2、
RelayUploadIntegrationTest +1、RedisCreditStoreTest +1。RedisCreditStoreTest 在全量运行中按条件跳过，
已单独用 `-Dtest.redis.port=6379` 真实 Redis 执行并通过（见 §10/§9.4）。

## 13. 维度检查

### tests（pass）
- 定向：st-common 相关 16 例、relay 集成 19 例、真实 Redis 5 例（含 8x5s 双实例）。
- 全量：见 §12。

### selfReview（pass）
- R-1 前移校验，空会话根因消除；单机模式与 init 回落语义未变。
- R-2 快照只影响读取一致性，规则合并逻辑不变。
- R-3 降级窗口复用既有 fail-open 机制；降级期间不再访问 Redis，避免 EVAL 风暴；
  刷新后确认不限速仍直接放行。
- relay 压力测试失败根因是 curl 默认表单 Content-Type，非代码缺陷；客户端源码已确认为 octet-stream。

### security（pass）
- relay 哈希键仅用于路由；R-1 409 分支在读 body 前拒绝，不消耗磁盘/不建临时文件。
- nginx access log 新增 `$upstream_addr` 只含后端地址，无 token/查询串之外的敏感信息（原格式已记录 `$request`）。
- 未新增公开接口；预签名地址仍是客户端可达地址，不泄露内网 rustfs 地址。
- 测试期间临时使用 admin 规则，验证后已恢复原值；E2E 创建的 10MB 测试文件已删除。

### apiContract（additive）
- `relay-chunk` 新增可选参数 `clientLimit`（服务端与客户端取更严格值），既有请求不传时行为不变；
  `speed-limit` 的 version 字段语义未变；无破坏性变更。
- `STCLOUD_STORAGE_PUBLIC_ENDPOINT` 为新增可选配置，未配置时回退 `endpoint`，兼容既有部署。

### database（无变更）
- 未新增/修改表结构，未执行迁移。

### knowledge（无稳定规则变化）
- 本轮只补部署/运维说明（README、.env.example、design §16），未改变仓库级稳定规则，无需更新 `.ai/knowledge`。

## 13.1 客户端与镜像重建（交付）

| 步骤 | 命令 | 结果 |
|------|------|------|
| 桌面主进程 | `npm run build:main`（package.ps1） | dist/main.js 生成 |
| 桌面前端 | `vite build --mode desktop`（package.ps1） | st-web/dist base=./ |
| 绿色版 | `npx electron-builder --win dir --config.electronDist=node_modules/electron/dist --config.win.signAndEditExecutable=false` | `st-desktop/release/win-unpacked/` 生成 |
| 安装包 | `npx electron-builder --win nsis ...`（同上参数） | `st-desktop/release/星云盘 Setup 1.0.0.exe`（82,607,229 B） |
| 正式前端 | `cd st-web && npm run build` | dist base=/，PWA precache 65 项 |
| Web 镜像 | `docker build -f docker/Dockerfile.web -t stcloud-web:local .` + 重建 nginx | nginx healthy |
| 入口验证 | `GET /`、`GET /api/health`、`POST /api/auth/login` | 200 / 200 / 200（admin/admin123） |

说明：本机 electron-builder 首次打包需要 winCodeSign（含 darwin 符号链接），当前会话无创建符号链接权限，
7z 解压子项报错；使用 `signAndEditExecutable=false` 跳过 rcedit 后完成绿色版与 NSIS 安装包。
代价是 exe 的资源图标/版本信息未被改写（运行与安装功能不受影响）；有管理员权限或开启 Windows 开发者模式后，
去掉该参数重跑 `st-desktop/package.ps1 -ForceClose` 即可恢复图标元数据。

### 13.2 独立评审（code-v4）

| attempt | 结论 | 说明 |
|---------|------|------|
| DISPATCH-20261022-ratelimit-review-04 | APPROVE_WITH_CHANGES，0 阻塞 | 评审段落已写入 codereview.md；结果文件写入晚于主线程超时判定，ledger 记 failed，仅作审计参考 |
| DISPATCH-20261022-ratelimit-review-05（canonical） | **APPROVE，0 阻塞** | 7 项非阻塞残留已纳入 §14；CODE_REVIEW 以该 attempt 的 result 完成 Evaluate（validatedRevision=code-v4） |

## 14. 剩余风险

> code-v4 评审遗留项中的 1–7 已在 code-v5 逐项收口（见 §15），当前只保留第 8 项长期限制。

独立评审（codereview.md「code-v4 独立复审」）结论 APPROVE_WITH_CHANGES、阻塞 0，书面接受以下残留：

1. **seq <= 0 空会话边界（低，与 R-1 同类）**：`ensureRelaySession` 只拦 `seq > 1`，协议外 `seq <= 0`
   仍会先建会话再被 `tryAcquireSeq` 判 OUT_OF_ORDER 409；仓库 web/desktop 从 1 开始，触发需自造客户端。
   下一补丁改为 `seq != 1` 或入口 `@Min(1)`。
2. **cluster 预签名默认值**：`docker-compose.cluster.yml` 未设置别名时默认 `host.docker.internal:9000`，
   与 README/.env.example「不要使用」冲突；本机已通过 `STCLOUD_PUBLIC_S3_ENDPOINT=http://192.168.2.5:9000` 覆盖。
3. **`FROM nginx:latest` 未固定 digest**：健康检查依赖基础镜像自带 curl（本机 1.31.8/Debian trixie 已验证
   含 curl、无 wget），建议与仓库其它镜像一样固定 digest。
4. **S3StorageConfig Javadoc 与语义相反**：注释仍写「publicEndpoint 设内网、endpoint 设对外」，实际相反；
   仅注释问题，下一补丁纠偏。
5. **旧缓存读取方法无生产调用方**：`getUserRule/getRoleRules/getVersion` 仅测试引用，保留兼容。
6. **R-3 refresh 异常分支缺独立用例**：当前由「重试耗尽共用 fresh == null」路径覆盖。
7. **finalize 并发窗口**：`hasSession` 与 `transitionSession` 之间无锁，并发 cleanup 可能得到 404 CHUNK_NOT_FOUND
   而非 409；不影响正常流程。
8. **故障期聚合**：5s 降级窗口内最坏 N x rate（与既有 fail-open 同级）；Redis 故障期间双节点聚合压测、
   节点故障后客户端重放 seq=1 落到新节点的迁移场景仍未覆盖，属 TASK 已声明未验证项。

其他运行限制：

- R-1 修复后，客户端在 relay-chunk 409 后若仍 finalize 会得到 409，需要重新 init；仓库内 web/desktop 在
  relay-chunk 失败时终止上传，符合该语义。
- 桌面安装包已重新打包（§13.1）；用户机器上的旧安装包仍走旧客户端限速逻辑，需重新安装。
- 桌面打包因 winCodeSign 符号链接权限使用 `signAndEditExecutable=false`，exe 图标/版本元数据未改写。

## 15. code-v5 残余项收口（review-05 非阻塞项）

### 15.1 变更

| 残留 | 修复 | 证据 |
|------|------|------|
| seq <= 0 空会话 | `ensureRelaySession` 守卫改为 `seq != 1`；只有第一片创建缓冲 | `UploadServiceImpl.java` 注释 + `RelayUploadIntegrationTest#tc017`（409、无会话、无临时文件） |
| finalize 认领竞态 | MERGING 认领后二次 `hasSession` 探测，缺失抛 409 走既有 abort 清理 | `UploadServiceImpl.relayFinalizeInternal` |
| refresh 异常无独立用例 | 新增 `CreditBucketTest#refreshFailureDegradesLocally` | 1 次 EVAL、stale=1、degraded=1、<1s |
| S3StorageConfig Javadoc 反向 | 改为 endpoint=服务端内网、public-endpoint=客户端可达 | `S3StorageConfig.java` |
| 旧 accessor 用途不明 | 三个方法加 Javadoc：兼容保留，新代码用 snapshot | `SpeedLimitCache.java` |
| nginx tag 漂移 | `FROM nginx@sha256:4c41e2d0…`（1.31.8/trixie，本地镜像 ID 4c41e2d0a3a5） | `docker/Dockerfile.web` |
| compose 预签名默认不可达 | 改为必填 `${STCLOUD_PUBLIC_S3_ENDPOINT:?…}`，缺失 fail-fast | `docker/docker-compose.cluster.yml` |
| README 表格列数 | 默认值并入说明列，保持两列 | `README.md` |
| 桌面 exe 元数据缺失 | rcedit 写入图标/ProductName=星云盘/1.0.0，`--prepackaged` 生成 NSIS | `st-desktop/release/星云盘 Setup 1.0.0.exe`（82,617,490 B） |

### 15.2 命令与结果

| 命令 | 结果 |
|------|------|
| `mvn -q -pl st-common test -Dtest=CreditBucketTest,SpeedLimitServiceTest,UserTransferLimiterTest` | exit 0；CreditBucket 6、SpeedLimitService 2、UserTransferLimiter 9，全 pass |
| `mvn -q -pl st-core -am test -Dtest=RelayUploadIntegrationTest` | exit 0；20 用例 0 failure（含 tc017） |
| `docker compose -f docker-compose.yml -f docker-compose.cluster.yml config --quiet`（docker/.env 已设别名） | exit 0 |
| `rcedit-x64.exe 星云盘.exe --set-icon … --set-file-version 1.0.0 …` | 退出 0；VersionInfo ProductName=星云盘、FileVersion=1.0.0 |
| `electron-builder --win nsis --prepackaged release/win-unpacked` | 生成 82,617,490 B 安装包 |
| `mvn -q test`（全量） | 见 §15.3（执行后回填） |

### 15.3 全量回归

| 命令 | 结果 |
|------|------|
| `mvn -q test`（11 模块） | exit code 0；132 个测试类、864 用例、0 failure / 0 error、80 skipped，约 3 分 20 秒 |

分模块：Common 51、Auth 38、Core 287、Search 77、Team 175、Preview 18、Share 113、Sync 45、Admin 55、API 5。
相对 code-v4（862）新增 2 例：CreditBucketTest#refreshFailureDegradesLocally、RelayUploadIntegrationTest#tc017。

## 16. code-v5 运行态与交付

| 项 | 命令/操作 | 结果 |
|----|-----------|------|
| 后端镜像 | `mvn -q -pl st-api -am -DskipTests package` + `docker build -f docker/Dockerfile -t stcloud-app:local .` + 重建 app1/app2 | 两节点 healthy |
| seq<=0 现场 | 临时 upload=4096KB/s、10MB/2片 init（relay）后发 `seq=0` | `{"code":409,"message":"中转会话已丢失，请重新上传"}`；随后 finalize 也 409 |
| 规则恢复 | 恢复 upload=0 / download=51200 | 两节点均 version=22、0/51200 |
| Web 镜像 | 固定 nginx digest 后 `docker build -f docker/Dockerfile.web` + 重建 nginx | nginx healthy；`GET /` 200；admin 登录 200 |
| 桌面安装包 | rcedit + `--prepackaged` 重建 NSIS | `release/星云盘 Setup 1.0.0.exe` 82,617,490 B；exe ProductName=星云盘、FileVersion=1.0.0 |

## 17. code-v5 独立评审（review-06）

- 结论：**APPROVE，阻塞 0**（`DISPATCH-20261022-ratelimit-review-06`，validatedRevision=code-v5）。
- 逐项通过：seq != 1 守卫先于 createSession 且 tc017 有判别力；finalize 认领后二次探测把丢失会话收敛为 409
  且 CAS 清理不误伤并发胜者；refresh 异常用例与实现逐条对应；Javadoc/accessor 注释、nginx digest、
  compose 必填、README 两列表格均一致。
- 评审同时指出：由于「清理先赢状态 CAS」的不变量，二次探测抵消的竞态当前不可达，属纵深防御；注释措辞略强。
- 8 项非阻塞残留：注释精度（1）、探测与 finalize 之间理论窗口（2）、design 归因（3）、digest 架构面（4）、
  .env.example 注释（5）、v5 用例证据（6，已由 §15.3 与 §16 补齐）、tc017 未覆盖恢复路径（7）、
  seq=1 重放依赖粘滞（8）。其中 3 已在文档修正、6 已补齐；1/2/4/5/7/8 作为下一补丁清理项，不影响本轮验收。

## 18. code-v6：两个 P1 收口

### 18.1 relay 首片 S3 已有 part 守卫（数据完整性纵深）

- `ensureRelaySession` 在 `seq == 1` 建会话前调用 `storageManager.listUploadedParts`（只读一次）；
  非空返回 409「检测到该上传已有分片，请重新初始化上传」，不创建本地会话/临时文件；
  ListParts 的 `S3Exception` 映射为 `STORAGE_SERVICE_ERROR`，其它 S3 SDK 异常上抛为 500；两者都在创建
  会话之前失败，不建空会话（fail-closed）。
- 边界：两节点并发首片仍依赖 Nginx uploadId 粘滞；守卫消除的是“已知已有 part 仍开空会话”的确定性重复写入，
  客户端后续重放不完整时仍可能混片（strict-seq 下该路径当前不可达）。集群模式下 init 不建本地会话，
  因此每次 relay 上传在每个节点首片各调用一次 ListParts。
- 测试：`RelayUploadIntegrationTest#tc018`：已有 part → 409 + 无会话/无临时文件；清空 part 后 seq=1 可恢复创建。
- 依据：`storageManager.listUploadedParts` 已有实现（`StorageService.java`）且`ListParts` 失败会抛
  `STORAGE_SERVICE_ERROR`，无需新增 S3 能力。

### 18.2 运营与分发

- README 增加：Redis 故障时各节点本地限速、单用户聚合最坏 N×rate；限速不替代存储配额；
  每 30s 采集各节点 `/api/transfer/rate-limit/stats`，`degraded`/`storeErrors` 增长告警；
  `/api/transfer/speed-limit` 的 version 用于确认配置收敛。
- 发布校验：生成 `st-desktop/release/SHA256SUMS.txt`：
  `76a4a70f54a8f130490e6088cbdb910419f08d3dd8dce7c17201d76cf9661756  星云盘 Setup 1.0.0.exe`。
  正式分发仍需代码签名证书（当前环境无证书）。

### 18.3 命令与结果

| 命令 | 结果 |
|------|------|
| `mvn -q -pl st-core -am test -Dtest=RelayUploadIntegrationTest` | exit 0；21 用例 0 failure（含 tc018） |
| `mvn -q test`（11 模块） | exit code 0；132 个测试类、865 用例、0 failure / 0 error、80 skipped（Core 288） |
| `mvn -q -pl st-api -am -DskipTests package` + app 镜像 + 重建 app1/app2 | 两节点 healthy |
| `rcedit`/`SHA256SUMS` | 安装包 SHA256 见 18.2 |

### 18.4 运行态验证（两节点 + Nginx）

临时 upload=4096KB/s → 10MB/2×5MB relay 上传；前 5 个 1MB 分片经 Nginx（一致性哈希节点）写入 part1：

| 请求 | 目标 | 结果 |
|------|------|------|
| seq=1..5（1MB/片） | Nginx → 会话节点 | seq5 返回 `part=1 uploaded=true` |
| seq=1（重放首片） | 直连会话节点 | 200 duplicate confirmed（幂等，无重复写入） |
| seq=1（重放首片） | 直连另一节点 | 409「检测到该上传已有分片，请重新初始化上传」 |
| relay-finalize | 直连另一节点 | 409（未创建空会话，未进入 merge） |
| 规则恢复 | - | 两节点 version=24、upload=0/download=51200 |

结论：守卫在真实 S3 multipart 已含 part1 时生效，另一节点不会从 part 1 重开空缓冲；
正常首传（无 part）与重复首片（会话节点）行为不变。

## 19. code-v6 独立评审（review-07）

- 结论：**APPROVE，阻塞 0**（`DISPATCH-20261022-ratelimit-review-07`，validatedRevision=code-v6）。
- 逐项通过：守卫只在“本地无会话 + registry 有配置 + seq==1”触发，位于 createSession 之前且早于读 body；
  ListParts 的 `S3Exception` → `STORAGE_SERVICE_ERROR`（其它 SDK 异常 → 500），两者都不建会话；
  全类无 `@Transactional`，S3 调用不在 DB 事务内；tc018 有判别力并覆盖清空后的恢复；
  README 的 N×rate/30s 采集/告警/version 与实现一致；SHA256 实算与 `SHA256SUMS.txt` 一致且安装包 NotSigned 与文档一致；
  diff 无 controller/公开接口，无新增越权面。
- 6 项非阻塞残留（均低）：影响措辞（1）、fail-closed 错误码表述（2）、集群模式“每次上传首片各一次”成本说明（3）、
  校验和入构建脚本（4）、INFO 日志（5）、团队入口/会话状态断言（6）。
  其中 1/2/3 的文档措辞已在评审后对齐（仅文档，代码与用例未变）；4/5/6 作为下一补丁清理项。
