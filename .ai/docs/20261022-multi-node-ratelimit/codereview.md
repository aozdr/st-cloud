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

## code-v4 独立复审

> 归属 exitCriteria: CODE_REVIEW
> dispatchId: DISPATCH-20261022-ratelimit-review-04
> taskId: 20261022-multi-node-ratelimit
> validatedRevision: 20261022-multi-node-ratelimit-code-v4（工作区未提交）
> reviewer: reviewer-ratelimit-04（独立 reviewer，只读）
> 快照：2026-10-22T21:35+08:00；`git status --porcelain` = 16 个 M + 3 个 ??，其中新增用例 `st-common/src/test/java/com/stcloud/common/ratelimit/SpeedLimitServiceTest.java` 属本 revision。
> 方式：read/grep、只读 `git status` / `git diff`，以及只读 `docker history nginx:latest --no-trunc`（核对基础镜像自带工具）。**未执行 mvn/gradle**、未执行 docker 写操作（不 run/build/stop）、未改代码/测试/TASK/design.md/verification.md/State、未创建其他 Agent。
> 范围：只复核 code-v4 增量的六个验收面，不重复 review-03 的全量评审；测试结论为静态判读 + 与 verification.md §9–§11 记录的交叉核对，**本 reviewer 未复跑**。

### 1. 结论

**APPROVE_WITH_CHANGES**（criterionProposal.outcome = **pass**，**阻塞 0**；非阻塞残留 8 项见 §8）

| 验收面 | 结论 | 关键证据 |
|---|---|---|
| R-1：seq>1 建会话前 409、finalize 只读探测 | 符合 | `UploadServiceImpl.java:848-853`（先判 seq 再 `createSession` `:852-853`）、`:773-777` |
| R-1 回归：tc016 断言真实覆盖 | 符合 | `RelayUploadIntegrationTest.java:527-568` |
| R-2：resolve 单次快照、规则与 version 同源 | 符合 | `SpeedLimitCache.java:69-93`、`SpeedLimitService.java:26-35` |
| R-2 覆盖 | 符合 | `SpeedLimitServiceTest.java:32-61` |
| R-3：stale 三路径统一 5s 降级 + degraded | 符合 | `CreditBucket.java:83-99`、`:139-147`、`:120-130` |
| R-3 覆盖 | 符合 | `CreditBucketTest.java:87-142` |
| R-4/R-5/T-12 用例存在且语义一致 | 符合 | §5 |
| 部署/文档 | 基本符合，3 项文档级残留 | §6、§8（2/4/5） |
| 安全与数据一致性 | 未发现新问题 | §7 |

### 2. R-1 复核：409 不落在任何本地状态创建之前

- 调用点 `UploadServiceImpl.java:655-657`：`ensureRelaySession(session, seq)` 在 Content-Length 校验、`tryAcquireSeq`、读取 body 之前执行；其前只有 `chunkManager.getFirstChunk`、`fileNodeMapper.selectById` 等只读查询（`:641-654`）。
- 新实现 `UploadServiceImpl.java:834-855`：`hasSession` 只读命中（`:836-838`）→ `registry == null` 维持单机历史行为（`:839-843`）→ `config == null` 409（`:844-847`）→ **`seq > 1` 直接 409（`:848-851`）→ 之后才 `createSession`（`:852-853`）**。
- 顺序为何重要：`RelayBufferManager.createSession` 会立即建临时文件并打开输出流（`RelayBufferManager.java:86-100`、`:79-80`），空会话会被 finalize 当作完整缓冲。修复后 409 分支不留会话、不留临时文件、不消耗磁盘，也不进入 `tryAcquireSeq`。
- finalize 只读探测：`UploadServiceImpl.java:773-777` 用 `hasSession`（`RelayBufferManager.java:115-117`，仅 `containsKey`）在 `transitionSession`（`:779`）与 merge 之前 409，故缺会话的重复 finalize 稳定 409、不会空合并。
- 单机/并发回归：local 模式（registry 为 null）分支未变（`:839-843`），init 的回落本地会话逻辑未变（`:277-285`）；并发 seq=1 由 `createSession` 的 synchronized + containsKey（`RelayBufferManager.java:86-91`）与 `tryAcquireSeq`（`:145-158`）保证，本轮未改这些语义。
- 用例 `RelayUploadIntegrationTest.java:527-562`：mock 注册表（`:530-534`）→ init 后断言无本地会话（`:540`）→ seq=1 惰性建会话（`:541-542`）→ 模拟丢失 `cleanup`（`:543`）→ seq=2 断言 409（`:545-547`）→ 无会话（`:548`）→ 无临时文件（`:549`，命名规则与 `RelayBufferManager.java:310-313`、`:93` 一致，辅助方法 `:564-568`）→ finalize 409（`:551-554`）→ `never().completeMultipartUpload`（`:555`）；finally 恢复注入（`:556-561`）。
  - 判别力：旧实现（先 create 再判 seq）会在 `:548`、`:549`、`:555` 三处失败，属有效回归用例，而非重复断言。

### 3. R-2 复核

- `SpeedLimitCache.java:76-89`：一次 `getEntry()`，同 entry 取用户规则、命中角色规则与 version；`LimitSnapshot` 见 `:91-93`；`getEntry` 的 TTL 双检（`:100-114`）与 load 的版本前后双读（`:116-138`）仍在。
- `SpeedLimitService.java:26-35`：只调一次 `cache.snapshot(...)`；合并语义未变——`mostRestrictive` 仍为 `>0` 取最小、全 0 不限速（`:48-54`），规则顺序仍是 [用户规则, 角色规则]。
- 旧方法保留：`SpeedLimitCache.java:42-67`（getUserRule/getRoleRules/getVersion），生产已无调用方（见 §8-6），兼容目的成立。
- 覆盖 `SpeedLimitServiceTest.java:32-49`：`times(1).snapshot(...)`、`never()` 旧三个读取方法、最严格值 1024/4096、version=13；`:51-61` 覆盖匿名不访问缓存。该用例无法直接构造 TTL 边界跨读（需真缓存并发），属设计级保障 + 单次读取契约断言，可接受。

