# 需求文档 - 星云盘移动端(PWA + Capacitor)

> 输出标准：`docs/newList/ai-requirement-document-standard.md`
> 与 UI/UX 设计文档（uispec.md）同步产出，经需求评审多方会议定版后共同作为前端实现唯一依据。
> 落盘路径：`.ai/docs/20260809-mobile-pwa-capacitor/requirement.md`

# 一、需求概述

## 1.1 功能名称

星云盘移动端(PWA + Capacitor Android 壳)

## 1.2 背景说明

当前星云盘覆盖 Web 端(st-web,React)与 PC 桌面端(st-desktop,Electron),`project-overview.md` 明确"首期无移动端"。现有 st-web 为桌面优先设计:101 个源码文件、约 1.5 万行、18 个页面路由,响应式极弱(仅 14 个文件含 `sm:/md:` 断点,集中在导航与首页),本质面向 1280px+ 桌面与 Electron 壳。

移动场景(手机浏览、随时查看/分享文件)缺失。用户希望以最小成本获得移动端能力,且**不安装 Android Studio**。基于已有技术栈与 Electron 壳经验,采用 PWA + Capacitor 混合方案:后端零改动复用现有 REST + JWT API,前端响应式改造 + PWA 离线,Capacitor 包装为可分发的 Android APK。

## 1.3 用户角色

| 用户角色 | 权限 | 使用场景 |
|---------|------|---------|
| 普通用户 | 文件读写/分享/团队/收藏 | 手机查看/下载文件、生成分享链接、浏览分类 |
| 管理员 | 含系统管理权限 | 移动端查看管理面板(只读优先) |
| 分享访客 | 无需登录,公开链接访问 | 手机打开分享链接,提取码访问/下载 |

# 二、业务需求分析

## 2.1 用户故事

- 作为**普通用户**,我希望在手机上打开星云盘浏览和管理文件,从而摆脱对 PC 的依赖
- 作为**普通用户**,我希望把网盘文件下载到手机本地,从而离线查看
- 作为**普通用户**,我希望在手机上生成/打开分享链接,从而随时与他人共享
- 作为**普通用户**,我希望把网盘"添加到主屏幕"像 App 一样启动,从而获得原生体验
- 作为**管理员**,我希望移动端功能范围与 Web 一致(系统管理可只读),从而无需单独维护两套
- 作为**企业 IT**,我希望用命令行打包 APK 内部分发,从而无需配置 Android Studio 环境

## 2.2 功能列表

| 编号 | 功能 | 描述 | 优先级 |
|------|------|------|--------|
| F1 | PWA 基座 | manifest + Service Worker 离线缓存,可添加到主屏幕全屏启动 | P0 |
| F2 | 移动导航重构 | md 以下切换为底部 Tab(首页/文件/传输/我的)+ 顶部抽屉菜单 | P0 |
| F3 | 响应式布局改造 | 18 页面移动端单列/卡片化,无横向滚动 | P0 |
| F4 | 触摸交互适配 | 右键菜单改长按 ActionSheet,键盘快捷键移动端禁用,触控热区≥44px | P0 |
| F5 | Capacitor 集成 | 环境检测(isCapacitor)+ 原生桥降级,与 isElectron 并列 | P0 |
| F6 | 下载落盘 | Capacitor 壳内文件下载到本地文件系统(替代浏览器 download 属性) | P1 |
| F7 | 相册/相机上传 | Capacitor 壳内调用相机/相册选图上传 | P1 |
| F8 | 状态栏/安全区 | 适配刘海屏,状态栏/启动屏 | P1 |
| F9 | 命令行打包 | gradlew 出签名 APK,无需 Android Studio | P1 |
| F10 | 文件同步弱化 | SyncPage 在移动端隐藏或降级提示(手机无同步文件夹概念) | P2 |

## 2.3 业务流程

### 添加到主屏幕(PWA)
```
用户手机浏览器打开网盘 -> 浏览器提示"添加到主屏幕" -> 用户确认
   ↓
系统安装 PWA(写 manifest + 缓存 App Shell) -> 桌面生成图标
   ↓
点击图标全屏启动(无浏览器地址栏) -> 弱网下壳层离线可打开
```

### Capacitor 壳下载文件
```
用户在文件列表长按文件 -> ActionSheet 选择"下载"
   ↓
环境检测: isCapacitor? -> 是: 调用 Filesystem 写本地 + Share 提示
   ↓
否(Web/Electron): 走原有浏览器 download / Electron IPC 降级路径
   ↓
下载完成,文件落盘可查
```

### 移动端文件浏览
```
用户点击底部 Tab"文件" -> 进入 FileManager(移动单列卡片列表)
   ↓
长按文件 -> 底部 ActionSheet(打开/下载/分享/重命名/删除/收藏)
   ↓
点击文件夹 -> 进入下级目录(移动端面包屑改返回箭头)
```

# 三、功能边界

