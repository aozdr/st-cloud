# 空间根目录权限代码评审（root-permission-code-r1）

## 结论

`CODE_REVIEW` 建议 **fail**。根规则保存、读回、跨空间覆盖隔离及正常节点继承的代码路径基本符合设计；但根目录的常用请求会传 `nodeId=null`，当前解析直接返回角色权限，导致根规则在根目录本身无法授权。该问题影响用户通过“空间根目录权限”设置后在根目录列举、新建文件夹、新建空白文件等操作。

## CR-01（阻断）：根目录 `null` 未映射到虚拟节点 `0`

- `st-team/src/main/java/com/stcloud/team/service/FolderPermissionService.java:220-224` 在 `nodeId == null` 时直接返回角色权限，未读取 `folder_node_id=0` 规则；根规则只在 `nodeId == 0` 或正数节点父链到达 `0` 时读取（同文件 `256-267`）。
- `st-team/src/main/java/com/stcloud/team/service/impl/TeamServiceImpl.java:543-559` 把 `nodeId` 原样传入上述解析。
- `st-team/src/main/java/com/stcloud/team/controller/TeamController.java:259-265,323-328,339-344` 的根目录列举、创建文件夹和新建空白文件都可能把缺省 `parentId=null` 传入 `requirePermissions`。现有前端在根目录使用 `parentId=null`（`st-web/src/pages/TeamSpacePage.tsx:82,303`），`teamFileSource` 对上述三个请求省略空 parentId（`st-web/src/lib/fileSource.ts:63-68`）。
- 可复现条件：空间查看者无 `upload`，管理员在根规则中给该查看者 `upload`，查看者在空间根目录新建文件夹；请求的 `parentId` 为空，权限计算仅返回角色权限，最终拒绝 `upload`。对根目录 `view` 的自定义角色也会出现同样遗漏。已有测试只验证根规则对正数节点继承（`TeamServicePermissionIntegrationTest.java:267-304`），未覆盖缺省根目录参数。
- 建议：在有 `spaceId` 且已完成成员校验的解析入口，将 `null` 根目录参数映射为虚拟根 `0`，或在根目录业务调用处统一传 `0`；补充 `resolveMyPermissions(spaceId, null)` 及根目录操作的集成用例。保留非成员拒绝与管理员直通语义。

## 其余核查

- 管理员校验位于 `getFolderPermissions` / `setFolderPermissions` 的根节点判断之前；正数节点继续校验存在、正常、空间归属（`TeamServiceImpl.java:603-615,650-653,680-683`）。
- 列表与覆盖删除均带 `space_id` 条件；根规则读取也带 `space_id`，显式租户 fresh 路径进一步限定 `tenant_id`（`FolderPermissionService.java:417-437,559-579`）。
- fresh 路径先验证祖先节点，缺失、跨空间、循环、超深时返回空权限集；根规则仅在到达根后追加（`FolderPermissionService.java:348-439`）。
- 20 层常规解析可在第 20 层之后读取根规则；现有单元测试覆盖该边界（`FolderPermissionFreshTest.java:107-118`）。
- `testreport.md` 记录六份测试报告共 46 项通过、后端聚合构建通过；本次评审仅静态核对，没有重跑共享 Maven 构建。

## 变更影响

本评审只写入评审文档和独立结果；未修改产品代码或 Loop State。
