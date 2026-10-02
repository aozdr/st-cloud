# 当前修订独立测试报告 env-code-r2

## 背景

执行者：/root/final_test；Dispatch：DISPATCH-env-final-test-01；TASK：TASK-20260930-environment-final-test。用户要求核对旧开发环境数据库和配置，再处理冻结遗留问题；冻结文档仅作为事实线索。

本报告建议 TEST_PASS=pass，限定本轮已执行场景及下列明确边界。没有写 State、执行 Evaluate 或判定主线程 Goal 完成，也不将所有未运行的历史场景标为通过。

## 输入

读取本 TASK、当前 Dispatch 协议/schema、requirement.md、testcases.md、changereport.md、当前源码清单及最小 State。State 显示 IMPLEMENTED、CODE_REVIEW、SECURITY_REVIEW 均为 done/validatedRevision=env-code-r2；TEST_PASS 待主线程 Evaluate。skillRefs 为 -。

主证据为 backend-full-r3.log/backend-final-counts.json、桌面各批日志、live-report.md、exp-accept-report.md、ops-remediation.md、schema 前后与 46 分支、backend-running-final.json、final-runtime-smoke/MQ/tenant 和 S3 当前探针。独立代码及安全结果为 DISPATCH-env-code-review-02、DISPATCH-env-security-01。旧失败日志保留，不用历史 State 或旧总结替代本次证据。

## 分析：源码与运行身份

本 child 独立重算 current-source-manifest.json 中 780 项，缺失/哈希不一致均为 0，见 final-test-source-check.json。清单修订为 env-code-r2，清单 SHA256 为 CA61A5A014F4C0F3AEC0C0A36C0F2B4AEB353377AEF97FE87CDE8F28BF2B0EFE。

最终不可变后端 JAR 的 SHA256 为 C5FF8F3094EDD8AFCC524B9AFCC4E5DE53EEB1F7AE641C2E8DB88AC9A6C81971，与 backend-running-final.json 一致。2026-10-01 10:41 宿主只读确认 8080 的监听 PID 为 10028/java。独立 GET /api/auth/ping 为 HTTP 200/code200。初次误用 /api/ping 得到 401，读取实际 Controller 映射后改为正确公开端点；前者没有被记为产品故障。见 final-test-runtime-check.json。

live 批次使用 r1 JAR，不能整包直接冒充最终 r2。独立比较两 JAR 内嵌 class 与非 META-INF XML/YAML/properties/SQL：st-common、st-auth、st-core、st-share、st-preview 完全一致；st-team 有五个 class 变化，st-sync 有两个监听器 class 变化。故旧真实认证、核心文件/恢复链路仅按相关模块复用，再由当前真实 MQ/图片证据和本轮团队/租户测试覆盖变化。live 认证 4 项源码、同步 26 项源码及体验 8 项源码/构建入口的记录哈希均与当前文件一致，见 final-test-module-reuse.json。

## 分析：测试计数与有效性

| 批次 | 实际结果 | 适用范围 |
| --- | --- | --- |
| 当前全后端 backend-full-r3 | 独立按 125 条逐类日志汇总 853 项：807 执行通过、46 条件跳过，失败 0/错误 0，全部 reactor SUCCESS/BUILD SUCCESS | r3 是测试批次名，代码修订仍 r2；与主线程 backend-final-counts.json 完全一致 |
| 桌面基线全量 desktop-final-regression | 167/167，通过、跳过 0 | 早于重复恢复 ID 修复；不宣称为修复后重新完整执行的 167 项 |
| 桌面当前受影响恢复/状态补验 | desktop-recovery-rounds-pass 34/34；desktop-r2-restart 20/20 | 当前路径/重复保全/pending 兼容及真实 sql.js 跨进程重放，和基线存在重叠 |
| 桌面 TS 用例及认证专项 | TS 21/21；独立体验认证 33/33 | 类型/数据库/上传等受控验证；当前相关认证源码哈希未变 |
| 桌面当前构建 | desktop-r2-build 成功；记录的类型检查通过 | main/preload 构建；没有 Electron 安装包实机启动 |
| Web 当前构建 | web-r2-build、web-r2-desktop-build 成功；记录的类型检查通过 | Web base=/；desktop mode base=./ |
| 胶卷独立 Chrome/React | 17/17 | 真实浏览器与组件、受控 HTTP/旧 error handler；对比度 4.508:1 与 15.862:1 |
| 最终后端真实图片/认证/MQ | final-runtime-smoke status=passed，七项事实检查全部通过 | 真实 8080、Redis、MySQL、Docker S3 与 Chrome 图片解码；不是所有格式转换或分享匿名图片契约 |

