# TASK：TASK-20261017-ui-ux-p1-b 文件表格可访问性与移动排序

> 依据：`.ai/docs/20261017-ui-ux-p1-b/design.md`。
> P1-B 接在 P1-A 之后；本轮拆成两个文件不重叠的并行子任务：表格 ARIA 与移动排序入口。

## 元信息

- Task ID：`20261017-ui-ux-p1-b`
- 关联 State：`.ai/state/20261017-ui-ux-p1-b.yaml`
- 归属 Agent：executor（taskType=implement）
- 日期：2026-10-17

## 目标

1. 将文件表格从“有 role=table/row/columnheader 但无 cell/aria-selected”的半成品语义升级为可被读屏识别的 grid；
2. 恢复 P1-A 在移动端隐藏排序后失去的排序入口；
3. 用真实浏览器验证 1440 与 390 下的 ARIA、排序和布局。

## 并行拆分

- 子任务 A（文件唯一）：`st-web/src/components/file/FileTableView.tsx`
- 子任务 B（文件唯一）：`st-web/src/components/file/FileToolbar.tsx`
- 主线程：TASK/design/state、浏览器脚本、合并后 build/lint、文档。

## 验收标准

- [ ] 表格容器 `role="grid"`，有 `aria-label`、`aria-multiselectable`、`aria-rowcount`/`aria-colcount`。
- [ ] 表头列的 `aria-sort` 位于 `columnheader` 上。
- [ ] 数据行有 `aria-selected`，数据单元格有 `role="cell"`，行组有 `role="rowgroup"`。
- [ ] 行使用 roving tabIndex（焦点行 0，其余 -1）。
- [ ] 移动端工具栏有“排序”入口，可选择名称/大小/时间、切换升降序与文件夹优先。
- [ ] 390px 无横向溢出；排序入口不裁切。
- [ ] `npm run lint`、`npm run build`、`git diff --check` 通过。
- [ ] 真实浏览器 1440/390 检查通过。

## 禁止修改范围

- 不改后端/数据库/桌面端。
- 不做 P1-C 导航、P2 次级页、PWA 安装。
- 两个子任务不得修改对方文件；主线程负责最终构建。

## 输出

`.ai/docs/20261017-ui-ux-p1-b/verification.md`。
