# 需求发现报告：PikPak 网盘 UI/UX 实地调研

> **调研动机**：星云盘计划以 PikPak 为参考标杆重做界面，需系统拆解其 UI/UX 设计 DNA。
> **调研方法**：基于用户提供的 `docs/PikPak.html`（mypikpak.com/drive/all 页面完整另存为），解析实际 DOM 结构、CSS 规则、内联样式与交互元素。**未使用图像识别**（模型无该能力），所有结论可追溯到 HTML 源码具体元素/类名/CSS 规则。
> **数据来源**：`docs/PikPak.html`（2.91MB，含完整内联 CSS + 渲染后 DOM body 83KB）。
> **PikPakFile 目录**：经检查为浏览器另存时附带资源（压缩 webpack chunks、Google 登录组件、小图标 PNG），**对 UI/UX 调研无用**，已跳过。
> **重要声明**：本报告为需求发现产出，仅记录观察事实与差距，**不替代 PM/UI 设计师做设计决策**。是否采用、如何落地由后续立项评审决定。

---

## 1. 调研背景与范围

### 1.1 调研对象
PikPak Web 端（mypikpak.com），"全部文件"页（`/drive/all`）。PikPak 定位为"云存储 + 媒体流式播放"混合产品，核心场景是**保存即观看**。

### 1.2 调研范围
- 页面布局与信息架构
- 交互流程与操作模式
- 组件设计模式（文件网格、文件夹封面、右键菜单、排序、上传、用户菜单等）
- 状态设计（loading / empty / error / 选中态）
- 视觉系统（色彩 / 间距 / 圆角 / 字体层级 / 图标）-- **作为设计系统的一部分，非全部**
- 对照星云盘前端现状，输出差距清单

### 1.3 调研边界
本次仅调研"全部文件"单页的 HTML 快照。PikPak 的播放器内页、分享页、设置页等**未包含**在快照中，相关结论标注为"未观察到"。动态交互（如拖拽、滚动加载的实际动画）仅能从 DOM 事件属性与 CSS transition 推断，非实际操作录屏。

---

## 2. PikPak 信息架构（实地 DOM 观察）

### 2.1 整体三栏布局

来源：`<body>` > `#app` > `.layout`

```
.layout (flex, 居中, absolute 定位满屏)
├── .header-bar (顶栏, height: 64px, background: #fff, box-shadow: 0 1px 0 rgba(0,0,0,.08))
│   ├── .header-bar-left (flex, 64px 高)
│   │   ├── Logo (SVG 链接, padding-left: 12px)
│   │   └── .search (搜索框, placeholder "搜索文件 Ctrl+F")
│   └── .header-bar-right
│       ├── .information-icons (通知图标 x2: 引荐奖励 + 外部购买注意)
│       ├── .be-member (会员升级链接)
│       ├── .avatar-wrapper (用户头像, 圆形, premium 样式)
│       └── .setting-icon (设置入口)
├── .main
│   ├── .sidebar (width: 200px, background: #f5f5f7)
│   │   ├── 云下载按钮 (el-button--primary, min-width: 123px)
│   │   ├── .nav (导航列表)
│   │   ├── .banner-carousel (引荐奖励轮播, height: 132px)
│   │   ├── .disk-usage (存储用量: "9.57 TB / 10 TB" + 进度条 + Premium 升级)
│   │   └── ul (移动端/桌面端/浏览器扩展/TV App 下载链接)
│   └── .container > .file-explorer (文件浏览主区)
│       ├── .file-explorer-header (工具栏, padding: 0 30px)
│       │   ├── .folder-navigator (面包屑: ol.real + ol.shadow 双层)
│       │   └── .file-operations (操作按钮组)
│       └── .list-container > .list-wrap (文件网格)
├── .transfer (右下角传输浮窗, 可折叠)
├── .cookie-wrapper (底部 Cookie 提示条)
└── .el-overlay.el-modal-dialog (模态弹窗容器)
```