### 4. R-3 复核

- 常量与降级窗口：`CreditBucket.java:13-14`、`:139-147`（记 `degraded` + 置 `degradedUntilMs`，写读均在 `lock` 内，见 `:49`）。
- 三条路径统一降级（`:83-99`）：① 重试上限 `++staleRetries > 2` → `fresh = null` → `degrade()`（`:85-89`）；② refresh 抛异常或无 supplier → `refresh` 返回 null → 同一分支 `degrade()`（`:149-158` 捕获 RuntimeException）；③ 同 rate 同 ver 未前进 → `degrade()`（`:94-99`）。三者都 `continue`，进入本地按 rate 补充（`:49-59`），**不再直接 return 放行**。
- 仍允许直接放行的只有「确认不限速」：`currentRate <= 0`（`:36-38`）与刷新后 `rateBytes <= 0`（`:90-93`），与设计 §16.3 边界一致，属正确语义。
- 不产生 EVAL 风暴：降级期间 `needCredit` 恒为 false（`:64-70`），不再 `store.grant`（`:74`）；5s 窗口后才可能再 EVAL，最多 1 次/5s/桶。`onStale`（evictCache）仍在 refresh 之前（`:80-85`、`UserTransferLimiter.java:249-253`），因此同值路径只在存储端版本真落后时出现，不是常态抖动。
- failOpen=false 语义保持：catch 内仍先 `throw e`（`:123-125`）；fail-open 分支改为 `storeError()` + `degrade()`（`:126-130`），与原「storeError + degraded + 置 5s 窗口」一一对应，计数不重复。
- 覆盖 `CreditBucketTest.java:87-103`（同值：grants=1、stale=1、degraded=1、耗时 <1s）、`:105-123`（耗尽：grants=3、refreshes=2、degraded=1）、`:125-142`（denied 等待期中断退出并保留中断标记）。三例在旧实现下均会失败（旧实现 degraded 恒 0），断言与修复语义一致。

### 5. 用例（R-4 / R-5 / T-12）

- R-4：`CreditBucketTest.java:87-142` 新增 3 例，补齐 review 要求的「同值必须退出（带耗时断言）」与中断用例，并额外给出重试上限断言。
- R-5：`RelayUploadIntegrationTest.java:527-562`（tc016）注入 mock `RelaySessionRegistry`，覆盖「注册表存在、本地会话丢失」分支；这是旧用例 tc015（`RelayUploadIntegrationTest.java:463-472` 命中的是 `tryAcquireSeq` 的 OUT_OF_ORDER）覆盖不到的 `ensureRelaySession` 路径。
- T-12：`RedisCreditStoreTest.java:175-230`。边界判读：首轮 `initBytes = quantum = 100KB`（`:201-202`），快照期内补充速率 1MB/s、cap 1MB（Lua 见 `RedisCreditStore.java:57-70`：`tokens <= cap`、按 `elapsed x storedRate` 补），故 `total <= init + rate x T`；上界用实测 `elapsedMs`（`:212-217`，只会放大、不会收紧）→ `rate x elapsed + 2 x quantum ≈ 5.21MB`，实测约 5.0–5.1MB；下界 `0.9 x rate x 5s = 4.5MB`，需至少 45 次授予，8 线程 50ms 退避裕度充足；`future.get(15s)` 与 `shutdownNow`（`:215`、`:219`）使 flaky 风险低。
  - 注意：该类受 `@EnabledIfSystemProperty(test.redis.port)` 门控（`RedisCreditStoreTest.java:29`），默认全量测试会 skip，T-12 证据依赖显式运行（verification §9.4/§10 记录 total=5000000、elapsed=5001ms、upper=5201000、lower=4500000），与上述静态边界一致。

### 6. 部署与文档

- 健康检查：`docker/Dockerfile.web:13-15` 改为 `curl -fsS -o /dev/null http://127.0.0.1/`。只读 `docker history nginx:latest --no-trunc` 显示官方构建层最终 `apt-get install --no-install-recommends ... $nginxPackages gettext-base curl`，全程未安装 wget（本地镜像 ID 4c41e2d0a3a5、`DYNPKG_RELEASE=1~trixie`）→ 旧 wget 探活必然失败，curl 在镜像内且为手工安装（不被随后 auto-remove 清除），改动方向正确。运行态 healthy 复测记录见 verification §9.5（实现方记录，本 reviewer 未重建镜像）。
- upstream 日志：`docker/nginx/nginx.conf:15-17` 仅追加常量 `upstream=` 与 nginx 内建 `$upstream_addr`，无用户输入拼接、无换行注入面；该变量只含后端 IP:port，不含 token/查询串，泄漏面未扩大（原格式已记 `$request`）。
- S3 绑定与回退：`st-api/src/main/resources/application.yml:73-77`（`endpoint <- STCLOUD_STORAGE_ENDPOINT`、`public-endpoint <- STCLOUD_STORAGE_PUBLIC_ENDPOINT`，空默认）；回退在 `S3StorageConfig.java:48-61`（blank → endpoint）；`S3Client` 仍只用 endpoint（`:36-46`）→ 服务端内网直连、预签名用对外地址，绑定/回退正确，未配置时与旧行为等价。
- 别名一致性：`docker/docker-compose.cluster.yml:29-31` 映射 `STCLOUD_PUBLIC_S3_ENDPOINT -> STCLOUD_STORAGE_PUBLIC_ENDPOINT`；`README.md:253-257`、`:274-275`、`:288-289` 与 `docker/.env.example:32-35`、`:49-51` 描述一致。三处文档级残留见 §8-2/4/5。
- 未新增/修改公开接口、compose 服务、数据库或状态机；本轮 diff 仅 16 个文件（无 controller）。

