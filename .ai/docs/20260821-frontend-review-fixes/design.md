# 程序设计文档：前端代码审查问题修复（第一批）

> 任务 State：`.ai/state/20260821-frontend-review-fixes.yaml`
> 输入：`frontend-review` 审查报告（2026-08-21）；本项目为 Bug 修复批次，无新功能需求，需求即为审查结论，不再单独产出 requirement.md。

# 一、需求分析

## 功能名称

前端代码审查问题修复（第一批：5 P1 + 3 P2）

## 功能描述

修复三端共用的前端缺陷，目标场景：

```
用户：桌面端/移动端用户
操作：文本预览/文本编辑/GIF 预览/下载文件/批量上传/团队空间上传
系统行为：修复前这些场景在打包版 Electron 与 Capacitor 壳下失效或低效
最终结果：三端行为一致、上传并发受控、桌面 token 自动续期、安全加固
```

## 修复项清单

| 编号 | 问题 | 影响 |
|------|------|------|
| P1-1 | 相对 `/api` URL 在打包端失效 | 打包版文本预览/编辑、GIF 预览、下载链接坏 |
| P1-2 | 桌面端刷新 token 字段与后端契约不符 | 桌面 token 过期后永不续期，持续 401 |
| P1-3 | `app://` 协议路径未校验 | 潜在本地文件越权读取 |
| P1-4 | Web 多文件上传串行 | 大文件阻塞后续文件，并行设置无效 |
| P1-5 | 桌面端上传丢失 spaceId | 团队空间上传失败/落错空间 |
| P2-6 | `TransferFloatingWidget` 死代码 | 无用代码 + 类名冲突隐患 |
| P2-7 | 搜索映射三处重复 | 维护漂移风险 |
| P2-8 | 缩略图一次性全量请求 | 200 项页面并发 200 请求 |

# 二、系统影响分析

## 影响模块

| 类型 | 模块 | 是否修改 |
|------|------|---------|
| 前端 | st-web（API 层 / 文件列表 / 上传 / 预览 / 搜索） | 是 |
| 前端 | st-desktop（主进程 / preload / IPC / 上传管理器） | 是 |
| 后端 | 无 | 否 |
| 数据库 | 无 | 否 |

## 影响文件预测

- 新增文件：无
- 修改文件：
  - st-web：`src/lib/api.ts`、`src/lib/fileSource.ts`、`src/components/preview/PreviewModal.tsx`、`src/components/file/FileThumbnail.tsx`、`src/pages/TextEditorPage.tsx`、`src/hooks/useUpload.tsx`、`src/pages/SearchPage.tsx`、`src/hooks/useFolderSearch.ts`、`src/shims.d.ts`（electronAPI 类型）
  - st-desktop：`src/api-client.ts`、`src/main.ts`、`src/preload.ts`、`src/types.ts`、`src/ipc-handlers.ts`、`src/upload-manager.ts`
- 删除文件：`st-web/src/components/TransferFloatingWidget.tsx`

# 三、整体设计方案

8 个修复项相互独立、无数据依赖，但 P1-1/P1-2 涉及三端行为，回归时需整体验证。实现顺序按「契约类（P1-2/P1-5）→ 跨端 URL（P1-1）→ 安全（P1-3）→ 性能（P1-4/P2-8）→ 清理（P2-6/P2-7）」。

# 四、前端设计（st-web）

## P1-1 流 URL 绝对化

**根因**：`buildStreamUrl` 返回相对路径 `/api/...`，打包版 origin 为 `app://web/`、Capacitor 为 `https://localhost`，相对路径解析到壳自身。

**方案**：

1. `lib/api.ts` 的 `buildStreamUrl` 内部改为 `getServerUrlSync() + '/api/file/${nodeId}/stream...'`，所有调用方（fileSource.getDownloadUrl、FileThumbnail 兜底、PreviewModal 的 GIF/兜底）自动获得绝对 URL，无需逐处修改。
2. `PreviewModal.tsx` 第 93/110 行的两处 `fetch('/api/...')`（分享流、普通文本流）改为 `fetch(getServerUrlSync() + '/api/...')`。
3. `TextEditorPage.tsx` 第 32 行 `fetch('/api/file/${nodeId}/stream')` 同样改为绝对地址。
4. 分享流 URL 同步改为绝对地址（`/api/share/access/stream/...`）。

