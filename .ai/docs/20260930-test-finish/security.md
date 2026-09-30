# 主线程安全自检
root执行，用户当前TASK单人授权；非独立安全评审。
输入：JwtAuthenticationFilter、UserSecurityService、AuthService/JwtUtils、安全管理写路径、TeamService/显式主体策略、分享授权计数、ThumbnailRenderer、同步路径与身份处理，以及实际撤权/异常/跨进程证据。

核对结果：令牌用途/版本/用户租户状态失败即拒绝；旧Access/Refresh不借Redis登记绕过主库，随机jti与真实Lua CAS只一胜；改密/撤权后另一JVM立即拒绝，在途请求边界有记录。缺共享搜索密钥初始化失败，游标上下文/签名/TTL不泄漏测试密钥。外部协作撤回和失效角色一致拒绝查看/上传/搜索。分享前置鉴权、限额占位与字节输出关系成立；预签名授予语义允许同URL直连复用，未误报应用次数。缩略图只经授权入口，真实流字节/像素/并发硬限额，不支持SVG/WebP缩略图拒绝并安全展示原图，浏览器SVG仅img、无脚本/外链。

同步根/身份/恢复边界：跨租户/用户/根拒绝，路径穿越/联接/跨卷失败保全，未知身份和失败页不写错误归属、不丢原件。没有生产/共享环境数据写入，本轮资源均任务标签、127.0.0.1端口与无宿主绑定挂载。

结果：目标范围未发现未解决的安全阻断。日志中的隔离测试密码不是生产凭证；不输出JWT或预签名URL到报告。部署限制：保留BIGINT、禁止旧Integer服务处理大自定义角色；旧无版本会话重登录，排空仅验签旧鉴权实例。schema扩展不等于撤权发布完成。
State Delta：建议SECURITY_REVIEW pass，finish-contract-tests-v9；主线程Evaluate。
