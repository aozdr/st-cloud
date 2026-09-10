# Change Report：分享防枚举

## 改后影响

- 分享链接 `shareCode` 由 4 位升级为 12 位不可枚举，旧 4 位分享仍可访问。
- 私密提取码验证新增失败计数、阈值锁定与图形验证码，失败达阈值后需验证码。
- 阈值/锁定/验证码开关/分享码长度可在「系统管理 → 分享安全」后台调整，即时生效。
- 错误码新增 3005（尝试次数过多）、3006（请完成验证码），前端已接入提示。

## 数据库

- 新增 `sys_config` 全局配置表；新增 `admin:share:security` 权限点并授 ADMIN 角色。
- 迁移脚本：`docker/mysql/init/38_share_security_config.sql`。
- H2 测试 schema：`st-core/src/test/resources/schema.sql`、`st-share/src/test/resources/schema.sql` 已同步。

## 关键文件

- 后端：`st-common/sysconfig/*`、`st-share/service/ShareBruteForceGuard.java`、`ShareCaptchaService.java`、`ShareServiceImpl.java`、`st-admin/controller/SysConfigController.java`。
- 前端：`st-web/pages/ShareAccessPage.tsx`、`st-web/pages/AdminPage.tsx`、`st-web/components/admin/ShareSecurityPanel.tsx`。
