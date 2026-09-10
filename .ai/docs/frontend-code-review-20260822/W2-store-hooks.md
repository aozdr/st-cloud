# W2 状态与 Hooks Code Review

> 审查人：主线程 reviewer（ox-alpha）/ 日期：2026-08-22 / 方式：静态人工审查（重点全文精读 useUpload/auth/transfer/selection/keyboard/clipboard/dragselect/filedialogs，其余 hooks 以模式扫描+清理配对核查）
> 范围：`st-web/src/store/**`（6 文件）、`st-web/src/hooks/**`（11 文件）

## 模块概述

- `hooks/useUpload.tsx`(345行)：Web 上传核心——MD5 秒传检查 → 分片直传 S3 / 低速中转双通道；Electron 走 IPC 委托
- `store/auth.ts`(142行)：登录态 + token 主动刷新定时器（生命周期 80% 触发）
- `store/transfer.ts`(114行)：传输设置 + 服务端限速上限取严合并
- 其余：文件选择/键盘导航/框选/剪贴板/长按/下拉刷新/PWA 等 UI hooks

## 问题清单

| 编号 | 严重度 | 位置 | 问题 | 证据(代码片段) | 建议 |
|---|---|---|---|---|---|
| W2-1 | **P0** | hooks/useUpload.tsx:41-47 | 大文件(>10MB)秒传指纹只哈希前 2MB + 文件大小。同前缀同大小的不同文件会碰撞 → **秒传错误命中后用户拿到的是别的文件内容**，属静默数据错乱 | `if (file.size > 10*1024*1024) { reader.readAsArrayBuffer(file.slice(0, 2*1024*1024)); }` | 改全量分片哈希（边读边 append），或服务端抽样二次校验后再允许秒传 |
| W2-2 | P1 | hooks/useUpload.tsx:224-248 | Web 上传无断点续传与重试：init 后不向服务端查询已上传分片，任一分片失败整个任务作废，只能整文件重传 | `await Promise.all(batch.map(uploadChunk))` 失败即进入 catch 标记 failed | 失败任务保留 uploadId，重试时调已传分片查询接口跳过完成片 |
| W2-3 | P1 | hooks/useUpload.tsx:197-206 | chunk-url 申请 `while (!url)` 循环无最大重试次数，服务端持续返回空 URL 时前端死循环轮询 | `while (!url) { ... }` | 加最大尝试次数与指数退避，超限置 failed |
| W2-4 | P1(待确认) | hooks/useUpload.tsx:253 | Electron 路径上传忽略 spaceId（参数名 `_spaceId`），桌面端在团队空间内上传可能落到个人根目录 | `addFilePaths = (... _spaceId?: string) =>` | 确认调用链；startUpload IPC 增补 spaceId 透传 |
| W2-5 | P1(待确认) | store/auth.ts:11,58-72 | ACCESS_TOKEN_TTL=7 天硬编码前端（注释称与后端一致）；且多标签页各持独立定时器并发用同一 refreshToken 刷新，若后端 refresh 是一次性 rotation 会互相踢下线 | `const ACCESS_TOKEN_TTL = 7*24*60*60*1000;` | TTL 从 /auth/me 或配置接口下发；刷新加 BroadcastChannel/Web Locks 单飞 |
| W2-6 | P2 | hooks/useFileKeyboard.ts:122-129 | Delete/F2 快捷键未做权限校验，与空格/Enter 预览的 `file:preview` 校验不一致 | `case 'Delete': if (st.selectedIds.size > 0) a.handleDelete(...)` | 统一入口权限判断（后端兜底仍在，但交互应一致） |
| W2-7 | P2 | hooks/useUpload.tsx:220 | uploadedChunks 用数组 spread 追加，大文件分片多时 O(n²) 拷贝 | `[...(t.uploadedChunks || []), index]` | 改计数即可（进度只用 uploadedCount），或 Set |
| W2-8 | P2 | store/auth.ts:50 | 主动刷新失败跳转 '/login' 硬编码，与 api.ts 相同的回跳丢失问题 | `window.location.href = '/login'` | 同 W1-7 一并统一处理 |

## 亮点

- 全部事件型 hooks 监听器注册/退订严格配对（keydown/mousemove/mql/beforeinstallprompt 逐项核对无泄漏）
- useFileKeyboard 与 useDragSelect 采用 ref 读态 + 单监听器模式，避免频繁解绑重绑；框选带边缘自动滚动与 rAF 帧循环管理，Windows 资源管理器级交互还原度
- transfer store 的「服务端上限 ∩ 用户设置取严」逻辑正确且注释清楚；外部设置广播不回发防循环
- useFileSelection 的 Ctrl/Shift/移动端长按三种选择语义完整

## 结论

Hooks 层工程质量整体优秀，交互细节打磨到位。但 **W2-1 弱哈希秒传是数据正确级缺陷**，必须最优先修复；上传链路的续传/重试缺失（W2-2/W2-3)是云盘产品稳定性短板。

统计：P0×1　P1×4（含 2 项待确认）　P2×3
