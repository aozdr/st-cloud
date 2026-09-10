# Change Report：同步上传失败退避机制（SYNC-RETRY-BACKOFF）

- Task: `20260815-sync-retry-backoff`
- Agent: 主线程直接执行（小型客户端改造，范围已与用户确认）
- 日期: 2026-08-15

## 背景

实测发现同步上传失败后（如雪花 ID 精度 bug、存储服务未就绪），`sync_state.localMtime` 不更新且无退避，
任何触发（WS 变更通知 / 文件监听 / 30s 定时）都会对同一文件立即重试，形成每秒刷屏的重试风暴。
全量上传失败路径原本是静默的（只发 `upload_failed` 事件，无日志、无状态），排查困难。

## 修改范围

仅 `st-desktop` 客户端；**不动服务端、不动 MySQL/H2、不动协议**。

## 修改文件清单

- `st-desktop/src/sync-retry.ts`（新增）：退避纯函数。
  - `computeBackoffMs(failCount)`：`min(1h, 30s × 2^(failCount-1))`。
  - `shouldRetryUpload(state, currentMtimeMs, now)`：无失败记录→重试；mtime 变化（用户再修改）→立即重试；
    `next_retry_at` 到期→重试；否则跳过。
- `st-desktop/src/database.ts`：
  - `sync_state` 新增 `fail_count`（默认 0）/ `fail_mtime` / `next_retry_at`，含幂等 ALTER TABLE 迁移。
  - `sync_config` 新增 `last_sync_at`（最后成功同步时间，仅展示/审计用），含幂等迁移。
  - `upsertSyncState` / `getSyncState` / `getAllSyncStates`、`upsertSyncConfig` / `getSyncConfig` / `getAllSyncConfigs` 支持新字段。
- `st-desktop/src/sync-engine.ts`：
  - `scanLocalChanges` / `processLocalEvents`：上传前按退避判定，退避期内跳过并打 info 日志（含剩余秒数）。
  - `uploadFile`：整体成败统一记账——成功清空 fail 字段；失败 `fail_count+1`、记录失败 mtime、
    计算下次重试时间，并补 error 日志（含任务真实 error 与"将在 Ns 后重试"），解决原静默失败问题。
  - `uploadFileBlockLevel` 成功路径同步清空 fail 字段。
  - `syncOnce` 成功推进游标时写入 `last_sync_at`。
- `st-desktop/src/sync-retry.test.ts`（新增）：`node:test` 单测 8 例。
- `st-desktop/package.json`：新增 `test` 脚本（tsc 编译到 `.test-dist` + `node --test`）。
- `st-desktop/.gitignore`：忽略 `.test-dist/`。

## 设计要点

- **退避以文件为单位**：`fail_mtime` 记录失败时的 mtime，若用户再次修改文件（mtime 变化）则无视退避立即重试，
  避免"失败过一次就长时间不能同步用户新改动"。
- **成功即清零**：任何成功上传/下载/对账更新该路径状态时 fail 字段归零，恢复正常节奏。
- **`last_sync_at` 不参与判定**：增量对账仍以 `sync_config.cursor`（sync_change_log.id）为准，
  时间戳仅用于展示与审计，规避时钟漂移。
- **下载路径无需退避**：已核实下载失败被捕获且游标照常推进，不会形成循环，不在本次范围。

## 测试结果

- `npm run lint`（tsc --noEmit）：通过。
- `npm run test`：8/8 通过（退避曲线、1h 封顶、无记录重试、退避期跳过、到期重试、mtime 变化立即重试、nextRetryAt 为空可重试）。
- `npm run build:main`：通过。

## 手工验收步骤（需桌面应用）

1. 停掉 RustFS 或配置错误服务器地址 → 上传失败日志出现"将在 30s 后重试"，不再每秒刷屏。
2. 恢复 RustFS → 退避到期后自动重试成功，`fail_count` 清零。
3. 退避期间手动修改该文件 → 立即重试（不受退避限制）。
4. 同步成功一次后 `sync_config.last_sync_at` 有值且随成功同步更新。
5. 旧 `transfers.db` 打开无异常，`sync_state` / `sync_config` 自动补列。

## 风险

- 退避最长 1h：正常同步最多延迟 1h；用户再修改立即重试、成功即清零，可控。
- sql.js 迁移为幂等 ADD COLUMN，失败不影响既有列；若迁移异常，旧字段逻辑照常工作（fail 列缺省视为无失败）。
- 回滚：还原 `database.ts` / `sync-engine.ts` 即可，新列留着无害。

## State Delta

- 新增 artifacts：`sync-retry.ts`、`sync-retry.test.ts`、本 changereport。
- 更新 artifacts：`database.ts`、`sync-engine.ts`、`package.json`、`.gitignore`。
- 无数据库/协议/服务端变更。
