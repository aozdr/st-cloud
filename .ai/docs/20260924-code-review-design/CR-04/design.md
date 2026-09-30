# CR-04 程序设计

修订：design-v1；主线程自检；不执行文件移动、删除或数据库操作。

## 1. 代码与改动范围

服务端：`st-sync/src/main/java/com/stcloud/sync/service/impl/SyncServiceImpl.java`、`dto/SyncDeltaResponse.java`、`dto/SyncDeltaItem.java`；核对 `listener/SyncChangeLogListener.java` 与 `SyncChangeMessageConsumer.java` 的 oldPath 一致性。事件源为 `st-core/src/main/java/com/stcloud/core/event/SyncChangeEvent.java`，已有 oldPath，原则上无需新增数据库列。

桌面：`st-desktop/src/sync-engine.ts`、`sync/sync-reconcile.ts`、`sync/sync-download.ts`、`sync/sync-shared.ts`，以及 `database.ts` 现有根内状态 CRUD 接口；必要时增加根内操作方法。只修改与该事件闭环直接相关的消费行为，不重写上传和调度框架。

## 2. 服务端范围投影

先校验当前用户拥有 root，查询根节点当前路径；按 user/tenant 获取原始有序日志，再逐条投影。判断函数 `inScope(path)` 必须先验证路径等于 rootPath 或以 rootPath+'/' 开头，再转相对路径并校验 exclusions；根外路径不得走 toRelativePath 后原样输出。

| oldIn | newIn | 输出 | path / oldPath |
|---|---|---|---|
| true | true | 原 MOVE 或 RENAME | 新相对 / 旧相对 |
| true | false | DELETE | 旧相对 / null |
| false | true | CREATE | 新相对 / null |
| false | false | 无 | 无 |

转换使用日志快照，不改变数据库记录。保留 logId/nodeId/nodeType；DELETE name 从旧路径末段提取、status=1，CREATE status=0。nodeId 始终是原节点，不能因 DELETE 投影改为云端实际删除。客户端不得据此向云端发删除请求。

非移动事件只对有效新范围输出；无意义同路径移动忽略。oldPath 缺失时不能假定 oldOut：对当前根执行必要的定向对账并让客户端通过明确恢复信号处理，不默默当 CREATE 消费。建议 delta 响应增加 `reconcileRequired`（可选布尔），遇到不能可靠投影且可能涉及该根的日志时为 true；只有完成根对账客户端才固化本页游标。未知关联范围宁可要求对账，不向客户端输出根外路径。

当事件 nodeId 为同步根自身时，不输出 path='/' 的 DELETE；根仍由相同节点 ID 标识。根路径变化/历史前缀无法匹配时设置 reconcileRequired，避免删除或重建用户选定本地根。

旧 parentId 并未存入 SyncChangeLog，不能编造；DELETE 不依赖 parentId。目录移入下载通过 nodeId 列举，使用列表结果的 parentId。现有 DTO parentId 可为空，前端类型应承认 null。

## 3. 游标与协议

服务端保持 LIMIT PAGE_SIZE+1、按原始日志 ID 推进 cursor/hasMore；投影为空不代表日志结束。新增可选 `scopeProjectionVersion: 2` 与 `reconcileRequired`，无更改既有事件枚举和 URL。服务端 Long 由全局 Jackson 配置序列化为十进制字符串；桌面端将 cursor 作为字符串传输，并以 SQLite TEXT 保存旧整数游标的无损迁移结果，禁止经 JS Number 转换。

客户端按页处理和持久化，避免必须把所有页聚合后才开始；单页所有事件、目录补齐和必要对账成功后写入该页 cursor。网络错误或无效响应抛出，不返回“空成功页”。即使重复消费上一页也必须幂等。任意未成功的下载必须向上抛出/返回明确失败，不能被 downloadFile catch 吞掉。

## 4. 客户端应用与本地保护

同步轮次先拉取并应用云端事件，再处理未与云端事件冲突的本地事件/扫描。针对同一 nodeId/path 的本地未上传变化进入保留分支，不抢先上传到旧根位置。引擎自身 rename/delete/create 在文件系统操作前登记自激抑制，事件完成后清理/刷新标记，防止监听器反向上传或删除。

