# D2 同步引擎 Code Review

> 审查人：主线程 reviewer（ox-alpha）/ 日期：2026-08-22 / 方式：静态人工审查（sync-engine.ts 1036 行全文精读；file-watcher/ws-client/sync-manager/sync-utils 全文；关键结论经 database.ts 实现交叉验证）
> 范围：`st-desktop/src/sync-engine.ts`、`sync-manager.ts`、`sync-retry.ts(+test)`、`sync-utils.ts(+test)`、`file-watcher.ts`、`ws-client.ts`、`task-scheduler.ts`

## 模块概述

双向对账引擎：journal-id 游标增量（30s 定时 + WS 即时触发）+ 版本门控全量重建；本地 chokidar 监听（awaitWriteFinish 防抖）；冲突四策略（keep_both 默认 / server_wins / local_wins / latest_wins）；>=8MB 更新走块级增量上传。

## 问题清单

| 编号 | 严重度 | 位置 | 问题 | 证据(代码片段) | 建议 |
|---|---|---|---|---|---|
| D2-1 | P1 | sync-engine.ts:1022 | keep_both 冲突的「本地版」副本上传到**云端根目录**而非原文件所在子目录：`'/'+basename` 丢失了 relPath 的目录层级 | `await this.uploadFile(tempPath, '/' + path.basename(tempPath));` | 以 relPath 的父目录拼接冲突副本相对路径 |
| D2-2 | P1 | sync-engine.ts:909-924 | 下载无临时文件+原子 rename：流中断后半截文件留在同步目录且无 sync_state，下轮扫描被判为「本地新文件」把损坏内容传上云端 | `res.data.pipe(ws); ws.on('finish', resolve)` | 写 `xxx.tmp` 成功后 rename；失败时删除残留 |
| D2-3 | P1 | database.ts:652-658（由 sync-engine.ts:138-139 触发） | 任一根版本升级触发 `resetSyncData()` 清空**所有根**的 sync_state 与全部 transfer_tasks：其它根 node_id 记录丢失 → 下轮扫描把已有文件当新建上传，云端产生重复节点；进行中传输任务记录蒸发 | `DELETE FROM sync_state; DELETE FROM transfer_tasks;` | 重置范围收敛到单 root_id；或升级期一次性迁移而非全清 |
| D2-4 | P1 | sync-engine.ts:598-613 | handleLocalDelete 注释称「仅当本地版本与上次同步一致」才删云端，实现未做任何比对：退避中/未同步成功的修改随 unlink 一并从云端删除（仅回收站可捞回） | 函数体直接 `/file/delete` | 至少校验 failCount==0 且 mtime 与 state 一致；建议加 30s 删除宽限期 |
| D2-5 | P2 | sync-engine.ts:319-337 | scanLocalChanges 每 30s 同步 statSync 全树遍历，万级文件时阻塞 Electron 主进程数秒 | `fs.statSync(localPath)` 循环 | 改 fs.promises 或增量扫描 |
| D2-6 | P2 | ws-client.ts:122-133 | 心跳只发 ping 不验 pong，无半开连接检测（NAT 掐断后长时间假在线；变更通知延迟，靠 30s 轮询兜底） | 无 pong 超时逻辑 | 记录 lastPong，两周期未收到即 terminate 重连 |
| D2-7 | P2 | file-watcher.ts:58-59 | 500ms 去抖计时器被任意文件的任意事件重置，持续写入的目录批次可能长期无法 flush | 单一 debounceTimer 复位 | 改最大等待窗口（如首事件后 ≤2s 强制 flush） |
| D2-8 | P2 | sync-engine.ts:969-981 | latest_wins 用本机时钟 vs 云端时间戳比较，跨设备时钟偏移会选错保留版本 | `localMtime >= cloudTime` | 文档标注局限或改用服务端接收序号 |
| D2-9 | P2 | sync-manager.ts:231-250 | auto-relink 直接信任服务端 localPathHint 创建本地目录并开始同步，路径失效/指向误目录时有破坏面 | `fs.mkdirSync(recursive)` 于 engine.start | relink 前校验目录存在且非空时需确认 |
| D2-10 | P2 | sync-utils.ts:26-34 | 忽略清单缺 node_modules/.gradle/target 等构建产物目录，代码目录同步会产生海量无效上传 | 仅覆盖点开头与 tmp/swp/lock | 补常见构建目录默认项 |
| D2-11 | P2 | 测试缺口 | 现有测试仅覆盖 sync-utils 纯函数与 retry 计算；DELETE 传播、MOVE 子树迁移、四种冲突策略、断点续跑均无引擎级用例 | test 文件仅 3 个纯函数套件 | 对 handleConflict/processCloudDelta 补集成测试（内存 FS + mock API） |

## 未覆盖的高危测试缺口

1. 云端 DELETE × 本地已修改（mtime 变）→ 当前行为：保留本地并丢 state，下轮重传为重复节点——无测试固化预期。
2. MOVE 目录 × 本地子文件正在退避重试 → 前缀迁移后 nextRetryAt 是否保留未见断言。
3. keep_both 在子目录深层文件冲突时的落点错误（D2-1）正是无集成测试漏掉的回归。

## 亮点

- journal-id 单调游标「全部成功才推进」，配合 pendingEvents 合并续跑，事件不丢不重的骨架设计正确
- 自激过滤三件套（engineWritten TTL + mtime 比对 + 冲突副本即时登记）系统性修复了历史回流死循环，且有注释沉淀根因
- 块级增量上传链路完整：block-check 复用 → 缺失块三次退避 → 失败整体回退全量；雪花 ID 按字符串传输防精度丢失有明确注释
- upsertSyncState 用 SQL COALESCE 合并，部分字段更新不擦除既有状态（handleConflict 的假设经实现验证成立）
- MOVE/RENAME 处理含同路径脏日志过滤与子树 sync_state 前缀迁移，避免整目录重传

## 结论

架构层（游标/事件合并/冲突框架）是全项目工程化程度最高的模块，历史 bug 修复有据可查。剩余风险集中在**破坏性操作的边界**：冲突副本落点错层级、下载中断污染、全局重置误伤其它根、本地删除直通云端。修复这四项后该模块可信度将显著提升。

统计：P0×0　P1×4　P2×7
