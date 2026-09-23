# 启动登录态恢复测试用例

1. 新会话仅保留有效 refresh token：恢复期间受保护路由只显示加载状态；刷新成功后才挂载主页，启动链路没有 `/auth/me`、`/file/storage` 等 401。
2. refresh token 已过期或被服务端撤销：清除两种 token，受保护路由跳转登录页，业务页面不挂载。
3. access token 未过期：无需刷新即可进入受保护页面。
4. access token 即将过期且存在 refresh token：先刷新再进入页面。
5. React StrictMode 双 effect：同一次启动最多一个刷新请求。
6. 登录或注册成功：令牌持久化、用户信息读取与页面跳转仍正常。
7. 静态验证：`tsc` 和 Vite 构建通过，既有未提交修改不受影响。
