# W6 团队/分享/预览 Code Review

> 审查人：主线程 reviewer（ox-alpha）/ 日期：2026-08-22 / 方式：静态人工审查（ShareAccessPage/PreviewModal/TextEditorPage/EditorPage/ShareDialog/NotificationBell/ShareManagePage 全文精读；team 其余组件模式扫描）
> 范围：`components/team|share|preview/**`、`pages/Team*`、`pages/Share*`、`pages/Editor*`

## 模块概述

- `pages/ShareAccessPage.tsx`(464行)：匿名分享访问页（提取码/文件夹浏览/权限门控/预览跳转）
- `components/preview/PreviewModal.tsx`(418行)：图片缩放平移/视频音频/文本预览 + OnlyOffice 跳转
- `pages/EditorPage.tsx`(243行)：OnlyOffice iframe 集成；`TextEditorPage.tsx`(140行)：纯文本在线编辑
- `components/share/ShareDialog.tsx`(317行)：创建分享（类型/提取码/权限点/有效期/二维码）

## 问题清单

| 编号 | 严重度 | 位置 | 问题 | 证据(代码片段) | 建议 |
|---|---|---|---|---|---|
| W6-1 | P1 | components/share/ShareDialog.tsx:29-34 | 提取码用 `Math.random()` 生成（非 CSPRNG），且仅 4 位（31 字符集 ≈ 92 万组合）；若后端无访问频率限制可被暴力猜解 | `code += chars[Math.floor(Math.random() * chars.length)]` | 改 `crypto.getRandomValues`，加长至 6 位；后端补限速 |
| W6-2 | P1 | pages/ShareAccessPage.tsx:101-108, PreviewModal.tsx:77-79, editor.ts:31-33 | 提取码经 URL query 三处外泄面（editor 跳转参数、OnlyOffice config GET、分享链接 ?pwd=），进浏览器历史/代理日志/Referer | `params.set('password', pwd)` | 编辑器配置改 POST；评估链接带码的产品取舍 |
| W6-3 | P1(待确认) | pages/ShareAccessPage.tsx:120-121 vs lib/permissions.ts:32-42 | 旧单值 permission 语义两处矛盾：本页认为 `>=1 可下载、>=3 可编辑`（0=最弱），permissions.ts 反向映射认为 `0=全部权限`。必有一处错误，直接造成权限误判 | `(fileInfo?.permission ?? 0) >= 1` ↔ `legacyToPermissions(0) → all true` | 与后端 legacy 值定义对齐后统一收敛到 permissions.ts 单源 |
| W6-4 | P1 | components/preview/PreviewModal.tsx:96-120 | 文本预览 fetch 无 AbortController/序号防竞态：快速切换文件时慢响应晚到会用旧内容覆盖当前显示（同页 TextEditorPage 已有正确写法可复用） | `.then((text) => { setTextContent(text); ... })` | 复制 TextEditorPage 的 cancelled+abort 模式 |
| W6-5 | P2 | pages/ShareAccessPage.tsx:187 vs 120 | 文件夹级「下载整个分享」按钮用旧字段 `fileInfo.permission >= 1` 门控，未走含 JSON 权限集的 canDownloadShare，与列表项按钮判定可能不一致 | `{fileInfo.permission >= 1 && (...)}` | 统一用 canDownloadShare |
| W6-6 | P2 | pages/TextEditorPage.tsx:23,80 | 返回按钮在 dirty 状态直接导航丢失改动（beforeunload 只管关标签页，不拦 SPA 路由）；保存为 last-write-wins 无版本冲突检测 | `const goBack = () => navigate(fromPath);` | goBack 前检查 dirty 弹确认；保存带 If-Match/baseVersion |
| W6-7 | P2 | components/preview/PreviewModal.tsx:59-61 | 分享场景预览也写入 localStorage 最近记录（shareContext 未排除），匿名访客机器上残留他人文件名 | `addRecentFile(file)` | shareContext 存在时跳过记录 |
| W6-8 | P2 | pages/ShareManagePage.tsx:1 | 整个页面源码被压成 3 行（无换行/缩进），diff 与维护困难；且使用原生 confirm() 与全站 ConfirmDialog 风格不一致 | 单行 2000+ 字符 | prettier 格式化；换用 ConfirmDialog |

## 亮点

- 全模块 XSS 面干净：全局扫描仅 SearchPage 两处 dangerouslySetInnerHTML 且正确经过 sanitizeHighlight 白名单消毒；文本预览走 `<pre>{text}` 转义
- EditorPage 工程质量高：destroyEditor 清理、初始化诊断面板、OnlyOffice 9.x iframe 高度塌陷实测修复均有注释沉淀
- ShareDialog 权限点勾选联动规则（view 依赖不可取消）与 effective-permissions 超权禁用 + 后端兜底的双层设计正确
- NotificationBell 轮询/外点关闭清理配对完整；TextEditorPage 的 abort+timeout+cancelled 是全项目正确的取数范本

## 结论

本组是安全敏感区，XSS 面控制得好，但**提取码的生成强度与传输路径（W6-1/W6-2）是分享体系的真实弱点**；W6-3 权限语义矛盾需尽快对齐，否则新旧数据行为漂移。

统计：P0×0　P1×4（含 1 项待确认）　P2×4
