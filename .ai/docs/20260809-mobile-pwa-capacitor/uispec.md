# UI 设计文档 - 星云盘移动端

> 输出标准：`docs/newList/ai-ui-design-document-standard.md`
> 与需求文档（requirement.md）同步产出，经需求评审定版后为前端实现唯一依据。
> 落盘路径：`.ai/docs/20260809-mobile-pwa-capacitor/uispec.md`
> 遵守项目规范，参考 `.ai/knowledge/ui-design-system.md`

# 一、页面定位

## 页面名称

星云盘移动端(响应式 + Capacitor 壳)

## 用户目标

用户在手机上单手完成文件浏览、下载、分享、上传等核心操作,获得接近原生 App 的体验,无需安装即可用(PWA),亦可包装为 APK 分发。

# 二、设计方向

```
设计风格：PikPak 风媒体云盘(延续现有 st-web 设计语言)
参考产品：PikPak / 阿里云盘 / 百度网盘 App
视觉关键词：单手可达 / 触控优先 / 卡片化 / 底部导航 / 安全区适配
```

核心原则:**不新建独立移动设计语言,而是将现有桌面设计在 md 断点下重排为移动布局**,复用全部 token / 主题 / 明暗模式 / 组件库。

# 三、页面结构设计

## 桌面端(md 以上,保持现状)

- Header:TopBar(搜索 + 用户菜单),h-14
- Navigation:左侧固定 Sidebar(可折叠),w-64/w-16
- Main Content:Outlet 路由内容区
- Footer:无

## 移动端(md 以下,新增)

```
┌─────────────────────────────┐
│ TopBar(h-14)                │ ← 抽屉触发☰ + 搜索图标 + 头像
│                             │
│ Main Content(可滚动)        │ ← 单列卡片/列表,内容区底部留 pb-20
│                             │
│                             │
├─────────────────────────────┤
│ BottomTab(pb-safe)          │ ← 首页/文件/传输/我的,h-16
└─────────────────────────────┘
```

- **Header**:复用 TopBar,移动端隐藏搜索框(桌面版居中搜索框 `hidden sm:block`),显示搜索图标按钮(已有 `sm:hidden`);左侧增加抽屉触发按钮(已有 `onMenuClick`)
- **Navigation**:桌面 Sidebar 在移动端为抽屉(`fixed -translate-x-full` 已实现),新增**底部 Tab** 作为主导航
- **Main Content**:Outlet 内容区,底部增加 `pb-20` 避让底部 Tab
- **Footer**:无

## 底部 Tab 导航(新增组件 `MobileTabBar`)

| Tab | 图标 | 路由 | 说明 |
|-----|------|------|------|
| 首页 | Home | `/` | 仪表盘 |
| 文件 | FolderClosed | `/files` | 全部文件 |
| 传输 | ArrowUpDown | `/transfers` | 传输管理(Capacitor/Electron 下显示;纯浏览器下显示 Web 上传进度) |
| 我的 | User | 抽屉/我的 | 触发顶部抽屉(收藏/分享/团队/回收站/管理/主题/存储) |

> "我的"Tab 点击弹出抽屉(Sidebar 复用),收纳次级导航,避免底部 Tab 过载。

# 四、信息层级设计

## 一级信息

- 当前页面内容(文件列表/搜索结果/首页卡片)
- 底部 Tab 当前选中态

## 二级信息

- 顶部搜索入口
- 用户头像/菜单
- 文件操作 ActionSheet

## 三级信息

- 抽屉内次级导航(收藏/分享/团队/回收站/管理)
- 存储空间环形进度

# 五、组件设计

| 组件 | 用途 | 交互 |
|------|------|------|
| MobileTabBar(新增) | 底部主导航 | 点击切换路由,选中态 primary 图标 + 标签;固定底部 pb-safe |
| ActionSheet(新增) | 文件操作菜单 | 长按文件触发,底部滑出,半透明遮罩点击关闭 |
| MobileFileCard(改造 FileGrid) | 单列文件卡片 | 缩略图左 + 信息右 + 更多按钮;长按触发 ActionSheet |
| MobileBreadCrumb(改造) | 移动路径导航 | 返回箭头 + 当前文件夹名,省略中间路径 |
| 抽屉(复用 Sidebar) | 次级导航 | 已有 `mobileOpen` + backdrop,底部 Tab"我的"触发 |

# 六、交互设计

