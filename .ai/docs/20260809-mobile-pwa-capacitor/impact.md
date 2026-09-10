# 影响分析报告 - 星云盘移动端

> 归属标准：IMPACT_ANALYSIS（dependsOn: REQ_ANALYSIS）
> 落盘路径：`.ai/docs/20260809-mobile-pwa-capacitor/impact.md`
> 输入：Loop State 快照 + artifacts.prd + artifacts.uiSpec + 代码扫描

## 需求摘要

为星云盘实现移动端:PWA 基座 + st-web 响应式改造 + Capacitor Android 壳,企业内部分发,不依赖 Android Studio。后端零改动,复用 REST + JWT。

## 影响范围

### Frontend(st-web,主要影响区)

**新增文件**
- `src/components/layout/MobileTabBar.tsx` - 底部 Tab 导航
- `src/components/ui/ActionSheet.tsx` - 底部操作菜单(替代右键菜单)
- `src/lib/capacitor.ts` - Capacitor 环境检测 + 原生桥降级(与 `electron.ts` 并列)
- `src/hooks/useMobile.ts` - 移动端断点判断(matchMedia md)
- `public/manifest.webmanifest` + 图标(由 vite-plugin-pwa 生成)
- `capacitor.config.ts` - Capacitor 配置
- `android/` - Capacitor 生成的 Android 工程(命令行管理)

**改造文件(按影响聚集分类)**

布局与导航(核心):
- `src/components/layout/AppLayout.tsx` - md 以下渲染 MobileTabBar,主内容区加 `pb-20` 避让
- `src/components/layout/Sidebar.tsx` - 断点从 lg 统一到 md;"我的"Tab 触发抽屉;移动端隐藏折叠按钮/存储环形图(降级为抽屉内简化版)
- `src/components/layout/TopBar.tsx` - 已有 `sm:hidden` 搜索图标,确认移动端可见性

文件组件簇(交互重做):
- `src/components/file/ContextMenu.tsx` - 移动端长按触发 ActionSheet 而非右键菜单
- `src/components/file/FileBrowser.tsx` - `useFileKeyboard` 移动端禁用;拖拽改 ActionSheet
- `src/components/file/FileGrid.tsx` - md 以下单列卡片;长按触发
- `src/components/file/FileTable.tsx` / `FileTableView.tsx` - 移动端表格改卡片列表或横向滚动
- `src/components/file/BlankContextMenu.tsx` - 移动端空白菜单适配

页面响应式(18 页面,按复杂度):
- 重型(需 IA 重排):`TeamSpacePage`(36KB)、`SearchPage`(23KB)、`TransferManager`(21KB)、`HomePage`(19KB)、`ShareAccessPage`(17KB)、`SyncPage`(16KB)、`RecycleBin`(16KB)、`ShareManagePage`(12KB)
- 轻型(间距/触控):`Login`、`FileManager`、`FavoritesPage`、`CategoryPage`、`AdminPage`、`DuplicateFilesPage`、`HiddenFilesPage`、`ServerConfigPage`、`TeamPage`、`TeamInvitePage`

环境检测降级(14 文件引用 isElectron):
- `useUpload.tsx`、`useFileKeyboard.ts`、`Sidebar.tsx`、`auth.ts`、`transfer.ts`、`server-config.ts`、`Login.tsx`、`DownloadDialog.tsx`、`TransferManager.tsx`、`FileBrowser.tsx`、`SyncPage.tsx`、`VersionHistoryDialog.tsx`、`types/index.ts`
- 新增 `isCapacitor()` 后,上传/下载/同步的降级链需扩展为 Capacitor > Electron > Web

构建配置:
- `vite.config.ts` - 加 vite-plugin-pwa 插件 + manifest + workbox 配置
- `package.json` - 新增依赖(vite-plugin-pwa、@capacitor/core、@capacitor/cli、@capacitor/filesystem、@capacitor/share、@capacitor/camera、@capacitor/status-bar、@capacitor/splash-screen)
- `index.html` - 补 apple-touch-icon / maskable icon meta(已有 viewport-fit/ theme-color)

### Backend(st-api)

**零代码改动**。仅运维配置:
- `stcloud.cors.allowed-origins` 需放行移动端来源(PWA 部署域名、Capacitor 壳的 `capacitor://` / `https://localhost` scheme)
- JWT 无状态认证已支持任何 HTTP 客户端

### Database

**无影响**。无新增表/字段/索引。

### Other

- **打包工具链**:新增 Android SDK commandline tools(命令行,非 AS)、Gradle wrapper(android 工程自带)、keystore 签名
- **CI/CD**:可选 GitHub Actions 云端打包(加 Android SDK action)
- **文档**:前端知识库(`.ai/knowledge/frontend.md`)需补充移动端/Capacitor 章节
- **PWA 缓存策略**:Service Worker precache 静态资源,API NetworkFirst,需注意缓存失效与版本更新

## 风险等级

**High**(影响 18 页面 + 60 组件 + 新增 Capacitor 原生层,属跨模块大型改动)

### High 风险项
- **响应式改造工作量**:8 个重型页面桌面交互密集,移动端可能需重新设计 IA 而非断点适配,易低估工期
- **WebView 兼容性**:docx-preview / plyr 在不同安卓 WebView 内核表现不一,真机回归不可省
- **环境降级链复杂度**:14 文件引用 isElectron,新增 isCapacitor 后三端降级链需逐一验证,遗漏会导致功能异常

### Medium 风险项
- **spark-md5 大文件卡顿**:WebView 内计算大文件 MD5 阻塞 UI
- **后台传输受限**:安卓 WebView 后台任务被杀,大文件传输中断
- **PWA 缓存失效**:版本更新时旧 Service Worker 缓存导致用户看到旧版

### Low 风险项
- **打包签名**:命令行流程成熟,风险低
- **CORS 配置**:运维配置项,文档说明即可

## 建议

1. **分批改造**:响应式按"导航+核心文件页(FileManager/HomePage)→ 次要页 → 重型页(TeamSpace/Search)"分批验收,避免后期返工
2. **环境检测统一抽象**:将 `isCapacitor()`/`isElectron()`/`isMobile()` 封装为单一 `runtime.ts`,所有降级判断走统一入口,降低遗漏风险
3. **WebView 兼容降级**:预览组件(docx/plyr)增加能力检测,不支持时降级"下载查看"
4. **MD5 卡顿兜底**:大文件 MD5 计算 loading + 大小阈值评估
5. **PWA 版本管理**:配置 workbox 自动清理旧缓存,版本号联动
6. **CORS 文档**:在部署文档明确移动端来源配置,避免生产环境跨域被拒
7. **SyncPage 弱化**:移动端隐藏同步入口(手机无同步文件夹概念),降低改造负担

> 风险项将在 TECH_DESIGN 阶段传递给 Architect 与前后端工程师作为设计约束。
