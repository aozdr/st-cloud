# 同步全量对账修复 - 程序设计文档

## 背景

用户反馈：未开启同步时上传新文件到云端，启动同步后该文件未同步到本地。

## 根因分析

同步引擎（`sync-engine.ts`）**仅依赖增量 delta（`sync_change_log`）做云端→本地同步**，缺少全量对账机制。

数据库验证：
- 同步文件夹 `/1123`（node_id=2082660255454892034）下有 **11 个文件**
- `sync_change_log` 仅有 **2 条记录**（均为 `template920.zip` 的 UPDATE + MOVE）
- `event_log` 仅有 **2 条 SYNC_CHANGE 事件**

其余 10 个文件在同步事件功能（迭代 1）部署前上传，无事件记录。delta API 按游标查询 `sync_change_log`，永远返回不了这些文件，客户端永远不会下载它们。

`scanLocalChanges()` 仅处理本地→云端方向（上传本地变更），无对称的云端→本地全量扫描。

## 修复方案

新增**全量对账（fullReconcile）**安全网机制：启动同步时递归列举云端文件夹所有文件，下载本地缺失的文件。

### 修改文件

| 文件 | 改动 |
|------|------|
| `st-core/.../dto/FileNodeVO.java` | 新增 `fileMd5` 字段（供对账时 md5 比对） |
| `st-core/.../impl/FileServiceImpl.java` | `toVO()` 填充 `fileMd5` |
| `st-desktop/src/sync-engine.ts` | 新增 `fullReconcile()` + `reconcileFolder()` 方法，`start()` 调用 |

### 实现细节

**`fullReconcile()`**：
- 在 `start()` 中、`syncOnce()` 之前调用
- 异常不阻断同步启动（catch + log）

**`reconcileFolder(folderId, relPrefix)`**：
- 调用 `GET /file/list?parentId={id}&page={n}&size=100` 分页列举云端子节点
- `nodeType === 0`（文件夹）：确保本地存在 → 记录 sync_state → 递归
- `nodeType === 1`（文件）：
  - 本地不存在 → 下载（复用 `downloadFile`）
  - 本地存在但 md5 不一致且本地未修改 → 从云端更新
- 跳过排除路径
- 支持分页（`pages` 字段）

### 向后兼容

- 无数据库 schema 变更（`file_md5` 列已存在于 `file_node` 表）
- 无 API 契约变更（`FileNodeVO` 新增字段是 additive）
- 增量 delta 机制不变，全量对账是额外的安全网

## 风险

- 大文件夹首次对账可能较慢（递归 + 分页列举），但仅启动时执行一次，后续靠 delta
- 对账仅下载缺失/过期文件，不删除本地多余文件（由 delta DELETE 事件处理）

## 验证

- 后端 `mvn compile -pl st-core -am` 通过
- 桌面端 `tsc --noEmit` 无新增错误（`sync-engine.ts` 零错误）
- 待手动验证：重启桌面端，确认 `/1123` 下缺失的 10 个文件被下载到本地
