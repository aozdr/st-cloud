# Change Report — TASK-SEC-BATCH-2（S-06 分享码 / S-07 原子计数 / S-09 流式限速）

## 元信息

- Task ID: `TASK-SEC-BATCH-2`
- Agent: executor（taskType=implement）
- dispatchId: `sec-batch2-001`
- claimedFile: `inbox-sec-batch2-001.md`
- 日期: 2026-08-14
- 来源: `.ai/docs/20260814-project-code-review/security-recheck.md` 的 S-06（P2 分享码熵不足/无冲突重试）、S-07（P2 download_count TOCTOU）、S-09（P2 流式无速率限制）

## 背景

安全复检确认分享模块仍有三项中危问题：分享码为 8 位十六进制（32 bit 熵）且唯一索引冲突时直接 500；下载计数“先查后增”存在并发竞态可突破次数上限；`streamShareFile` 匿名大文件全速回传无速率限制（带宽 DoS 面）。用户已定版：分享码改为 4 位数字字母；流式默认限速 5MB/s。本次按 TASK 定版方案在 `st-share` 内修复三处并补充测试。

## 输入

- `TASK-SEC-BATCH-2.md`（定版方案与验收标准）
- `security-recheck.md`（S-06/S-07/S-09 根因与修复建议）
- `st-core` 只读参考：`DownloadServiceImpl.pacedTransfer`（限速节奏风格）
- `st-share` 现有实现与集成测试（H2 真实 Mapper + Mock 外部服务），`file_share` 表结构（`uk_share_code` 唯一索引、`download_limit/download_count` 列）

## 分析

1. **S-06**：`generateShareCode` 当前用 `UUID` 前 8 位十六进制（32 bit 熵），且生成后直接 insert，`uk_share_code` 冲突会抛 `DuplicateKeyException`（500）。按定版改为 `SecureRandom` 从 32 字符集（排除易混字符 `0/O/1/I`）生成 4 位，生成后先按 `share_code` 查询冲突，冲突重试最多 8 次，仍冲突抛业务异常；历史 8 位 hex 分享码不变，`share_code VARCHAR(32)` 无需变更。
2. **S-07**：`getDownloadUrl` 与 `streamShareFile` 均是“先查 `downloadCount >= downloadLimit` 再无条件 `+1`”，并发下可超限。改为原子条件更新 `UPDATE ... WHERE id=? AND (download_limit IS NULL OR download_count < download_limit) SET download_count = download_count + 1`，按影响行数判定：`updated == 0` 抛 `SHARE_ACCESS_DENIED("下载次数已达上限")`。`getDownloadUrl` 移除前置查询（以原子更新为唯一闸门）；`streamShareFile` 保留前置快速失败（TASK 允许“保留快速失败可选”），最终闸门为流式成功后的原子条件更新，保持“仅成功后计数”既有语义。
3. **S-09**：`streamShareFile` 8KB buffer 直读直写无任何限速。按“按已写字节计算目标耗时，超速则 `Thread.sleep`”定版方案新增 `STREAM_RATE_BYTES_PER_SEC = 5MB/s` 常量与 `paceStream` 辅助方法：累计字节/速率得到理论最短耗时，未达标则休眠补齐，保证平均速率不超过 5MB/s（中文注释）。

## 决策

按 TASK 定版方案实施，仅修改 `st-share/**`：

- `ShareServiceImpl.generateShareCode`：`SecureRandom` + 4 位 32 字符集 + 冲突查询重试（最多 8 次）+ 仍冲突抛业务异常。
- `ShareServiceImpl.getDownloadUrl`：删除前置“检查-拒绝”，改由原子条件更新统一完成检查与递增。
- `ShareServiceImpl.streamShareFile`：保留快速失败前置检查；流式成功后改为原子条件更新；读块循环接入 `paceStream` 限速。
- 新增测试：集成测试补 S-06 分享码格式、S-07 原子计数（未达上限递增 / 已达上限拒绝且不再递增）、S-09 限速耗时断言；新增 `ShareServiceImplShareCodeUnitTest` 单元测试（无冲突生成 / 冲突重试 / 超限抛异常不落库）。

## 修改文件清单

- 修改：`st-share/src/main/java/com/stcloud/share/service/impl/ShareServiceImpl.java`
- 修改：`st-share/src/test/java/com/stcloud/share/ShareServiceImplSecurityIntegrationTest.java`（新增 4 个用例）
- 新增：`st-share/src/test/java/com/stcloud/share/ShareServiceImplShareCodeUnitTest.java`（3 个用例）

