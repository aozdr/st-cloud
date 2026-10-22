# UI 评审基准（现行）

`ui-review` 的基准路径由此文件承接。现行实现与评审以以下文件为准：

1. `st-web/src/design/tokens.md`：布局、颜色、对比度与例外白名单。
2. `.ai/knowledge/ui-design-system.md`：历史演进说明；其中与 `tokens.md` 冲突的数值（如 240 / 68 / 64、`#9AA1AE`）以 `tokens.md` 为准。
3. `.ai/design/UI_DESIGN_SPEC.md`：V2.0 历史方案，仅用于追溯，不再作为实现约束。

## 现行基线

- 布局：Sidebar 224 / TopBar 60 / 表头 40 / 行 52 / 主按钮 36。
- 主色：`#4F6EF7`；文本主色 `primary-strong`（light `primary-700`，dark `primary-400`）。
- 实心主按钮：`primary-700` + 白字。
- 禁止：`bg-white`、`stone-*`、`hover:bg-muted`、`text-primary-600` 作为正文/链接。

## 评审口径

- 偏离 `tokens.md` 的布局、token 或状态设计：BLOCK。
- 文件类型图标色、`FileTypeIcon` 固定色、语义状态色：允许例外。
- 无法用真实浏览器验证时，必须在评审记录中标注限制，不得声称已通过视觉验收。
