# 星云盘 UI 落地契约（20260820-ui-refactor）

> 依据 `UI_DESIGN_SPEC.md` v1.0。本文定义 Token 映射与组件规格，实现时以本文 + 规范原文为准。所有值为强制约束，缺参时取相邻 Token，不自行创造新值。

## 1. Design Token 映射

### 1.1 主色

保持 Tailwind `primary-50..950` 体系，重映射为规范品牌色：

| 色阶 | 值 | 用途 |
|------|-----|------|
| 600 | #4F6EF7 | 主色 / 主按钮 / 选中态 / 链接 / 复选框 |
| 700 | #4563E6 | hover |
| 800 | #3D59D1 | active |
| 100 | #EEF2FF | primary-light / 激活导航底 |

`themes.ts` 默认主题改为规范主色（建议 key `indigo`，preview #4F6EF7）。

### 1.2 中性色（light 默认）

| Token | 值 | 对应现状 |
|-------|-----|---------|
| --bg-page | #F7F8FC | bg |
| --bg-subtle | #F4F6FA | surface-2 |
| --bg-hover | #F7F9FD | 新增 |
| --bg-active | #EEF2FF | 新增 |
| --border | #E8EBF1 | border |
| --border-light | #EEF0F4 | 新增 |
| --text-primary | #1F2430 | fg |
| --text-secondary | #697386 | muted |
| --text-tertiary | #929AAA | 新增 |
| --text-disabled | #B8BEC9 | 新增 |

### 1.3 语义色

| Token | 值 | light 底 |
|-------|-----|---------|
| success | #35A56A | #EAF8F0 |
| warning | #E6A23C | #FFF6E5 |
| danger | #E05252 | #FFF0F0 |
| info | #4F6EF7 | #EEF2FF |

### 1.4 圆角 / 阴影 / 尺寸

- radius：xs 6 / sm 8 / md 10 / lg 12 / xl 14 / 2xl 16 / round 999
- shadow：sm `0 1px 2px rgba(31,36,48,.04)`；md `0 4px 16px rgba(31,36,48,.06)`；lg `0 12px 32px rgba(31,36,48,.10)`
- `--sidebar-width: 240px`、`--header-height: 68px`

### 1.5 字体

Inter 优先，回退 `-apple-system / BlinkMacSystemFont / Segoe UI / PingFang SC / Microsoft YaHei / sans-serif`。`index.html` 引入 Inter（离线回退系统字体）。

## 2. 布局

- AppLayout：flex 100vw/100vh；Sidebar + Main(Header + Content)
- Sidebar：240px、白底、右 1px #E8EBF1 边框、padding 20/16；Brand 40px；导航项 40px 高、padding 0 12px、圆角 8px、gap 12px；Normal #697386 / Hover #F4F6FA+#1F2430 / Active #EEF2FF+#4F6EF7（无粗左边条）；底部 Storage Summary 卡片（#F7F8FC、圆角 12px、6px 圆角进度条、fill #4F6EF7）
- Header：68px、白底、下边框 #EEF0F4、padding 0 32px；搜索框 320×40、圆角 10px、底 #F4F6FA、focus 白底 + #C9D2FF 边框 + 3px rgba(79,110,247,.10) ring；右侧 Notification/Help/User
- Content：padding 28px 32px 40px、bg #F7F8FC、overflow-y auto

## 3. 文件页

- 面包屑：32px 高、13px；当前段 #1F2430/500，普通段 #929AAA，分隔符 #B8BEC9、margin 0 8px
- PageHeader：24px/32px/600 标题 + 13px #929AAA 描述 + 右侧主按钮
- Toolbar：40px、margin-top 20 / margin-bottom 16；左侧 Select/Sort，右侧 View Switcher/More
- 文件列表容器：白底、1px #E8EBF1、圆角 14px、overflow hidden、无阴影
- 表头：44px、padding 0 20px、底 #FCFCFD、下边框 #EEF0F4；文字 12px/500/#929AAA；列 Name/Modified/Owner/Size（按 P3 决策）
- 行：64px、padding 0 20px、grid `minmax(300px,1fr) 180px 140px 100px`、gap 16px
- FileIcon：40×40、圆角 10px；文件夹 #FFF5D9/#E8A93A，PDF #FFF0F0/#E05252，Image #EEF2FF/#4F6EF7，Document #EDF7FF/#4C91D7，Spreadsheet #EAF8F0/#35A56A
- 行交互：normal #FFF / hover #F8FAFF / selected #F1F4FF；禁止行变蓝、位移、缩放、明显动画
- Checkbox：16×16、圆角 4px、normal #CDD2DC 边框、checked #4F6EF7
- 空状态：56px 图标 / 16px 标题 / 13px 描述，不做大插画
- 骨架屏：行高 64px、#F0F2F6、1500~1800ms ease-in-out

## 4. 通用组件

- Button：variant primary/secondary/ghost/danger，size sm/md/lg；md 40px 高、padding 0 16px、圆角 9px；primary #4F6EF7 → hover #4563E6 → active #3D59D1；禁巨型 CTA/渐变/发光
- Input：圆角 8~10px、focus ring 同搜索框
- Modal：480px、圆角 16px、shadow-lg、overlay rgba(15,20,30,.35)；header 24/24/16、body 0 24 24、footer 16 24 24；支持 Escape
- ContextMenu：180px、padding 6、圆角 10px、shadow-md；项 36px 高、padding 0 10px、圆角 7px；danger #E05252
- UploadPanel：虚线 #C9D2FF 边框、#F8FAFF 底、圆角 12px、hover #F1F4FF + #4F6EF7
- Skeleton：行级骨架优先，不用整页 spinner

## 5. 响应式

- ≥1280px：完整 240px 侧栏
- 1024~1279px：侧栏 208px；隐藏 Owner 列与部分 metadata；内容横向 padding 缩减
- <1024px：侧栏收起为图标导航；列表列 Name/Modified/Actions，隐藏 Owner/Size
- <768px：移动布局（底部导航/抽屉）；行 = Icon + Name + Metadata + More
- 现有 MobileTabBar / ActionSheet / 安全区适配保留

## 6. 图标与动画

- 统一 lucide-react（stroke 1.8~2）；禁 emoji 作为 UI 图标
- 动画 120~180ms ease-out；禁 hover 位移/缩放、弹跳、持续浮动、粒子
- 尊重 prefers-reduced-motion（现有全局规则保留）

## 7. 可访问性

- 键盘可操作；Button 有可访问名称；icon-only 按钮提供 aria-label
- Modal 支持 Escape；焦点环 `0 0 0 3px rgba(79,110,247,.16)`
- 颜色不作为唯一状态表达；文件列表支持键盘导航

## 8. 验收自检（UI Review Contract 摘要）

按 Layout / Spacing / Typography / Color / Component / Interaction 六维自检，输出 P0~P3 分级清单；P0/P1 必须清零后方可交付。