### 7. 安全与数据一致性

- 无新增公开接口：`git status` 变更面不含 controller/路由；relay 相关仍只有既有的 `relay-chunk` / `relay-finalize`。
- 409 分支不消耗磁盘：在 `createSession` 之前抛出（§2），且早于读取 body 的循环（`UploadServiceImpl.java:696-707`）。
- 预签名不回泄内网地址：默认取 `publicEndpoint`，仅空白时回退 endpoint（`S3StorageConfig.java:50-51`）；残余风险在部署侧（endpoint 设内网名且 public-endpoint 留空），见 §8-2、§8-4。
- 重复 confirm 幂等未受影响：`UploadServiceImpl.java:436-438` 走 `releaseUpload(userId, chunkKey)`（`UserTransferLimiter.java:98-120`），窗口释放按 chunkKey 幂等，本轮无改动。
- 指标：R-3 的 `degraded` 计数集中在 `degrade()`（`CreditBucket.java:139-147`），store 异常与 stale 僵持共用同一窗口语义，运维可观测。

### 8. 非阻塞残留（8 项）

1. **R-1 同类的 seq ≤ 0 边界未收口**（低）：`UploadServiceImpl.java:848-851` 只拦 `seq > 1`，因此 `seq <= 0` 且本地无会话时仍会 `createSession`（`:852-853`），随后被 `tryAcquireSeq` 判 OUT_OF_ORDER 409（`:664-677`，无 cleanup），留下空会话；若同 uploadId 再 finalize，仍可在空缓冲上合并。触发需客户端发送协议外 seq（仓库 web/desktop 从 1 起）；建议一行加固（`if (seq != 1)` 或入口 `@Min(1)`），与 review-03 的接受口径一致，不升为阻塞。
2. **cluster 预签名默认值与文档冲突**（低，部署）：`docker/docker-compose.cluster.yml:31` 默认 `http://host.docker.internal:9000`，而 `README.md:256-257`、`docker/.env.example:50` 明确不要使用该地址；未设置别名时预签名 URL 只能宿主机访问。compose 不在本轮 diff，建议统一默认值或改必填（`:?`）。
3. **基础镜像未固定**（低）：`docker/Dockerfile.web:4` `FROM nginx:latest`，健康检查依赖基础镜像自带 curl；仓库其它镜像均按 digest 固定（如 `docker/docker-compose.yml:7`、`:30`、`:47`），建议同样固定，避免 mutable tag 让健康检查再次失效。
4. **S3StorageConfig Javadoc 与现语义相反**（低，文档）：`S3StorageConfig.java:22-26` 写「publicEndpoint 设内网地址、endpoint 设对外地址」，与 `application.yml:73-77` 及实际回退（`:48-61`）正好相反，易误导运维；建议下一补丁同步注释。
5. **README 表格列数不匹配**（低，文档）：`README.md:288-289` 新增两行在「环境变量（生产必设）」2 列表（`:282-283`）内多写第 3 列（默认值），GFM 按表头列数截断，默认值不会呈现；建议并入说明列或给该表加列。
6. **旧缓存读取方法已无生产调用方**（低）：`SpeedLimitCache.java:42-67` 仅测试 `never()` 引用；保留兼容可接受，后续可标注用途或清理。
7. **R-3「refresh 异常」缺独立用例**（低，测试强度）：仅通过重试耗尽共用 `fresh == null` 分支覆盖，`LimitSupplier` 抛 RuntimeException / supplier 为 null 的降级无独立断言；建议补 1 例。
8. **既有并发/容量语义残留**（低）：finalize 的 `hasSession` 与 `transitionSession` 之间无锁（`UploadServiceImpl.java:775-781`），并发 cleanup 可能让 finalize 得到 404 CHUNK_NOT_FOUND（`RelayBufferManager.java:302-308`）而非 409；5s 降级窗口最坏 N x rate（与既有 fail-open 同级，verification §14 已记录），故障期双节点聚合压测、节点故障迁移（客户端重放 seq=1 落到新节点）仍未覆盖，属 TASK 层已声明的未验证项。

### 9. 放行建议

1. 阻塞为 0，可置 `CODE_REVIEW = pass`（criterionProposal.outcome = pass）。
2. 下一补丁优先级：§8-1（一行 seq 加固）、§8-2/§8-5（默认值与文档一致）、§8-4（注释纠偏）；§8-3 建议随镜像基线一起固定。
3. 本段结论只对快照 `20261022-multi-node-ratelimit-code-v4` 有效；代码再变需重新复核。


## code-v4 独立复审（review-05）

- 评审：reviewer-ratelimit-05；dispatchId `DISPATCH-20261022-ratelimit-review-05`；快照 `20261022-multi-node-ratelimit-code-v4`（工作区未提交）。
- 变更面：`git status --porcelain=v1` / `git diff --stat` 核对为 17 个已改文件 + 新增 `SpeedLimitServiceTest.java`；本段只读评审，未执行 mvn/gradle，未修改源码/测试/其他文档（除本段追加）。
- **结论：APPROVE，阻塞项 0。** 依据为静态核对（调用顺序、逻辑分支、断言）+ `verification.md` §10/§12 已记录的执行证据；本轮未重复执行构建，故不复述测试通过数。

### 逐项核对

