# 程序设计文档：同步引擎 V2 重构

> 输出标准：`docs/newList/ai-design-document-standard.md`
> 落盘路径：`.ai/docs/20260815-sync-refactor/design.md`

# 一、需求分析

## 功能名称

同步引擎 V2 重构（死循环修复 + 版本门控 + 异常数据清理）

## 功能描述

```
用户：打开星云盘桌面端，不做任何操作
操作：应用启动并恢复同步引擎
系统行为（现状）：无条件全量对账；keep_both 冲突副本回流上传；临时文件删除触发反向删云端；local_mtime 被擦除反复上传；服务端产生 old==new 的 MOVE 脏日志 → 死循环
最终结果（目标）：版本号 + 最后同步时间门控；冲突只产两份副本且不回流；本地状态不被局部更新擦除；服务端不再产生无意义 MOVE/RENAME；异常数据可一键清理（DB + S3）
```

# 二、系统影响分析

## 影响模块

| 类型 | 模块 | 是否修改 |
|------|------|---------|
| 前端 | st-desktop 同步引擎（sync-engine/database/db-migrate/sync-utils） | 是 |
| 后端 | st-core（FileServiceImpl/RecycleBinService） | 是 |
| 后端 | st-sync（SyncServiceImpl/SyncAdminController） | 是 |
| 数据库 | 服务端 MySQL：无变更；客户端 sql.js：sync_config 增加 sync_version | 是（仅客户端） |
| 运维 | scripts/cleanup-sync-junk.ps1（新增） | 是 |

## 影响文件预测

- 新增文件：st-desktop/src/sync-utils.ts、sync-utils.test.ts、st-sync/.../SyncAdminController.java、scripts/cleanup-sync-junk.ps1
- 修改文件：st-desktop/src/{sync-engine,database,db-migrate,db-migrate.test,package.json}、st-core/.../{FileServiceImpl,RecycleBinService,RecycleBinServiceImpl}.java、st-sync/.../SyncServiceImpl.java
- 删除文件：无

# 三、整体设计方案

数据流：

```text
文件监听/WS推送/30s定时
        ↓
SyncEngine.syncOnce（单线程状态机，事件合并 pending 不丢弃）
        ├─ 本地变更 → 上传（自写过滤 + 指数退避）
        └─ 云端 delta（游标）→ 下载/移动/删除/冲突
        ↓
sync_config（cursor/sync_version/last_sync_at）仅在全部变更成功后推进

版本门控：sync_version != 当前版本 或 last_sync_at 为空
  → 清本地机器格式垃圾 → resetSyncData（保留游标）→ 全量对账（成功才固化版本）→ 增量
```

# 四、前端设计（st-desktop）

- 状态设计：sync_config 增加 `sync_version`；upsertSyncState 改为合并语义（COALESCE，未传字段保留）
- 同步循环：`pendingEvents` 合并；`engineWritten`（30s TTL）自激过滤；unlink 用 TTL 名单判断
- 冲突：`uniqueConflictName` 唯一化（同秒追加 `-1/-2`）；冲突副本落盘即登记 sync_state；本地副本经系统临时目录上传
- MOVE/RENAME：old==new 跳过；文件移动保留旧状态；文件夹移动按前缀迁移子孙状态
- DELETE：mtime + md5 双条件保护，本地修改不静默删除
- 全量对账：本地已存在且云端同名 md5 不一致 → 冲突流程（不覆盖本地修改）

# 五、后端设计（st-core / st-sync）

- API 设计：新增 `POST /api/admin/sync/cleanup-junk`（管理员，@PreAuthorize hasRole('ADMIN')）
- 业务流程：rename 同名 / move 同目录 → no-op；delta 过滤 `oldPath == path` 的 MOVE/RENAME
- 数据模型：无服务端表变更；清理接口复用 RecycleBinService.permanentDeleteAdmin（引用归零删 S3、退还配额、清 ES）
- 异常处理：清理接口先删指向垃圾节点的 sync_change_log，再永久删除；dry-run 由脚本 SQL 承担

# 六、数据库设计

- 服务端 MySQL：无表/字段/索引变更
- 客户端 sql.js：`sync_config` 增加 `sync_version INTEGER`（CREATE + ALTER + db-migrate 重建 schema 常量三处同步，防老库重建丢列）
- resetSyncData：清 transfer_tasks/sync_state/sync_history/sync_block_hash；`UPDATE sync_config SET sync_version=NULL, last_sync_at=NULL`（保留游标）

# 七、安全设计

- 清理接口仅管理员可调用（hasRole('ADMIN')）
- 清理只匹配机器格式正则 `(本地|冲突)-\d{14}(-\d+)?`，普通文件不匹配
- S3 删除走引用计数：file_object 引用归零才物理删除，避免误删仍被引用的对象
- 删除保护：云端 DELETE 不覆盖本地修改（mtime+md5 双条件）

# 八、性能设计

- 全量对账中本地已存在文件：大小相同才计算全量 md5，大小不同直接判冲突，省一次哈希
- 增量以游标为准，不重放历史日志（重建保留 prevCursor）
- 事件合并 + 空转退出：引擎自写路径不再回流，避免无效同步轮次

# 九、开发计划

```
Task1: 桌面端 database/db-migrate（sync_version + 合并语义 + reset）
Task2: 桌面端 sync-utils 纯函数 + 单测
Task3: 桌面端 sync-engine 重构（门控/事件/冲突/MOVE/DELETE）
Task4: 服务端守卫 + 清理接口（FileServiceImpl/SyncServiceImpl/SyncAdminController/RecycleBinService）
Task5: 清理脚本 + 文档 + 知识库 + ADR
```

# 十、风险分析

| 风险 | 影响 | 解决方案 |
|------|------|---------|
| 全量对账与增量 delta 秒级窗口 | 个别变更延迟一轮 | 全量后立即增量 + 30s 定时兜底 |
| `(本地-ts)` 上传失败不自动重试 | 云端少一份副本（本地/云端原版本不丢） | 保留 conflict 标记，用户再编辑即重试；sync_history 记录 |
| 清理接口误删格式匹配的普通文件 | 数据丢失（极罕见） | 严格正则 + dry-run 先行 + 走引用计数 S3 删除 |
| 老库 INTEGER 亲和性重建 | sync_version 列丢失 | db-migrate schema 常量同步 + 单测断言 |