未改动其它 `st-*` 模块、`st-web`、`docker/mysql/init`、数据库脚本（`share_code` 列宽不变，无迁移）。

## 与验收标准对照

| 验收项 | 结果 |
|--------|------|
| shareCode 4 位数字字母（排除 0O1I）且冲突重试 | PASS（单元测试覆盖生成/重试/超限三分支） |
| 下载计数原子条件更新（rg 复核无“先查后增”；并发不超限） | PASS（仅保留 streamShareFile 快速失败，最终闸门为原子更新） |
| streamShareFile 限速 5MB/s 生效（代码 + 测试） | PASS（1MB 数据耗时 ≥ 理论下限的宽松断言） |
| `mvn -q -pl st-share -am test` EXIT=0 | PASS（st-share 33 用例全绿：Expiry 10 + Security 20 + ShareCode 3） |
| 核心逻辑中文注释 | PASS |
| 未改动其它模块 | PASS |

## 测试结果

- 命令：`mvn -q -pl st-share -am test`
- 结果：EXIT=0；`ShareServiceImplExpiryIntegrationTest` 10/10、`ShareServiceImplSecurityIntegrationTest` 20/20（含新增 4 例）、`ShareServiceImplShareCodeUnitTest` 3/3；上游 st-auth/st-core/st-team 测试全部通过。
- rg 复核：`download_count = download_count + 1` 仅存在于两处原子条件更新内；`ShareServiceImpl` 无 `UUID` 残留；`getDownloadCount() >= getDownloadLimit()` 仅剩 streamShareFile 快速失败一处（TASK 允许）。

## 风险

- **分享码空间**：4 位 32 字符集共约 104 万组合（20 bit 熵），较原 32 bit 熵下降；这是用户定版（分享码 4 位数字字母）的取舍，配合冲突重试不会产生重复码，但公开接口无限流问题仍存在（S-06 配套的 IP 限流不在本 TASK 范围，需主线程另行立项）。
- **流式计数时序**：`streamShareFile` 的原子更新在流式成功后执行（保持“仅成功后计数”既有语义），极端并发下（流式传输期间配额被其它请求耗尽）可能出现“已传输字节后计数更新失败并抛异常”的窄窗口；数据库计数本身不会超限。如需传输前强闸门，需将语义改为“开始即计数”，属产品口径决策。
- **限速精度**：`Thread.sleep` 粒度约 1ms，实际平均速率 ≤ 5MB/s（只会更慢），符合“不超过上限”语义；小文件（< 8KB）不受限速影响。
- **冲突重试并发窗口**：生成侧“查询-插入”在极端并发下仍可能撞唯一索引（概率极低），按 TASK 定版仅做生成侧重试，未捕获 `DuplicateKeyException` 二次重试。

## State Delta

- 新增 artifact：`.ai/docs/TASK-SEC-BATCH-2/changereport.md`；`st-share` S-06/S-07/S-09 修复代码 + 7 个新增测试用例。
- 解除 blocker：S-06/S-07/S-09（对应 security-recheck.md 三项 P2 问题）。
- 建议 exitCriteria：IMPLEMENTED（st-share）→ done；等待主线程复跑 `mvn -q -pl st-share -am test` 与三处抽查后进入 CODE_REVIEW / SECURITY_REVIEW。

## 下一步

- 主线程复跑 st-share 测试与 rg 抽查（已本地验证）。
- 重派 reviewer/security reviewer 复核 S-06/S-07/S-09 修复并评估残留（S-04 提取码 BCrypt、S-06 配套公开接口限流、S-08 跨租户分享、S-12 存在性 oracle 等不在本 TASK 范围）。
- 前端无感知变更：分享码展示长度由 8 位变为 4 位，需确认前端分享码输入/展示组件不依赖固定长度。

## 变更影响

- 影响范围：`st-share` 分享创建（分享码）、下载 URL 生成（原子计数）、流式预览/下载（原子计数 + 限速）三条链路。
- 对其它模块：无代码改动；`st-core` 仅作只读参考。
- 对测试：既有 26 个分享集成用例全部保持通过；新增 7 个用例（4 集成 + 3 单元）。
- 对用户：新分享码变短（4 位）；流式分享下载速率受 5MB/s 默认限制；已达下载上限的分享在并发下由数据库原子保证不超限。
