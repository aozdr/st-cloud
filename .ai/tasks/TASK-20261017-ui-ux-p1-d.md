# TASK：TASK-20261017-ui-ux-p1-d PWA 品牌与文档标题

> 小型、低风险配置/品牌修正；按 AGENTS §1 small 直接路径执行，不创建 Loop State。

## 目标

- 修正 PWA 图标、主题色、背景色与横竖屏策略；
- 让浏览器标签页标题随路由变化；
- 与 P1-A 文件页改动并行完成，文件集不重叠。

## 修改范围

- `st-web/index.html`
- `st-web/vite.config.ts`
- `st-web/src/store/theme.ts`
- `st-web/src/lib/useDocumentTitle.ts`（新增）
- `st-web/src/components/layout/AppLayout.tsx`（仅 hook 调用）
- `st-web/src/pages/AdminPage.tsx`（仅 hook 调用，覆盖 AppLayout 外路由）

## 验收

- [x] favicon `/vite.svg` → `/favicon.svg`
- [x] manifest `theme_color=#4F6EF7`、`background_color=#F7F8FA`、`orientation=any`、`lang=zh-CN`
- [x] theme-color meta 浅色 `#F7F8FA`、深色 `#111216`
- [x] `/files` 标题 `全部文件 - 星云盘`；`/admin` 标题 `系统管理 - 星云盘`
- [x] `npm run lint` / `npm run build` 通过
