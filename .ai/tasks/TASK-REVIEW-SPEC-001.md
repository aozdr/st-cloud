# TASK-REVIEW-SPEC-001（全库 Spec 审查：文档-代码一致性 — reviewer/review）

## 元信息

- Task ID: `TASK-REVIEW-SPEC-001`
- 归属 Agent: reviewer（taskType=review）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 模式: 只读审查（禁止修改任何业务代码）

## 目标

全库无单一 PRD，本轴定义为**文档-代码一致性**审查：对照 `.ai/docs/` 各迭代 requirement/design 与 `.ai/tasks/`，检查已交付功能在文档与代码间是否存在漂移。产出 `.ai/docs/20260814-project-code-review/spec.md` 并返回 State Delta。

## 方法

1. 列出 `.ai/docs/` 下各迭代目录的 requirement.md / design.md / uispec.md（重点：20260813-share-expiry、20260813-block-sync、20260813-upload-rate-throttle、20260812-schema-versioning、20260812-sync-full-reconcile、favorites-enhancement、20260809-teamspace-*、mobile-pwa-capacitor 等）。
2. 抽取关键承诺：接口契约（路径/方法/字段）、数据模型变更（表/字段）、核心行为（状态流转、权限、配额、分享过期、块级同步）。
3. 抽样核对代码：`st-*/**` 中对应 Controller/Service/Mapper/Entity、`docker/mysql/init/*.sql`、前端页面与 API 调用。
4. 分类漂移：
   - **文档承诺未实现**（文档写了但代码缺失/半成品）
   - **代码存在但文档未同步**（实现已变、文档还写旧契约）
   - **接口契约不一致**（路径/参数/字段与文档不符）

## 范围

- include（允许）：读取 `st-*/**`、`docker/mysql/init/**`、`.ai/docs/**`、`.ai/tasks/**`、`.ai/knowledge/api-reference.md`、`business-domain.md`、`.ai/dispatch/**`（收件箱）；写入 `.ai/docs/20260814-project-code-review/spec.md`
- exclude（禁止）：修改任何 `st-*` 代码、数据库脚本、`.ai/` 其它文件；创建子 Agent；读取其它审查章节

## 输出（spec.md 结构）

1. 审查概览（文档清单/核对范围）
2. 文档承诺未实现（逐条引用文档路径+条款+代码现状）
3. 代码存在但文档未同步（引用文档路径+差异）
4. 接口契约不一致（路径/参数/字段差异）
5. 结论与建议（是否需补文档/补实现，按优先级）

## 验收标准

- `spec.md` 存在且覆盖上述结构
- 每条漂移点引用对应文档路径；差异描述具体（接口路径/字段/行为）
- 未修改任何 `st-*` 代码；未创建子 Agent

## 验证

- 主线程检查 spec.md 结构与内容质量、会话日志确认未写业务代码
