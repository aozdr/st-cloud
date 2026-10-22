# 程序设计文档：文件表格可访问性与移动排序（P1-B）

> 任务 ID：`20261017-ui-ux-p1-b`
> 前置：P1-A 已完成；P1-A 的移动端工具栏隐藏了排序控件。

## 一、目标

- 让文件表格成为语义完整的 ARIA grid，而不是只有 `role=table/row/columnheader` 的外壳。
- 恢复移动端排序入口，避免 P1-A 隐藏桌面排序后移动端无法排序。
- 两个改动分属不同文件，可并行实施。

## 二、方案

### 2.1 FileTableView ARIA（子任务 A）

- 容器：`role="grid"`，`aria-label="文件列表"`，`aria-multiselectable="true"`，`aria-rowcount={files.length + 1}`，`aria-colcount={7}`。
- 表头：`role="row"`；每列 `role="columnheader"`；排序列的 `aria-sort` 从内部 button 移到 columnheader；内部按钮补 `aria-label="按名称排序"`。
- 行组：表头 `role="rowgroup"`；虚拟行容器 `role="rowgroup"`。
- 数据行：`role="row"`、`aria-selected={isSelected}`、`aria-rowindex={vi.index + 2}`、roving `tabIndex`（有 focusedId 时该行 0，否则首行 0，其余 -1）。
- 数据单元格：名称/类型/大小/时间/所有者/操作均 `role="cell"`；复选框列保持 columnheader/cell 语义。
- 不改变虚拟列表、选择、点击、双击、拖拽逻辑。

### 2.2 移动排序入口（子任务 B）

- `FileToolbar` 增加仅 `<md` 显示的 `Popover` 触发器：
  - 按钮 36×36，`aria-label="排序"`，图标 `ArrowDownUp`。
  - 内容：名称/大小/修改时间三个选项（当前项高亮）、升/降序按钮、文件夹优先开关。
- 桌面端保留原有排序下拉与文件夹优先，行为不变。
- 仅在无选择态显示，避免和批量操作冲突。

### 2.3 主线程验证

- 合并后 `npm run build` / `npm run lint` / `git diff --check`。
- CDP 浏览器：
  - 1440：grid role/label/rowcount/colcount、columnheader aria-sort、row aria-selected、cell 数量。
  - 390：排序按钮可见且不裁切；打开菜单选择“大小”，检查排序状态变化；无横向溢出。
- 不改变 API/数据。

## 三、风险

| 风险 | 缓解 |
|---|---|
| 虚拟行嵌套导致 ARIA grid 结构不合法 | 表头/虚拟容器都加 rowgroup；行内全部加 cell |
| roving tabIndex 与全局键盘冲突 | 只影响 Tab 进入点；方向键仍由既有 hook 处理 |
| 移动 Popover 与桌面排序重复 | 移动触发器 md:hidden，桌面组 hidden md:flex |
| 两个子任务同时构建 | 子任务只改各自文件，不跑 build；主线程合并后统一构建 |

## 四、测试计划

1. 子任务 A/B 各自 lint 自己的文件（可选）。
2. 主线程 build/lint/diff-check。
3. 浏览器 1440/390 检查 ARIA、排序、溢出。
4. 回归右键菜单、行选择、双击打开。

## 五、完成标准

见 TASK 验收清单；verification.md 逐条给证据。
