# PikPak 风格全站 UI/UX 重做 - UI/UX 设计文档（uiSpec）

> 关联：`.ai/docs/20260809-pikpak-redesign-requirement.md`（PRD）
> 依据：`.ai/docs/pikpak-discovery-report.md`（HTML 实证，所有设计决策可追溯至 PikPak 实际 DOM/CSS）
> 阶段：UI 与 PM 协作初稿，待需求评审多方会议定版
> 原则：**前端照此实现即可，不需自行发挥**。所有 Tailwind 类、色值、尺寸均为最终实现值。

---

## 1. 设计方向定调

| 维度 | 决策 | 依据（HTML 实证） |
|------|------|------------------|
| 明暗 | 浅色优先（light 默认），保留 dark 切换 | PikPak `color-scheme: light`，白/浅灰底 |
| 主色 | `#306eff` 深饱和蓝 | PikPak `--color-primary: #306eff` |
| 侧边栏 | 浅灰底 + 深色文字 | PikPak `.sidebar { background: #f5f5f7 }` |
| 选中态 | 淡蓝底 `#e5ebff` + 蓝字 | PikPak `.nav a.active { color: var(--color-primary); background: #e5ebff }` |
| 缩略图比例 | 强制 16:9 | PikPak `.thumbnail-wrap { padding-top: 56.25% }` |
| 缩略图圆角 | 16px | PikPak `.thumbnail { border-radius: 16px }` |
| 缩略图背景 | 模糊填充（同图 blur） | PikPak `.blur { filter: blur(15px) }` |
| 圆角体系 | 分层：缩略图16px/弹窗16px/控件6px/popover8px | PikPak CSS 实勘 |

---

## 2. 视觉规范（Design Tokens）

### 2.1 蓝色主题色阶（替换 themes.ts 中 blue 主题）

PikPak 实际值：primary `#306eff` = rgb(48,110,255)，hover `#5286ff`=rgb(82,134,255)，active `#175cff`=rgb(23,92,255)。

围绕 #306eff 构建 50-950 完整色阶（RGB 空格分隔，适配现有 token 格式）：

```
'50':  '235 244 255'   /* #EBF4FF */
'100': '214 231 255'   /* #D6E7FF */
'200': '173 206 255'   /* #ADCEFF */
'300': '122 173 255'   /* #7AADFF */
'400': '82 134 255'    /* #5286FF  hover */
'500': '48 110 255'    /* #306EFF  primary */
'600': '23 92 255'     /* #175CFF  active */
'700': '19 76 214'     /* #134CD6 */
'800': '17 64 178'     /* #1140B2 */
'900': '14 52 143'     /* #0E348F */
'950': '9 33 90'       /* #09215A */
preview: '#306EFF'
```

### 2.2 侧边栏选中态淡蓝

新增语义 token（index.css :root）：
```css
--nav-active-bg: 229 235 255;   /* #e5ebff 淡蓝底 */
```
dark 模式对应：
```css
--nav-active-bg: 48 110 255;    /* 用 primary 透明感，rgba(48,110,255,0.15) */
```

### 2.3 圆角体系

| 用途 | 值 | Tailwind 类 |
|------|-----|------------|
| 缩略图/卡片封面 | 16px | `rounded-2xl` |
| 弹窗 modal | 16px | `rounded-2xl`（已用） |
| 气泡 popover | 8px | `rounded-lg` |
| 按钮等基础控件 | 6px | `rounded-md` |

### 2.4 文字层级

| 层级 | 浅色 | 深色 | 用途 |
|------|------|------|------|
| 主文字 | `#000` | 现有 fg | 文件名、标题 |
| 次文字 | `#666` | 现有 muted | 日期、大小、辅助信息 |
| 三级文字 | `#98a2ad` | 现有 muted 更弱 | 占位符、禁用态 |

现有 token 已三层对应，仅需确认 muted 在浅色下接近 `#666`。

---

## 3. 侧边栏设计（Sidebar.tsx）

### 3.1 布局
- 宽度：保持 200px（PikPak 实际 200px，现状 lg:w-64=256px，**调整为 w-52 即 208px 或保持 256px**；评审确认。建议保持现状 256px，PikPak 200px 偏窄）
- 背景：浅色用 `--surface`（白）或 `--surface-2`（浅灰）；**采用 surface-2 浅灰**对齐 PikPak `#f5f5f7`
- 移除 `sidebar-gradient` 类中的深色逻辑，改为 `bg-surface-2 border-r border-border`

