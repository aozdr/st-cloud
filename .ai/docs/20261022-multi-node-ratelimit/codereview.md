# 代码评审：限速多节点化与 Nginx 负载均衡（v3 复审）

> 归属 exitCriteria: CODE_REVIEW
> dispatchId: DISPATCH-20261022-ratelimit-review-03
> taskId: 20261022-multi-node-ratelimit
> validatedRevision: 20261022-multi-node-ratelimit-code-v3（工作区未提交）
> reviewer: reviewer-ratelimit-03（独立 reviewer，只读）
> 评审方式：read/grep + `git status --short`（只读）静态审查与调用链交叉验证；未执行 mvn、未执行 git 写操作、未改代码/State/TASK、未接真实 Redis 或两节点环境。
> 本文件覆盖 review-02 的旧版 codereview.md；只复核两个阻塞项与 v3 增量，不重复全量评审。

## 0. 快照与对照基线

- 快照采集：2026-10-22T19:45+08:00，工作区 `git status` 显示限速相关文件均为 M/??（未提交），**无法用 git diff 隔离 v2→v3**。
- 因此以 `verification.md` §5 的修复清单为对照，逐条在当前代码中定位；结论只对上述快照有效，代码再变需重新复核。

## 1. 结论

**APPROVE_WITH_CHANGES**（criterionProposal.outcome = **pass**，阻塞 0）

| 复核项 | 结论 |
|---|---|
| BLOCK-1：共享桶只使用服务端 rate；stale 有界且不空转 | **已消除**（§2） |
| BLOCK-2：finalize 只读探测、缺会话一律 409 | **已消除**（§3）；同源残留 1 项（R-1，高风险，非阻塞） |
| relay-chunk 新建会话且 seq>1 → 409 | **符合**（§3.3） |
| v3 是否引入新阻塞级回归 | **未发现**（§4） |
| 放行 | 建议放行；R-1 建议在同一分支的下一个补丁修复（一行 cleanup），R-2/R-3 为改进项 |

## 2. BLOCK-1 复核：共享桶 rate 语义与 stale 有界

### 2.1 共享桶只接收服务端 rate ✓

| 写入共享桶的入口 | 传入 rate | 证据 |
|---|---|---|
| 下载 pacing | `speedLimitService.resolve().getDownloadSpeedLimit()*1024` | `DownloadServiceImpl.java:161-166`（注释明确「共享桶只用服务端 rate」） |
| 下载客户端自设限速 | 只进本地 `client-pace` TokenBucket | `DownloadServiceImpl.java:197-201` → `UserTransferLimiter.java:175-180` |
| 分片门控（预签名） | `limit.getUploadSpeedLimit()*1024` | `UploadServiceImpl.java:386-393`；`clientLimit` 形参在 `getChunkUrlInternal` 中已不参与，仅保留接口兼容（`:362-372`, `:372-399`） |
| relay pacing | `currentLimit.getUploadSpeedLimit()*1024` | `UploadServiceImpl.java:657-661, 681` |
| relay 客户端自设限速 | 只进本地桶，且仅在 `clientSlower` 时调用 | `UploadServiceImpl.java:661-667, 678-680` |
| simpleUpload 服务端中转 | `capRate(server, null) = server` | `UploadServiceImpl.java:167-169` → `pacedInputStream`（`:822-830, 837, 846`） |

全仓库 grep `acquireDownload\(|acquireUploadPace\(|tryAcquireUpload\(|acquireClientLimit\(` 确认：pacing/gate 只有上述入口，无绕过。

结论：同一 `(tenant,user,direction)` 在 ver 不变时 rate 恒定，B-1 的「同 ver 多 rate → 恒 stale」根因消除；客户端自设限速仍以「本地 min(server, client)」生效。

### 2.2 stale 重试有界、刷新不前进即退出 ✓

- `CreditBucket.java:31` `staleRetries`；`:81-83` `++staleRetries > 2` 直接返回 —— 单次 acquire 最多 3 次 EVAL，无紧循环。
- `CreditBucket.java:84-94`：refresh 为 null / rate<=0 / **(rate,ver) 均未前进** 一律退出；前进才更新 currentRate/Ver/Quantum 重试。
- `CreditBucket.java:69-79`：stale 先 `stats.stale()` + `onStale`（`evictCache`），不把 stale 当故障降级，与 verification §5 一致。
- 循环其它分支无零 sleep 路径：denied 必 sleep ≥1ms（`:108-110`），granted 后 credit 增加、下一轮走 sleep 分支（`:96-103, 127-129`）。
- 上传门控 stale：单次请求一次 EVAL → evict → `retry(200ms)` 返回（`UserTransferLimiter.java:60-64`），无服务端循环、无窗口成员泄漏。

