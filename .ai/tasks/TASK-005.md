# TASK：TASK-005 权限性能优化

> 依据《st-cloud-Codex-execution-tasks.md》TASK-005。优先级 P1。

## 元信息
- Task ID: `TASK-005`
- 关联 State: `.ai/state/20260811-codex-tasks-execution.yaml`
- 归属 Agent: backend-engineer

## 目标
降低文件访问权限计算成本：大目录访问不递归遍历，权限变更可刷新缓存。

## 修改范围
- `FolderPermissionService.resolvePermission`：向上遍历改为命中权限快照/缓存（Redis 或内存，key=space:node:user），未命中才计算并回填
- `FileServiceImpl.validateAccessible`：复用缓存；分享鉴权路径同步接入
- 权限变更点（TeamFolderPermission/TeamRole/成员变更）失效刷新相关缓存

## 禁止修改范围
- 不改变权限语义与返回码（-1/0/1/2）
- 不改变现有权限表结构（快照/缓存为可重建派生数据）

## 验收标准
- 大目录（深层级）权限判定命中缓存，无递归 SQL
- 权限变更后缓存失效，重新计算正确

## 测试要求
- 集成测试：缓存命中、变更失效、分享路径
- 权限回归

## 输出要求
- 完成后产出 `.ai/docs/<task-id>/changereport-t005.md`；架构决策产出 ADR