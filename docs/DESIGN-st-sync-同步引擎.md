# 星云盘 同步引擎技术设计方案

> **文档版本**：v1.0
> **创建日期**：2026-08-01
> **文档状态**：Design Draft
> **关联模块**：`st-sync`（后端，当前为空）、`st-desktop`（Electron 客户端，传输能力已就绪）
> **关联 PRD**：PRD v2.0 Epic 3（P0 阻断项）

---

## 1. 概述与目标

### 1.1 背景

PRD Epic 3「PC 客户端文件同步」为 P0 阻断发布项，但 `st-sync` 后端模块当前**完全为空**（仅有 `pom.xml`，依赖 `st-common` + `st-core`）。`st-desktop`（Electron）已具备成熟的**单向传输能力**（`upload-manager` / `download-manager` / `task-scheduler` / SQLite 任务库 `database.ts` / 带 JWT 自动刷新的 `api-client.ts`），但**不具备双向同步**（无本地文件监听、无 cloud→local 变更同步、无冲突检测）。

### 1.2 目标

构建双向文件同步引擎，实现：本地变更自动增量上传、云端变更自动同步到本地、冲突安全处理，体验对标 Dropbox/OneDrive。

### 1.3 非目标

- 虚拟磁盘 / 按需下载（lazy load）—— 二期
- LAN 同步加速 —— 远期
- 协同编辑（OT/CRDT）—— 范围外

### 1.4 设计原则

- **最大化复用**：同步上传复用现有 `upload-manager`（含本轮新增的 `replaceFileId` 替换上传与版本快照）；下载复用 `download-manager`；调度复用 `task-scheduler`；任务持久化复用 `database.ts`。
- **客户端主导**：同步逻辑主要在客户端（监听本地、计算差异、驱动上传/下载），服务端只提供「变更 delta」与「同步根注册」。
- **冲突不丢数据**：冲突一律生成冲突副本，不覆盖任何一方。

---

## 2. 总体架构

