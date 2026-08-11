# 前端与桌面端架构

> 本文档描述 st-web（React Web 端）与 st-desktop（Electron 桌面端）的架构。

## Web 端（st-web）

### 技术栈

React 18 + TypeScript + Vite + Tailwind CSS + Radix UI + Zustand

### 目录结构

```
st-web/src/
├── App.tsx              路由定义（react-router-dom v7）
├── main.tsx             应用入口（BrowserRouter + Provider 嵌套）
├── index.css            全局样式
├── pages/               页面组件
├── components/          可复用组件
│   ├── layout/          布局（AppLayout / Sidebar / TopBar）
│   ├── file/            文件管理（FileBrowser / FileGrid / FileTable / ContextMenu / Dialogs / UploadPanel 等）
│   ├── share/           分享（ShareDialog）
│   ├── preview/         预览（PreviewModal / PlyrPlayer - 支持字幕/倍速/图片缩放旋转）
│   ├── ui/              基础 UI 组件（button / input / dialog / toast / table 等）
│   ├── EmptyState.tsx    通用空状态组件（SVG 插图）
│   ├── StorageAnalysis.tsx 存储空间分析（按类型饼图）
│   ├── TransferFloatingWidget.tsx 传输浮窗（全局上传进度）
│   └── admin/           管理面板（UserManageTab / RoleManagePanel / AuditLogPanel / SpeedLimitPanel 等）
├── store/               Zustand 状态管理
├── lib/                 工具库与 API 封装
├── hooks/               自定义 Hooks
├── types/               TypeScript 类型定义
└── assets/              静态资源
```

### 页面路由

| 路由 | 页面组件 | 说明 | 认证 |
|------|----------|------|------|
| `/login` | Login | 登录页 | 公开 |
| `/share/:shareCode` | ShareAccessPage | 分享访问页 | 公开 |
| `/server-config` | ServerConfigPage | 服务器配置页 | 公开 |
| `/` | HomePage | 首页（仪表盘） | 受保护 |
| `/files` | FileManager | 文件管理（根目录） | 受保护 |
| `/files/:parentId` | FileManager | 文件管理（指定目录） | 受保护 |
| `/files/category/:type` | CategoryPage | 分类浏览 | 受保护 |
| `/search` | SearchPage | 全文搜索 | 受保护 |
| `/recycle` | RecycleBin | 回收站 | 受保护 |
| `/shares` | ShareManagePage | 我的分享管理 | 受保护 |
| `/admin` | AdminPage | 系统管理 | 受保护 |
| `/team` | TeamPage | 团队空间列表 | 受保护 |
| `/team/:spaceId` | TeamSpacePage | 团队空间详情 | 受保护 |
| `/transfers` | TransferManager | 传输管理 | 受保护 |
| `/sync` | SyncPage | 文件同步 | 受保护 |
| `/favorites` | FavoritesPage | 我的收藏 | 受保护 |
| `/duplicates` | DuplicateFilesPage | 重复文件检测 | 受保护 |
| `/hidden` | HiddenFilesPage | 隐藏文件 | 受保护 |

- 受保护路由通过 `ProtectedRoute` 组件守卫，未认证跳转 `/login`
- 所有受保护路由嵌套在 `AppLayout` 下（含 Sidebar + TopBar）
- 页面组件均使用 `React.lazy` 懒加载，配合 `Suspense` 加载态
- 全局 Provider：`ToastProvider` > `ConfirmProvider` > `PromptDialog`

### 状态管理（Zustand）

`src/store/` 目录：

| Store | 文件 | 职责 |
|-------|------|------|
| auth | `auth.ts` | 认证状态（token、用户信息、isAuthenticated） |
| storage | `storage.ts` | 存储配额信息 |
| transfer | `transfer.ts` | 传输任务状态（上传/下载进度） |
| theme | `theme.ts` | 主题（亮/暗模式） |
| folderFilter | `folderFilter.ts` | 文件列表筛选状态 |

### API 层

`src/lib/` 目录：

