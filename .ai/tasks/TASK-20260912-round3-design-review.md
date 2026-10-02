# TASK-20260912-round3-design-review
角色 reviewer；独立技术可行性评审，不写业务代码或 State，不创建子 Agent，不执行构建。
输入：.ai/docs/20260912-code-review-round3/requirement.md、uispec.md 和当前源码。
评审候选方案：四个 canonical 写入点改成每次上传 UUID 独立物理路径，tenant+MD5 数据库去重保持。新增持久 object_upload_candidate 表，PUT 前登记 WRITING，提交引用的同一数据库事务内 CAS WRITING→ADOPTED 或 DISCARDED（竞争失败的路径）；失败置 DISCARDED。GC 30-60分钟后 CAS 认领 WRITING/DISCARDED→DELETING，commit 必须 CAS 成功方可引用；事务外删除，检查对象、节点、版本及会话引用。旧 canonical 路径继续可读可复用，本轮在线 GC 不扫描历史未知对象；独立 UUID 不复用以隔离迟到 DELETE。评估长时间 PUT 与 GC 的交错，如何确保迟到 PUT 的泄漏最终可回收。评估这是对附件推荐方案的必要调整还是可用更小方案。
同时评审 relay committedSeq/inFlightSeq 严格顺序，任何部分写入失败明确 abort，Desktop 进程内保留 acknowledged seq，重启不自动续传；分享缩略图继承原 stream 权限与计数语义。
写范围 include：.ai/docs/20260912-code-review-round3/architecture-review.md、.ai/runtime/results/DISPATCH-round3-design-review-01.json。
exclude：其他所有文件（尤其业务代码、State、既有改动）。
结果须列事实/推测、具体缺口、可执行建议及 criterionProposal（TECH_DESIGN 不得宣称通过，用户尚未确认设计）。
