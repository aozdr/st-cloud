# Code Review：在线文档编辑（OnlyOffice）

> 归属：CODE_REVIEW（多 Agent 禁用，由主线程自查并记录；依赖 IMPLEMENTED done）

## 结论

**PASS**，无阻断问题。

## 结构检查

- st-core 新增 `editor` 包职责单一（配置/权限/config/回调/锁），与上传、版本主链路隔离 ✅
- 团队（TeamController）与分享（ShareController）config 端点只做权限判定，复用 st-core 的 EditorConfigService，未复制逻辑 ✅
- 前后端接口契约与 design.md 一致（`{editorUrl, config}`）✅

## 规范检查

- 核心逻辑（权限判定/回调验签/版本裁剪/保存锁）均有中文注释 ✅
- 命名与项目约定一致（Service/Impl、DTO、异常）✅
- 无无关重构，改动最小化 ✅

## 性能检查

- 保存回调按文件 Redis 锁串行化，避免同文件并发覆盖 ✅
- 幂等键（sha1(nodeId|status|key|url)）避免重复落盘，OnlyOffice 重试安全 ✅
- 回调下载有大小上限（200MB），防超大文件投毒 ✅

## 边界检查

- 编辑标记 Redis Set + 滑动 TTL(2h)，多人协同不互斥；关闭回调按 users 移除 ✅
- 版本裁剪仅 source=1 且保留物理对象（防共享对象误删）✅
- Redis 不可用时编辑标记降级（打开不阻断），保存锁/幂等回退 memory 语义并记录日志 ✅

## 问题清单

| 编号 | 问题 | 级别 | 处理 |
|------|------|------|------|
| R1 | 版本裁剪不物理删除超限对象，长期编辑会占用额外存储 | P3 | 已记录风险；后续引入版本级引用计数后回收 |
| R2 | 分享访客编辑标记用合成 id（share:CODE），关闭回调未携带时由 TTL 兜底 | P3 | 已记录风险；TTL 2h 自动失效 |

## 结论

实现与设计一致，进入安全审查。
