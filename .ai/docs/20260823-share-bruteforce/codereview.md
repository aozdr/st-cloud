# Code Review：分享防枚举

结论：PASS

## 变更范围

后端：

- `st-common`：新增 `SysConfig` 实体 / Mapper / `SysConfigService`（带缓存、白名单更新）；`ResultCode` 新增 3005/3006；`MyBatisPlusConfig` 忽略 `sys_config` 租户隔离。
- `st-share`：`ShareBruteForceGuard`（失败计数/锁定/验证码阈值）、`ShareCaptchaService`（Hutool LineCaptcha，一次有效）；`ShareServiceImpl` 分享码加固为 12 位 57 字符集、`validateShareAccess` 统一频控与验证码；`ShareController` 新增 `/api/share/captcha` 并透传 captcha 参数。
- `st-admin`：`SysConfigController` 提供分享安全配置读写，权限 `admin:share:security`。
- DB：`38_share_security_config.sql` 新增 `sys_config` 表 + 默认参数 + 权限点。

前端（`st-web`）：

- `ShareAccessPage` 验证码展示/输入与 3005/3006 提示。
- `AdminPage` 新增「分享安全」Tab + `ShareSecurityPanel` 配置面板。

## 检查点

- 接口兼容：新增参数为可选（`@RequestParam(required=false)` / DTO 可空），旧请求不受影响。
- 事务：`validateShareAccess` 只读 + 内存计数，计数不入 DB 事务；`accessShare` 的 `view_count` 仍是原子 UPDATE。
- 异常：新增错误码 3005/3006 均在 `ResultCode` 定义，前端按 code 分支。
- 配置安全：`SysConfigService.update` 仅允许 `share.brute_force.` 前缀写入，防任意配置篡改。
- 验证码安全：`verify` 成功后即删，避免复用；答案存缓存有 2 分钟 TTL。
- 分享码熵：12 位 57 字符集，`SecureRandom`，唯一索引重试兜底。

## 风险

- 计数/验证码答案依赖 `CacheFactory`：未启用 Redis 时单机内存，多实例需启用 Redis 保证一致性。
- IP 取自 `X-Forwarded-For`，反向代理未正确配置时可能被伪造，需网关信任头。
- `Hutool LineCaptcha` 抗 OCR 能力有限，高防场景建议后续升级滑块/第三方行为验证。

## 结论

实现质量良好，符合分享安全目标，无阻塞问题。
