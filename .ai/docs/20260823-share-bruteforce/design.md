# 分享防枚举方案设计（定稿）

## 背景

星云盘文件分享当前缺陷：

- `file_share.share_code` 为 4 位 32 字符集（约 105 万组合），**可枚举**。公开分享（`shareType=0`）无密码，枚举分享码即可访问他人文件。
- 私密分享提取码（`password`）校验失败无计数、无锁定，可暴力枚举。

百度网盘采用「长随机分享标识（surl）+ 短提取码 + 服务端限流/验证码/风控」。本方案把该机制落地。

## 目标

1. 分享标识不可枚举。
2. 私密提取码验证具备失败计数、阈值、锁定。
3. 失败达阈值后要求图形验证码（人机校验）。
4. 阈值/锁定/验证码开关/码长均为**后台可改全局配置**，非静态。
5. 超限返回明确错误码与文案；分享不存在/已取消不浪费资源（不计频控）。
6. 单元 + 集成测试覆盖。

## 用户决策（2026-08-23 已确认）

| 项 | 决策 |
|---|---|
| P1 配置 | 做成全局配置，后台管理页可改，不做静态 |
| P2 验证码 | 需要图形验证码 |
| P3 不存在/已取消 | 直接跳过，不计频控，避免浪费资源 |

## 现状关键事实

| 项 | 现状 |
|---|---|
| `shareCode` | 4 位，32 字符集，列 `VARCHAR(32)`，唯一索引 `uk_share_code` |
| `password` | 私密提取码，`validateShareAccess` 明文比较，无计数/锁定 |
| 公开接口 | `accessShare` / `getDownloadUrl` / `listShareFiles` / `streamShareFile` / `editorConfig` 共用 `validateShareAccess` |
| 配置先例 | `sys_rate_limit`：实体在 `st-common`，管理接口在 `st-admin`，面板在 `st-web/components/admin` |
| 权限模型 | `sys_permission` + `sys_role_permission`；权限码迁移脚本；ADMIN 默认全量权限 |
| 验证码 | `hutool-all` 已传递到 `st-share`（`cn.hutool.captcha.LineCaptcha`），无需新增依赖 |
| 前端 | `/share/:shareCode` 路由，`?pwd=` 传提取码；`AdminPage` 有 Tab 面板先例 |

## 方案

### 1. 分享标识不可枚举

- `shareCode` 扩为 **12 位 58 字符集**（排除易混 `0/O/1/I/l`），空间约 58^12 ≈ 1.6×10^21，不可枚举。
- 仍 `SecureRandom` 生成 + 唯一索引重试；旧 4 位码保留有效。`VARCHAR(32)` 够用，无需改动 `file_share` 结构。

### 2. 全局配置：新增 `sys_config` 表 + 后台管理

新增通用 key-value 配置表（放 `st-common`），分享防爆破参数以 key 前缀 `share.brute_force.` 存放：

| key | 默认值 | 含义 |
|---|---|---|
| `share.brute_force.shareCodeLength` | 12 | 分享码长度 |
| `share.brute_force.maxFailPerCode` | 5 | 单分享码失败阈值 |
| `share.brute_force.codeWindowMs` | 300000 | 单码失败窗口 |
| `share.brute_force.codeLockMs` | 900000 | 单码锁定时长 |
| `share.brute_force.maxFailPerIp` | 20 | 单 IP 总失败阈值 |
| `share.brute_force.ipWindowMs` | 600000 | 单 IP 窗口 |
| `share.brute_force.ipLockMs` | 1800000 | 单 IP 锁定时长 |
| `share.brute_force.captchaEnabled` | true | 是否启用验证码 |
| `share.brute_force.captchaThreshold` | 3 | 失败达阈值后需验证码 |
| `share.brute_force.captchaLockMs` | 1800000 | 预留：验证码校验失败锁定（当前计入单码/IP 失败） |

实现：

- `st-common`：`SysConfig` 实体、`SysConfigMapper`、`SysConfigService`（`getInt/getBool/getString(key, default)` 带缓存、`listByGroup`、`update`）。
- `st-admin`：`SysConfigController`，`GET /api/admin/config?group=share.brute_force.` 与 `PUT /api/admin/config`；权限 `admin:share:security`。
- **安全约束**：更新接口仅允许写入白名单前缀 `share.brute_force.` 的 key，防任意配置篡改；更新后清缓存。
- `st-web`：`AdminPage` 新增 Tab「分享安全」，面板 `components/admin/ShareSecurityPanel.tsx` 读写上述参数。

### 3. 提取码验证防爆破 + 验证码

新增 `ShareBruteForceGuard`（`st-share`，用 `CacheFactory`：多实例 Redis 一致、单实例内存兜底）。计数 key 前缀 `share:brute:`。

`validateShareAccess(shareCode, password, captchaId, captchaCode)` 流程：

1. 查分享；**不存在/已取消 → 直接抛**，不计频控（P3）。过期 → 抛，不计。
2. 检查单码/IP 是否锁定，命中抛 `SHARE_ATTEMPT_LIMIT`。
3. 若 `captchaEnabled` 且该单码失败数 ≥ `captchaThreshold` → 校验验证码：错误/invalid 计 IP 失败并抛 `SHARE_CAPTCHA_REQUIRED`；通过则清除验证码状态继续。
4. 校验提取码：错误 → 记单码 + IP 失败，超阈值抛 `SHARE_ATTEMPT_LIMIT`；正确 → 清除单码失败计数。

IP 获取：`RequestContextHolder` + `IpUtils.getClientIp`；公开接口均在 Web 线程内。

验证码生成：`GET /api/share/captcha`（公开，`ShareController`），`LineCaptcha` 生成 4 位图，返回 `{ captchaId, imageBase64 }`；答案存 `CacheFactory` TTL 2 分钟，一次校验后即销毁。`ShareService` 新增 `getCaptcha()`。

### 4. 错误码与前端

- `ResultCode` 新增 `SHARE_ATTEMPT_LIMIT(3005, "尝试次数过多，请稍后再试")`、`SHARE_CAPTCHA_REQUIRED(3006, "请完成验证码")`。
- `ShareAccessPage`：收到 3006 展示验证码重新生成与输入；3005 展示锁定提示；其余不变。

## 数据 / 接口影响

| 项 | 影响 |
|---|---|
| DB | 新增 `sys_config` 表 + 默认参数 seed；新增 `admin:share:security` 权限点并授 ADMIN |
| API | 新增 `/api/share/captcha`、`/api/admin/config`；`accessShare` 请求体可选新增 `captchaId`/`captchaCode` |
| 兼容 | 旧 4 位 `shareCode` 仍有效；未达阈值时访问行为不变 |

## 测试

- `SysConfigServiceTest`：默认值、缓存、白名单更新、evict。
- `ShareServiceImplShareCodeUnitTest`：12 位、58 字符集、唯一性、重试。
- `ShareServiceImplSecurityIntegrationTest`：失败→锁定；命中抛 3005；成功清除计数；达阈值需验证码；验证码错误计 IP；不存在分享不计频控；公开分享无密码可访问；正常访问不误伤。用短窗口注入 + `X-Forwarded-For` 模拟 IP。

## 遗留问题点

已由用户确认；无新增遗留问题。
