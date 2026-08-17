# TASK：第一迭代 TX-01 — st-core 上传路径事务边界改造（F1-3 / F2-1 / F2-2）

> 依据 `.ai/docs/20260817-transaction-boundary/design.md` 3.3 节 F1-3/F2-1/F2-2。

## 元信息

- Task ID: `TASK-20260817-transaction-boundary-01`
- 关联任务 State: `.ai/state/20260817-transaction-boundary.yaml`
- 关联文档: `.ai/docs/20260817-transaction-boundary/design.md`、`requirement.md`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-17

## 目标

将 st-core 上传路径的 S3/外部网络调用移出数据库事务，并拆分只读检查与秒传写入：

1. `FileObjectService` 新增 `acquireByPath(tenantId, md5, size, storagePath)`：仅 DB 操作（select → 命中 incrementRefCount / 未命中 insertIgnore + 竞争复用），不触发上传
2. `UploadServiceImpl.checkInstantUpload`：拆为"只读检查（无事务）+ 秒传命中创建（独立 bean 事务方法）"；非命中路径不再开事务
3. `UploadServiceImpl.simpleUpload`：S3 限速上传移出事务（上传到 `{tenantId}/{md5}`），成功后由独立 bean 的 `@Transactional` 方法落 DB（acquireByPath + 插入节点 + 扣配额 + 发事件）；事务失败时若本请求创建了对象记录（insertIgnore==1）则尽力删除已上传对象，删除前校验 ref_count==0
4. `UploadServiceImpl.mergeChunks`：S3 `completeMultipart`/`abort`/`deleteObjectQuietly` 移出事务；`handleMergeFailure` 收敛为独立小事务；成功后独立 bean 的 `@Transactional` 方法落 DB（markChunksMerged + acquireByPath + 节点更新 + 版本快照 + 差值配额 + 事件）

## 修改范围

- `st-core`：`FileObjectService` + `FileObjectServiceImpl`（新增 `acquireByPath`）
- `st-core`：`UploadServiceImpl`（checkInstantUpload / simpleUpload / mergeChunks 结构拆分）
- `st-core`：上传管理协作 bean（`UploadManager` 或等价独立 bean，新增 `@Transactional` 方法承接 DB 落库）
- `st-core`：对应单元/集成测试（新增用例：非秒传路径无事务、S3 超时不断连、DB 失败清理）

## 禁止修改范围

- `st-sync/**`、`st-share/**`、`st-team/**` 等其它模块
- 上传 API 契约（路径/请求/响应结构）与业务规则（配额/引用/去重语义）不变
- 数据库表/字段/迁移不变
- 不运行 mvn/npm/git；不写 `.ai/` 除 changereport 外内容

## 验收标准

- [ ] `acquireByPath` 存在且不触发任何 S3 调用
- [ ] `checkInstantUpload` 非命中路径不开启事务（测试断言）
- [ ] `simpleUpload` 的 S3 上传发生在事务外；失败时按规则清理且不误删被引用对象
- [ ] `mergeChunks` 的 S3 调用在事务外；并发幂等保持（现有测试不回归）
- [ ] 未修改任何 API/DB/业务规则

## 测试要求

- 本任务不自行运行构建；主线程合并后统一 `mvn test`
- 新增测试：`acquireByPath` 命中/未命中/并发竞争；`simpleUpload` DB 失败清理；`mergeChunks` S3 失败路径

## 输出要求

完成后追加 `.ai/docs/20260817-transaction-boundary/changereport.md` 的「TX-01」章节（修改文件清单 / 与验收标准对照 / 测试结果 / 风险），并返回 State Delta。
