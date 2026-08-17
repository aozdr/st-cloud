# TASK：第一迭代 TX-03 — 事务超时配置与规范固化（F6 / F7）

> 依据 `.ai/docs/20260817-transaction-boundary/design.md` 3.3 节 F6-1/F6-2。

## 元信息

- Task ID: `TASK-20260817-transaction-boundary-03`
- 关联任务 State: `.ai/state/20260817-transaction-boundary.yaml`
- 关联文档: `.ai/docs/20260817-transaction-boundary/design.md`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-17

## 目标

1. `st-api/src/main/resources/application.yml`：新增全局事务默认超时 `spring.transaction.default-timeout: 30s`
2. `AGENTS.md`「代码修改强制约束」新增第 8 条：`8. 事务边界：核心写路径禁止在事务内执行 S3/外部网络调用（S3 先做、DB 后落；删除类走提交后异步补偿）`
3. `.ai/knowledge/conventions.md` 新增「事务边界」小节：五条原则（网络调用在事务外 / 删除类异步补偿 / 只读方法不开事务 / 长事务显式 timeout / 半成品 tmp 前缀 + 清理兜底）+ 已知反例清单（ArchiveServiceImpl.extractArchive、UploadServiceImpl.simpleUpload/mergeChunks、EditorCallbackServiceImpl.handleCallback、SyncBlockServiceImpl.blockCheck/blockUpload、RecycleBinServiceImpl 永久删除系列、TextFileServiceImpl.overwriteContent）

## 修改范围

- `st-api/src/main/resources/application.yml`
- `AGENTS.md`
- `.ai/knowledge/conventions.md`

## 禁止修改范围

- 任何 `st-*` 产品代码（除 application.yml）
- 不运行 mvn/npm/git；不写 `.ai/` 除 changereport 外内容

## 验收标准

- [ ] `application.yml` 含 `spring.transaction.default-timeout: 30s`（位置在 spring 配置下）
- [ ] `AGENTS.md` 第 8 条就位
- [ ] `conventions.md` 事务边界小节就位（原则 + 反例清单）
- [ ] 未触碰产品代码

## 测试要求

- 本任务无代码测试；主线程构建验证时确认配置不破坏启动（如 mvn test 覆盖到配置加载）

## 输出要求

完成后追加 `.ai/docs/20260817-transaction-boundary/changereport.md` 的「TX-03」章节（修改文件清单 / 与验收标准对照 / 测试结果 / 风险），并返回 State Delta。
