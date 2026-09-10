# 变更报告：st-web 前端审查与重构

- taskCode：TASK-20260816-frontend-refactor-01
- 日期：2026-08-16
- 状态：已实现并验证（lint 0 错误 / build 通过 / dev 冒烟 200）

## 变更摘要

### P0 正确性与安全

- `Login.tsx:53`：修复 JSX 字面 `\n`，登录页不再渲染多余文本。
- `lib/utils.ts`：新增 `sanitizeHighlight`（白名单 `<em>` 消毒）；`SearchPage.tsx` 两处高亮渲染改走消毒。
- `TopBar.tsx`：修复「搜索当前文件夹」label 包裹 button 的无效结构，改为 `label[htmlFor]` + `button[role=switch]`。
- `HomePage.tsx`：修复三元表达式语句（no-unused-expressions）。

### 移除 Word/Excel 本地预览（统一 OnlyOffice）

- `PreviewModal.tsx`：删除 docx-preview / xlsx 本地渲染（renderDocx、parseExcel、Excel sheet 选择器、相关 state/effect/JSX）。
- Office（docx/xlsx/pptx）与 PDF 预览一律跳转 OnlyOffice 只读视图：个人 `/file/:id/editor?mode=view`；分享 `/share/:shareCode/editor?nodeId=&mode=view`。
- `package.json` / `package-lock.json`：移除 `docx-preview`、`xlsx` 依赖（产物减少约 660KB，PWA precache 2235KB → 1573KB）。

### P1 结构重构

- `FileBrowser.tsx`：1265 → ~1050 行；抽取 `useFileSelection` / `useFileClipboard` / `useFileDialogs` / `useFolderSearch`；三视图渲染收敛为 `FileList` 组件，消除 3 份重复 props 与 `onDoubleClick`。
- `FileThumbnail.tsx`：合并原 GridThumbnail（新增 `blur`/`className` 支持网格大图），统一引用 `utils.isImage`，消除与 utils 漂移的后缀表。
- `HomePage.tsx`：抽取 `FileCard` 组件，收藏/最近访问/最近文件三段卡片复用。
- `lib/api.ts`：新增 `buildStreamUrl`，替换 fileSource / FileThumbnail / PreviewModal 中 5 处 token-in-URL 重复拼接。
- 移除死代码：`networkError`、`isFolderChange`、`handleItemMenu`、`AppLayout` 未用导入等。
- 清理 35 个 ESLint 错误（未用导入/变量、`any` 收敛），并修复 5 个 hook 依赖警告。

### P2 可访问性

- `index.css`：`.btn-*` 组件类补 `focus-visible` 焦点环。
- `AppLayout.tsx`：`<main id="main-content">`（skip link 已按用户要求移除，见「UI/UX 优化轮」）。
- `Sidebar.tsx`：移除无条件 `aria-current`（NavLink 自动管理）。
- 预览弹窗关闭/翻页按钮补焦点环；登录页/管理页装饰图标补 `aria-hidden`。

## 验证结果

- `npm run lint`：0 errors / 8 warnings（既有：MoveDialog loadTree、6 处 react-refresh、ShareAccessPage loadFiles，均为 warning）。
- `npm run build`：通过；产物不再含 xlsx/docx-preview chunk。
- dev server 冒烟：`/`、`/login`、`/src/main.tsx`、FileList、PreviewModal、FileBrowser 均 200。

## 风险与遗留

- `PreviewModal` 分享场景 Office 预览改为跳转 OnlyOffice 分享编辑器路由，需在真实分享链接上回归（后端 editor-config 对 PDF 的分享支持如受限需后端配合）。
- `MoveDialog`/`ShareAccessPage` 的 hook 依赖警告保留（直接补依赖会导致重复请求循环）。
- `refreshToken` 存 localStorage 属于既有设计，需后端支持 httpOnly cookie 才能根治，本迭代未改。

## 追加：UI/UX 优化轮（2026-08-16 二次审查）

依据：`web-design-guidelines` + `frontend-design-ui-ux`（Design Pre-Flight / anti-slop），对照 `.ulpi/design/DESIGN.md`。

### P0 体验缺陷

- `FileBrowser.tsx`：目录加载失败不再误显示「此文件夹为空」，改为错误空态 + 重试按钮（`loadError` 状态）。

### P1 视觉与一致性

- `Sidebar.tsx`：深色模式激活项背景按 DESIGN.md 注释补 `/0.15` 透明度，消除纯蓝刺眼底。
- `.ulpi/design/frontend-redesign.md`：标记为 V3 过期文档，禁止再按其执行。
- `Login.tsx` / `HomePage.tsx`：移除 hero/登录面板的 `blur-3xl` 圆形光晕（anti-slop 明确点名的 glow 特征）。
- `FileToolbar.tsx`：低频操作（移动到/复制到/批量重命名）收进「更多」下拉菜单，降低工具栏密度。

### P2 细节

