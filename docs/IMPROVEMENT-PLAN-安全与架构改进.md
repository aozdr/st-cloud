# 晟云盘 - 安全与架构改进计划

> 基于 grill-me 拷问会话整理。所有问题均经源码核实。
> 状态标记：✅ 已决策方案 | 🔲 待决策方案

---

## P0 - 严重（需立即处理）

### 1. JWT 签名密钥硬编码 ✅

**问题**
`st-common/.../JwtUtils.java` 中签名密钥为 `static final` 常量：
```java
private static final String SECRET = "stcloud-secret-key-must-be-at-least-32-chars-long";
```
- 不读取 `application.yml` 的 `stcloud.jwt.secret`，README 要求"生产修改密钥"实际无法生效。
- 任何拿到源码/反编译 jar 的人可伪造任意租户、任意用户（含 admin）的 JWT。

**已决策方案：C 混合（DB 存 + 主密钥加密）**
- `JwtUtils` 由静态工具类重构为 Spring Bean（`@Component` + 构造器注入）。
- 首次启动随机生成 ≥32 字节密钥，落库时用**环境变量主密钥**（`STCLOUD_MASTER_KEY`）加密存储。
- 运行时从 DB 读取、解密、缓存到内存；轮换时经 RocketMQ 广播或后台配置触发刷新。
- 主密钥不进 DB、不进源码，仅存环境变量/KMS。
- 启动时 fail-fast：主密钥缺失或密文解密失败则拒绝启动。

**影响范围**：`JwtUtils`、`JwtAuthenticationFilter`、`AuthService` 全部调用点。

---

### 2. Token 吊销机制缺失 ✅

**问题**
- access token 有效期 7 天，`roles/permissions/tenantId` 全在 claim 中。
- `JwtAuthenticationFilter` 直接从 token 读取权限，**无服务端权限查询**。
- 无黑名单 / 版本号机制。禁用用户、撤销权限后，旧 token 最长 7 天仍有效。

**已决策方案：两段式**
- **第一段（必做）**：access token 缩短至 ~2 小时；refresh 端点已在 `refreshToken()` 中重新加载权限（`loadUserPermissions`）并校验用户状态——天然执法点。禁用用户时删除 Redis 中的 refresh token（`stcloud:refresh:{userId}`），使其无法刷新。最坏暴露窗口从 7 天降到 2 小时。
- **第二段（需即时踢人时）**：`sys_user` 增加 `token_version` 字段，登录时写入 claim；每请求验签后用 Redis 比对版本号（单 key GET）。禁用/降权时版本号 +1 并清该用户 Redis 缓存，旧 token 立即失效。

**取舍**：不引入 token 黑名单（列表只增不减，长期负担）。轮换密钥 = 全员重登，人工操作可接受。

---

## P1 - 高（应在上线前处理）

### 3. CORS 配置过宽 🔲

**问题**
`SecurityConfig.corsConfigurationSource()`：
```java
configuration.setAllowedOriginPatterns(List.of("*"));
configuration.setAllowCredentials(true);
```
`*` + `allowCredentials(true)` 是高危组合：任意站点可携带凭证发起跨域请求，CSRF 防护已禁用（`csrf().disable()`），等于对 XHR 攻击敞开。

**建议方案**（待决策）
- 将 `allowedOriginPatterns` 收敛为前端实际域名白名单（Web 域 + Electron 本地）。
- 桌面端走绝对地址直连的特性改为单独白名单条目，而非放开全网。
- 若需保留多环境灵活性，从配置文件读取白名单，默认拒绝 `*`。

---

### 4. Token 经 query 参数传递 🔲

**问题**
`JwtAuthenticationFilter.extractToken()` 允许 `?token=xxx`：
```java
String paramToken = request.getParameter("token");
```
用于下载/流式预览接口。但 query 参数会进入：访问日志、浏览器历史、Referer 头、代理日志——token 泄漏面显著扩大。

**建议方案**（待决策）
- 大文件下载/预览改用**短期一次性下载令牌**：前端先调 `/api/file/{id}/download-token` 换取一个 5 分钟有效、单次使用的 token（存 Redis），下载接口只认这个令牌，不认 JWT。
- 或保留 query token 但缩短其有效期并绑定 IP/UA 指纹。

---

## P2 - 中（应纳入技术债务）

### 5. TenantContext 空值兜底为 1L 🔲

**问题**
`TenantContext.getTenantId()` 在 tenantId 为 null 时默认返回 `1L`：
```java
if (tenantId == null) { return 1L; }  // 私有云模式默认租户
```
- 已认证请求经 `JwtAuthenticationFilter` 会设置 tenantId，正常不触发兜底。
- 但兜底是**静默**的，会掩盖 bug：若某条代码路径忘记设置 TenantContext，请求会被悄悄归到租户 1，SAAS 模式下存在跨租户数据误读风险。

**建议方案**（待决策）
- SAAS 模式下 tenantId 为 null 应**抛异常**而非兜底；仅在 `isPrivateMode()` 时返回默认租户。
- 或保留兜底但加 WARN 日志，便于排查。

---

### 6. 测试覆盖严重不足 🔲

**问题**
- 169 个 Java 文件，仅 3 个测试（全在 st-search 模块）。
- 核心模块 st-core（46 文件）、st-auth（19 文件）零测试。
- P0/P1 改动（密钥重构、token 吊销、CORS）无回归保护，改完难验证。

**建议方案**（待决策）
- 优先为本次改动的关键路径补测试：`JwtUtils`（Bean 化后）、`JwtAuthenticationFilter`（吊销/版本号）、`AuthService.refreshToken`、`SecurityConfig`（CORS 白名单）。
- 设定最低目标：安全与认证链路单测覆盖率 > 70%。

---

## 优先级与排期建议

| 优先级 | 项目 | 建议时机 |
|--------|------|----------|
| P0 | 1. JWT 密钥外置（方案 C） | 上线前必做 |
| P0 | 2. Token 吊销（第一段：缩短 + refresh 执法） | 上线前必做 |
| P1 | 3. CORS 收敛白名单 | 上线前 |
| P1 | 4. 下载令牌替代 query token | 上线前 |
| P2 | 5. TenantContext 兜底修正 | 技术债窗口 |
| P2 | 6. 安全链路测试补齐 | 随 P0/P1 改动同步 |

---

## 待办

- [x] P0-1 重构 JwtUtils 为 Bean，实现 DB+主密钥方案
- [x] P0-2 access token 缩短至 2h，refresh 删除 Redis 条目实现禁用
- [ ] P1-3 CORS 白名单从配置读取
- [ ] P1-4 一次性下载令牌机制
- [ ] P2-5 TenantContext SAAS 模式抛异常
- [ ] P2-6 补安全链路单测
