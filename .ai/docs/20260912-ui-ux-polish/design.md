# 程序设计文档：st-cloud UI/UX 视觉与交互收敛

> 任务 ID：`20260912-ui-ux-polish`
> 输入：`D:/文档/下载/st-cloud_UI_Visual_Redesign_V2_Codex.md`、`D:/文档/下载/st-cloud_UI_UX_review_codex_execution.md`
> 工程约束：仓库根目录 `AGENTS.md`、`.ai/knowledge/frontend.md`、`.ai/knowledge/conventions.md`

## 一、需求分析

### 功能名称

共享 UI 视觉重构与 Web/Electron UX 修复。

### 用户场景

```
用户：Web、PWA/Capacitor、Electron 用户
操作：打开首页、文件页、传输、同步、登录、管理页，使用键盘、鼠标和触控操作
系统行为：共享 st-web UI 保持功能/API 不变，统一视觉层级并修复高频交互缺陷
最终结果：产品表现为文件优先、紧凑、低噪声的现代桌面云盘；不同运行环境不展示不可用入口
```

### 完成标准

- 两份计划中的 P0 项全部完成。
- 不新增后端 API、数据库字段、权限语义或第二套 React UI。
- P1 中不扩大范围的项目完成：导航分组、首页收敛、网格与登录视觉、同步文案、主题 token。
- Web lint/build、Desktop lint/test/build:main 取得真实结果；失败项明确记录。
- 1440×900、1280×800、960×600、390×844 的布局风险有静态或手工证据。

## 二、系统影响分析

| 类型 | 模块 | 是否修改 | 说明 |
|------|------|---------|------|
| 前端 | `st-web` 共享布局、文件管理、首页、传输、同步、管理、登录 | 是 | 主要改动面 |
| 桌面端 | `st-desktop` 主进程及必要的桌面入口 | 是 | 取消悬浮窗创建、托盘入口和主界面入口 |
| 后端 | API/权限/业务服务 | 否 | 不改契约 |
| 数据库 | Schema/迁移 | 否 | 无数据模型变化 |
| IPC | 现有 mini API 类型与底层模块 | 保留 | 本轮先切断用户可达路径，避免无必要的 IPC 破坏性变更 |

### 影响文件预测

- 共享样式：`st-web/src/index.css`、`st-web/tailwind.config.js`。
- 应用外壳：`st-web/src/components/layout/AppLayout.tsx`、`Sidebar.tsx`、`TopBar.tsx`、`MobileTabBar.tsx`。
- 文件页：`FileBrowser.tsx`、`FileToolbar.tsx`、`FileTableView.tsx`、`FileGrid.tsx`、`FileList.tsx`、`FileBreadcrumb.tsx`，必要时新增 `FileTypeIcon` 的视觉复用。
- 页面：`HomePage.tsx`、`components/home/FileCard.tsx`、`TransferManager.tsx`、`SyncPage.tsx`、`AdminPage.tsx`、`Login.tsx`。
- 桌面入口：`st-desktop/src/main.ts`，必要时同步 `AppLayout` 的悬浮窗事件监听。
- 任务文档：本目录下 `testcases.md`、`changereport.md`、`codereview.md`、`security.md`、`testreport.md`。

## 三、整体设计方案

实施顺序固定为：

1. Design System 与 App Shell。
2. 文件管理器高频交互和视觉层级。
3. Home、Transfer、Sync。
4. Login、Admin、Mobile runtime 入口。
5. 串行执行 lint/build/test 与验收矩阵。

统一原则：白色内容面、淡灰辅助面、低对比边框、单一品牌蓝、紧凑控件、无普通卡片阴影、无整页大灰底。业务状态色只保留在状态或文件类型图标内。

## 四、前端设计

### 4.1 Design System

