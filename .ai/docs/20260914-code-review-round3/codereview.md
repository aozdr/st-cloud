# Code Review 报告

## Review 范围

- 固定点：`main@7be97cc`
- 复核 revision：`20260914-code-review-round3-working-tree`
- 方法：按 Standards 与 Spec 两条轴独立复核实现、测试、schema 和文档；覆盖本轮新增与修改文件，不把附件中的执行计划当作代码事实。

## Standards 轴

| 检查项 | 结论 | 证据 |
|---|---|---|
| 事务与对象存储边界 | 通过 | 规范对象写入先登记、提交后确认；外部删除在事务外；失败候选可补偿 |
| 并发与去重 | 通过 | 候选表唯一键、活动计数、删除前三重检查；relay 使用 committed/in-flight |
| API/兼容性 | 通过 | 新增 thumbnail endpoint；既有 stream、普通上传和下载接口保留 |
| 数据库版本 | 通过 | 42 号迁移首行符合约束，H2 同步，schema 对比两次 PASS |
| 测试与可维护性 | 通过 | P0 race、relay 失败/跳号、SQLite 迁移、恢复禁止降级均有测试；核心逻辑有中文注释 |

## Spec 轴

| 计划验收项 | 结论 | 证据 |
|---|---|---|
| 规范对象不立即删除并延迟安全回收 | 通过 | 四个写入入口接入候选协调器；宽限期与 NORMAL/ref/session 三重检查已实现 |
| relay 序号完整请求成功后提交 | 通过 | `uploadPart`、body、追加失败均不会确认 seq；失败显式 abort 或释放 |
| desktop relay 不绕回直传 | 通过 | 同进程只走 relay；重启任务明确 restart-required；无 direct fallback |
| 分享胶卷授权缩略图 | 通过 | 新 endpoint 复用访问、密码、过期、下载权限、范围和图片类型检查；前端胶卷只用缩略图 |
| 门禁与交付物 | 通过 | core、team、web、desktop、schema 及报告/ADR 均已安排或完成 |

## Findings

- P0：无。
- P1：无。
- P2：无阻断性问题。

## 非阻断残留

1. relay 状态仍是服务端 JVM 内存态；应用重启后客户端要求重新开始，旧 multipart 的最终清理由服务端超时/abort 机制负责，这是本轮明确选择的安全边界。
2. 首次请求生成缩略图是同步的预览缓存路径，后续请求读取缓存；本轮不扩展异步图片处理或缓存击穿治理。

## Review 决定

本轮实现满足计划范围，未发现新增 P0/P1 或需要阻断交付的 P2；在已知基线测试失败被明确记录的前提下，Code Review 通过。
