# UI Design System

## 目的

统一星云盘前端页面体验，避免不同功能开发产生视觉和交互差异。

## UI评审基础规范

### 页面布局

- 页面需要保持清晰的信息层级
- 主要操作必须容易发现
- 避免信息堆叠

### 状态设计

所有页面需要考虑：

- Loading
- Empty
- Error
- Success
- Disabled

### 文件管理体验

重点关注：

- 文件列表效率
- 搜索入口
- 批量操作
- 上传下载反馈
- 最近访问体验

### 组件一致性

新增组件前优先复用已有组件。

评审关注：

- 按钮
- 表单
- 弹窗
- 列表
- 卡片
- 导航


## PikPak 风格设计规范（2026-08-09 重做）

> 依据：`.ai/docs/pikpak-discovery-report.md`（HTML 实证）、`.ai/docs/20260809-pikpak-redesign-uispec.md`

### 设计方向

- **浅色优先**：默认 light 模式（PikPak `color-scheme: light`），保留 dark 切换
- **主色**：天蓝系（`--color-primary-500: 6 167 255` ≈ #06a7ff，
  `--color-primary-600: 0 147 224` ≈ #0093e0），构建 50-950 完整色阶
  （PikPak 蓝 #306eff 为历史方案，2026-08 视觉迭代后已替换为当前 token，UI 一律使用 token 不写死旧值）
- **侧边栏**：浅灰底 `bg-surface-2` + 深色文字，选中态淡蓝底 `--nav-active-bg` (#e5ebff 浅色 / primary 深色)
- **缩略图**：强制 16:9（`aspect-video`）+ 16px 圆角（`rounded-2xl`）+ 模糊背景填充（同图 `blur-xl`）
- **圆角体系**：分层--缩略图/弹窗 16px (`rounded-2xl`) / 控件 6px (`rounded-md`) / popover 8px (`rounded-lg`)

### 文件网格规范

- 缩略图容器：`aspect-video w-full rounded-2xl overflow-hidden bg-surface-2`
- 图片缩略图：模糊背景层（`object-cover blur-xl scale-110 opacity-60`）+ 前景 `object-contain` 居中
- 非图片：FileTypeIcon 居中，无模糊层
- 文件信息区：左对齐，文件名 + 大小(仅文件) + 日期
- 网格列数：2/3/4/5/6/7（2xl 7 列）

### 排序规范

- 排序字段：名称/大小/修改时间 + 升降序
- **文件夹优先**开关（默认开启），独立于排序字段
- foldersFirst 为 FileBrowser 本地 state（localStorage 持久化）

### 组件

- `Switch`（`components/ui/switch.tsx`）：轻量自定义开关，无需 Radix switch 依赖
- `EmptyState`：SVG 插图用 primary token，主题色变化自动适配

### 颜色硬编码禁令

- 禁止在组件/样式中硬编码深色（如 `#0d0d0f`），一律用 `rgb(var(--bg))` 等 token
- brand-gradient 等渐变背景用 token 驱动，确保明暗模式自适应

## UI_DESIGN_SPEC v1.0 视觉契约（2026-08-20 重做，现行基准）

> 依据用户文档 `UI_DESIGN_SPEC.md`（Figma 风格契约），替代 PikPak 天蓝方案为现行视觉基准。实现细则见 `.ai/docs/20260820-ui-refactor/uispec.md`。

### 设计方向

- **现代 SaaS / Cloud Drive**：明亮、干净、轻量、大面积留白、低对比度边框、白色内容卡 + 浅灰页面背景
- **默认浅色**：默认 `light`，保留 dark 切换（dark 独立定义 Token，禁止 invert）
- **主色固定 #4F6EF7**：`primary-600=#4F6EF7`、hover `#4563E6`（700）、active `#3D59D1`（800）、light `#EEF2FF`（100）；主题切换保留但默认主题为 `indigo`
- **字体 Inter**（回退 PingFang SC / 微软雅黑）

### 布局常量

- 侧栏 `--sidebar-width: 240px`（白底，导航激活 `#EEF2FF` 底 + 主色文字，无粗左边条）
- 顶栏 `--header-height: 68px`（下边框 `#EEF0F4`，搜索框 320×40 r10）
- 内容边距 `28px 32px 40px`，页面底色 `#F7F8FC`

### 文件列表规范

- 容器：白底 + 1px `#E8EBF1` + r14，无阴影
- 表头 44px（底 `#FCFCFD`，12px/500/`#929AAA`），列 Name/Modified/Owner/Size
- 行 64px，hover `#F8FAFF`、selected `#F1F4FF`；禁止位移/缩放动画
- 复选框 16×16 r4；FileIcon 40×40 r10（类型底色按 §24）
- 视图：**list + grid 两视图**（2026-08-20 收敛，删除 card 视图）
- 空态 56px 图标；骨架屏行高 64px

### 通用组件

- 主按钮 40px/r9/`#4F6EF7`；弹窗 480px/r16/shadow-lg；右键菜单 180px/r10/项 36px/r7
- 动画 120~180ms ease-out；禁 hover 位移/缩放、渐变、发光、emoji 图标
