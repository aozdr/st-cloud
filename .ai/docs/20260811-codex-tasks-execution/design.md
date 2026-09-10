# 设计：st-cloud 代码优化总体方案

## 总体方案
按 TASK-001..006 顺序推进，每任务遵守：先扫描→影响分析→方案确认→实现→测试→变更报告；DB 变化带迁移脚本；接口变化说明兼容；核心逻辑中文注释。全程保持前端 st-web 接口契约不变。

## 各任务设计要点
### TASK-001 文件对象模型
- 新增 file_object（id, tenant_id, md5, size, storage_path, ref_count, status, created_at）；同租户内 md5 唯一
- file_node 增加 object_id（可空），去重/秒传/删除/版本统一走 object 引用计数
- 迁移：已有完成节点按 (tenant_id, md5) 回填 object；保留旧 storage_path 兼容下载
### TASK-002 上传状态机
- 抽出 UploadManager/ChunkManager/StorageManager/EventPublisher，UploadServiceImpl 变编排门面
- UploadStatus 扩展为 INIT/UPLOADING/MERGING/STORED/FAILED；uploadId 幂等
### TASK-003 容量并发
- 配额扣减原子化：UPDATE ... SET used=used+? WHERE used+?<=quota（个人/团队/云盘三表），失败即超限
- 校验与扣减合并为一次原子操作，取消读后写
### TASK-004 事件可靠性
- 业务事务内写 event_log（outbox），由定时任务/事务后投递 RocketMQ；消费者幂等
- FileIndexEvent/SyncChangeEvent 迁移到 outbox 消息
### TASK-005 权限性能
- 引入权限快照或 Redis ACL 缓存，FolderPermissionService.resolvePermission 命中缓存免遍历；权限变更失效刷新
### TASK-006 测试
- 文件（秒传/分片/移动/删除恢复）、并发（同文件/容量竞争）、权限（用户/团队/分享）三类

## 数据/接口设计
- 新增表：file_object、event_log（TASK-004）
- file_node 新增列：object_id；TASK-004 新增 event_log 表
- 对外 REST 接口签名不变；仅内部模型/逻辑调整

## 风险
- TASK-001 迁移回填量大，需灰度与备份
- TASK-004 引入 RocketMQ 为基础设施变化，需确认环境
- TASK-002 涉及核心上传链路，需充分回归

## 文档对应
- 每任务产出 changereport.md；涉及架构/表结构决策产出 ADR 到 .ai/decisions/ADR/