# 技术设计：限速多节点化与 Nginx 负载均衡

> 归属 exitCriteria: TECH_DESIGN（依赖 REQ_ANALYSIS）

## 1. 总体方案

三层职责：本地桶负责平滑，Redis 负责全局准入，Nginx 负责入口与中转亲和。

```
客户端（Web / 桌面端 / 分享访问）
        |
        v
   Nginx :80/:443
   ├─ /                         -> st-web 静态资源（可选）
   ├─ /api/file/upload/relay-*  -> upstream stcloud_relay（hash $arg_uploadId consistent）
   ├─ /api/sync/ws              -> WebSocket Upgrade
   ├─ /api/{file}/stream 等     -> proxy_buffering off（流式）
   └─ /api/                     -> upstream stcloud_api（least_conn）
        |
        ├── app1 ──┐
        └── app2 ──┤
                    ├─ Redis：信用桶 hash + 上传窗口 ZSET + 配置版本 + pub/sub
                    ├─ MySQL / S3 / RocketMQ（既有）
                    └─ 节点本地：8KB 令牌桶（平滑）、relay 临时文件
```

数据流（下载）：请求 -> 解析 rate -> 本地桶取 8KB -> 不足时向 Redis 领 quantum -> 扣减成功继续写；
写入速率由本地桶按 rate 平滑，全局额度由 Redis 原子扣减保证。

数据流（上传直传）：chunk-url -> Redis 单脚本（窗口 + 令牌）-> 签发预签名 URL -> 客户端直传 S3；
chunk-confirm -> Redis ZREM（幂等）-> 释放窗口。

数据流（上传中转）：init -> Redis 写 relay 配置 -> relay-chunk 由 Nginx 按 uploadId 粘滞到同一节点 ->
本地临时文件 + 8KB pacing（同样走信用桶）-> 达到分片阈值 uploadPart -> relay-finalize 合并。

## 2. Redis 共享状态

### 2.1 Key 设计（单 slot，兼容 Redis Cluster）

| key | 类型 | 字段 / member | 说明 |
|-----|------|---------------|------|
| `rl:{tenantId:userId}:up` | hash | tokensMb / lastMs / rate / ver | 上传字节信用 |
| `rl:{tenantId:userId}:down` | hash | 同上 | 下载字节信用 |
| `rl:{tenantId:userId}:win` | zset | member=`uploadId:chunkIndex`，score=过期毫秒 | 上传在途窗口 |
| `stcloud:rate-limit:ver` | string | 自增计数 | 配置版本，管理端写入时 INCR |

`{tenantId:userId}` 作为 hash tag，保证同一用户的两个 key 落在同一 slot，单脚本可原子操作。

### 2.2 容量与 quantum

- capacity（字节）= max(8192, rate x 1s)，与现状一致。
- quantum（字节）= clamp(rate x quantum-ms, quantum-min, quantum-max)，默认 100ms、64KB~1MB。
- 每用户成功扣减次数 = rate / quantum ≈ 10 次/秒，与节点数和速率无关。
- 突发上界：单节点 <= quantum，集群 <= N x quantum。

### 2.3 精度：整数毫字节

Redis Lua 的返回值会被截断为整数，浮点 tokens 不能直接跨边界。统一用「毫字节」整数：
`tokensMb`、`needMb`、`capacityMb` 全部 x1000；补充量 = `elapsedMs x rate`（恰好等于毫字节）。
所有算术在脚本内完成，Java 侧只传整数、只收整数。

### 2.4 准入脚本（伪代码）

