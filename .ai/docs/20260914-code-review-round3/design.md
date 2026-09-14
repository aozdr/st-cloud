# 实施设计

## P0 孤儿候选

新增 `OrphanObjectCleanupService`、mapper、实体和定时回收任务；新增配置：

- `stcloud.upload.orphan.grace-ms`，默认 `3600000`。
- `stcloud.upload.orphan.cleanup-interval-ms`，默认 `60000`。

四个规范对象写入入口在对象存储写入前调用 `beginUpload`，提交成功调用 `markCommitted`，失败调用 `markFailed`。旧的 `current == null` 立即删除逻辑删除。规范对象 cleanup 只登记候选，不直接物理删除。

## P1 Relay

重写 `RelayBufferManager.tryAcquireSeq` 语义，新增 `commitSeq`、`releaseSeq` 和冲突决策；`appendChunk` 的分片编号只在 `uploadPart` 成功后递增。服务层捕获 IO/运行时失败并释放序号，必要时终止会话。

## P1 Desktop

SQLite `transfer_tasks` 增加三列并通过升级函数补列；任务映射、创建、更新和重载均保留字段。恢复根据 `transferMode` 分支：relay 只执行 `/relay-chunk` 和 `/relay-finalize`；跨重启的 relay 任务进入 restart-required 状态。

## P2 Share

在 `ShareController`/`ShareService`/`ShareServiceImpl` 增加 thumbnail 流接口。服务端验证分享后检查节点是图片且在分享范围内，优先读取预览对象，不存在则生成 JPEG 缩略图并缓存。前端胶卷改用新 endpoint。

## 文件边界

包含：上述模块、迁移脚本、schema、相关测试和本目录报告。

不包含：无关 UI 重构、生产数据库执行、历史对象批量扫描、普通下载协议改造。
