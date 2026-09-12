# 测试报告

## 后端

| 命令 | 结果 |
|---|---|
| `mvn -pl st-core -am test` | 通过；st-common 31/31，st-core 159/159 |
| 定向 Archive/权限/上传/Schema 套件 | 55/55 通过 |
| `mvn -pl st-team -am -DskipTests compile` | 通过 |
| `git diff --check` | 通过，退出码 0 |

定向套件包含：SchemaConsistency 3、ArchiveExtractTransactionBoundary 2、ArchiveServiceIntegration 9、FileServiceFlow 7、FileServicePermission 5、RelayUpload 13、UploadStateMachine 12、UploadTransactionBoundary 4。

## 前端与桌面端

- `st-web`: `npm run build` 通过，TypeScript 与 Vite/PWA 构建完成。
- `st-desktop`: `npm run lint` 通过；`npm test` 17/17 通过；`npm run build:main` 通过。

## 数据库

- 测试应用使用 H2 内存数据库，因此不依赖 Docker。
- 本地 MySQL `127.0.0.1:3306` 已执行 `40_upload_session.sql`、`41_file_node_version_uniqueness.sql` 并登记 `schema_version=20260912.1`。
- `.ai/scripts/compare-schema.ps1` 通过：H2 15 表、MySQL 37 表，共享表列一致，所有 SQL 已登记，`Result: PASS (no diff)`。

## 结论

本轮代码、H2 schema、客户端构建和本地 MySQL schema 门禁均已通过。真实对象存储、消息队列和部署环境联调仍需在目标环境执行。

补充：初次提交时发现任务 State 使用了块式 YAML，无法满足 V2 的 JSON-compatible YAML 约束；已将 State 转为合法 JSON 文档（JSON 同时是 YAML 子集），`loopctl validate` 和完整 `run-loop-gate.ps1 -SkipIntegrationTests` 均已通过，最终结果为 `LOOP_GATE_PASS`。