| 文件 | 职责 |
|------|------|
| `api.ts` | Axios 实例封装，请求/响应拦截器，自动附加 JWT Header |
| `electron.ts` | Electron IPC 通信封装，认证状态同步到桌面端 |
| `server-config.ts` | 服务器地址配置（localStorage / Electron） |
| `permission.ts` | 前端权限判断工具 |
| `store/favorites.ts` | 收藏功能（Zustand store，对接 /favorite/* API） |
| `recentFiles.ts` | 最近访问文件 |
| `fileSource.ts` | 文件数据源 |
| `fileTypes.ts` | 文件类型判断与图标映射 |
| `utils.ts` | 通用工具函数 |

### 服务器地址配置

- **Web 端**：`localStorage` 键 `stcloud:serverUrl`，路由 `/server-config`
- **桌面端**：`userData/server-config.json`，启动时自动加载并同步到渲染进程
- 配置保存后立即刷新 API 基址（`<服务器地址>/api`），无需重启
- 测试连接：探测 `/api/auth/login`，任意 HTTP 响应即视为连通

### 自定义 Hooks

| Hook | 文件 | 职责 |
|------|------|------|
| useUpload | `useUpload.tsx` | 文件上传逻辑（MD5 计算、分片、断点续传） |
| useDragSelect | `useDragSelect.ts` | 文件拖拽多选 |
| useFileKeyboard | `useFileKeyboard.ts` | 文件键盘操作（快捷键） |

## 桌面端（st-desktop）

### 技术栈

Electron 31 + TypeScript + tsup（打包） + electron-builder（安装包） + sql.js（本地 SQLite）

### 目录结构

```
st-desktop/src/
├── main.ts              Electron 主进程入口
├── preload.ts           预加载脚本（IPC 桥接）
├── ipc-handlers.ts      IPC 消息处理器
├── api-client.ts        后端 API 客户端
├── server-config.ts     服务器地址配置管理
├── sync-engine.ts       同步引擎核心
├── sync-manager.ts      同步管理器
├── file-watcher.ts      本地文件监听
├── upload-manager.ts    上传管理器
├── download-manager.ts  下载管理器
├── task-scheduler.ts    任务调度器
├── transfer-settings.ts 传输设置
├── database.ts          本地 SQLite 数据库（sql.js）
├── types.ts             类型定义
└── utils/
    ├── file-utils.ts    文件工具
    └── md5.ts           MD5 计算
```

### 架构

```
┌─────────────────────────────────────┐
│         渲染进程 (st-web)            │
│  React UI（复用 Web 端代码）          │
│  通过 window.electronAPI.* 调用 IPC  │
└──────────────┬──────────────────────┘
               │ IPC (contextBridge)
┌──────────────┴──────────────────────┐
│         主进程 (main.ts)             │
│  ├── ipc-handlers.ts   IPC 路由      │
│  ├── sync-engine.ts    同步引擎      │
│  ├── upload-manager.ts 上传管理      │
│  ├── download-manager.ts 下载管理    │
│  ├── file-watcher.ts   文件监听      │
│  ├── task-scheduler.ts 任务调度      │
│  └── database.ts       本地 SQLite   │
└──────────────┬──────────────────────┘
               │ HTTP (api-client.ts)
┌──────────────┴──────────────────────┐
│         后端 (st-api :8080)          │
└─────────────────────────────────────┘
```

### 关键能力

- **同步引擎**：监听本地文件夹变化（`file-watcher.ts`），通过 `sync-engine.ts` 与云端 delta 接口同步，冲突处理在 `sync-manager.ts`
- **传输管理**：`upload-manager.ts` / `download-manager.ts` 管理上传下载队列与断点续传
- **本地数据库**：`database.ts` 使用 sql.js 存储同步状态、文件映射等本地数据
- **任务调度**：`task-scheduler.ts` 调度同步与传输任务
- **服务器配置**：`server-config.ts` 管理 `userData/server-config.json`，启动时加载并同步到渲染进程

### 构建命令

| 命令 | 说明 |
|------|------|
| `npm run dev` | 同时启动 Web 开发服务器 + Electron |
| `npm run dev:web` | 仅启动 Web（cd ../st-web && npm run dev） |
| `npm run dev:electron` | 编译主进程并启动 Electron |
| `npm run build:main` | 编译主进程/预加载脚本到 dist/ |
| `npm run build:web` | 构建前端 |
| `npm run build` | 全量构建 + electron-builder 打包 |
| `npm run lint` | TypeScript 类型检查（tsc --noEmit） |
## 移动端架构（PWA + Capacitor）

> 2026-08-09 新增，详见 `.ai/docs/20260809-mobile-pwa-capacitor/`

### 技术方案

- **PWA**：vite-plugin-pwa，manifest + Service Worker（precache App Shell，API NetworkFirst，图片 CacheFirst）
- **Capacitor 6**：WebView + 原生插件（filesystem/camera/share/status-bar），与 Electron 壳并列
- **响应式**：Tailwind md（768px）断点，md 以下移动布局（底部 Tab + 抽屉），md 以上桌面布局（侧栏 + 多列网格）
- **后端零改动**：REST + JWT 无状态，移动端直接复用 `/api`，CORS 配置放行移动来源

### 环境检测统一抽象

`src/lib/runtime.ts` 提供三端检测，所有降级判断走统一入口：

- `getRuntime()`: 'capacitor' | 'electron' | 'web'
- `isCapacitor()` / `isElectron()` / `isWeb()`
- `isMobileViewport()`: matchMedia('(max-width: 767px)')
- 降级链：capacitor > electron > web

### 移动端组件

- `MobileTabBar`（`components/layout/`）：底部 Tab 导航（首页/文件/传输/更多），md:hidden
- `ActionSheet`（`components/ui/`）：底部操作菜单，替代桌面右键 ContextMenu
- `useMobile` hook（`hooks/`）：matchMedia md 断点响应

### Capacitor 配置

- `capacitor.config.ts`：appId `com.stcloud.app`，webDir `dist`，allowMixedContent false
- `src/lib/capacitor.ts`：原生插件懒加载（dynamic import），web 环境返回 null
- 打包：`npm run build` -> `npx cap sync` -> `npx cap add android` -> `gradlew assembleRelease`（无需 Android Studio）

### 移动端交互规则

- 长按文件触发 ActionSheet（移动端），右键触发 ContextMenu（桌面端）
- 键盘快捷键移动端禁用
- 表格类页面移动端 overflow-x-auto 横向滚动
- 对话框/Toast 移动端 max-w-[calc(100vw-2rem)] 防溢出
- 安全区适配：pt-safe / pb-safe（env(safe-area-inset-*)）

### 打包工具链（无 Android Studio）

- Node 18+ / JDK 17（已有）
- Android SDK commandline tools（sdkmanager 装 platform 34 + build-tools）
- Gradle wrapper（android 工程自带 gradlew）
- 签名：keytool 生成 keystore + gradlew assembleRelease