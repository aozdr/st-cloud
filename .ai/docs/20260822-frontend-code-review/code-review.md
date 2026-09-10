# st-web / st-desktop 分模块 Code Review

## 背景

评审对象是当前 `main` 工作区中的 `st-web` 与 `st-desktop` 全量源码，不是某一次 diff。目标是按模块识别安全、数据一致性、可靠性、性能和可维护性问题，并给出修复优先级。

## 输入

- 源码规模：`st-web/src` 约 19,306 行 / 125 个源码文件；`st-desktop/src` 约 5,279 行 / 26 个源码文件。
- 配置：Vite、PWA、TypeScript、ESLint、Electron、electron-builder、传输与同步模块。
- 验证结果：
  - `st-web`: `npm run lint` 通过，0 errors / 9 warnings；`npm run build` 通过。`recharts` chunk 533.92 KB，`FileBrowser` chunk 138.04 KB。
  - `st-desktop`: `npm run lint` 即 `tsc --noEmit` 通过；`npm test` 通过，17/17。
- 未验证运行中的 MySQL、S3、OnlyOffice、真实打包安装包和网络链路。

## 总体判断

项目功能面较完整，路由懒加载、桌面端 contextIsolation、同步重试、冲突副本、雪花 ID 迁移测试等设计有明确意图。但两个模块之间存在多处契约漂移；桌面端存在路径逃逸、过宽 Electron 权限、主进程阻塞 I/O 和非原子持久化等高风险问题；Web 端存在请求竞态、团队空间上传丢上下文、敏感 API 缓存和测试缺口。

优先级定义：

| 级别 | 含义 |
|---|---|
| P0 | 可导致本地路径逃逸或用户数据破坏，应立即修 |
| P1 | 安全、数据一致性或核心功能高风险 |
| P2 | 性能、可维护性、体验或防御纵深问题 |
| P3 | 低风险改进 |

## st-web

### P1

#### W1. 认证令牌存储与会话生命周期风险偏高

- access token 与 refresh token 都写入 `localStorage`：`st-web/src/store/auth.ts:99`、`st-web/src/lib/api.ts:94`。任何 XSS 都可能拿到长期 refresh token。
- logout 只触发后端接口但不等待结果：`st-web/src/store/auth.ts:129`。网络失败时服务端会话可能仍有效。
- access token TTL 硬编码为 7 天：`st-web/src/store/auth.ts:11`，容易与后端配置漂移；应解析 JWT `exp` 或由后端下发有效期。
- 刷新失败直接 `window.location.href = '/login'`：`st-web/src/store/auth.ts:50`、`st-web/src/lib/api.ts:106`。这绕过 React Router，也会在 Electron `app://` 环境造成整页状态丢失。

建议：

1. refresh token 改为 `HttpOnly; Secure; SameSite=Strict` Cookie；access token 保存在内存并由短时刷新接口换取。
2. 若短期无法改后端，先收紧 CSP、移除不必要的 `dangerouslySetInnerHTML`、限制第三方脚本，并缩短 refresh token 有效期。
3. logout 明确等待/重试吊销结果，失败时向用户提示会话可能未完全注销。
4. 登录跳转通过 Router 状态或事件完成，不要硬编码根路径。

#### W2. Web 与桌面端大文件采样 MD5 算法不一致

- Web 对超过 10MB 的文件只哈希前 2MB，再附加 float64 表示的文件大小：`st-web/src/hooks/useUpload.tsx:41`。
- 桌面端对超过 10MB 的文件哈希首部、中间、尾部各 2MB：`st-desktop/src/utils/md5.ts:29`。
- 桌面端注释声称“与前端逻辑一致”，但实际算法不同。

这会导致秒传/去重结果跨端不一致，也可能把内容变化落在未采样区间的文件误判为相同文件。

建议：

1. 后端定义唯一完整性算法，优先流式 SHA-256 或完整 MD5；分片上传另用逐片 ETag/MD5 校验。
2. 短期至少统一两端实现并补跨端向量测试；不建议长期使用采样哈希做秒传依据。

#### W3. Electron 团队空间上传丢失 `spaceId`

Web 上传入参支持 `spaceId`：`st-web/src/hooks/useUpload.tsx:11`。但桌面端委托函数将其声明为 `_spaceId` 并丢弃：`st-web/src/hooks/useUpload.tsx:253`；IPC 只传 `filePath/parentId/replaceFileId`：`st-desktop/src/ipc-handlers.ts:107`；桌面端初始化请求也不带 `spaceId`：`st-desktop/src/upload-manager.ts:106`。

