# 知识库回顾：历史版本预览

> Task: `20260910-version-preview`｜日期：2026-09-10

## 已同步的知识库文件

| 文件 | 更新内容 |
|------|---------|
| `.ai/knowledge/api-reference.md` | 预览模块新增 `GET /api/preview/{nodeId}/version/{versionId}` 及行为说明 |
| `.ai/knowledge/business-domain.md` | 「文件版本管理」补充历史版本预览规则、缩略图按版本缓存、Office 限制 |
| `docs/PRD-云盘系统-v2.0.md` | 功能清单、实现矩阵、缺口表、Story 6.1 验收项与实现现状更新 |

## 可复用的经验

1. **预览能力放在 st-preview**：`st-preview` 依赖 `st-core`，版本元数据（`FileVersion`/`FileVersionMapper`）可直接使用；反向（`st-core` 引用 `st-preview` 的 `PreviewResultVO`）不成立。
2. **版本级缩略图命名空间**：历史版本缩略图用 `thumbnails/{nodeId}/v{versionNum}/{size}.jpg`，与当前版本 `thumbnails/{nodeId}/{size}.jpg` 隔离，避免相互覆盖，也无需缓存迁移。
3. **Spring 单例 Mock 的桩会跨用例、跨测试类残留**：集成测试中对外部依赖 Mock 做桩后，必须在 `@AfterEach` 统一 `reset(...)`，否则异常桩会污染后续用例（本次实测）。
4. **OnlyOffice 编辑器只认当前版本**：历史版本预览必须短路编辑器跳转分支，PDF 退化为浏览器内置查看器（iframe）。
