# 需求文档：Code Review 修复迭代（20260813-code-review-rework）

## 背景

2026-08-13 项目级 Code Review（`.ai/docs/20260813-project-code-review/codereview.md`）发现 1 Critical + 8 Major + 7 Minor + 3 Suggestion。本轮按用户批准的计划仅修复 Critical/Major（C1、M1~M8、FE-S1、FE-S2），Minor/Suggestion 留后续迭代。

## 用户故事

- 作为文件用户，永久删除一个文件后重新上传同 md5 内容，不应报错（500/NPE），且应正常秒传/去重（C1）。
- 作为文件用户，发起 merge 或 relay-finalize 时，只有文件 owner 或租户管理员能完成合并（M1/M3）。
- 作为存储管理员，配额/容量不足时上传应在 S3 操作前被拒绝；失败后不残留不可恢复的 multipart（M2）。
- 作为同步用户，block-check/block-upload 必须由服务端会话绑定，任何客户端传入的 storagePath/s3UploadId/md5/size 均不可信（M5）。
- 作为团队用户，并发上传不应因云盘容量行锁而串行化（M6）。
- 作为同步用户，放弃或超时的块级上传应被 abort 清理，不残留 S3 分片（M7）。
- 作为 Web 用户，低速率中转上传应支持取消，取消即 abort 并清理任务（FE-S1）。
- 作为桌面同步用户，本地存在但从未同步过的文件不应被全量对账下载覆盖（FE-S2）。

## 功能范围

| 编号 | 功能 | 验收标准 |
|------|------|---------|
| C1 | file_object 去重复活 | 同租户同 md5 的 deleted 行被复活（deleted=0/status=0/ref_count=1，更新 storage_path/size），insertIgnore 竞态兜底保留，acquire 永不返回 null 且不抛 NPE |
| M1 | mergeChunks 权限 | 非 owner 且非租户管理员调用 mergeChunks 返回 PERMISSION_DENIED |
| M2 | 配额预检/预扣顺序 | 合并事务内先配额预检/预扣（可回滚 DB 原子 UPDATE），S3 complete 失败走补偿 abort；替换上传恢复旧版本；重试 merge 不报 NoSuchUpload |
| M3 | relayFinalize 事务 | relay 合并经 Spring 代理进入 @Transactional merge 流程，事务与容量锁生效 |
| M4 | 迁移脚本幂等 | 28_file_object.sql 重复执行不报 duplicate column |
| M5 | 块级会话绑定 | block-check 服务端持久化会话（uploadId→storagePath/md5/size/blocks 摘要）；block-upload 仅凭 fileNodeId + s3UploadId 取会话校验归属与完整性，deleteObjectQuietly 目标为服务端记录路径 |
| M6 | 容量锁解耦 | 正常路径不持 FOR UPDATE 行锁跨 S3 I/O；仅配额接近阈值时加锁或改为原子预留；并发上传不串行且总量不超限 |
| M7 | 块级 abort/超时清理 | block-upload 前置校验失败即 abort；新增超时清理任务（参照 relay @Scheduled）清理过期会话并 abort S3 multipart |
| M8 | 块级测试 | SyncBlockServiceImpl 集成测试覆盖主路径（越权/去重命中未命中/配额/版本/失败回退/并发重复调用） |
| FE-S1 | Web relay 取消 | useUpload.tsx relay 循环接入取消信号，取消时调用 DELETE /file/upload/abort 并清理任务；与桌面端行为一致 |
| FE-S2 | 对账保护 | sync-engine.ts reconcileFolder 对本地存在但无 sync_state 的文件保留本地，不静默下载覆盖（走冲突/保留语义） |

## 非目标（本轮不做）

- Minor/Suggestion（m1~m7、s1~s3，含 31 号脚本版本记录、magic number、类型漂移、FileNodeVO fileMd5 口径等）。
- 中转断点续传、桌面端 relay 暂停/恢复回绕直传（P2 遗留项）。
- 代码提交 git、发布部署。
