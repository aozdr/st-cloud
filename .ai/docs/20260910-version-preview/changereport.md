# Change Report：历史版本预览

> Task: `TASK-20260910-version-preview-01`｜执行：executor（taskType=implement）｜日期：2026-09-10

## 一、修改文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `st-preview/src/main/java/com/stcloud/preview/controller/PreviewController.java` | 修改 | 新增 `GET /api/preview/{nodeId}/version/{versionId}` |
| `st-preview/src/main/java/com/stcloud/preview/service/PreviewService.java` | 修改 | 新增 `previewVersion(nodeId, versionId)` 声明 |
| `st-preview/src/main/java/com/stcloud/preview/service/impl/PreviewServiceImpl.java` | 修改 | 版本预览实现；抽出 `dispatchByStorage` 复用分派；缩略图/文本方法改为按对象路径入参 |
| `st-preview/src/test/resources/schema.sql` | 修改 | 测试用 H2 补 `file_version` 表（对齐 02/36 号脚本） |
| `st-preview/src/test/java/com/stcloud/preview/service/PreviewVersionIntegrationTest.java` | 新增 | 9 个集成用例（TC-01 ~ TC-09） |
| `st-web/src/hooks/useFileDialogs.ts` | 修改 | 新增 `PreviewTarget` 类型，preview 状态携带 versionId/versionNum |
| `st-web/src/components/file/VersionHistoryDialog.tsx` | 修改 | 非当前版本行新增「预览」按钮，新增 `onPreview` prop |
| `st-web/src/components/file/FileBrowserDialogs.tsx` | 修改 | 预览状态透传版本信息；版本弹窗 → 预览浮层接线 |
| `st-web/src/components/preview/PreviewModal.tsx` | 修改 | 版本预览模式：版本接口取数、禁切换/幻灯片/下载、跳过编辑器跳转、PDF iframe、层级 z-60 |

无数据库/迁移脚本变更。

## 二、与验收标准对照

| 验收标准 | 结果 | 证据 |
|---------|------|------|
| 新端点返回指定版本预览 | 通过 | TC-01/02/03 覆盖文本、视频、图片 |
| 版本归属 + 节点可访问双重校验 | 通过 | TC-04/05/06 |
| 图片版本缩略图使用版本级 key | 通过 | TC-03 断言 key = `thumbnails/{nodeId}/v2/lg.jpg` |
| Office 及其它类型返回 unsupported | 通过 | TC-07 |
| 版本弹窗非当前版本有预览入口 | 通过 | `VersionHistoryDialog` 仅 `!isCurrent` 渲染按钮 |
| 版本模式禁用切换/幻灯片/下载、不写最近文件、不跳 OnlyOffice | 通过 | `PreviewModal` 版本模式短路，`npm run build` 通过 |
| 现有当前版本预览行为不变 | 通过 | TC-08 + 原 `PreviewServiceIntegrationTest` 5 用例全绿 |

## 三、测试结果

| 命令 | 结果 |
|------|------|
| `mvn -pl st-preview -am test` | 通过：st-common 31 + st-core 149 + st-preview 14 = 194 用例，0 失败 |
| `\\ st-web 下 npm run build` | 通过：tsc -b + vite build，63 个产物 |
| `mvn -q -DskipTests compile`（全仓） | 通过 |

## 四、风险与遗留

- 浏览器点击级验证（M-01 ~ M-04）需运行完整环境（MySQL/Redis/S3/后端/前端）后人工确认，本次未执行。
- Office（doc/docx/xls/xlsx/ppt/pptx）历史版本按设计提示「该类型历史版本暂不支持在线预览」，需恢复后才能查看内容。
- 若历史版本物理对象已被清理，预览返回「读取文件内容失败」错误，不返回空白预览（对象清理逻辑现有实现为保守保留）。

## 五、追加变更（2026-09-10，用户反馈后）