| 项 | 结论 | 证据（file:line） |
|----|------|------------------|
| R-1 seq>1 先拒后建 | PASS | `ensureRelaySession(session, seq)` 在读取 body 之前调用（`UploadServiceImpl.java:657`，读取循环始于 `:696`）；顺序为 hasSession → registry 配置 → `seq > 1` 抛 CONFLICT（`:848-851`）→ 仅 seq==1 才 `createSession`（`:852-853`） |
| R-1 finalize 只读探测 | PASS | `UploadServiceImpl.java:773-777` 仅 `hasSession` 判定后 409，无 create 路径；注释覆盖「第二次 finalize 在空缓冲上合并」 |
| R-1 tc016 断言覆盖 | PASS | `RelayUploadIntegrationTest.java:528-562`：init 不建会话（`:540`）→ 首片惰性创建（`:541-542`）→ cleanup 模拟丢失（`:543`）→ seq=2 409（`:545-547`）→ 无会话（`:548`）→ 无临时文件（`:549`，`hasTempFile` `:564-568` 与 `RelayBufferManager.java:93/311-313` 的 sanitize+".tmp" 一致，非空断言）→ finalize 409（`:551-554`）→ `never().completeMultipartUpload`（`:555`）；`s3-test-upload-id` 与 `:212` mock 值一致，不会因 s3UploadId 不匹配而「碰巧」通过 |
| R-2 一次快照 | PASS | `SpeedLimitCache.java:76-89` 在单次 `getEntry()` 内同时取 userRule / 命中角色规则 / version；`SpeedLimitService.resolve()` 只调 `cache.snapshot(...)`（`SpeedLimitService.java:27`），无第二、三次 `getEntry` |
| R-2 合并语义兼容 | PASS | 用户规则在前、角色规则按 roles 顺序追加（`SpeedLimitCache.java:79-87`），与旧 `getRoleRules`（`:49-62`）的 null/空处理及遍历顺序等价；`mostRestrictive` 取最小正值（`SpeedLimitService.java:48-54`）与顺序无关；旧 accessor 保留（`:42-67`），生产代码已无调用方（grep 仅测试 `never()` 引用） |
| R-2 用例 | PASS | `SpeedLimitServiceTest.java:32-49` 断言最严格合并 + 同源 version=13 + snapshot 恰一次 + 三个旧 accessor `never()`；`:51-61` 匿名不访问缓存 |
| R-3 三条 stale 路径统一降级 | PASS | `CreditBucket.java:85-99`：`++staleRetries > 2` 与 `refresh()` 返回 null（含 supplier 抛 RuntimeException，`:149-158`）共用 `fresh == null → degrade(); continue`（`:86-89`）；刷新值未前进 `degrade(); continue`（`:94-99`）；三处均不再 `return` 放行 |
| R-3 degrade 语义 | PASS | `CreditBucket.java:140-147`：`stats.degraded()` + `degradedUntilMs = now + 5000`；store 异常路径复用同一 `degrade()`（`:120-130`，`stats.storeError()` 保留），窗口内按 rate 本机补充（`:49-70`） |
| R-3 failOpen=false 仍抛 | PASS | `CreditBucket.java:123-125` 未改，直接 `throw e` |
| 用例：CreditBucket 3 例 | PASS | `:89-102` 同值 stale：1 次 EVAL、stale=1、degraded=1、<1s；`:107-122` 持续前进：grants=3（EVAL 上界）、refreshes=2、degraded=1；`:127-141` denied 等待期中断退出且保留中断标记（对应 `sleep` `:161-168`） |
| 用例：RedisCreditStore 8x5s | PASS（有 flaky 风险） | 双 store 实例 + 8 线程 5s，上界 rate×elapsed+2×quantum、下界 0.9×rate×5s；记录值 total=5,000,000 / upper=5,201,000 / lower=4,500,000（verification §9.4）。风险：真实 Redis + 5s 实时窗口，共享/高负载下下界可能抖动；该用例按 redis 可用性 gated（全量回归 skipped，单独执行通过） |
| Dockerfile.web 健康检查 | PASS（有残留） | `docker/Dockerfile.web:14-15` `curl -fsS -o /dev/null`：HTTP≥400 返回非 0，比 wget 更严格；基础镜像 `nginx:latest` 未固定（`:4`），curl 依赖可变基线 |
| nginx upstream 日志 | PASS | `docker/nginx/nginx.conf:16` 追加 `upstream=$upstream_addr`，语法正确、仅内部地址，无 token；原格式已含 `$request`，无新增敏感面 |
| application.yml S3 绑定/回退 | PASS | `application.yml:74` endpoint←STCLOUD_STORAGE_ENDPOINT，`:77` public-endpoint←STCLOUD_STORAGE_PUBLIC_ENDPOINT（空默认）；`S3StorageConfig.java:18/27/50-51` 松散绑定 + isBlank 回退 endpoint，预签名不回泄内网地址 |
| 安全/一致性 | PASS | 无 controller/路由改动（无新增公开接口）；409 在 `createSession` 与读 body 之前（`UploadServiceImpl.java:657` vs `:696`），不占磁盘；重复 confirm 幂等路径（`tryAcquireSeq` `:664-671`）未改；「刷新后确认不限速」放行（`:90-93`）属语义性放行而非 stale 放行 |

### 非阻塞残留（7 项，按优先级）

