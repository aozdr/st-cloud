# TASK：历史版本预览（st-preview 版本预览接口 + st-web 版本预览弹窗）

> 开发前置产物。编码输入只接受本文件与 `.ai/docs/20260910-version-preview/design.md`。

## 元信息

- Task ID: `TASK-20260910-version-preview-01`
- 关联任务 State: `.ai/state/20260910-version-preview.yaml`
- 关联文档: `.ai/docs/20260910-version-preview/design.md` / `.ai/docs/20260910-version-preview/testcases.md`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-09-10
- 用户已确认遗留问题：P1 仅支持图片/视频/音频/文本/PDF，Office 提示不支持；P2 仅非当前版本显示预览入口；P3 不做下载历史版本

## 目标

为文件历史版本增加在线预览能力：登录用户可在历史版本弹窗中直接查看指定版本内容，不恢复、不修改当前版本。

## 修改范围

- 模块 / 目录：`st-preview`（后端）、`st-web`（前端）
- 涉及文件：
  - `st-preview/src/main/java/com/stcloud/preview/controller/PreviewController.java`
  - `st-preview/src/main/java/com/stcloud/preview/service/PreviewService.java`
  - `st-preview/src/main/java/com/stcloud/preview/service/impl/PreviewServiceImpl.java`
  - `st-preview/src/test/resources/schema.sql`（仅测试用 H2 补 `file_version` 表）
  - `st-preview/src/test/java/com/stcloud/preview/service/PreviewVersionIntegrationTest.java`（新增）
  - `st-web/src/components/preview/PreviewModal.tsx`
  - `st-web/src/components/file/VersionHistoryDialog.tsx`
  - `st-web/src/components/file/FileBrowserDialogs.tsx`
- 涉及接口 / 数据库：新增 `GET /api/preview/{nodeId}/version/{versionId}`；无数据库变更
- 前后端联动：前端按 design.md 第五章契约调用新端点

## 禁止修改范围

- 不得修改 `st-share`、`st-team`、`st-admin`、`st-search`、`st-sync`、`st-desktop`
- 不得修改 `docker/mysql/init/**`、`st-core/src/test/resources/schema.sql`、`SchemaConsistencyTest`
- 不得修改现有端点 `/api/preview/{nodeId}`、`/thumbnail`、`/video` 的路径与返回结构
- 不得修改 `VersionController` 的 list/restore 行为
- 不得新增表、字段、索引或迁移脚本

## 验收标准

- [ ] `GET /api/preview/{nodeId}/version/{versionId}` 返回指定版本预览（图片/视频/音频/文本/PDF）
- [ ] 版本归属与节点可访问性双重校验；跨文件、跨租户 versionId 返回「版本不存在」
- [ ] 图片版本缩略图使用 `v{versionNum}` 键，不覆盖当前版本缩略图
- [ ] Office 及其它类型返回 `unsupported`
- [ ] 历史版本弹窗非当前版本行显示「预览」，预览层显示「历史版本 V{n}」
- [ ] 版本模式禁用左右切换/幻灯片/下载，不写最近文件，不跳 OnlyOffice
- [ ] 现有当前版本预览行为不变

## 测试要求

- 集成测试：`st-preview` 覆盖 testcases.md 中 TC-01 ~ TC-09
- 构建：`mvn -pl st-preview -am test` 全绿；`cd st-web; npm run build` 通过
- 手工验证点：M-01 ~ M-04

## 输出要求

编码完成后输出 Change Report 并落盘 `.ai/docs/20260910-version-preview/changereport.md`（修改文件清单 / 与验收标准对照 / 测试结果 / 风险）。
