# st-web 现行设计 Token（v3 基线）

> 本文件是 2026-10-17 用户确认后的实现基线，优先级高于 `.ai/design/UI_DESIGN_SPEC.md` V2.0。
> V2.0 的 `#5865F2 / 240 / 64` 保留为历史方案，不再作为实现或评审依据。

## 布局

| 项 | 值 |
|---|---|
| Sidebar 展开 / 折叠 | 224px / 64px |
| TopBar | 60px，white，下边框 `border-light` |
| 文件表头 / 行高 | 40px / 52px |
| 文件页内容内边距 | 20px（移动）/ 32px（桌面） |
| 主按钮 | 36px 高、8px 圆角 |
| 弹窗 | 16px 圆角、`shadow-float` |

## 颜色

| 语义 | Light | Dark |
|---|---|---|
| brand / primary-600 | `#4F6EF7` | `#4F6EF7` |
| 文本主色 primary-strong | primary-700 `#4563E6` | primary-400 `#798FFC` |
| page bg | `#F7F8FA` | `#111216` |
| surface | `#FFFFFF` | `#1B1D22` |
| surface-2 | `#F6F7F9` | `#22252B` |
| fg | `#20242D` | `#EAECF0` |
| muted | `#687080` | `#9DA3AF` |
| tertiary | `#657184`（白 4.94:1 / surface-2 4.61:1） | `#8D94A0`（5.52:1） |
| border / border-light | `#E8EAF0` / `#F0F1F4` | `#2B2E35` |
| success / warning / danger | `#35A56A` / `#E6A23C` / `#E05252` | `#34D399` / `#FBBF24` / `#F87171` |

## 使用规则

1. 正文/链接/选中文件名/图标强调色使用 `text-primary-strong`，不再使用 `text-primary-600`。
2. 实心主按钮使用 `primary-700` 底 + 白字（7 套主题均 ≥5:1）；纯图标、进度条、装饰块可保留 `primary-600`。
3. 次级按钮 hover 使用 `bg-surface-2`，不得使用 `hover:bg-muted`。
4. 12px 辅助信息只能使用 `text-muted` 或 `text-tertiary`（已满足 4.5:1）；不得使用 `text-disabled` 作为正文。
5. 禁止新增 `bg-white`、`stone-*` 等绕过主题的视觉类；文件类型图标色、`FileTypeIcon` 固定色、语义状态色是唯一例外。
6. 页面背景用 `bg-bg`/`bg-surface`，区域背景用 `bg-surface-2`；不要用灰色大容器包裹 PageHeader。

## 对比度基线

按 WCAG 2.1 相对亮度计算，正文阈值 4.5:1，大文本/图标 3:1：

- `primary-700` 白字：7 套主题 5.02–7.10。
- `primary-strong` light on white：5.02+；dark on `surface`：4.99+。
- `text-tertiary` light on white：4.94；light on `surface-2`：4.61；dark on `surface`：5.52。
- `primary-600` 白字仅 4.28（默认主题），不得再用于含文字的实心按钮。
