# Spec 验证报告：历史版本预览 + 全库 spec 审查现状复核

> Task: `20260910-version-preview`｜类型：只读一致性验证（文档 ↔ 代码 ↔ 测试）｜日期：2026-09-10
> 验证对象：① 本次迭代 design.md 条款是否落地；② `.ai/docs/20260814-project-code-review/spec.md` 所列漂移的当前状态

## 一、本次迭代 spec 符合性

| 设计要求（design.md） | 代码证据 | 测试证据 | 结论 |
|------|---------|---------|------|
| 新增 `GET /api/preview/{nodeId}/version/{versionId}` | `PreviewController.java:28-31` | TC-01/02/03 | 符合 |
| 先校验节点可访问，再校验版本归属；仅凭 versionId 取不到对象 | `PreviewServiceImpl.java:84-89` | TC-04/05/06 | 符合 |
| 文件夹节点拒绝预览 | `getFileNode` 复用 | TC-09 | 符合 |
| 图片历史版本缩略图用版本级 key `v{versionNum}`，不覆盖当前版本 | `PreviewServiceImpl.java:95`（当前版本仍为 `:141` 的旧 key） | TC-03 断言 key | 符合 |
| 音视频/PDF 用预签名 URL；文本 500KB 截断 | `dispatchByStorage`（`:107`） | TC-01/02 | 符合 |
| Office 及其它类型返回 unsupported，不跳 OnlyOffice | 默认分派 + `PreviewModal.tsx:324` 文案 | TC-07 | 符合 |
| 仅非当前版本显示「预览」入口 | `VersionHistoryDialog.tsx:145`（`!isCurrent` 分支内） | 代码级确认 | 符合 |
| 版本模式禁切换/幻灯片/下载、不写最近文件、层级高于版本弹窗 | `PreviewModal.tsx:50`（空列表）、`:114`（跳过最近文件）、`:278`（隐藏下载）、`:260`（z-60） | 构建通过；浏览器点击未执行 | 基本符合（M 项待人工） |
| 现有当前版本预览行为不变 | `preview()`（`:79`）与 `previewVersion()`（`:101`）共用同一分派 | TC-08 + 原 `PreviewServiceIntegrationTest` 5 用例 | 符合 |
| 无数据库变更 | 无迁移脚本；仅测试 H2 补 `file_version` | — | 符合 |

未覆盖项：M-01 ~ M-04（浏览器点击级验证）需运行 MySQL/Redis/S3/前后端后人工确认。

## 二、全库 spec 审查（2026-08-14）现状复核

| 原编号 | 当时的漂移结论 | 当前现状 | 判定 |
|------|--------------|---------|------|
| 2.1.1~2.1.4 | 分享过期无未来时间校验、缺 `clearExpireAt`、缺过期徽标、前端用 UTC | 均已在代码中实现（`ShareServiceImpl`、`ShareManagePage`） | 已解决 |
| 2.1.5 | `ShareAccessVO.isExpired` 死代码 | 该字段已移除 | 已解决 |
| 2.1.6 | st-share 无测试、H2 缺 `file_share` | `st-share/src/test` 存在；H2 `schema.sql:227` 已有 `file_share` | 已解决 |
| 2.1.7 | 分享过期迭代文件不全 | 已有 `changereport.md`；仍缺 codereview/security/testreport | 部分解决 |
| 2.2 / 4.1 | 团队 roles/stats 端未暴露（前端必然 404） | `TeamController.java:438-467` 已暴露 `/roles`、`/role`、`/stats` | 已解决 |
| 2.3 | block-sync 脚本编号文档写 27 | design 已改为 `32_file_block.sql` | 已解决 |
| 2.4 | `st-web/android` 工程未生成 | 仍不存在（设计标注为命令行按需生成） | 未解决（待确认必要性） |
| 3.1 | api-reference 缺 6 组新端点 | 中转、块级同步、同步排除/冲突、团队邀请、通知均已收录 | 已解决 |
| 3.2 | data-model/business-domain 缺 `file_block` | 已收录（`data-model.md:273`、`business-domain.md:165`） | 已解决 |
| 3.3 | favorites 文档未按迭代归档 | 已迁至 `.ai/docs/20260814-favorites-archive/`；根目录仅剩 `favorites-enhancement-README.md` | 已解决 |
| 4.2 | block-check 契约文档为简版 | design 仍写请求 `{fileNodeId, blocks}`、响应 `{reusable, missing}`；实现为 `fileMd5/fileSize/blockSize` + `reusableBlocks/missingBlocks(presignedUrl)` | 未解决（文档落后，低） |

## 三、本次新发现的文档漂移

| 位置 | 现象 | 建议 |
|------|------|------|
| `docs/PRD-云盘系统-v2.0.md` §0 变更摘要、§12.4 下一步建议 | 仍写「版本恢复升为 P0 阻断项」「下一步实现版本恢复接口」，而版本恢复本次前后均已在代码中实现 | 属 v1→v2 历史描述，本次未改动；如需现状口径统一，可单独回写 |
| `.ai/docs/20260814-project-code-review/spec.md` | 报告头部与结论仍按 2026-08-14 状态陈述，10 项中 8 项已失效 | 本报告已给出逐条现状，原报告作为历史审计记录保留 |

## 四、仍开放项汇总

以上 5 项已于 2026-09-10 处置完毕：

| 优先级 | 项 | 处置结果 |
|-------|---|---------|
| P2 | `st-web/android` 工程 | 已处理：`20260809-mobile-pwa-capacitor/design.md` 补现状说明（按需 `npx cap add android`，不出包则无需生成） |
| P2 | share-expiry 迭代缺 `codereview.md` / `security.md` / `testreport.md` | 已处理：补 `20260813-share-expiry/README.md` 记录缺口关闭证据；**不补造历史评审记录**，如需留痕应在当前状态重新评审 |
| P2 | `favorites-enhancement-README.md` 位置 | 已处理：迁入 `.ai/docs/20260814-favorites-archive/` |
| P3 | block-sync design 的 block-check 契约 | 已处理：按实现同步 `BlockCheckRequest/Response`、`BlockUploadRequest` 字段与缺块直传说明 |
| P3 | PRD 历史章节「版本恢复待实现」 | 已处理：§12.1 与 §12.5 补 2026-09-10 现状说明 |

## 五、验证局限

- 本次为静态一致性核对（文档 ↔ 代码 ↔ 测试用例），未连接 MySQL 执行 `compare-schema.ps1`，未做运行期接口探测。
- 前端条目以代码路径与类型检查为准，浏览器点击级验收未执行。
