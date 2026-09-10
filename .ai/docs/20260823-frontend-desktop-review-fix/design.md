# 前端与桌面端 Code Review 修复设计

## 1. 背景

代码评审了 `st-web`（React18+TS+Vite 前端）与 `st-desktop`（Electron 桌面端），
当前存在 1 个 Critical 构建失败、多处安全与契约不一致问题。
本设计修复评审结论，并新增「分享提取码防爆破」方案（已与用户确认）。

## 2. 目标与边界

目标：让 `st-web` / `st-desktop` 恢复可构建，收敛安全基线（禁用不安全 Electron 配置、
修复 IPC 来源校验与路径穿越），并统一前后端认证/分享/同步契约。

范围：`st-web`、`st-desktop`，以及为支撑上述修复所需的
`st-auth` / `st-sync` / `st-core` / `st-share` / `st-api` 配合改动。

禁止范围：不重构业务逻辑、不动数据模型字段、不改上传/下载/同步算法
（仅做安全加固与契约对齐）。

## 3. 修复计划

### 3.1 前端 `st-web`

| 编号 | 问题 | 修复 | 归属 |
|------|------|------|------|
| P0-1 | `tsc -b` 失败：`FileCard.tsx`/`FileTypeBadge.tsx` 引用缺失的 `getDesignBadge`/`DesignFileBadge`，且未接入 `FileGrid` | 移除这两个未接入文件，或补实现并接入 | 纯前端 |
| W-1 | PWA `NetworkFirst` 缓存 `/api/.*`，会把带 token 的文件流写入 Cache Storage | `vite.config.ts`：stream/download 用 `NetworkOnly`，含 `token` query 拒绝缓存 | 纯前端 |
| W-2 | `buildStreamUrl`/`FileThumbnail`/`PreviewModal` 把 `accessToken` 放 URL query；后端 `JwtAuthenticationFilter` 明确拒绝 query 中的 access token | 移除 access token 入 URL 的 fallback；图片/GIF 走 `/preview/*/thumbnail` 或 `POST /file/{id}/download-token`（query 只放短期 download 令牌） | 纯前端 |
| W-3 | 分享 `pwd` 进 URL/历史/日志 | 密码不再写地址栏，改状态/header 传递 | 前端为主，后端配合 |
| W-4 | `categoryFileSource` 分页 `total` 用启发式估算 | 依赖 `/api/search` 的真实 `total` | 前端 + 后端确认 |
| W-5 | 三套文件大小格式化、两套环境检测 | 收敛单源，删除 `fileSize.ts` 与本地实现 | 纯前端 |
| W-6 | `legacyToPermissions(2)` 仅回 `{view}`，与 DB 注释「2-上传」不符 | 修正为 `{view, upload}`，与后端双向核对 | 前端 + 后端核对 |
| W-7 | lint 9 warning、`AdminPage` 内容未按权限门禁 | 修 2 个依赖告警；渲染加 `can` 守卫 | 纯前端 |

### 3.2 桌面端 `st-desktop`

| 编号 | 问题 | 修复 |
|------|------|------|
| D-1 | `webSecurity:false` + `sandbox:false` | 改回 `webSecurity:true`；开发用 Vite 代理，生产 `/api` 统一走主进程转发 |
| D-2 | IPC 无 sender 校验，`preload` 暴露 `trashItem`/`openPath`/`startUpload`/`startDownload`/同步目录 | handler 校验 `event.senderFrame.url`；文件路径仅允许对话框/下载目录返回值 |
| D-3 | `app://` 协议 `path.join(resources, web, pathname)` 路径穿越 | `path.resolve` 后断言落在 `resources/web` 内 |
| D-4 | `sync-engine.processCloudDelta` 云端相对路径未防 `..`，`fs.rmSync`/`renameSync`/`unlinkSync` 可越界 | 拼接后断言 `path.resolve(absPath)` 在 `path.resolve(root.localPath)` 内，否则跳过 |
| D-5 | WebSocket 用 `?token=<JWT>` | 改 `new WebSocket(url, {headers:{Authorization}})` |
| D-6 | 桌面 refresh 读 `accessToken`，后端字段为 `token` | 改读 `res.data?.data?.token` |
| D-7 | `getUserId` 未处理 base64url | `payload.replace(/-/g,'+').replace(/_/g,'/')` 再 base64 解码 |
| D-8 | `chokidar` 未声明为直接依赖 | 加入 `dependencies` |
| D-9 | 生产 `console.log` 噪音、`sync-engine.ts` 958 行 | 收敛日志；拆分 reconcile/delta/冲突处理（可选后置） |

### 3.3 后端配合项

| 后端 | 改动 |
|------|------|
| `st-auth` SecurityConfig/CORS | 放行 Electron `app://web`、开发 `http://localhost:5173`；或前端 `/api` 统一走主进程代理（推荐） |
| `st-share` ShareController | 分享 `password` 从 `@RequestParam` 迁移到 header/body/短时令牌 |
| `st-sync` SyncAuthHandshakeInterceptor | 握手支持 `Authorization` header 取 token |
| `st-sync` SyncServiceImpl | `isUnderRoot` 改为路径归一化比较（防 `..` 入库） |
| `st-core` / `st-api` | 确认 `/api/search` 返回真实 `total` |
| `st-core` / `st-sync` | 核对 `permission=2` 语义与 `legacyPermissionFromPerms` |

## 4. 分享提取码防爆破设计（已确认）

