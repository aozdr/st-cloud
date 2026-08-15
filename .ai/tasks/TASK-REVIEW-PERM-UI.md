# TASK-REVIEW-PERM-UI（前端权限 UI 迭代 Code Review — reviewer/review）

## 元信息

- Task ID: `TASK-REVIEW-PERM-UI`
- 归属 Agent: reviewer（taskType=review）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 迭代: 20260814-permission-ui（IMPLEMENTED 已 done，进入 CODE_REVIEW）

## 目标

对前端权限 UI 迭代的代码改动做 Code Review（两轴：标准符合度 + 需求/设计符合度），输出 `.ai/docs/20260814-permission-ui/codereview.md`。

## 审查范围

- `st-web/src/components/team/FolderPermissionDialog.tsx`（all/member/role 主体 + 9 权限点勾选 + permissions payload）
- `st-web/src/components/team/RoleManageDialog.tsx`（9 权限点校验/隐含 view/防御式解析）
- `st-web/src/components/share/ShareDialog.tsx`（effective-permissions 调用 + 超权禁用 + allowDownload 联动）
- `st-web/src/types/index.ts`（CreateShareRequest.permissions、FolderPermissionItem 类型）
- `st-share` 后端 `GET /api/share/effective-permissions` 接口（ShareController/ShareService/Impl）
- 对照：`.ai/docs/20260814-permission-ui/design.md`、`changereport.md`、`.ai/docs/20260814-permission-model/design.md`

## 检查重点

1. **标准符合度**：前端规范（conventions/frontend.md）、类型安全、权限点 key 与后端一致、异常处理（接口失败/权限为空）、无重复实现。
2. **需求/设计符合度**：对照 design.md 验收点——all 主体、9 权限点、超权禁用、allowDownload 联动、后端接口分支（个人/团队/未授权空集）。
3. **安全**：effective-permissions 接口越权（他人文件返回空集）、分享超权兜底。

## 范围

- include（读）：上述审查文件 + design/changereport + 技能（code-review 及自主发现的相关技能）
- include（写）：`.ai/docs/20260814-permission-ui/codereview.md`
- exclude：修改任何业务代码、创建子 Agent

## 验收标准

- codereview.md 含：审查概览 / 问题清单（等级+位置+建议）/ PASS 或 BLOCK 结论
- 每条发现引用代码位置；BLOCK 时列必须修改项

## 验证

- 主线程核对 codereview.md；BLOCK 项进入 rework