### 2.3 门控与 pacing 共用 `rl:{t:u}:up` 不再互造 stale

两者都传服务端 rate + 同一 ver，Lua 的 `ver == storedVer and rate ~= stored`（`RedisCreditStore.java:103-105`）不再由客户端限速差异触发。重复扣减仍在（更严格，不会超上限），verification §5 已书面接受，本轮不作为问题。

### 2.4 BLOCK-1 结论：消除

残余的「等 ver + 不同 rate」只剩配置读取/版本键异常这类来源，已不再死循环，但会退化（见 R-2）。

## 3. BLOCK-2 复核：relay finalize 空会话

### 3.1 finalize 是只读探测 ✓

`UploadServiceImpl.java:750-754`：以 `relayBufferManager.hasSession(uploadId)` 判定，`hasSession` 只做 `sessions.containsKey`（`RelayBufferManager.java:114-117`），不创建会话、不建临时文件。409 在 `transitionSession` 之前抛出，无 DB 状态写入。

### 3.2 缺会话一律 409 ✓

- `ResultCode.CONFLICT(409)`（`ResultCode.java:14`）。
- `ensureRelaySession` 全仓库只被 `relayChunkInternal` 调用（`UploadServiceImpl.java:803-819`），finalize 不再调用。
- 重复 finalize 每次都命中 `hasSession=false` → 稳定 409，不会二次进入 merge；`relay:cfg` 与会话状态仍由 active/超时流程收敛。

### 3.3 relay-chunk 新建会话且 seq>1 → 409 ✓

`UploadServiceImpl.java:631-636`：`ensureRelaySession` 返回 true 且 `seq > 1` → `CONFLICT`，检查发生在读取 body / append 之前。

### 3.4 残留 R-1（高风险，非阻塞）：409 分叉留下的本地空会话，仍可让后续 finalize 在空缓冲上合并

- 路径：节点 B 无本地会话但 `relay:cfg` 存在 → `ensureRelaySession` **先** `createSession`（建临时文件、空缓冲）→ 返回 true → seq>1 抛 409（`UploadServiceImpl.java:633-636`），**未 cleanup**。
- 随后同 uploadId 的 relay-finalize（nginx 按 uploadId 哈希，仍落 B）→ `hasSession=true` → 越过 3.1 守卫 → `RelayBufferManager.finalize`：`accumulated==0` 时 `lastPart=0` 仍继续（`RelayBufferManager.java:179-198`）→ `mergeChunksInternal` 直接 completeMultipart（`UploadServiceImpl.java:496-518`，无对象实际大小校验）→ `finalizeMerge` 按声明的 `node.getFileSize()` 记账（`UploadCommitManager.java:161-164, 188-192`）→ 文件静默缺尾部余量。
- 触发条件比原 B-2 更强：需「节点 A 已 flush 部分分片后失效/被驱逐 → 客户端下发 seq>1 到 B 得到 409 → 客户端仍调用 finalize」。仓库内 web 客户端在 relay-chunk 失败即终止、不 finalize（`st-web/src/hooks/useUpload.tsx:142-161`），因此不属于受支持流程；第三方客户端若在 finally 中 finalize 即命中。
- 附加影响：该空会话在会话超时后会被 `cleanupExpired → abortSession`（`RelayBufferManager.java:246-255, 263-300`）中止整个 multipart —— 属失败而非静默损坏，方向安全。
- 建议（一行）：`if (relaySessionCreated && seq > 1) { relayBufferManager.cleanup(uploadId); throw new BusinessException(CONFLICT, ...); }`；或把 seq 校验前移到 createSession 之前。review-02 建议的「空缓冲守卫」（committedSeq==0 && accumulated==0 且 S3 已有 part → 409）本轮**未实现**。
- 判定：不升级为阻塞 —— 验收项「finalize 不创建会话、缺会话 409」已达成；该路径非 v3 引入，且不在仓库客户端流程内。

### 3.5 BLOCK-2 结论：主攻击链消除，保留 R-1

## 4. v3 增量其它点（快速回归，静态）