结果是桌面端原生选择器上传团队空间文件时，可能被后端当作个人空间上传，或因 parentId 不属于个人空间而失败。

建议：

1. IPC 契约显式携带 `spaceId`。
2. 桌面端 `/file/upload/*` 根据后端契约传递 `spaceId`，或在 UI 层禁止桌面原生选择器用于团队空间直到支持。
3. 为“个人目录 + 团队空间目录”的上传路径补集成测试。

#### W4. 文件列表请求存在 stale response 竞态

`fetchFiles` 没有 AbortController 或请求序号：`st-web/src/components/file/FileBrowser.tsx:277`。该函数依赖 `parentId/page/pageSize/refreshKey`，并在 effect 中触发：`st-web/src/components/file/FileBrowser.tsx:302`。快速切换目录、翻页、修改页大小或连续刷新时，慢的旧响应可能覆盖新目录列表。

建议：

1. 在 effect 内创建 `AbortController`，cleanup 时 abort。
2. 或维护递增 requestId，只接受最新请求结果。
3. 加载中禁用高成本操作或保留旧数据时明确区分 stale loading 状态。

#### W5. 下载路径拼接与 shell IPC 缺少路径边界

- Web 端在渲染层拼接 Windows 路径：`st-web/src/components/file/FileBrowser.tsx:628` 使用 `${dir}\\${n.name}`，不可移植，也信任云端文件名。
- 主进程允许 renderer 直接打开、显示或删除任意路径：`st-desktop/src/ipc-handlers.ts:208`、`st-desktop/src/ipc-handlers.ts:212`、`st-desktop/src/ipc-handlers.ts:216`。

即使当前页面可信，这也是 XSS 后的高影响原语。文件名还可能包含分隔符或 Windows 保留字符。

建议：

1. 渲染层只传 `nodeId/name/taskId`，主进程用安全文件名生成下载目标。
2. 所有 `shell:*` 操作改为基于任务表中的 `savePath` 或用户本次选择的路径校验；拒绝越出 downloads/sync roots 的路径。
3. 服务端与桌面端共同校验文件名非法字符。

#### W6. PWA 缓存了所有 `/api` 响应

`vite.config.ts` 将 `/api/.*` 配置为 NetworkFirst 并缓存 status 200：`st-web/vite.config.ts:31`。这可能持久化认证、文件元数据、管理端、通知、分享访问等敏感响应到 CacheStorage。

建议：

1. 默认不缓存 `/api`。
2. 如确需离线体验，仅缓存明确的公共、低敏 GET 接口，并排除 `/auth`、`/file/*/stream`、分享流、admin、notification。
3. 缓存 key 不包含 token；登出时清理 `api-cache`。

#### W7. 桌面端服务器地址初始化存在双源不一致

Renderer axios 在模块加载时同步读取 localStorage：`st-web/src/lib/api.ts:5`、`st-web/src/lib/server-config.ts:31`。主进程地址则保存在 `server-config.json` 中，只有 ServerConfigPage 会异步读取并更新 axios：`st-web/src/pages/ServerConfigPage.tsx:17`、`st-web/src/pages/ServerConfigPage.tsx:47`。

如果 renderer localStorage 被清空或与主进程配置不一致，应用启动后会先请求错误地址。

建议：

1. Electron 启动时先通过 preload 获取主进程地址，成功后再挂载路由或初始化 API client。
2. 主进程地址作为 Electron 唯一事实源；web 浏览器环境才使用 localStorage。
3. 主进程校验协议为 http/https，生产环境限制明文 HTTP 仅用于 localhost 或用户显式确认。

### P2

#### W8. FileBrowser 已成为 God Component

`FileBrowser.tsx` 约 1,258 个非空行，包含 12 组 `useState`、15 个 `useEffect`、列表获取、排序、分页、拖拽、右键菜单、键盘、下载、移动、收藏、弹窗协调等职责。核心入口见 `st-web/src/components/file/FileBrowser.tsx:77`。

建议按三层拆分：

1. `useFileListing(source, query)`：列表、分页、排序、abort、refresh。
2. `useFileActions(source)`：删除、复制、移动、下载、收藏。
3. 展示组件：Toolbar、Breadcrumb、List/Grid、Pagination、DialogCoordinator。

#### W9. 大量异常被静默吞掉

