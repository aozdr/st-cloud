# W1 基础设施层 Code Review

> 审查人：主线程 reviewer（ox-alpha）/ 日期：2026-08-22 / 方式：静态人工审查
> 范围：`st-web/src/lib/**`（13 文件）、`types/index.ts`、`main.tsx`、`App.tsx`、`vite.config.ts`
> 说明：eslint / tsc --noEmit 因沙箱限制未能在本次会话执行，本报告基于人工读码。

## 模块概述

- `lib/api.ts`(132行)：axios 封装，Result 解包 + JWT 注入 + 401 刷新队列
- `lib/fileSource.ts`(150行)：个人盘/团队盘/收藏/分类四套 FileSource 抽象
- `lib/permission.ts` + `lib/permissions.ts`：管理员权限 hook 与文件夹权限点常量（命名相近但职责不同）
- `lib/server-config.ts`、`runtime.ts`、`electron.ts`、`capacitor.ts`：服务器地址与三端运行时检测
- `lib/utils.ts`(173行)：cn/消毒/文件类型识别/格式化；`lib/fileSize.ts` 与之重复的另一个格式化器
- `App.tsx`(98行)：懒加载路由表 + ProtectedRoute

## 问题清单

| 编号 | 严重度 | 位置 | 问题 | 证据(代码片段) | 建议 |
|---|---|---|---|---|---|
| W1-1 | P1 | lib/api.ts:36,89,94-95 | accessToken 与 refreshToken 均存 localStorage，任意 XSS 即可窃取长效凭证 | `localStorage.getItem('accessToken')` / `localStorage.setItem('refreshToken', ...)` | 迁移到 HttpOnly Cookie（需后端配合），或至少缩短 refresh 有效期并绑定设备指纹 |
| W1-2 | P1 | lib/permissions.ts:24-42 | 新旧权限映射正反向不对称：正向 `upload→2`，反向 `2→{view:true}`，往返丢失 upload 权限；正向可产出 3，反向无 3 分支 | `if (perms.upload) return 2;` ↔ `if (permission === 2) return { view: true };` | 对齐后端 legacyPermissionFromPerms 语义，补齐反向映射分支并加往返单测 |
| W1-3 | P1 | lib/fileSource.ts:130 | 分类页用弱正则剥 HTML 标签，而 utils.ts 已有白名单消毒器 sanitizeHighlight 未复用；正则对 `<em<a>>` 类畸形输入会漏 | `name: r.fileName.replace(/<[^>]*>/g, '')` | 改用 `sanitizeHighlight(r.fileName)` 或要求后端返回纯文本字段 |
| W1-4 | P1 | lib/fileSource.ts:83-92 | teamFileSource 的 getDownloadUrl/downloadZip 调个人端点 `/file/{id}/download-token`、`/file/download/zip`，非 `/team/{spaceId}/...`，团队文件下载可能 403 或绕过团队权限校验 | ``api.post(`/file/${nodeId}/download-token`)`` | 确认后端 /file/stream 是否校验团队节点归属；否则改调团队下载端点 |
| W1-5 | P1 | editor.ts:31-33 | 分享编辑配置将提取码放入 GET query，会落入服务器/代理访问日志与浏览器历史 | `params: { nodeId, password: password || undefined }` | 改 POST body 传递 password |
| W1-6 | P1 | vite.config.ts:48-63 | Service Worker 将 `/api` 响应缓存到磁盘 Cache Storage（含文件列表等认证数据），且 cacheableResponse 含 `statuses:[0,200]`，公共电脑上有隐私残留 | `urlPattern: /^\/api\/.*/i, handler: 'NetworkFirst'` | API 响应退出缓存或仅缓存明确公开的端点；移除 status 0 |
| W1-7 | P2 | lib/api.ts:106,112 | 401 后 `window.location.href='/login'` 硬编码且丢失回跳地址；Electron file:// 场景行为待验证 | `window.location.href = '/login'` | 用 router 导航并携带 redirect 参数 |
| W1-8 | P2 | lib/api.ts:16-22 | buildStreamUrl 将 token 放入 query（注释已说明为兜底），token 进入访问日志 | `params.set('token', opts.token)` | 保持短时效一次性 token，确认过期策略 |
| W1-9 | P2 | utils.ts:148 vs fileSize.ts:9 | 两套文件大小格式化并存，单位上限与小数位行为不一致 | `formatSize()` 上限 GB / `formatFileSize()` 上限 TB | 合并为单一实现，另一处 re-export |
| W1-10 | P2 | App.tsx:86 | `/admin` 路由仅有登录守卫无角色守卫，非管理员可直接输 URL 进入（若 AdminPage 内部无拦截则升级 P1） | `<Route path="admin" element={<AdminPage />} />` 外仅 ProtectedRoute | 加 RequireRole 包装或在 AdminPage 内首查角色 |
| W1-11 | P2 | main.tsx:14 | 生产 Electron 以 file:// 加载时 BrowserRouter 依赖 pushState 在 file 协议可用，存在兼容风险（待 D1 确认加载方式） | `<BrowserRouter>` | 若 Electron 走 file://，改 HashRouter 或自定义协议 |
| W1-12 | P2 | lib/permissions.ts:2,9 | 头部注释写「9 个权限点」，实际定义 10 个，文档漂移 | `9 个权限点与后端权限模型保持一致` | 更正注释 |

## 亮点

- 全库类型纪律好：15k 行仅 6 处 `as unknown as`、0 处 `@ts-ignore`
- `utils.sanitizeHighlight` 白名单消毒器设计正确（EM 白名单 + 属性全清）
- api.ts 解包层用接口收窄让调用方直接拿到业务数据，工程体验佳；401 并发刷新排队实现正确
- runtime.ts 三端检测优先级清晰（capacitor > electron > web），中文注释完整

## 结论

基础设施层整体工程质量高于平均，类型安全与注释规范执行到位。核心风险集中在**凭证存储位置（W1-1）**与**权限映射往返有损（W1-2）**两处，建议优先修复；W1-3/W1-4/W1-5 需结合后端语义确认后定级。

统计：P0×0　P1×6　P2×6
