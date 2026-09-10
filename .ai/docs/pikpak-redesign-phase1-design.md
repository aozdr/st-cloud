# PikPak 风格重做 - 阶段1 技术设计（设计系统基座）

> 关联需求：pikpak-redesign-requirement.md
> 范围：仅阶段1，设计 token / 主题 / 布局 / 导航 / 基础组件 / 空状态

## 1. 改动文件范围（阶段1）

| 文件 | 改动 | 风险 |
|------|------|------|
| src/themes.ts | DEFAULT_THEME: blue -> violet | 低 |
| src/store/theme.ts | 默认模式: system -> dark | 低 |
| src/index.css | 深色 token 调深/中性化；移除暖石硬编码渐变；圆角增大 | 中 |
| .ulpi/design/DESIGN.md | 解锁 MEGA-红，重锁 PikPak 深色紫蓝 | 低 |
| .ulpi/design/frontend-redesign.md | 同步更新为新方向 | 低 |

## 2. 设计 Token 变更

### 2.1 默认主题 (themes.ts)
- DEFAULT_THEME: 'blue' -> 'violet'
- 紫蓝 primary-600 = 124 58 237 (#7C3AED)

### 2.2 默认模式 (theme.ts)
- loadMode() 默认: 'system' -> 'dark'
- 深色为默认体验

### 2.3 深色 token 调整 (index.css .dark)
当前深色已较接近 PikPak（bg #181A20），微调：
- bg: 24 26 32 -> 15 15 17 (更深，更中性，去蓝灰偏色)
- surface: 32 35 42 -> 22 22 26
- surface-2: 42 46 54 -> 32 32 38
- border: 52 56 66 -> 42 42 48
- fg/muted 保持（可读性已验证）

### 2.4 移除暖石硬编码
- .brand-gradient: #0c0a09 -> rgb(var(--bg))，渐变用 primary
- .sidebar-gradient: #1c1917 -> #0c0a09 -> 改用 token rgb(var(--bg)) 到 rgb(var(--surface))

### 2.5 圆角
- modal-content: rounded-2xl 保持
- 卡片类组件后续阶段统一调大

## 3. 不改动
- 组件架构（restyle in place，不重写组件）
- 路由/状态管理/业务逻辑
- 多主题/明暗模式架构本身

## 4. 验证
- npm run build 通过
- 默认深色+紫蓝生效
- 浅色模式仍一致
- 无暖石硬编码残留