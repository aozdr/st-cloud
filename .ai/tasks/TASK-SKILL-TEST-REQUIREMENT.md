# TASK-SKILL-TEST-REQUIREMENT（skillRefs 触发实测：需求评审 + grill me — executor/requirement）

## 元信息

- Task ID: `TASK-SKILL-TEST-REQUIREMENT`
- 归属 Agent: executor（taskType=requirement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 目的: 验证派发强制携带 skillRefs 机制（子代理执行前必须读取 SKILL.md 并按其流程执行）

## 目标

对以下原始需求执行**需求评审（grill me 拷打）**并产出需求文档：

> 原始需求：给团队空间的文件/文件夹加"权限配置"功能。管理员可以在文件详情里设置权限，可以针对全体成员（管理员除外）或单独用户配置权限；配置后该用户在该文件夹及子文件的有效权限 = 角色权限 + 配置权限（增强）。分享出去的文件权限不能超过配置权限。

## 执行要求（skillRefs 强制）

1. ACK 后、读 TASK 前，**先完整读取 skillRefs 指向的每个 SKILL.md 全文**：
   - `C:/Users/Administrator/.agents/skills/grill-me/SKILL.md`
   - `C:/Users/Administrator/.agents/skills/prd-development/SKILL.md`
   - `C:/Users/Administrator/.agents/skills/user-story/SKILL.md`
2. 按 grill-me 流程对需求**拷打式提问**：用户是谁、真实痛点、现有方案为何不够、最小可用范围、边界与异常、权限语义等；输出问题清单与澄清结论。
3. 按 prd-development 结构产出需求文档（含问题陈述/用户/方案/成功标准/用户故事/验收标准/范围外/开放问题）。
4. 落盘 `.ai/docs/20260814-skill-test-requirement/requirement.md` 并返回 State Delta（注明"已读取哪些 SKILL.md、grill me 问题数"）。

## 范围

- include（读）：skillRefs 三个 SKILL.md、`.ai/docs/20260814-permission-model/design.md`（权限模型参考）、`.ai/knowledge/role-context.md`、`.ai/dispatch/**`
- include（写）：`.ai/docs/20260814-skill-test-requirement/requirement.md`
- exclude：修改任何 `st-*` 业务代码、其它 `.ai/` 文件、创建子 Agent

## 验收标准

- 会话日志确认读取了 3 个 SKILL.md（shell 读取记录）
- requirement.md 含 grill me 问题清单（≥5 问）+ PRD 结构章节
- 未改业务代码

## 验证

- 主线程核对会话日志的 SKILL.md 读取记录与 requirement.md 内容
