# 需求：限速多节点化与 Nginx 负载均衡

> 归属 exitCriteria: REQ_ANALYSIS

## 1. 背景

当前限速实现是进程内状态：UserTransferLimiter 用 ConcurrentHashMap 按 userId 持有 TokenBucket，
synchronized 串行化。它只在单实例下正确。一旦部署两个及以上节点：

- 聚合速率变成 N x rate；每个新节点启动还各送一次容量突发；
- 上传在途窗口（window=1）与 release 都只在本地，跨节点 confirm 无法释放；
- 中转上传的临时文件与会话在本地，请求被轮询到其他节点直接失败；
- 同步 WebSocket 只推给本节点会话。

目标是在 Nginx 做入口负载均衡的前提下，让「单用户聚合速率 <= 配置值」在任何节点组合下成立，
且中转上传、流式下载、WebSocket 正常工作。

## 2. 用户与场景

| 场景 | 期望 |
|------|------|
| 管理员给用户配 500KB/s，用户开 3 个并发下载，请求落在 2 个节点 | 聚合 <= 500KB/s，允许 100ms 级突发 |
| 用户走中转上传 2GB，chunk 依次 POST 到 nginx | 同一 uploadId 始终同一节点，分片不丢不重 |
| 滚动发布 / 节点故障 | 新节点不产生长期超额；传输自动切到存活节点 |
| 管理员把 500KB/s 改为 200KB/s | 秒级生效，不依赖重启 |
| Redis 故障 | 传输不中断，按节点本地限速降级并告警 |
| 桌面端同步 | WebSocket 连到任一节点都能收到变更推送 |

## 3. 功能需求

- FR-1 本地桶保持 8KB 粒度平滑，不引入逐字节网络调用。
- FR-2 全局准入由 Redis 原子扣减完成，quantum 按时间量化：clamp(rate x 100ms, 64KB, 1MB)。
- FR-3 上传在途窗口与令牌扣减在同一个 Lua 脚本中完成，避免「扣了令牌但窗口满」丢失额度。
- FR-4 释放按 uploadId+chunkIndex 幂等，跨节点有效。
- FR-5 限速规则变更跨节点收敛：pub/sub 主动失效 + 桶内 rate/ver 校验双保险。
- FR-6 Redis 不可用时 fail-open 降级到节点本地 rate，打点告警，恢复后自动回共享模式。
- FR-7 中转上传在 nginx 上按 uploadId 粘滞；init 不创建本地会话，首个 relay-chunk 惰性创建。
- FR-8 nginx 关闭请求/响应缓冲，保证流式下载与 pacing 生效；WebSocket 支持 Upgrade。
- FR-9 新增 /api/health 供 nginx / compose 探活；应用优雅停机。
- FR-10 同步变更推送跨节点扇出（Redis pub/sub），不重复写 sync_change_log。
- FR-11 可观测：授予字节、拒绝次数、降级次数、Redis 脚本耗时。

## 4. 非功能需求

- 精度：1 秒窗口聚合 <= rate；突发上界 <= 节点数 x quantum（quantum 默认 100ms 流量）。
- 开销：每用户 active 时 <= 10 次 Redis 脚本/秒；不限速用户零 Redis 调用。
- 兼容：mode=local 时行为与现状完全一致；对外 API 与响应字段不变。
- 回滚：切换 mode=local 即回到单节点实现；Redis key 自然过期，无 schema 变更。
- 可用性：Redis 故障不影响主流程，仅退化为 N x rate。

## 5. 边界与非目标

- 不做全局带宽总量控制（只做单用户/单方向）。
- 不把预签名直传改成服务端代理（保持现有上传形态）。
- 不解决分享流固定 5MB/s 与预签名下载绕过用户桶的问题（单独评估）。
- 不做多活单元化、Redis 集群化、S3 双活。
- 不改前端（除部署文档）。

## 6. 业务规则与异常

- rate<=0 表示不限速，直接放行，不创建桶状态。
- 客户端自设限速只能更慢：capRate 取更严值，规则不变。
- 令牌不足返回 retryAfterMs（上传路径），下载路径阻塞等待。
- 中转会话丢失（节点故障）时返回明确错误，由客户端重新初始化，不静默重试到其他节点。
- 配置版本 ver 较小的节点不得覆盖较大 ver 的限速值，只回读最新值并刷新本地缓存。

## 7. 验收标准

见 TASK 验收标准章节，逐条对应测试计划中的用例。

## 8. 待确认决策（已给推荐，默认按推荐执行）

1. 中转上传亲和方式：推荐 nginx hash $arg_uploadId + 服务端惰性会话；不采用全站 userId 粘滞。
2. 同步推送：推荐 Redis pub/sub 扇出；不采用 MQ BROADCASTING（会重复写库）。
3. 部署形态：推荐 docker-compose 增加 app1/app2 + nginx 作为参考实现；生产可直接替换为 K8s。
4. mode 默认值：推荐 local（自托管单机零变化），多节点部署显式设 redis。
