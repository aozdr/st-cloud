# 体验范围与验证
当前涉及UI：TC02-18成员修改失败恢复。沿用现有页面/样式，不重新设计。
状态：下拉选择是待提交值；角色徽标是已保存值；请求中禁用下拉；失败显示未保存及可访问重试按钮；失败不丢选择，成功才改变成员角色。错误关联aria-describedby，状态用role=status，按钮保留键盘语义。
浏览器证据：browser-team-actions-4.log复现旧选择回退；browser-team-acl-final-2.log修复后权限/网络失败保留及重试通过。大ID同名角色ACL保存与回显分别按完整字符串识别。browser-team-requests.json保存实际请求参数；API为受控夹具，不冒充真实服务端端到端。后端MVC/数据库由独立Java测试验证。
该小范围修复不存在待确认的设计决策；其余预览UI验收尚未完成，不能关闭整体EXP_ACCEPT。

流程工具缺口：loopctl要求EXP_DESIGN独立评审或单人授权，但当前schema的singleAgentAuthorization.criteria不允许EXP_DESIGN，无法表达用户已明确要求全部由当前主线程完成的授权。本轮不伪造child或跳过适用UI，设计文档与实测证据已保留，相关State门禁保持未完成；不据此终止其他验收工作。
