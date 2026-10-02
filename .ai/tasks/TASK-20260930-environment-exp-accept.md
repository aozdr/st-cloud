# 当前独立体验验收

目标：按定版uispec独立体验验收当前界面，补 V06 真实浏览器实际团队删除→回收站→恢复；核对认证持续登录/临时断网和胶卷17项的实际证据边界。
include: .ai/docs/20260930-environment-remediation/exp-accept*；.ai/runtime/results/DISPATCH-env-exp-accept-01.json；自建专用浏览器fixture（同目录exp-accept-runtime，最终令牌脱敏）。
exclude: 产品源码、State、历史记录、Maven/Git、Docker/8080进程停止、用户已有账号/文件。允许启动自己的Web预览（5173/5174无占用再启动）、真实Chrome headless与随机专用测试账号/小额团队空间/文件；仅对本任务fixture软删除恢复，不删除共享资源。当前8080为本轮后端；完整回归稍后会更新后端，运行前核对HTTP就绪，主线程不在你活动期间重启。Web dist已当前构建。Node Playwright bundled，Chrome已可运行，见web-filmstrip-test.cjs。
真实页面完成用户可见删除、回收站查看、恢复，保存截图/脱敏动作和真实HTTP状态。普通成员不可恢复是权限行为，可用第二专用账号做管理员角色关系；不要改DB提升系统权限，不依赖旧admin密码。无法完成的外部/部署/权限条件如实报告。实机Electron仍外部缺口，不能冒充。
输出EXP_ACCEPT proposal env-code-r2、by=/root/exp_accept；尚未冻结r2请先做验证，主线程通知r2/IMPLEMENTED后再给正式建议。无需用户确认。
