# Change Report — TASK-FIX-SEC-SHARE（分享安全 P0/P1 修复：S-01/S-02/S-03）

## 元信息

- Task ID: `TASK-FIX-SEC-SHARE`
- Agent: executor（taskType=implement）
- dispatchId: `sec-fix-001`
- claimedFile: `inbox-sec-fix-001.md`
- 日期: 2026-08-14
- 来源: `.ai/docs/20260814-project-code-review/security-recheck.md` 的 S-01（P0 越权分享）、S-02（P0 下载 NPE/绕过）、S-03（P1 路径边界）

## 背景

安全复检确认分享模块存在三项阻断问题：创建分享仅做祖先链可访问校验、无资源级归属校验（可分享他人文件）；公开下载走 `DownloadServiceImpl` 的个人 owner 校验导致匿名 NPE/500，且 `streamShareFile` 可绕过下载次数与"仅查看"限制；子树 path 前缀校验缺少目录边界（`/a.txt` 可匹配 `/a.txt2`）。本次按 TASK 定版方案修复三处并补充集成测试。

## 输入

- `TASK-FIX-SEC-SHARE.md`（定版方案：S-01/S-02/S-03 修改点与验收标准）
- `security-recheck.md`（S-01~S-03 根因与修复建议）
- `st-core` 只读：`FileNode`（spaceId/ownerId/path）、`FileServiceImpl.validateAccessible/validateTeamNode`、`DownloadServiceImpl.generateDownloadUrl`（被替换的前置约束行为）、`StorageService.generateDownloadUrl/storagePath`
- `st-share` 现有实现与集成测试基类（H2 真实 Mapper + Mock FileService/StorageService）

## 分析

1. **S-01**：`createShare` 在 `fileService.validateAccessible(...)`（仅检查祖先链回收态）后无归属校验。按定版方案：个人文件（`spaceId == null || <= 0`）要求 `ownerId == 当前用户`（租户管理员 `canAccessTenant()` 例外，与 `DownloadServiceImpl` 对齐）；团队文件复用 `fileService.validateTeamNode(spaceId, nodeId)`。
2. **S-02**：`getDownloadUrl` 末端调用 `downloadService.generateDownloadUrl(nodeId)`，其内部对个人文件强制 `UserContext.getUserId().equals(ownerId)`，匿名场景 `getUserId()` 为 null → NPE。改为 `storageService.generateDownloadUrl(storagePath)`（分享链路经 `validateShareAccess` 认证），并新增 `permission=0` 拒绝下载；`streamShareFile` 新增 `downloadLimit` 校验并在成功后 `download_count+1`（permission=0 仍允许 inline 预览，统一口径）。
3. **S-03**：三处 `path.startsWith(root.getPath())` 无边界，改为提取的 `isWithinShare(root, node)`：`path.equals(rootPath) || path.startsWith(rootPath + "/")`。
4. **下载前置约束保留**：原 `downloadService.generateDownloadUrl` 同时提供"文件夹不可单文件下载、未完成上传不可下载"两类拒绝；直接替换为 `storageService` 后若不加约束，文件夹分享根（storagePath=null）会引入新 NPE 回归。故在 st-share 侧补齐同等校验（`nodeType==0` → BUSINESS_ERROR；`uploadStatus != COMPLETED` → BUSINESS_ERROR），行为与原链路一致。

## 决策

按 TASK 定版方案实施，仅修改 `st-share/**`：

- `ShareServiceImpl.createShare`：新增资源级归属校验（S-01）。
- `ShareServiceImpl.getDownloadUrl`：permission=0 拒绝；子树校验走 `isWithinShare`；URL 生成改走 `storageService.generateDownloadUrl(storagePath)`；保留文件夹/未完成上传前置约束（S-02/S-03 + 防回归）。
- `ShareServiceImpl.streamShareFile`：新增 downloadLimit 校验、成功后计数、子树边界校验（S-02/S-03）。
- `ShareServiceImpl.listShareFiles`：parent 校验走 `isWithinShare`（S-03）。
- 移除不再使用的 `DownloadService` 依赖（字段 + import）。
- 新增 `ShareServiceImplSecurityIntegrationTest`（11 个安全用例）；`AbstractShareIntegrationTest` 扩展 `insertFileNode(..., spaceId)` 与 `insertFolder` 测试辅助。

