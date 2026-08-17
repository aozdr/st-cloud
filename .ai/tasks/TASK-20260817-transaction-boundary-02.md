# TASK：第一迭代 TX-02 — 只读方法去事务（F1-1 / F1-2）

> 依据 `.ai/docs/20260817-transaction-boundary/design.md` 3.3 节 F1-1/F1-2。

## 元信息

- Task ID: `TASK-20260817-transaction-boundary-02`
- 关联任务 State: `.ai/state/20260817-transaction-boundary.yaml`
- 关联文档: `.ai/docs/20260817-transaction-boundary/design.md`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-17

## 目标

1. `st-share` `ShareServiceImpl.getDownloadUrl`：删除 `@Transactional`（纯读 + 预签名 URL，无 DB 写）
2. `st-sync` `SyncBlockServiceImpl.blockCheck`：删除 `@Transactional`；S3 `initMultipartUpload` 调用移至事务外（方法整体无 DB 写，S3 失败直接返回错误）

## 修改范围

- `st-share`：`ShareServiceImpl.java`（getDownloadUrl 去注解）
- `st-sync`：`SyncBlockServiceImpl.java`（blockCheck 去注解；S3 init 调用位置调整）
- 对应测试：断言两方法不再开启事务（事务拦截/日志）

## 禁止修改范围

- `st-core/**`、`st-team/**` 等其它模块
- 接口契约与权限校验逻辑不变
- 不运行 mvn/npm/git；不写 `.ai/` 除 changereport 外内容

## 验收标准

- [ ] `getDownloadUrl` 无 `@Transactional`，逻辑不变
- [ ] `blockCheck` 无 `@Transactional`，S3 init 在事务外，逻辑不变
- [ ] 新增/调整测试验证两方法不开启事务
- [ ] 未修改任何 API/业务规则

## 测试要求

- 本任务不自行运行构建；主线程合并后统一 `mvn test`

## 输出要求

完成后追加 `.ai/docs/20260817-transaction-boundary/changereport.md` 的「TX-02」章节（修改文件清单 / 与验收标准对照 / 测试结果 / 风险），并返回 State Delta。
