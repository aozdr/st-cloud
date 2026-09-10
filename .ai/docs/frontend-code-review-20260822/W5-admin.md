# W5 管理后台 Code Review

> 审查人：主线程 reviewer（ox-alpha）/ 日期：2026-08-22 / 方式：静态人工审查（AdminPage.tsx 全文；UserManageTab/StorageManageTab/AuditLogPanel/SpeedLimitPanel/RoleManagePanel 未逐行深读）
> 范围：`components/admin/**`(7 文件)、`pages/AdminPage.tsx`、`pages/ServerConfigPage.tsx`

## 问题清单

| 编号 | 严重度 | 位置 | 问题 | 证据(代码片段) | 建议 |
|---|---|---|---|---|---|
| W5-1 | P2 | pages/AdminPage.tsx:16,52 | 默认 tab 固定为 'dashboard' 且渲染不校验 can：无任何管理权限的用户直输 /admin 时，标签栏为空但 DashboardTab 仍挂载并触发统计接口请求 | `{tab === 'dashboard' && <DashboardTab />}` | 初始 tab 取第一个 can=true 项；全部无权限时显示无权页 |
| W5-2 | P2 | pages/AdminPage.tsx（路由层） | 路由仅 ProtectedRoute 登录守卫，无角色级守卫（W1-10 复核结论：页内 tab 已按权限码隐藏 + 后端兜底，降级为加固项） | App.tsx:86 仅 isAuthenticated | 增加 RequireAnyPermission 包装 |
| W5-3 | P2(待确认) | admin 各 Panel（未深读） | 危险操作（封禁/删号/容量调整/限速）的二次确认与输入校验未逐行核验，存在漏检盲区 | — | 补专项复查：重点 UserManageTab 状态变更与 StorageManageTab 数值边界 |

## 亮点

- 权限模型细粒度到位：六个 tab 分别绑定 admin:user:manage / admin:storage:manage / admin:audit:view / transfer:speed:limit / admin:role:manage 等独立权限码，非粗糙的 isAdmin 一刀切
- AuditLogPanel 懒加载，首屏成本可控
- usePermission hook 以稳定空数组默认值避免无效重渲染（permission.ts:3 EMPTY_PERMISSIONS）

## 结论

权限码粒度设计良好；本次深读范围内无高危项。主要遗留是「零权限用户进入后台仍拉数据」的小漏洞与各 Panel 的待复查盲区。

统计：P0×0　P1×0　P2×3（含 1 项待确认）
