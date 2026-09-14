# 技术架构评审

## 决策一：规范对象采用持久化孤儿候选

新增 `file_orphan_candidate` 表，以 `(tenant_id, storage_path)` 唯一标识候选，记录 `active_uploads`、`candidate_at`、`last_active_at` 和回收状态。

- 上传规范对象前原子登记活动计数。
- 提交成功后删除候选记录；数据库提交失败后释放活动计数并进入 pending。
- 定时任务只领取宽限期已到、活动计数为 0 的候选。
- 领取后再次确认无 NORMAL `file_object`、无有效 `file_node` 引用、无活动上传会话，才调用对象存储删除。
- 新上传登记遇到 deleting 候选时不复用该候选，避免回收与新写入交叉。

该方案不依赖单 JVM 锁，安全边界由数据库状态和删除前复核提供。

## 决策二：Relay 序号使用 committed/in-flight 二态

`RelaySession` 保存 `committedSeq` 和 `inFlightSeq`：

- `seq <= committedSeq`：幂等重复，返回已确认。
- `seq == committedSeq + 1` 且无 in-flight：取得处理权。
- 其它情况：返回冲突，不修改 committed 序号。
- 请求体读取、限速、追加和分片上传全部成功后提交序号。
- 任一失败释放 in-flight；无法安全恢复时终止中转会话，不把失败请求标记为成功。

## 决策三：桌面端不跨协议恢复 Relay

任务持久化 `transfer_mode`、`relay_chunk_size`、`relay_limit_kb`，同一应用内暂停后从已确认字节偏移继续调用 relay 接口。应用重启后，JVM relay 状态可能已丢失，恢复流程将任务标记为需要重新开始，不调用普通分片 URL、PUT 或 merge。

## 决策四：分享缩略图由分享模块完成授权和输出

新增分享授权缩略图 endpoint。它复用分享访问校验、下载开关和节点范围检查，优先读取预览桶缩略图；缓存不存在时服务端生成并写入预览桶，再输出 JPEG。缩略图请求不递增原文件下载计数。

## 未决项

无。宽限期默认 1 小时，所有可调参数通过配置覆盖。