```lua
-- KEYS[1] = rl:{t:u}:down | up   KEYS[2] = rl:{t:u}:win（仅上传门控时传入）
-- ARGV[1]=needBytes ARGV[2]=rateBps ARGV[3]=capacityBytes ARGV[4]=ver
-- ARGV[5]=ttlMs ARGV[6]=initTokensBytes ARGV[7]=windowSize ARGV[8]=windowMember ARGV[9]=windowExpireMs
local t = redis.call(TIME)          -- Redis 5+ 默认 effects replication，可直接调用
local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local rate, capMb, needMb, ver = tonumber(ARGV[2]), tonumber(ARGV[3]) * 1000, tonumber(ARGV[1]) * 1000, tonumber(ARGV[4])

-- 1) 窗口先判：满了直接拒绝，不扣令牌
if ARGV[7] ~= 0 then
  redis.call(ZREMRANGEBYSCORE, KEYS[2], -inf, nowMs)
  if redis.call(ZCARD, KEYS[2]) >= tonumber(ARGV[7]) then return {0, 500, 0, 0} end
end

-- 2) 读状态；不存在则按 initTokens 一次性初始化
local h = redis.call(HMGET, KEYS[1], tokensMb, lastMs, rate, ver)
local tokensMb, lastMs, storedRate, storedVer = tonumber(h[1]), tonumber(h[2]), tonumber(h[3]), tonumber(h[4])
if not tokensMb then
  tokensMb = tonumber(ARGV[6]) * 1000; lastMs = nowMs; storedRate = rate; storedVer = ver
else
  -- 3) 配置版本更旧的节点不得覆盖新值：回读最新 rate/ver，调用方刷新缓存后重试
  if ver < storedVer then return {0, 0, storedRate, storedVer} end
  local elapsed = math.max(0, nowMs - lastMs)
  tokensMb = tokensMb + elapsed * storedRate            -- 毫字节 = ms x B/s
  if tokensMb > capMb then tokensMb = capMb end
  if ver > storedVer or rate ~= storedRate then
    rate = storedRate = (ver > storedVer and rate or storedRate)
    if tokensMb > capMb then tokensMb = capMb end
  end
  storedVer = ver; lastMs = nowMs
end

-- 4) 扣减或拒绝
local granted, waitMs = 0, 0
if tokensMb >= needMb then
  tokensMb = tokensMb - needMb; granted = 1
  if ARGV[7] ~= 0 then redis.call(ZADD, KEYS[2], ARGV[9], ARGV[8]) end
else
  waitMs = math.ceil((needMb - tokensMb) / rate)       -- 毫字节 / (B/s) = ms
end
redis.call(HSET, KEYS[1], tokensMb, math.floor(tokensMb), lastMs, nowMs, rate, rate, ver, storedVer)
redis.call(PEXPIRE, KEYS[1], ARGV[5])
if ARGV[7] ~= 0 then redis.call(PEXPIRE, KEYS[2], ARGV[5]) end
return {granted, waitMs, rate, storedVer}
```

说明：

- 窗口判定与令牌扣减在同一个脚本内，消除「判完窗口、扣令牌前窗口被释放」的 TOCTOU。
- 拒绝时不扣令牌、不写 member，避免额度丢失。
- 返回 `rate/storedVer` 让过期节点在 1 个 RTT 内自愈，不必等 30s TTL。
- 释放脚本只有一行：`ZREM KEYS[1] ARGV[1]`，天然幂等。

## 3. 本地桶改造（st-common）

新增 `CreditBucket`（替代 DownloadBucket / UploadPaceBucket 的内部实现）：

- 字段：tokens、credit、lastNs、rate、degradedUntil。
- `credit` 只增（来自 Redis grant），`tokens` 按 rate 从 credit 补充，容量 = quantum。
- 关键写法：状态变更 + Redis 调用在 `ReentrantLock` 内（约 0.2~1ms），**sleep 移到锁外**，
  修掉现在 `synchronized` 内 `Thread.sleep(<=1000ms)` 的串行阻塞。
- 申请逻辑：tokens 不足且 credit < quantum -> 调 `CreditStore.grant`；granted 则 credit += quantum，
  否则按返回的 waitMs 在锁外 sleep。
- 降级：`CreditStore` 异常/超时 -> 记录 degradedUntil（5s），期间允许按 rate 凭空补充，并打点；
  恢复后自动回到信用模式。

`TokenBucket` 保留给 local 模式与单测；`UserTransferLimiter` 依据 `stcloud.rate-limit.mode` 选择 store。

## 4. 上传门控

- `tryAcquireUpload`：mode=redis 时调用准入脚本（传 window 参数），返回 allowed / retryAfterMs；
  本地模式保持现有 UploadGate（含 5 分钟过期回收）。
- `releaseUpload(userId, uploadId, chunkIndex)`：改为三参，Redis 用 member 幂等释放；
  本地模式按新增的 chunkKey 记账（顺带修掉重复 confirm 误释放）。
- `confirmChunkInternal`：先 `markChunkUploaded`，后按 `uploadId+chunkIndex` 释放；重复 confirm 第二次 ZREM 返回 0。

## 5. 配置收敛

- `SpeedLimitCache` 增加 `ver`：加载时读 `stcloud:rate-limit:ver`；
- `SpeedLimitManageServiceImpl` 四个写操作后：INCR 版本号 + `PUBLISH stcloud:rate-limit:evict`；
- 各节点 `RedisMessageListenerContainer` 订阅后 `cache.evict()`；
- 未收到消息的节点在脚本返回 stale 时用返回的 rate/ver 刷新缓存并重试一次。

## 6. 中转上传的多节点适配

