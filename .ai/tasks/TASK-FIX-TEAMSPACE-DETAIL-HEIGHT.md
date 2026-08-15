# TASK-FIX-TEAMSPACE-DETAIL-HEIGHT（详情高度延伸到页面底部 — executor/implement）

## 元信息

- Task ID: `TASK-FIX-TEAMSPACE-DETAIL-HEIGHT`
- taskCode: `TSUI-03`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14

## 目标（用户澄清）

撤销"详情占满整个文件区（隐藏文件列表）"的做法，改为：**详情面板保持原本宽度（w-80 侧边栏），但高度延伸到整个页面内容区（到容量设置/页面底部，超出文件列表区域）**；文件列表始终保留在左侧 `flex-1`。

## 实现要点（定版）

1. `TeamSpacePage.tsx` 文件区改为左右并排：
   - 左：`FileBrowser`（`flex-1 min-h-0`，始终渲染，不再被详情隐藏）
   - 右：`detailFile` 非空时渲染详情侧边栏（`h-full w-80 flex-shrink-0 border-l overflow-hidden flex flex-col`）——高度占满页面内容区（父级 `flex-col h-full` 链保证延伸到底部，超出文件列表区域）。
2. 详情侧边栏保留 **"详情 | 权限"双 tab**（TSUI-02 已实现，继续保留）：
   - 详情 tab：`FileDetailPanel variant="sidebar"`（宽度 w-80，内容纵向滚动）。
   - 权限 tab：`FolderPermissionDialog variant="panel"`（当前节点权限配置）。
3. 撤销原"详情打开时文件列表隐藏/详情占满 flex-1"的渲染分支；文件列表状态（breadcrumb/parentId）与详情并存。
4. 顶部权限弹窗做法不变。

## 范围

- include（写）：`st-web/src/pages/TeamSpacePage.tsx`（主要）、`st-web/src/components/file/FileDetailPanel.tsx`（如需要调整）
- include（读）：当前 TeamSpacePage 详情渲染段、`.ai/dispatch/**`
- exclude：后端代码、其它页面、创建子 Agent

## 验收标准

- 详情为 w-80 右侧侧边栏，高度占满页面内容区（延伸到容量设置/页面底部）
- 文件列表保留在左侧 flex-1，不受详情影响
- 详情/权限 tab 可切换，权限 tab 可配置当前节点权限并保存
- `npx tsc --noEmit` 通过（主线程统一验证）

## 验证

- 主线程统一 tsc/build；抽查结构
