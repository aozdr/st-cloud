# Change Report：同步雪花ID精度污染根治（SYNC-ID-CORRUPTION）

- Task: `20260815-sync-id-corruption`
- Agent: 主线程直接执行（范围已与用户确认"全部执行"）
- 日期: 2026-08-15

## 背景

同步客户端持续触发"block-check 响应异常: 文件不存在"并循环重试。彻查两端数据库后确认：
本地 `transfers.db` 的 `sync_state.node_id` / `sync_config.root_id` 为**旧版 INTEGER 亲和性**，
正确字符串 ID 写入后被 SQLite 存为 64 位整数（无损），但 sql.js 读回时转成 JS Number，
超过 2^53 丢精度（2087445337642287105 -> 2087445337642287000），后续所有请求使用被污染的 ID。
`sync_history.root_id` 为 TEXT 列故一直正确，解释了"历史正常、状态全坏"的现象。

## 两端彻查结论

### 本地库（transfers.db）
- `sync_state` 16 行 node_id 全部被污染；`sync_config.root_id` 被污染；`sync_history` 正常（TEXT 列）。
- 按 md5/路径与云端一一对应，无缺失/多余；`sync_block_hash` 空表；`transfer_tasks` 的 file_id 为 TEXT 且正确。
- 实测：INTEGER->TEXT 重建后，15/16 行 ID 无损恢复为正确值；`template920.zip` 为源头污染（上传响应里已是数字），
  迁移后仍差 1 位（...7104 vs ...7105），由全量对账自愈。

### 服务端（MySQL stcloud）
- `sync_root` 正常（2083478593059856385 / 云文件夹 2082660255454892034）；`sync_cursor=0` 为遗留字段，
  delta 接口只认请求 `since`（客户端持有游标），无需改动。
- `sync_change_log` 8 条正常；`file_block` 0 行（块级从未成功）；服务端 ID 无损坏。
- 异常清理：同步文件夹内 Everywhere-Windows-x64-v0.5.1.zip 存在回收站重复节点
  （2083481398822739970，status=1），已按永久删除语义删除该行、object 7 引用 4->3、重算 md5 引用计数。
- 其它 8 个同名节点位于其它目录，不在同步范围，未动。
- 观察项：本库无 user_quota 表（配额功能表缺失，permanentDelete 的配额退还在此库为 no-op），建议单独核对 schema。

## 修改文件清单

- `st-desktop/src/db-migrate.ts`（新增）：`ensureIdColumnsText(db)`——检测 `sync_state.node_id` /
  `sync_config.root_id` 声明类型，非 TEXT 则重建表（建新表->拷贝->删旧->改名），幂等。
- `st-desktop/src/database.ts`：初始化时在补列迁移后调用 `ensureIdColumnsText`，老库启动即自愈。
- `st-desktop/src/sync-engine.ts`：`reconcileFolder` 增加 node_id 自愈——本地与云端内容一致但 node_id
  不一致时以云端为准刷新（覆盖源头污染的差 1 位场景），并清除失败退避。
- `st-desktop/src/upload-manager.ts`：`initData` 为空时抛出带服务端 message 的明确错误，替代 TypeError。
- `st-desktop/src/db-migrate.test.ts`（新增）：迁移逻辑单测 4 例（bug 复现/恢复/幂等/新库跳过）。
- `st-desktop/package.json`：test 脚本纳入 db-migrate 相关文件。

## 测试结果

- `npm run lint`：通过。
- `npm run test`：12/12 通过（迁移 4 例 + 退避 8 例）。
- `npm run build:main`：通过。
- 真实库副本验证：迁移重建 `["sync_state","sync_config"]`，15 行 ID 精确恢复、`root_id` 恢复为
  `"2083478593059856385"`；`template920.zip` 由全量对账自愈。

## 手工验收步骤（重启桌面客户端后）

1. 启动后全量对账刷新所有 node_id（含 template920 差 1 位修复）。
2. 同步日志：块检查成功 -> 块级上传完成 -> `fail_count` 清零，不再出现"文件不存在"/循环。
3. 无操作不再触发同步；30s 定时与 WS 触发均为空转。

## 风险与回滚

- 表重建迁移幂等且保留数据；失败不影响旧列，回滚还原相关文件即可。
- 云端清理仅删除同步文件夹内的回收站重复节点，正常节点引用计数已重算。
- 本地库修改（迁移）在应用启动时自动完成，无需手工操作。