- `SearchPage.tsx`：`autoFocus` 仅桌面端生效（避免移动端强制弹键盘）；placeholder `...` → `…`。
- `Login.tsx`：移动端补 `<h1>`（星云盘），标题层级完整。
- `ContextMenu.tsx`：「详情」移到菜单底部（分享之后、删除之前），常用操作前置。
- `TransferFloatingWidget.tsx` / `TeamSpacePage.tsx`：`...` → `…` 文案统一。
- `FileBrowser.tsx`：目录滚动位置按 parentId 缓存，返回目录时恢复（数据仍重新拉取）。
- `AppLayout.tsx`：移除左上角「跳到主要内容」skip link（用户确认不需要）。

### 未改动（用户要求其他不动）

- 设计语言默认方向（DESIGN.md 主张暗色+紫罗兰，实现为浅色+蓝色）未做默认值变更，待用户拍板。
- 主题色、默认模式、滚动数据缓存等其余项保持现状。

## 追加：在线解压后端加固 + S3 上传验证（2026-08-16）

### 后端修正（st-core）

- `ArchiveServiceImpl.extractArchive` 新增 `validateTargetFolder`：目标目录必须是存在（`FILE_NOT_FOUND`）、是文件夹（`FILE_TYPE_NOT_ALLOWED`）、且属于当前用户或租户管理员（`FORBIDDEN`）；`0` 表示根目录。防止把文件解压到他人目录。

### S3 上传验证

- 代码路径确认：`ArchiveServiceImpl` 逐条目读取 ZIP 后调用 `StorageService.uploadObject` → `StorageServiceImpl` 真实执行 S3 `PutObject`（bucket `stcloud`，endpoint `127.0.0.1:9000` MinIO）。
- 新增 `ArchiveServiceIntegrationTest`（5 用例全绿）：
  1. 解压到根目录：断言 `uploadObject` 按条目调用 2 次，key 前缀 `files/1/`、内容（hello/world）、大小（5B）均正确，file_node 落库含嵌套目录；
  2. 解压到用户子目录：产物挂在目标目录下；
  3~5. 安全校验：他人目录 → 403；目标是文件 → 2005；目标不存在 → 2001。

### 遗留提示（未改，待决策）

- 解压产物直接写 S3（`files/{tenantId}/{uuid}/{name}`），不走 file_object 去重（`REF_COUNT_NONE`，代码注释为有意设计），因此解压不校验存储配额/容量；如需纳入配额管控需另做迭代。

## 追加：解压接入去重与配额（2026-08-16，用户确认）

### 改动（st-core `ArchiveServiceImpl`）

- 每个 ZIP 文件条目读取进内存后计算 MD5，走 `fileObjectService.acquire` 去重引用：
  - 同租户同 md5 已存在 → 复用物理对象、引用 +1（秒传），不再重复上传；
  - 未命中 → 上传到规范路径 `{tenantId}/{md5}` 并建 `file_object` 记录。
- 文件节点现在写入 `objectId` / `fileMd5` / 规范 `storagePath`，`refCount` 由 `syncRefCountByMd5` 校正。
- 配额：解压前预扫描 ZIP 统计文件总大小并 `checkUserQuota` 快速失败；逐条目原子扣减 `sys_user.storage_used`（`updateStorageUsed`，并发超配额时 UPDATE 返回 0 抛 `STORAGE_QUOTA_EXCEEDED` 回滚事务），避免解压到一半超配额留下孤儿 S3 对象。

### 测试（`ArchiveServiceIntegrationTest`，7/7 通过）

1. 解压到根目录：S3 上传 2 次（key `1/{md5}`、内容/大小正确）、节点落库含 objectId/fileMd5、配额扣减 10B；
2. 解压到子目录：产物挂在目标目录；
3~5. 目录校验：他人目录 403 / 非文件夹 2005 / 不存在 2001；
6. 去重命中：不重复上传、file_object refCount 1→2、节点引用已有对象；
7. 配额不足：预检抛 `STORAGE_QUOTA_EXCEEDED`，无上传、无扣减、无节点。

## 追加：加载态补齐 + 解压交互升级（2026-08-16，用户反馈）

### 解压对话框（`ArchiveDialog.tsx` 重写）

- 目标路径可手动编辑：默认「压缩包所在文件夹/压缩包名」（如 `/docs/archive.zip` → `/docs/archive`），路径缺失时解压前自动逐级创建目录（`/file/by-path` 解析 + `/file/folder` 创建）。
- 目录树选择器与路径输入双向同步（树节点带路径映射）。
- 解压过程显示不确定进度条 + 条目数提示，解压期间关闭按钮与遮罩点击禁用。
- 说明：进度条为不确定动画；真实百分比需要后端把解压改为异步任务 + 轮询进度，本次未做。

### 通用加载态

- `ConfirmDialog` 支持异步 `onConfirm`：确认按钮显示加载态，成功 resolve(true)、失败 resolve(false) 后关闭；`FileBrowser` 删除操作已接入（删除请求执行期间弹窗保持加载）。
- `ConvertDialog` / `BatchRenameDialog` 按钮加 spinner；批量重命名文案 `...` → `…`。
- 耗时操作增加进行中提示：多文件打包下载（「正在打包下载，请稍候…」）、拖拽移动（「正在移动…」）、剪贴板粘贴（「正在粘贴…」）。
- 新增 `animate-progress-indeterminate` 不确定进度条动画。

### 遗留

- `RecycleBin.tsx` 为单行压缩文件，永久删除/清空仍走旧的立即关闭模式（可后续按 ConfirmDialog onConfirm 接入）。

