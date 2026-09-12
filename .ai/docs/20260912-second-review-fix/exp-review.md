# 第二轮 Review 修复体验约束审查

## 审查范围与结论

基线为 `11ef6c2836af548c52052e5860511242741d9d1d`。依据本轮工作区 diff、`uispec.md` 和 `testreport.md` 做静态验收：本轮没有修改 CSS、视觉 token、布局、上传面板组件或 Desktop renderer；Web 上传入口只把哈希调用改为完整文件 MD5，Desktop 上传和同步路径也只调整哈希调用。既有失败展示链路仍在，体验约束**有边界地通过**。未进行浏览器/安装包端到端操作，不据此宣称真实交互已全部验证。

## 逐项证据

| 约束 | 证据与判定 |
|---|---|
| 视觉、布局、组件和交互样式不变 | `git diff --name-only -- '*.css' '*.scss' '*.tsx' '*.vue' '*.svg' 'st-desktop/src/renderer/**'` 仅列出 `st-web/src/hooks/useUpload.tsx`；该 diff 只有导入和调用完整 MD5 的变化，没有 JSX/样式变化。新增 `st-web/src/lib/file-md5.ts` 仅实现分块计算。通过。 |
| 上传错误沿用现有展示 | `st-web/src/lib/api.ts` 将业务失败转为含服务端消息的 `ApiError`；`useUpload.tsx` 的 catch 把消息写入失败任务；`UploadPanel.tsx` 继续在失败行展示 `task.error`。Desktop `upload-manager.ts` 把失败写入既有任务 `error` 字段，`useUpload.tsx` 的 IPC 更新继续显示该字段。静态链路通过。 |
| merge 与 abort 冲突 | 服务端新增 `CONFLICT` 业务错误，现有 Web API 拒绝链路会把其消息交给上传任务失败展示；没有新增 UI。仅静态确认，未手工复现竞争场景。 |
| Archive 输入超限 | 服务端使用既有 `FILE_TOO_LARGE` 码及“ZIP 压缩包输入大小超过限制”消息。`ArchiveDialog.tsx` 解压提交/轮询失败路径使用错误消息的既有 toast；但浏览内容的 `contents` 请求在 `ArchiveDialog.tsx:70-72` 对所有失败固定提示“读取压缩包失败”，因此该入口不能展示具体超限原因。属于既有展示限制，未按本轮禁止改 UI 的范围调整。 |
| simpleUpload 文案 | 服务端阈值为 100MB，错误文案已统一为“简单上传限制100MB以内，请使用分片上传”；前端仍用既有错误机制展示。通过。 |

## 构建证据与边界

`testreport.md` 记录 Phase 7：`st-web: npm run build`、`st-desktop: npm run lint`（`tsc --noEmit`）、`st-desktop: npm run build:main` 均退出码 0。完整 `st-desktop: npm run build` 在 `electron-builder` 写系统 Electron ZIP 缓存时因 `Access is denied` 失败，未产出安装包；这是打包验收缺口，不能由主进程构建代替。此次体验审查没有重跑构建。

## 风险与判定建议

- 浏览 ZIP 内容时超限只得到通用失败提示；在允许 UI 范围变更后可单独处理，本轮不扩大范围。
- 完整 MD5 会延长大文件开始上传前的 hashing 阶段，当前构建与静态检查不提供耗时或响应性实测数据。
- Desktop 安装包未构建成功；主线程应将其作为 Phase 7 门禁未通过项，不据此宣称桌面成品体验验收完成。

本审查仅提议 `EXP_ACCEPT` 对**未变更 UI 与既有展示链路**通过；整轮最终 `ACCEPT` 由主线程综合测试门禁决定。
