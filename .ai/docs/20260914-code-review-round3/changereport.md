# 变更报告

## 任务

- 任务：`TASK-20260914-code-review-round3`
- 基线：`main@7be97cc`
- 当前验证 revision：`20260914-code-review-round3-working-tree`
- 范围：`st-core`、`st-desktop`、`st-share`、`st-web`、H2/MySQL schema、测试与交付文档。

## 已实现

### P0 规范对象孤儿候选

- 新增 `file_orphan_candidate` 表、实体、Mapper 和 `OrphanObjectCleanupService`。
- 规范对象写入入口统一在外部对象写入前登记活动计数，事务提交成功后移除候选，失败后转为延迟候选。
- 默认宽限期 1 小时、清理间隔 60 秒，可通过 `stcloud.upload.orphan.grace-ms` 和 `stcloud.upload.orphan.cleanup-interval-ms` 覆盖。
- 物理删除前重新检查：无 `NORMAL file_object`、无有效 `file_node` 引用、无活动 `upload_session`；任一条件不满足则保留候选。
- 删除对象调用在数据库事务外执行；删除失败会恢复候选，不再沿规范路径立即删除。
- 归档、文本覆盖、编辑器回调和普通上传入口均接入协调器。

### P1 Relay 序号与失败语义

- `RelayBufferManager` 将序号状态拆为 `committedSeq` / `inFlightSeq`。
- 只接受连续的 `committedSeq + 1`；已提交序号幂等确认，跳号和并发处理中返回冲突。
- body 读取、限速、落盘、`uploadPart` 全部成功后才提交序号。
- `uploadPart` 失败显式终止 relay 会话；分片号仅在远端上传成功后递增。

### P1 Desktop relay 恢复

- `transfer_tasks` 增加并持久化 `transfer_mode`、`relay_chunk_size`、`relay_limit_kb`，旧 SQLite 自动补列。
- 同一进程内恢复只调用 `/relay-chunk` 与 `/relay-finalize`。
- 应用重启后 relay 任务标记为 `restart-required`，要求重新开始上传，禁止降级到 `/chunk-url`、PUT 或普通 merge。

### P2 分享缩略图

- 新增 `GET /api/share/access/thumbnail/{shareCode}`。
- 接口复用分享密码、验证码、过期、下载开关和分享范围校验，只允许图片节点并输出 JPEG 缩略图。
- 优先读取预览对象，不存在时生成并缓存 `sm` 缩略图；原 `/stream` 下载契约保持不变。
- Web 胶卷仅切换到授权缩略图接口，主预览仍使用原流式预览路径。
- 同步修正分享安全测试，使其验证当前默认 12 位、大小写混合且排除易混字符的正式契约。

## 数据库与兼容性

- 新增 `docker/mysql/init/42_file_orphan_candidate.sql`，首行是 `SET NAMES utf8mb4;`，schema version 为 `20260914.1`。
- 已同步 `st-core/src/test/resources/schema.sql` 和迁移 README。
- 已在本地开发/测试 MySQL 执行迁移；未执行生产迁移。
- 既有上传、下载、预览和 relay endpoint 的请求结构未改变；只新增分享缩略图接口和 SQLite 可空迁移列。

## 范围控制

未修改无关 UI、未批量扫描历史对象、未改变普通下载协议、未执行破坏性 Git 操作。
