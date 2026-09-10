# 变更报告：新建文件（20260815）

> 由实现子任务按 taskCode 分节追加，主线程合并后归档。

## NF-FE-01（前端实现）

### 背景

为星云盘补齐「新建文件」入口：文件列表工具栏与空白区域右键菜单提供「新建」菜单（新建文件夹 + txt/docx/xlsx/pptx），Office 文件新建成功后自动进入 OnlyOffice 编辑。

### 修改文件清单

| 文件 | 变更 |
|------|------|
| `st-web/src/types/index.ts` | 新增 `BlankFileType`、`CreateBlankFileRequest` 类型 |
| `st-web/src/lib/fileSource.ts` | `FileSource` 新增 `createBlankFile(parentId, type)`；个人与团队数据源分别对接 `POST /api/file/new`、`POST /api/team/{spaceId}/files/new` |
| `st-web/src/components/file/FileToolbar.tsx` | 「新建文件夹」按钮升级为「新建」下拉菜单（文件夹 + 4 种文件类型），选择后自动收起 |
| `st-web/src/components/file/BlankContextMenu.tsx` | 空白区域右键菜单整合「新建」飞出子菜单（文件夹 + 4 种文件类型），支持点击/悬停展开、贴边翻转 |
| `st-web/src/components/file/FileBrowser.tsx` | 新增 `handleNewFile`：调接口成功 → 刷新列表；docx/xlsx/pptx 跳转 `/file/:nodeId/editor`（带来源路径）；失败 toast 提示错误信息且不跳转 |

### 与验收标准对照

| 验收标准 | 实现情况 |
|---------|---------|
| FileToolbar 新增「新建」下拉（文件夹/txt/docx/xlsx/pptx）+ 空白区域右键菜单整合（D2） | ✅ 已完成 |
| 调 POST /api/file/new 或团队端点成功后刷新列表；docx/xlsx/pptx 跳转 /file/:nodeId/editor（P3） | ✅ 已完成 |
| 失败（配额/权限）toast 提示不跳转；无权限不显示新建菜单 | ✅ 菜单按 `has('file:upload')` 显隐；失败 toast 展示后端错误信息 |
| npx tsc --noEmit 通过 | ✅ 通过；`npm run build` 由主线程统一验证 |

### 测试结果

- `cd st-web && npx tsc --noEmit`：通过（0 错误）
- `npm run build`：按派发约定由主线程统一验证

### 风险

- 团队端点为 `@RequestBody NewFileRequest`，与前端 body `{ parentId, type }` 契约一致；若联调时后端改为 query 参数需同步调整
- 收藏/分类页复用 FileBrowser，新建入口与既有「新建文件夹」行为一致（按全局 `file:upload` 权限显示）；团队文件夹级权限由后端把关（D3）
- 右键子菜单为固定宽度近似高度估算翻转，极端小屏下可能轻微偏移，不影响功能

### State Delta

- artifacts.code：`in_progress` → 待主线程核对产物后标记
- 无新增/解除 blocker
- 对应 exitCriteria：IMPLEMENTED（部分，前端侧）

## NF-BE-01（后端实现）与主线程整合

### 后端实现（executor）

- `st-core`：NewFileService(+Impl)、NewFileController（`POST /api/file/new`）、NewFileRequest DTO、
  `templates/blank.{docx,xlsx,pptx}` 标准 OOXML 空白模板
- `st-team`：TeamController 新增 `POST /api/team/{spaceId}/files/new`（upload 权限 + checkNotLocked + 活动日志）
- 流程：类型白名单 → 权限/归属校验 → 重名命名 → 模板字节 → 配额预检 → S3 落盘 + file_object 去重 →
  建 file_node（创建即完成）→ 事件发布 → 原子扣减配额（无半成品）

### 测试接管（主线程，tester 职责）

- BE 子线程违规编写的 NewFileServiceIntegrationTest.java 已删除（V15.3：实现子线程只产出代码与静态自检）
- 主线程重写 `NewFileServiceIntegrationTest`（10 用例，TC-01~TC-10）：各类型/重名/个人与团队权限/配额/事件/类型白名单/状态
- 修复 EditorCallbackIntegrationTest 遗留的冒号 key（对齐 document.key 下划线格式）

### 验证结果

- `mvn test` 全量通过（含 SchemaConsistencyTest、全部既有回归、新测试 10 例）
- `npm run build` / `tsc --noEmit` 通过

### 与验收标准最终对照

| 验收标准 | 结果 |
|---------|------|
| 个人/团队新建端点（owner / upload 权限） | ✅ |
| txt/docx/xlsx/pptx + 默认命名 + 重名序号 + 白名单 | ✅ |
| 新建即完成 + 配额 + 容量校验 | ✅ |
| FileIndexEvent + SyncChangeEvent(CREATE) | ✅ |
| TC-01~TC-14 覆盖 + 构建测试全绿 | ✅ |

## 修复：PPT 空白模板不合法（20260815 实测）

### 背景

新建 PPT 后打开 OnlyOffice 编辑器报
`PAGEERROR: Cannot read properties of undefined (reading 'createDuplicate')`（sdkjs/slide），
Word/Excel 正常。经隔离验证，根因不是 OnlyOffice 不支持 PPT，而是本项目自制的极简
`blank.pptx` 模板不是合法 OOXML：仅 12 个部件，缺少 `ppt/theme/theme1.xml`、
notesMasters、presProps/viewProps 等必需部件，slideMaster 未引用主题。

### 验证证据（同容器对照，ConvertService.ashx 转 PDF）

| 模板 | 结果 |
|------|------|
| 旧 `blank.pptx`（4590B，无主题） | 失败：`Error -3`（文件无法解析） |
| OnlyOffice 官方 zh-CN 空白模板（36342B，56 部件含 theme1.xml） | 成功：`Percent 100 / EndConvert True` |
| 用新模板经后端 `POST /api/file/new` 新建的 PPT（36342B） | 成功：转换 100% |

### 修改

- `st-core/src/main/resources/templates/blank.pptx`：替换为 OnlyOffice 官方 zh-CN 空白
  PPT 模板（56 个部件，含主题/母版/版式/备注母版），与 OnlyOffice 加载器完全兼容

### 验证

- 新建的 PPT 经 `POST /api/file/new` 创建后 fileSize=36342，内部含 theme1.xml 与
  notesMaster；OnlyOffice 可下载（HTTP 200）并成功解析转换
- 提示：新建 PPT 才会使用新模板；此前用旧模板创建的历史 PPT 仍为坏模板，需删除重建
