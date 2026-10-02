# 当前修订独立验收审计

目标：在KNOWLEDGE后按requirement的五条完成标准审计当前env-code-r2，只提出ACCEPT建议，主线程独占Goal判定。
输入：当前State最小快照、requirement/changereport/testreport/remaining-status、current-source-manifest与backend-running-final、CODE/SECURITY/EXP独立结果、.ai/knowledge/local-environment-baseline.md。历史指令只作事实，不恢复历史State。
include 写入：.ai/docs/20260930-environment-remediation/acceptance.md；acceptance-*；.ai/runtime/results/DISPATCH-env-accept-01.json。
exclude：源码/配置/State/Git/Maven/安装/真实业务变化/凭据提取。只读当前证据与少量哈希/门禁检查，不重复运行构建或浏览器，不创建child。
验收：数据库开发目标与配置、11冲突和构建、B01实际认证、V02–V06/O01–O04可运行验证及修复/实机和部署边界、新TASK独立审查及主线程串行集成证据均有明确对应。46条件跳过不冒充通过，桌面批次重叠不合计；运行最终SHA、模块沿用有效性、令牌脱敏/保留审计/测试逻辑容量归还事实一致。
输出中文约定格式，ACCEPT/env-code-r2、by实际child proposal；发现当前未解决产品缺陷或有效证据缺口要fail，不将已明确外部/生产边界写成已完成。不得宣布整个Goal完成或修改State。