**关键布局参数**（来源：CSS 规则与内联 style）：
- 顶栏高度：64px（`.header-bar { height: 64px }`），白底 + 底部 1px 投影分隔
- 侧边栏宽度：200px（内联 `style="width: 200px"`），浅灰底 `#f5f5f7`
- 工具栏内边距：`padding: 0 30px`
- 网格项宽度：184px（`--grid-item-width: 184px`，非 Tailwind 断点式，而是 JS 测量式自适应）

### 2.2 侧边栏导航项（来源：`.nav` > `ol` > `li`）

| 导航项 | 链接 | 图标色（非选中） | 说明 |
|--------|------|------------------|------|
| 全部文件 | /drive/all | `var(--color-primary)` 蓝色（当前页激活） | 默认页 |
| 最近添加 | /drive/recent | `#777` 灰 | -- |
| 回收站 | /drive/trash | `#777` 灰 | -- |
| 我的分享 | /drive/shared | `#777` 灰 | -- |
| 播放历史 | /drive/watch-history | `#777` 灰 | 媒体特性 |
| 已加星标 | /drive/star | `#777` 灰 | 收藏 |

**选中态样式**（来源：`.nav a.active`）：
```css
.nav a.active { color: var(--color-primary); background: #e5ebff; }
```
- 选中项文字变蓝色 `#306eff`
- 选中项背景为**淡蓝底 `#e5ebff`**（蓝色 8% 透明感）
- ⚠️ **修正旧报告**：旧 `pikpak-design-teardown.md` 称侧边栏选中态为灰色 `--color-active #f2f3f4`，实际导航选中态用的是**淡蓝 `#e5ebff`**，灰色 `#f2f3f4` 是 hover/active 的通用 token 但导航未使用它

**侧边栏底部区域**：
- 存储用量：`.disk-usage` 显示 "9.57 TB / 10 TB" + 横向进度条（`.progress-bar`，`right: 4.2154%` 表示已用占比）+ info 图标 tooltip + "Premium" 升级链接（金色 `--color-premium: #d1ae6a`）
- 分隔线后：移动端 App / 桌面端 App / 浏览器扩展 / TV App 四个下载入口（纯图标链接）

---

## 3. PikPak 文件网格组件深度拆解（核心 UI/UX 差异）

> 这是 PikPak 与星云盘最大的体验差异所在，**远超颜色层面**。

### 3.1 网格布局机制

来源：`.file-explorer` CSS 变量 + `.list-wrap` + `#shadow-li`

- `--grid-item-width: 184px`：每个网格项固定宽度 184px
- `--folder-cover-scale: 1.15`：文件夹封面缩放系数
- `--file-explorer-header-height: 36px`
- 布局采用 **shadow-li 测量机制**：首个 `li#shadow-li` 用于测量容器宽度计算列数（visibility: hidden, position: absolute），实现**按固定项宽自适应列数**，而非 Tailwind 的断点式 `grid-cols-N`
- `.list-wrap { padding: 0 10px }`，`.grid { padding: 10px 18px 0 }`

### 3.2 缩略图容器（统一 16:9）

来源：`.thumbnail-wrap` + `.thumbnail`

```css
.thumbnail-wrap { position: relative; padding-top: 56.25%; }  /* 56.25% = 9/16，强制 16:9 宽高比 */
.thumbnail {
  position: absolute; inset: 0;
  border-radius: 16px;          /* ⚠️ 缩略图圆角 16px，非 6px */
  overflow: hidden;
  background: #e1e6ea;          /* 占位灰底 */
}
```

**关键发现**：
- 所有文件/文件夹缩略图**强制 16:9 宽高比**（`padding-top: 56.25%` 技巧）
- 缩略图圆角 **16px**（`.thumbnail { border-radius: 16px }`）
- ⚠️ **修正旧报告**：旧报告称"PikPak 圆角偏小 6px 基础"，6px 是 Element Plus 的 `--el-border-radius-base`，但 PikPak **缩略图实际用 16px**，弹窗用 16px（`--el-dialog-border-radius: 16px`），只有 popover 用 8px。视觉上缩略图是大圆角的。

### 3.3 文件夹 3D 立体封面（PikPak 标志性设计）

来源：`li.grid.folder.row` > `.thumbnail` > `.folder-cover`

