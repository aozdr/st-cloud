# TASK-SEC-PERM-UI（前端权限 UI 安全复检 — reviewer/security）

## 元信息

- Task ID: `TASK-SEC-PERM-UI`
- 归属 Agent: reviewer（taskType=security）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 迭代: 20260814-permission-ui（CODE_REVIEW 已 done，安全条件项）

## 目标

对前端权限 UI 迭代做安全复检（权限相关，SECURITY_REVIEW 条件项启用），输出 `.ai/docs/20260814-permission-ui/security.md`：

1. effective-permissions 接口越权（他人文件/未登录/非成员返回空集）。
2. 分享权限上限（分享权限 ⊆ 有效权限 + share 前置；个人文件上限 {view,download} 与接口一致）。
3. 文件夹权限配置接口（setFolderPermissions）权限校验（仅管理员/管理角色可配置；all 主体不可越权）。
4. 下载/流式三重闸门（allowDownload + permission + permissions 含 download）。

## 范围

- include（读）：st-web 三个组件 + types、st-share/st-team 权限相关代码、design/codereview、`.ai/dispatch/**`
- include（写）：`.ai/docs/20260814-permission-ui/security.md`
- exclude：修改业务代码、创建子 Agent

## 验收标准

- security.md 含逐项结论（PASS/BLOCK）+ 问题清单（等级+位置+建议）

## 验证

- 主线程核对 security.md；BLOCK 项 rework