1. **R-1 同类边界：`seq <= 0` 仍会创建空会话**（低，建议下一补丁一行加固）：守卫只拦 `seq > 1`（`UploadServiceImpl.java:848-851`），`seq <= 0` 且本地无会话时仍 `createSession`（`:852-853`），随后 `tryAcquireSeq` 判非 ACQUIRED 后 409（`:672-677`）且无 cleanup，留下空会话；若同 uploadId 再 finalize，仍可在空缓冲上合并。触发需协议外 seq（仓库 web/desktop 从 1 起），故不阻塞；建议 `if (seq != 1)` 或入口 `@Min(1)`。
2. **`S3StorageConfig` Javadoc 与现语义相反**（低，文档）：`S3StorageConfig.java:22-26` 写「publicEndpoint 设内网、endpoint 设对外」，与 `application.yml:73-77`、README 部署说明及实际回退（`:48-61`）相反，易误导运维。
3. **cluster 预签名默认值与文档冲突**（低，部署）：`docker/docker-compose.cluster.yml:31` 默认 `http://host.docker.internal:9000`，而 README 与 `docker/.env.example:49-51` 明确要求不要用该地址；未设置别名时预签名 URL 仅宿主机可达。compose 不在本轮 diff，建议统一默认值或改必填。
4. **README 环境变量表列数不匹配**（低，文档）：新增两行为 3 列（`README.md:288-289`），所在表头为 2 列，GFM 会截断默认值列。
5. **R-3 `refresh()` 返回 null 的分支无独立用例**（低，测试强度）：exhaustion 用例走的是 `staleRetries>2` 产生的 null，supplier 抛异常/为 null 的降级仅被同一分支隐式覆盖；建议补 1 例断言 degraded 且无 EVAL 风暴。
6. **RedisCreditStoreTest 5s 实时窗口稳定性**（低）：下界 0.9×rate×5s 依赖真实 Redis 吞吐，CI/共享 Redis 并发下需观察；已用 println 输出 total/elapsed。
7. **旧缓存 accessor 无生产调用方**（低，清理项）：`SpeedLimitCache.java:42-67` 仅被 `SpeedLimitServiceTest` 的 `never()` 引用，保留兼容可接受，建议后续标注用途或移除。

### 放行建议

1. 阻塞 0，`CODE_REVIEW` 可置 pass（validatedRevision=`20261022-multi-node-ratelimit-code-v4`）。
2. 下一补丁优先级：残留 1（一行加固）→ 残留 2/3/4（文档与部署默认值一致）→ 残留 5（补 1 例）。
3. 本段结论只对上述快照有效；代码再变需重新复核。

## code-v5 独立复审（review-06）

- 评审：reviewer-ratelimit-06；dispatchId `DISPATCH-20261022-ratelimit-review-06`；快照 `20261022-multi-node-ratelimit-code-v5`（工作区未提交，HEAD=`2007c41`）。
- 变更面：`git status --porcelain=v1` / `git diff --stat` 核对为 11 个已改文件（187+/18-）+ 1 个未跟踪 dispatch envelope；`git diff` 未出现 controller/公开接口/DDL/前端变更。本段只读评审：未执行 mvn/gradle、未做 git 写操作、未修改源码/测试/TASK/design/verification/State，只追加本段并写入结果 JSON。评审收尾时 `verification.md`（+48）与 `state` 被实施侧/主线程并行更新（非本评审写入），源码与测试的 diff 面在评审开始与收尾时一致，故本段结论对 code-v5 源码面有效。
- **结论：APPROVE，阻塞项 0**（validatedRevision=`20261022-multi-node-ratelimit-code-v5`）。依据为静态核对（调用顺序、CAS 语义、断言与实现映射）+ 本地只读镜像/compose 校验；v5 执行证据由实施侧记录并已回填 `verification.md:388-432`，本评审未复跑 mvn/docker，只引用该记录（见非阻塞 6）。

### 逐项核对