## 追加：删除异步化 + 右上角常驻进度（2026-08-16，用户反馈）

- 新增全局 `OperationProgressProvider`（`components/ui/OperationProgress.tsx`）：`run(label, task)` 执行期间右上角常驻显示「label…」（spinner + 文本，`z-110` 位于 toast 之上，`pointer-events-none`），任务完成后自动消失；支持并发多个操作各自独立展示。
- `main.tsx` 挂载 Provider；`FileBrowser` 删除改为：确认弹窗关闭后走 `run('删除中', ...)` 异步执行，右上角持续显示"删除中…"直到删除请求完成（成功/失败后消失，随后 toast 提示结果）。
- 删除流程不再阻塞弹窗按钮，改为后台执行 + 常驻指示，符合"删除异步"诉求。

## 追加：解压"上传失败"根因修复（2026-08-16，用户反馈 + 日志定位）

### 现象

- 用户新建文件夹后解压到其中，日志显示 S3 上传成功（key=`1/{md5}`），随后 `INSERT IGNORE INTO file_object` 返回 `Updates: 0`，再查同 md5 仍为 0 条，`acquire` 返回 null，最终抛 `2003 文件上传失败`。

### 根因：去重墓碑（dedup tombstone）

- `file_object` 唯一键 `uk_tenant_md5(tenant_id, md5)` 对软删除行仍然生效；`insertIgnore` 被墓碑挡住，而 `selectByTenantAndMd5` 过滤 `status=0 AND deleted=0` 看不到它，导致"插不进去也查不到"。
- 该问题不只影响解压：任何"上传内容与历史已删除对象同 md5"的路径（含普通上传秒传复用）都会触发。

### 修复（st-core）

- `FileObjectMapper` 新增 `revive`：将软删除的同 md5 记录重置 `status=0/deleted=0`、`ref_count=0`、更新 `storage_path/size`。
- `FileObjectServiceImpl.acquire`：`insertIgnore` 返回 0 且查询不到正常行时，先 `revive` 再原子 `+1` 引用（ref_count 先置 0 再由 incrementRefCount +1，保证并发下不互相覆盖）。

### 测试

- `ArchiveServiceIntegrationTest` 新增墓碑用例：预置软删除 file_object → 解压成功，物理对象仅上传一次，记录恢复（status=0、refCount=1），节点正确引用。
- 结果：ArchiveServiceIntegrationTest 8/8、FileObjectIntegrationTest 5/5 全绿。

### 遗留备注

- 解压自动创建的文件夹 `path` 固定为 `"/"`（`createFolderNode` 历史实现），不影响列表/下载，但会使其无法被"当前文件夹搜索"按路径前缀命中；如需修正可另开一轮（创建时按父路径拼接）。

## 追加：解压产物路径修正（2026-08-16，用户确认）

- `ArchiveServiceImpl`：解压创建的文件/文件夹现在按「目标目录路径 + ZIP 内层级」拼接正确 `path`（根目录 `/`，如 `/a.txt`、`/folder/b.txt`），不再固定为 `/`。
- 实现：新增 `folderPathMap`（ZIP 内路径 → file_node path）与目标目录路径前缀；`createFolderNode`/`createFileNode` 接收 path 参数。
- 测试补充路径断言（根目录与子目录用例），`ArchiveServiceIntegrationTest` 8/8 全绿。

## 追加：解压真实进度 + 完成自动跳转（2026-08-16，用户反馈）

### 后端（st-core）

- `POST /api/file/{nodeId}/archive/extract` 改为异步任务：立即返回 `{taskId}`，后台线程执行解压（不占用 Web 请求线程）。
- 新增 `GET /api/file/{nodeId}/archive/progress/{taskId}`：返回 `{status, total, done, error, count}`；`total`=ZIP 文件条目总数，`done`=已创建文件数（即目标目录内新增文件数），每 500ms 轮询即可得到真实百分比。
- `ArchiveProgressReporter` 回调接口 + `ArchiveService.extractArchive(nodeId, targetFolderId, reporter)` 重载；预扫描同时统计文件总数。
- 进度任务为内存态（并发安全），超过 200 个任务时顺带清理已完成项。

### 前端（st-web）

- `ArchiveDialog`：解压改为「创建任务 → 每 500ms 轮询进度 → 完成/失败收尾」；进度条按 `done/total` 显示真实百分比与「N/M」，total 未知前回退不确定动画。
- 解压成功 → toast 数量 → `onExtracted(resolvedId)` → **自动跳转目标目录**；`FileBrowser` 的跳转逻辑加固：即使节点查询失败也按目录 id 直接跳转（不再回退成"刷新当前列表"）。
- 失败 → 展示后端错误信息，弹窗保持打开可重试。

### 验证

- `ArchiveServiceIntegrationTest` 9/9（新增进度回调断言：total=2、done=2、count=2）；前端 lint 0 错误、build 通过。
- 注意：后端接口契约已变更，需重新构建并重启后端 + 重新构建前端后生效。

## 追加：修复异步解压"无权限"（2026-08-16，用户反馈）