- 主色固定为 `#4F6EF7`，hover `#4563E6`，浅色 `#EEF2FF`。
- Light token 收敛到：`bg #F7F8FA`、`surface #FFFFFF`、`surface-2 #F6F7F9`、`fg #20242D`、`muted #687080`、`border #E8EAF0`、`border-light #F0F1F4`。
- Dark token 收敛到：`bg #111216`、`sidebar #17181D`、`surface #1B1D22`、hover `#22252B`、border `#2B2E35`、主文字 `#EAECF0`、辅助文字 `#9DA3AF`。
- 展开 Sidebar `224px`，折叠 `64px`；TopBar `60px`；普通按钮目标高度 `36px`。
- 保留现有语义 Tailwind token，业务页不再新增无语义灰色/蓝色硬编码；仅保留文件类型与状态色。
- 删除普通按钮 `scale(.98)` active 反馈；普通卡片不加 shadow；popover/modal 只使用轻量阴影。
- 动画收敛到 opacity/轻微位移，保留 `prefers-reduced-motion` 兼容。

### 4.2 App Shell 与导航

- `AppLayout` 使用白色主内容面，去除主内容四角的大圆角与不必要悬浮感；Electron 标题栏继续独立保留。
- `Sidebar` 按“文件/工具/桌面端/管理”组织低频入口，首页和全部文件保持最前；系统管理固定靠下；保留权限过滤。
- 导航拖拽排序能力不删除，但抓手只在 hover/focus 可见，避免常驻噪声；折叠状态只保留图标和 tooltip。
- `TopBar` 保留 `Ctrl/Cmd+K`，移除全局 `Ctrl/Cmd+F` 拦截；搜索框 placeholder 为“搜索文件或内容”，回车触发搜索，移除固定 primary 搜索按钮；宽度使用 `min(440px, responsive)`。
- `MobileTabBar` 使用 `getRuntime()`：Electron 不显示底部 Tab；Web/Capacitor 不把不可用的传输页放入主 Tab，使用“首页/文件/搜索/更多”。

### 4.3 文件管理器

- 工具栏拆为无选择态与选择态：无选择态只保留新建/上传、排序/刷新、视图；选择态突出已选信息、下载/移动/复制/更多/删除；批量重命名等低频操作收进更多。
- 视图切换不改变当前目录语义。瀑布流仅在当前文件集合全部为图片或明确图片分类时可用；普通文件夹隐藏按钮并自动回退网格，禁止静默丢弃非图片文件。
- `FileTableView` 行声明稳定 `group`，收藏/更多/选择按钮同时支持 `group-hover`、`focus-within`、selected；触控和键盘不依赖 hover。
- 列表表头使用 `surface-2`，列表行使用 `surface`，选中使用 `primary light`；网格保持白色内容面，不延伸列表表头灰底。
- 网格普通文件/文件夹的文字左对齐，媒体允许缩略图/播放 overlay；普通文件操作使用浅色圆形按钮，不使用黑色媒体蒙层。
- 文件页将 Toolbar、Breadcrumb、item count 组成一个紧凑白色信息带；保持表格内部横向滚动而非页面横向溢出。

### 4.4 Home / Transfer / Sync

- Home greeting 降为 `20px/600`，存储信息置于右侧紧凑显示；快捷分类高度控制在 `72~80px`。
- 保留“最近访问”作为主 Recent 区块；删除独立“最近文件”重复区块；收藏仅有数据时展示。
- Transfer 删除 KPI 卡片和页面内悬浮窗按钮，改为标题、速度/任务数 inline summary、过滤 Tab、传输列表；保留暂停/恢复/设置等真实能力。
- Sync 顶部改为标题、实时连接状态、添加同步；根列表保留状态与启动/停止，策略/选择性同步/删除放展开详情或更多；所有用户可见英文改为中文。

### 4.5 Admin / Login

- Admin 的页面背景、表面、边框、文字和 active 状态全部使用主题 token；宽度、标题栏、导航间距与主 Sidebar 视觉一致，保留独立管理布局。
- Login 去除渐变文字、发光点和营销式超大标题；Electron 左侧更克制，保留 Logo、“安全同步你的文件”和 2~3 个能力点；Web 可保留简短品牌说明。

### 4.6 桌面端悬浮窗

