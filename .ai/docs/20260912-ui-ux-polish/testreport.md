# 测试报告：st-cloud UI/UX 视觉与交互收敛

## 自动化结果

| 项目 | 命令/方式 | 结果 |
|---|---|---|
| Web lint | `node st-web/node_modules/eslint/bin/eslint.js .` | 通过，0 error、11 个既有 warning |
| Web TypeScript | `npm run build` 内含 `tsc -b`；另以 `tsc -p tsconfig.app.json --noEmit --incremental false` 复核 | 通过；初始缺失依赖已按现有 lockfile 补齐 |
| Web production build | `npm run build` | 通过；3901 modules transformed，Vite 与 PWA service worker 均生成成功 |
| Web MD5 contract | `node --test st-web/src/lib/hash-contract.test.mjs` | 通过，6/6 |
| Desktop TypeScript | `node st-desktop/node_modules/typescript/bin/tsc --noEmit` | 通过 |
| Desktop tests | `npm run test` | 通过，18/18；覆盖 db migration 4、retry 8、sync utils 5、transfer tasks 1 |
| Desktop main build | `node st-desktop/node_modules/tsup/dist/cli-default.js src/main.ts src/preload.ts --format cjs --outDir dist` | 通过 |
| 差异检查 | `git diff --check` | 通过，无空白错误 |

## 静态验收

- TopBar 未发现 Ctrl/Cmd+F 全局 handler。
- FileGrid 未发现使用 `files.filter(image)` 作为瀑布流唯一数据源的实现。
- `st-desktop/src/main.ts` 未发现 `createMiniWindow`、`showMiniWindow` 或托盘显示悬浮窗入口。
- `TransferManager.tsx` 未发现悬浮窗显示/复位入口。
- MobileTabBar 已指向 `/search`，Electron 由 AppLayout 隐藏。
- 修改文件中的新增硬编码文档颜色已收敛为语义 token；拖拽提示、确认遮罩、媒体播放 overlay 的半透明/模糊属于交互反馈保留项。

## 手工/浏览器验收

- 使用本地 Vite preview 检查 `/login`，视口为 1440×900、1280×800、960×600、390×844；四种尺寸的 `document.documentElement.scrollWidth` 与 `document.body.scrollWidth` 均等于 viewport 宽度，页面级横向溢出为 0，登录关键内容可见。
- Electron 托盘实机交互未启动；已完成 desktop typecheck、`npm run test` 与 `build:main`，入口静态验收通过。
- 初始 npm 启动器曾指向缺失的用户目录 `npm-cli.js`；改用系统 `npm.cmd` 完成依赖补齐和正式脚本验证，未修改 package.json/package-lock.json。