```
.folder-cover (120px * scale, position: relative)
├── .back    (absolute, cover-size, 3D 背层)
├── .cover   (absolute, margin-left:50% margin-top:3%, transform: translate(-50%,50%),
│             width: cover-size*0.75, height: width*9/16, border-radius:4px, bg:#d8dce0)
│   └── .el-image > img (文件夹内最新文件的预览图, object-fit: cover)
├── .front   (absolute, cover-size, 3D 前层)
│   ├── .sub-front (background: 文件夹 SVG 图标, center/100%)
│   └── .loading-animation
```

CSS 变量（来源：`.folder-cover`）：
```css
--cover-size: calc(120px * var(--scale));
--thumbnail-width: calc(var(--cover-size) * 0.75);   /* 封面"屏幕"占封面 75% */
--thumbnail-height: calc(var(--thumbnail-width) * 9 / 16);  /* 16:9 */
--icon-size: calc(36px * var(--scale));
```

**设计含义**：
- 文件夹不是扁平图标，而是**3D 立体封面**：back/front 两层构成文件夹"外壳"透视感，cover 层嵌入一张 16:9 预览图（文件夹内内容的缩略图）
- 无内容文件夹显示 `.placeholder`（灰色文件夹 SVG 图标，底色 `#d8dce0`）
- 加载中显示 `.loading-animation`
- `--folder-cover-scale: 1.15` 使文件夹比普通文件略大（视觉强调）

### 3.4 文件缩略图（视频/图片/其他）

来源：`li.grid.row` > `.thumbnail` > `.file-cover`

```
.file-cover (100% 宽高, flex-center)
├── .blur (absolute, background-image: 缩略图URL, filter: blur(15px))  /* 模糊背景填充 */
├── .el-image > img (实际缩略图, object-fit: contain)  /* 居中contain展示 */
├── .disable-drag
└── .play-icon (仅视频文件, 圆形播放按钮)
```

```css
.play-icon {
  border-radius: 50%;
  background: rgba(34, 34, 34, 0.7) no-repeat center / 70%;  /* 半透明深色圆 + 白色三角 */
}
```

**设计含义**：
- **模糊背景技术**：视频/文件缩略图用 `object-fit: contain` 居中展示原始比例，**背景用同一张图 `blur(15px)` 填充**，避免 letterbox 黑边，视觉更丰满
- **视频播放按钮**：视频文件缩略图上叠加半透明圆形播放按钮（rgba(34,34,34,0.7) + 白色三角 SVG），点击即播放
- 非媒体文件（未在快照中观察到具体形态，标注"未观察到"）

### 3.5 网格项信息区与操作区

来源：`.grid-operation` + `.grid-file-info`

```
li.grid.row
├── .thumbnail-wrap (16:9 缩略图)
│   └── .grid-operation (absolute 覆盖在缩略图上)
│       ├── .checkbox-wrap > .pp-checkbox (勾选框)
│       └── .pp-link-button > .pp-icon (更多菜单, transform: rotate(90deg))
└── .grid-file-info (margin-top: 8px)
    ├── .name (ellipsis, title 属性)
    └── .extra-info
        ├── 文件大小 (如 "18.7 MB", flex-shrink:0)  -- 仅文件有，文件夹无
        └── 日期 (如 "2022-07-11 20:19", ellipsis)
```

**设计含义**：
- 勾选框 + 更多按钮**叠加在缩略图右上角**（hover 显示，常态可能隐藏），不占额外空间
- 更多按钮图标旋转 90°（横向三点变竖向）
- 文件名下方显示大小 + 日期；**文件夹只显示日期不显示大小**（文件夹无固定大小语义）

---

## 4. PikPak 交互流程与操作模式

### 4.1 工具栏操作组（来源：`.file-operations`）

| 操作 | 元素 | 说明 |
|------|------|------|
| 清空回收站 | `display:none`（非回收站页隐藏） | 条件显示 |
| 本地上传 | `.upload-button` + 下拉 | 点击展开"上传文件 / 上传文件夹"二级菜单 |
| 新建文件夹 | icon + label | -- |
| 清除播放历史 | `display:none`（非历史页隐藏） | 条件显示 |
| 分隔线 | `.divider-in-operations` | -- |
| 更新（排序） | `.pp-link-button` + 下拉箭头 | 展开 6 种排序 + 文件夹优先开关 |
| 切换视图 | icon only (tooltip) | 网格/列表切换 |