- 现象：解压异步化后提示「无权限」（403）。
- 根因：解压迁移到后台线程池执行，而 `UserContext`/`TenantContext` 是请求线程 ThreadLocal；后台线程取不到用户，`getAccessibleFileNode` 的 `userId=null` 权限校验直接抛 FORBIDDEN，MyBatis 租户过滤也会退化。
- 修复：`ArchiveController` 在提交任务前捕获 `UserContext.CurrentUser` + `TenantContext`（tenantId/tenantMode），工作线程内恢复，finally 中清理。
- 验证：st-core 编译通过；解压服务逻辑无改动（上下文恢复后权限/租户路径与同步执行一致）。

## 追加：删除文件夹不再逐子孙更新（2026-08-16，用户反馈）

### 现状澄清

- 数据库层面 2026-08-07 起已是「只改变被删文件夹自身状态，子孙 status 不动」（`FileServiceFlowIntegrationTest` 有对应断言）。
- 你看到的"所有子文件都在执行更新"，是删除时逐子孙写入的 **Outbox 事件（`event_log` INSERT ×2/子孙：ES 索引 + 同步日志）**。

### 改动

- `FileServiceImpl.deleteToRecycleBin` / `deleteTeamFiles`：删除文件夹只发布文件夹自身 1 条索引 + 1 条同步事件；**不再逐子孙发布事件**。子孙仅做内存级可访问性缓存失效（无 DB 写入）。
- 正确性兜底：
  - 同步：桌面端对文件夹 DELETE 事件执行 `fs.rmSync(recursive)` 递归删除本地子树，逐子孙事件冗余；
  - 搜索：ES 索引中子孙文档不再逐个删，改为 `SearchServiceImpl.searchContent` 查询时用 `findIdsWithInaccessibleAncestor` 批量按祖先链过滤（已删除 / 回收子树中的命中不下发）；
  - 永久删除仍逐节点清理 ES 索引（回收期残留的子孙文档在硬删除时清除）。

### 测试

- `FileServiceFlowIntegrationTest` 新增断言：回收文件夹只产生自身 2 条事件（索引+同步），子孙 0 条；
- `SearchServiceImplTest` 新增用例：回收祖先链下的命中被过滤、DB 已不存在的命中被过滤；
- 结果：EventOutbox 6/6、FileServiceFlow 6/6、FileServicePermission 3/3、SearchServiceImplTest 全绿。

## 追加：事件日志完全异步化（2026-08-16，用户反馈）

### 设计澄清（符合"业务只发消息、MQ 消费者异步处理"）

- 业务事务内只写 2 行 `event_log`（Outbox，可靠性锚点，防消息丢失），不做任何 ES/同步日志写入。
- 事务提交后 `EventRelay` 把 MQ 发送交给后台线程（daemon），立即返回，broker 慢/不可用不再阻塞用户请求。
- ES 索引、`sync_change_log` 全部在 RocketMQ 消费者（或未配置 MQ 时的 @Async 本地监听器）里异步执行。
- 崩溃时未发送的行保持 PENDING，由 `EventRetryTask` 每 60s 扫描兜底（新增：覆盖 PENDING 超时 5 分钟的行，发送前崩溃不丢事件）。

### 改动

- `EventRelay`：`relay()` 提交后仅入队，`doRelay()` 后台执行 selectById→syncSend→markSent/markFailed。
- `EventLogMapper.selectRetryable`：增加 `stuckBefore`，同时选中 status=2 失败行与 status=0 超时 PENDING 行。
- `EventRetryTask`：传入 5 分钟 PENDING 卡死阈值。

### 测试

- `relay_sendsAfterCommit` 改为验证异步投递（轮询等待 status=1，`NOT_SUPPORTED` 挂起外层测试事务以读到真实已提交状态）。
- 全部相关套件绿：EventOutbox 6/6、FileServiceFlow 6/6、FileServicePermission 3/3、Archive 9/9、SearchContent 10/10。

## 追加：F5 刷新行为修正（2026-08-16，用户反馈）

### 原因

- Web 端 F5 走浏览器原生整页重载（原代码只在 Electron 拦截）；重载后应用回到当前路由，侧边栏「全部文件」高亮，表现为"没刷新列表、只选中了 tab"。
- Electron 桌面端存在窗口级 F5 整页重载的可能，同样丢状态。

### 修复

- 桌面端主进程 `before-input-event` 拦截 F5（禁止窗口重载），改发 `app:refresh-file-list` IPC。
- `preload.ts` 暴露 `onRefreshFileList`，`st-desktop/src/types.ts` 与 `st-web/src/types/index.ts` 同步接口。
- `FileBrowser` 监听 IPC → 原地 `refresh()`（重新拉取当前目录，保留滚动/状态）。
- `useFileKeyboard` F5 改为所有环境统一 `preventDefault + refresh()`：Web 端 F5 也原地刷新，不再整页重载。

### 验证

- Web：lint 0 错误、build 通过。
- 桌面端：`build:main`（tsup）与 `tsc --noEmit` 通过（完整 electron-builder 打包需本机 NSIS/签名工具，属环境限制）。

### 方向修正（用户确认：F5 要"刷新页面"效果，不是原地刷列表）

