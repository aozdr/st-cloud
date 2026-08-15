# 同步引擎 V2（20260815-sync-refactor）

## 全量/增量控制

- 客户端 `sync_config.sync_version` 与引擎常量 `SYNC_ENGINE_VERSION` 比对；不一致或 `last_sync_at` 为空 → 全量重建一次。
- 全量重建：清机器格式本地垃圾 → 清本地同步表（保留游标）→ 云端快照对账 → 成功才固化版本。
- 增量范围始终以 `sync_change_log.id` 游标为准（单调、无时钟漂移），游标仅在变更全部处理成功后推进。
- `sync_state` 以 `(root_id, local_path)` 为唯一键：重新配置同步根后旧状态不会污染新 root；删除同步根时清理该 root 全部状态（sync_state/块哈希/历史）。
- 服务端 delta 按同步根文件夹范围过滤（path 与 MOVE/RENAME 的 oldPath 均须在根内），防止按用户查询串入其他/已删除同步根的日志。

## 防自激规则

- 引擎写入路径登记 30s TTL；事件到达时与 sync_state mtime 比对，一致即跳过。
- 冲突副本下载后立即登记 sync_state；本地版副本经系统临时目录上传；同步目录内不产生临时文件。
- `upsertSyncState` 为合并语义：未传字段保留旧值，禁止局部更新擦除 local_mtime。

## 冲突语义

- keep_both：`(冲突-时间戳)` 本地副本（云端版本）+ `(本地-时间戳)` 云端副本（本地版本），原路径保留本地内容。
- 冲突名唯一化：同秒冲突追加 `-1/-2`。

## 服务端守卫

- rename 同名 / move 同目录 → no-op，不写 sync_change_log。
- delta 兜底过滤 `oldPath == path` 的 MOVE/RENAME。
- delta 按同步根文件夹过滤（20260815 第二轮修复，与复合主键配套）。
- 管理员清理：`POST /api/admin/sync/cleanup-junk`，复用回收站永久删除（S3 引用归零删除、配额退还、ES 清理）。

## 已知边界

- 全量对账与增量之间秒级窗口由 30s 定时兜底。
- `(本地-时间戳)` 上传失败不自动重试，保留 conflict 标记，用户再编辑即重试。