不将桌面 167、34、20、21、33 或历史日志重复合计为一个唯一全量用例数。后端独立计数见 final-test-backend-counts.json；不能把 853 全部写成已执行通过。

46 条条件跳过逐项如下。这些类需要显式专用配置，本轮完整 reactor 未开启它们；本报告不从功能探针推导它们通过。

| 类 | 跳过数 | 实际启用条件/边界 |
| --- | --- | --- |
| UserSecurityServiceMysqlIntegrationTest | 4 | test.tenant.context.mysql.url 匹配专用 stcloud_team_recycle_20260930 库 |
| NgramSearchIntegrationTest | 5 | test.es.port 为显式数字端口 |
| ShareMysqlIntegrationTest | 19 | test.mysql.port=60328；其中 S3 专项还需 test.s3.port=60330 |
| UserSecurityRedisIntegrationTest | 18 | test.redis.port 为显式数字端口、专用真实 Redis 安全撤销/多进程条件 |

本次直接变更的团队、回收、认证租户和 MQ 租户场景均有已执行证据；上述遗留专项未开启仍保留为覆盖边界。没有为消除跳过而连接用户会话、清理共享数据或另建未授权 fixture。

## 分析：遗留问题与用例映射

| 编号/用例 | 当前事实与证据 | 结论与限制 |
| --- | --- | --- |
| ENV01、ENV02、DB01；O04 开发环境 | Docker MySQL 8.0.46/stcloud，补缺少 42～46 并新增 47，登记 20260930.1；schema-before 有 7 个差异，schema-after PASS；SchemaConsistencyTest 当前 3 项通过。独立 46 无列字段 bigint/NOT NULL/default0，重复执行保留 73；两次比对退出 0 | 开发目标已对齐。compare-schema 覆盖 H2 共享 22 张表的列集合及全部迁移登记；MySQL 共 38 张表，其余模块按自身测试 schema 验证，不能称为全库逐字段比较 |
| MERGE01、BUILD01 | 11 个初始冲突整合；独立代码复审未见产品冲突标记/阻断缺陷；当前后端候选 13、候选竞争 9、规范对象 GC 1、并发上传 3，以及上传/文本/编辑器/解压事务边界用例通过 | 两侧候选/规范对象安全回归保留；S3 事务外、失败候选延迟 GC 的测试成立 |
| B01；AUTH01、AUTH02 | live-auth-final：真实后端/Redis 连续两轮各 4 个并发 401，仅一次刷新，完整 pair 同步落盘；3 次旧 refresh HTTP200/code1005 拒绝；新 Node 进程磁盘恢复并继续刷新。Redis 最新 pair 匹配。当前最终后端另两轮刷新/旧值拒绝通过 | 客户端保存遗漏、singleflight 与持久恢复已有当前证据；原生 Electron IPC/窗口与 Chrome 自己的真实后端两轮/离线体验仍未执行 |
| V01 | 本轮新建 TASK/State，r2 独立 CODE_REVIEW 和 SECURITY_REVIEW 已通过；本报告独立核对日志、源码、运行身份并提出 TEST_PASS | 不使用历史 State 直接完成；KNOWLEDGE/ACCEPT 和 Goal 完成仍归主线程后续 Evaluate |
| V02；TEAM01 | TeamRoleMysqlConcurrencyIntegrationTest 当前 33 项通过：服务内真实 READ_COMMITTED、实际 InnoDB 等锁、邀请等锁期间撤销/过期、四个权限失效入口串行；原角色/成员/邀请矩阵保留 | 专项可控事务交错通过，不代表长期并发压力 |
| V03；SYNC01 | 两轮真实软删除/恢复后，实际引擎受控重放旧 DELETE331/340，原字节/节点映射保留；停止引擎期间真实 MOVE/RENAME+UPDATE，新 Node 从同一 sql.js 恢复并推进游标；真实 chokidar 上传、S3 字节一致；仅 exclusions 网络故障注入后实际 manager 清理/重试；最终无双斜杠映射，五份恢复清单及旧副本保留 | 后端/MySQL/Redis/MQ/S3、桌面生产模块真实运行；Electron app/窗口事件为宿主适配，旧 DELETE 与启动故障明确为受控注入 |
| V04；WEB01 | 独立 Chrome 17 项：Tab/Space/Enter/左右键、updatedAt/密码/URL 变化、陈旧 error 隔离和焦点/名称/对比度；Sidebar 实际页面检查 | 胶卷失败图由受控 HTTP 注入。真实浏览器深链空白定位相对 base，修复后 Vite 原生预览 JS/CSS 全部 200/正确 MIME、无 pageerror；与 S3 原图故障没有因果证据 |
| V05；DB01 | mysql-46.log、schema-46-first、schema-46-repeat：无列分支创建、重复运行、73 保留、两次比对 0 | 独立实际 MySQL 验证，不只依靠 H2 |
| V06；RECYCLE01 | 当前真实 MySQL 回收竞争 4 项、原 MySQL 18 和 H2 18 通过；锁序空间→节点、锁后权限/状态重读，配额/引用/Outbox 断言；独立 Chrome 实际创建、软删、回收列表查看及恢复专用 0B 目录成功 | 恢复后再返回原团队列表的已登录可见检查未完成；浏览器恢复成功不能替代这一步。长期压力/实机 Electron 未执行 |
| O01 | 最终 PID10028/8080 加载 r2 JAR，hash匹配；当前普通账号注册/两轮刷新、真实 PNG 原图/缩略图/Chrome 解码、MQ journal348 对应 outbox1/retry0 | 日常端口版本加载已证实；最终团队行为由当前后端集成测试覆盖，真实 Chrome 团队操作批次使用先前同核心模块 r1 |
| O02；OPS01 | 当前 Auth/JWT/公开入口、异步审计、TenantTaskRunner/回收扫描、团队活动/过期、MQ/local listener 上下文测试通过；其中团队活动4/过期4、MQ作用域3实际执行。最终正常运行日志 TenantContext warning=0 | 显式默认路径与真实缺租户诊断分开，精确恢复线程原值；0 警告仅指本次真实正常请求/MQ采样，不称所有长期任务永无告警 |
| O03；OPS01 | 8 依赖功能探针通过；真实 MQ 随机 topic SEND_OK 后按队列/offset读取字节；S3 签名 HEAD/随机 PUT/GET+SHA256 两日均通过。最终后端真实 PNG149字节往返、缩略图 image/jpeg660字节、Chrome解码64×32通过 | Docker S3 当前业务读写正常。现有容器尚未重建启用自动healthcheck；Broker无持久卷，需先备份/迁移；ES固定digest为本机自建产物；历史137/OOM根因仍未知 |
| O04 生产边界 | 开发配置环境变量/密钥入口已对齐，生产稳定密钥校验保留 | 没有生产迁移/发布授权，不宣称生产库及多实例稳定密钥一致性已验证 |
| O05 | 专用审计用户、数据库、文件、Redis、S3/MQ资源继续保留 | 不因历史文档清理用户资源 |

