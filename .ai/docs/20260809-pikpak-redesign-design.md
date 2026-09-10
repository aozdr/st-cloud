# 技术设计 - PikPak 风格全站 UI/UX 重做（阶段1）

> 关联：PRD（20260809-pikpak-redesign-requirement.md）、uiSpec（20260809-pikpak-redesign-uispec.md）、影响分析（20260809-pikpak-redesign-impact.md）、体验评审（20260809-pikpak-redesign-exp-review.md）
> 阶段：阶段1 纯前端视觉系统，后端无改动
> 依据：uiSpec 是唯一设计依据，本文档为实现层面技术方案

---

## 1. 背景与目标

以 PikPak HTML 实证为依据，重做星云盘前端视觉系统。阶段1 聚焦纯前端：蓝色主题 #306eff、浅色默认、侧边栏浅色化+淡蓝选中态、文件缩略图 16:9+16px圆角+模糊背景填充、分层圆角、文件夹优先排序。不涉及后端、数据模型、路由、状态管理架构。

---

## 2. 架构设计

无架构变更。沿用现有：React 18 + TypeScript + Vite + Tailwind v3.4 + Radix UI + Zustand。改动均为 token/样式/组件 UI 层，不动路由/状态/API 层。

---

## 3. 前端设计

### 3.1 蓝色主题色阶替换（themes.ts）

将 blue 主题 shades 替换为 #306eff 体系（uiSpec §2.1 已给完整 50-950 值）。preview 改 `#306EFF`。DEFAULT_THEME 保持 `'blue'`（不改 key，只改色阶值，避免破坏用户已存的主题偏好 key）。

```ts
{
  key: 'blue',
  label: '蓝色',
  shades: {
    '50':  '235 244 255',
    '100': '214 231 255',
    '200': '173 206 255',
    '300': '122 173 255',
    '400': '82 134 255',
    '500': '48 110 255',   /* #306EFF primary */
    '600': '23 92 255',    /* #175CFF active */
    '700': '19 76 214',
    '800': '17 64 178',
    '900': '14 52 143',
    '950': '9 33 90',
  },
  preview: '#306EFF',
},
```

### 3.2 默认浅色模式（store/theme.ts）

`loadMode()` 默认值 `'system'` -> `'light'`。注释更新为"PikPak 风格浅色优先"。已保存偏好的用户不受影响（localStorage 优先）。

### 3.3 设计 Token（index.css）

**新增 --nav-active-bg**（:root 浅色 + .dark 深色）：
```css
:root {
  --nav-active-bg: 229 235 255;   /* #e5ebff 淡蓝底 */
}
.dark {
  --nav-active-bg: 48 110 255;    /* primary，搭配 /15 透明度使用 */
}
```

**brand-gradient 移除 #0d0d0f 硬编码**（HomePage.tsx:104 + Login.tsx:52 的消费者自动受益）：
```css
.brand-gradient {
  background:
    radial-gradient(ellipse at 20% 0%, rgb(var(--color-primary-500) / 0.15) 0%, transparent 50%),
    radial-gradient(ellipse at 80% 100%, rgb(var(--color-primary-500) / 0.10) 0%, transparent 50%),
    rgb(var(--bg));   /* #0d0d0f -> rgb(var(--bg))，跟随明暗模式 */
}
```
> 注意：brand-gradient 用于 Home/Login 的 hero 区。浅色模式下 bg 为浅色，渐变叠加 primary 透明色，需视觉验证不显突兀（纳入 EXP_ACCEPT）。

### 3.4 侧边栏（Sidebar.tsx）

`navItemClass` 替换（uiSpec §3.2）：
```ts
const navItemClass = (isActive: boolean) =>
  cn(
    'group relative flex items-center gap-3 px-3 py-2.5 rounded-lg text-[13px] font-medium transition-colors duration-200 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
    collapsed && 'lg:justify-center lg:px-0',
    isActive
      ? 'bg-[rgb(var(--nav-active-bg))] text-primary-600'
      : 'text-muted hover:text-fg hover:bg-surface'
  );
```
aside className 中 `sidebar-gradient` -> `bg-surface-2 border-r border-border`（浅灰底 + 右分隔线）。Logo 区/存储区文字随 token 自动适配。

### 3.5 顶栏（TopBar.tsx）

搜索 input placeholder 增加快捷键提示：`搜索文件...` -> `搜索文件 Ctrl+F`。
新增 Ctrl+F 全局快捷键绑定聚焦搜索框（useEffect 监听 keydown，仅在非 input 聚焦时触发，避免冲突）。

### 3.6 文件网格（FileGrid.tsx）- 核心

**缩略图容器**（第145行）：
```tsx
// aspect-square(1:1) -> aspect-video(16:9)，rounded-lg(8px) -> rounded-2xl(16px)
<div className="relative aspect-video w-full rounded-2xl overflow-hidden mb-2 bg-surface-2">
```

**GridThumbnail 增加模糊背景层**：
```tsx
return (
  <div className="absolute inset-0">
    {/* 模糊背景层：图片文件用同图 blur 填充，消除 letterbox 留白 */}
    {img && url && (
      <img src={url} alt="" aria-hidden
        className="absolute inset-0 w-full h-full object-cover blur-xl scale-110 opacity-60" />
    )}
    {/* 占位图标 */}
    <div className={cn('absolute inset-0 flex items-center justify-center transition-opacity duration-300', loaded ? 'opacity-0' : 'opacity-100')}>
      <FileTypeIcon config={config} size="xl" isFolder={file.nodeType === 0} suffix={file.suffix} />
    </div>
    {/* 前景图：contain 居中展示原始比例 */}
    {img && url && (
      <img src={url} alt={file.name} loading="lazy" draggable={false}
        className={cn('absolute inset-0 w-full h-full object-contain transition-opacity duration-300', loaded ? 'opacity-100' : 'opacity-0')}
        onLoad={() => setLoaded(true)} />
    )}
  </div>
);
```

