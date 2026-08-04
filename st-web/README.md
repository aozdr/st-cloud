# 星云盘 Web 前端

星云盘的 Web 前端，基于 React 18 + TypeScript + Vite 构建。

## 技术栈

- **React 18** + **TypeScript**
- **Vite** 构建工具
- **Tailwind CSS** + **Radix UI** 组件库
- **Zustand** 状态管理
- **Recharts** 图表
- **Axios** HTTP 客户端

## 目录结构

```
src/
├── components/     # 组件
│   ├── admin/      #   管理端（限速等）
│   ├── file/       #   文件浏览、网格、版本历史
│   ├── layout/     #   布局（侧边栏、顶栏）
│   ├── preview/    #   文件预览
│   ├── share/      #   分享
│   └── ui/         #   基础 UI 组件（Button、Input、Toast 等）
├── hooks/          # 自定义 Hook（上传等）
├── lib/            # 工具库（API、请求、文件源、电子桥接）
├── pages/          # 页面
├── store/          # Zustand 状态（auth、theme、transfer、storage）
├── types/          # 类型定义
├── themes.ts       # 主题配置
└── App.tsx         # 路由与根组件
```

## 开发

```bash
npm install
npm run dev
```

启动后访问 `http://localhost:5173`，API 请求通过 Vite 代理转发至后端 `http://127.0.0.1:8080`（见 `vite.config.ts`）。

## 构建

```bash
npm run build
```

产物输出到 `dist/`。

## 状态管理

使用 Zustand 管理全局状态：

- `store/auth.ts` — 认证状态与登录/登出
- `store/theme.ts` — 主题切换
- `store/transfer.ts` — 传输设置（限速、并发）
- `store/storage.ts` — 存储用量（左下角实时刷新）
