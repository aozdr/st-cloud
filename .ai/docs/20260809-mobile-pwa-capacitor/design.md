# 程序设计文档 - 星云盘移动端

> 输出标准：`docs/newList/ai-design-document-standard.md`
> 大型任务,架构评审已通过(`architecture-review.md`)。前后端合用本文档,分章节。
> 落盘路径：`.ai/docs/20260809-mobile-pwa-capacitor/design.md`

# 一、需求分析

## 功能名称

星云盘移动端(PWA + Capacitor Android 壳)

## 功能描述

```
用户：手机用户(普通用户/管理员/分享访客)
操作：底部 Tab 导航浏览 -> 文件卡片长按 ActionSheet 操作 -> 下载/分享/上传
系统行为：运行时环境检测(capacitor/electron/web)降级,复用现有 /api
最终结果：18 页面移动端可用,PWA 可安装,Capacitor 出 APK
```

# 二、系统影响分析

## 影响模块

| 类型 | 模块 | 是否修改 |
|------|------|---------|
| 前端 | st-web | 是(响应式 + PWA + Capacitor) |
| 后端 | st-api | 否(零改动,仅 CORS 运维配置) |
| 数据库 | - | 否 |

## 影响文件预测

**新增文件**
- `src/lib/runtime.ts` - 环境检测统一抽象(isCapacitor/isElectron/isWeb/isMobile)
- `src/lib/capacitor.ts` - Capacitor 原生桥封装
- `src/hooks/useMobile.ts` - 移动端断点响应式 hook
- `src/components/layout/MobileTabBar.tsx` - 底部 Tab 导航
- `src/components/ui/ActionSheet.tsx` - 底部操作菜单
- `src/components/ui/MultiSelectBar.tsx` - 移动端多选操作栏(采纳 UX3)
- `capacitor.config.ts` - Capacitor 配置
- `public/pwa-*.png` - PWA 图标(192/512/maskable)
- `android/` - Capacitor Android 工程(命令行生成)

> 现状（2026-09-10 核验）：`st-web/android/` 尚未生成。PWA / capacitor.config.ts / runtime / 原生插件封装均已落地，
> Android 壳属按需生成：需要出 APK 时执行 `npx cap add android` 即可，不做出包则无需该目录。

**修改文件**
- `vite.config.ts` - 加 vite-plugin-pwa
- `index.html` - 补 apple-touch-icon/maskable meta
- `package.json` - 新增依赖
- `src/components/layout/AppLayout.tsx` - md 以下渲染 MobileTabBar,主内容区 pb-20
- `src/components/layout/Sidebar.tsx` - 断点 lg->md;"更多"Tab 触发抽屉;移动端简化存储组件
- `src/components/layout/TopBar.tsx` - 确认移动端搜索图标可见
- `src/components/file/ContextMenu.tsx` - 移动端长按触发 ActionSheet
- `src/components/file/FileBrowser.tsx` - useFileKeyboard 移动端禁用;长按多选模式
- `src/components/file/FileGrid.tsx` - md 以下单列卡片;长按触发
- `src/components/file/FileTable.tsx` / `FileTableView.tsx` - 移动端卡片化
- 18 页面 - 响应式间距/触控热区/单列布局
- `src/lib/electron.ts` - 内部委托 runtime.ts(向后兼容)

**删除文件**:无

# 三、整体设计方案

三端共用 st-web,运行时检测降级:

```
getRuntime() -> 'capacitor' | 'electron' | 'web'
  capacitor: Capacitor 原生桥(filesystem/camera/share)优先
  electron:  window.electronAPI IPC(现有,不变)
  web:       浏览器 API(download 属性/input file)

isMobile() -> matchMedia('(max-width: 767px)')  // Tailwind md 断点
```

移动布局(md 以下):底部 Tab + 抽屉 + 单列卡片 + 长按 ActionSheet + 多选模式。
桌面布局(md 以上):保持现状(左侧 Sidebar + 多列网格 + 右键菜单 + 键盘快捷键)。

# 四、前端设计

## 页面设计

路由不变(18 路由)。布局通过 `useMobile()` hook + Tailwind md 断点切换:

- AppLayout:`md:hidden` 渲染 MobileTabBar;主内容区 `pb-20 md:pb-0`
- Sidebar:`md` 断点(原 lg)为抽屉;移动端由 MobileTabBar"更多"触发 `mobileOpen`
- 页面内容:md 以下单列(`grid-cols-1 md:grid-cols-*`),间距 `px-4`,触控热区 `min-h-[44px]`

## 组件设计

### 新增组件

**MobileTabBar**(底部 Tab)
- 4 项:首页(/) / 文件(/files) / 传输(/transfers) / 更多(触发抽屉)
- 固定底部 `fixed bottom-0`,`h-16` + `pb-safe`,背景 `bg-surface` + `border-t`
- 选中态:`text-primary-600` 图标 + 标签;NavLink end 判断
- `md:hidden`