### 3.2 导航项样式

```tsx
// 替换现有 navItemClass
const navItemClass = (isActive: boolean) =>
  cn(
    'group relative flex items-center gap-3 px-3 py-2.5 rounded-lg text-[13px] font-medium transition-colors duration-200 cursor-pointer',
    collapsed && 'lg:justify-center lg:px-0',
    isActive
      ? 'bg-[rgb(var(--nav-active-bg))] text-primary-600'   // 淡蓝底 + 蓝字
      : 'text-muted hover:text-fg hover:bg-surface'          // 深灰字 + 悬停浅底
  );
```

**关键变化**：
- 选中态：`text-white bg-primary-600/20` -> `text-primary-600 bg-[rgb(var(--nav-active-bg))]`（深色字蓝 + 淡蓝底，非白字）
- 非选中：`text-white/60 hover:text-white hover:bg-white/5` -> `text-muted hover:text-fg hover:bg-surface`（深灰字，非白字）

### 3.3 存储用量区
- 现状用圆环进度（SVG circle）。**保留圆环设计**（PikPak 用横向条，但圆环信息密度更高，且已实现，不必照搬）。仅适配浅色 token。

### 3.4 信息架构
- 保留现状导航分组（主导航/分类/工具），不照搬 PikPak 的 IA（星云盘有团队/同步/重复检测等 PikPak 没有的功能）。仅视觉适配。

---

## 4. 顶栏设计（TopBar.tsx）

### 4.1 搜索框
- placeholder 增加快捷键提示：`搜索文件 Ctrl+F`（PikPak 实证 D15）
- 实际绑定 Ctrl+F 快捷键聚焦搜索框（增强）

### 4.2 顶栏分隔
- 现状用 border-b。PikPak 用 `box-shadow: 0 1px 0 rgba(0,0,0,0.08)` 极淡投影。**保持 border-b border-border**（token 化，效果接近，不必照搬投影技巧）。

### 4.3 结构
- 保留现状（搜索 + 用户菜单 + 通知）。PikPak 有会员入口，星云盘无会员体系，不照搬。

---

## 5. 文件网格设计（FileGrid.tsx）- 核心

### 5.1 网格容器
- 现状：`grid grid-cols-2 sm:grid-cols-3 ... 2xl:grid-cols-8 gap-3`（断点式）
- PikPak：固定项宽 184px + shadow-li 测量自适应
- **决策**：**保持断点式网格**（已实现，响应式可靠），不引入 shadow-li 测量机制（增加复杂度，收益有限）。调整列数适配 16:9 比例后更大的项：
```tsx
'grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 2xl:grid-cols-7 gap-4'
```

### 5.2 网格项卡片

```tsx
<div
  className={cn(
    'group relative flex flex-col rounded-2xl p-2 cursor-pointer select-none transition-[background-color,box-shadow] duration-200',
    isSelected
      ? 'bg-primary-500/10 ring-2 ring-primary-400'
      : 'hover:bg-surface-2',
    cutIds?.has(file.id) && 'opacity-50',
  )}
>
```
- 卡片圆角 `rounded-xl` -> `rounded-2xl`（16px，对齐 PikPak 缩略图圆角）
- 选中态保留 ring（视觉清晰）

### 5.3 缩略图容器（核心变更）

现状：`aspect-square w-full rounded-lg`（1:1 正方形，8px 圆角）
改为：

```tsx
<div className="relative aspect-video w-full rounded-2xl overflow-hidden mb-2 bg-surface-2">
  <GridThumbnail file={file} />
  {/* 操作按钮区 */}
</div>
```
- `aspect-square` -> `aspect-video`（16:9，对齐 PikPak `padding-top: 56.25%`）
- `rounded-lg` -> `rounded-2xl`（16px）
- 增加 `bg-surface-2` 占位底色（对齐 PikPak `background: #e1e6ea`）

### 5.4 模糊背景填充（GridThumbnail 增强）

现状 GridThumbnail 仅显示 FileTypeIcon 或图片。增加模糊背景层：