| 项 | 结论 | 证据（file:line） |
|----|------|------------------|
| a1 `seq != 1` 守卫在 createSession 之前 | PASS | `UploadServiceImpl.java:855-859` 抛 CONFLICT，`createSession` 在其后 `:860-861`；调用点 `:657` 位于读 body（`:696` 起）与 `tryAcquireSeq`（`:664`）之前，拒绝时不占磁盘 |
| a2 tc017 判别力 | PASS | `RelayUploadIntegrationTest.java:568-594`：init 不建会话（`:581`）→ seq=0 409（`:583-585`）→ 无会话（`:586`）→ 无临时文件（`:587`，`hasTempFile` `:596-600`）。旧实现（`git diff` 删除的 `if (seq > 1)`）下 seq=0 会走到 `createSession`，而 `RelaySession` 构造即 `CREATE` 临时文件（`RelayBufferManager.java:79-80`、`:92-95`），故 `:586`/`:587` 两条断言都失败；`:585` 的 409 断言两版都成立（旧版由 `tryAcquireSeq` OUT_OF_ORDER 抛同码），不构成判别力，真正有判别力的是会话/文件两条 |
| a3 客户端无回归 | PASS | web 端 relay seq 从 1 起（`st-web/src/hooks/useUpload.tsx:142`）；单机模式在 `UploadServiceImpl.java:846-850` 提前返回，既有单机行为不变；已存在会话时 `:843-845` 早退，seq≥2 的后续小块不受守卫影响 |
| b1 认领后二次探测收敛为 409 | PASS | `UploadServiceImpl.java:787-789` 在 MERGING 认领（`:779-781`，CAS）之后、`finalize`（`:791`）之前二次 `hasSession`，缺失抛 CONFLICT；该异常进入既有 catch（`:802`）后由 `:806` 的 `[MERGING]` 认领转 ABORTED 并清理，客户端看到 409 而非 `CHUNK_NOT_FOUND(404)`（`RelayBufferManager.java:302-308`） |
| b2 失败清理不误伤并发胜者 | PASS | catch 仅认领自己持有的状态：merge 前 `[MERGING]`、merge 后 `[FAILED]`（`UploadServiceImpl.java:803-806`），`transitionStatus` 是 `status IN (...)` 的 CAS（`UploadSessionMapper.java:19-26`）；CAS 失败整块 cleanup 跳过（`:807-822`），不会 abort 并发胜者 multipart；`finally` 只移除 registry 登记（`:824-828`），与 code-v4 语义一致 |
| b3 竞态是否真的存在（非阻塞，注释精度） | PASS（注释略强） | 认领成功后缓冲实际不可被删：`abortSession` 在 DB 状态=MERGING 时直接返回（`RelayBufferManager.java:276-279`），其清理以 0/4→3 的 CAS 为前提（`:281`、`:297-299`）；若清理先赢 CAS，则 finalize 的 0/4→1 认领失败并在 `:780` 直接 409。因此 `UploadServiceImpl.java:784-786` 描述的时间窗已被 CAS 收敛，本次二次探测属纵深防御（开销一次 `ConcurrentHashMap` 查询，无害） |
| c refresh 异常用例与实现一致 | PASS | `CreditBucketTest.java:144-163`：supplier 抛 RuntimeException → `refresh()` 捕获返回 null（`CreditBucket.java:149-158`）→ `fresh == null` 分支 `degrade(); continue`（`:85-89`）→ 降级窗口按 rate 本机补充（`:49-70`），不再 EVAL。断言 1 次 EVAL（`:159`）、stale=1（`:160`）、degraded=1（`:161`）、<1s（`:162`）与实现逐条对应；`staleStore` 返回 `stale=true`（`:166-173`），先命中 `grant.stale()` 分支，不累加 granted/denied；异常在 `refresh()` 内被吸收，不计 storeErrors，与断言一致；`RateLimitStats` 键名与累加匹配（`RateLimitStats.java:30-36`、`:42-49`） |
| d1 S3StorageConfig Javadoc | PASS | `S3StorageConfig.java:22-27` 与实现一致：`endpoint` 只给 `S3Client`（`:36-46`），`publicEndpoint` 只给 `S3Presigner` 且空值回退 endpoint（`:48-61`）；与 `application.yml:74-77` 一致。diff 仅注释，无行为变化 |
| d2 SpeedLimitCache accessor 注释 | PASS | `SpeedLimitCache.java:42-45`、`:53`、`:69-72` 引用的 `snapshot(Long, List)`（`:81-94`）与 `LimitSnapshot#version()`（`:96-98`）真实存在；生产解析确已走 snapshot（`SpeedLimitService.java:27-35`）。diff 仅注释，无行为变化 |
| e1 nginx digest 固定 | PASS | `docker/Dockerfile.web:4-6` 固定 `sha256:4c41e2d0a3a5…`；本地只读校验 `docker image inspect`：`Id` 与 `RepoDigests` 同为该 digest、`NGINX_VERSION=1.31.8`、`trixie`、`linux/amd64`，按 `nginx@sha256:…` 可直接解析到本地镜像；健康检查（`:15-17`）未改，基础镜像与 v4 实际使用的一致，无行为漂移 |
| e2 compose 必填可解析且 fail-fast | PASS | `docker/docker-compose.cluster.yml:29-33` 用 `${STCLOUD_PUBLIC_S3_ENDPOINT:?…}`：`docker/.env` 含该键时 `compose config --quiet` 退出 0，解析值在 app1/app2/x-app 三处均为客户端可达地址；改用不含该键、以及含空值的临时 env-file 后，`compose config` 均退出 1 且报 `required variable STCLOUD_PUBLIC_S3_ENDPOINT is missing a value: set STCLOUD_PUBLIC_S3_ENDPOINT in docker/.env …`（注：PowerShell 里 `$env:X=''` 会移除变量，无法复现缺失场景，故用 env-file 验证） |
| e3 README 表格与部署说明 | PASS | `README.md:282-291` 表头两列、各行两格；`README.md:255-256` 与 `docker/.env.example:49-52` 的「必填、缺失报错」表述与 compose 实测一致。注：表格两列化实际发生在 v4 提交 `2007c41`，v5 的 README diff 只有集群段落（见非阻塞 3） |
| f 回归与安全面 | PASS | `git diff` 无 controller/公开接口/DDL 变更；`seq != 1` 是收紧而非放宽（在创建缓冲、读 body 之前拒绝协议外输入）；compose 改动只在部署面；错误消息与日志不含凭证；二次探测只增加一次 map 查询。`UploadServiceImpl.java:860-861` 的 `createSession` 参数与 v4 相同（`config.rateBytes()`/`config.relayChunkSize()`），无协议变化 |

### 非阻塞残留（8 项，按优先级）

