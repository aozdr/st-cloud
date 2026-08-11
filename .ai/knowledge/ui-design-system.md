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
- **主色**：蓝色 #306eff（PikPak `--color-primary`），构建 50-950 完整色阶
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