```
┌──────────────────────── st-desktop（Electron 客户端）────────────────────────┐
│                                                                              │
│  ┌──────────────┐   ┌────────────────┐   ┌──────────────────────────────┐   │
│  │ FileWatcher  │──>│  SyncEngine    │──>│ UploadManager / DownloadMgr  │   │
│  │ (chokidar)   │   │  (差异+冲突)    │   │ (复用现有, replaceFileId)     │   │
│  └──────────────┘   └───────┬────────┘   └──────────────┬───────────────┘   │
│                             │                            │                   │
│                     ┌───────▼────────┐           ┌───────▼────────┐          │
│                     │ SyncStateStore │           │ TaskScheduler  │          │
│                     │ (SQLite)       │           │ (现有)         │          │
│                     └────────────────┘           └────────────────┘          │
└──────────────────────────────┬───────────────────────────────────────────────┘
                               │ HTTPS REST (api-client.ts, JWT 自动刷新)
┌──────────────────────────────┴───────────────────────────────────────────────┐
│                        st-sync（后端 Spring Boot）                              │
│  ┌──────────────────┐  ┌──────────────────┐  ┌───────────────────────────┐  │
│  │ SyncRoot 管理     │  │ Delta 查询        │  │ Heartbeat / 在线状态       │  │
│  │ 注册/列表/删除    │  │ 按 cursor 增量返回 │  │ (Redis)                   │  │
│  └──────────────────┘  └──────────────────┘  └───────────────────────────┘  │
│                       复用 st-core: FileNode 树 / 上传 / 下载 / 版本           │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 服务端设计（`st-sync`）

### 3.1 职责边界

服务端**不实现同步算法**，只提供：
1. **同步根（SyncRoot）注册**：记录「哪个云端文件夹 ↔ 哪个本地路径」的绑定关系。
2. **Delta 查询**：客户端轮询获取「自上次 cursor 以来云端文件夹下的变更」。
3. **在线心跳**：记录客户端在线状态（用于冲突窗口判断，可选）。

文件实际上传/下载/版本/删除**全部复用 `st-core` 现有接口**（`/api/file/upload/*`、`/api/file/{id}/stream`、`/api/file/delete`、`/api/file/{id}/versions`）。

### 3.2 实体

#### `SyncRoot`（表 `sync_root`）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 雪花 ID |
| tenant_id | BIGINT | 租户（BaseEntity 继承） |
| user_id | BIGINT | 所属用户 |
| cloud_folder_node_id | BIGINT | 云端同步根文件夹的 FileNode ID（0=用户根） |
| local_path_hint | VARCHAR | 本地路径标识（仅记录，实际路径由客户端管理） |
| cursor | BIGINT | 该同步根当前的同步游标（上次同步到的最大变更序号/时间戳） |
| status | INT | 0-正常 1-已暂停 2-已删除 |
| created_at / updated_at | DATETIME | BaseEntity |

#### 变更追踪策略（MVP：基于时间戳）

MVP **不引入额外 change_log 表**，直接复用 `file_node.updated_at`：
- Delta 接口返回 sync 根文件夹下（递归）所有 `updated_at > since` 的 FileNode（含其 status）。
- 客户端据此做全量对账：新增/修改→下载，回收站/删除→本地移除。

> **演进**：二期若时间戳精度不足（同一秒多次变更），引入 `sync_change_log` 表（单调递增 id + 操作类型），在 `st-core` 文件操作处通过 `FileIndexEvent` 复用现有事件机制异步落库。

### 3.3 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/sync/roots` | 注册同步根（body: cloudFolderNodeId, localPathHint） |
| GET | `/api/sync/roots` | 列出当前用户的同步根 |
| DELETE | `/api/sync/roots/{id}` | 注销同步根 |
| PUT | `/api/sync/roots/{id}/pause` | 暂停 / 恢复（toggle） |
| GET | `/api/sync/roots/{id}/delta?since={ts}` | 增量变更：返回 `updated_at > since` 的 FileNode 列表 + 服务器当前时间（作为新 cursor） |
| POST | `/api/sync/heartbeat` | 客户端心跳（body: rootIds），刷新 Redis 在线状态 |

**Delta 响应示例**：
```json
{
  "code": 200,
  "data": {
    "cursor": 1690900000000,
    "hasMore": false,
    "changes": [
      { "nodeId": 101, "path": "/docs/a.txt", "name": "a.txt", "size": 1024, "md5": "...", "status": 0, "updatedAt": "2026-08-01 10:00:00" },
      { "nodeId": 102, "path": "/docs/old.txt", "name": "old.txt", "size": 0, "md5": null, "status": 1, "updatedAt": "2026-08-01 09:55:00" }
    ]
  }
}
```
- `status=0`（正常）：本地不存在或 md5 不一致 → 下载
- `status=1`（回收站）/ `status=2`（删除）：本地存在 → 删除本地文件

### 3.4 Delta 实现要点

- 复用 `FileService` 的目录树查询能力，按 `cloudFolderNodeId` 递归取子节点，过滤 `updated_at > since`。
- **分页**：单次返回上限 500 条，`hasMore=true` 时客户端用最后一条的 `updatedAt` 继续。
- **权限**：校验 sync root 归属当前用户（`getNodeByIdAndOwner`）。
- **删除检测**：含 `status` 字段即可让客户端识别软删除。

---

## 4. 客户端设计（`st-desktop`）

### 4.1 新增组件

| 组件 | 职责 |
|------|------|
| `FileWatcher` | 基于 `chokidar` 监听本地同步目录的 add/change/unlink/unlinkDir |
| `SyncEngine` | 同步主循环：拉取 delta → 对账 → 驱动上传/下载 → 冲突处理 |
| `SyncStateStore` | 本地 SQLite，记录每个已同步文件的 `(localPath, nodeId, md5, size, mtime, syncStatus)` |
| `ConflictHandler` | 冲突副本生成与通知 |

### 4.2 本地同步状态库（SQLite，复用 `database.ts` 的 sql.js）

新增表 `sync_state`：

```sql
CREATE TABLE IF NOT EXISTS sync_state (
  local_path  TEXT PRIMARY KEY,        -- 相对同步根的相对路径
  node_id     INTEGER,                 -- 对应云端 FileNode ID（未上传时为空）
  md5         TEXT,
  size        INTEGER,
  local_mtime INTEGER,                 -- 本地修改时间(ms)
  cloud_mtime TEXT,                    -- 云端 updatedAt
  status      TEXT,                    -- synced / pending_upload / pending_download / conflict
  updated_at  TEXT NOT NULL
);
```

另存同步根配置与 cursor 于 `sync_config` 表（或 electron `userData` JSON）。

### 4.3 同步循环

```
启动/定时(每 30s) 或 文件事件触发:
  1. 扫描本地变更 (FileWatcher 事件队列) -> 标记 pending_upload
  2. 拉取云端 delta (GET /delta?since=cursor) -> 标记 pending_download / pending_delete
  3. 对账每个文件:
     a. 仅本地变 -> 上传 (新建用 init+merge; 同名存在用 replaceFileId 替换, 触发版本快照)
     b. 仅云端变 -> 下载到本地
     c. 双方都变 且 md5 不同 -> 冲突
     d. 云端删除 且 本地未变 -> 删本地
  4. 更新 sync_state 与 cursor
  5. 冲突文件生成副本并通知
```

### 4.4 上传复用与替换

- 新文件：调用现有 `upload-manager`（`/upload/check` → `/upload/init` → `/upload/chunk-url` → `/upload/merge`）。
- **覆盖已有文件**：`UploadInitRequest` 传入 `replaceFileId`（本轮已实现）→ 服务端复用节点、生成历史版本、中止可回退。客户端从 `sync_state` 取得 `node_id` 作为 `replaceFileId`。
- 这样同步覆盖天然产生版本历史，与 Epic 6 版本管理打通。

### 4.5 文件监听防抖

`chokidar` 事件需防抖（`awaitWriteFinish` 200ms），避免大文件写入中途反复触发。复制/移动整目录时先全量扫描再增量。

---

## 5. 冲突策略（对齐 PRD Story 3.3）

- **判定**：本地 `mtime > lastSyncedMtime` 且 云端 `updatedAt > cursor` 且 `md5 不同` → 冲突。
- **处理**：保留两份。
  - 云端版下载为 `文件名 (冲突-yyyyMMddHHmmss).扩展名`（本地）。
  - 本地版上传为 `文件名 (本地-yyyyMMddHHmmss).扩展名`（云端，新建节点）。
  - 原 `node_id` 指向云端版，本地 `sync_state` 记录新映射。
- **通知**：通过 IPC 向渲染进程推送冲突事件，UI 列出冲突文件供用户决策（保留哪份/都保留）。
- **不自动覆盖**：任何一方的修改都不会被静默丢弃。

---

## 6. 数据流示例

**场景：用户在本地修改 `docs/report.docx`，同时同事在云端修改了同一文件。**

```
1. FileWatcher 检测 report.docx 变更 -> SyncStateStore 标记 pending_upload
2. SyncEngine 拉 delta -> 发现 report.docx 云端 updatedAt 也变了 -> 标记冲突
3. ConflictHandler:
   - 下载云端版 -> docs/report (冲突-20260801100000).docx
   - 上传本地版 -> docs/report (本地-20260801100000).docx (新建节点)
4. 通知用户: 检测到冲突, 已保留两份
5. sync_state 更新两个文件的映射, cursor 推进
```

---

## 7. 复用清单

| 现有能力 | 同步中的用途 |
|------|------|
| `upload-manager` + 分片上传 | 本地变更上传（新建/替换） |
| `UploadInitRequest.replaceFileId`（本轮新增） | 覆盖上传 → 版本历史 |
| `download-manager` + `/file/{id}/stream` | 云端变更下载到本地 |
| `task-scheduler` | 同步任务并发控制（与手动传输共享槽位或独立配额） |
| `database.ts`（SQLite） | 扩展 `sync_state` / `sync_config` 表 |
| `api-client.ts`（JWT 自动刷新） | 所有同步 API 调用 |
| `st-core` FileNode 树 / Delta | 服务端变更查询基础 |
| `VersionService`（本轮新增） | 替换上传产生的版本可恢复 |

---

## 8. MVP 范围与分期

### MVP（解除 P0 阻断）
- [ ] 服务端：`SyncRoot` 实体 + 注册/列表/删除 + 时间戳 Delta 接口
- [ ] 客户端：`FileWatcher` + `SyncStateStore` + `SyncEngine` 主循环（上传/下载/删除对账）
- [ ] 冲突：生成冲突副本 + 通知（不含 UI 决策面板）
- [ ] 单同步根、单用户根目录同步

### 二期
- 选择性同步（按子文件夹勾选）
- 冲突 UI 决策面板
- `sync_change_log` 精确变更日志（替代时间戳）
- 多同步根、团队空间同步
- 暂停/恢复、限速与传输管理集成

### 远期
- 虚拟磁盘 / 按需下载
- LAN 同步加速
- 大目录全量迁移优化

---

## 9. 边界情况与风险

| 场景 | 处理 |
|------|------|
| 同一秒多次本地变更 | `awaitWriteFinish` 防抖 + 以 `mtime` 为准 |
| 大文件同步中客户端退出 | `sync_state` 标记 `pending_upload`，重启后断点续传（复用 `/upload/status`） |
| 网络中断 | `api-client` 自动刷新 Token；同步循环退避重试（指数退避，上限 5min） |
| 云端文件被他人删除 | Delta 返回 `status=1/2`，客户端删本地（仅当本地未再修改） |
| 本地删除 | `unlink` 事件 → 调用 `/api/file/delete` 移入云端回收站（仅当本地版本与上次同步一致） |
| 符号链接 / 特殊文件 | `chokidar` 忽略符号链接，不同步 |
| 路径深度/文件名非法字符 | 复用 PRD 约束（≤20 层、禁 `/\:*?"<>|`），非法名跳过并告警 |
| 时钟漂移 | Delta 以服务端时间为准（响应带回 `cursor=serverTime`），客户端不依赖本地时钟判定云端变更 |
| 配额不足 | 上传前 `/upload/check` 复用现有配额校验，不足时暂停同步并通知 |

### 主要风险
1. **复杂度**：双向同步状态机是本系统最复杂模块，需充分测试并发与冲突场景。
2. **时间戳 Delta 精度**：同秒多次变更可能丢失，MVP 可接受，二期用 change_log 消除。
3. **跨平台路径**：Windows/macOS 路径分隔符、大小写敏感差异，需统一用相对 POSIX 路径存储。

---

## 10. 测试计划

### 单元测试（服务端 `st-sync`）
- SyncRoot CRUD + 权限校验（非所有者 403）
- Delta 接口：`since` 过滤、递归子节点、含软删除、分页边界

### 单元测试（客户端）
- SyncEngine 对账逻辑（仅本地变/仅云端变/双方变/云端删除）的纯函数测试
- ConflictHandler 命名规则与映射更新

### 集成测试
- 端到端：本地新建文件 → 云端出现；云端新建 → 本地出现
- 覆盖同步：本地改 + 云端改（不同 md5）→ 生成两份冲突副本，无数据丢失
- 断网恢复：同步中拔网 → 重连后继续，不重复、不丢失

### 性能基准
- 10k 文件目录首次全量同步耗时
- 增量 Delta（无变更）响应 P95 ≤ 200ms

---

## 11. 落地建议（实现顺序）

1. **服务端先行**：`st-sync` 实现 `SyncRoot` + Delta（可独立编译验证，复用 st-core）。
2. **客户端 SyncStateStore**：扩展 `database.ts` 加 `sync_state` 表。
3. **客户端 SyncEngine + FileWatcher**：主循环 + 上传/下载对账（先不做冲突，冲突=本地版优先 + 告警）。
4. **冲突处理**：副本生成 + IPC 通知。
5. **集成测试**：覆盖核心场景后并入发布。

> 本方案为「设计决策完整」文档，实现时可按第 11 节顺序逐步落地，每步可独立编译/测试。

---

> **文档结束** | 星云盘 同步引擎技术设计 v1.0 | 2026-08-01