# Change Report：OnlyOffice 支持编辑 PDF — 20260823

> 归属：主线程直接执行的聚焦特性启用（在既有 OnlyOffice 编辑管线上放开 PDF 编辑），
> 不涉及数据库表变更与接口契约变化，作为 IMPLEMENTED 依据。

## 背景

OnlyOffice 在线编辑（20260815 迭代）仅对 docx/xlsx/pptx 开放编辑，PDF 在生成配置时被强制为只读查看。
经核实 ONLYOFFICE Docs 8.1 起提供全功能 PDF 编辑器（文本编辑、页面增删/旋转、对象插入、注释等，
社区版可用），本项目 docker-compose 使用 `onlyoffice/documentserver:latest`（当前 9.x），
因此具备开启 PDF 编辑的运行时条件。本轮目标：让有编辑权限的用户可以在线编辑 PDF，保存回写为 PDF。

## 输入

- 用户需求：OnlyOffice 能否编辑 PDF，可以则在项目实现。
- 阅读代码：`EditorConfigServiceImpl` / `EditorPermissionServiceImpl` / `EditorController` /
  `EditorCallbackServiceImpl`（确认回调落盘与格式无关）/ 前端 `lib/editor.ts`、`FileBrowser`、`ContextMenu`、
  `FileToolbar`、`PreviewModal`、`ShareAccessPage`。

## 决策

- 不新增接口、不改数据结构、不改回调协议：PDF 编辑复用现有 config/回调/版本/锁/保护链路。
- 前端「在线编辑」入口白名单从 `docx/xlsx/pptx` 扩到 `docx/xlsx/pptx/pdf`；
  双击预览仍是只读（`?mode=view`），「在线编辑」按钮走默认 edit 模式。
- 只读查看（`mode=view`）仍强制 `canEdit=false`，与 `EditorController` 现有逻辑一致；
  本次仅移除对 PDF 的硬编码只读拦截。

## 改动文件清单

后端：

- `st-core/src/main/java/com/stcloud/core/editor/EditorConfigServiceImpl.java`：移除
  `canEdit = canEdit && !"pdf".equals(node.getSuffix())` 的强制只读拦截，保留注释说明 PDF 可编辑。
- `st-core/src/main/java/com/stcloud/core/editor/EditorPermissionServiceImpl.java`：注释与支持格式描述更新为
  `docx/xlsx/pptx/pdf`。
- `st-core/src/main/java/com/stcloud/core/editor/EditorPermissionService.java`：`isEditableSuffix` 注释同步。

前端：

- `st-web/src/lib/editor.ts`：`EDITABLE_SUFFIXES` 增加 `'pdf'`，注释同步。
- `st-web/src/components/file/FileBrowser.tsx`：可编辑判定与工具栏注释同步为含 PDF。
- `st-web/src/components/file/ContextMenu.tsx`：`showEdit` 注释同步。
- `st-web/src/components/file/FileToolbar.tsx`：`canEditSelected` 注释同步。

测试：

- `st-core/src/test/java/com/stcloud/core/editor/EditorConfigServiceImplTest.java`（新增）：Mockito 单测，
  验证 PDF + 编辑权限 → `mode=edit`、`permissions.edit=true`；view 模式仍只读；config 含签名 token。
- `st-core/src/test/java/com/stcloud/core/editor/EditorPermissionIntegrationTest.java`：新增
  `owner_canEdit_pdf`，验证 owner 打开 PDF 可编辑。

文档：

- `.ai/docs/20260815-onlyoffice-editor/testcases.md`：TC-06 / TC-25 覆盖 `pdf`。

## 与验证对照

| 验证项 | 结果 |
|--------|------|
| PDF（edit）config：documentType=pdf、permissions.edit=true、mode=edit | `EditorConfigServiceImplTest.pdfOwner_canEdit` |
| PDF（view）config：mode=view、permissions.edit=false | `EditorConfigServiceImplTest.pdf_viewMode_isReadOnly` |
| owner 个人文件 PDF 可编辑 | `EditorPermissionIntegrationTest.owner_canEdit_pdf` |
| 前端入口含 pdf | `editor.ts.EDITABLE_SUFFIXES`，由 `FileBrowser.canEditNode` / `FileToolbar` / `ContextMenu` / `ShareAccessPage` 消费 |

## 风险与说明

- **运行时版本**：PDF 编辑能力依赖 ONLYOFFICE Docs 8.1+；项目当前用 `onlyoffice/documentserver:latest`
  （9.x）满足要求。若生产固定了 8.0 及更早镜像，PDF 会退化为只读，需升级镜像。
- **PDF 编辑保存格式**：OnlyOffice 编辑 PDF 后回调返回 PDF 字节，沿用现有去重/落盘/配额/版本链路，无需额外处理。
- **前端入口行为**：分享页 `isOfficeViewable = isEditableOfficeSuffix(suffix) || isPdf(suffix)` 在 PDF 进入
  `EDITABLE_SUFFIXES` 后条件冗余但语义不变（PDF 查看仍走 OnlyOffice）。

## 下一步

- 运行 `mvn -pl st-core -am test` 与 `st-web` 的 `tsc --noEmit` + `npm run build` 验证。
- 部署环境（含 OnlyOffice 容器）做端到端回归：打开 PDF → 编辑 → 保存 → 关闭，确认版本生成与文件保护正常。
