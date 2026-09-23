# TASK-20260923-desktop-auth-startup

## 目标

修复 PC 客户端重开后先进入受保护页面、并发产生 `/auth/me`、文件和收藏等 401 请求的问题。有效 refresh token 应在业务页面挂载前换取 access token；无效令牌应回到登录页。

## 范围

- 前端认证状态的启动恢复与受保护路由门禁。
- 与启动恢复直接相关的验证。

## 验收标准

1. 仅有有效 refresh token 时，先刷新令牌再挂载主页，启动期间不请求受保护业务接口。
2. refresh token 无效时清理本地登录态并显示登录页，不挂载主页。
3. 有有效 access token 时直接进入主页；开发模式重复挂载不重复刷新。
4. 登录、注册、主动刷新和既有 401 拦截器行为保持可用；前端类型检查及构建通过。

## 写入范围

- `st-web/src/store/auth.ts`
- `st-web/src/store/auth-startup.test.mjs`
- `st-web/src/App.tsx`
- `.ai/docs/20260923-desktop-auth-startup/**`
- `.ai/state/20260923-desktop-auth-startup.yaml`
- `.ai/tasks/TASK-20260923-desktop-auth-startup.md`

## 排除范围

- 后端认证接口、令牌有效期、数据库、其他页面与现有未提交改动。
