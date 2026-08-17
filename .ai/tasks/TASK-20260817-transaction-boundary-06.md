# TASK：第二迭代 TX-06 — 解压/OnlyOffice 回调/文本覆盖改造（F5）

> 依据 `.ai/docs/20260817-transaction-boundary/design.md` 3.3 节 F5 与 P2 决策。

## 元信息

- Task ID: `TASK-20260817-transaction-boundary-06`
- 关联任务 State: `.ai/state/20260817-transaction-boundary.yaml`
- 关联文档: `.ai/docs/20260817-transaction-boundary/design.md`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-17

## 目标

三处"事务内网络 I/O"改造（可复用 TX-01 已合并的 `acquireByPath` 与 `UploadCommitManager`）：

1. `TextFileServiceImpl.overwriteContent`：S3 上传移出事务（先查对象/上传，再事务落 DB 节点+配额+版本+事件）
2. `EditorCallbackServiceImpl.handleCallback`：外部 URL 下载到临时文件 + S3 上传移出事务；DB 更新（节点+配额+事件）收敛进独立事务方法
3. `ArchiveServiceImpl.extractArchive`：ZIP 下载到临时文件（事务外）→ 预检统计 → 逐条目上传 S3（事务外，tmp 或最终 key）→ 一个事务内插入文件夹/文件节点 + 配额 + 事件；失败清理已上传对象，定时兜底

## 修改范围

- `st-core`：`TextFileServiceImpl.java`、`EditorCallbackServiceImpl.java`、`ArchiveServiceImpl.java`、`UploadCommitManager.java`（如需新增提交方法）、对应测试

## 禁止修改范围

- 其它模块；解压/回调/文本接口契约与业务规则不变
- 不运行 mvn/npm/git；不写 `.ai/` 除 changereport 外内容

## 验收标准

- [ ] 三处方法的 S3/外部网络调用全部在事务外（测试断言）
- [ ] 失败清理：已上传对象尽力删除（`deleteObjectQuietly`），半成品走 tmp 兜底
- [ ] 配额/引用/去重语义不变；解压进度回调语义不变
- [ ] 未修改 API/业务规则

## 测试要求

- 本任务不自行运行构建；主线程合并后统一 `mvn test`
- 重点回归：ArchiveServiceIntegrationTest、EditorCallbackIntegrationTest、文本覆盖相关测试

## 输出要求

完成后追加 `.ai/docs/20260817-transaction-boundary/changereport.md` 的「TX-06」章节，并返回 State Delta。
