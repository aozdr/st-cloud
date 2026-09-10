# Change Report — TASK-UI-DOWNLOAD-FLAG（前端 allow_download 开关）

## 元信息

- Task ID: `TASK-UI-DOWNLOAD-FLAG`
- Agent: executor（taskType=implement）
- dispatchId: `ui-flag-001`
- claimedFile: `inbox-ui-flag-001.md`
- 日期: 2026-08-14
- 来源: 后端 `allow_download` 字段已就绪（TASK-FIX-SEC-DOWNLOAD-FLAG），补充前端创建/管理入口

## 背景

后端已为 `file_share` 新增 `allow_download`（0-禁止下载/流式，1-允许）并作为下载/流式的权威开关；`permission` 保留用于前端展示/兼容。本次在前端补齐对应交互：创建分享时提供"允许下载"开关（与 `permission` 联动），管理页展示每条分享的下载状态。

## 输入

- `TASK-UI-DOWNLOAD-FLAG.md`（定版目标/范围/验收）
- `TASK-FIX-SEC-DOWNLOAD-FLAG` changereport（后端 allow_download 语义：默认联动、下载/流式统一开关）
- `st-web/src/components/share/ShareDialog.tsx`、`st-web/src/pages/ShareManagePage.tsx`、`st-web/src/types/index.ts`
- `st-web/src/components/ui/switch.tsx`（项目内轻量 Switch 组件，无新增依赖）

## 分析

1. **类型已就绪**：`FileShare.allowDownload`（必填）与 `CreateShareRequest.allowDownload`（可选）已由后端任务加入 `st-web/src/types/index.ts`，本任务核对确认，无需改动。
2. **创建对话框**：原"权限"二选一按钮（仅查看 permission=0 / 可下载 permission=1）与"允许下载"开关语义完全等价；按 TASK 定版收敛为单一开关（默认开），避免两个控件表达同一维度产生冲突状态。
3. **与后端联动**：开关开 → `permission=1 + allowDownload=1`；开关关 → `permission=0 + allowDownload=0`，与后端 `createShare` 未显式传 `allowDownload` 时的联动策略一致，且显式传值更明确。
4. **管理页**：`ShareManagePage` 无编辑入口（仅取消操作），按 TASK"无编辑入口则仅展示"处理，在访问/下载列展示"允许下载/仅查看"徽标。
5. **文件结构**：`ShareManagePage.tsx` 渲染段为单行压缩代码，采用锚点插入（`{share.downloadCount}</span>` 之后、该 `<td>` 闭合前）保持最小 diff，不重构整文件。

## 决策

- `ShareDialog.tsx`：新增 `allowDownload` 状态（默认 `true`）；创建请求携带 `permission` + `allowDownload` 联动值；原"权限"双按钮替换为"允许下载"开关（`Switch` 组件，默认开）；创建成功结果区展示"允许下载/仅查看"。
- `ShareManagePage.tsx`：访问/下载列内新增"允许下载"（绿）/ "仅查看"（灰）徽标，基于 `share.allowDownload` 展示。
- `types/index.ts`：无改动（字段已存在）。
- 核心联动逻辑均添加中文注释。

## 修改文件清单

- 修改：`st-web/src/components/share/ShareDialog.tsx`
- 修改：`st-web/src/pages/ShareManagePage.tsx`
- 新增：`.ai/docs/TASK-UI-DOWNLOAD-FLAG/changereport.md`

未改动：后端代码、其它前端页面、`docker/mysql/init`、`st-web/src/types/index.ts`（已含字段）。

## 与验收标准对照

| 验收项 | 结果 |
|--------|------|
| 创建对话框有"允许下载"开关且默认开 | PASS（Switch 默认开，`allowDownload` 初始 `true`） |
| 请求携带 allowDownload 并与 permission 联动 | PASS（开 → permission=1 + allowDownload=1；关 → permission=0 + allowDownload=0） |
| 管理页展示 allowDownload 状态 | PASS（访问/下载列展示"允许下载/仅查看"徽标；无编辑入口，仅展示） |
| `npx tsc --noEmit`（st-web）通过 | PASS（EXIT=0；另 `npx tsc -b` 亦 EXIT=0） |

## 测试结果

- 命令：`npx tsc --noEmit`（st-web）→ EXIT=0
- 命令：`npx tsc -b`（st-web，构建前置类型检查）→ EXIT=0
- UI 交互未做浏览器级验证（本任务为类型/结构改动，主线程按验收执行 build 抽查）

## 风险

- 创建对话框交互由"权限"双按钮收敛为单一开关；原选项（仅查看/可下载）即 permission 0/1，与开关语义一致，功能无回退。
- 开关关闭时后端同时禁止下载与流式预览（allow_download=0），符合 TASK-FIX-SEC-DOWNLOAD-FLAG 定版语义；若后续需要"可预览但禁下载"等细粒度组合，需另行扩展。
- `ShareManagePage.tsx` 保持单行压缩结构，后续编辑该文件建议先格式化。

## State Delta

- 新增 artifact：`.ai/docs/TASK-UI-DOWNLOAD-FLAG/changereport.md`
- 修改：`ShareDialog.tsx`（开关 + 请求联动 + 结果展示）、`ShareManagePage.tsx`（状态徽标）
- exitCriterion：开关 UI 就位且 tsc 通过 → 建议勾选 IMPLEMENTED（前端）

## 下一步

- 主线程执行 `npm run build` / UI 抽查，确认渲染无回归后进入 CODE_REVIEW。

## 变更影响

- 影响范围：分享创建对话框与管理页两个前端文件，类型层无变更。
- 与后端：请求显式携带 `allowDownload`（0/1），与后端字段契约一致，向后兼容（后端未传时联动逻辑不变）。
- 对其它模块：无影响（未改其它前端页面与后端）。