| 文件 | 类型 | 说明 |
|------|------|------|
| `st-web/src/components/file/VersionHistoryDialog.tsx` | 修改 | 修复「上传新版本」无效：Web 分支不再在选择文件前 `toast + onClose`（会卸载隐藏 input 导致选择结果丢失），改由 `handleFileSelect` 在选中后入队并关闭 |
| `st-web/src/components/file/ContextMenu.tsx` | 修改 | 新增 `showNewVersion` 开关与「上传新版本」菜单项（文件 + 有 `file:upload` 权限时显示） |
| `st-web/src/components/file/FileBrowserDialogs.tsx` | 修改 | 向右键菜单透传 `showNewVersion={enableVersions}` |
| `st-web/src/hooks/useFileBrowser.ts` | 修改 | 新增 `newVersionInputRef` / `handleNewVersionUpload` / `handleNewVersionChange` 与 `newVersion` 动作处理 |
| `st-web/src/components/file/FileBrowser.tsx` | 修改 | 挂载「上传新版本」专用隐藏 file input，并接线到 hook |

验证：`npm run build` 通过（tsc + vite）。后端 `replaceFileId` 覆盖上传链路（`UploadServiceImpl.initChunkedUpload` → `UploadCommitManager.finalizeMerge` → `snapshotCurrentVersion`）经只读核对确认支持版本快照，无需改动。

文档漂移清理（同批）：block-sync design 契约同步、mobile-pwa design 补 android 现状、share-expiry 补 README、favorites README 归档、PRD 历史章节补现状说明。

## 六、追加变更 2（2026-09-10，Office 历史版本只读预览）

用户实测：Office 文件覆盖上传后，历史版本预览提示「该类型历史版本暂不支持在线预览」。据此把 P1 改判为"Office 历史版本也要能在线预览"，走 OnlyOffice 只读打开。

| 文件 | 类型 | 说明 |
|------|------|------|
| `st-common/src/main/java/com/stcloud/common/utils/JwtUtils.java` | 修改 | `generateEditorToken` 增加可选 `versionId` 重载（原 7 参方法委托，保持兼容） |
| `st-core/src/main/java/com/stcloud/core/editor/EditorConfigService.java` | 修改 | 新增 `generateVersionConfig(nodeId, versionId)` |
| `st-core/src/main/java/com/stcloud/core/editor/EditorConfigServiceImpl.java` | 修改 | 版本只读配置：key=`{nodeId}_v{versionId}`、edit=false、mode=view、无 callbackUrl、不登记编辑锁、令牌带 versionId |
| `st-core/src/main/java/com/stcloud/core/editor/EditorController.java` | 修改 | `/editor/config` 增加可选 `versionId` 参数（不传行为不变） |
| `st-core/src/main/java/com/stcloud/core/controller/FileController.java` | 修改 | `/stream` 从令牌读取 `versionId` 声明并透传给下载服务 |
| `st-core/src/main/java/com/stcloud/core/service/DownloadService.java` | 修改 | 新增 `streamFile(nodeId, versionId, req, resp)` |
| `st-core/src/main/java/com/stcloud/core/service/impl/DownloadServiceImpl.java` | 修改 | 版本流按 `file_version` 取 storagePath/大小，Range 与限速逻辑复用 |
| `st-core/src/test/java/com/stcloud/core/editor/EditorConfigServiceImplTest.java` | 修改 | 新增 2 例：版本配置只读且无 callbackUrl、版本不属于该节点抛错 |
| `st-web/src/lib/editor.ts` | 修改 | `getEditorConfig` 支持 `versionId` |
| `st-web/src/pages/EditorPage.tsx` | 修改 | 读取 `versionId` 并透传；版本预览视为只读，不登记/释放编辑位 |
| `st-web/src/components/preview/PreviewModal.tsx` | 修改 | 版本模式下 Office（docx/xlsx/pptx）跳转 OnlyOffice 只读页；PDF 仍用内置查看器 |

验证：`mvn -pl st-core,st-preview -am test` 通过（st-common 31 + st-core 151 + st-preview 14，共 196 用例）；`npm run build` 通过。
未验证：OnlyOffice docservice 运行期行为（本机未启动文档服务），需在运行环境实测；风险见 design.md 第十二章。