- `st-desktop/src/main.ts` 不再启动 mini window，不再在托盘显示“显示传输悬浮窗”，托盘双击回到主窗口/打开传输管理，不创建独立悬浮窗。
- `AppLayout` 和 `TransferManager` 移除用户可见的悬浮窗入口及相关跳转/复位调用。
- 本轮不删除 `mini-window.ts`、`menu-window.ts`、`mini-transfer.html`、`mini-menu.html` 及相关 preload/IPC 类型，以保留向后兼容和降低桌面构建风险；它们在本轮结束后不得由正常启动、托盘、页面入口触达。

## 五、后端与数据库设计

无后端、API、权限语义、数据库或迁移变更。

## 六、安全与兼容性

- 不改变权限判断、上传下载、同步协议、预览内核或文件操作 API。
- 文件删除、移动、复制、上传和同步调用链保持原样；仅调整 UI 入口与展示。
- 取消悬浮窗只切断创建和用户入口，不改变传输队列与任务持久化。
- 保留 Electron IPC mini 类型，避免 Web 与旧桌面渲染资源混用时产生不必要的契约破坏。
- 所有 icon button 保持桌面至少 `32x32`、移动至少 `40x40` 的可操作区域；键盘 focus 使用可见 ring。

## 七、性能设计

- 不引入新的运行时依赖和全量状态重构。
- 文件表格继续使用现有虚拟列表；只调整 class/可见操作区。
- 搜索历史、文件加载、传输和同步事件订阅保持原实现。
- 视觉动画不增加整页 scale/fade，不给普通文件卡片增加 shadow/transform。

## 八、开发计划

```
Task A：token、AppLayout、Sidebar、TopBar、MobileTabBar、Admin
Task B：FileBrowser/FileToolbar/FileTableView/FileGrid/FileList/FileBreadcrumb
Task C：TransferManager、SyncPage、AppLayout 桌面悬浮窗入口、st-desktop main/tray 启动路径
Task D：Home、FileCard、Login，以及统一样式清理
Task E：串行 lint/build/test、静态验收矩阵与文档
```

实现阶段可在一个主工作树内按依赖顺序完成；不并行修改同一 UI 文件，不修改后端。

## 九、遗留问题点（Grill Me）

| 编号 | 遗留问题 | 影响 | 建议方案 | 用户裁决 |
|------|---------|------|---------|---------|
| P1 | mini-window 相关 dead code 是否在本轮一并删除 | 一并删除会扩大 Electron IPC/打包风险；保留会有未使用文件 | 本轮仅切断启动、托盘和页面入口，下一轮再删除 | 待确认 |
| P2 | Web/Capacitor 移动 Tab 是否用“搜索”替代不可用的“传输” | 改变移动端主导航标签，但不改变路由和能力 | 使用“首页/文件/搜索/更多”，Electron 不显示 MobileTabBar | 待确认 |
| P3 | 首页是否删除独立“最近文件”区块 | 减少重复内容，但用户需从“最近访问”获取同类文件 | 保留“最近访问”，删除“最近文件”独立区块 | 待确认 |

## 十、风险分析

| 风险 | 影响 | 解决方案 |
|------|---------|---------|
| UI 改动文件多 | class 组合错误或响应式回归 | 先完成 typecheck/build，再按验收矩阵检查关键尺寸 |
| 传输悬浮窗入口清理 | 用户误以为传输功能被删除 | 明确保留 `/transfers`、暂停/恢复/限速/历史，验证主传输页可用 |
| 瀑布流可用性变化 | 旧偏好可能保存 waterfall | 读取偏好后按当前集合自动回退 grid，写回正常视图 |
| 主题 token 收敛 | 未发现的硬编码颜色残留 | `rg` 扫描并记录允许的文件类型/状态色例外 |

## 十一、用户确认

用户于 2026-09-12 确认以下设计决策：

- 接受本轮保留 mini-window 底层 dead code，仅切断启动、托盘和页面入口。
- Web/Capacitor 移动 Tab 使用“搜索”替代不可用的“传输”；Electron 不显示 MobileTabBar。
- 首页删除独立“最近文件”区块，仅保留“最近访问”作为主 Recent 区块。
