# TASK：第二迭代 TX-04 — st-sync 块复制移出事务（F3）

> 依据 `.ai/docs/20260817-transaction-boundary/design.md` 3.3 节 F3。

## 元信息

- Task ID: `TASK-20260817-transaction-boundary-04`
- 关联任务 State: `.ai/state/20260817-transaction-boundary.yaml`
- 关联文档: `.ai/docs/20260817-transaction-boundary/design.md`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-17

## 目标

`SyncBlockServiceImpl.blockUpload`：S3 `UploadPartCopy` 循环与 `completeMultipartUpload` 移出事务；DB 写入（acquireByPath + 节点更新 + 版本快照 + 块布局 + 配额 + 事件）收敛进独立 `@Transactional` 方法（复用 `UploadCommitManager` 或新增等价协作 bean）。

## 修改范围

- `st-sync`：`SyncBlockServiceImpl.java`（blockUpload 结构拆分）
- `st-core`：`UploadCommitManager.java`（如需新增块级提交方法，与 TX-01 合并后的主分支版本兼容）
- 对应测试：S3 复制在事务外、DB 落库原子、失败清理

## 禁止修改范围

- 其它模块；接口契约与块同步业务规则不变
- 不运行 mvn/npm/git；不写 `.ai/` 除 changereport 外内容

## 验收标准

- [ ] `blockUpload` 的 S3 调用全部在事务外
- [ ] DB 写入单事务原子（节点 + 版本快照 + 块布局 + 配额 + 事件）
- [ ] 去重命中清理（deleteObjectQuietly）保持幂等
- [ ] 未修改 API/业务规则

## 测试要求

- 本任务不自行运行构建；主线程合并后统一 `mvn test`

## 输出要求

完成后追加 `.ai/docs/20260817-transaction-boundary/changereport.md` 的「TX-04」章节，并返回 State Delta。
