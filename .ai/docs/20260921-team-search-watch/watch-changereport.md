# 文件关注变更覆盖报告

版本：`tsw-code-r1`  任务：`TASK-20260921-team-search-watch-watch`

## 事件入口覆盖

| 成功写入口 | 事件 | 实现位置/证据 |
| --- | --- | --- |
| `NewFileServiceImpl.createBlankFile`、`createCompletedFile` | `CREATE` | 插入节点后调用 `ReliableEventPublisher.publishSyncChange`；完成文件同时发布索引事件 |
| `FileServiceImpl.createFolder`、`createTeamFolder` | `CREATE` | 文件夹插入后发布索引与同步事件 |
| `UploadCommitManager.createInstantNode`、`commitSimpleUpload` | `CREATE` | 秒传/简单上传事务完成后统一走 `UploadEventPublisher.publishCreated` |
| `UploadCommitManager.finalizeMerge` | `CREATE`/`UPDATE` | 新建分片完成为 `CREATE`，替换上传完成为 `UPDATE`，均在节点完成状态写入后发布 |
| `UploadCommitManager.commitExtract`（`ArchiveServiceImpl.extractArchive`） | `CREATE` | 解压事务内逐个创建文件夹/文件并发布一次成功事件 |
| `FileServiceImpl.rename`、`renameTeamFile` | `RENAME` | 记录旧路径后发布；同名无实际变化不发布 |
| `FileServiceImpl.move`、`moveTeamFiles` | `MOVE` | 记录 `oldParentId` 后调用重载，捕获事件同时保存旧父链 |
| `FileServiceImpl.copy`、`copyTeamFiles` | `CREATE` | 递归复制的每个成功节点发布一次事件 |
| `FileServiceImpl.deleteToRecycleBin`、`deleteTeamFiles` | `DELETE` | 仅回收根节点发布一次；目录后代通过捕获阶段树匹配覆盖订阅 |
| `RecycleBinServiceImpl.restore` | `CREATE` | 恢复根及当前正常子孙各发布一次恢复事件 |
| `VersionServiceImpl.restoreVersion` | `UPDATE` | 版本恢复后发布一次内容更新事件 |
| `UploadCommitManager.commitTextOverwrite` | `UPDATE` | 文本保存事务内节点与对象归属完成后发布 |
| `UploadCommitManager.commitEditorSave`（`EditorCallbackServiceImpl`） | `UPDATE` | OnlyOffice 已验证保存事务内发布；无法确认主体时 `actorId=null` |
| `SyncBlockCommitManager.commitBlockUpload` | `UPDATE` | 同步块写入事务通过 `UploadEventPublisher.publishUpdated` 发布 |

`ReliableEventPublisher.publishSyncChange` 先写 Outbox，再同步发布不可变 `FileWatchCaptureEvent`。捕获监听器只做数据库祖先/后代匹配和队列插入，并要求存在真实数据库事务；任何匹配或 SQL 异常向外抛出使文件写事务回滚。MQ 开启与否只影响原同步/索引事件的后续通道，不改变关注队列捕获。

## 关注队列语义

- 事件匹配包含变更根、当前祖先和 MOVE 的旧祖先；目录 MOVE/DELETE 额外按同租户同空间树查直接后代订阅，并按用户聚合成一条 delivery。
- 捕获阶段剔除已确认的 `actorId=userId`，投递前按持久化订阅 ID、租户、成员状态和当前权限再次核验。
- DELETE 处理区分祖先订阅与删除子树后代订阅：祖先订阅复用删除根核权，后代订阅使用删除专用路径校验；永久删除/断链/越界均 fail closed。
- 目录 MOVE 根当前不可见但直接关注的后代仍可见时，允许发送无名称/路径的通用提醒；通知正文不读取历史 payload。
- 投递在单条事务内锁定、插入 `notification`、标记完成；取消后旧 watch ID 抑制，事件/用户唯一键与通知唯一键共同保证重复消费幂等。SQL 异常退避 5 秒至 5 分钟，最多 10 次；错误摘要和日志只保留受控异常类型，不记录原始消息。

## Schema 与验证状态

- `docker/mysql/init/43_file_watch.sql` 新增 `file_watch`、`file_watch_delivery`，并为 `notification` 增加可空事件字段及 `(tenant_id,user_id,event_id)` 唯一键；首行为 `SET NAMES utf8mb4;`。
- `st-team/src/test/resources/schema.sql` 已包含上述表和通知字段；`st-core/src/test/resources/schema.sql` 已同步同一组表/字段，供事件事务回滚和 schema 覆盖测试使用。
- 已准备/补强关注服务的单元与可靠性测试入口；主线程负责串行执行 Maven、`SchemaConsistencyTest`、迁移前后 schema 对比及数据库验证。本派发阶段未运行 Maven、未执行迁移，不能据此声称测试或数据库对比通过。

## 未执行项与风险

1. H2、MySQL 双 schema 对比和授权开发库迁移待主线程执行。
2. 真实 MQ、ES、S3 与多实例并发投递未在本阶段运行；实现依赖数据库唯一键/行锁，需集成环境验证。
3. 生产永久删除后的目标读取按 fail closed 处理；历史通知只保留通用不可用状态，不恢复历史名称或路径。