## 分析：失败保留与修复后证据

- 旧环境结构比对先失败是迁移前事实，schema-after 及 46 两次比对通过才作为门禁证据。历史迁移名只按结构/数据审计登记，没有盲目重放老数据 SQL。
- backend-full-final 的独立测试库缺默认 tenant/role 配置，后续库补必要基础配置；backend-full-r2 的 TeamCopyDatabaseIntegrationTest 缺 ObjectUploadCandidateService bean，当前测试配置补依赖，最终本类 14 项通过。
- backend-full-r2-final 的 SyncTenantScopeTest 一个错误由 Mockito 重新设 throw 桩时执行旧桩触发，改 doReturn 后定向 3 项通过，backend-full-r3 再完整执行 3 项通过。没有以一次定向成功替代仍失败的全量。
- desktop-recovery-rounds 初次 13 项失败为受控测试 VM 没接入新增 crypto 依赖；更新测试适配后当前 34 项全部通过。重复恢复 ID 碰撞是另一个真实产品缺陷，修复前 live-sync-recovery-id-failure 保留，后续真实两轮及 34/20 项补验成立。
- live 的字符串 pages、双斜杠/根映射、重复 legacy ID 三次真实失败均由主线程修复，并直接复用污染 SQLite 及原副本复验，未清夹具制造成功。
- final-runtime-smoke-initial 用 chunkIndex=0 被当前1起始契约拒绝 code2006；修正本轮脚本分片索引后，最终图片/缩略图/MQ全部通过。它不是 Docker S3 原图读取失败。
- 体验子线程原 Web 深链 JS404/CSS返回HTML及中途空间容量拒绝均保留。相对 base 产品缺陷已修复并独立复验；恢复后原目录可见仍缺实际登录页补验，不能以匿名登录页渲染替代。