静态搜索发现约 24 处 `catch { /* ignore */ }`，例如管理面板、团队评论、通知和同步页面。部分场景合理，但统一吞错会导致空列表、按钮无反馈、后端故障难以定位。

建议：

1. 定义前端错误策略：可忽略、可重试、需 toast、需 ErrorBoundary。
2. 至少对写操作和管理端查询输出用户可见错误。
3. 生产环境接入轻量 telemetry，而不是只 `console.error`。

#### W10. 可访问性仍有缺口

- `Dialog` 只在 mount 时收集 focusable 元素：`st-web/src/components/ui/Dialog.tsx:25`，动态内容变化后 Tab 圈不更新；关闭后也没有恢复触发元素焦点。
- `PreviewModal` 是全屏交互界面，但没有 `role="dialog"`、`aria-modal` 和完整焦点圈：`st-web/src/components/preview/PreviewModal.tsx:187`。

建议统一封装 Modal primitive：动态 focus trap、初始焦点、Escape、背景点击、关闭恢复焦点、滚动锁定的行为保持一致。

#### W11. 代码格式与可读性不一致

`st-web/src` 有 49 行长度不低于 300 字符。多个页面几乎整页压缩在一行，例如：

- `st-web/src/pages/RecycleBin.tsx:1`
- `st-web/src/pages/HiddenFilesPage.tsx:1`
- `st-web/src/pages/ShareManagePage.tsx:1`
- `st-web/src/pages/TeamSpacePage.tsx` 中多段 JSX 也极长。

建议引入 Prettier 或 Biome format check，并对新改动执行 CI 门禁；避免一次性 reformat 造成无关 diff，可按模块逐步格式化。

#### W12. 自动化测试不足

`st-web/src` 当前没有发现组件、hook 或 E2E 测试。构建只能证明类型和打包成功，不能证明文件浏览、上传、分享权限、回收站、团队权限等关键流程。

建议分层补充：

1. Vitest + Testing Library：API interceptor、token refresh、上传分片、权限 hook、文件名工具。
2. MSW：列表竞态、401 刷新队列、失败响应。
3. Playwright：登录、上传、下载、分享、团队空间最小 smoke。

### P3

#### W13. 性能与状态订阅可继续收敛

- 文件页大小最高 200：`st-web/src/components/file/FileBrowser.tsx:75`，但没有虚拟滚动；表格行也未 memo 化。
- `UploadProvider` 中每个分片都会 `setTasks`：`st-web/src/hooks/useUpload.tsx:216`，高频进度会让消费该 context 的组件一起渲染。建议拆分低频任务列表和高频进度 store，并节流到约 250ms。
- `recharts` chunk 533.92 KB；如只有少量图表，可在页面内部再 lazy import 图表层。
- 部分 Zustand 使用返回整个 store，如 `AppLayout` 中 `useAuthStore()`：`st-web/src/components/layout/AppLayout.tsx:17`。建议选择具体字段。

#### W14. 其他低风险改进

- Admin 路由只检查登录，不检查权限码：`st-web/src/App.tsx:86`。后端必须已强制鉴权；前端也应加 permission guard 减少无效请求和混淆页面。
- `permission.ts` 与 `permissions.ts` 名称易混：前者是 hook，后者是常量映射。建议改为 `usePermission.ts` 与 `permission-keys.ts`。
- `sanitizeHighlight` 当前白名单逻辑基本合理：`st-web/src/lib/utils.ts:24`，但更好的做法是让后端返回结构化片段，或先整体 escape 再识别 `<em>`，彻底去掉 `dangerouslySetInnerHTML`。
- Vite dev server 绑定 `0.0.0.0` 且 `allowedHosts: true`：`st-web/vite.config.ts:56`。开发便利可以接受，但建议默认 localhost，远程调试时显式开启。

## st-desktop

### P0

#### D1. 云端同步路径可逃逸本地同步根

云端返回的相对路径被直接拆分后拼接到本地根下：

- 全量对账：`st-desktop/src/sync-engine.ts:658`
- 云端 delta：`st-desktop/src/sync-engine.ts:770`
- 路径拼接：`st-desktop/src/sync-engine.ts:771`
- 目录递归创建：`st-desktop/src/sync-engine.ts:866`
- 云端删除甚至可能递归删除本地路径：`st-desktop/src/sync-engine.ts:789`