问题：init 在当前节点创建本地临时文件与内存会话，Nginx 轮询后 relay-chunk 可能落到另一节点。

方案：

1. init 不再创建本地会话，只把 `{rate, relayChunkSize, storagePath, s3UploadId}` 写入
   `relay:cfg:{uploadId}`（TTL = sessionTimeout，默认 10 分钟）。
2. relay-chunk 进入后：本地 session 不存在 -> 从 Redis 读配置 + 从 DB 读 upload_session -> 惰性创建本地临时文件。
3. Nginx 对 relay-chunk / relay-finalize 用 `hash $arg_uploadId consistent`，同一 uploadId 固定同一节点。
4. relay-finalize 若本地 session 仍缺失（节点故障/被驱逐）-> 返回 409「上传会话已丢失，请重新上传」，
   客户端走重新初始化，不静默切换节点。
5. Redis 配置 key 同时解决「rate 在 init 后冻结」问题：中转分片大小在一次上传内稳定。

## 7. 降级与可观测

- 降级策略：fail-open。Redis 连接/脚本异常 -> 本节点按 rate 本地限速（最坏 N x rate），
  连续失败 3 次进入 5s 熔断，半开探测恢复。
- 指标（Micrometer，actuator 只暴露 health）：
  - `ratelimit.granted.bytes`、`ratelimit.denied`、`ratelimit.wait.ms`、
  - `ratelimit.store.latency`、`ratelimit.degraded`、`ratelimit.script.errors`。
- 日志：降级开始/结束、配置版本跳变、窗口冲突各一条 WARN（限频）。

## 8. Nginx 配置

```nginx
# upstream：普通 API 用 least_conn
upstream stcloud_api {
    least_conn;
    server 172.28.0.11:8080 max_fails=2 fail_timeout=10s;
    server 172.28.0.12:8080 max_fails=2 fail_timeout=10s;
    keepalive 64;
}

# 中转上传：按 uploadId 一致性哈希，保证本地临时文件同节点
upstream stcloud_relay {
    hash $arg_uploadId consistent;
    server 172.28.0.11:8080 max_fails=2 fail_timeout=10s;
    server 172.28.0.12:8080 max_fails=2 fail_timeout=10s;
    keepalive 16;
}

map $http_upgrade $connection_upgrade { default upgrade; "" close; }

server {
    listen 80;
    server_name _;
    client_max_body_size 10g;
    client_body_timeout 300s;

    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    root /usr/share/nginx/html;
    location / { try_files $uri $uri/ /index.html; }

    # 中转上传：粘滞 + 关闭请求缓冲（否则 nginx 先收完整个 body，pacing 失效）
    location ~ ^/api/file/upload/relay-(chunk|finalize)$ {
        proxy_pass http://stcloud_relay;
        proxy_request_buffering off;
        proxy_buffering off;
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
        proxy_next_upstream off;      # 不允许 POST 重试到其他节点
        proxy_set_header Connection "";
    }

    # 同步 WebSocket
    location = /api/sync/ws {
        proxy_pass http://stcloud_api;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

    # 流式下载 / 预览 / 缩略图：不缓冲，Range 透传
    location ~ ^/api/(file/[0-9]+/stream|share/access/stream/|share/access/thumbnail/) {
        proxy_pass http://stcloud_api;
        proxy_buffering off;
        proxy_request_buffering off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_set_header Connection "";
    }

    # 普通 API
    location /api/ {
        proxy_pass http://stcloud_api;
        proxy_read_timeout 120s;
        proxy_set_header Connection "";
    }
}
```

要点：

- `proxy_request_buffering off` 让服务端 pacing 真正作用于客户端上传速度。
- `proxy_buffering off` 避免大文件下载被 nginx 缓冲到磁盘导致内存/磁盘抖动。
- relay 位置关闭 `proxy_next_upstream`，避免请求被重放到另一个没有临时文件的节点。
- 默认 `proxy_next_upstream error timeout` 不会重试已发出的非幂等请求，其余接口保持默认即可。

## 9. 部署拓扑（参考实现）

```yaml
services:
  nginx:
    image: nginx:1.27-alpine
    ports: ["80:80"]
    volumes: ["./nginx/conf.d:/etc/nginx/conf.d:ro"]
    depends_on: [app1, app2]
  app1: &app
    build: { context: .., dockerfile: docker/Dockerfile }
    environment: &appenv
      SPRING_PROFILES_ACTIVE: prod
      STCLOUD_MASTER_KEY: ${STCLOUD_MASTER_KEY}
      STCLOUD_RATE_LIMIT_MODE: redis
      STCLOUD_SEARCH_TEAM_CURSOR_SECRET: ${STCLOUD_SEARCH_TEAM_CURSOR_SECRET}
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/stcloud
      STCLOUD_STORAGE_ENDPOINT: ${STCLOUD_PUBLIC_S3_ENDPOINT}
      ROCKETMQ_NAME_SERVER: rocketmq-namesrv:9876
      SERVER_SHUTDOWN: graceful
    healthcheck: { test: ["CMD", "curl", "-f", "http://localhost:8080/api/health"] }
    stop_grace_period: 60s
  app2: *app
```

