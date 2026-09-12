# 安全审查报告

## 结论

基于代码路径、任务验收标准和回归证据，当前 P0/P1 范围内未发现会阻断验收的跨租户、跨用户或路径穿越缺陷。安全审查可接受。

## 控制项

| 风险面 | 当前控制 |
|---|---|
| 租户/用户越权 | 查询、修改、回收站、恢复、下载和 Archive 入口校验 tenant；个人 API 拒绝 team node |
| 团队空间 | 团队上传使用显式 team route，先做 ACL，再校验 session.spaceId |
| uploadId 泄露 | session 绑定 tenant/user/node/space，S3 标识和 storage path 以服务端记录为准 |
| 状态竞态 | merge claim、失败转换、版本分配和更新均检查 affected rows；数据库唯一键兜底 |
| 路径穿越 | Archive/ZIP 拒绝绝对路径、`.`、`..`、空 segment，并对段名做 sanitizer |
| 压缩炸弹/资源耗尽 | 条目数、单条/总展开大小、深度、压缩比、临时文件、有界 executor 和用户并发上限 |
| 分片绕过 | fileSize、totalChunks、chunkSize、ceil 关系、MD5、clientLimit 和未知 Content-Length 均受服务端约束 |
| 外部网络/事务 | S3 multipart 在数据库提交路径之外；失败后执行 session/node/abort 补偿 |

## 残余风险

1. 本地测试使用 Mockito 模拟对象存储，不证明真实 S3/MinIO 的权限策略、multipart 超时和网络重试配置。
2. 初始化失败的清理是补偿式而非跨系统原子事务；生产环境仍需保留定时扫描和告警。
3. Archive 配置可被部署配置覆盖，生产发布需审查配置值不能放宽到超出容量和队列预算。
4. 迁移遇到存量唯一冲突时会失败并保留冲突数据，符合本任务“禁止自动清理”的决策；上线前应人工预检。

## 证据

- `FileServicePermissionIntegrationTest` 5/5、`FileServiceFlowIntegrationTest` 7/7。
- `ArchiveServiceIntegrationTest` 9/9、`ArchiveExtractTransactionBoundaryTest` 2/2。
- 上传状态、relay、事务边界测试全部通过。
- Schema 对比和迁移登记均通过；没有执行任何数据删除。
