# W4 文件操作对话框 Code Review

> 审查人：主线程 reviewer（ox-alpha）/ 日期：2026-08-22 / 方式：静态人工审查（ContextMenu.tsx 全文；上传链路结论承接 W2 的 useUpload 精读；Archive/BatchRename/Convert/VersionHistory 等对话框未逐行深读）
> 范围：`components/file/` 操作类：ContextMenu、BlankContextMenu、各类 Dialog、UploadPanel、FileDetailPanel

## 问题清单

| 编号 | 严重度 | 位置 | 问题 | 证据(代码片段) | 建议 |
|---|---|---|---|---|---|
| W4-1 | P2(待确认) | hooks/useUpload.tsx:325-331（UI 承接于 UploadPanel） | Web 在途上传任务无取消能力暴露面：useUpload 仅提供 removeTask/clearCompleted（移除记录，不中止传输）；Electron 任务才有 cancelUpload IPC。Web 大文件误传无法中断 | context 未暴露 abort 句柄 | addFiles 返回句柄并在 UploadPanel 提供取消按钮（AbortController + 服务端 abort） |
| W4-2 | P2 | components/file/ContextMenu.tsx:87-88,100 | 「收藏/隐藏/详情」三项不受权限点控制（个人操作可接受，但团队空间下 hide/favorite 语义需后端确认归属） | `{ action: 'hide', label: '隐藏' }` | 团队空间按节点权限隐藏相应菜单项 |
| W4-3 | P2 | BatchRename/Archive/VersionHistory（未深读） | 长任务对话框（解压/转码/批量重命名）的轮询清理与失败恢复未纳入本次逐行审查，存在遗漏风险 | — | 补一轮专项复查 |

## 亮点

- ContextMenu 权限门控是全项目最完整的样本：预览/剪切/复制/粘贴/下载/重命名/移动/分享/删除逐项绑定 file:* 权限码，分隔符自动折叠逻辑干净
- 菜单定位含翻转与边界钳制（useLayoutEffect），Portal 渲染避免裁剪
- 批量操作经 useOperationProgress 统一进度反馈（FileBrowser 内 runOperation）

## 结论

操作入口的权限一致性做得好；核心缺口在 Web 上传任务的「取消」能力与若干长任务对话框未复查的盲区。

统计：P0×0　P1×0　P2×3（含 1 项待确认）