```tsx
function GridThumbnail({ file }: { file: FileNode }) {
  // ... 现有 url 加载逻辑 ...
  return (
    <div className="absolute inset-0">
      {/* 模糊背景层：图片文件用同图 blur 填充，非图片用类型色块 */}
      {img && url && (
        <img
          src={url}
          alt=""
          aria-hidden
          className="absolute inset-0 w-full h-full object-cover blur-xl scale-110 opacity-60"
        />
      )}
      {/* 前景图标/图片 */}
      <div className={cn('absolute inset-0 flex items-center justify-center', loaded ? 'opacity-0' : 'opacity-100')}>
        <FileTypeIcon config={config} size="xl" isFolder={file.nodeType === 0} suffix={file.suffix} />
      </div>
      {img && url && (
        <img
          src={url}
          alt={file.name}
          className={cn('absolute inset-0 w-full h-full object-contain transition-opacity duration-300', loaded ? 'opacity-100' : 'opacity-0')}
          loading="lazy"
          draggable={false}
          onLoad={() => setLoaded(true)}
        />
      )}
    </div>
  );
}
```
- 模糊背景：`blur-xl scale-110 opacity-60`（对齐 PikPak `filter: blur(15px)`，scale 防边缘透出）
- 前景：`object-contain` 居中展示原始比例（对齐 PikPak `.el-image img { object-fit: contain }`）
- 非图片文件：无模糊层，仅 FileTypeIcon 居中（类型色块+图标）

### 5.5 视频播放按钮（阶段2，阶段1 预留）

阶段1 不实现（需后端帧缩略图）。阶段2 增加视频缩略图 + 居中播放按钮：
```tsx
{isVideo(file) && thumbnailUrl && (
  <button className="absolute inset-0 flex items-center justify-center">
    <span className="w-12 h-12 rounded-full bg-black/50 flex items-center justify-center">
      <Play className="w-5 h-5 text-white" fill="currentColor" />
    </span>
  </button>
)}
```

### 5.6 文件信息区

```tsx
<div className="px-1">
  <div className={cn('text-xs leading-tight truncate', isSelected ? 'text-primary-600 font-medium' : 'text-fg')} title={file.name}>
    {file.name}
  </div>
  <div className="text-[11px] text-muted truncate flex items-center gap-1.5">
    {file.nodeType === 1 && <span className="flex-shrink-0">{formatSize(file.size)}</span>}
    <span className="truncate">{formatDate(file.updatedAt)}</span>
  </div>
</div>
```
- 文件名：左对齐（现状居中），`text-fg`（主文字，非 muted）
- 文件夹只显示日期不显示大小（对齐 PikPak：文件夹 extra-info 无 size）
- 文件显示大小 + 日期（对齐 PikPak D7）

### 5.7 操作按钮
- 保留现状（勾选框左上、收藏右上、底部 hover 操作组）。PikPak 勾选+更多在右上角，但星云盘已有多按钮设计，保留不动，仅圆角随体系调整。

---

## 6. 工具栏设计（FileToolbar.tsx）

### 6.1 排序增强
现状：Select 下拉（名称/大小/修改时间）+ 升降序按钮。
PikPak：popover 菜单 6 项排序 + 文件夹优先开关。

**决策**：保留现有 Select + 升降序设计（已实现，可靠），**新增"文件夹优先"开关**：

```tsx
{!hasSelection && view !== 'table' && (
  <div className="flex items-center gap-1.5">
    {/* 现有 Select 排序 + 升降序按钮 */}
    <label className="flex items-center gap-1 text-xs text-muted cursor-pointer select-none">
      <Switch checked={foldersFirst} onCheckedChange={onToggleFoldersFirst} />
      <span>文件夹优先</span>
    </label>
  </div>
)}
```
- 文件夹优先默认开启（对齐 PikPak `el-switch is-checked`）
- 排序逻辑：先按 foldersFirst 过滤（文件夹置顶），再按 sortBy + sortDir 排序

### 6.2 上传入口
- 保留现状（侧边栏 + 工具栏上传按钮）。PikPak 工具栏"本地上传"下拉分文件/文件夹，星云盘已支持，不强制照搬。

---

## 7. 状态设计

### 7.1 Loading
- 文件网格加载：保留现有骨架屏/Spinner
- 缩略图加载：FileTypeIcon 占位 -> 图片淡入（现有 transition-opacity 保留）
- PikPak 有 `.loading-animation`，星云盘现有占位机制足够

### 7.2 Empty
- 保留现有 `EmptyState` 组件（SVG 插图），适配浅色 token

### 7.3 Error
- 缩略图加载失败：fallback 到 FileTypeIcon（现有逻辑保留）

### 7.4 选中态
- 网格项：`bg-primary-500/10 ring-2 ring-primary-400`（保留）
- 表格行：保留现有高亮