**文件信息区**（uiSpec §5.6）：居中 -> 左对齐，增加大小/日期：
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
> 需确认 FileNode 是否有 updatedAt 字段及 formatDate 工具（实现时核对 types.ts/utils.ts）。

**网格列数**：`2xl:grid-cols-8` -> `2xl:grid-cols-7`（16:9 项变大，定版决策 #2）。

**卡片圆角**：`rounded-xl` -> `rounded-2xl`（对齐缩略图 16px）。

### 3.7 文件夹优先排序（FileToolbar.tsx + FileBrowser.tsx）

**新增 Switch 组件**（`src/components/ui/switch.tsx`，自定义实现，不引入 Radix switch 依赖）：
```tsx
import { cn } from '../../lib/utils';
interface SwitchProps { checked: boolean; onCheckedChange: (v: boolean) => void; }
export function Switch({ checked, onCheckedChange }: SwitchProps) {
  return (
    <button role="switch" aria-checked={checked} onClick={() => onCheckedChange(!checked)}
      className={cn('relative inline-flex h-5 w-9 items-center rounded-full transition-colors',
        checked ? 'bg-primary-600' : 'bg-border')}>
      <span className={cn('inline-block h-4 w-4 transform rounded-full bg-white transition-transform',
        checked ? 'translate-x-4' : 'translate-x-0.5')} />
    </button>
  );
}
```

**FileToolbar** 新增 foldersFirst prop + Switch（uiSpec §6.1）：
```tsx
<label className="flex items-center gap-1 text-xs text-muted cursor-pointer select-none whitespace-nowrap">
  <Switch checked={foldersFirst} onCheckedChange={onToggleFoldersFirst} />
  <span>文件夹优先</span>
</label>
```

**FileBrowser** 排序逻辑：foldersFirst 默认 true（useState），排序时先分离文件夹/文件，foldersFirst 时文件夹置顶，再各自按 sortBy+sortDir 排序。foldersFirst 为本地 state（不污染 folderFilter store，该 store 仅服务搜索，符合 EXP_DESIGN 约束 #2）。

---

## 4. 后端设计

无（阶段1 纯前端）。

---

## 5. 数据设计

无（阶段1）。

---

## 6. 性能与安全

**性能**：
- 模糊背景层增加一个 img 元素，但复用同一 url（浏览器缓存，无额外请求）。blur-xl 是 GPU 加速，性能可接受。
- 16:9 比例缩略图比 1:1 略大，单屏项数减少（8->7列），DOM 节点数略减，性能不退化。
- contentVisibility: 'auto'（现有）保留，虚拟滚动效果不变。

**安全**：
- 阶段1 纯前端视觉变更，不涉及权限校验/文件操作/分享/配额等云盘安全敏感逻辑。
- 据影响分析判定：**SECURITY_REVIEW 标 skipReason 跳过**（条件项，无安全敏感逻辑）。

---

## 7. 修改文件范围

| # | 文件 | 改动类型 | 风险 |
|---|------|---------|------|
| 1 | `src/themes.ts` | 色阶值替换 | 低 |
| 2 | `src/store/theme.ts` | 默认值 system->light | 低 |
| 3 | `src/index.css` | 新增 token + brand-gradient 改 | 中（影响 Home/Login） |
| 4 | `src/components/layout/Sidebar.tsx` | navItemClass + aside class | 低 |
| 5 | `src/components/layout/TopBar.tsx` | placeholder + Ctrl+F | 低 |
| 6 | `src/components/file/FileGrid.tsx` | 16:9 + 圆角 + 模糊背景 + 信息区 + 列数 | 中 |
| 7 | `src/components/file/FileToolbar.tsx` | foldersFirst Switch | 低 |
| 8 | `src/components/file/FileBrowser.tsx` | foldersFirst 排序逻辑 | 低 |
| 9 | `src/components/ui/switch.tsx` | 新增组件 | 低 |

间接验证（不改代码）：`src/pages/HomePage.tsx`、`src/pages/Login.tsx`（brand-gradient 消费者）。

---

## 8. 测试方案

- **编译验证**：`npm run build` 通过（TypeScript + Vite）
- **ESLint**：`npx eslint` 改动文件无 error
- **视觉验证**（无图像识别，用 DOM/CSS 检查）：
  - themes.ts 蓝色 500 = 48 110 255
  - FileGrid 缩略图容器含 aspect-video + rounded-2xl
  - GridThumbnail 含 blur-xl 模糊层
  - Sidebar navItemClass 含 nav-active-bg + text-primary-600
  - index.css brand-gradient 无 #0d0d0f
- **功能回归**：文件管理（上传/下载/移动/复制/重命名/删除/分享）、收藏、回收站、搜索功能不受影响
- **双模式**：浅色/深色切换均正常，--nav-active-bg 双模式生效

---

## 9. 实现顺序（子任务拆分）

1. **Token 层**（themes.ts + theme.ts + index.css）-- 基座，先做
2. **Switch 组件**（ui/switch.tsx）-- 独立，可与 1 并行
3. **布局层**（Sidebar.tsx + TopBar.tsx）-- 依赖 1 的 token
4. **文件网格**（FileGrid.tsx）-- 依赖 1 的 token
5. **工具栏+排序**（FileToolbar.tsx + FileBrowser.tsx）-- 依赖 2 的 Switch
6. **编译验证 + 视觉检查** -- 全部完成后