**兼容性**：axios 本就用 `getApiBaseUrl()` 直连服务器，说明后端 CORS 已放行浏览器来源；Electron 依赖现有 `webSecurity: false` 跨域放行（本批不改此策略，见遗留问题 Q3）。

## P1-4 Web 多文件上传并发化

**现状**：`useUpload.addFiles` 中 `for (const file of files)` 逐个 `await` 完整上传流程。

**方案**：

1. 将单文件上传流程抽为独立函数 `uploadOneFile(task, ctx)`（内容与原循环体一致，保留秒传/中转/分片/重试逻辑）。
2. `addFiles` 改为两步：先同步创建全部任务（UI 立即展示），再用小并发池执行上传。
3. 并发数 = `useTransferStore.getState().effective.maxParallelTasks`（1~10，默认 3），不引新依赖，手写 worker 池（约 30 行）：`let next = 0; const worker = async () => { while (next < files.length) await uploadOneFile(tasks[next++]); }; await Promise.all(workers)`。
4. 单文件内分片并发（5）保持不变。

## P1-5 团队空间上传 spaceId 透传（Web 侧）

`useUpload.addFilePaths` 把 `spaceId` 传入 `window.electronAPI.startUpload(filePath, parentId, replaceFileId, spaceId)`（当前 `_spaceId` 被丢弃）。

## P2-6 删除死代码

删除 `components/TransferFloatingWidget.tsx`。已核实全项目无引用（仅自身定义）。

## P2-7 搜索映射收敛

在 `lib/fileSource.ts` 新增导出函数 `searchResultToFileNode(r: SearchResultVO): FileNode`（含 `<...>` 标签剥离），替换 SearchPage / categoryFileSource / useFolderSearch 三处重复内联映射。

## P2-8 缩略图懒加载 + URL 缓存

`FileThumbnail`：

1. 增加容器 `ref`，挂载后用 `IntersectionObserver`（root 为 null，threshold 0）观察；可见后才请求 `/preview/{id}/thumbnail`；不可用环境（SSR/旧浏览器）回退立即请求。
2. 模块级 `Map<string, { url: string; at: number }>` 缓存（key = `${file.id}:${size}`，TTL 10 分钟），避免切视图/重挂载重复请求。
3. 现有 `contentVisibility: auto`（网格）保留，两者叠加。

# 五、桌面端设计（st-desktop）

## P1-2 桌面 token 刷新对齐

**根因**：`api-client.ts:33` 读 `res.data?.data?.accessToken`，后端返回 `{ token, refreshToken }`。

**方案**：

1. 刷新成功后同时更新 `token` 与 `refreshTokenValue`：
   `const data = res.data?.data; if (data?.token) { token = data.token; refreshTokenValue = data.refreshToken ?? refreshTokenValue; }`
2. 刷新成功后主进程广播 `auth:refreshed`（`BrowserWindow.getAllWindows().forEach(w => w.webContents.send('auth:refreshed', { token, refreshToken }))`），防止渲染进程继续持旧 token。
3. `preload.ts` 暴露 `onAuthRefreshed(cb)`；`st-web/shims.d.ts` 类型同步；`st-web` 侧在 `lib/electron.ts` 增加订阅封装，并在 `main.tsx` 订阅一次：收到后写回 localStorage（axios 请求拦截器按请求读 localStorage，无需额外处理）。

## P1-3 app:// 协议路径校验

`main.ts` 的 `protocol.handle('app')`：

1. `pathname = decodeURIComponent(url.pathname)` 后先 `replace(/\\/g, '/')`、拒绝包含 `\0` 或 `..` 段。
2. `const filePath = path.resolve(process.resourcesPath, 'web', pathname)`，校验 `filePath` 必须以 `webRoot + path.sep` 为前缀或等于 `webRoot`，否则回退 `index.html`。
3. 保留现有「文件不存在 → index.html」的 SPA 回退。

## P1-5 IPC 契约扩展（spaceId）