| 项 | 证据 | 结论 |
|---|---|---|
| H-2：simpleUpload 无刷新回调 | `UploadServiceImpl.java:827-830` + `:837, 846` | 已修 ✓ |
| M-1：失效顺序 INCR → evict → 广播 | `SpeedLimitManageServiceImpl.java:128-144`，afterCommit 注册 `:112-126` | 已修 ✓ |
| relay pacing 用当前 rate/ver | `UploadServiceImpl.java:657-667` | ✓ |
| relay 配置续期 | `UploadServiceImpl.java:705-709` | ✓ |
| 编译一致性（静态判读） | 新增/改签名与调用点、`CreditStore` 三个实现（RedisCreditStore/测试匿名类）、`CreditLimit/LimitSupplier` 用法一一对应 | 无编译级疑点（未跑 mvn） |
| API 契约 | `getChunkUrl` 仍接受 `clientLimit`（不参与共享桶），上传/下载既有接口、状态机、预签名协议未变 | 向后兼容 ✓ |
| 范围/风格 | 仅限速相关类改动，无新增 DB、无 UI | 符合 TASK exclude |

## 5. 新发现清单（含 file:line）

| ID | 级别 | 位置 | 说明 | 建议 |
|---|---|---|---|---|
| R-1 | 高 | `UploadServiceImpl.java:633-636`、`:750-754`；`RelayBufferManager.java:179-198` | §3.4：409 分叉留下本地空会话，后续 finalize 仍可在空缓冲上合并（静默截断） | 抛 409 前 cleanup，或 seq 校验前移 |
| R-2 | 中 | `SpeedLimitService.java:27-37`；`SpeedLimitCache.java:74-88, 90-112` | `resolve()` 三次 `getEntry()`：缓存 TTL 边界跨读可产出「新 ver + 旧 rate」，Lua 以 `ver > storedVer` 采纳它（`RedisCreditStore.java:110-114`）→ 之后真正的新 rate 同 ver 恒 stale：下载每次请求 1 次 EVAL 后直接放行（该 8KB 不受限），上传门控 retry 直到桶 TTL(10min) 过期；Redis 版本键丢失（回退 ver=0）同理 | `resolve()` 内取一次 entry 快照；stale 时采纳脚本返回的 ver/rate 并重试一次 |
| R-3 | 中 | `CreditBucket.java:81-94` | stale 用尽/未前进时直接 return：该块不受限，只记 `stats.stale()`，无 `degraded`/字节计数，运维不可见 | 超限时置 5s 本地降级（按 rate 供给）并记 `degraded` |
| R-4 | 低 | `st-common/src/test/java/com/stcloud/common/ratelimit/CreditBucketTest.java` | 仅 2 例：缺 review-02 放行条件中的「refresh 同值必须退出（带超时断言）」与中断用例 | 补 2 例 |
| R-5 | 低 | `st-core/src/test/java/com/stcloud/core/service/impl/RelayUploadIntegrationTest.java:462-471` | 无 redis 模式「惰性会话 + seq>1 409 + 缺会话 finalize 409」用例；tc015 命中的是 OUT_OF_ORDER 分支，未覆盖 `UploadServiceImpl.java:634`（registry 为 null 的 local 模式） | 补集成用例（mock RelaySessionRegistry） |
| R-6 | 低 | `UploadServiceImpl.java:362-372` | `getChunkUrl` 的 `clientLimit` 形参已无作用，接口保留但语义消失，易误用 | 注释说明或后续版本删除 |

R-2/R-3 不阻塞；若希望本轮收口，只需 R-1 一行 + R-4 两例。

## 6. 与 TASK 验收的关系（仅本两项 + 回归面）

| 验收项 | 结论 |
|---|---|
| 用户级聚合速率不超配置（mode=redis） | 共享桶 rate 口径单一（§2.1）；relay 门控+pacing 重复扣减更严格（已接受） |
| relay 同 uploadId 同节点、缺会话明确 409 | finalize/chunk 均 409（§3.2/3.3）；R-1 为失败恢复路径残留 |
| confirm 释放幂等、重复 confirm 不增加签发 | 本轮未改动相关代码；沿用 verification 结论 |
| Redis 故障不中断传输 | fail-open 分支未改动；本轮未复测 |
| mode=local 行为不变 | local 分支（TokenBucket/UploadGate）语义未变；沿用 verification 的 14 例 |
| mvn 构建与测试 | 主线程执行（verification §7：854 用例 0 failure）；本轮按约定未复跑 |

## 7. 放行条件

1. 阻塞项为 0，可置 `CODE_REVIEW = pass`；
2. 建议同分支下一个补丁：R-1（一行 cleanup）、R-2（单次 entry 快照）、R-4（stale 同值退出用例）；
3. 两节点 + Nginx 端到端压测、Redis 断连演练仍属 TASK 层已声明的未验证项（verification §6），不在本评审新增约束内。
