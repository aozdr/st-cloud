# TASK-SEC-SHARE（分享访问链路安全复检 — reviewer/security）

## 元信息

- Task ID: `TASK-SEC-SHARE`
- 归属 Agent: reviewer（taskType=security）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: M1 分享过期改动后需 SECURITY_REVIEW 复检

## 目标

对 st-share 分享访问链路做安全复检（M1 改动后），重点：

1. 过期校验：`accessShare` 的 `SHARE_EXPIRED(3002)` 判定、过期时间比较（Asia/Shanghai）、创建/更新时未来时间校验是否可绕过。
2. `clearExpireAt` 权限：只有分享所有者能清除/修改过期时间；普通用户不可篡改他人分享。
3. 越权：非所有者更新/取消分享是否被拒；分享 code 是否可枚举/可预测。
4. 提取码：密码校验路径、错误次数限制（如有）、敏感信息泄露（日志/响应）。
5. 状态流转：取消/过期/永久三种状态的访问控制组合。

## 范围

- include（读）：`st-share/**`、`st-core` 相关（file_node 权限）、`.ai/docs/20260813-share-expiry/**`、`.ai/docs/20260814-project-code-review/spec.md`
- include（写）：`.ai/docs/20260814-project-code-review/security-recheck.md`
- exclude：修改任何 `st-*` 代码、创建子 Agent

## 验收标准

- 输出 `security-recheck.md`：审查范围 / 逐项结论（PASS/BLOCK）/ 问题清单（等级+位置+建议）/ 结论
- 每个结论引用代码位置；发现问题给出可执行修复建议

## 验证

- 主线程核对 security-recheck.md 结构与引用位置