1. `st-desktop/src/types.ts`：`ElectronAPI.startUpload` 增加可选第 4 参 `spaceId?: string`；`TransferTask` 增加可选 `spaceId?: string`。
2. `preload.ts` 透传第 4 参。
3. `ipc-handlers.ts` `upload:start` handler 接收 `spaceId` 并传给 `startUpload`。
4. `upload-manager.ts` `startUpload(filePath, parentId, replaceFileId?, spaceId?)`：spaceId 写入任务记录，`/file/upload/check` 与 `/file/upload/init` 请求体按 Web 端同款 `...(spaceId ? { spaceId } : {})` 透传。resume 不需要 spaceId（不重走 check/init）。

# 六、数据库设计

无变更。桌面端 `task` 表仅增加可选字段 `spaceId`（列可空，存量数据兼容；sql.js 迁移脚本按需加列，见 testcases）。

# 七、安全设计

| 项 | 措施 |
|---|------|
| app:// 路径 | 规范化 + 白名单前缀校验（P1-3） |
| token 刷新 | 字段对齐 + 主/渲染进程同步（P1-2）；残余双端竞态见遗留问题 Q1 |
| IPC | 本批不改全部 handler 的 sender 校验（范围外），仅对新增/修改的 `upload:start`、`auth:refreshed` 不做越权面扩大 |
| 上传 | spaceId 仅透传，不做前端权限判断（后端已有校验） |

# 八、性能设计

| 项 | 措施 |
|---|------|
| 上传 | 文件级并发受 maxParallelTasks 控制，分片并发不变 |
| 缩略图 | IntersectionObserver 懒加载 + 10 分钟 URL 缓存 |

# 九、开发计划

```
Task1: P1-2 + P1-5（桌面契约类：types/preload/ipc-handlers/api-client/upload-manager）
Task2: P1-1（跨端流 URL：api.ts/fileSource/PreviewModal/TextEditorPage/FileThumbnail 兜底）
Task3: P1-3（app:// 路径校验：main.ts）
Task4: P1-4 + P2-8（上传并发池 + 缩略图懒加载）
Task5: P2-6 + P2-7（删除死代码 + 映射收敛）
```

各 Task 独立提交，验证由主线程串行执行。

# 十、遗留问题点（Grill Me 拷打收敛）

| 编号 | 遗留问题 | 影响 | 建议方案 | 用户裁决 |
|------|---------|------|---------|---------|
| Q1 | 主进程与渲染进程各自持有 refresh token，双方可能同时触发刷新，refresh token 轮换可能互相作废 | 极低概率出现一方刷新失败（各自有回退：渲染进程跳登录、主进程保留旧 token 重试），同步类任务短暂中断 | 本批接受残余竞态，后续迭代做"单一刷新权威"（渲染进程 401 时委托主进程刷新，经 IPC 返回新 token） | 待确认 |
| Q2 | P2-8 缩略图 URL 缓存 TTL=10 分钟，后端预签名 URL 实际过期时间未暴露 | 若后端 URL 过期 <10 分钟，缓存期间偶发图片加载失败 | 采用 10 分钟 TTL；若后端暴露过期时间字段则改用动态 TTL | 待确认 |
| Q3 | P1-1 绝对 URL 在 Electron 打包版依赖 `webSecurity: false` 跨域放行；CORS 收紧方案（主进程代理 `/api`）改动较大 | 维持现状不新增风险面，但 `webSecurity: false` 本身是长期安全债 | 本批不动，列入第二批安全加固（主进程 net 代理 + 恢复 webSecurity） | 待确认 |

# 十一、风险分析

| 风险 | 影响 | 解决方案 |
|------|------|---------|
| P1-1 改动链路长（预览/下载/分享/文本） | 单点遗漏导致打包端局部失效 | 收敛为单一函数 + 全链路 grep 核对 + 三端手动回归清单 |
| P1-4 重构上传状态机 | 秒传/中转/重试逻辑回归 | 逻辑原样搬迁，仅改调度层；自动化 + 手工大文件上传回归 |
| P1-5 IPC 契约扩展 | 旧版桌面端与新前端混用 | 参数为可选追加，向后兼容；前后端同步发布 |
| P1-2 修复后行为反转 | 出现刷新风暴 | 实测 token 过期场景；广播同步防止渲染进程持旧 token |
