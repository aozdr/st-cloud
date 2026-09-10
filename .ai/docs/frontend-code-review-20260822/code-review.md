# 星云盘前端项目 分模块 Code Review 总报告

> 审查人：主线程 reviewer（ox-alpha）/ 日期：2026-08-22
> 范围：st-web（React18+TS+Vite，约 15k 行）+ st-desktop（Electron31 主进程，约 4k 行）
> 方式：主线程静态人工审查（原定并行子线程方案因运行时不支持改为单线程执行）
> 说明：eslint / tsc --noEmit 因沙箱限制未能执行，全部结论来自读码；「待确认」项需结合后端契约复核

## 一、总体结论

工程化基础扎实：类型纪律好（全库仅 6 处 as unknown as、零 ts-ignore）、事件监听器清理配对完整、XSS 面控制干净（全局仅 SearchPage 两处且正确消毒）、同步引擎的游标/冲突框架设计正确且有历史 bug 根因沉淀。

风险集中在四条主线：**凭证与提取码的强度/传输路径**、**秒传弱指纹（可静默给错文件）**、**Electron webSecurity 关闭构成的攻击链**、**本地库全量导出式持久化**。

## 二、统计

| 模块 | 报告 | P0 | P1 | P2 |
|---|---|---|---|---|
| W1 基础设施层 | W1-infra.md | 0 | 6 | 6 |
| W2 状态与 Hooks | W2-store-hooks.md | 1 | 4 | 3 |
| W3 文件浏览视图 | W3-file-view.md | 0 | 2 | 2 |
| W4 文件操作对话框 | W4-file-actions.md | 0 | 0 | 3 |
| W5 管理后台 | W5-admin.md | 0 | 0 | 3 |
| W6 团队/分享/预览 | W6-team-share-preview.md | 0 | 4 | 4 |
| W7 基础 UI 与页面 | W7-ui-pages.md | 0 | 0 | 4 |
| D1 Electron 外壳与 IPC | D1-electron-shell.md | 1 | 2 | 6 |
| D2 同步引擎 | D2-sync-engine.md | 0 | 4 | 7 |
| D3 传输与存储 | D3-transfer-storage.md | 0 | 4 | 4 |
| **合计** | | **2** | **26** | **42** |

## 三、必须优先修复（Top 8）

| # | 级别 | 编号 | 问题 | 位置 |
|---|---|---|---|---|
| 1 | P0 | D1-1 | 渲染进程 `webSecurity:false` + 已知 XSS 点 = 可跨源窃取凭证的真实攻击链 | st-desktop/src/main.ts:60 |
| 2 | P0 | W2-1 | 大文件秒传指纹仅哈希前 2MB+大小，碰撞即静默拿错文件 | st-web/src/hooks/useUpload.tsx:41-47 |
| 3 | P1 | W2-1族 | 桌面端同样用采样 MD5 秒传，且双端采样算法互不一致（跨端秒传失效+误判） | md5.ts / useUpload.tsx / upload-manager.ts |
| 4 | P1 | D3-1 | sql.js 每次变更全量导出写盘，上传每分片触发一次，O(n²) 恶化 | database.ts:216-220 |
| 5 | P1 | D2-1~D2-4 | 同步破坏性边界四连：冲突副本上传错目录、下载中断残留半截文件回传、版本升级清空所有根状态、本地删除直通云端 | sync-engine.ts |
| 6 | P1 | W1-2+W6-3 | 新旧权限映射正反向矛盾（一处认为 0=全权、一处认为 0=最弱），必有一处错误 | permissions.ts / ShareAccessPage.tsx |
| 7 | P1 | W6-1/W6-2 | 提取码 Math.random 生成仅 4 位 + 经 URL query 三处外泄 | ShareDialog / ShareAccessPage / editor.ts |
| 8 | P1 | W1-1 | access+refresh token 存 localStorage，XSS 可窃取长效凭证 | api.ts / auth.ts |

## 四、跨模块主题

1. **秒传指纹家族**（W2-1 / D3-3 / D3-4）：建议一个专项统一为全量流式 MD5，双端共用算法并补往返测试。
2. **权限语义单源化**（W1-2 / W6-3）：legacy 单值权限的正反向映射必须与后端逐值对齐后收敛到 lib/permissions.ts。
3. **短时效凭证替代长 token 入 URL**（W1-8 / W3-2 / W6-2 / D2 ws token）：凡进 URL 的都应是一次性短期票据。
4. **数据加载竞态**（W3-1 / W6-4）：项目内已有正确范本（TextEditorPage 的 abort+cancelled），复制到 PreviewModal 与 FileBrowser 即可。
5. **破坏性操作宽限期**（D2-4 / D1-2 trashItem）：删除类动作建议延迟确认或限定路径白名单。

## 五、覆盖率声明

- 全文精读：api/fileSource/permissions/editor/utils/server-config/runtime/capacitor/electron、auth/transfer store、useUpload/useFileKeyboard/useFileSelection/useFileClipboard/useDragSelect/useFileDialogs、FileBrowser(核心区)/FileThumbnail/ContextMenu、ShareAccessPage/PreviewModal/EditorPage/TextEditorPage/ShareDialog/NotificationBell/AdminPage/Login(前半)、sync-engine(全文1036行)/sync-manager/sync-utils/ws-client/file-watcher/upload-manager/download-manager/database(关键区)/md5/file-utils/api-client(desktop)
- 模式扫描：hooks 清理配对、全局 XSS 面（dangerouslySetInnerHTML/innerHTML）、admin/team 组件定时器与监听器
- 未深读盲区：Archive/BatchRename/Convert/VersionHistory 对话框内部、UserManageTab 等 admin Panel 内部、TransferManager/SyncPage 轮询细节、layout 六组件——已在各分报告中标注为待复查项

## 六、建议的修复顺序

第一批（安全，1~2 天）：D1-1 → W1-1 → W6-1/W6-2 → W1-3
第二批（数据正确性，2~3 天）：秒传专项（W2-1/D3-3/D3-4）→ 权限映射对齐（W1-2/W6-3）→ 同步四连（D2-1~D2-4）
第三批（稳定性，按需）：D3-1 持久化防抖 → W3-1/W6-4 竞态 → W3-2 缩略图缓存