代码没有拒绝 `..`、绝对路径、Windows 盘符、保留设备名，也没有在拼接后做 canonical path containment 校验。恶意或受损账号/代理返回 `../../target` 类路径时，可能写到同步根之外；结合 DELETE 可造成本地数据破坏。

建议立即增加中央路径守卫：

```ts
function resolveInsideRoot(root: string, cloudRelPath: string): string {
  const normalizedRel = path.posix.normalize('/' + cloudRelPath.replace(/\\/g, '/'));
  if (normalizedRel === '/' || normalizedRel.includes('\0')) {
    throw new Error('invalid sync path');
  }
  const abs = path.resolve(root, '.' + normalizedRel);
  const rootAbs = path.resolve(root) + path.sep;
  if (!abs.startsWith(rootAbs)) throw new Error('path escapes sync root');
  return abs;
}
```

同时校验每一段：非空、不为 `.`/`..`、无 Windows 非法字符、不等于 `CON/PRN/AUX/NUL/COM1-9/LPT1-9`、最大长度受限。所有 mkdir/rename/download/delete 必须走该守卫。

### P1

#### D2. Electron 关闭 webSecurity 属于过宽权限

主窗口设置 `webSecurity: false`：`st-desktop/src/main.ts:53`。注释解释是为了绕过 CORS，但这同时削弱同源策略，使渲染层漏洞更容易读取其他源响应。

建议：

1. 移除 `webSecurity: false`。
2. 后端为 `app://` 或固定桌面 Origin 配置 CORS；或由主进程提供受控 API proxy/custom protocol。
3. 生产窗口启用 `sandbox: true`；当前 preload 只使用 `contextBridge/ipcRenderer`，通常可运行在 sandbox 下。

#### D3. `app://` 协议处理缺少 containment 校验

`pathname` 先 decode 再直接 join resources/web：`st-desktop/src/main.ts:139`。编码后的 `..` 可能绕过 URL 归一化后在 fs 层逃出资源目录。

建议：

1. `path.resolve(process.resourcesPath, 'web', pathname)` 后校验结果位于 web root 内。
2. 只允许白名单扩展名和 MIME 映射。
3. 找不到资源时返回明确 404，避免所有非法路径 fallback 到 index.html 掩盖问题。

#### D4. 登出不会清除主进程凭据或停止旧同步

Web 登出只是移除 localStorage：`st-web/src/store/auth.ts:129`。`syncAuthToElectron` 只有在两个 token 同时存在时才发 IPC：`st-web/src/lib/electron.ts:16`。因此主进程内存里的 token 不会被清空，已启动的同步引擎也不会因登出停止。下一个用户登录前，旧同步可能继续使用旧身份访问云盘。

建议：

1. 增加 `clearAuth` IPC；`auth:set` 支持 null。
2. clear 时顺序执行：stopAllSync -> 清空 token -> 取消 watcher/timer -> 广播未认证状态。
3. Web logout 无论 token 是否存在都调用 clearAuth。

#### D5. 桌面端 refresh token 字段与后端契约不一致

桌面端期待 `res.data?.data?.accessToken`：`st-desktop/src/api-client.ts:33`。后端 `LoginResponse` 字段是 `token` 和 `refreshToken`：`st-auth/src/main/java/com/stcloud/auth/dto/LoginResponse.java:13`。Web 端使用的也是 `token`：`st-web/src/lib/api.ts:93`。

因此桌面端后台自动刷新会一直拿不到新 token，长传/同步在 UI 未主动刷新时容易集中失败。

建议：

1. 桌面端改读 `data.token`。
2. 用共享 OpenAPI 类型或 contract test 锁定 `/auth/refresh` 响应。
3. 刷新成功后通知 renderer 同步新 refreshToken，避免主进程与 renderer token 状态漂移。

#### D6. sql.js 全库同步导出且写入非原子

数据库是整库内存导出：`st-desktop/src/database.ts:217`。每次任务进度都会 `persist()`：`st-desktop/src/database.ts:338`；上传循环最多每秒多次调用：`st-desktop/src/upload-manager.ts:343`。随着任务和块哈希增多，会在 Electron main process 反复执行 O(整库大小) 序列化和同步磁盘写入。`writeFileSync` 直写目标文件，崩溃时也可能损坏 `transfers.db`。

建议：

1. 迁移到 `better-sqlite3` + WAL，或至少将 SQL.js 数据放入 worker thread。
2. 进度更新合并/节流持久化，关键状态变更才立刻落盘。
3. 写临时文件 + flush/fsync/rename 实现原子替换。
4. 启动时校验备份并从 `.bak` 恢复。

