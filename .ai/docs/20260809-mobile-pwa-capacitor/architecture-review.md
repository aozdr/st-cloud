# 架构设计评审 - 星云盘移动端

> 输出标准：`docs/newList/ai-architecture-review-standard.md`
> 大型任务 TECH_DESIGN 前置,Architect 主笔。评审通过后进入程序设计阶段。
> 落盘路径：`.ai/docs/20260809-mobile-pwa-capacitor/architecture-review.md`

# 一、架构评审基本信息

## 功能信息

```
功能名称：星云盘移动端(PWA + Capacitor Android 壳)
业务背景：现有覆盖 Web + PC(Electron),移动端缺失;需以最小成本获得移动能力,不安装 Android Studio
目标：PWA 基座 + st-web 响应式改造 + Capacitor Android 壳,企业内部分发
涉及模块：st-web(前端主改区);st-api(零改动,仅 CORS 运维配置);新增 capacitor.config.ts + android 工程
```

## 影响范围

| 类型 | 模块 | 影响 |
|------|------|------|
| 前端 | st-web | 18 页面响应式改造 + 导航重构 + PWA + Capacitor 集成,新增 MobileTabBar/ActionSheet/runtime.ts |
| 后端 | st-api | 零代码改动,仅 CORS 配置放行移动来源 |
| 数据库 | - | 无 |
| 缓存 | - | 无(Service Worker 缓存属前端,非 Redis) |
| 第三方服务 | Capacitor / vite-plugin-pwa | 新增构建依赖与原生插件 |

# 二、需求理解评审

```
业务目标：手机单手完成文件浏览/下载/分享/上传,PWA 可安装,Capacitor 出 APK 内部分发
核心流程：底部 Tab 导航 -> 文件卡片长按 ActionSheet -> 操作(打开/下载/分享/上传)
成功标准：18 页面移动端可用 + PWA 可安装 + APK 命令行打包 + 原生能力可用 + 编译通过
限制条件：不安装 Android Studio;后端零改动;iOS 不在本期;不上架商店
```

评审确认:需求清晰,边界明确,非功能要求(性能/安全/可维护性)合理。

# 三、整体架构设计

```
手机浏览器 / Capacitor WebView
  ↓ (HTTP, Authorization: Bearer <JWT>)
st-web(React,响应式)
  ├─ PWA: manifest + Service Worker(precache App Shell)
  ├─ 环境检测: runtime.ts(isCapacitor > isElectron > Web)
  └─ Capacitor 原生桥: @capacitor/filesystem/camera/share/status-bar
        ↓ (HTTP /api/*)
st-api(Spring Boot,无状态 JWT,零改动)
  ↓
MySQL / Redis / S3 / ES(无变更)
```

关键架构决策:**单仓库单前端**,PWA 与 Capacitor 共用 st-web 代码,通过运行时环境检测降级,与现有 Electron 壳并列,不新建独立移动工程。

# 四、技术方案评估

## 技术选型

| 技术 | 选择 | 原因 | 替代方案 |
|------|------|------|---------|
| 移动形态 | PWA + Capacitor | 复用 st-web 代码,壳层经验复用(Electron),免 AS | RN/Flutter(重写前端,成本高);原生 Kotlin(需 AS) |
| PWA 工具 | vite-plugin-pwa | 与现有 Vite 无缝集成,workbox 自动化 | 手写 SW(维护成本高) |
| 原生桥 | Capacitor 6 | Ionic 出品,WebView + 原生插件,与 Electron 同构 | Cordova(已过时) |
| 移动判断 | matchMedia md 断点 | 与 Tailwind 断点一致,SSR 安全 | window.innerWidth(无响应式) |
| 环境抽象 | runtime.ts 统一入口 | 三端降级集中管理,降低遗漏 | 各处散落判断(现状,易遗漏) |

## 评估

- 是否符合当前技术栈:✅ React/Vite/Tailwind 不变,Capacitor 是增量
- 是否增加不必要复杂度:✅ 无新框架,仅增量插件
- 是否方便维护:✅ 单仓库,环境检测统一抽象

# 五、后端架构评审

**零改动**。复用现有:
- JWT 无状态认证(`SessionCreationPolicy.STATELESS`),移动端通过 Bearer token 对接
- CORS 由 `stcloud.cors.allowed-origins` 配置,Capacitor Android 壳 origin 为 `capacitor://localhost` 或 `https://localhost`,PWA 为部署域名
- 分享公开接口白名单 `/api/share/access/**` 已有,访客移动端访问无障碍

无新增 Controller/Service/Mapper/Entity。事务/并发/幂等沿用现有实现。

# 六、前端架构评审

```
页面：18 页面路由不变,布局响应式重排
组件：新增 MobileTabBar/ActionSheet/useMobile;改造 AppLayout/Sidebar/FileGrid/ContextMenu
状态：新增 useMobile hook(matchMedia);环境检测抽 runtime.ts
接口依赖：复用现有 /api,零新增接口
```

### 环境检测统一抽象(架构关键)

现状:`isElectron()` 散落 14 文件。新增 `isCapacitor()` 后,统一为 `src/lib/runtime.ts`:

```
getRuntime(): 'capacitor' | 'electron' | 'web'
isCapacitor() / isElectron() / isWeb()
isMobile(): matchMedia('(max-width: 767px)')  // 与 Tailwind md 一致
```