- 桌面端主进程 F5 → 显式 `webContents.reload()` 整页刷新（保留当前路由，与浏览器 F5 一致）。
- Web 端移除 useFileKeyboard 的 F5 拦截，恢复浏览器原生刷新。
- 撤掉上一版加入的 `onRefreshFileList` IPC 管道（preload/types/FileBrowser 监听全部移除）。

### 方向再修正（用户最终确认：PC 端 F5 只刷新文件列表，不整页重载）

- 桌面端主进程 F5 → `event.preventDefault()` + `webContents.send('app:refresh-file-list')`，不重载窗口。
- preload 暴露 `onRefreshFileList`，两侧类型同步；FileBrowser 监听 IPC 后调用 `refresh()` 原地重新拉取当前目录（保留滚动/状态）。
- Web 端维持浏览器原生 F5（不拦截）。

## 追加：F5 刷新特效（Windows 风格，2026-08-16，用户反馈）

- `FileBrowser` 新增 `isRefreshing`：刷新期间工具栏刷新按钮的 `RefreshCw` 图标旋转（`animate-spin`）并禁用，类似 Windows 地址栏刷新动画。
- 列表用 `key={refreshKey}` 包裹并加 `animate-file-enter` 淡入动画：F5 / 工具栏刷新 / 上传完成后列表淡入重绘，产生"刷新特效"。
- 数据仍为原地重拉，不整页重载、保留滚动位置。
- Web lint 0 错误、build 通过。

## 追加：打开文件夹不再整页重挂载（Windows 风格，2026-08-16，用户反馈）

### 根因

- 打开文件夹走路由导航（`/files/:parentId`），AppLayout 按 `location.pathname` 加 key 导致整页重挂载 + 路由淡入动画；
- FileManager 又给 FileBrowser 加 `key={parentId}` 二次重挂载 → 工具栏/面包屑/路径全部重绘闪烁。

### 修复

- AppLayout 去掉 `key={location.pathname}` 与路由淡入动画：路由切换不再整页重挂载。
- FileManager 去掉左右面板的 `key={parentId}`：FileBrowser 原地切换目录（parentId 变化 → 原地拉取列表、更新路径），只有文件列表数据更新，工具栏/面包屑/路径保持稳定、路径文本直接替换。
- 保留既有逻辑：切换目录时旧列表不清空、新数据到达后平滑替换（不闪骨架屏），并按目录保留滚动位置。
- Web lint 0 错误、build 通过。

## 追加：修复"返回首页/根目录"闪烁（2026-08-16，用户反馈）

### 根因

- `/files`（index）与 `/files/:parentId` 是两个独立路由条目；点面包屑"首页"跳到 `/files/0` 时在两条目间切换，FileManager/FileBrowser 重新挂载 → 闪烁，与打开文件夹的原地效果不一致。

### 修复

- App 路由合并为单一条目 `files/:parentId?`（parentId 可选）：`/files`、`/files/0`、`/files/:id` 全部命中同一路由，互相切换不再重挂载。
- FileManager 根目录导航统一走 `/files`（规范 URL）。
- 现在打开文件夹、面包屑切目录、返回根目录/首页均为原地更新，无闪烁。

## 追加：打开文件夹流畅度优化（2026-08-16，用户反馈）

- **路径秒换**：路由 state 携带目标节点 id + path，FileBrowser 打开文件夹时面包屑立即更新，不再等待一次额外的 `getNodeById` 请求（慢半拍的来源）。
- **列表立即反馈**：切换文件夹瞬间进入骨架屏，不再让旧文件夹内容停留到新数据到达才突然替换（"点了没反应"的笨重感来源）；新数据到达后淡入显示（列表容器 key 加入 parentId）。
- 兼容：直接刷新/手动输入 URL 无 state 时仍回退 `getNodeById` 解析路径；前进/后退同样按历史 state 即时更新。
- Web lint 0 错误、build 通过。

## 追加：移除文件列表双面板功能（2026-08-16，用户要求）

- `FileManager.tsx` 删除双面板切换按钮、右侧面板与相关状态，恢复为单面板文件浏览；移除 `Columns2/Square` 图标与 `useState` 导入。

## 追加：上传任务 icon 支持拖拽移动（2026-08-16，用户要求）

- 右下角上传任务 icon 改为可拖拽：Pointer 事件 + `setPointerCapture` 实现，`touch-action: none` 支持触屏；拖拽 4px 以上视为移动（不触发展开），位置钳制在视口内并持久化到 `localStorage('uploadFabPos')`，刷新后保持。
- 展开的上传队列面板仍固定在右下角原位置（`fixed bottom-6 right-6`），避免遮挡问题由用户自行挪动 icon 解决。

## 追加：文件夹每页条数可选 + 分页 UI 优化（2026-08-16，用户要求）

- `FileBrowser` 新增每页条数：50 / 100 / 150 / 200，默认 100，选择持久化到 `localStorage('filePageSize')`；切换每页条数回到第 1 页。
- 分页底栏重做：左侧「共 N 项」；右侧「每页 [选择器] 项 · 第 x / y 页 · 上一页/下一页」，统一按钮样式（边框/悬停/焦点环/tabular-nums）。
- 顺带修复：切换文件夹时页码重置为 1（此前停留在旧目录页码可能越界空列表）。

