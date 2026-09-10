# 安全审查记录 - 星云盘移动端

> 归属标准：SECURITY_REVIEW（dependsOn: IMPLEMENTED）
> 落盘路径：`.ai/docs/20260809-mobile-pwa-capacitor/security.md`
> 输入：State 快照 + artifacts.code + design.md

## 审查范围

移动端安全相关变更:
- 环境检测(runtime.ts/capacitor.ts)
- PWA Service Worker 缓存策略(vite.config.ts)
- Capacitor 原生桥配置(capacitor.config.ts)
- CORS/认证(JWT 复用)

## 审查结论：通过(附注意事项)

## 一、越权访问 ✅

- 移动端复用现有 RBAC + JWT 认证,无新增权限码
- 文件操作 ActionSheet 的菜单项通过 `has('file:download')` 等权限判断,与桌面 ContextMenu 一致
- 无移动端特有的越权风险

## 二、文件泄露 ✅

- ActionSheet 仅展示有权限的操作项(与 ContextMenu 同逻辑)
- 下载操作走现有 API + 权限校验,Capacitor Filesystem 仅在本地写文件,不绕过服务端权限

## 三、分享权限 ✅

- 分享访客页 `/share/:shareCode` 移动端适配,复用公开白名单 `/api/share/access/**`
- 提取码校验不变,无新增风险

## 四、API 鉴权 ✅

- JWT Bearer token 复用现有 axios 拦截器(api.ts),自动附加 Authorization header
- 无状态认证(SessionCreationPolicy.STATELESS),移动端无 session 固定风险
- token 存 localStorage,与 Web 端一致

## 五、PWA Service Worker 缓存安全 ⚠️ 注意事项

- **缓存范围**:precache 仅静态资源(JS/CSS/HTML/SVG/PNG),不缓存 API 响应体含 auth header
- **API 缓存**:runtime caching 对 `/api/*` 走 NetworkFirst,3s 超时回退缓存。缓存的 API 响应不含 Authorization header(仅缓存响应体),无 token 泄露风险
- **注意**:NetworkFirst 回退缓存可能让用户看到旧数据(如文件列表)。对于敏感操作(删除/分享),API 响应不应被缓存。当前配置缓存所有 `/api/*` 响应(statuses: [0, 200]),建议:
  - **SR1(P1)**:对写操作(POST/PUT/DELETE)排除缓存,仅缓存 GET 请求。可在 workbox runtimeCaching 的 urlPattern 中排除写方法,或拆分为两条规则
- **缓存清理**:workbox `cleanupOutdatedCaches` + `skipWaiting` 自动更新,旧版本缓存自动清理

## 六、Capacitor 原生配置安全 ✅

- `allowMixedContent: false` - 禁止混合内容,强制 HTTPS ✅
- `androidScheme: 'https'` - WebView 使用 HTTPS scheme ✅
- 无 `allowNavigation` 白名单(默认允许所有导航),**SR2(P2)**:建议配置 `allowNavigation` 限制仅允许后端域名,防止 WebView 加载恶意页面
- SplashScreen/StatusBar 配置无安全风险

## 七、CORS 配置 ⚠️ 运维注意

- 后端零改动,CORS 由 `stcloud.cors.allowed-origins` 配置
- **SR3(P1)**:生产环境必须配置允许的移动来源:
  - PWA 部署域名(如 `https://cloud.example.com`)
  - Capacitor Android 壳 origin:`capacitor://localhost` 或 `https://localhost`
  - 留空则拒绝所有跨域请求,移动端将无法访问 API
- 此为运维配置项,非代码改动,但需在部署文档明确

## 八、数据一致性 ✅

- 移动端文件操作走现有 API,服务端事务/去重/引用计数不变
- Capacitor 下载落盘是本地操作,不影响服务端数据一致性
- 上传走现有 spark-md5 + 分片上传,去重逻辑不变

## 风险等级：Low

本次变更无 Critical/High 安全风险。移动端复用现有安全架构(JWT/RBAC/CORS),新增的 PWA/Capacitor 层不引入新攻击面。

## 注意事项汇总

| 编号 | 注意事项 | 优先级 | 说明 |
|------|---------|--------|------|
| SR1 | API 缓存排除写操作 | P1 | workbox 应仅缓存 GET,排除 POST/PUT/DELETE |
| SR2 | Capacitor allowNavigation | P2 | 限制 WebView 导航白名单 |
| SR3 | CORS 运维配置 | P1 | 生产环境必须配置移动来源(非代码改动) |

## 审查结果：通过

无阻塞安全风险。SR1(缓存排除写操作)建议在测试前修复,SR3(CORS)为运维配置需在部署时确认。