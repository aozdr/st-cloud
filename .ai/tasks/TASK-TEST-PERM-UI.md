# TASK-TEST-PERM-UI（前端权限 UI 测试验证 — tester/test）

## 元信息

- Task ID: `TASK-TEST-PERM-UI`
- 归属 Agent: tester（taskType=test）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 迭代: 20260814-permission-ui（CODE_REVIEW done，进入 TEST_PASS）

## 目标

测试验证（单进程串行）并输出 `.ai/docs/20260814-permission-ui/testreport.md`：

1. 运行 `mvn -q -pl st-share,st-team -am test`（单进程串行，含权限模型/分享上限回归用例）。
2. 前端：`npx tsc --noEmit`（st-web）确认 0 错误；核对 design.md 前端验收点（代码级核对：all 主体、9 权限点、超权禁用、allowDownload 联动、effective-permissions 调用）。
3. 汇总：各模块测试统计 + 前端验收点核对表 + 失败问题清单。

## 范围

- include（读）：st-web 组件/types、st-share/st-team 测试与代码、design.md、codereview.md、`.ai/dispatch/**`
- include（写）：`.ai/docs/20260814-permission-ui/testreport.md`
- exclude：修改业务代码、创建子 Agent

## 验收标准

- 单进程 mvn test 完成（统计全绿或失败清单）；tsc 0 错误
- testreport.md 含前端验收点核对表

## 验证

- 主线程核对 testreport.md；失败项 rework