## 风险与当前边界

46 条条件专项未运行、Electron 原生窗口/IPC、长期并发压力、所有图片格式转换、浏览器恢复后原团队列表及 Chrome 自己的真实两轮/断网恢复，均未据本次证据判为通过。独立审查没有确认仍需阻断的已知产品缺陷。

体验验收中途剩余可分配为 0B，源于两个本轮空测试空间默认各占 10GiB 配额。主线程随后按精确账号/空间/原值、storage_used=0 和无文件条件 CAS 将各空间配额缩至1MiB；fixture-quota-before/after.tsv 与 fixture-quota-adjust.sql 显示准确更新2行、无删除/权限变化，释放19.998GiB。旧“当前容量0”已被后续事实覆盖，但中途受阻及未完成场景保留；本 child 没有执行该数据库写操作。

自动health尚未进入旧容器，Broker持久化迁移、本地ES镜像的远程可用性、历史Docker故障根因、生产多实例密钥仍为部署边界。未擅自重建、清理资源或提取会话凭据。

live fixture令牌字段已置null；live-sanitization.json记录最终文件扫描/源码身份与脱敏检查通过。体验子线程曾被自动审批拒绝从Redis提取fixture会话，已停止且没有绕过；本 child 未访问任何用户会话或凭据。此次只读监听PID检查获得宿主自动审批，完成后没有进程变更。

## 决策与 State Delta（仅 proposal）

建议 TEST_PASS=pass，validatedRevision=env-code-r2，by=/root/final_test，dispatchId=DISPATCH-env-final-test-01，evidenceRef=.ai/runtime/results/DISPATCH-env-final-test-01.json。

理由：本次修改的有效回归、实际开发环境/数据库、真实认证/同步/S3/MQ链路及最终版本身份成立；先前已确认缺陷有修复后证据，当前没有待修复的已知阻断失败。跳过、受控注入、模块复用和实机/部署边界均明确。此建议不等于全部历史专项或最终 Goal 完成；主线程核对标准范围与依赖后决定 Evaluate。

## 下一步与变更影响

主线程读取本独立结果、核对三元组、作用域和当前修订后 Evaluate TEST_PASS，并处理 KNOWLEDGE/ACCEPT及完成标准。后续范围扩大或产品源码变化须重新判定证据有效性。

本 child 只读取证据/源码/最小State并写 testreport、final-test-* 及独立结果；没有产品/配置/State 修改、Maven/Git/依赖安装、会话提取、真实业务新增/删除、容器或后端进程操作。

