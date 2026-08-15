# TASK-FIX-SEC-SHARE（分享安全 P0 修复：S-01/S-02/S-03 — executor/implement）

## 元信息

- Task ID: `TASK-FIX-SEC-SHARE`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: security-recheck.md 的 S-01（P0 越权分享）、S-02（P0 下载 NPE/绕过）、S-03（P1 路径边界）

## 目标

修复分享模块三个安全缺陷（定版方案，禁止自行扩大范围）：

### S-01 创建分享增加资源级归属校验（`ShareServiceImpl.createShare`）

在 `fileService.validateAccessible(request.getFileNodeId())` 之后新增：

```java
Long currentUserId = UserContext.getUserId();
if (fileNode.getSpaceId() == null || fileNode.getSpaceId() <= 0) {
    // 个人文件：必须是本人（或租户管理员，对齐 DownloadServiceImpl 的 canAccessTenant 例外）
    if (!fileNode.getOwnerId().equals(currentUserId) && !UserContext.canAccessTenant()) {
        throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "无权分享他人文件");
    }
} else {
    // 团队文件：必须是该空间成员且有访问权
    fileService.validateTeamNode(fileNode.getSpaceId(), fileNode.getId());
}
```

### S-02 分享下载链路修复（`getDownloadUrl` / `streamShareFile`）

1. `getDownloadUrl`：
   - **permission 校验**：`share.getPermission() == 0`（仅查看）→ `SHARE_ACCESS_DENIED("仅查看不可下载")`。
   - **去掉 owner 校验链路**：末尾 `downloadService.generateDownloadUrl(targetNodeId)` 改为 `storageService.generateDownloadUrl(targetNode.getStoragePath())`（分享链路经 validateShareAccess 认证，不走 DownloadServiceImpl 的个人 owner 校验，消除匿名 NPE）。
   - 保留 downloadLimit/downloadCount 校验与消耗；子树校验改为带边界（见 S-03）。
2. `streamShareFile`：
   - 新增 downloadLimit 校验（与 getDownloadUrl 同口径：`downloadLimit != null && downloadCount >= downloadLimit` → 拒绝，且成功后 `download_count + 1`）。
   - 子树校验改为带边界（见 S-03）。
   - permission==0（仅查看）允许 inline 预览（Content-Disposition 保持 inline），但同样计入下载次数（统一口径）。

### S-03 子树 path 边界（三处）

`getDownloadUrl`、`listShareFiles`（parent 校验）、`streamShareFile` 的 `startsWith(root.getPath())` 改为边界判断，提取私有方法：

```java
private boolean isWithinShare(FileNode root, FileNode node) {
    return node.getPath() != null && root.getPath() != null
            && (node.getPath().equals(root.getPath())
                || node.getPath().startsWith(root.getPath() + "/"));
}
```

### 测试（st-share 新增/扩展集成测试）

- 分享他人个人文件被拒（S-01）
- 分享自己文件成功；团队文件非成员被拒
- 匿名访问分享下载 URL 成功（不再 NPE）（S-02）
- 仅查看（permission=0）分享 getDownloadUrl 被拒
- downloadLimit 达到后 streamShareFile 拒绝（S-02）
- 同名前缀子文件（根 `/a.txt` 匹配 `/a.txt2`）被拒（S-03）

## 范围

- include：`st-share/**`（ShareServiceImpl + 测试 + 测试资源）；只读 `st-core` 的 FileNode/StorageService/DownloadServiceImpl/FileServiceImpl（validateTeamNode）、`.ai/docs/20260814-project-code-review/security-recheck.md`
- exclude：修改 `st-core`/`st-team`/`st-auth` 业务代码、`docker/mysql/init`、前端、创建子 Agent

## 验收标准

- `mvn -q -pl st-share -am test` EXIT=0（新增安全用例全绿）
- 关键逻辑含中文注释；未改动其它模块
- 代码中无遗留 `startsWith(root.getPath())` 无边界用法（rg 复核）

## 验证

- 主线程复跑 st-share 测试；抽查三处边界与下载链路