## 包含范围

- st-web 响应式改造(PWA + 移动布局 + 触摸交互)
- Capacitor 集成(lib 桥接 + android 工程 + 原生插件)
- 命令行打包流程(keystore + gradlew)
- 后端 CORS 配置说明(非代码改动,运维配置)

## 不包含范围

- 后端代码改动(REST + JWT 无状态,移动端直接复用)
- iOS 适配(本期仅 Android,后续可 `cap add ios`)
- 应用商店上架(企业内部分发,不做商店合规)
- 移动端独有新功能(功能范围与 Web 一致)
- 原生后台下载 Foreground Service(降级为前台保活提示)
- 移动端文件同步(SyncPage 弱化/隐藏)

# 四、业务规则

- **环境检测优先级**:Capacitor > Electron > Web API。三端共用同一份 st-web 代码,通过 `isCapacitor()` / `isElectron()` 运行时判断降级
- **后端零改动**:JWT 无状态认证(`SessionCreationPolicy.STATELESS`),移动端通过 `Authorization: Bearer <token>` 对接;CORS 由 `stcloud.cors.allowed-origins` 配置放行移动来源
- **PWA 离线策略**:App Shell + 静态资源 precache;API 请求走 NetworkFirst(数据需实时);登录态(token)存 localStorage,离线可恢复会话
- **移动导航**:md(768px)为分界,以下切换底部 Tab + 抽屉,以上保留桌面侧栏
- **功能降级**:SyncPage 移动端隐藏(手机无同步文件夹);传输管理在非 Capacitor/Electron 的纯浏览器下显示 Web 上传进度(无后台)
- **权限**:移动端复用现有 RBAC,无新增权限码;分享访客走公开白名单 `/api/share/access/**`

# 五、异常场景

| 场景 | 处理方式 |
|------|---------|
| 弱网/断网 | PWA 离线壳层可打开,数据请求失败提示"网络异常,请重试" |
| WebView 不兼容(docx-preview/plyr) | 预览降级为"不支持在移动端预览,请下载查看" |
| Capacitor 原生插件不可用 | 降级到 Web API(如下载用浏览器 download 属性) |
| MD5 计算大文件卡顿 | spark-md5 在 WebView 内卡顿时,提示"正在计算"loading,或限制单文件大小 |
| 安卓 WebView 后台杀进程 | 传输任务中断,重进 App 后从服务端恢复任务状态 |
| 相机/相册权限拒绝 | 提示"需要相册权限才能上传",引导用户去系统设置开启 |

# 六、非功能需求

## 性能

- 移动端首屏加载:3G 网络 < 5s(依赖 PWA precache)
- 列表滚动:60fps,长列表虚拟化
- 包体:APK < 30MB(WebView + 前端资源)

## 安全

- 权限控制:复用 JWT + RBAC,移动端不存明文密码
- 数据保护:token 存 localStorage(Capacitor 壳内可后续切 SecureStorage)
- CORS:生产环境必须配置允许的移动来源,留空则拒绝跨域
- 分享链接:公开接口走白名单,提取码访问需校验

## 可维护性

- 复用 st-web 单仓库,不新建独立移动工程
- Capacitor 配置与 Electron 并列,共享前端代码
- 环境检测抽象为统一接口,新增平台只需扩展判断

# 七、验收标准

- Given 手机浏览器打开网盘,When 点击"添加到主屏幕",Then 桌面生成图标且点击全屏启动无地址栏
- Given 弱网环境,When 断网后点击桌面图标,Then PWA 壳层(导航/骨架)可离线打开
- Given 手机进入任意页面,When 单手操作,Then 无横向滚动、触控热区≥44px、底部 Tab 可达
- Given Capacitor 壳内,When 下载文件,Then 文件落盘到手机可查(非仅浏览器缓存)
- Given Capacitor 壳内,When 相册选图上传,Then 上传成功且可在文件列表看到
- Given 命令行环境(无 Android Studio),When 执行 gradlew assembleRelease,Then 产出签名 APK
- Given 陌生安卓手机,When 安装 APK 启动,Then 登录->上传->下载->分享闭环跑通
- Given st-web,When npm run build,Then 编译通过无 TS 错误

# 八、风险分析

| 风险 | 影响 | 解决方案 |
|------|------|---------|
| 18 页面响应式改造工作量被低估 | 工期延误 | 按"导航+核心文件页->次要页"分批验收,重型页(TeamSpace/Search)可能需重新设计 IA |
| 后台下载受限 | 大文件传输体验差 | 降级为前台保活提示,企业内部分发可接受 |
| WebView 内核差异 | 预览/播放异常 | 真机回归,预览降级提示下载 |
| spark-md5 大文件卡顿 | 上传秒传计算阻塞 UI | loading 提示 + 大文件大小限制评估 |
| 安卓版本碎片 | 低版本 WebView 不支持新 API | 最低支持 Android 8(WebView Chromium) |