注意：`STCLOUD_STORAGE_ENDPOINT` 必须是客户端可达地址（预签名 URL 直接指向对象存储），
不能用 127.0.0.1；OnlyOffice 的 `public-base-url` 指向 Nginx。

## 10. 同步 WebSocket 跨节点

现状：RocketMQ 消费者是 CLUSTERING，只有一节点收到消息，只推送本地 WS 会话。
方案：

1. 保留 CLUSTERING 写 `sync_change_log`（不重复写库）。
2. 写入成功后 `PUBLISH stcloud:sync:push {tenantId,userId,logId}`。
3. 各节点订阅后调用 `sendToTenantUser` 推给本地会话。
4. Redis 不可用时的兜底：本节点直接推送一次（与现在一致）。

## 11. 兼容、迁移与回滚

- 默认 `mode=local`，单机部署行为不变；多节点部署显式设 `STCLOUD_RATE_LIMIT_MODE=redis`。
- 无数据库迁移；Redis 仅新增 key，均有 TTL。
- API 不变：`ChunkUrlResponse.retryAfterMs`、`SpeedLimitResult` 字段与语义保持。
- 回滚：改回 `mode=local` 并摘掉多余节点；Redis key 到期自动消失。
- 灰度：先在测试环境两节点验证，再滚动替换生产节点。

## 12. 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| Nginx 未配置 relay 亲和 | 中转上传跨节点失败 | 配置模板固定 hash；预发用例覆盖；日志带 uploadId 便于排查 |
| Nginx 默认缓冲 | 上传 pacing 失效、下载抖动 | 明示 off，并用 curl 限速对比验证 |
| Redis 单点 | 限速退化 / 抖动 | fail-open + 熔断 + 告警；Redis 已有密码/持久化 |
| 配置版本时钟偏差 | 旧值覆盖新值 | 版本用 Redis INCR 单调递增，不用时间戳 |
| relay 节点故障 | 该次上传丢失 | 明确 409 + 客户端重新初始化；不静默切节点 |
| 长下载阻碍优雅停机 | 发布变慢或断流 | 60s 宽限后断流，客户端 Range 续传 |
| 预签名 URL 域名不可达 | 上传失败 | 部署文档强制公网 S3 地址 |
| Actuator 暴露 | 信息泄露 | 只暴露 health，SecurityConfig 收紧到 /actuator/health |

## 13. 测试计划

| 编号 | 类型 | 内容 |
|------|------|------|
| T-01 | 单测 | TokenBucket/CreditBucket 假 store：快路径、阻塞、中断、rate 变更 |
| T-02 | 单测 | 降级：store 抛异常时按 rate 放行并置 degraded |
| T-03 | 单测 | 上传窗口：拒绝不扣令牌、释放幂等、重复 confirm |
| T-04 | 集成（真实 Redis） | 两 store 共享 key，8 线程 5s，总量 <= rate x 5 + 2 x quantum |
| T-05 | 集成（真实 Redis） | 版本收敛：旧 ver 被拒绝并回读新 rate |
| T-06 | 集成（H2） | confirm 幂等、relay 惰性会话创建、409 分支 |
| T-07 | 手工/脚本 | nginx：Range 透传、流式不缓冲、WS 握手、relay 两 chunk 同节点 |
| T-08 | 故障演练 | 停 Redis 10s：传输不中断、指标可见、恢复后回共享模式 |
| T-09 | 发布演练 | 两节点滚动重启：无长期超额、进行中下载可 Range 续传 |

命令：

    mvn -pl st-common,st-core -am test -Dsurefire.failIfNoSpecifiedTests=false
    mvn -pl st-api -am package -DskipTests
    # 真实 Redis 集成测试需 -Dtest.redis.port=6379

## 14. 实施分期