#### D7. 大量同步 I/O 阻塞 Electron 主进程

以下操作都在 main process event loop 执行：

- 5MB 分片同步读取：`st-desktop/src/utils/file-utils.ts:11`
- 目录递归遍历/stat：`st-desktop/src/sync-engine.ts:388`
- 冲突副本复制、删除、rename：`st-desktop/src/sync-engine.ts:1017`
- 块哈希同步读取：`st-desktop/src/utils/block-hash.ts:23`

大目录、大文件或多任务并发时会导致窗口、悬浮窗和 IPC 卡顿。

建议：

1. 文件哈希、分片读写、目录扫描迁移到 `worker_threads`。
2. Node fs API 改 async，目录遍历使用异步队列和并发上限。
3. 为单根同步设置全局文件操作并发与字节速率预算。

#### D8. 云端 delta 失败项仍推进游标

`downloadFile` 内部捕获下载失败，只记录 history/event 后返回：`st-desktop/src/sync-engine.ts:909`。`syncOnce` 随后认为本轮成功并推进 cursor：`st-desktop/src/sync-engine.ts:277`。这与注释“全部变更处理成功后才更新”不符；失败的云端 CREATE/UPDATE 可能要等到下一次全量重建才会补齐。

建议：

1. `processCloudDelta` 返回成功/失败明细。
2. 单项失败时保留上一轮成功 cursor，或将失败项进入持久化 retry queue。
3. 对 DELETE/MOVE/RENAME 的 fs 异常也纳入同一退避机制。
4. 补“第 N 项下载失败不得推进游标”的集成测试。

#### D9. IPC 输入与外部服务器地址缺少运行时校验

几乎所有 handler 只写 TypeScript 参数类型，没有运行时校验。尤其：

- 服务器地址任意字符串可保存：`st-desktop/src/server-config.ts:40`
- 传输设置可直接合并，无范围检查：`st-desktop/src/transfer-settings.ts:86`
- shell 路径操作见 D5。
- mini window drag 参数也未限制范围。

建议引入 zod/valibot schema 校验所有 IPC 入参；服务器地址限制协议、端口和可选 allowlist；设置值限定 `1..10`、非负限速和上限。

#### D10. 自动 relink 盲目信任服务端 `localPathHint`

启动恢复时，若云端 root 没有对应本地 config，客户端会直接采用服务端返回的 `localPathHint` 写入本地配置并 startSync：`st-desktop/src/sync-manager.ts:231`。账号或服务端响应被篡改时可引导客户端监听/写入任意历史提示路径。

建议：

1. 自动 relink 仅允许匹配本机既有 config 快照或用户最近确认过的根。
2. 其他情况进入待确认状态，由用户重新选择目录。
3. 首次启动前展示旧云端路径与新本地路径，不允许静默绑定。

### P2

#### D11. JWT 放在 WebSocket query 中

WS 连接拼接 `?token=<JWT>`：`st-desktop/src/ws-client.ts:72`。日志已脱敏，但代理、服务端 access log 和异常上报仍可能接触到 token。

建议使用一次性短时 ticket：先用授权接口换 ticket，再通过 query 连接；或使用浏览器端不可用的 subprotocol/header 方案时确保不落日志。ticket 应一次性、60 秒内过期并绑定连接 IP/session。

#### D12. 数据库迁移靠 try/catch 忽略异常

多处列/索引迁移直接 catch 忽略：`st-desktop/src/database.ts:112`、`st-desktop/src/database.ts:130`、`st-desktop/src/database.ts:150`。这能处理“列已存在”，也会隐藏磁盘错误、SQL.js 异常和半迁移状态。

建议：

1. 引入 `PRAGMA user_version` 或本地 migration 表记录版本。
2. 每个 migration 幂等且显式检查 schema。
3. 只捕获预期的 duplicate column/index error，其他错误终止启动。

#### D13. 下载缺少最终一致性与断点续传完整性校验

下载只按字节数判断完成：`st-desktop/src/download-manager.ts:168`。续传依赖临时文件长度和 Range 206：`st-desktop/src/download-manager.ts:97`。若服务端对象版本变化、Range 语义异常或磁盘出现坏块，可能得到长度正确但内容错误的文件。

建议：

1. 下载前获取版本/ETag/content hash，续传响应必须匹配。
2. 完成后校验服务端 MD5/SHA-256。
3. 206 响应校验 `Content-Range` 起始位置。