**排序菜单**（来源：`.base-sorter` > `ul.base-sorter-shim`）：
- A-Z / Z-A / 更新（默认选中）/ 更旧 / 更大 / 更小
- **文件夹优先**：`el-switch is-checked`（开关，默认开启）-- 独立于排序的置顶选项

**上传菜单**（来源：`.upload-type`）：
- 上传文件（icon-file 图标）
- 上传文件夹

### 4.2 右键/更多菜单（来源：`.normal-menu`）

| 菜单项 | 图标色 | 说明 |
|--------|--------|------|
| 查看详细信息 | 默认 | -- |
| --- 分隔线 --- | | |
| 下载到 PC | 默认 | -- |
| 移动 | 默认 | -- |
| 复制 | 默认 | -- |
| 重命名 | 默认 | -- |
| --- 分隔线 --- | | |
| 删除 | `rgb(255, 102, 56)` 橙红 | 危险操作用警示色 |

**设计含义**：删除等危险操作用橙红色 `#ff663b`（`--color-error`）区分，非通用红色。

### 4.3 面包屑导航（来源：`.folder-navigator`）

```
.folder-navigator
├── ol.real (实际面包屑, 当前仅 "全部文件")
└── ol.shadow (阴影层, 用于溢出时的视觉处理, ellipsis)
```
- 双层 ol 结构：real 层是实际可点击面包屑，shadow 层处理宽度溢出时的省略
- 面包屑项用 `.pp-link-button`，支持 ellipsis

### 4.4 用户菜单（来源：`.pp-header-bar-avatar-popover`）

```
头像点击 -> popover (width: 320px)
├── .user-info (头像 + 用户名"零" + Premium 徽章 + 邮箱 + "会员剩余162天")
└── ul.user-nav
    ├── 账号与安全
    ├── 兑换码
    ├── 帮助中心 (新窗口)
    ├── 意见与反馈
    └── 退出登录
```

### 4.5 传输浮窗（来源：`.transfer`）

- 右下角固定浮窗 `.transfer-entry`（`icon-transfer` 图标）
- 点击展开 `.transfer-content`（上传/下载任务列表，快照中 `display:none` 未展开）
- 支持折叠/展开两种状态

### 4.6 拖拽能力（来源：DOM 事件属性）

- 文件项 `draggable="true"`，`.thumbnail-wrap` 有 `data-allow-drag="true"`
- 存在 `.swipe-select-box`（`data-v-ee3e9aee`）-- **滑选多选**机制：拖拽空白处画框批量选中文件
- 文件名 `data-allow-drag="false"` -- 文件名区域禁止拖拽（避免误触选中文字）

### 4.7 响应式机制（来源：底部 script）

```js
document.documentElement.style.fontSize = window.innerWidth / 360 + 'px'
document.documentElement.style.setProperty('--vh', window.innerHeight + 'px')
```
- 以 360px 为设计基准宽度，动态计算 `font-size` 实现 rem 响应式缩放
- `--vh` 自定义视口高度变量（解决移动端 100vh 问题）

---

## 5. PikPak 视觉系统（CSS 变量实勘）

### 5.1 色彩 Token（来源：`:root` 与内联 CSS 变量）

| Token | 值 | 用途 |
|-------|-----|------|
| `--color-primary` | `#306eff` | 主操作色（按钮/链接/激活图标） |
| `--color-primary-hover` | `#1a5eff` | 悬停态 |
| `--color-primary-active` | `#175cff` | 按下态 |
| `--color-primary-disabled` | `rgba(48,110,255,.5)` | 禁用态 |
| `--color-primary-text` | `#000` | 主色按钮上的文字 |
| `--color-secondary-text` | `#666` | 次文字 |
| `--color-tertiary-text` | `#98a2ad` | 三级文字/占位 |
| `--color-hover` | `#f2f3f4` | 通用悬停背景 |
| `--color-active` | `#f2f3f4` | 通用激活背景 |
| `--color-error` | `#ff663b` | 错误/危险（橙红） |
| `--color-premium` | `#d1ae6a` | 会员金色 |