1. **「二次探测抵消的竞态」在当前不变量下不可达**（低，注释/文档精度）：如 b3 所述，清理必须先赢状态 CAS，认领成功后清理会被 MERGING 守卫挡回；若清理先赢 CAS，则 `:779` 已经 409。建议下一补丁把 `UploadServiceImpl.java:784-786` 与 `design.md:413-415` 改成「纵深防御：即使未来出现不经状态 CAS 的缓冲清理，也收敛为 409」，避免读者误判当前存在 404 竞态。
2. **`:787` 探测与 `:791` finalize 之间仍是理论窗口**（低）：该窗口内若缓冲消失，`finalize` 会抛 `CHUNK_NOT_FOUND`（`RelayBufferManager.java:302-308`）→ 404。当前无删除 `upload_session` 行的生产路径（grep 仅测试清理），且所有清理以状态 CAS 为前提，故不可达；留作后续硬化即可。
3. **design §17.4 归因偏差**（低，文档）：`design.md:429` 称 v5 把 README 环境变量表补齐为两列，但 v5 README diff 只有 `README.md:255-256` 段落，表格两列化在 v4 提交 `2007c41`；建议改为「沿用 v4 两列表格」。
4. **digest 的架构/缓存面**（低，部署）：本地核验对象是 `linux/amd64` 单平台镜像；若该 digest 对应单平台 manifest，arm64 宿主或清缓存后拉取会失败。建议 CI 增一步 `docker buildx imagetools inspect` 或实际 pull 校验，并在 README 注明 amd64 前提。
5. **`.env.example` 示例行仍为注释**（低，部署）：新克隆 + cluster 部署会 fail-fast（已实测报错文案指向 `docker/.env`），属预期；发布说明或 README 快速开始里再提一句即可。
6. **v5 执行证据为实施侧记录，本评审未复跑**（低，门禁）：评审收尾时 `verification.md:388-432` 已回填 v5 证据（`RelayUploadIntegrationTest` 20 例含 tc017、`CreditBucketTest` 6 例、compose config exit 0、全量 864 例）；记录对应同一 code-v5 快照，但本评审按只读约束未执行 mvn/docker，证据真实性由 VERIFIED 门禁与主线程复核，建议确认两条新用例名与当前测试文件一致（本段已静态核对存在）。
7. **tc017 未覆盖「拒绝后可恢复」**（低，测试强度）：用例未断言随后用 seq=1 重试仍能创建缓冲并成功（新实现允许，旧实现因已建空会话会走另一分支）；补一条正向断言可同时锁定恢复路径。
8. **既有残留（不在 v5 变更面）**（低）：seq=1 重放落到第二个节点时仍会惰性建缓冲并重复 append（依赖 Nginx 按 uploadId 粘滞，属 v4 设计前提）；`design.md:431-436`（§17.5 桌面安装包）与本任务仓库范围无关，属文档噪声。

### 放行建议

1. 阻塞 0，`CODE_REVIEW` 可置 pass（validatedRevision=`20261022-multi-node-ratelimit-code-v5`）。
2. 下一补丁优先级：残留 1（注释精度）→ 3（文档归因）→ 4/5（部署校验与说明）→ 2/6/7（硬化与用例补强）。
3. 本段结论只对快照 `20261022-multi-node-ratelimit-code-v5` 有效；代码再变需重新复核。


## code-v6 独立复审（review-07）

- 评审：reviewer-ratelimit-07；dispatchId `DISPATCH-20261022-ratelimit-review-07`；快照 `20261022-multi-node-ratelimit-code-v6`（工作区未提交，HEAD=`2007c41`）。
- 变更面（只读 `git status --porcelain=v1` / `git diff --stat`）：6 个已改文件 260+/7-：`UploadServiceImpl.java`(+11)、`RelayUploadIntegrationTest.java`(+38)、`README.md`(+7/-2)、`design.md`(+22)、`verification.md`(+45)、`.ai/state/20261022-multi-node-ratelimit.yaml`(+144)；另有 1 个未跟踪 dispatch envelope。`git diff` 内无 controller/公开接口/DDL/前端/构建脚本变更。
- 本段为只读评审：未执行 mvn/gradle、未做 git 写操作、未改源码/测试/TASK/design/verification/State，只追加本段并写结果 JSON。实施侧记录的 `verification.md:466-488` 运行证据（21 例 relay、全量 865 例、两节点实测）本评审未复跑，仅做静态一致性与只读命令（`Get-FileHash`/`Get-AuthenticodeSignature`/`git`）核对。
- **结论：APPROVE，阻塞项 0**（validatedRevision=`20261022-multi-node-ratelimit-code-v6`）。守卫位置、fail-closed、数据完整性、tc018 判别力、运营文档与 SHA256 发布校验均与实现一致；非阻塞残留 6 项见末。

### 逐项核对

