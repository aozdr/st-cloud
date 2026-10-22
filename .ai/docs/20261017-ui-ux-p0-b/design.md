# 程序设计文档：弹窗与键盘可访问性（P0-B）

> 任务 ID：`20261017-ui-ux-p0-b`
> 前置：`20261017-ui-ux-p0-a`（P0-A 已完成并验收）。

## 一、目标与非目标

### 目标

- 用统一 hook 补齐弹窗焦点陷阱、Escape、滚动锁与焦点还原。
- 消除原生 `confirm()`，统一到产品内 ConfirmDialog。
- 预览页具备 dialog 语义。
- ContextMenu 支持键盘导航。

### 非目标

- 不改弹窗视觉、不重写文件操作逻辑。
- 不做 P1 的表格 ARIA、PageHeader、筛选器。
- 不引入第三方 modal 库。

## 二、方案

### 2.1 `useModalA11y`

新增 `st-web/src/components/ui/useModalA11y.ts`：

```ts
useModalA11y(panelRef, onClose, { open?, initialFocusRef?, closeOnEscape? })
```

- 打开时保存 `document.activeElement`，锁 `body.overflow`（模块级计数，支持嵌套）。
- 初始焦点：`initialFocusRef` → 当前面板内已有焦点 → 第一个可聚焦元素 → 面板本身。
- 面板 keydown：Escape 关闭；Tab/Shift+Tab 在可聚焦元素间循环。
- 清理：恢复 body overflow、移除监听、焦点还原。
- `onClose` 通过 ref 保持最新，避免每次渲染重新绑定。

### 2.2 接入范围

| 文件 | 处理 |
|---|---|
| `ui/Dialog.tsx` | 使用 hook + `createPortal`；移除旧 focus trap/Escape effect |
| `ui/ConfirmDialog.tsx` | panelRef + hook；保留 confirm 按钮初始焦点 |
| `ui/PromptDialog.tsx` | panelRef + hook；输入框初始焦点 |
| `ui/ShortcutHelpDialog.tsx` | panelRef + hook + role |
| `SettingsDialog.tsx` | panelRef + hook + role（已有 role） |
| `file/Dialogs.tsx` 三个弹窗 | panelRef + hook + role/aria-label |
| `MoveDialog`/`ConvertDialog`/`VersionHistoryDialog` | panelRef + hook + role |
| `TransferSettingsDialog`/`SyncPage` 两个 modal | panelRef + hook + role |
| `preview/PreviewModal.tsx` | 外层 ref + hook + role/aria-label；保留 ←/→ 导航 |
| `ContextMenu.tsx` | role=menu/menuitem、方向键/Home/End/Escape、打开时聚焦首项 |

### 2.3 原生 confirm 替换

- `CommentPanel`、`RoleManageDialog`、`ShareManagePage`、`TeamSpacePage`（3 处）
- 统一改用 `useConfirm()`：
  - 删除类：`danger: true` + 明确确认文案；
  - 退出空间类：`danger: true`，说明不可访问后果。

### 2.4 ContextMenu 键盘

- 容器 `role="menu"`，项 `role="menuitem"`，分隔线 `role="separator"`。
- 打开后聚焦第一项；↑/↓ 循环；Home/End；Escape 关闭并还原焦点；禁用的关注状态项跳过。
- 鼠标行为不变。

## 三、风险

| 风险 | 缓解 |
|---|---|
| 多弹窗嵌套时滚动锁计数错误 | 模块级计数，cleanup 必减一 |
| 初始焦点抢输入框 | 优先 `initialFocusRef`/当前焦点，其次首项 |
| StrictMode 双执行 | 计数成对增减，焦点还原可重复 |
| 单行压缩文件编辑 | 只做最小替换，build 验证 |
| 无浏览器环境 | 静态 + build 证据，记录限制 |

## 四、测试计划

1. `npm run lint`、`npm run build`。
2. 静态扫描：`confirm(`、`useModalA11y`、`role="dialog"`、`aria-modal`。
3. 代码审查：hook cleanup、focus trap、嵌套弹窗、ContextMenu 禁用项。
4. 若环境允许：键盘走查（打开/关闭/焦点还原/背景滚动/菜单方向键）。
5. `git diff --check`。

## 五、完成标准

见 TASK 验收清单；verification.md 逐条给证据。