- 文件 CREATE：若同 nodeId/内容已存在仅刷新状态；否则临时文件下载、验证成功后原子替换。临时下载失败不把部分文件登记为 synced。目标已存在不同内容时保留冲突副本，不覆盖。
- 目录 CREATE：创建目录后按 nodeId 递归分页列举有效子树，应用相同排除/路径约束，复用抽取后的 reconcileFolder；全部完成才视为该事件成功。不只 mkdir 后推进游标。
- DELETE：确认对应 nodeId 的受管理状态，逐文件比较可靠内容基线；确认未改动的文件可清理，哈希失败按不确定处理。独立修改文件移到应用 userData 下 `sync-recovery/<rootId>/<operationId>/`，保存原路径/nodeId/原因清单；恢复目录不位于任何同步根。恢复副本写入并核对成功后才移除原位置/状态。若恢复目录与根重叠或写入失败，停止该页。
- 目录 DELETE：不能用目录 mtime 决定递归删除。枚举子树，记录未跟踪或有修改的内容并完整保存在上述恢复目录，再移除旧受管理目录；不执行基于不完整枚举的 recursive rm。恢复清单保留可还原路径，展示恢复位置，不自动上传这些副本。
- MOVE/RENAME：同 nodeId 的状态前缀迁移且保留 mtime/md5；源不存在而目标同 ID 已存在视为重放；两处都无内容时按文件下载/目录子树补齐。本地独立修改先安全保留，不仅 break 后推进游标。目标冲突不覆盖。

恢复操作先写 pending 清单，再拷贝和核对、更新文件/状态、最后标记 complete。跨卷按复制核对后删除处理；崩溃后根据 pending 清单继续，不重复生成恢复副本。任何根路径、恢复路径都需解析绝对路径并验证边界；符号链接/目录联接不能导致遍历或删除根外内容。

后续实现不得扩大到自动删除所有未知本地文件；目录 MOVE/DELETE 有明确事件范围时才处理该范围中的未跟踪子文件，并以完整保留而非丢弃为前提。

## 5. 当前状态与日志状态竞争

移入后节点可能再次移走/删除；CREATE/MOVE 应用前通过现有 GET `/api/file/{nodeId}` 读取当前节点，下载/列举后发现状态变化时复核，不能只依赖旧日志路径。若确认在当前有效范围，按当前路径对账；明确在外则清理已登记的旧映射并等后续日志；无权限/网络失败不能解释为删除。同一页按日志顺序处理，相同节点以当前状态对账后的重放不得创建过时路径。DELETE 应用前确认路径上的状态 nodeId 与事件一致；该路径已被其他节点占用时，不删除新节点内容。当前节点重新移回有效范围时以当前状态对账，旧 DELETE 重放不得再次清走。

同步根改名导致历史绝对前缀变化时使用 nodeId+根全量对账恢复；不做字符串全局替换猜测。日志与文件事件的 Outbox 架构保持原样，本项仅核对两个消费通道没有漏 oldPath。

## 6. 升级修复历史残留

现有 start 在版本不同时调用 resetSyncData，可能清空所有根且丢掉判别残留的基线。此次升级移除“本项版本变化即全局清库”的做法：先保留当前根全部状态，暂停该根监听上传，再完整分页列举云端有效树。

对旧状态中 nodeId 未出现在新树的条目，使用节点详情定向复核。确认节点当前在根外，按 DELETE 保留/清理策略修复；确认仍在根内但改路径，按 MOVE 对账；只有列举和详情均成功才固化新 syncVersion。403/超时或不完整目录扫描不能成为自动删除依据。失去旧状态的本地文件没有足够证据判定为残留，保留并记录人工核对，不默默当新文件上传以掩盖问题。

恢复过程使用 pending 清单阻止待核对路径的自动上传，全部成功才解除；根间独立，不改其他根的 cursor/state。保留原 cursor，再继续增量吸收扫描期间变更，不直接跳到日志最大值。

## 7. 发布与边界

先发布能识别 scopeProjectionVersion 的桌面程序，随后切换服务端；桌面在能力标记未达到 2 时不宣称跨根修复完成，并暂停受影响同步而非继续丢事件。协调发布后执行一次根内迁移对账。旧桌面虽可识别 CREATE/DELETE，但不具备目录补齐与安全恢复，不作为验收客户端。

回退保留恢复副本和清单、暂停自动同步，不清空本地状态重来。未知旧数据需人工核对、持续变动的目录需要后续增量收敛，是明确限制。

R04-1/2→投影；R04-3/6→子树应用与恢复；R04-4/5→分页、失败传播、幂等；R04-7→升级对账；R04-8→边界和根节点身份。本轮未编写用例或执行任何同步操作。