#### D14. 日志与诊断信息缺rotation/redaction

renderer console 全量追加到 `desktop-log.txt`：`st-desktop/src/main.ts:65`。文件没有大小上限，业务异常可能包含 URL、token、用户路径或文件名。同步/WS console 日志也有类似风险。

建议建立统一 logger：级别控制、token/Authorization/query token redaction、单文件上限、滚动保留数量、用户手动导出脱敏包。

#### D15. 传输暂停的即时性有限

上传 pause 只改变状态标志，正在进行的 presign、S3 PUT、relay POST 不会被 abort：`st-desktop/src/upload-manager.ts:371`。单个 5MB chunk 或最长 300 秒 relay request 仍会继续消耗带宽。

建议每个任务持有 AbortController，pause/cancel 立即中止网络请求；确认失败不应静默吞掉后直接 merge，应给出明确失败原因。

### P3

#### D16. 打包、测试和其他改进

- `electron-builder.json` 已包含 web 资源，但建议 CI 中解压安装包断言 `resources/web/index.html`、`sql-wasm.wasm`、preload/main 存在。
- 当前自动化测试集中在纯函数：`db-migrate`、`sync-retry`、`sync-utils` 共 17 个用例。核心 transfer manager、database persistence、watcher、delta 处理无测试。先用 tmp 目录 + mock apiClient 覆盖路径守卫、游标、取消和崩溃恢复。
- 外部链接 handler 允许任意 http/https：`st-desktop/src/main.ts:79`。如产品有固定分享域名，可加域名 allowlist。
- `appendLog` 无 rotation：`st-desktop/src/main.ts:27`。
- `axios` 版本范围在两个模块不同（web `^1.19.0`，desktop `^1.7.2`）。以 lockfile 实际版本为准，但建议统一依赖策略和安全更新节奏。

## 跨模块问题汇总

| 问题 | 影响 | 修复方向 |
|---|---|---|
| 大文件哈希算法不一致 | 秒传漏判、重复上传、理论碰撞 | 后端定标准算法，两端共享测试向量 |
| 团队空间 `spaceId` 在 Electron 丢失 | 团队文件传错空间或失败 | IPC/API/UI 三层补字段并集成测试 |
| auth 生命周期分散在 localStorage/renderer memory/main memory | 登出残留、刷新失败、token 漂移 | 定義 auth session owner 和 clear/refresh 事件 |
| API envelope 处理两套实现 | 字段漂移（已发生 `token` vs `accessToken`） | 用 OpenAPI/generated client 或共享 contract test |
| 传输设置双写 localStorage 与主进程 | 启动值、服务端上限、悬浮窗修改可能短暂冲突 | Electron 下以主进程为 source of truth，启动时单向加载 |
| 云端文件名/相对路径信任不足 | 本地路径逃逸、下载覆盖、同步破坏 | 中央 sanitize + containment guard |

## 建议执行顺序

1. **P0 立即**：D1 同步路径守卫；补恶意相对路径回归测试。
2. **P1 第一批**：D2/D3/D4/D5/D6 收敛 Electron 权限与 auth/db 基础风险。
3. **P1 第二批**：W3/W4/W5/W6/W7 修复 Web 核心链路；X 跨模块契约同步定版。
4. **P1 第三批**：D7/D8/D9/D10 解决桌面性能、可靠性和输入边界。
5. **P2**：拆 FileBrowser、统一错误策略、可访问性、格式化、logger、下载校验。
6. **P3**：虚拟滚动、bundle、依赖一致性、打包产物断言。

## State Delta

- 新增评审报告：`.ai/docs/20260822-frontend-code-review/code-review.md`
- 未修改业务源码。
- 未标记任何 Loop exitCriteria；本报告是评审输入，修复需要另建 Task 并按 AGENTS 门禁执行。

## 风险

- 本次未执行真实大文件上传/下载、多用户并发同步、崩溃中断数据库、打包安装和恶意服务端响应测试；上述问题基于源码与静态证据。
- 部分问题是否可利用取决于后端已有校验。即使后端当前拦截了非法文件名，桌面端仍应做本地 defense-in-depth。
- 令牌存储、PWA 缓存、CORS 和 Electron 权限调整涉及后端契约与部署方式，实施前需要方案确认。

## 下一步

建议先创建一个独立安全修复 TASK，范围只包含 D1-D6；不要与 FileBrowser 重构或样式格式化混合，便于 review 和回滚。