### 7.5 Disabled
- 按钮：保留 `disabled:opacity-50 disabled:cursor-not-allowed`

---

## 8. 响应式与适配

- 网格列数断点保持（2/3/4/5/6/7）
- 移动端侧边栏抽屉保留
- PikPak 用 rem + `--vh` 动态缩放，星云盘用 Tailwind 断点式，**不引入 rem 缩放**（体系不同，收益有限）

---

## 9. 阶段1 改动文件清单（实现依据）

| 文件 | 改动 | 优先级 |
|------|------|--------|
| `src/themes.ts` | blue 色阶替换为 #306eff 体系 | P0 |
| `src/store/theme.ts` | loadMode 默认 system -> light | P0 |
| `src/index.css` | 新增 --nav-active-bg token；brand-gradient 移除 #0d0d0f 硬编码 | P0 |
| `src/components/layout/Sidebar.tsx` | navItemClass 浅色化 + 淡蓝选中态；sidebar-gradient -> bg-surface-2 | P0 |
| `src/components/layout/TopBar.tsx` | 搜索 placeholder 加 Ctrl+F；Ctrl+F 快捷键绑定 | P1 |
| `src/components/file/FileGrid.tsx` | aspect-square->aspect-video；rounded-lg->rounded-2xl；信息区左对齐+大小日期 | P0 |
| `src/components/file/FileGrid.tsx` (GridThumbnail) | 增加模糊背景层 | P0 |
| `src/components/file/FileToolbar.tsx` | 新增文件夹优先开关 | P1 |

---

## 10. 不改动（明确边界）

- FileTable（表格视图）-- 阶段1 不改，保持现状
- 所有 Dialog 组件（分享/移动/重命名等）-- 仅圆角随体系，逻辑不动
- 预览播放器 PlyrPlayer -- 不动
- 管理面板/团队/同步/重复检测/隐藏文件页 -- 阶段3 适配，阶段1 不动
- 后端全部 -- 阶段1 不涉及
- 路由/状态管理 -- 不动

---

## 11. 待评审确认点

1. 侧边栏宽度：保持 256px 还是改 208px 对齐 PikPak 200px
2. 网格列数：16:9 后项变大，列数是否从 8 列降到 7 列
3. 文件信息区：左对齐（本方案）还是保持居中（现状）
4. 阶段1 是否含次要页（分享/管理/回收站等）视觉适配

---

## 12. 需求评审多方会议定版记录

> 评审参与：PM + UI + 前端 + 后端 + 测试
> 结论：**通过定版**，PRD + uiSpec 成为前端实现唯一依据

### 12.1 五方审查结论

| 视角 | 结论 | 要点 |
|------|------|------|
| 产品 | 通过 | 方向基于 HTML 实证，分阶段合理，功能完整性保留 |
| UI | 通过 | uiSpec 给出明确 Tailwind 类映射，前端可直接实现 |
| 前端 | 通过（附约束） | Tailwind v3.4 aspect-video/rounded-2xl 可用；需新增 Switch 组件（Radix 已在栈）；folderFilter store 需加 foldersFirst 字段——均为 TECH_DESIGN 细节，非阻塞 |
| 后端 | 通过 | 阶段1 不涉及后端；阶段2 文件夹代表图/视频帧提取接口待阶段2 技术设计 |
| 测试 | 通过（附补充） | 验收标准可测试；视觉验收用 DOM/CSS 检查 + 编译（无图像识别），补充：缩略图比例/圆角/色值用元素检查验证 |

### 12.2 待确认点定版决策

| # | 待确认点 | 定版决策 | 理由 |
|---|---------|---------|------|
| 1 | 侧边栏宽度 | 保持 256px | 现状可靠，PikPak 200px 偏窄，不改 |
| 2 | 网格列数 | 降到 7 列（2xl:grid-cols-7） | 16:9 项变大，7 列视觉节奏更佳 |
| 3 | 文件信息区 | 左对齐 | 对齐 PikPak，信息阅读自然 |
| 4 | 阶段1 范围 | 含核心页（文件管理/首页/侧边栏/顶栏），次要页（分享/管理/回收站/搜索/团队/同步/传输/重复/隐藏）阶段3 | 核心页先见效，次要页 token 化后自然受益 |

### 12.3 定版声明

PRD（`.ai/docs/20260809-pikpak-redesign-requirement.md`）+ uiSpec（本文档）经多方评审定版，为前端实现唯一依据。前端不得自行发挥设计，偏离文档即为 blocker。
