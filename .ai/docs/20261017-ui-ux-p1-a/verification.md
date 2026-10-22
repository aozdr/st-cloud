# 验证记录：文件页信息架构与操作可发现性（P1-A）

> Task：`20261017-ui-ux-p1-a`
> 设计修订：`p1a-design-18A256312566-12DB37F7F68D`
> 代码修订：`p1a-3457eb36cf71`
> 日期：2026-10-17
> 结论：**IMPLEMENTED + VERIFIED（构建 + 真实 Chrome 1440/390 走查）**。

## 1. 修改摘要

| 类别 | 内容 |
|---|---|
| 页面层级 | `FileBrowser` 改为“标题 + 项数 → 面包屑 → 工具栏”；根目录标题“全部文件”、文件夹当前名、分类名；分类页隐藏面包屑 |
| 主操作 | 工具栏上传改 primary（移动端文案“上传”），新建改 secondary；移动端工具栏隐藏排序/文件夹优先，保留刷新/视图 |
| 行操作 | `FileTableView` 收藏/更多在 `<md` 常显，按钮 36×36；复选框外圈 36×36 命中区；操作列 112px |
| 网格 | `FileGrid` 收藏按钮 36×36，移动端常显 |
| 面包屑 | 路径/根目录/编辑/取消按钮补 ≥32–36px 命中区 |
| 分页 | 上一页/下一页 `min-h-9`，页码输入 `h-8` |
| 更多菜单 | 表格“更多”按钮接入 ContextMenu（此前 `onItemMenu` 未接线，按钮不存在） |

## 2. 验收对照

| 验收项 | 状态 | 证据 |
|---|---|---|
| 根目录标题“全部文件” | 通过 | 浏览器 `title=全部文件` |
| 文件夹标题为当前文件夹名 | 通过 | `/files` 进入 `uu` 后 `title=uu` |
| 分类页标题且无面包屑 | 通过 | `/files/category/image` → `title=图片`、无路径导航 |
| 面包屑在工具栏之前 | 通过 | DOM 位置检查 `breadcrumbBeforeToolbar=true` |
| 上传 primary、新建 secondary | 通过 | 类名与浏览器检查 |
| 移动端工具栏无裁切 | 通过 | 390px：上传右边界 104、视图右边界 302，均 <390 |
| 390px 无横向溢出 | 通过 | `pageOverflow=0`、`tableOverflow=0` |
| 移动端行收藏/更多可见 | 通过 | `opacity=1`；尺寸 36×36 |
| 分页/面包屑命中区 | 通过（静态） | `min-h-9` / `w-8 h-8` / `min-h-8` |
| `npm run lint` / `npm run build` | 通过 | 0 errors / 11 既有 warnings；build 成功 |
| `git diff --check` | 通过 | exit 0 |

## 3. 命令与结果

```text
npm run build  → 成功；PWA 65 entries
npm run lint   → 0 errors, 11 既有 warnings
npm run test:hash → 6/6 pass
git diff --check → exit 0
```

真实浏览器（Chrome headless + CDP）：

```text
node .ai/docs/20261017-ui-ux-p1-a/browser-p1a.mjs
→ 12/12 PASS（9 项 P1-A + 3 项 P1-D 标题/品牌）
```

证据：`browser-p1a-results.json`、`p1a-files-root-1440.png`、`p1a-files-folder-1440.png`、`p1a-files-mobile-390.png`。

## 4. 未覆盖与限制

- 未测 768/1024 宽度与 7 套主题色；390 只测了列表视图。
- “更多”按钮的点击展开未单独走查（P0-B 已覆盖 ContextMenu 键盘）；本轮只验证按钮存在、可见、36px。
- 移动端工具栏隐藏排序/文件夹优先；排序入口在移动端暂时不可用，后续应放入“更多”菜单（P1-B/C）。
- 拖拽、批量选择、分页实际翻页未在本轮浏览器脚本中回归；依赖既有逻辑未改。

## 5. 知识同步

- `.ai/knowledge/ui-design-system.md` 新增“文件页信息层级（2026-10-17）”：标题/面包屑/工具栏顺序、上传 primary、移动端工具栏收缩、触屏行操作规则。
