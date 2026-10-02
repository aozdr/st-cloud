# TASK-20261001-review-fixes-code

目标：独立复核本轮两处修复的实现与真实云盘行为；只提出可复现、影响用户或存储生命周期的问题。

只读输入：主 TASK、定版 design.md/testcases.md、changereport.md、source-manifest-r4.json，和其中列出的六个产品/测试文件。现有 Git 工作区包含大量既有改动，本任务只核对本轮补丁；不要将旧改动算作本轮新问题。写入白名单：.ai/docs/20261001-review-fixes/r4/codereview.md、.ai/runtime/results/DISPATCH-review-fixes-code-04.json。禁止源码/State/数据库/Git index/部署写入，禁止 Maven 和共享缓存构建，禁止派发。可执行有针对性的只读 Node 复现（不修改源文件）。

完成：记录背景、输入、分析、决策、State Delta proposal、风险、下一步、变更影响；事实与推测分开。核对真实云盘场景，发现可复现回归则 fail 并给位置、触发条件、影响，否则 pass。对照当前源码 SHA256 校验 revision review-fixes-code-r4；不把不相关旧测试报告当成证据。

返回 CODE_REVIEW criterionProposal，by 使用 Runtime canonical child identity，evidenceRef 使用独立 result 路径，validatedRevision=review-fixes-code-r4。不得宣布整个 Goal 完成；由主线程 Evaluate。