## 追加：默认主题对齐 DESIGN.md（暗色优先 + 紫罗兰，2026-08-16，用户反馈"页面白茫茫"）

- 根因：`.ulpi/design/DESIGN.md`（V4）锁定"暗色优先 + 紫罗兰主色"，但实现默认是浅色 + 蓝色，浅色白底白卡片导致整体"白茫茫"。
- 改动：
  - `themes.ts`：`DEFAULT_THEME` blue → violet（紫罗兰，与 DESIGN.md 主色一致）；
  - `store/theme.ts`：默认外观模式 light → dark；
  - `index.html` 首屏防闪烁脚本默认模式同步为 dark。
- 已保存过 `themeMode` 的用户需在设置里切到深色（或清除 localStorage 后重进）。

## 追加：浅色模式层次优化（2026-08-16，用户确认"列表白底不动，周边区域改灰"）

- 浅色 `--bg` 由 #FAFAF9 加深为 #F2F2F1，页面底不再是"白上加白"。
- `FileBreadcrumb` 由白色改为页面灰（bg-bg），工具栏保持灰底。
- 文件列表区域（FileBrowser 根容器）保持白色不变：仅工具栏/面包屑/页面底为灰色，形成"灰色 chrome + 白色列表面板"层次。

### 方向修正（用户否定灰色方案，改为"白色 + 清晰区域划分"）

- 还原灰色改动：`--bg` 回到 #FAFAF9（白），面包屑回白色。
- 新方案：文件列表（三种视图）统一包一层"白色卡片"——圆角 + 边框 + 阴影（`shadow-card`），与白色工具栏/面包屑（底部细线分隔）形成明显区域划分；去掉了各视图原有的内边框避免双层。
- 全站保持白色，无灰色。

### 方向再修正（用户要求"文件列表不要圈起来"）

- 移除列表卡片包裹层（圆角/边框/阴影），列表为纯白内容区，仅靠工具栏/面包屑的底部细分隔线与行分隔线区分区域。

## 追加：全部文件顶部区域对齐传输管理（2026-08-16，用户要求）

- 参照 TransferManager：页面底用 `bg-surface-2/50` 极浅底色，顶部两块白色条（工具栏、面包屑）带细分隔线，白色条在浅底上清晰分层。
- FileBrowser 根容器底色改为 `bg-surface-2/50`；工具栏由页面色改为纯白条（`bg-surface`）；面包屑保持白色条。
- 文件列表保持白色：表格/列表视图白底原有，网格视图补齐 `bg-surface`，无边框无阴影（不圈起来）。

### 修正（用户要求"文件列表不要动"）

- 还原 FileGrid 组件的改动（列表组件零修改）；网格视图白底改由 FileBrowser 外层滚动容器按视图条件补齐，列表视觉不变。
- 仅保留顶部区域区分：页面底极浅色（`bg-surface-2/50`，同传输管理）+ 工具栏/面包屑纯白条 + 分隔线。

## 追加：区域分割线加深（2026-08-16，用户要求）

- 新增 `--border-strong` 分割线色值（浅色 stone-300 #D6D3D1；深色模式提亮为 #3E3E46）。
- 顶栏、工具栏、面包屑、分页栏的区域分割线改用 `border-strong`，区域边界更清晰。

### 撤回（用户要求）

- 移除 `--border-strong`，顶栏/工具栏/面包屑/分页栏分割线恢复原 `border-border`（工具栏恢复 `border-border/60`）；顶部浅底+白条结构保留。

## 追加：分页支持输入页码跳转（2026-08-16，用户要求）

- 分页栏"第 [输入框] / N 页"：直接输入页数，回车或失焦跳转；自动钳制在 1..总页数，非法输入回退当前页。
- 页码输入框与当前页同步（翻页/换文件夹/改每页条数后保持一致），仅数字输入。

## 追加：点击列表空白处取消输入框聚焦（2026-08-16，用户反馈）

- 根因：文件列表空白处 `mousedown` 的 `preventDefault()`（拖拽框选防文本选中）同时阻止了输入框失焦。
- 修复：点击空白时先手动 blur 当前聚焦的 INPUT/TEXTAREA（页码输入、路径编辑等），随后照常执行框选与清空选中。

## 追加：桌面传输悬浮小窗（百度网盘式，2026-08-16，用户要求）

- 独立于主窗口的 Electron 小窗（无边框/透明/置顶/可拖动/记忆位置）：`mini-window.ts` + `st-web/public/mini-transfer.html`。
- 数据自动经 `task:update` 全窗口广播接收；小窗展示上传/下载进度、速度、暂停/继续，点击回主界面，`/transfers` 页提供"悬浮窗"按钮重新显示。
- 自定义右键菜单：打开网盘 / 简易速度限制配置（上传/下载 KB/s，0=不限，保存即生效）/ 退出整个程序；新增 IPC `transfer:getSettings`、`app:quit`、`mini:setSize/moveBy/openMain/show/hide`。
- 修复：透明窗口改用手动拖拽（`mini:moveBy`）；菜单/限速面板打开时窗口自动撑高防裁切；开发模式加载失败自动重试 + 清缓存。

## 追加：悬浮窗黑块修复（2026-08-16，用户反馈"一团黑"）

