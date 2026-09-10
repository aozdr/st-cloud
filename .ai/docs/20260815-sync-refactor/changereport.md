# 变更报告（20260815-sync-refactor）

## 背景

修复同步死循环与冲突文件自生成问题，按“版本号 + 最后同步时间”门控全量同步，重构冲突/移动/删除处理。

## 修改文件清单

### 桌面端
- `st-desktop/src/database.ts`：sync_config 增加 `sync_version`；`upsertSyncState` 合并语义（COALESCE）；新增 `resetSyncData`（保留游标，清版本标记）。
- `st-desktop/src/db-migrate.ts`：重建 schema 常量同步 `sync_version`。
- `st-desktop/src/sync-engine.ts`：版本门控 + 全量重建流程；pending 事件合并；引擎自写 TTL 过滤；冲突副本登记 + 唯一命名 + 临时目录上传；MOVE no-op/状态保留/子孙前缀迁移；DELETE md5+mtime 双条件保护；`fullReconcile` 返回成功状态。
- `st-desktop/src/sync-utils.ts`（新增）、`sync-utils.test.ts`（新增）。
- `st-desktop/src/db-migrate.test.ts`：补 `sync_version` 保留断言。
- `st-desktop/package.json`：test 脚本纳入新单测。

### 服务端
- `st-core/.../FileServiceImpl.java`：rename 同名 / move 同目录 no-op 守卫。
- `st-sync/.../SyncServiceImpl.java`：delta 过滤 `old==new` MOVE/RENAME。
- `st-core/.../RecycleBinService.java` + `RecycleBinServiceImpl.java`：新增 `permanentDeleteAdmin`。
- `st-sync/.../SyncAdminController.java`（新增）：`POST /api/admin/sync/cleanup-junk`。

### 脚本
- `scripts/cleanup-sync-junk.ps1`（新增，UTF-8 BOM）：dry-run 列表 + apply 清理。

## 第二轮修复（20260815，用户实测复现后）

- `st-desktop/src/database.ts`：`sync_state` 主键改为 `(root_id, local_path)`，旧表缺 root_id 时直接重建（老数据可删）；新增 `deleteSyncStatesByRoot/deleteBlockHashesByRoot/deleteSyncHistoryByRoot`；`getSyncStats` 按 root 过滤。
- `st-desktop/src/sync-engine.ts`：全部状态读写注入 rootId；`SYNC_ENGINE_VERSION` 升到 3（强制既有安装全量重建）。
- `st-desktop/src/sync-manager.ts`：`deleteSyncRoot` 清理该 root 全部本地状态。
- `st-desktop/src/db-migrate.ts` / `db-migrate.test.ts`：重建 schema 同步 `root_id`，测试断言保留。
- `st-sync/.../SyncServiceImpl.java`：delta 按同步根文件夹过滤（path 与 MOVE/RENAME 的 oldPath 均须在根内）。

**根因确认（用户提问点）**：旧 `sync_state` 仅以 `local_path` 为主键、无 `root_id`；删除同步根只删 `sync_config` 不删 `sync_state`。重新配置同路径同步后，旧 root 的 node_id/md5/local_mtime 直接命中新 root 的文件，被误判“本地已修改/已同步”，触发冲突与反复上传。

## 与验收标准对照

| 验收标准 | 结果 |
|---|---|
| 打开应用不再无条件全量；版本不符才全量一次 | 已实现（版本门控 + 成功才固化版本） |
| keep_both 只产两份副本且不回流 | 已实现（登记 + 唯一名 + 临时目录） |
| sync_state 局部更新不再擦 local_mtime | 已实现（合并语义） |
| 服务端不再产生无意义 MOVE/RENAME | 已实现（源头守卫 + delta 过滤） |
| 构建测试全绿；老库升级后 sync_version 保留 | tsc/npm test/build + mvn compile 通过，测试断言覆盖 |
| 清理脚本 dry-run/apply 可用 | 已提供，走回收站永久删除逻辑含 S3 |
| 重新配置同步根不串状态（TC-009） | 已修复：复合主键 + 删除清理 + delta 根过滤 |

## 测试结果

- `tsc --noEmit`：通过
- `npm test`：16/16 通过
- `npm run build:main`：通过
- `mvn -pl st-sync -am -DskipTests compile`：通过

## 风险

- 全量对账与增量窗口由 30s 定时兜底。
- 清理接口依赖管理员 JWT，需在服务端部署新代码后调用。
- 桌面端需重新打包安装，`sync_version` 从 NULL → 2 触发一次全量重建（测试数据场景）。

## 下一步

- 部署服务端（st-core/st-sync），调用清理接口清掉现存垃圾副本。
- 重新打包桌面端，验证启动门控与冲突收敛（见 testcases.md 人工清单）。
