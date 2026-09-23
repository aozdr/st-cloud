# TASK-20260922-team-search-watch-frontend

补充同一前端闭环验收：NotificationBell 目前仅定义未挂载，需接入既有公共头部，使桌面与移动端都可打开站内提醒。沿用 st-web include 范围与原定版通知交互，不新增业务功能。

模型：GPT-5.6 Terra high。目标：修复375px团队搜索筛选条逐字竖排和压缩包类型溢出问题：SearchPage约723行。保持既有设计，标签不拆字图标不压缩，可合理换行或局部横向滚动，所有类型键盘可达。不改接口及搜索业务。

输入：当前 design.md/testcases.md/requirement.md；最小 State .ai/state/20260922-team-search-watch.yaml。

include：st-web/**, .ai/docs/20260922-team-search-watch/frontend-changereport.md, .ai/runtime/results/DISPATCH-20260922-TSW-FE-A3.json
exclude：st-core/**, st-team/**, st-search/**, st-common/**, docker/**, .ai/state/**

保留已有实现；禁止修改State、TASK、其他文档、Git提交和业务数据。不得创建子Agent。核心权限/事务逻辑中文注释，不在写事务内调用外网。
数据库SQL43已在本地开发库迁移并登记20260921.1，禁止重复执行或修改已应用SQL。
后端不运行Maven；主线程串行验证。前端可运行build/lint。必须向主线程说明文件稳定可验证，修复后返回真实独立结果，不把未执行检查称为通过。
结果：.ai/runtime/results/DISPATCH-20260922-TSW-FE-A3.json；by=/root/terra_frontend_a3；revision=tsw-code-r2；criterionProposal仅建议。
历史日志只作故障定位：.ai/docs/20260921-team-search-watch/search-tests-r2.log、backend-runtime.log、testreport.md，不沿用历史通过结论。

当前验收返修：主线程375px浏览器发现 NotificationBell 弹层左侧超出视口，因为 absolute right-0 相对靠中间铃按钮定位而宽320px。修复移动端弹层在屏幕内完整显示并保持桌面锚定。补齐加载中/请求失败/重试状态，避免请求失败显示暂无通知。保留搜索筛选修复，不改API。用apply_patch编辑，运行build/lint。主线程独占浏览器。