- 根因：Windows 透明窗口未显式设置全透明背景且开启阴影，部分显卡上渲染成黑色方块。
- 修复：`backgroundColor: '#00000000'` + `hasShadow: false`（页面自带阴影），并打印 `[mini] 页面加载完成` 日志确认加载。

## 追加：悬浮窗重写为百度网盘式悬浮球（2026-08-16，用户反馈"根本不是百度网盘的 UI"）

- 重写 `st-web/public/mini-transfer.html`：收起态为 56px 圆形悬浮球（紫色渐变 + 云朵图标 + 进度环 + 红/绿任务数角标），底部一红一绿显示上传/下载实时速度（对齐百度网盘悬浮窗视觉）。
- 点击悬浮球展开为传输列表面板：文件名、蓝色进度条、实时百分比与速度、暂停/继续/删除；暂停为灰色进度条并标注"已暂停"（对齐百度网盘）。
- 悬浮球与面板头部均可拖拽移动整个悬浮窗（指针事件 + `mini:moveBy`），位置持久化保持不变。
- 自定义右键菜单（打开网盘 / 简易速度限制配置 / 退出整个程序）完全保留，限速配置面板含上传/下载 KB/s 输入（0=不限速）+ 保存/取消。
- 修复"限速配置显示不全"：打开限速面板/右键菜单前先撑高窗口再切换状态，窗口按面板完整高度（272×280）显示，杜绝被裁切；展开态固定 310×430 由列表内部滚动，避免每次任务更新导致窗口高度抖动。
- 验证：`npm run build`（st-web，dist/mini-transfer.html 已产出）、`npm run lint`、`npm run build:main`（st-desktop）全部通过；页面内联 JS `node --check` 通过。

## 追加：悬浮窗右键菜单增加任务开始/暂停（2026-08-16，用户要求）

- 右键菜单新增"任务开始/暂停"项（打开网盘下方）：有进行中任务时显示"暂停全部任务"（暂停图标），否则显示"开始全部任务"（播放图标，仅在有已暂停任务时可点），无任务时置灰。
- 新增主进程 IPC `transfer:pauseAll` / `transfer:resumeAll`（ipc-handlers.ts），遍历全部任务分别调用 upload/download 的暂停/恢复，并沿用 task-scheduler 的并行槽位控制；preload、st-desktop/types.ts、st-web/types/index.ts 同步声明 `pauseAllTransfers` / `resumeAllTransfers`。
- 菜单高度随第四项调整为 172px，窗口撑高逻辑同步更新。
- 验证：st-desktop `npm run lint` + `npm run build:main`、st-web `npm run build`、页面内联 JS `node --check` 全部通过。

## 追加：悬浮窗"拖飞丢失"修复（2026-08-16，用户反馈"拖了一下就飞走了找不到"）

- 根因一：拖拽时窗口可被移动到所有显示器可见区之外，且旧的位置被持久化，重启也回不来。
- 根因二：拖拽监听挂在 window 上，小窗尺寸小、鼠标快速移出窗口后事件流中断，窗口停在半路/甩出屏幕。
- 修复：
  - `moveMiniWindowBy` 增加钳制：目标位置必须落在鼠标所在显示器工作区内（取 `getCursorScreenPoint` + `getDisplayNearestPoint`），拖拽不可能把窗口拖出屏幕。
  - `mini-window.ts` 增加 800ms 可见性守卫：窗口与所有显示器工作区零交集时自动复位到主屏右下角；创建窗口时同样按当前窗口尺寸校验已保存位置。
  - 渲染层拖拽改为 `setPointerCapture`，指针移出窗口后事件仍持续送达，拖拽不再中途丢失。
  - 展开面板头部新增"复位位置"按钮（`mini:reset` IPC + `resetMiniWindowPosition`）；主界面传输管理"悬浮窗"按钮现在同时复位位置（找回）。
  - `resizeMiniWindow` 最小宽度从 200 降为 56，允许收起态收缩为 80×86 悬浮球。
- 验证：st-desktop lint + build:main、st-web build、页面 JS 语法检查全部通过。

## 追加：悬浮窗列表简化为"进行中任务"（2026-08-16，用户要求"做个简单的，展开能看到当前进行中的任务"）

- 悬浮窗展开面板只渲染进行中（上传/下载/等待/计算/合并）与已暂停的任务，已完成/失败/取消的不占列表；空态文案改为"暂无进行中的任务"。任务按钮委托按过滤后的列表取任务，修复了索引错位。
- 展开尺寸改为紧凑固定值：窗口 366×366（面板 300×280 + 球列 56 宽 + 间隙），列表内部滚动，不再随任务数自动变大。
- 新增 `mini:openPanel` IPC：主进程按球当前位置与屏幕边界决定面板从球的左/右/上/下哪个方向展开（右侧/下方空间不足时自动换向），并返回布局参数（球/间隙/面板/窗口尺寸），渲染层写入 CSS 变量精确定位，窗口由球列+面板铺满、无透明空气区。
- 展开时球列保留在角落（panel-left/panel-top 时自动换边），球仍可拖拽移动整个悬浮窗；速度条在展开态隐藏（CSS !important 防止内联样式覆盖）。
- 验证：st-desktop lint + build:main、st-web build（dist 与 public 哈希一致）、页面 JS 语法检查全部通过。

