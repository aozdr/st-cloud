# 星云盘桌面客户端

基于 Electron 31 + TypeScript 的星云盘桌面客户端，封装 st-web 前端，提供本地文件传输（上传/下载）、断点续传、文件同步与原生系统集成。

## 功能特性

- **服务端地址配置**：无需登录即可在登录页「服务器设置」中配置后端地址，跨重启持久化
- **文件上传/下载**：分片上传、断点续传、暂停/恢复/取消，受传输限速规则约束
- **文件同步**：本地文件夹与云端双向同步（基于 st-sync 引擎）
- **原生集成**：系统文件选择对话框、下载目录、在文件夹中打开、移至回收站
- **传输任务管理**：SQLite（sql.js）本地持久化传输任务记录

## 技术栈

- Electron 31 + TypeScript
- tsup（构建主进程/预加载脚本为 CJS）
- electron-builder（打包为 Windows NSIS 安装包）
- axios（主进程 API 客户端）
- sql.js（浏览器端 SQLite，用于本地任务数据库）

## 项目结构

```
st-desktop/
├── src/
│   ├── main.ts              # 主进程入口：创建窗口、加载前端、启动时加载服务器地址
│   ├── preload.ts           # 预加载脚本：通过 contextBridge 暴露 electronAPI
│   ├── ipc-handlers.ts      # IPC 通道注册：服务器地址、认证、上传/下载、同步、对话框
│   ├── server-config.ts     # 服务器地址持久化（userData/server-config.json）
│   ├── api-client.ts        # 主进程 axios 客户端（上传/下载/同步使用）
│   ├── upload-manager.ts    # 上传任务管理
│   ├── download-manager.ts  # 下载任务管理
│   ├── sync-manager.ts      # 文件同步管理
│   ├── sync-engine.ts       # 同步引擎核心
│   ├── file-watcher.ts      # 本地文件监听
│   ├── transfer-settings.ts # 传输限速设置
│   ├── task-scheduler.ts    # 任务调度
│   ├── database.ts          # 本地 SQLite 任务数据库（sql.js）
│   ├── types.ts             # 类型定义（ElectronAPI、TransferTask 等）
│   └── utils/               # 工具（文件处理、MD5）
├── dist/                    # tsup 编译输出（main.js / preload.js）
├── electron-builder.json    # 打包配置
├── tsup.config.ts           # tsup 构建配置
└── package.json
```

## 快速开始

### 环境要求

- Node.js 18+
- st-web 已安装依赖（`npm install`）

### 开发模式

```bash
cd st-desktop
npm install
npm run dev
```

`npm run dev` 会并行启动 st-web 的 Vite 开发服务器与 Electron 主进程，Electron 窗口加载 `http://localhost:5173`。

### 打包构建

```bash
npm run build
```

`build` 流程：编译主进程/预加载脚本 → 构建 st-web 生产包 → electron-builder 打包为 `release/` 下的 NSIS 安装包。

## 服务器地址配置

桌面端支持自定义后端地址，无需登录即可配置。配置入口位于登录页底部「服务器设置」（仅 Electron 模式显示），对应前端路由 `/server-config`。

### 持久化机制

| 项 | 说明 |
|------|------|
| 默认地址 | `http://127.0.0.1:8080` |
| 存储文件 | Electron `userData` 目录下的 `server-config.json`，格式 `{"url": "..."}` |
| 加载时机 | 主进程启动时调用 `loadServerUrl()` 读取并缓存 |
| 同步方式 | 渲染进程通过 IPC 读写；保存后主进程 `api-client` 基址同步更新 |

> 渲染进程同时将地址写入浏览器 `localStorage`（键 `stcloud:serverUrl`），以便 axios 初始化。两者以 `userData/server-config.json` 为准。

### IPC 通道

| 通道 | 方向 | 说明 |
|------|------|------|
| `server:getUrl` | 渲染 → 主 | 读取已保存的服务器地址 |
| `server:setUrl` | 渲染 → 主 | 保存服务器地址并更新主进程 API 基址 |

渲染进程通过 preload 暴露的 `window.electronAPI.getServerUrl()` / `setServerUrl(url)` 调用。

## 传输与同步 IPC

| 模块 | IPC 通道 | 说明 |
|------|------|------|
| 认证 | `auth:set` | 同步 JWT 到主进程 |
| 上传 | `upload:start/pause/resume/cancel` | 上传任务生命周期 |
| 下载 | `download:start/pause/resume/cancel` | 下载任务生命周期 |
| 任务查询 | `tasks:getAll` | 获取全部传输任务 |
| 任务记录 | `task:remove` | 删除任务记录（不中止传输） |
| 对话框 | `dialog:selectFiles/selectFolder/selectSavePath` | 原生文件选择 |
| 系统 | `shell:openPath/showItemInFolder/trashItem` | 系统集成 |
| 同步 | `sync:register/listRoots/deleteRoot/start/stop/status` | 文件同步管理 |
| 事件 | `task:update` / `sync:event` | 主 → 渲染推送 |

## 许可证

MIT License，见根目录 [LICENSE](../LICENSE)。