**关键结论**：
- 主色蓝色 `#306eff`（深饱和蓝），**非紫色**
- 浅色优先：`color-scheme: light`，背景白/极浅灰
- 文字黑灰三层级：`#000` / `#666` / `#98a2ad`

### 5.2 圆角体系（来源：CSS 规则）

| 元素 | 圆角 | 来源 |
|------|------|------|
| 缩略图 `.thumbnail` | 16px | `.thumbnail { border-radius: 16px }` |
| 文件夹封面 `.cover` | 4px | `.folder-cover .cover { border-radius: 4px }` |
| 弹窗 | 16px | `--el-dialog-border-radius: 16px` |
| 气泡 popover | 8px | `--el-popover-border-radius: 8px` |
| Element Plus 基础 | 6px | `--el-border-radius-base: 6px` |

⚠️ **修正旧报告**：PikPak 圆角并非"偏小 6px"，而是**分层圆角体系**：缩略图/弹窗用大圆角 16px（媒体感），内部元素/基础控件用 6px（工具感），popover 用 8px。

### 5.3 技术栈

- **Element Plus**（CSS 变量 `--el-` 前缀，组件 `el-button`/`el-scrollbar`/`el-carousel`/`el-switch`/`el-image`/`el-popper` 等）
- **Vue 3**（`data-v-*` scoped 样式标识，`<!--v-if-->` 注释）
- 自定义图标系统 `.pp-icon`（CSS 变量 `--icon-color` / `--icon-color-hover` 驱动，SVG inline）

---

## 6. 差距清单：星云盘现状 vs PikPak

> 对照 `st-web/src` 实际代码与 PikPak HTML 实证。差距按 UI/UX 维度而非仅颜色。

### 6.1 文件呈现范式（最大差距）

| # | 维度 | 星云盘现状（FileGrid.tsx） | PikPak 实际 | 差距等级 |
|---|------|---------------------------|------------|---------|
| D1 | 网格布局 | Tailwind 断点式 `grid-cols-2~8` | 固定项宽 184px + shadow-li 测量自适应 | 中 |
| D2 | 缩略图宽高比 | 无强制比例（随图标/图片原始尺寸） | **强制 16:9**（padding-top: 56.25%） | 高 |
| D3 | 文件夹呈现 | 扁平 FileTypeIcon 图标 | **3D 立体封面**（back/cover/front 三层，嵌入内容预览图） | 高 |
| D4 | 视频缩略图 | 仅图片有缩略图，视频无 | 视频有缩略图 + **播放按钮叠加** | 高 |
| D5 | 缩略图背景 | 无（letterbox 留白） | **模糊背景填充**（同图 blur(15px) 填满） | 中 |
| D6 | 缩略图圆角 | rounded-lg（约 8-16px，待确认） | 16px | 低 |
| D7 | 文件信息区 | 名称 + 大小/日期（待确认具体布局） | 名称 + 大小(仅文件) + 日期，ellipsis | 低 |

### 6.2 侧边栏与信息架构

| # | 维度 | 星云盘现状（Sidebar.tsx） | PikPak 实际 | 差距等级 |
|---|------|---------------------------|------------|---------|
| D8 | 侧边栏底色 | `sidebar-gradient` 深色渐变 | 浅灰 `#f5f5f7` | 高 |
| D9 | 侧边栏文字 | `text-white/60`（白字） | 深色文字 `#666` / `#000` | 高 |
| D10 | 导航选中态 | `bg-primary-600/20` + `text-white` | **淡蓝底 `#e5ebff` + 蓝字 `#306eff`** | 高 |
| D11 | 导航项 | 首页/全部文件/收藏/分享/团队 + 分类(图片/视频/文档/音乐/压缩包) + 工具(回收站/重复/隐藏/传输/同步/管理) | 全部文件/最近添加/回收站/分享/播放历史/已加星标 | 中（IA 差异，非优劣） |
| D12 | 云下载入口 | 无（上传在侧边栏按钮） | 侧边栏顶部"云下载"主按钮（离线下载特性） | 中 |
| D13 | 存储用量展示 | 圆环进度（SVG circle, dashOffset） | 横向进度条 + 文字 "9.57 TB / 10 TB" + Premium 升级 | 低 |
| D14 | 多端下载入口 | 无 | 移动/桌面/扩展/TV 四入口 | 低 |

