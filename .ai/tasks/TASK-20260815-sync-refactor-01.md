# TASK：同步引擎 V2 重构（死循环修复 + 版本门控 + 异常数据清理）

> 开发前置产物。工程师编码输入只接受本文件。

## 元信息

- Task ID: `TASK-20260815-sync-refactor-01`
- 关联任务 State: `.ai/state/20260815-sync-refactor.yaml`
- 关联文档: `.ai/docs/20260815-sync-refactor/design.md` / `testcases.md`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-15

## 目标

消除同步死循环与冲突文件自生成；以“同步引擎版本 + 最后同步时间”门控全量同步；清理已产生的机器格式垃圾副本（数据库 + S3）。

## 修改范围

- 模块 / 目录：st-desktop（同步引擎）、st-sync / st-core（服务端守卫与清理）、scripts（清理脚本）
- 涉及文件：
  - st-desktop/src/sync-engine.ts、database.ts、db-migrate.ts、sync-utils.ts（新增）、sync-utils.test.ts（新增）、db-migrate.test.ts、package.json
  - st-core/.../FileServiceImpl.java、RecycleBinService.java、RecycleBinServiceImpl.java
  - st-sync/.../SyncServiceImpl.java、SyncAdminController.java（新增）
  - scripts/cleanup-sync-junk.ps1（新增）
- 涉及接口 / 数据库：新增 `POST /api/admin/sync/cleanup-junk`（管理员）；客户端 sync_config 增加 sync_version 列（本地 sql.js）；服务端表结构不变
- 前后端联动：桌面端首次启动触发一次全量重建；无接口契约破坏

## 禁止修改范围

- 不得改动 upload-manager.ts / download-manager.ts / api-client.ts / 渲染层 UI（st-web）
- 不得改动服务端既有对外 API 契约（delta 仅新增行为过滤，不改变参数/响应结构）
- 不得新增 MySQL 表/字段（本次不涉及服务端 schema 变更）

## 验收标准

- [ ] 打开应用不再无条件全量；版本不符/last_sync_at 为空才全量一次，且全量成功才固化版本
- [ ] keep_both 只产两份副本且不回流；连续多轮日志无新增上传/下载/冲突
- [ ] sync_state 局部更新不再擦 local_mtime（合并语义）
- [ ] 服务端不再产生 old==new 的 MOVE/RENAME 脏日志（源头守卫 + delta 过滤）
- [ ] 构建测试：tsc / npm test（16 例）/ build:main / mvn -pl st-sync -am compile 全绿
- [ ] 清理接口与脚本可用（dry-run 列出，apply 删除含 S3 物理对象）

## 测试要求

- 单元测试：sync-utils 纯函数 + db-migrate 迁移保留断言
- 构建：`npx tsc --noEmit`、`npm test`、`npm run build:main`、`mvn -pl st-sync -am -DskipTests compile`
- 手工验证点：启动门控 / 无操作零同步 / 冲突收敛 / 同目录移动与同名重命名不再产生 MOVE/RENAME / 删除保护 / 文件夹移动子孙不重传

## 输出要求

编码完成后输出 Change Report 并落盘 `.ai/docs/20260815-sync-refactor/changereport.md`（修改文件清单 / 与验收标准对照 / 测试结果 / 风险）。
