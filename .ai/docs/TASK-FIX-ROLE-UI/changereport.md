# Change Report — TASK-FIX-ROLE-UI（角色管理 UI 优化）

## 元信息

- Task ID: `TASK-FIX-ROLE-UI`
- taskCode: `TSUI-06`
- Agent: executor（taskType=implement）
- dispatchId: `tsui-06`
- claimedFile: `inbox-tsui-06.md`
- 日期: 2026-08-14

## 背景

用户反馈 `RoleManageDialog` 角色管理 UI"很难看"。TASK 定版要求：布局清晰（角色列表 + 新建/编辑表单分区）、9 权限点分组勾选展示（带图标与分组）、新建默认与编辑回填、保存/删除按钮、间距与视觉层次，并保留 upload/download 隐含 view 联动与防御式解析。

## 输入

- `TASK-FIX-ROLE-UI.md`（定版目标/范围/验收）
- 收件箱信封 `inbox-tsui-06.md`（dispatchId=tsui-06）
- `st-web/src/components/team/RoleManageDialog.tsx`（唯一修改文件）
- `st-web/src/lib/permissions.ts`（`PERMISSION_KEYS` 单源：9 权限点 key/label）
- `st-web/src/types/index.ts`（`TeamRoleInfo`）
- `st-web/src/components/team/FolderPermissionDialog.tsx`（同库权限勾选联动与视觉惯例参照）
- `st-web/src/index.css` / `tailwind.config.js`（surface/fg/muted/primary 语义 token 与 btn-primary/btn-secondary）
- 技能：`frontend-design-ui-ux`、`design-guide`（skillRefs）；自主发现补充 `shadcn`、`web-design-guidelines`

## 分析

1. **布局**：原实现为单列堆叠（列表在上、表单弹出在下方），视觉拥挤且列表/表单无明确分区。改为左右分区：左侧固定宽度角色列表（含数量、新建按钮、行选中态），右侧表单区（新建/编辑共用一个表单，未选择时展示空状态引导），移动端自动纵向堆叠。
2. **权限点**：原 9 个权限点平铺 2 列，无分组无图标。按语义分为「文件访问（查看/下载/上传）」「文件操作（删除/重命名/移动）」「协作分享（分享）」「管理（管理成员/管理设置）」四组，每组标题带图标、每个权限点带 lucide 图标与勾选卡片（选中态主色描边高亮）。
3. **行为保留**：新建默认 `{view:true, download:true}`、编辑防御式解析回填、保存前 view 归一化、`confirm` 删除均保留；并补齐与 `FolderPermissionDialog` 一致的逆向联动（取消 view 同步取消 upload/download）。
4. **细节**：Esc 关闭、表单打开自动聚焦名称输入框、编辑态表单头部提供删除按钮、空列表/空表单空状态，均使用项目既有语义 token 与按钮类，未新增依赖。

## 决策

- 仅修改 `st-web/src/components/team/RoleManageDialog.tsx`：左右分区布局 + 4 组权限点图标勾选 + 空状态 + Esc/自动聚焦 + 双向隐含 view 联动。
- 权限点 key/label 继续复用 `PERMISSION_KEYS` 单源；图标以本地映射 `PERMISSION_ICONS` 维护。
- 核心联动/解析逻辑保留中文注释。

## 修改文件清单

- 修改：`st-web/src/components/team/RoleManageDialog.tsx`
- 新增：`.ai/docs/TASK-FIX-ROLE-UI/changereport.md`

未改动：后端代码、其它前端组件/页面、`st-web/src/lib/permissions.ts`（权限点单源未动）、数据库。

## 与验收标准对照

| 验收项 | 结果 |
|--------|------|
| 角色列表 + 新建/编辑表单分区，布局清晰 | PASS（左右分区，移动端纵向堆叠；列表行选中高亮） |
| 9 权限点分组勾选展示（带图标与分组） | PASS（4 组 × 9 点全覆盖，组标题与权限点均带图标） |
| 新建默认与编辑回填 | PASS（新建默认 view+download；编辑防御式解析回填） |
| 保存/删除按钮 | PASS（保存/取消在表单底部；编辑态表单头部另提供删除；列表行保留编辑/删除） |
| 隐含 view 联动保留 | PASS（upload/download → 自动补 view；取消 view → 联动取消 upload/download） |
| `npx tsc --noEmit`（st-web）通过 | PASS（EXIT=0） |

## 测试结果

- 命令：`npx tsc --noEmit`（st-web）→ EXIT=0
- 浏览器级 UI 验证由主线程统一执行（验收 `validation`：主线程统一 tsc/build）

## 风险

- 对话框由 `max-w-lg` 扩为 `max-w-3xl`，小屏下已做纵向堆叠（列表限高 56）与内部滚动，主线程 build/UI 抽查时建议顺带确认。
- 新增逆向联动（取消 view 取消 upload/download）与 `FolderPermissionDialog` 行为一致，若后端未来放宽隐含规则需同步调整两处。
- 预设角色仍不可编辑/删除（与既有行为一致）。

## State Delta

- 新增 artifact：`.ai/docs/TASK-FIX-ROLE-UI/changereport.md`
- 修改：`st-web/src/components/team/RoleManageDialog.tsx`
- exitCriterion：UI 优化完成且 tsc 通过 → 建议勾选 IMPLEMENTED（前端）

## 下一步

- 主线程执行 `npm run build` / UI 抽查，确认渲染无回归后进入 CODE_REVIEW。

## 变更影响

- 影响范围：仅角色管理对话框单个前端组件；无类型、接口、后端与数据层变化。
- 权限点单源 `PERMISSION_KEYS` 未改，`FolderPermissionDialog` / `ShareDialog` 等引用不受影响。
- 隐含 view 联动语义与后端及 `FolderPermissionDialog` 保持一致，向后兼容。
