# 架构评审：Code Review 修复迭代

## C1 去重复活

`file_object` 唯一键 `(tenant_id, md5)` 保留；`acquire` 冲突分支扩展：insertIgnore 返回 0 且 active 行不存在时，按 (tenant_id, md5) 查 deleted=1 行并原子复活（`UPDATE ... SET deleted=0, status=0, ref_count=1, storage_path=?, size=? WHERE tenant_id=? AND md5=? AND deleted=1`），返回复活行。并发竞态仍由 insertIgnore + 兜底 select 保护。方案无需表结构变更，避免破坏既有唯一约束语义。

## M2/M3 事务与配额顺序

- merge 事务顺序改为：权限校验 → claimMerging → 配额预检/预扣（DB 原子 UPDATE，可回滚）→ S3 completeMultipart → 去重归属 → 状态落库 → 事件；S3 失败走补偿 abort（替换上传恢复旧版本，新建上传标记 FAILED 保留分片）。
- relayFinalize 不得自调用 mergeChunks（绕过代理）；改为注入自身代理（`@Lazy @Resource UploadService self`）或抽独立事务组件，确保 @Transactional 生效、容量 FOR UPDATE 锁在事务内有效。

## M5 块级会话绑定

新增 `BlockCheckSessionManager`（st-sync 内，内存 ConcurrentHashMap，参照 RelayBufferManager）：block-check 成功初始化 multipart 后写入会话（uploadId/s3UploadId → storagePath/fileMd5/fileSize/block 摘要/tenantId/userId/expireAt），block-upload 凭 fileNodeId+s3UploadId 读取会话并校验归属；客户端字段仅作参考，服务端以会话为准；deleteObjectQuietly 目标取会话 storagePath。会话超时（如 2h）由 @Scheduled 任务 abort 并清理（M7）。

## M6 容量锁

`checkCapacity` 正常路径改用非锁定读（`getCloudTotalCapacity` 已存在）+ 已用容量求和；仅当剩余容量低于阈值（如 <10% 总量或 <delta 的 2 倍）时才走 `FOR UPDATE` 复核，避免正常并发上传持锁跨 S3 I/O。容量最终一致性由原子配额 UPDATE 保证。

## M8 测试

SyncBlockServiceImpl 主路径集成测试（H2 + Mock S3）：越权、去重命中/未命中、配额、版本递增、失败回退、并发重复调用。复用 st-core 测试基建（H2 schema + @SpringBootTest 模式）。

## 风险

- 会话内存存储：实例重启丢失 in-flight 会话（可接受，客户端失败重来；与 relay 一致）。
- M6 阈值内仍有短暂锁，但仅限接近容量上限场景。
- 33 号脚本若仅登记版本记录，需确保 compare-schema PASS 不依赖其内容。