**ActionSheet**(底部操作菜单)
- props:`open`, `items: {label, icon, onClick, danger?}[]`, `onClose`
- 从底部滑入(`animate-dialog-pop` 变体),半透明遮罩,点击遮罩/下滑关闭
- 危险项(删除)`text-red-500`
- 复用于:文件操作、排序选择、批量操作

**MultiSelectBar**(移动端多选操作栏,采纳 UX3)
- 长按文件进入多选模式,顶部出现操作栏:全选/下载/分享/删除/取消
- 单击切换选中,选中态 checkbox

**useMobile hook**
- `matchMedia('(max-width: 767px)')` 监听,返回布尔值
- SSR 安全(typeof window 判断)

### 改造组件

**ContextMenu**:`isMobile()` 时,`onContextMenu` 触发 ActionSheet 而非定位菜单;长按(touchstart 500ms + touchmove 取消)触发

**FileGrid**:md 以下单列卡片(缩略图左 + 信息右 + 更多按钮);长按触发 ContextMenu/多选;`grid-cols-2 sm:grid-cols-3 md:grid-cols-*`

**FileTable/FileTableView**:md 以下隐藏表格,改 FileGrid 单列卡片渲染(复用)

**FileBrowser**:`useFileKeyboard` 在 `!isMobile()` 时绑定;多选模式状态(`selectMode`)

**Sidebar**:断点 `lg:` -> `md:`;"更多"Tab 触发 `mobileOpen`;移动端隐藏折叠按钮,存储环形图简化

**AppLayout**:渲染 `<MobileTabBar className="md:hidden" />`;主内容区 `pb-20 md:pb-0`

## 状态设计

- `useMobile()` hook(本地 matchMedia,无需 Zustand)
- FileBrowser 新增 `selectMode: boolean` 本地 state(移动端多选)
- 环境检测 `runtime.ts` 无状态(纯函数)

## UI 规范

参照 `uispec.md` 与 `ui-design-system.md`,复用全部 token,不新增颜色/字体。新增组件用现有 `rounded-2xl`/`shadow-float`/`bg-surface` 等。

# 五、后端设计

**无后端改动**。

- API:复用现有 `/api/*` 全部接口
- 认证:JWT Bearer token,移动端 axios 自动附加(现有 `api.ts` 拦截器)
- CORS:运维配置 `stcloud.cors.allowed-origins` 放行 PWA 域名 + Capacitor origin(`capacitor://localhost`)

# 六、数据库设计

无变更。

# 七、安全设计

- token 存 localStorage(Capacitor 壳内后续可切 SecureStorage,本期可接受)
- CORS 生产环境必须配置允许的移动来源
- Capacitor `allowMixedContent: false`,HTTPS 强制
- token 不进 Service Worker 缓存(仅缓存静态资源与无 auth 的 API 响应)
- 分享公开接口白名单不变,提取码校验不变
- RBAC 复用,无新增权限码

# 八、性能设计

- PWA precache:静态资源离线可用,首屏 < 5s(3G)
- 列表虚拟化:长列表复用现有滚动(若卡顿后续加 react-window)
- MD5 卡顿:spark-md5 loading + 大文件阈值评估
- SW 缓存:workbox `cleanupOutdatedCaches` + `skipWaiting` 自动更新
- APK 包体 < 30MB(WebView + 前端资源)

# 九、开发计划

```
Task1: 环境检测统一抽象(runtime.ts + capacitor.ts + useMobile)
Task2: PWA 基座(vite-plugin-pwa + manifest + 图标)
Task3: 移动导航重构(MobileTabBar + AppLayout + Sidebar 断点)
Task4: 新增交互组件(ActionSheet + MultiSelectBar)
Task5: 文件组件簇改造(FileGrid/ContextMenu/FileTable/FileBrowser)
Task6: 轻型页面响应式(Login/FileManager/Favorites/Category/Admin 等)
Task7: 重型页面响应式(HomePage/SearchPage/TransferManager 等)
Task8: Capacitor 集成(config + android 工程 + 原生插件)
Task9: 下载落盘/相册上传原生桥对接
Task10: 命令行打包流程(keystore + gradlew)
```

依赖:Task1 -> Task3/4/5;Task2 独立;Task3 -> Task6/7;Task8 依赖 Task2(build 产物);Task9 依赖 Task8。

# 十十、风险分析

| 风险 | 影响 | 解决方案 |
|------|------|---------|
| 18 页面响应式工作量 | 工期延误 | 分批改造,导航+核心页优先 |
| WebView 兼容(docx/plyr) | 预览异常 | 能力检测降级"下载查看" |
| 降级链遗漏(14 文件) | 功能异常 | runtime.ts 统一抽象,逐一迁移 |
| spark-md5 卡顿 | 上传阻塞 | loading + 大小阈值 |
| 长按与滚动冲突 | 误触 | touchmove 取消长按 |

> 本设计基于架构评审结论与体验评审 UX1-UX5 优化建议,门禁 TECH_DESIGN 待编排器勾选。
