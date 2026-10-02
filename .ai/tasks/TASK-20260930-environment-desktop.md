# B01 桌面令牌轮换修复

Task ID: TASK-20260930-environment-desktop。
State: .ai/state/20260930-environment-remediation.yaml。
输入：.ai/docs/20260930-environment-remediation/design.md、testcases.md、requirement.md。历史 remaining-issues.md 仅为问题事实。
include：st-desktop/src/api-client.ts、main.ts、preload.ts、新认证辅助/测试；st-desktop/package.json 的测试入口；st-web/src/lib/api.ts、store/auth.ts、types/electron.d.ts 或现有 Electron 类型文件、新认证测试；.ai/docs/20260930-environment-remediation/desktop-*；本 dispatch 结果。
exclude：其他 UI、同步引擎、数据库/后端、历史文档/State、Maven 构建、Git 操作。
目标：刷新响应更新成对 token；并发 401 单次刷新，较旧 access 导致的迟到 401 使用最新 access；刷新失败、退出/换服/新登录隔离；主进程变更通知 renderer 持久化，重启从持久状态恢复。
验证 AUTH01/AUTH02：真实本地 HTTP 受控接口连刷两轮、并发、拒绝旧 refresh、失败、旧刷新不能覆盖新会话；类型检查。不要宣称真实 Spring/Redis 或 Electron 实机验证。使用已有 node_modules，不改 lockfile、不安装无关依赖。
返回中文正式结果与 IMPLEMENTED proposal（子任务部分实现，不替主线程宣布整体完成）。
