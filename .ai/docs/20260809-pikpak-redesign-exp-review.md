# 体验评审记录（EXP_DESIGN）- PikPak 风格重做阶段1

> 评审者：UI Designer & Reviewer
> 评审对象：PRD + uiSpec + 影响分析
> 依据：PikPak HTML 实证 + st-web 现状代码

## 评审结论：通过

uiSpec 覆盖完整，前端可直接实现，无需自行发挥。以下逐项确认。

## 1. UI 状态覆盖确认

| 状态 | uiSpec 覆盖 | 现状组件 | 结论 |
|------|-----------|---------|------|
| Loading | §7.1 骨架屏/Spinner + 缩略图占位淡入 | 现有机制 | 通过，保留 |
| Empty | §7.2 EmptyState SVG 插图 | EmptyState.tsx（用 primary token） | 通过，token 改后自动适配 |
| Error | §7.3 缩略图失败 fallback FileTypeIcon | 现有逻辑 | 通过，保留 |
| 选中态 | §7.4 ring-2 + bg-primary | FileGrid 现有 | 通过，保留 |
| Disabled | §7.5 opacity-50 | 现有 | 通过，保留 |

## 2. 页面覆盖确认

| 页面 | 阶段1 处置 | 依据 |
|------|-----------|------|
| 文件管理（网格视图） | 改（16:9/圆角/模糊背景/信息区） | uiSpec §5 核心 |
| 文件管理（表格视图） | 不改，token 自动适配 | uiSpec §10 明确边界 |
| 首页 HomePage | brand-gradient token 化，视觉验证 | 影响分析间接 |
| 登录页 Login | brand-gradient token 化，视觉验证 | 影响分析间接 |
| 侧边栏/顶栏 | 改（浅色/淡蓝/搜索提示） | uiSpec §3/§4 |
| 次要页（分享/管理/回收站等） | 阶段3，token 化后自然受益 | 定版决策 #4 |

## 3. 组件一致性确认

- EmptyState 用 `--color-primary-100~400`：蓝色色阶更新后自动适配，无需改组件 ✓
- FileTable/FileTableView：不改，token 驱动自动适配新蓝 ✓
- 所有 Dialog：圆角随体系（rounded-2xl 已用于 modal-content），逻辑不动 ✓
- 新增 Switch 组件：Radix 封装，与现有 ui/ 组件风格一致 ✓

## 4. 视觉规范完整性

- 蓝色色阶 50-950 完整 ✓
- --nav-active-bg 双模式（浅 #e5ebff / 深 primary 透明）✓
- 圆角体系分层明确（16/16/6/8）✓
- 缩略图 16:9 + 16px 圆角 + 模糊背景 ✓

## 5. 遗漏检查

无遗漏。uiSpec 已覆盖阶段1 全部核心交互与状态。表格视图不改是明确范围决策，非遗漏。

## 6. 传递给 TECH_DESIGN 的约束

1. Switch 组件需封装为 `src/components/ui/switch.tsx`，Radix Switch，样式对齐现有 ui/ 组件
2. foldersFirst 排序：建议 FileBrowser 本地 state（不污染 folderFilter store，该 store 当前仅服务搜索）
3. --nav-active-bg 需在 index.css :root 与 .dark 均定义
4. brand-gradient 改动后必须视觉验证 HomePage + Login