- **长按替代右键**:文件卡片长按 500ms 触发 ActionSheet(打开/下载/分享/重命名/移动/收藏/删除)
- **点击进入**:单击文件夹进入下级,单击文件预览
- **底部 ActionSheet**:从底部滑入,操作项纵向列表,危险操作(删除)红色,遮罩点击或下滑关闭
- **移动端禁用键盘快捷键**:`useFileKeyboard` 快捷键在 `isMobile()` 时不绑定;`?` 快捷键帮助弹窗移动端隐藏
- **拖拽改点击**:桌面端拖拽移动文件,移动端改为 ActionSheet 内"移动到"
- **下拉刷新**(可选):文件列表下拉刷新数据
- **loading**:列表骨架屏(shimmer,已有);ActionSheet 弹出动画
- **empty**:复用 EmptyState(SVG 插图 primary token)
- **error**:toast 提示,网络错误友好文案
- **success**:下载完成 toast + 系统通知(Capacitor)

# 七、响应式设计

- **PC(md+)**:左侧 Sidebar + 顶部搜索框,多列网格(2/3/4/5/6/7 列),右键菜单,键盘快捷键
- **平板(sm-md)**:可折叠侧栏,2-3 列网格,保留部分桌面交互
- **移动端(<md,768px)**:底部 Tab + 抽屉,单列卡片,长按 ActionSheet,隐藏快捷键,安全区适配

断点:以 Tailwind `md`(768px)为移动/桌面分界,与现有 Sidebar 的 `lg:static` 协调(现有侧栏在 lg 以下为抽屉,需统一为 md 断点)。

# 八、视觉规范

> 完全复用现有 token,不新增颜色/字体。参考 `.ai/knowledge/ui-design-system.md`

## 颜色

- 主色:`rgb(var(--color-primary-600))`,底部 Tab 选中态
- 背景:`rgb(var(--bg))` 主背景,`rgb(var(--surface))` 卡片/Tab 栏
- 文字:`rgb(var(--fg))` 主文字,`rgb(var(--muted))` 辅助
- 危险:`red-500` 删除按钮

## 字体

- 复用 'Plus Jakarta Sans' / 'PingFang SC' / 'Microsoft YaHei'
- 文件名:`text-sm font-medium`
- 辅助信息(大小/日期):`text-xs text-muted`

## 间距

- 页面边距:`px-4`(移动端)
- 卡片间距:`gap-2`
- 底部 Tab 高度:`h-16` + `pb-safe`
- 触控热区:最小 44×44px(`min-h-[44px]`)

## 圆角与阴影

- 复用现有:缩略图/弹窗 `rounded-2xl`(16px),控件 `rounded-md`(6px),popover `rounded-lg`(8px)
- ActionSheet:`rounded-t-2xl` + `shadow-float`
- 底部 Tab 栏:`border-t border-border`,无圆角

# 九、组件复用分析

- **已有组件复用**:Sidebar(抽屉模式已实现)、TopBar(搜索图标按钮已实现)、EmptyState、Toast、Dialog、FileGrid(改造为单列)、FileTypeIcon、PreviewModal
- **新增组件**:MobileTabBar(底部 Tab)、ActionSheet(底部操作菜单)
- **抽象建议**:ActionSheet 可抽象为通用底部弹层组件,复用于文件操作/排序选择/批量操作

# 十、实现建议

```
技术方案：
- 响应式:Tailwind md 断点,移动端默认单列,md+ 恢复桌面布局
- 环境检测:新增 isMobile()(matchMedia md 断点)与 isCapacitor(),与 isElectron() 并列
- PWA:vite-plugin-pwa,manifest + workbox precache App Shell
- Capacitor:@capacitor/core + cli,新增 capacitor.config.ts + src/lib/capacitor.ts

组件拆分：
- components/layout/MobileTabBar.tsx(新增底部 Tab)
- components/ui/ActionSheet.tsx(新增底部操作菜单)
- AppLayout.tsx 改造:md 以下渲染 MobileTabBar,隐藏桌面 Sidebar 交互
- FileGrid.tsx 改造:md 以下单列卡片布局
- ContextMenu.tsx 改造:移动端长按触发 ActionSheet

状态管理：
- 新增 useMobile hook(基于 matchMedia),供组件判断移动端
- 路由不变,MobileTabBar 复用 NavLink

接口依赖：
- 后端零改动,复用现有 /api 全部接口
```
