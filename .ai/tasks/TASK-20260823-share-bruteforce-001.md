# TASK-20260823-share-bruteforce-001

- 任务：分享防枚举（百度方案落地）
- 类型：implement（后端 + 前端）
- 目标：分享标识不可枚举、提取码防爆破 + 图形验证码、阈值后台可改。
- 范围：
  - `st-common`：`sys_config` 全局配置 + `ResultCode` 3005/3006。
  - `st-share`：`ShareBruteForceGuard`、`ShareCaptchaService`、分享码加固、`validateShareAccess` 频控/验证码、`/api/share/captcha`。
  - `st-admin`：`SysConfigController`（权限 `admin:share:security`）。
  - `st-web`：`ShareAccessPage` 验证码/提示、`AdminPage`「分享安全」面板。
  - DB：`docker/mysql/init/38_share_security_config.sql`。
- 验收：单码/IP 失败锁定、达阈值需验证码、成功清除、不存在分享跳过、后台可改配置、旧分享码兼容。
- 状态：done。
