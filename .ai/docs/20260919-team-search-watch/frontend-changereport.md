# 前端实现变更报告

## 范围

本报告对应 `TASK-20260919-team-search-watch-frontend`、设计版本 `tsw-design-r1` 和代码版本 `tsw-code-r1`。实现仅涉及 `st-web`，没有修改 State、TASK、后端模块或依赖锁文件。

## 已落实

- `SearchPage` 保留个人 `/api/search` 契约并增加团队分支 `/api/search/team`。团队模式支持空间选择、当前文件夹及子文件夹范围、类型/大小/时间筛选、游标加载更多、错误码文案和安全结果定位；个人/团队切换和查询、筛选切换都会取消旧请求并以请求代际阻止迟到响应覆盖当前结果。
- `FileDetailPanel` 通过 `useFileWatch` 提供文件与文件夹关注入口，按钮在读取和保存期间禁用；`useFileWatch` 按节点绑定请求代际，切换节点或卸载时旧响应不能覆盖新节点，DELETE 无响应体时仍按取消成功处理。
- `FollowingPage` 使用 `/api/file-watches` 分页列表，严格只有 `available === true` 的记录显示可访问名称、路径和定位按钮；失效项仅显示通用文案与取消按钮。取消订阅后重新读取此前已展开的页，处理分页 offset 位移，避免漏项。
- `NotificationBell` 对新文件变更通知调用服务端安全 target 接口，读取目标前不拼接存储 URL；不可用目标不跳转，已读失败保留未读状态，并保留旧团队通知行为。个人文件定位使用 `focusId`，团队目标通过团队 source 实时核权后再进入目录。
- `TeamSpacePage` 通过团队文件 source 验证 `spaceId/folderId/nodeId`，异步取消只在当前请求仍有效时提交解析结果；解析成功后把 `focusId` 传给 `FileBrowser`，StrictMode 重放不会因提前写入 resolved key 而阻塞有效解析。
- 路由和侧边导航增加 `/following`，团队空间工具区增加团队搜索入口；新增 DTO 类型与现有组件/tokens 保持一致，交互支持键盘按钮语义和窄屏宽度。

## 接口契约

前端使用设计中固定的 `/api/search/team`、`/api/file-watches/state`、`PUT/DELETE /api/file-watches/{nodeId}`、`GET /api/file-watches` 和 `GET /api/notification/{id}/target`，未向接口增加用户 ID 或客户端权限字段。个人搜索、旧通知和既有路由保持兼容。

## 验证证据

- `st-web`: `npm run build`，退出码 0，TypeScript 与 Vite 生产构建通过。
- `st-web`: `npm run lint`，退出码 0；仅保留仓库既有的 11 条 Fast Refresh/Hook 警告，无错误。
- `st-web`: `npm run test:hash`，退出码 0，6/6 用例通过。
- 未运行 Maven；未在本 dispatch 中执行真实后端联调或浏览器 E2E，需由主线程在服务可用时完成 TC-U01/TC-U02 的桌面、375px 与真实竞态验收。

