# PikPak 设计拆解报告（需求发现 - 修正版）

> 调研动机：星云盘 UI/UX 重做以 PikPak 为参考标杆，需系统拆解其设计 DNA 作为重设计输入。
> 调研方法：**联网实勘** mypikpak.com，提取实际 CSS 变量、颜色、布局结构。非凭印象。
> 数据来源：PikPak 官网 mypikpak.com 页面源码 CSS 变量提取（2026-08-09）。

---

## 1. PikPak 产品定位与设计哲学

PikPak 是"云存储 + 媒体流式播放"混合定位产品，核心场景是**保存即观看**。

设计哲学：浅色优先、蓝色驱动、媒体内容为王。工具感与媒体感平衡--不像纯文件管理器那么枯燥，也不像纯媒体播放器那么花哨。

---

## 2. PikPak 真实设计系统（CSS 变量实勘）

### 2.1 色彩系统（来源：mypikpak.com CSS 变量）

**主色：蓝色（NOT 紫色）**

| CSS 变量 | 值 | 用途 |
|----------|-----|-----|
| --color-primary | #306eff | 主操作色（按钮/链接/激活） |
| --color-primary-active | #175cff | 按下态（更深蓝） |
| --color-primary-hover | #5286ff | 悬停态（更浅蓝） |
| --color-primary-disabled | rgba(48,110,255,.5) | 禁用态 |
| --color-primary-text | #000000 | 主色按钮上的文字（黑色） |

**文本层级**

| CSS 变量 | 值 | 用途 |
|----------|-----|-----|
| --color-primary-text | #000000 | 主文字 |
| --color-secondary-text | #666666 | 次文字 |
| --color-tertiary-text | #98a2ad | 三级文字/占位 |

**背景与状态**

| CSS 变量 | 值 | 用途 |
|----------|-----|-----|
| --color-active | #f2f3f4 | 激活/选中背景（侧边栏选中态） |
| --color-hover | #f8f8f8 | 悬停背景 |
| --color-error | #ff663b | 错误色（橙红） |
| --color-premium | #d1ae6a | 会员金色 |

**渐变（hero/banner 区专用）**

```
--linear-gradient: linear-gradient(122.65deg, rgba(66,68,69,.9) 3%, rgba(23,25,28,.9) 69%)
```
深色渐变，用于 hero banner 区，非全局背景。

### 2.2 布局参数（来源：CSS 变量）

| 变量 | 值 | 说明 |
|------|-----|-----|
| --header-height | 60px | 顶栏高度 |
| --footer-height | 107px | 页脚高度 |
| --pc-max-width | 1400px | PC 最大宽度 |
| --pc-middle-width | 1280px | PC 中等宽度 |
| --mobile-break-width | 768px | 移动端断点 |
| --el-border-radius-base | 6px | 基础圆角 |
| --el-dialog-border-radius | 16px | 弹窗圆角 |
| --el-popover-border-radius | 8px | 气泡圆角 |

### 2.3 关键设计结论

1. **浅色优先**：主背景接近白/极浅灰（#f8f8f8 / #f2f3f4 / #fcfdff），NOT 深色
2. **蓝色主色** #306eff，NOT 紫色。紫色 #7b38ff 在页面中存在但非主色
3. **侧边栏浅灰底**：选中态 --color-active #f2f3f4，悬停态 --color-hover #f8f8f8
4. **文字黑灰层级**：#000 / #666 / #98a2ad，NOT 白色系
5. **圆角偏小**：6px 基础（NOT 大圆角媒体感），16px 弹窗
6. **hero 区深色渐变**：banner 区用深色渐变，但全局是浅色
7. **Element Plus 组件库**：CSS 变量以 --el- 开头，基于 Element Plus

---

## 3. 差距清单：当前星云盘 vs PikPak（修正版）

| # | 维度 | 当前星云盘 | PikPak 实际 | 差距 |
|---|------|-----------|------------|------|
| G1 | 主色 | 红(默认蓝可切换) | 蓝 #306eff | 高-改默认主题为蓝 |
| G2 | 模式 | system(已误改dark) | 浅色优先 | 高-恢复浅色默认 |
| G3 | 侧边栏 | sidebar-gradient深色 | 浅灰 #f2f3f4 | 高-改浅灰 |
| G4 | 侧边栏文字 | text-white/60 | 深色文字 #666 | 高-改深色文字 |
| G5 | 文字层级 | fg/muted token | #000/#666/#98a2ad | 低-token已对应 |
| G6 | 圆角 | rounded-2xl(16px) | 6px基础 | 中-适度调小 |
| G7 | hero区 | brand-gradient深色 | 深色渐变(类似) | 低-已接近 |
| G8 | 文件呈现 | 图标+文件名为主 | 缩略图海报卡片 | 高-阶段2处理 |
| G9 | 媒体分类导航 | 二级 | 一级 | 中-阶段1可调 |

---

## 4. 修正后的设计 Token 方案

### 蓝色主题色阶（匹配 PikPak #306eff）

```
PikPak 实际值:
  primary:       #306eff = rgb(48, 110, 255)
  primary-hover: #5286ff = rgb(82, 134, 255)  
  primary-active:#175cff = rgb(23, 92, 255)

构建完整色阶 (50-950):
  50:  235 244 255   #EBF4FF
  100: 214 231 255   #D6E7FF
  200: 173 206 255   #ADCEFF
  300: 122 173 255   #7AADFF
  400: 82 134 255    #5286FF  (hover)
  500: 48 110 255    #306EFF  (primary)
  600: 23 92 255     #175CFF  (active)
  700: 19 76 214     #134CD6
  800: 17 64 178     #1140B2
  900: 14 52 143     #0E348F
  950: 9 33 90       #09215A
```

### 侧边栏
- 背景：浅灰 #f2f3f4 或 white
- 选中态：#f2f3f4
- 悬停态：#f8f8f8
- 文字：#666（次）/ #000（主）

### 默认模式
- 浅色优先（light），保留 dark 可切换

---

## 5. 优先级建议（三阶段 - 修正版）

| 阶段 | 范围 | 对应差距 |
|------|------|---------|
| 阶段1 设计系统基座 | 蓝色主题/浅色默认/浅灰侧边栏/圆角调整 | G1-G7,G9 |
| 阶段2 核心文件页 | FileGrid海报卡/FileTable/Preview沉浸/首页 | G8 |
| 阶段3 次要页 | 分享/团队/管理/回收站/搜索/传输管理适配 | 全站一致性 |

---

## 6. 前次错误修正说明

> 前版拆解报告基于模型印象，未联网实勘，存在重大错误：
> - 错误声称"深色优先" -> 实际浅色优先
> - 错误声称"紫蓝强调色" -> 实际蓝色 #306eff  
> - 错误声称"深色侧边栏" -> 实际浅灰侧边栏
> - 错误声称"大圆角媒体感" -> 实际 6px 小圆角
>
> 已基于 mypikpak.com CSS 变量实勘全部修正。阶段1 代码改动同步纠正。