### 6.3 顶栏

| # | 维度 | 星云盘现状（TopBar.tsx） | PikPak 实际 | 差距等级 |
|---|------|---------------------------|------------|---------|
| D15 | 搜索快捷键 | 无显式提示 | placeholder "搜索文件 **Ctrl+F**" | 低 |
| D16 | 顶栏结构 | 搜索 + 用户菜单 + 通知 | Logo + 搜索 | 通知图标 + 会员 + 头像 + 设置 | 中（布局顺序差异） |
| D17 | 会员入口 | 无 | 顶栏 + 侧边栏双入口"Premium"金色 | 低（商业模式差异） |

### 6.4 交互模式

| # | 维度 | 星云盘现状 | PikPak 实际 | 差距等级 |
|---|------|-----------|------------|---------|
| D18 | 滑选多选 | `useDragSelect` hook 已有 | `.swipe-select-box` 画框批量选中 | 低（已具备） |
| D19 | 排序选项 | 待确认（FolderFilter store） | 6 种排序 + **文件夹优先开关**（默认开） | 中 |
| D20 | 上传入口 | 侧边栏按钮直接选文件 | 工具栏"本地上传"下拉 -> 上传文件/上传文件夹 | 中 |
| D21 | 右键菜单危险色 | 待确认 | 删除项用橙红 `#ff663b` 区分 | 低 |
| D22 | 传输浮窗 | `TransferFloatingWidget` 已有 | 右下角可折叠浮窗 | 低（已具备） |

### 6.5 视觉系统

| # | 维度 | 星云盘现状（index.css / themes.ts） | PikPak 实际 | 差距等级 |
|---|------|-------------------------------------|------------|---------|
| D23 | 默认主题色 | blue 主题 `#06A7FF`（偏青蓝） | `#306eff`（深饱和蓝） | 中 |
| D24 | 默认模式 | `system`（跟随系统） | 浅色优先（light） | 低 |
| D25 | 圆角体系 | 单一 token（待确认） | 分层：缩略图16px/弹窗16px/控件6px/popover8px | 中 |
| D26 | 文字层级 | fg/muted token（已三层） | `#000`/`#666`/`#98a2ad` | 低（已对应） |
| D27 | 顶栏分隔 | 待确认 | `box-shadow: 0 1px 0 rgba(0,0,0,.08)`（极淡投影非边框） | 低 |

---

## 7. 跨产品功能借鉴建议

> 以下基于 PikPak 实地观察，评估与星云盘定位（免费、私有化部署、个人+团队双场景）的契合度。

### 7.1 文件夹 3D 立体封面（契合度：高）
- **灵感来源**：PikPak `.folder-cover` back/cover/front 三层结构
- **可迁移核心体验**：文件夹不再是干瘪图标，而是"装着内容"的立体容器，预览图嵌入封面，浏览时一眼可知文件夹内容
- **最小落地形态**：文件夹 grid 项内嵌一张 16:9 内容预览图 + CSS 3D 透视层模拟文件夹外壳
- **注意**：需后端支持"文件夹代表图"接口（取文件夹内最新/首张文件的缩略图）

### 7.2 视频缩略图 + 播放按钮（契合度：高）
- **灵感来源**：PikPak `.file-cover` + `.play-icon`
- **可迁移核心体验**：视频文件直接显示帧缩略图 + 播放按钮，点击进入预览播放器
- **最小落地形态**：FileGrid 视频项显示缩略图 + 居中播放按钮，复用已有 PlyrPlayer
- **注意**：需后端支持视频帧缩略图生成

