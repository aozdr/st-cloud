# TASK：团队搜索与文件关注前端

- taskId：TASK-20260921-team-search-watch-frontend
- 模型：GPT-5.6-Luna max；不得更换模型。
- State：.ai/state/20260921-team-search-watch.yaml；design revision：tsw-design-r1。
- 输入：.ai/docs/20260921-team-search-watch/{requirement,uispec,design,testcases}.md。

## 目标
按已定版界面和 API 契约实现团队搜索、团队目标定位、详情关注按钮、我的关注页和通知安全跳转，保持个人搜索和原通知兼容。

## 范围
- 仅 st-web/** 中本功能必要的页面、组件、hooks、类型、导航及有意义的测试。
- [planned-output] .ai/docs/20260921-team-search-watch/frontend-changereport.md。
- .ai/runtime/results/DISPATCH-20260921-TSW-FE-A1.json。
禁止修改其他模块、依赖锁文件（无新增依赖需求）、全站视觉系统、State、TASK、需求/设计；禁止 Git 提交/重置、派生 Agent。

## 约束
严格采用 design.md HTTP 路由/字段，后端编码并行无需等待接口上线即可按契约实现。关注入口在 FileDetailPanel，避免每行查询；团队预览必须通过团队上下文，失权目标不跳转；搜索范围切换防迟到响应。沿用 tokens/组件、保证窄屏与键盘可操作。
读取适用 frontend 技能并汇报使用；用户现有设计约束优先于通用审美建议，不建立无需求新设计体系。

## 验证
可运行 st-web 自身已有 typecheck/build/test（不调用 Maven，不与后端共用缓存）；如命令或环境不支持，报告真实限制。验收 S06/W01/W02/W07/U01，覆盖竞态与接口契约；每约60秒回报进度。
独立结果 by=/root/luna_frontend_0921，criterion=IMPLEMENTED，revision=tsw-code-r1；真实测试证据与变更清单，禁止修改 State 或声称整体完成。




### A2 375px实测修复
团队搜索类型筛选条在375px标签逐字竖排且右侧溢出。最小响应式修复，保持文字不拆字和所有类型可达，保留既有搜索契约；完成当前前端报告。