| 期 | 内容 | 预估 |
|----|------|------|
| P0 | 单节点缺陷修复：release 幂等、桶回收、sleep 移出临界区、rate 变更重置 | 1~2 天 |
| P1 | CreditStore 抽象 + Redis 脚本 + mode 开关 + 降级 | 3~5 天 |
| P2 | 上传窗口脚本、relay 惰性会话 + Redis 配置、配置版本与 pub/sub | 3~4 天 |
| P3 | nginx 配置 + compose 编排 + /api/health + 优雅停机 | 2~3 天 |
| P4 | 指标、测试补齐、文档与发布演练 | 2~3 天 |
| P5 | 同步 WS 跨节点扇出 | 1 天 |

合计约 12~18 人天；P0 可独立先行合入。

## 15. 开放决策

见 requirement.md 第 8 节，默认按推荐项执行；如无异议，按本设计进入 P0。

## 16. code-v4 残余项修复（R-1/R-2/R-3）

独立评审 code-v3 给出的书面接受残留，按「先安全、再可观测、最后补证据」处理，不改变总体架构、
Redis key、API 契约与数据库。

### 16.1 R-1：relay 缺会话时不得创建空缓冲

- 现状：`ensureRelaySession` 先 `createSession` 再判断 `seq > 1`，409 分支留下空会话；
  后续 finalize 的 `hasSession` 只读探测会命中该空会话，在空缓冲上完成合并，文件静默截断。
- 决策：把 seq 判断前移到创建之前。本地会话缺失且 `seq > 1` 时直接 409，**不创建任何本地状态**；
  只有 `seq == 1` 才按 Redis 登记配置惰性创建。这样 R-1 的根因（空会话）不会产生，
  也不再依赖「409 前 cleanup」这种补偿式修复。
- 兼容：单机模式（registry 为 null）行为不变；init 仍只在无法登记时回落本地会话。
- 回归：`RelayUploadIntegrationTest#tc016` 覆盖「init 只登记 → 首片惰性创建 → 会话丢失 → seq>1 409
  → 无空会话/无临时文件 → finalize 409 → 未调用 completeMultipartUpload」。

### 16.2 R-2：限速规则与版本一次快照

- 现状：`resolve()` 三次 `getEntry()`，缓存 TTL 边界会跨读两份 entry，产出「新 ver + 旧 rate」。
- 决策：`SpeedLimitCache.snapshot(userId, roles)` 在一次 `getEntry()` 内同时取出用户规则、角色规则与
  version；`resolve()` 只调用该快照。旧的单值读取方法保留，避免破坏外部调用。
- 兼容：解析语义（用户 + 角色取最严格值，0 表示不限速）不变。

### 16.3 R-3：stale 耗尽改为 5s 本地降级

- 现状：连续 stale 超过重试上限、刷新值未前进或 refresh 不可用时直接 `return`，该块完全不受限，
  只记 `stale`，运维不可见。
- 决策：上述三种情况统一置 5s 本地降级（`degradedUntilMs`）并累加 `degraded`，然后按当前 rate
  平滑放行；不再直接放行，也不会形成 EVAL 风暴（降级期间不再调用 `grant`）。
- 权衡：降级窗口内最坏 N x rate（与现有 fail-open 同一量级），由指标暴露；5s 后重新回到共享桶。
- 边界：刷新后确认 `rate <= 0`（不限速）仍直接放行，属于正确语义而非降级。

### 16.4 R-6 关闭

- `getChunkUrl` 的 `clientLimit` 参数已重新参与客户端限速门控（`getChunkUrlInternal`），
  评审文档中的 R-6 由 code-v4 复核对齐。

### 16.5 部署与文档顺带修复

- `docker/Dockerfile.web` 健康检查改用 curl：`nginx:latest`（Debian trixie）已不带 wget，
  原健康检查导致容器长期 unhealthy。
- nginx access log 增加 `upstream=$upstream_addr`，用于核对 relay 粘滞、节点分布与共享限速。
- application.yml/README/.env.example 明确 `STCLOUD_STORAGE_ENDPOINT`（服务端内部）与
  `STCLOUD_STORAGE_PUBLIC_ENDPOINT`（预签名）的拆分及 compose 别名 `STCLOUD_PUBLIC_S3_ENDPOINT`。

### 16.6 测试计划（增补）

| 编号 | 类型 | 内容 |
|------|------|------|
| T-10 | 单测 | CreditBucket stale 同值/重试耗尽 → 5s 降级 + degraded；拒绝等待期中断退出 |
| T-11 | 单测 | SpeedLimitService 只读一次快照并按最严格规则合并 |
| T-12 | 集成（真实 Redis） | 两 store 实例 8 线程 x 5s，总发放 <= rate x T + 2 quantum 且 >= 0.9 rate x T |
| T-13 | 集成（H2） | redis 模式 relay 缺会话 seq>1 409 且不留空会话，finalize 409 |
