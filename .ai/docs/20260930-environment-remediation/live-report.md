# 真实认证与桌面同步专项结果

执行者：`/root/live_validation`。派发：`DISPATCH-env-live-01`，任务：`TASK-20260930-environment-live`。2026-09-30 开始，2026-10-01 恢复后完成。只建议本专项通过，不关闭整体 `TEST_PASS`。

## 背景与输入

按当前 TASK、定版设计和测试用例补 B01、V03。所有写入均使用新建专用账号、文件夹、同步根和本任务目录；未操作已有用户资源。数据库/配置对齐和最终后端 r2 验收由主线程负责。

实际 8080 首次为 PID21224；环境切换后使用主线程恢复的 PID26304，身份见 `backend-running-resume.json`：不可变 r1 JAR 的 SHA256 为 `F1EB01D19674FCE542CCA3BA31A848932F909628279CCF66401576CA5663F5D4`。后端连真实 MySQL、Redis、RocketMQ 和 Docker S3。桌面通过 TypeScript 转译加载当前生产模块；Electron `app` 路径和窗口事件出口以宿主适配，文件、sql.js、chokidar、HTTP、WebSocket 与上传/下载模块实际运行。

## 分析与验证事实

| 场景 | 当前证据与结论 |
| --- | --- |
| AUTH01/AUTH02 | `live-auth-final.log`：同一真实后端/Redis上连续两轮，每轮桌面主进程与 Web auth-session 的 4 个并发 401 共用一次刷新；最新 access/refresh 在两端一致并落盘。每轮旧 refresh 以及恢复前旧 refresh 均被 HTTP200/code1005 拒绝。独立新 Node 进程读真实磁盘 pair，继续刷新成功。 |
| Redis实际值 | `live-redis-final.log`：127.0.0.1:6379 的本轮账号 refresh 与最新持久 pair 完全匹配；报告只记录布尔结果。 |
| 删除/恢复/旧DELETE | `live-sync-final-chain.log` 和 `live-sync-repeat-chain.log`：先真实软删除并记录实际 DELETE，再真实恢复；受控调用实际引擎重放旧日志。DELETE331/340 均保留原字节与同节点映射，真实增量继续推进到332/341。未伪造数据库日志。 |
| 离线MOVE/RENAME+UPDATE及重启 | 停止本任务引擎后，真实HTTP重命名、移动及改内容；全新 Node 进程从同一 sql.js 文件读旧映射。两次分别由332推进到335、341推进到344；新路径 `/destination/renamed.txt` 保持同节点与最新字节，旧路径/映射退出镜像。 |
| 启动故障后重试 | 仅注入 `/exclusions` 网络失败，实际 manager 清理实例；恢复请求后启动成功，真实WS连接和通知出现。没有替换其余HTTP后端。 |
| 本地实际监听上传 | 实际chokidar发现修改，实际upload-manager完成后，S3流式下载MD5与本地新字节一致。等待以同步状态落盘完成为准；首次额外断言过早的失败不作为S3故障。 |
| 重复保全及历史恢复 | 同一已污染SQLite未清库，旧 `legacy-<nodeId>` 副本保留。修复后完整链路连续通过两轮。`live-sync-snapshot.log`/`sync-final-snapshot.json` 证明最终无双斜杠映射、游标344→345、5份恢复清单完整且旧副本字节仍在。 |
| 真实MySQL/MQ | `live-mysql-final.tsv`：34条本轮 journal 均有真实eventLogId，关联Outbox status=1/retry_count=0；最终节点normal、对象引用1，6个文件版本保留。PID26304 启动日志显示 stcloud-sync/SYNC_CHANGE 消费者运行，真实WS收到本轮change通知。 |

本轮专用账号为 `env_live_muo6dq7r` / `2105297388782690305`；云端目录 `2105298513682440193`，同步根 `2105298514185756673`，文件 `2105298514835873794`。首次调试另外两个随机账号仍保留审计，未清共享库。

## 决策与发现的真实问题

真实链路先后确认三个客户端问题，并向主线程报告、由主线程修复后直接复用失败fixture重验：

1. `/file/list` 的分页Long实际序列化为字符串，原 `Number.isInteger(pages)` 阻断全量启动。失败证据 `live-sync-initial-failure.log`；修复安全十进制页数兼容后通过。
2. 同步根CREATE(`/`)递归生成 `//note.txt` 等重复映射，根映射又被当历史残留。失败证据 `live-sync-root-failure.log`；规范prefix和同节点同绝对路径alias处理后，既有污染恢复成功。
3. 重复移动同节点时 `legacy-<nodeId>` 保全ID碰撞旧清单。失败证据 `live-sync-recovery-id-failure.log`；加入稳定路径/原基线/游标生成ID及旧清单兼容后，两轮完整链路通过，旧副本保留。

S3实际上传/下载均成功，本任务没有图片请求失败；测试文件为专用TXT，不能据此声称图片转换/缩略图接口已验收。

## State Delta（proposal）

提出当前派发的部分 `TEST_PASS=pass`，`validatedRevision=env-code-r1`，限定 AUTH01、AUTH02、SYNC01 的上述模块和执行SHA。未写State、未定义Goal、未判定整体完成。主线程后续按最终 `env-code-r2` 和哈希一致性重新汇总 Evaluate。

执行源码内容见 `live-runtime/auth-source-manifest.json` 和 `live-runtime/sync-source-manifest.json`。关键哈希：

- `api-client.ts`：`fcc71681136b4a8f0b6283207a19c22845088b93c77a51a6593d24516dddd51f`
- `auth-session.ts`：`16878feb1f7411e56c08093c5ce806cea8df7d53f05f59ec26e537a0ab09ec32`
- `sync-reconcile.ts`：`1398cada7c6e3d7d36abc3a8fd06408f2ef80346ad6676f2f70b35780d955e6c`
- `sync-recovery.ts`：`9cdd18316d3d324df2362e212a63b5cc069aff74b9cf5d9f3c7b6eea5c789a03`

## 风险与边界

未执行Electron窗口实机、原生IPC传输、真实浏览器完整登录或团队回收站页面；没有长期压力覆盖。旧DELETE和启动故障为明确受控注入，其余HTTP使用真实后端。当前后端是r1不可变JAR，最终r2部署与O02运行告警还须主线程/最终tester复验。

首次调试布尔断言改造前，专用临时账号的refresh曾进入工具诊断；已通过真实Redis/刷新轮换废止。成功证据日志无token。结束时`auth-storage.json`内access/refresh字段已置null并标记redacted，见`live-redaction.log`；未删除账号、文件、映射、数据库或恢复副本。

## 下一步与变更影响

主线程核对Scope/产物、收集独立结果后关闭本child；最终tester依据最终r2与模块SHA复核。需要重放时按`live-runtime/auth-live.cjs`、`sync-live.cjs replay`、`snapshot-proof.cjs`、`redact-credentials.cjs`顺序运行。脱敏后的认证脚本只会从已记录本轮账号Redis恢复新pair，所有凭据仍须在复测结束脱敏。

本child仅新增本任务脚本、日志、源码哈希和独立结果；产品源码均由主线程修改。没有运行Maven/Git、修改State、安装依赖、停止8080或删除用户资源。
