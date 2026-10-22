# 验证记录：文件表格可访问性与移动排序（P1-B）

> Task：`20261017-ui-ux-p1-b`
> 设计修订：`p1b-design-8DF789A2936F-CB2FD32A11BE`
> 代码修订：`p1b-d7807e7dc8cf`
> 日期：2026-10-17
> 结论：**IMPLEMENTED + VERIFIED（构建 + 真实 Chrome 1440/390 走查）**。

## 1. 修改摘要

本轮按两个文件不重叠的并行子任务实施：

| 子任务 | 文件 | 内容 |
|---|---|---|
| A：表格 ARIA | `FileTableView.tsx` | `role="grid"`、`aria-label`、`aria-multiselectable`、`aria-rowcount/colcount`；表头/虚拟容器 `rowgroup`；`columnheader aria-sort`；行 `aria-selected`/`aria-rowindex`/roving tabIndex；7 个 `role="cell"` |
| B：移动排序 | `FileToolbar.tsx` | `<md` 专用排序 Popover：名称/大小/时间、升降序、文件夹优先；桌面排序组保持不变 |

## 2. 验收对照

| 验收项 | 状态 | 证据 |
|---|---|---|
| 表格容器 `role="grid"` 且 `aria-label/multiselectable/rowcount/colcount` | 通过 | 浏览器：`role=grid`、`label=文件列表`、`multiselectable=true`、`rowcount=16`、`colcount=7` |
| `rowgroup` 完整 | 通过 | `rowgroups=2`（表头 + 虚拟行容器） |
| `columnheader aria-sort` | 通过 | 名称 `ascending`，大小/时间 `none` |
| 行 `aria-selected` / `aria-rowindex` / roving tabIndex | 通过 | 首行 `selected=false`、`rowindex=2`、`tabIndex=0` |
| 单元格 `role="cell"` | 通过 | 首行 7 个 cell |
| 点击行更新 `aria-selected` | 通过 | 点击后 `true` |
| 移动端排序入口 | 通过 | 36×36、可见、右边界 254 < 390 |
| 移动端选“大小” | 通过 | `columnheader[aria-sort=ascending]` 文本“大小” |
| 移动端切换方向 | 通过 | `ascending → descending` |
| 390px 无横向溢出 | 通过 | `pageOverflow=0`、`tableOverflow=0` |
| `npm run lint` / `npm run build` | 通过 | 0 errors / 11 既有 warnings；build 成功 |
| `git diff --check` | 通过 | exit 0 |

## 3. 命令与结果

```text
npm run build  → 成功；PWA 65 entries
npm run lint   → 0 errors, 11 既有 warnings
npm run test:hash → 6/6 pass
git diff --check → exit 0

node .ai/docs/20261017-ui-ux-p1-b/browser-p1b.mjs
→ 10/10 PASS
```

浏览器证据：`browser-p1b-results.json`、`p1b-files-1440.png`、`p1b-files-390-sorted.png`。

子任务自检：两个子 Agent 均只改各自文件，`npx eslint <file>` 通过；未运行 build（避免并发构建），由主线程统一构建。

## 4. 未覆盖与限制

- roving tabIndex 只提供 Tab 进入点，方向键仍由既有文件列表全局 hook 处理；未新增行级 `onFocus`/方向键 DOM focus 同步。
- `aria-colcount` 固定为 7，不随移动端 CSS 隐藏列变化；读屏会将隐藏列计入列数。
- 行内“更多/收藏”等嵌套按钮仍可被 Tab 访问，属于嵌套交互控件的既有结构；未改成 menu button 模式。
- 移动排序 Popover 打开时若选择态被程序化置为 true，入口卸载但 state 保留；正常点击外部会先关闭，未加重置 effect。
- 未测 768/1024、7 主题色、移动端瀑布流/网格视图的排序入口（网格视图共享同一工具栏）。

## 5. 知识同步

- `.ai/knowledge/ui-design-system.md` 的文件页规则补充表格 ARIA 约定：`role=grid`、`rowgroup`、`row/cell`、`aria-selected`、`columnheader aria-sort`、roving tabIndex。