降级链:原生能力(下载/上传/同步)按 `capacitor > electron > web` 优先级选择实现。现有 `electron.ts` 的 `isElectron`/`getElectronAPI` 保持向后兼容,内部委托 runtime.ts。

### PWA 架构

- vite-plugin-pwa 注入 manifest + 自动生成 SW
- precache: index.html + 静态资源(JS/CSS/字体/图标)
- runtime cache:`/api/*` NetworkFirst(数据需实时),图片 CacheFirst
- 离线回退:壳层(导航/骨架)可打开,数据请求失败提示重试

### Capacitor 架构

- `capacitor.config.ts`:webDir 指向 st-web `dist`,appId `com.stcloud.app`
- `src/lib/capacitor.ts`:`isCapacitor()` + 原生桥封装(下载/上传/状态栏)
- 原生插件:filesystem(下载落盘)、camera(相册/相机)、share(分享文件)、status-bar/splash-screen(刘海适配)
- 构建:`npm run build` -> `npx cap sync` -> `npx cap add android` -> `gradlew assembleRelease`

# 七、数据库设计评审

**本次不涉及数据库**。无表/字段/索引变更。

# 八、缓存设计评审

前端 Service Worker 缓存(非 Redis):
- precache:构建产物(index.html + JS/CSS chunks + 图标)
- runtime:`/api/*` NetworkFirst(timeout 3s 回退缓存);图片 CacheFirst + 过期清理
- 版本更新:workbox `cleanupOutdatedCaches`,新版本自动激活

检查:无缓存穿透/击穿/雪崩风险(SW 缓存为本地浏览器层,非共享缓存)。

# 九、高并发设计评审

```
预估压力:移动端不新增服务端压力,复用现有 API 容量
瓶颈:无新增瓶颈
解决方案:无需额外限流,沿用 st-common 限速服务
```

# 十、安全设计评审

- **身份认证**:复用 JWT,token 存 localStorage;Capacitor 壳内后续可切 SecureStorage(本期 localStorage 可接受,企业内部分发)
- **权限控制**:RBAC 复用,无新增权限码;移动端不存明文密码
- **CORS**:生产环境必须配置 `stcloud.cors.allowed-origins` 放行 PWA 域名与 Capacitor origin,留空则拒绝跨域
- **数据隔离**:租户隔离沿用 `tenant_id`,移动端无特权
- **分享公开接口**:白名单 `/api/share/access/**`,提取码校验不变
- **WebView 安全**:Capacitor 配置 `allowMixedContent: false`,禁止混合内容;HTTPS 强制
- **敏感数据**:token 不进 Service Worker 缓存(仅缓存静态资源与 API 响应,不含 auth header)

# 十一、可扩展性评审

- **新平台扩展**:runtime.ts 统一入口,新增平台(如 iOS)只需扩展 `getRuntime()` 判断 + `cap add ios`
- **模块耦合**:Capacitor 原生能力通过 lib 封装,组件不直接依赖 Capacitor API,解耦良好
- **水平扩展**:后端无变更,现有水平扩展能力不受影响
- **替换实现**:PWA 与 Capacitor 独立,可单独启用/禁用(vite-plugin-pwa 配置开关)

# 十二、异常和容错设计

| 异常 | 处理方案 |
|------|---------|
| 弱网/断网 | PWA 壳层离线可打开,API 失败显示重试按钮(非 EmptyState) |
| WebView 不兼容(docx/plyr) | 能力检测,降级"下载查看" |
| Capacitor 插件不可用 | 降级 Web API(下载用浏览器 download) |
| MD5 大文件卡顿 | loading 提示 + 大小阈值 |
| 安卓杀进程传输中断 | 重进 App 从服务端查询任务状态恢复 |
| 权限拒绝(相机/相册) | 引导去系统设置开启 |
| SW 缓存旧版本 | workbox 自动清理 + skipWaiting |

# 十三、架构风险分析

| 风险 | 影响等级 | 解决方案 |
|------|---------|---------|
| 18 页面响应式工作量被低估 | High | 分批改造:导航+核心文件页 -> 次要页 -> 重型页 |
| WebView 内核差异(预览/播放) | High | 真机回归 + 能力检测降级 |
| 14 文件环境降级链遗漏 | High | runtime.ts 统一抽象,逐一迁移验证 |
| spark-md5 大文件卡顿 | Medium | loading + 大小阈值评估 |
| 后台传输受限 | Medium | 降级前台保活提示 |
| PWA 缓存失效 | Medium | workbox cleanupOutdatedCaches |
| 安卓版本碎片 | Low | 最低 Android 8(WebView Chromium) |

# 十四、架构评审结论

```
架构评分：8.5/10
技术方案：PWA + Capacitor 复用 st-web,单仓库单前端,环境检测统一抽象,后端零改动。方案成熟,复杂度可控。
主要风险：响应式改造工作量(High)、WebView 兼容性(High)、降级链遗漏(High)
优化建议：1.runtime.ts 统一抽象 2.分批改造 3.预览能力检测降级 4.采纳体验评审 UX1-UX5(P0)
是否进入开发：是(评审通过,进入程序设计阶段)
```

> 评审通过。开发工程师可基于本评审产出程序设计文档(design.md)。High 风险项作为设计约束传递。
