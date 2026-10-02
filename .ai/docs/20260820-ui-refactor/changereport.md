# Change Report（20260820-ui-refactor）

## 变更摘要

按 UI_DESIGN_SPEC v1.0 重构 st-web 视觉体系与文件管理界面：

1. Design Token：主色收敛为 #4F6EF7（indigo 默认主题），中性色/语义色/圆角/阴影按规范重映射；默认浅色；字体 Inter。
2. 布局：Sidebar 240px 白底 + 线性存储卡；TopBar 68px + 320px 规范搜索框。
3. 文件模块：FileBrowser 页面结构（Breadcrumb → PageHeader → Toolbar → 白卡列表）；list 视图四列 64px 行；grid 视图规范卡片；两视图收敛（删除 FileTable.tsx）。
4. 通用组件：按钮/弹窗（480px）/右键菜单/空态（56px 图标）/骨架屏/类型图标色对齐规范。
5. 页面：首页去渐变/发光/缩放；FileManager 增加「我的文件」页头。

## 验证

- npm run build PASS、npm run lint 0 error、dev server 冒烟 200。
- 无头 Chrome 量化验收 18 项全部符合规范（testreport.md）。

## 未改动

- 后端、数据库、接口契约、桌面端主进程、移动端布局逻辑（MobileTabBar/ActionSheet 保留）。
