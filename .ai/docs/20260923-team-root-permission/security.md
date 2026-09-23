# 空间根目录权限安全评审

## 背景与输入

基于 `root-permission-design-r1`、`root-permission-code-r1` 和独立安全 TASK，只读审阅管理员门禁、跨空间隔离、根规则授权、断链处理和覆盖删除。阅读了当前 `FolderPermissionService`、`TeamServiceImpl`、相关控制器及定向测试；未运行共享 Maven 构建，也未修改产品代码或 Loop State。

## 结论与问题

**SECURITY_REVIEW 建议 fail：常规节点权限解析可把空间 A 的根规则授予空间 B 的节点。**

- `FolderPermissionService.java:237-253` 沿父链查询 `file_node` 时只按节点 ID 读取，没有核实每层节点的 `space_id`、状态或完整父链。`FolderPermissionService.java:256-266` 只要读到 `parent_id=0`，便加入请求参数 `spaceId` 对应的根规则。
- 可复现输入：用户是空间 A 成员，A 根规则授予 `upload`；空间 B 的顶层节点 `b` 有 `parent_id=0`，用户对 B 没有相应授权。调用 `resolvePermissions(A, b.id, userId, rolePerms)` 会得到 `upload`。`TeamServiceImpl.java:530-559` 的 `requirePermissions` 直接使用这个结果，因此其授权判断也会通过。现有根规则测试只用 A 节点配 A 空间、B 节点配 B 空间，没有交叉节点输入。
- 多数团队文件操作在后续 FileService 中还有归属校验，降低了实际跨空间写入机会；但权限服务和 `requirePermissions` 已错误放权，不能以调用方的第二层校验代替授权边界。修复应在常规解析逐层确认 `file_node.space_id` 等于请求空间，断链、跨空间、循环和删除节点返回拒绝；增加 A 空间根规则与 B 空间节点交叉回归用例。

## 已确认的安全边界

- `TeamServiceImpl.java:650-653,680-683` 在读写根规则前均执行 `checkPermission(spaceId, 0)`；该校验仅允许空间管理员，普通成员无法直接写规则。
- `TeamServiceImpl.java:603-615` 只把 `nodeId=0` 当作虚拟根，负数和不属于本空间的正数节点拒绝。
- `FolderPermissionService.java:559-578` 的根规则读取与全量覆盖删除同时限定 `space_id` 和 `folder_node_id`；空间 A 覆盖不会删 B 的根规则。
- `FolderPermissionService.java:348-439` 的 fresh 路径逐层核实空间与租户、删除状态，对缺失、循环、负数及超过深度的父链返回空权限集，只有到根后才追加本空间根规则。其请求内规则缓存按单个空间上下文创建。

## State Delta 与下一步

仅提出 `SECURITY_REVIEW=fail`，对应 `root-permission-code-r1`；主线程负责 Evaluate。建议修复常规解析后补交叉空间节点、断链及删除节点测试，再进行安全复审。风险集中在新增根规则参与常规权限并集的授权路径；没有发现根规则管理接口绕过管理员门禁或跨空间覆盖删除。
