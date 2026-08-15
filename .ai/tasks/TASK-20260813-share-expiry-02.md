# TASK-20260813-share-expiry-02（前端）

## 元信息

- Task ID: `TASK-20260813-share-expiry-02`
- 关联任务 State: `.ai/state/20260813-share-expiry.yaml`
- 关联文档: `.ai/docs/20260813-share-expiry/design.md` / `testcases.md` / `requirement.md`
- 归属 Agent: frontend-engineer
- 创建者: workflow-manager
- 日期: 2026-08-13

## 目标

修复 st-web 分享过期时间的时间格式与时区语义不一致问题，并在分享管理页对已过期分享展示"已过期"状态；移除 `ShareAccessVO` 类型中的死字段 `isExpired`。

## 修改范围

### 模块/目录

- `st-web/src/components/share/ShareDialog.tsx`
- `st-web/src/pages/ShareManagePage.tsx`
- `st-web/src/types/index.ts`

### 行为变更

1. `ShareDialog.tsx` 的 `computeExpireAt`：由 `new Date().toISOString()`（UTC 带 Z）改为本地时间格式 `yyyy-MM-ddTHH:mm:ss`（无时区后缀，手写补零）。有效期选项（1 天/5 天/7 天/无限期，默认 7 天）与无限期不提交 `expireAt` 的行为保持不变。
2. `ShareManagePage.tsx`：新增已过期判断（`status === 1 && expireAt 早于当前时间`），状态列对已过期分享展示琥珀色"已过期"徽标；保留取消按钮；不改动筛选 Tab 结构。
3. `types/index.ts`：`ShareAccessVO` 接口删除 `isExpired: boolean`（后端已删除该字段）。

## 禁止修改范围

- 不得修改分享弹窗的有效期选项文案/默认值（保持 1/5/7 天 + 无限期）。
- 不得修改 `st-share/**`、`st-desktop/**`、`st-team/**` 等其它模块代码。
- 不得改动分享管理页的 API 调用与既有交互（复制链接/复制提取码/取消分享）。
- 不得新增前端依赖。

## 验收标准

- [ ] `computeExpireAt` 输出 `yyyy-MM-ddTHH:mm:ss`（本地时间，无 `Z`、无毫秒）。
- [ ] "无限期"选项仍不提交 `expireAt`。
- [ ] 管理页对已过期分享展示"已过期"徽标，未过期与永久分享展示不变。
- [ ] `types/index.ts` 中 `ShareAccessVO.isExpired` 已删除且全项目无引用。
- [ ] `npm run build`（或 `tsc`）通过。

## 测试要求

- `cd st-web && npm run build` 通过；如有 lint 脚本一并运行。
- 手动验证点：分享弹窗创建有限期/无限期分享请求体格式；管理页过期状态渲染（可在浏览器 DevTools 中临时改 `expireAt` 验证）。

## 输出要求

- 编码完成后将变更情况追加到 `.ai/docs/20260813-share-expiry/changereport.md`（修改文件清单 / 与验收标准对照 / 构建结果 / 风险）。
- 返回 State Delta：列出改动文件、构建结果、未覆盖项。