匿名分享（无需登录）场景下不存在绝对不可绕过的客户端唯一标识；换 IP/VPN 可绕过
纯 IP 计数。因此采用「服务端签发受控令牌 + 全局限流 + 高熵提取码」三层方案。

### 4.1 身份模型

- 主身份 = 服务端签发匿名访问令牌 `visitToken`。
  - `POST /api/share/access/access` 校验密码成功后返回 `ShareAccessVO.visitToken`（随机 UUID）。
  - Redis 存 `stcloud:share:visit:{visitToken}` → 绑定 `shareCode`，TTL 30 分钟。
  - 后续 `list / download / stream / editor-config` 一律带 `visitToken`（header），不再带密码。
  - 失败计数按 `shareCode + visitToken` 记录。换 IP 不改变 `visitToken`，VPN 换 IP 无法绕过锁定。
- 兜底 = 服务端全局限流（不分身份）：
  - `stcloud:share:global-fail:{shareCode}` 失败计数，10 分钟窗口 30 次上限，超限即拒绝该分享全部尝试。
  - 挡住「换 token + 换 IP」的分布式爆破。
- 根治 = 高熵提取码：创建分享生成 8 位随机字母数字，替代低熵数字码。

### 4.2 判定流程（进入 `ShareServiceImpl.validateShareAccess` 第一步）

```text
1. 全局限流：INCR global-fail，若超过窗口阈值，抛 SHARE_PASSWORD_LOCKED
2. 主锁：存在 share:lock:{shareCode}:{visitToken} 且未过期
     -> 命中锁：INCR lockhit；若 lockhit % 5 == 0 则重置锁 TTL（上限 30 分钟）
     -> 直接抛 SHARE_PASSWORD_LOCKED，不校验密码、不走正常逻辑
3. 无锁：校验密码
     -> 正确：DEL fail / lock / visit，放行（visitToken 继续有效）
     -> 错误：INCR fail；若 fail >= 10 设置 share:lock（TTL 10 分钟），抛 SHARE_PASSWORD_ERROR
            否则抛 SHARE_PASSWORD_ERROR
```

### 4.3 Redis Key 汇总

| Key | 用途 | TTL |
|-----|------|-----|
| `stcloud:share:visit:{token}` | 会话主体（绑定 shareCode） | 30 分钟 |
| `stcloud:share:fail:{shareCode}:{visitToken}` | 失败计数 | 10 分钟（命中 10 次上锁） |
| `stcloud:share:lock:{shareCode}:{visitToken}` | 锁定标记 | 10 分钟（每次 reset 上限 30 分钟） |
| `stcloud:share:lockhit:{shareCode}:{visitToken}` | 锁定期内命中计数 | 与锁一致 |
| `stcloud:share:global-fail:{shareCode}` | 全局限流 | 10 分钟 |

### 4.4 错误码与前端

- 新增 `SHARE_PASSWORD_LOCKED`（建议 `3005`，文案「尝试过多，请稍后再试」）。
- 前端 `ShareAccessPage`：本地计数连续 10 次错误即禁用提交按钮 + 倒计时；
  收到 `SHARE_PASSWORD_LOCKED` 立即禁用（服务端已锁）。
- 前端密码/令牌不再拼 URL（复用 W-3）。

### 4.5 依赖与落点

- `st-share` 复用 `st-common` 的 `spring-boot-starter-data-redis` 与 `RedisConfig`
  （st-common 已依赖），在 `ShareServiceImpl` 注入 `StringRedisTemplate`。
- 统一挂钩点：`validateShareAccess(shareCode, password)`（`ShareServiceImpl.java:553`），
  `accessShare / getDownloadUrl / listShareFiles / streamShareFile / editorConfig` 全部收敛于此。
- `ShareAccessVO` 增加 `visitToken` 字段；`ShareAccessRequest` 保持 `password`（body）。

## 5. 风险

- `visitToken` 存 `sessionStorage`：关窗即失效；后端短 TTL 兜底，泄漏面小。
- 全局限流可能误伤同分享下的大量正常访问/多人协作，需合理阈值并支持管理员清 key。
- 后端把分享 `password` 改 header/body 是接口契约变化，需同步发布前端，避免旧前端不兼容。
- Electron 恢复 `webSecurity:true` 后 `/api` 跨源需 CORS 或主进程代理，二选一必须落地，否则黑屏。

## 6. 验证

- 前端：`npm run lint` + `npm run build`（`tsc -b` 必须通过）。
- 桌面：`npm run lint` + `npm test` + `npm run build`。
- 安全：`/app://web/../../` 返回 404；`/sync/ws` 带 header 鉴权；无接口 URL 出现 access token。
- 契约：登录刷新字段为 `token`；`/search` 分页 total 正确；`permission=2` 三处一致。
- 防爆破：连续 10 次错误触发锁定；锁定期间正常接口直接返回 `SHARE_PASSWORD_LOCKED`；
  VPN 换 IP 不解除锁定；`visitToken` 失效后需重新 access。

## 7. 遗留问题点（待确认）

1. 全局限流阈值（默认 10 分钟 / 30 次）与锁定时长（10 分钟，重置上限 30 分钟）是否作为默认值。
2. `visitToken` 存储：`sessionStorage`（推荐）/ `localStorage`，以及是否随页面刷新保持。
3. 是否本轮一并实施高熵提取码（8 位随机）与旧弱提取码迁移，还是仅对新分享生效。
