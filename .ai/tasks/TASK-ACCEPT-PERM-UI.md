# TASK-ACCEPT-PERM-UI（前端权限 UI 验收 — reviewer/accept）

## 元信息

- Task ID: `TASK-ACCEPT-PERM-UI`
- 归属 Agent: reviewer（taskType=accept）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 迭代: 20260814-permission-ui（最终收敛点 ACCEPT）

## 目标

对照 `goal.completionCriteria` 与 design.md 验收点逐项验收（PASS/BLOCK），输出验收结论到 `.ai/docs/20260814-permission-ui/accept.md`：

1. FolderPermissionDialog 支持 all/member/role + 9 权限点勾选 + permissions 保存（验收点 1）。
2. ShareDialog 展示可分享权限点并禁用超权项；allowDownload 联动（验收点 2）。
3. RoleManageDialog 9 权限点完整（验收点 3）。
4. 后端 effective-permissions 接口正确（个人/团队/未授权三分支）（完成标准 4）。
5. tsc/build/测试通过（完成标准 5）。
6. 代码/测试/文档齐全（codereview/security/testreport/changereport）。

BLOCK 时列出未达标项（打回实现）；PASS 时给出结论。

## 范围

- include（读）：迭代全部产物（design/codereview/security/testreport/changereport）、st-web 组件、st-share/st-team 相关代码、`.ai/dispatch/**`
- include（写）：`.ai/docs/20260814-permission-ui/accept.md`
- exclude：修改业务代码、创建子 Agent

## 验收标准

- accept.md 含逐项核对表 + PASS/BLOCK 结论；BLOCK 列未达标项

## 验证

- 主线程核对 accept.md；BLOCK → 打回实现（IMPLEMENTED 重开 + 级联回退）