| 项 | 结论 | 证据（file:line） |
|----|------|------------------|
| a1 守卫只在“本地会话缺失 + registry 有配置 + seq==1”生效 | PASS | `UploadServiceImpl.java:847-849` 已有会话早退；`:850-854` registry=null（单机）早退；`:855-858` 配置过期 409；`:859-863` seq!=1 409；`:866-867` 才调 ListParts；`:868-870` 非空 409；`:871-872` createSession 在其后。调用点 `:657` |
| a2 调用在 createSession 之前、且不改变正常首传 | PASS | 守卫位于 `:866-870`，createSession `:871-872`；S3 无 part 时返回空列表继续建会话（`tc018:622-625`）；`:868` 有 null 兜底，mock/异常实现也不会误判 |
| a3 已存在会话 / 单机 / team / 非首片路径 | PASS | 同节点重复首片走 `:847-849` 早退（幂等确认 `:664-671`）；单机 `:850-854` 直接返回，历史行为不变；team 入口 `relayTeamChunk`(`:631-636`) 复用同一 `relayChunkInternal`(`:638-658`)，共享守卫；控制器未改（`FileController.java:218-231`、`TeamUploadController.java:135-147`） |
| b1 fail-closed：ListParts 失败不静默放过 | PASS | `StorageServiceImpl.java:235-238`：`S3Exception` → `BusinessException(STORAGE_SERVICE_ERROR, "查询上传状态失败")`；守卫抛在 createSession 之前，异常上抛 → 不建空会话。注：非 S3 服务错误（如 `SdkClientException`）会走 500 `INTERNAL_ERROR`（`GlobalExceptionHandler.java:92-96`），仍不建会话（见非阻塞 2） |
| b2 S3 调用不在 DB 事务内 | PASS | `UploadServiceImpl` 全类无 `@Transactional`（类声明 `:60`，全文无事务注解），`relayChunk`/`relayChunkInternal` 无事务；`UploadStorageManager.java:58-60` 直连 `StorageService`，无事务包装。符合“核心写路径不在 DB 事务内调外部网络” |
| b3 调用在读取 body 之前 | PASS | 控制器只取 `getContentLengthLong()` 并把 `getInputStream()` 透传（`FileController.java:228-230`）；服务层 `InputStream` 首次读取在 `:696`，守卫 `:866` 早于它，也早于 Content-Length 校验 `:658-662` 与 `tryAcquireSeq` `:664` |
| b4 S3 调用次数 | PASS | diff 只新增 1 处 ListParts（`:866`），仅“本地无会话 + registry 有配置 + seq==1”到达；每次建会话至多一次。集群模式 init 成功后不建本地会话（`:277-284`），故每次 relay 上传在节点首片各一次（见非阻塞 3）；另一处 ListParts（`:345`）为既有状态查询能力，非本轮新增 |
| c1 已有 part 时不再从 part 1 开空会话 | PASS | `:866-870` 在 `:871` 之前拒绝，未创建临时文件（`RelayBufferManager.java:86-100` 才 CREATE），因此不进入后续 append/uploadPart/finalize |
| c2 不误伤“正常首传”（S3 无 part） | PASS | `tc018:622-625` 把 mock 改回空列表后 seq=1 仍能建会话；`verification.md:481-488` 的实机路径（首传经 Nginx → 会话节点）无 409 |
| c3 并发两节点首片仍依赖粘滞的边界是否被诚实记录 | PASS | `design.md:450-451` 明确“本守卫不替代 Nginx uploadId 粘滞，只消除‘已知已有 part 仍开新会话’的确定性风险”；`verification.md:452` 同口径复述，无“已解决竞态”的过度声明 |
| d1 tc018 判别力 | PASS | `RelayUploadIntegrationTest.java:597-632`：mock 返回 `[1]` → `:616-618` 断言 CONFLICT、`:619` 无会话、`:620` 无临时文件；旧实现（无守卫）在 `:616-617` 即失败——`postChunk` 会走到 createSession（`RelayBufferManager.java:86-100`，构造即建临时文件 `:79-80`）；即便绕过 assertThrows，`:619`/`:620` 亦红。用例总数 21 个 `@Test` 方法，与 `verification.md:470` 的“21 用例（含 tc018）”一致（`:79` 是 `@TestConfiguration`，不计数） |
| d2 恢复路径 | PASS | `:622-625` 清空 part 后 seq=1 成功建会话；`finally` `:626-631` 复位 registry 并清理缓冲，不污染其他用例 |
| e1 README 运营/分发与实现一致 | PASS | `README.md:255-257`（N×rate、stats 每 30s 采集、degraded/storeErrors 告警、version 收敛、“限速非硬配额”）↔ `TransferController.java:30-45`（`/api/transfer/rate-limit/stats` 为 `hasRole('ADMIN')`，返回 mode+计数；`/speed-limit` 返回 `SpeedLimitResult` 含 `version`，`SpeedLimitResult.java:16`）、`RateLimitStats.java:42-50` 键名一致；`README.md:249-251` 的首片守卫描述与 `:866-870` 一致 |
| e2 SHA256 与安装包实际一致 | PASS | 只读 `Get-FileHash -Algorithm SHA256` = `76A4A70F54A8F130490E6088CBDB910419F08D3DD8DCE7C17201D76CF9661756`，与 `st-desktop/release/SHA256SUMS.txt:1`（小写同值，sha256sum 两空格格式）一致；安装包 82,617,490 字节；`Get-AuthenticodeSignature`=`NotSigned`，与 `README.md:227`/`design.md:458`/`verification.md:464` 的“未签名”表述一致 |
| f 安全面（无新接口/不泄露/不越权） | PASS | `git diff` 无 controller/公开接口/DDL/前端；守卫复用既有 `StorageService.listUploadedParts`（`StorageService.java:108`、`UploadStorageManager.java:58-60`），key 来自会话而非请求（`requireOwnedSession` 已按 tenant/user 绑定 `:927-933`，team 再校验 spaceId `:959-968`），无任意 key 注入；409 文案“检测到该上传已有分片，请重新初始化上传”不含 bucket/key/endpoint；未知异常 `INTERNAL_ERROR` 不带消息（`GlobalExceptionHandler.java:92-96`）；无跨租户或越权面变化 |

### 非阻塞残留（6 项，按优先级）

1. **“静默数据损坏”影响措辞略强**（低，文档精度）：`design.md:442-444`、`README.md:249-251` 与 `UploadServiceImpl.java:864-865` 注释称空缓冲会“覆盖旧分片并与剩余旧 part 混拼”。在当前协议下，能从 seq=1 重放的客户端会从 byte 0 完整重放，新节点以相同的 `relayChunkSize`/`partMinSize` 重写 part1..，而 `completeMultipartUpload` 使用 ListParts 全量 part（`StorageServiceImpl.java:168-197`），因此“可证损坏”需要“只重放前缀后 finalize”的客户端行为，而 strict-seq（`UploadServiceImpl.java:664-677`）使该路径当前不可达；守卫的确定收益是阻止重复编号写入与消除潜在的混片窗口。建议措辞降级为“重复写入 + 潜在混片（取决于客户端后续行为）”，与本轮“纵深防御”定位一致。
2. **fail-closed 的错误码表述偏宽**（低，文档）：`design.md:446-447`/`verification.md:451` 写“ListParts 失败时由 STORAGE_SERVICE_ERROR 拒绝”；实际仅 `S3Exception` 映射为该码（`StorageServiceImpl.java:235-238`），网络层 `SdkClientException` 会冒泡为 500 `INTERNAL_ERROR`（`GlobalExceptionHandler.java:92-96`）。两者都不建会话（fail-closed 成立），建议注明两类失败的分工。
