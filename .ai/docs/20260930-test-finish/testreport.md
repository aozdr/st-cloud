# 最终测试报告
当前任务 TASK-20260930-TEST-FINISH；代码修订 finish-contract-tests-v9；2026-09-30。

136 条验收逐项全部 PASS，0 FAIL / PARTIAL / NOT RUN。原始标准仍为 ../20260924-code-review-testcases/，完整映射见 results.md。前次127条状态逐项保留原证据，本轮补齐4条同步和5条DB-ID；不改写历史日志。

| 验证 | 实际结果 | 证据 |
|---|---|---|
| 受影响后端及依赖串行全量回归 | 732条报告，713执行通过、19 MySQL条件跳过，0失败 | backend-regression-isolated.log、backend-summary.json、backend-xml/ |
| 上述19条MySQL条件测试补跑 | 19执行通过，0失败/跳过，含InnoDB锁、两JVM、真实S3预签名同URL两次HTTP GET | share-mysql-s3-regression.log、share-mysql-s3.xml |
| 生产DTO/JSON三组大ID契约 | 3通过，团队/成员/邀请/ACL/文件/目录/版本/分享全部实际ID字段、邀请请求 | id-contract.xml、id-contract-config.log |
| H2真实schema及旧Integer契约 | 4条大ID/兼容、3条SchemaConsistency通过；当前全量亦重新执行 | large-id-schema.xml、schema-consistency.xml、backend-xml/ |
| 无版本JWT发布兼容 | 真实Redis、H2 TCP与第二JVM，1条完整组合通过，当前全量再次通过 | jwt-release.log/xml、backend-xml/ |
| 桌面完整回归 | 21+126=147通过，0失败/跳过 | desktop-regression.log |
| 桌面新增ID与协议能力 | 9通过，包含三组root/node/cursor、协议缺失/旧版本拒绝 | desktop-id-config.log |
| 桌面类型与主进程构建 | 通过 | desktop-types.log、desktop-build.log |
| 浏览器团队/复制/预览 | 三轮角色、成员、邀请、ACL与复制＋预览回归通过；全部受控API | browser-matrix.log、browser-id-0～2/、browser-preview请求JSON/PNG |
| 浏览器历史/分享ID | 三组文件/版本/分享目录与节点/路由/query精确 | browser-version-share-complete.log、browser-version-share-requests.json |
| 前端整改文件lint与生产构建 | 通过 | web-scoped-lint.log、web-build.log |
| 证据一致性与流程 | 136唯一编号、XML真实数值、日志和源码SHA256校验；授权正例与3负例；verify-loop FAIL=0/WARN=18 | verify-evidence.log、validated-source-hashes.json、schema-authorization-check.log、verify-loop.log |

初次后端回归失败是Ngram测试硬连共享9200，无可用ES；已记录backend-regression.log，不记作通过。修复测试隔离配置后，专用ES含IK插件实际5条通过，完整回归退出0。新增浏览器历史夹具的团队入口、current/content字段错误均保留失败日志；按实际契约修正后的三轮全部通过。没有因此修改生产UI。

数据门禁：本轮无新增业务DDL。44/45历史迁移的实际MySQL前后两次结构对比、版本唯一登记和DDL失败恢复证据仍只读引用；当前H2存读与MySQL条件分享另记，H2不替代MySQL结构门禁。

证据边界：浏览器API受控、后端MVC/H2为另一层，不声称同一HTTP端到端；搜索游标进程测试ES/候选元数据受控，真实ES分词另有独立5条；鉴权/团队锁多数为H2，不声称MySQL语义；旧Integer和旧仅验签为行为契约夹具，不是运行旧发行包；缩略图两个Renderer并发为同JVM独立实例，不声称两JVM全局限流。全部为验收演练，没有部署。
