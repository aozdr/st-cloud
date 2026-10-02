# 当前修订变更记录 env-code-r2

用户目标：核对旧开发环境数据库与配置，并处理冻结遗留问题。已有暂存改动及历史证据保留，本次没有生产发布或清理审计资源。

数据库：实际Docker MySQL8.0.46/stcloud缺少42～46。已增47索引幂等补丁、同步H2、执行缺少迁移、登记20260930.1，迁移前比对暴露差异，迁移后比对退出0；46无列与重复分支在独立库通过两次比对。42候选索引支持失败后重试。compare-schema增加无mysql CLI时的JDBC兼容入口。

冲突：整合11个冲突文件，保留独立UUID上传候选和规范对象GC两侧测试、关注/通知/团队schema及Sidebar两侧功能。上传失败只放弃候选并延迟回收，保持S3事务外。去重当前读去掉会被租户SQL重写破坏的冗余LIMIT；真实并发回归恢复。

认证：桌面主进程/渲染器统一轮换完整令牌对，singleflight、迟到401重放、会话/服务器隔离、临时离线保留和持久恢复。新增IPC兼容旧调用，无HTTP契约变化。受控HTTP33项通过；桌面基线全量167项与当前受影响34项/跨进程20项均有日志，各批次重叠不合计。真实8080/Redis两轮并发刷新与新进程持久恢复见live-report.md，最终后端再补两轮刷新/旧值拒绝。

租户：允许公开入口显式默认1，不覆盖认证租户；AuthService短期作用域，异步审计传递并恢复上下文，仅缺租户填充值才诊断；启用租户后台扫描，PRIVATE单次执行，禁用租户保留数据。新增32项回归通过。非默认租户匿名分享契约未扩展。

团队回收：真实MySQL确认锁前权限/状态决策窗口；所有回收写入口统一空间→节点锁序、锁后权限/状态重读，清空重复父子跳过。当前4个并发专项和原MySQL18项、H2 18项通过；副作用仍使用提交后Outbox，事务内无S3。

胶卷：实际Chrome复现旧error回调污染与低对比度，src变化的请求身份隔离错误；white/90降级图标，17项通过，对比度4.508:1/15.862:1。

配置/运行：MySQL/JWT/S3环境变量对齐，Docker镜像固定实际digest并增加实装工具可执行的健康探针；容器未重建以保留Broker容器层数据。最终完整后端807项执行通过、46项条件跳过，已打包并更新本任务8080实例，运行身份与JAR SHA256见backend-running-final.json。独立测试汇总见testreport.md，最终独立验收由ACCEPT报告提出建议、主线程Evaluate。

验证失败经过与最终证据都保留在本目录。故障注入/受控外部边界必须按报告区分；历史OOM根因未知，生产配置不在此次授权目标。

## r2 独立审查及真实业务复现后的修复

首轮 CODE_REVIEW 未通过，CR01–CR03 均按具体触发修复：removeMember/leaveSpace/setExternalMember/setExternalConfig 先取得同一空间锁并用 READ_COMMITTED；团队活动异步捕获/精确恢复 tenantId/mode；两个过期任务按启用租户运行，外部成员空间锁后按当前类型/过期快照删除，文件锁按锁身份/时间 CAS 释放。新增 MySQL 服务内隔离/锁等待 8 项，团队上下文 8 项；最终全后端中 TeamRoleMysql 33、团队上下文 8 均已通过，完整串行构建成功。

真实 MQ 去重查询先于上下文设置引发告警，已将 SYNC_CHANGE MQ 和本地异步监听器整段放入事件租户作用域并 finally 精确恢复，新增 3 项线程复用/异常/幂等早退测试。

真实桌面引擎复现后端 Long 分页为字符串、根 CREATE 生成双斜杠以及根映射被当历史残留的问题。严格接受安全整数的十进制 pages；统一前缀，登记根映射，只清理同节点同文件的重复斜杠别名。额外重复真实移动复现固定 legacy-nodeId 保全清单碰撞；改为路径/持久状态/游标基线 SHA ID，稳定同轮重试，匹配路径的升级前旧 pending 清单可收尾，所有旧副本保留。新真实原件四轮保全与 pending 兼容测试通过。desktop-recovery-rounds-pass.log 34/34；桌面原全量167/167、21个TS用例及类型通过，受影响跨进程专项20/20见desktop-r2-restart.log。

独立 Chrome 验收发现 Web 深层路由刷新无法加载 JS/CSS；原生 Vite preview 复现，与 S3 无关。Web 默认 base=/，桌面 build:web 显式 --mode desktop 维持 ./，两个构建/类型通过；独立匿名深层访问资源200且正确MIME，无 pageerror。现有 HTTP/API 与路由不变。

2026-10-01 当前 Docker S3 重新执行签名 HEAD、随机 PUT/GET 与 SHA256 字节校验通过（s3-current-20261001.log）。开发 schema 验收时再次只读比对退出0，日志schema-final-20261001.log已保存；该比较覆盖共享22张表列集和完整迁移登记，其余模块表按各自测试schema验证，不把该脚本描述为全库逐字段比较。

最终源码身份见 current-source-manifest.json，780项已由代码/安全复审独立核对。冻结后仅修正新测试Mockito重新设桩方式，生产源码未变；修正后的3项以及最后完整批次均通过。backend-full-r3.log 是重跑批次名，State实现修订为env-code-r2。最终后端853项中807执行通过、46按隔离环境条件跳过，失败/错误0；受影响桌面真实SQLite跨进程20项通过并生成最终包。

8080已从本任务PID26304更新为最终PID10028，JAR/manifest SHA见backend-running-final.json。普通注册内存会话两轮刷新/旧值拒绝、真实PNG上传/原图字节、缩略图200、Chrome解码与MQjournal348均通过，Outbox status1/retry0，采集窗口TenantContext正常链路告警0。r1真实认证/回收/预览的相关class与最终包相同，team/sync更新用当前测试和最终运行补证据。

本轮浏览器两个0B无文件测试空间默认各10GiB造成途中可分配容量0；按精确空间/账号/原配额/0B/无文件CAS调整为各1MiB，实际2行，释放19.998GiB。账号、目录、权限和历史资源均保留，见fixture-quota-before/after.tsv及SQL。被自动审批拒绝的Redis浏览器会话提取未执行，未进行生产部署。