## 修改文件清单

- 修改：`st-share/src/main/java/com/stcloud/share/service/impl/ShareServiceImpl.java`
- 修改：`st-share/src/test/java/com/stcloud/share/AbstractShareIntegrationTest.java`（新增辅助方法，未改既有语义）
- 新增：`st-share/src/test/java/com/stcloud/share/ShareServiceImplSecurityIntegrationTest.java`

未改动 `st-core` / `st-team` / `st-auth` / `st-web` / 数据库脚本。

## 与验收标准对照

| 验收项 | 结果 |
|--------|------|
| createShare 归属校验（个人本人/租户管理员；团队 validateTeamNode） | PASS |
| getDownloadUrl permission 校验 + storageService 直连（匿名不 NPE） | PASS |
| streamShareFile 限次（downloadLimit 拒绝 + 成功后计数） | PASS |
| 三处 path 边界（getDownloadUrl / listShareFiles / streamShareFile） | PASS |
| `mvn -q -pl st-share -am test` EXIT=0（含新增安全用例） | PASS（21 用例全绿） |
| 核心逻辑中文注释 | PASS |
| 未改动其它模块 | PASS |
| 无遗留无边界 `startsWith(root.getPath())` | PASS（rg 复核无匹配） |

## 测试结果

- 命令：`mvn -q -pl st-share -am test`
- 结果：EXIT=0；`ShareServiceImplExpiryIntegrationTest` 10/10、`ShareServiceImplSecurityIntegrationTest` 11/11、`st-core` 依赖模块测试全部通过。
- 新增用例覆盖：分享他人个人文件被拒、分享自己文件成功、团队文件非成员被拒/成员通过、匿名下载 URL 成功（不再 NPE）、仅查看 getDownloadUrl 被拒、downloadLimit 达上限 streamShareFile 拒绝、permission=0 流式预览成功并计数、三处同名前缀子树越权被拒。

## 风险

- `getDownloadUrl` 的 permission==0 拒绝是行为收紧：默认新建分享 permission=0，前端若对"仅查看"分享直接调下载接口会收到 `SHARE_ACCESS_DENIED`，需前端按权限展示下载入口（属预期语义，TASK 已定版）。
- 流式计数为"成功后 +1"，与 `getDownloadUrl` 生成 URL 即计数的口径在时序上不同（后者存在 S-07 TOCTOU，本次范围外）；并发下 downloadLimit 仍为"检查-递增"非原子（S-07，未纳入本次 TASK）。
- 测试中 `FileService.validateTeamNode` 为 mock：团队成员判定依赖 st-team 真实实现，已通过 verify 锁定调用参数，运行期仍需联调验证。

## State Delta

- 新增 artifact：`.ai/docs/TASK-FIX-SEC-SHARE/changereport.md`；`st-share` 安全修复代码 + 11 个集成测试。
- 解除 blocker：S-01/S-02/S-03（对应 security-recheck.md 三个 BLOCK 项）。
- 建议 exitCriteria：IMPLEMENTED（st-share）→ done；等待主线程复跑 `mvn -q -pl st-share -am test` 与 rg 复核后进入 CODE_REVIEW / SECURITY_REVIEW。

## 下一步

- 主线程复跑 st-share 测试与 `rg "startsWith\\(root\\.getPath\\(\\)\\)" st-share/src` 复核（已本地验证）。
- 重派 security reviewer 复核 S-01/S-02/S-03 修复并评估是否残留（S-04/S-06/S-07 等 P1/P2 项不在本 TASK 范围，需主线程另行立项）。
- 前端联调：分享页下载入口需匹配 permission 语义。

## 变更影响

- 影响范围：`st-share` 分享创建/下载/列表/流式四条链路；`file_share.permission` 语义在下载链路开始强制执行。
- 对其它模块：无代码改动；`DownloadService` 在 st-share 不再被引用（st-core 自身不变）。
- 对测试：新增 11 个安全集成用例，既有 10 个过期用例全部保持通过。