### 7.3 模糊背景填充技术（契合度：中）
- **灵感来源**：PikPak `.blur` 层 `filter: blur(15px)`
- **可迁移核心体验**：非全屏比例的缩略图用同图模糊填满背景，消除 letterbox 留白
- **最小落地形态**：FileThumbnail 增加模糊背景层

### 7.4 文件夹优先排序开关（契合度：中）
- **灵感来源**：PikPak 排序菜单底部 `el-switch is-checked`
- **可迁移核心体验**：排序时可选"文件夹始终置顶"，独立于排序字段
- **最小落地形态**：FileToolbar 排序下拉增加开关项

### 7.5 云下载/离线下载（契合度：低-中）
- **灵感来源**：PikPak 侧边栏"云下载"主按钮
- **可迁移核心体验**：粘贴链接离线下载到网盘
- **注意**：涉及版权/合规风险，私有化部署场景需评估，列为远期候选

---

## 8. 优先级建议

> 供 PM 评估立项参考，非最终决策。

| 优先级 | 建议项 | 对应差距 | 理由 |
|--------|--------|---------|------|
| P0 | 文件夹 3D 立体封面 + 内容预览 | D3 | 体验差异最大，视觉冲击最强，PikPak 标志性设计 |
| P0 | 视频缩略图 + 播放按钮 | D4 | 媒体云盘核心体验，星云盘已有 PlyrPlayer 可复用 |
| P1 | 强制 16:9 缩略图宽高比 | D2 | 统一视觉节奏的基础，3D 封面与播放按钮的前置条件 |
| P1 | 模糊背景填充 | D5 | 低成本消除留白，提升视觉丰满度 |
| P1 | 侧边栏浅色化 + 淡蓝选中态 | D8-D10 | 全局视觉基调转变，浅色优先 |
| P2 | 分层圆角体系 | D25 | 细节打磨，缩略图16px/控件6px |
| P2 | 主色调整为 #306eff 深饱和蓝 | D23 | 与 PikPak 一致性 |
| P2 | 文件夹优先排序开关 | D19 | 低成本交互增强 |
| P3 | 上传入口移至工具栏 + 文件/文件夹分离 | D20 | 操作位置调整 |
| P3 | 搜索快捷键提示 | D15 | 微优化 |

---

## 9. 调研局限与待补充

1. **单页快照**：仅"全部文件"页，播放器内页/分享页/设置页/移动端未覆盖
2. **动态交互未实操**：拖拽排序、滚动加载动画、3D 封面 hover 效果等仅从 CSS transition/DOM 事件推断，非实际操作录证
3. **列表视图未捕获**：快照为网格视图，PikPak 的列表/表格视图形态未观察到（切换视图按钮存在但状态未展开）
4. **空状态/error 状态未捕获**：快照为有数据状态，empty/error/loading 骨架屏形态未观察到
5. **非媒体文件呈现**：文档/压缩包等无缩略图文件的呈现形态，快照中仅有文件夹和视频，未观察到
6. **建议后续**：若立项，PM/UI 设计师阶段应补充列表视图、空状态、error 状态、非媒体文件呈现的实地观察（可用 in-app browser 实际访问）

---

## 10. 对既有文档的修正说明

> 本次调研发现 `.ai/docs/` 下既有 PikPak 相关文档存在与 HTML 实证矛盾的结论：

| 文档 | 错误结论 | HTML 实证修正 |
|------|---------|--------------|
| `pikpak-design-teardown.md` | 侧边栏选中态灰色 `#f2f3f4` | 实际淡蓝 `#e5ebff` + 蓝字 |
| `pikpak-design-teardown.md` | "圆角偏小 6px 基础" | 缩略图实际 16px，6px 仅是 Element Plus 基础值 |
| `pikpak-redesign-requirement.md` | "深色优先、紫蓝强调" | PikPak 实际**浅色优先、蓝色 #306eff** |
| `pikpak-redesign-phase1-design.md` | 默认模式改 dark、主题改 violet | 与 PikPak 实际方向相反（已浅色优先但主题仍待校正） |

**建议**：PM 评估立项时应以本报告 HTML 实证为准，既有需求/设计文档需 PM 重新评审定版后再进入开发。