## 追加：悬浮窗改为百度网盘式长方形速度块（2026-08-16，用户要求）

- 删除悬浮窗的传输列表面板与展开逻辑（含 `mini:openPanel` IPC、主进程 `openMiniWindowPanel`、面板/任务列表 HTML），消除一切透明"空气墙"：悬浮窗固定为长方形块（250×70），铺满窗口，无透明拦截区。
- 块内展示：云朵图标 + "星云盘"标题 + 上传/下载总速度（红↑/绿↓，百度网盘配色）+ 进行中任务数 + 底部蓝色总进度条（活跃任务平均进度，带百分比，百度网盘样式）。
- 交互：点击块打开主界面，拖拽移动（主进程轮询方案保留），右键菜单保留（打开网盘 / 任务开始暂停 / 简易速度限制配置 / 退出整个程序）；菜单与限速面板打开时窗口先撑高再显示，关闭后恢复块尺寸。
- 清理：移除 `openMiniWindowPanel` 相关 IPC/preload/类型声明及展开布局 CSS 变量。
- 验证：st-desktop lint、st-web build（dist 与 public 哈希一致）、页面 JS 语法检查全部通过。

## 追加：悬浮窗固定尺寸重构——右键菜单改为独立小窗（2026-08-16，用户反馈"长按会自动变大"）

- 彻底解决尺寸乱变：悬浮窗窗口固定 250×70（resizable:false），渲染层删除全部 `setMiniWindowSize` 调用，悬浮块永远铺满窗口，尺寸永不变化。长按/拖拽只移动位置，不再触发任何 resize。
- 右键菜单改为独立菜单小窗（新增 `st-desktop/src/menu-window.ts` + `st-web/public/mini-menu.html`，固定 210×190，无边框/透明/置顶/失焦自动隐藏），在悬浮块旁边弹出并自动避开屏幕边缘；不再撑大悬浮窗。
- 菜单小窗内两视图：右键菜单（打开网盘 / 任务开始暂停 / 简易速度限制配置 / 退出整个程序）与限速设置（上传/下载 KB/s + 保存/取消，返回按钮切回菜单）。
- 新增 IPC `mini:showMenu` / `mini:hideMenu`；移除 `mini:setSize` 及 preload/类型中的 `setMiniWindowSize`。
- `openMainWindow` 通过 `isMenuWindow` 排除菜单小窗，避免"打开网盘"误选中菜单窗。
- 验证：st-desktop lint + build:main、st-web build（mini-transfer.html/mini-menu.html 均已入 dist 且哈希一致）、两个页面 JS 语法检查全部通过。

## 追加：悬浮窗拖拽手感修复（2026-08-16，用户反馈"拖拽不被鼠标抓住、一拖就飞到屏幕边缘"）

- 根因一：旧拖拽由渲染层每次 pointermove 上报位移，IPC 有延迟，且鼠标快速移出小窗后事件流中断，窗口跟不上鼠标。
- 根因二：钳制参考用了"鼠标当前所在显示器"，鼠标一靠近屏幕边缘窗口就被吸过去，表现为"飞到屏幕边缘"。
- 修复：拖拽改为主进程驱动——`mini:startDrag` 按下时记录鼠标相对窗口偏移，主进程每 16ms 用 `getCursorScreenPoint` 轮询鼠标位置移动窗口（窗口跟手、鼠标移出小窗不断流），`mini:endDrag` 松开时停止并持久化；钳制改为把窗口主体限制在鼠标所在显示器工作区，既不会拖丢也不会吸附边缘。
- 渲染层 `startDrag/endDrag` + 指针捕获保证 pointerup 可靠送达；球拖拽用 `ballDragged` 标志区分点击/拖拽，拖拽后不会误触发展开。
- 旧的 `mini:moveBy` IPC 移除，`moveMiniWindowBy` 保留为空兼容桩。
- 验证：st-desktop lint + build:main、st-web build（dist 与 public 页面哈希一致）、页面 JS 语法检查全部通过。

## 追加：悬浮窗展开态拖拽修复（2026-08-16，用户反馈"展开后空气列表挡住小球无法拖拽、拖拽不自然"）

- 根因一：展开时小窗被撑成 310×430 矩形，面板铺满全窗、球被隐藏，球附近一大片区域是"空气列表"（透明但拦截鼠标），用户抓球实际抓到的是窗口大片透明区。
- 根因二：拖拽偏移按"窗口左上角"计算，展开后窗口很大，鼠标位置相对窗口左上角偏移巨大，拖拽起点跳变、不自然。
- 修复：
  - 展开后面板改为精确定位在球右侧（`left:68px; right:12px; top:4px; bottom:12px`），窗口其余区域透明不拦截鼠标；球保持可见可拖，不存在空气列表挡球。
  - 拖拽改为上报"被抓住元素左上角相对窗口的坐标"（`mini:startDrag(handleX, handleY)`），主进程按鼠标与该元素的偏移移动窗口——抓住哪就在哪，展开/收起状态拖拽都不跳变。
- 验证：st-desktop lint + build:main、st-web build、页面 JS 语法检查全部通过。
