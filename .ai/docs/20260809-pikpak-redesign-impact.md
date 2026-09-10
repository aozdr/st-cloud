# 影响分析报告 - PikPak 风格全站 UI/UX 重做（阶段1）

## 需求摘要

以 PikPak HTML 实证为依据，重做星云盘前端视觉系统（阶段1 纯前端）：蓝色主题调整为 #306eff、浅色默认、侧边栏浅色化+淡蓝选中态、文件缩略图 16:9+16px圆角+模糊背景填充、分层圆角体系、文件夹优先排序。详见 PRD + uiSpec。

## 影响范围

### Frontend

**直接修改（7 文件）**

| 文件 | 改动 | 影响面 |
|------|------|--------|
| `src/themes.ts` | blue 色阶替换为 #306eff 体系 | **全局**：blue 是 DEFAULT_THEME，所有页面视觉色变化 |
| `src/store/theme.ts` | loadMode 默认 system -> light | 全局默认明暗模式，新用户首屏变浅色 |
| `src/index.css` | 新增 --nav-active-bg；brand-gradient 移除 #0d0d0f 硬编码 | 全局 token + brand-gradient 消费者 |
| `src/components/layout/Sidebar.tsx` | navItemClass 浅色化 + 淡蓝选中态 | 全局侧边栏（所有受保护页面） |
| `src/components/layout/TopBar.tsx` | 搜索 placeholder + Ctrl+F | 全局顶栏 |
| `src/components/file/FileGrid.tsx` | aspect-video + rounded-2xl + 模糊背景 + 信息区 | 文件管理页网格视图 |
| `src/components/file/FileToolbar.tsx` | 文件夹优先开关 | 文件管理页工具栏 |

**间接受影响（brand-gradient 消费者，2 文件）**

| 文件 | 影响 | 处置 |
|------|------|------|
| `src/pages/HomePage.tsx:104` | brand-gradient 改 token 后视觉变化 | token 化自动适配，需视觉验证 |
| `src/pages/Login.tsx:52` | 同上 | token 化自动适配，需视觉验证 |

**新增（TECH_DESIGN 确认）**
- `src/components/ui/switch.tsx`（Radix Switch 封装，用于文件夹优先开关）-- 当前无此组件
- `src/store/folderFilter.ts` 可能新增 foldersFirst 字段（或 FileBrowser 本地 state）

### Backend
无（阶段1 纯前端）。阶段2 含文件夹代表图接口 + 视频帧提取，待阶段2 独立影响分析。

### Database
无（阶段1）。阶段2 可能新增缩略图缓存字段，待阶段2。

### Other
- **主题色全局变化**：blue 是默认主题，色阶从 #06A7FF(青蓝) 改为 #306eff(深饱和蓝)，全站所有用 primary-* 的组件视觉变化。这是**预期行为**，但需全站视觉一致性验证。
- **多主题架构不变**：red/emerald/violet/orange/teal 主题不受影响，仅 blue 色阶更新。
- **深色模式**：保留，浅色为默认。dark token 不变，--nav-active-bg 需补 dark 值。
- **文档影响**：`.ai/knowledge/ui-design-system.md`、`frontend.md` 需在 KNOWLEDGE 阶段同步更新。

## 风险等级

**Medium**

| 风险 | 等级 | 说明 |
|------|------|------|
| 蓝色色阶全局变化致视觉不一致 | 中 | token 驱动，编译验证全站 |
| brand-gradient 改动影响 Login/Home | 中 | token 化自动适配，需视觉验证两页 |
| 16:9 缩略图改变文件页布局节奏 | 中 | 阶段1 聚焦文件页，列数调至 7 列适配 |
| 新增 Switch 组件引入依赖 | 低 | Radix 已在栈 |
| 浅色/深色双模式一致性 | 中 | --nav-active-bg 双模式补值，编译验证 |

## 建议

1. **TECH_DESIGN 重点**：themes.ts 色阶重算的完整性（50-950 全覆盖）、--nav-active-bg 双模式 token、Switch 组件封装方案、foldersFirst 排序逻辑位置（store vs 本地）
2. **风险传递**：brand-gradient 消费者（HomePage/Login）需在实现后做视觉验证，纳入 EXP_ACCEPT
3. **范围控制**：阶段1 严格限制在 7 直接修改文件 + 2 间接验证文件，不扩散到次要页（阶段3）
4. **安全审查判定**：阶段1 为纯前端视觉变更，不涉及权限/文件操作/分享/配额，**SECURITY_REVIEW 可标 skipReason 跳过**（条件项）
