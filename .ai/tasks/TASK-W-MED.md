# TASK-W-MED（W3 状态裸数字枚举化 — executor/implement）

## 元信息

- Task ID: `TASK-W-MED`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: 全量 Code Review W3（Primitive Obsession：状态裸数字遍布 9 个文件）

## 目标

为业务状态裸数字建立枚举，替换既有裸数字字面量。**行为等价**：枚举 `getCode()` 返回原数字，禁止改变任何状态值语义。核心逻辑补中文注释。

## 修改清单（已定版，数字映射以现有代码为准）

1. 新增枚举（放入各模块 `enums` 包，含 code + 注释）：
   - st-share：`ShareStatus`（0-已取消 / 1-有效），替换 `ShareServiceImpl.java:83,111,296`
   - st-team：`TeamSpaceStatus`、`InviteStatus`、`RoleStatus`，替换 `TeamServiceImpl.java:85,336,363,376,385,778` 等
   - st-auth：`UserStatus`、`TenantStatus`（如适用），替换 `AuthService.java:66,79,117,122,163`
   - st-sync：`SyncRootStatus`，替换 `SyncServiceImpl.java:71,118`
   - st-admin：`UserStatus`（若与 st-auth 重复可复用或独立），替换 `UserManageServiceImpl.java:84,135`
   - st-core：`FileChunkStatus`（UploadChunkManager:30）、`FileObjectStatus`（FileObjectServiceImpl:45）、`EventOutboxStatus`（ReliableEventPublisher:83 / EventRelay:35）
2. `setRefCount(0/1)` 语义常量化：`UploadServiceImpl.java:112,179,256`、`ArchiveServiceImpl.java:177,199`、`FileServiceImpl.java:104,240,629,870`（新增 `RefCount` 常量或私有静态常量 + 注释）。
3. `FileController.java:237`：`setStatus(500)` → `HttpStatus.INTERNAL_SERVER_ERROR.value()`。

## 范围

- include：`st-share`、`st-team`、`st-auth`、`st-sync`、`st-admin`、`st-core` 的 service/controller/dto/entity 与新增 enums
- exclude：`st-web`、`st-desktop`、`st-common`、`docker/mysql/init`、`st-core/src/test`、创建子 Agent；禁止改变任何数字值/数据库结构

## 验收标准

- 替换后编译通过；相关模块测试通过（`mvn -q -pl st-core,st-share,st-team,st-auth,st-sync,st-admin -am test` EXIT=0）
- 枚举 code 与原数字一致；无遗漏裸数字（`rg` 复核关键文件）
- 未改任何状态流转语义、数据库结构

## 验证

- 主线程复跑上述 mvn test；抽查枚举映射
