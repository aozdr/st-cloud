# TASK-PERM-BE2（st-share 分享权限上限 — executor/implement）

## 元信息

- Task ID: `TASK-PERM-BE2`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: 权限模型重设计（design.md，已确认）

## 依赖契约（st-team 已定版，勿改）

```java
Set<String> teamService.resolveMyPermissions(Long spaceId, Long nodeId);
void teamService.requirePermissions(Long spaceId, Long nodeId, String... perms);
```

## 目标

分享权限上限落地：

1. **字段**：`FileShare` 加 `permissions`（JSON 字符串，如 `{"view":true,"download":true}`）；`CreateShareRequest` 加 `permissions`（JSON 或 `Set<String>`，默认仅 view）；`UpdateShareRequest` 加 `permissions`；`ShareVO` 回传。
2. **createShare**：
   - 前置：个人文件走 owner 校验（现有）；团队文件先 `teamService.requirePermissions(spaceId, nodeId, "share")`（share 权限点前置）+ 获取 `resolveMyPermissions`。
   - 分享权限上限：请求权限集 ⊆ 用户对该文件有效权限集，否则拒绝 `SHARE_ACCESS_DENIED("分享权限不能超过你的权限")`；未传权限时默认 = 用户有效权限（个人文件默认 {view,download}，与 allow_download 联动）。
   - `allow_download` 联动：权限集含 `download` → 1，否则 0（与显式 allowDownload 取交集：两者都允许才 1）。
3. **updateShare**：同样校验分享权限 ⊆ 用户有效权限 + share 前置；permissions 更新联动 allow_download。
4. **getDownloadUrl / streamShareFile**：分享权限集含 `download` 才允许（与现有 allow_download==0 拒绝并存，双保险）。
5. **测试（st-share）**：个人文件默认分享 {view,download}；请求超权（含 download 但用户无 download）被拒；团队分享走 requirePermissions("share")；allow_download 联动。

## 范围

- include：`st-share/**`（ShareServiceImpl、DTO、FileShare 实体、测试）
- exclude：`st-team`/`st-core` 主代码、前端、`docker/mysql/init`、创建子 Agent

## 验收标准

- createShare/updateShare 权限上限 + share 前置生效；allow_download 与 permissions 联动
- 下载/流式以权限集含 download 为准（双保险）
- `mvn -q -pl st-share -am test` EXIT=0

## 验证

- 主线程复跑 st-share 测试；抽查权限校验
