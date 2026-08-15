# TASK-FIX-SEC-SHARE-ALIGN（团队分享创建对齐文件夹级权限 — executor/implement）

## 元信息

- Task ID: `TASK-FIX-SEC-SHARE-ALIGN`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: S-01 加固——团队分享创建从"空间成员级"对齐到"文件夹权限链级"

## 目标

当前 `createShare` 团队文件分支仅调用 `fileService.validateTeamNode(spaceId, nodeId)`（只校验节点属于该空间），**未对齐团队文件夹权限链**（`FolderPermissionService.resolvePermission`：-1-无权限 0-管理 1-编辑 2-查看）。将其对齐为项目标准入口 `TeamService.checkPermission(spaceId, nodeId, minPermission)`（已含：空间成员校验 + 权限链计算 + -1/越权拦截）。

## 修改（已定版）

1. `st-share/pom.xml`：新增 `com.stcloud:st-team` 依赖（st-team 不依赖 st-share，无环；版本走 parent 管理）。
2. `ShareServiceImpl`：
   - 注入 `TeamService teamService`；
   - `createShare` 团队分支（`fileNode.getSpaceId() != null && > 0`）改为：
     ```java
     // 团队文件：对齐团队文件夹权限链（成员校验 + 权限链计算 + -1/越权拦截），至少"可查看"（2）可分享
     teamService.checkPermission(fileNode.getSpaceId(), fileNode.getId(), 2);
     ```
   - 保留 `fileService.validateAccessible` 与个人分支（本人/租户管理员）不变；异常保持 `TeamService.checkPermission` 原样抛出（TEAM_PERMISSION_DENIED，信息更准确）。
3. 测试（st-share）：
   - `ShareTestApplication` / `AbstractShareIntegrationTest` 增加 `@MockBean TeamService`（st-share 测试不扫描 st-team 组件）；
   - 新增用例：团队非成员分享被拒（mock checkPermission 抛 TEAM_PERMISSION_DENIED）；团队查看权限分享成功（mock 不抛）；个人文件分支回归不变。

## 范围

- include：`st-share/pom.xml`、`st-share/src/main/java/com/stcloud/share/service/impl/ShareServiceImpl.java`、`st-share/src/test/**`
- exclude：`st-team`/`st-core` 业务代码、`docker/mysql/init`、前端、创建子 Agent

## 验收标准

- `createShare` 团队分支调用 `teamService.checkPermission(spaceId, nodeId, 2)`（rg 复核）
- `mvn -q -pl st-share -am test` EXIT=0（新增团队权限用例全绿；既有 21 用例不回归）
- 个人分支/过期/清除等逻辑零改动

## 验证

- 主线程复跑 st-share 测试；抽查团队分支代码
