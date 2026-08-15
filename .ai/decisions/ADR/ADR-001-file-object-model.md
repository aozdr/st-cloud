# ADR-001：文件对象模型（同租户 MD5 去重 + 引用计数）

> 架构决策记录。本决策定义 TASK-001 的表结构与对象引用语义。由 Knowledge Manager 复核一致性。

## 状态
Accepted

## 背景
原系统 `file_node` 直接持有 S3 物理对象路径，去重逻辑依赖全局 `selectByMd5`（无租户作用域，跨租户可能误判），秒传需复制物理对象、删除依赖 storage_path 字符串判重，引用关系无显式模型。这导致：跨租户去重边界不清、版本/复制场景引用计数无法可靠维护、秒传扩展受限。

约束：
- 不删除 `file_node.file_md5 / storage_path`（迁移期兼容，下载/ES 继续可用）
- 不改变前端 `FileNodeVO` 与所有 REST 接口签名
- 不引入跨租户共享物理对象（多租户隔离安全要求）

## 决策
- 新增 `file_object` 表：`id / tenant_id / md5 / size / storage_path / ref_count / status / created_at / updated_at / deleted`
- 去重维度为**同租户 md5 唯一**（`UNIQUE KEY uk_tenant_md5 (tenant_id, md5)`），跨租户同 md5 各自独立对象
- 新上传对象路径规范化为 `tenantId/md5`；`file_node` 新增 `object_id`（可空：文件夹/未完成上传为 null），通过引用计数关联
- 引用计数语义：`ref_count` = 同租户同 md5 的已完成 `file_node` 数；归零且永久删除时才物理删除 S3 对象
- 并发首传竞态用 `INSERT IGNORE` + 失败后 `selectByTenantAndMd5` 复用胜出行（允许一次冗余上传）
- 版本恢复/替换上传：目标 md5 有对象则复用 +1，旧对象仅减引用不物理删除（可能仍被版本历史引用）；无对象的历史版本路径不建对象，保留原路径

## 放弃的方案
1. **全局 md5 去重（跨租户共享）**：可最大化节省存储，但破坏租户隔离，权限/审计/计费边界不清，且回滚风险大，放弃。
2. **纯 file_node 加索引去重（不新增表）**：引用计数难以原子维护、版本/复制场景无法统一，放弃。
3. **去重维度为 (tenant_id, md5, size)**：size 对同一文件内容稳定，加入组合键会增加误判面（同一 md5 不同 size 属异常数据），维持 (tenant_id, md5) 即可。
4. **CAS 循环重试实现并发首传**：实现复杂、收益低，用 `INSERT IGNORE` 的幂等插入足够，代价是极端并发下多一次冗余上传。

## 理由
- **租户隔离（安全）**：同租户唯一键保证物理对象不跨租户共享，符合云盘权限安全要求
- **引用一致性（数据正确性）**：显式 `ref_count` 原子增减，复制/版本/删除各链路统一走对象引用，杜绝"最后引用已删仍留孤儿 / 引用未归零误删"两类错误
- **秒传可扩展**：对象层与上传链路解耦，后续秒传/哈希索引/对象生命周期管理均在此层扩展
- **迁移友好**：保留旧列 + 回填脚本，存量数据无需重建物理对象即可平滑切换

## 后续限制
- 回填脚本 `28_file_object.sql` 需在存量生产库灰度执行并备份（大表全量扫描）
- 版本历史路径未纳入对象体系，可能产生孤儿物理对象，后续 TASK 需纳入对象生命周期清理
- 并发首传允许一次冗余上传，极端高并发下存储写放大有限（同 md5 同 key 覆盖），可接受
- `file_object` 与 `file_node.file_md5/storage_path` 双写，需维持一致性；后续可评估将冗余列逐步下线（当前保留）

## 关联
- 关联任务 State: `.ai/state/20260811-codex-tasks-execution.yaml`
- 关联文档: `.ai/docs/20260811-codex-tasks-execution/design.md`、`.ai/docs/20260811-codex-tasks-execution/changereport-t001.md`
- 迁移脚本: `docker/mysql/init/28_file_object.sql`