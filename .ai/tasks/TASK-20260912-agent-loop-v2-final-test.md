# TASK-20260912-agent-loop-v2-final-test

## 目标

在独立 tester 身份下执行统一 Agent Loop V2 门禁，并确认当前 revision 可进入 TEST_PASS。

## include

- `AGENTS.md`
- `.ai/**`
- `.github/workflows/ai-loop-gate.yml`

## exclude

- 业务代码修改
- State 修改

## 验收

- `pwsh -NoProfile -File .ai/scripts/run-loop-gate.ps1` 退出码 0。
- 报告静态门禁、State suites、Worktree suites 和 reconcile 结果。

## 输出

只返回独立测试结论，不修改文件。主线程生成 `.ai/docs/20260911-agent-loop-v2/testreport.md`。
