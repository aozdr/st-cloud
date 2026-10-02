# 历史门禁修复

本轮按 small 处理，仅修复历史 State 元数据和本地分支归属，不修改业务代码或降低校验规则。

## State 修复

`.ai/state/20260912-ui-ux-polish.yaml` 继续使用冻结 V2 定义。DESIGN、TESTCASES、IMPLEMENTED 的执行身份改为 direct，executionId 引用原有历史事件，不伪造 Dispatch。

CODE_REVIEW 和 TEST_PASS 等记录由实现者自检，缺独立身份或当时用户单人授权证据；SECURITY_REVIEW 缺跳过审批；验收证据未逐条匹配目标。将这些节点及后续依赖标为 stale，顶层改为 incomplete，记录 open blocker。原状态、节点和验收证据保存在新增 history.before 中，原报告全部保留。本轮未重新完成历史 UI 任务或重跑其业务测试。

## 分支归档

原 codex/frontend-review-fixes 没有工作树或事务登记，且比 main 多一个未合并提交。可逆重命名为 archive/frontend-review-fixes-20261002，保留提交 5b606537fd64052aafe8a4a16e1dfe458dbccc9d 和 reflog。不创建虚假事务、不删除提交。恢复原名可用 git branch -m archive/frontend-review-fixes-20261002 codex/frontend-review-fixes；恢复前需要处理受管分支归属。

## 验证

- 历史 State validate：PASS。
- git diff --check：通过。
- 完整 run-loop-gate.ps1：退出码 0，LOOP_GATE_PASS；静态 FAIL=0、资源 RECONCILED issues=0；Loop 三套测试通过，Worktree 集成测试 8/8 通过。原始输出见 gate.log。
- Git safe.directory 仅通过进程环境传给子进程，未修改全局设置。