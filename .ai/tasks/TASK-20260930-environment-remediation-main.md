# 旧环境与遗留问题主任务

Task ID：TASK-20260930-environment-remediation-main。State：.ai/state/20260930-environment-remediation.yaml。
设计：.ai/docs/20260930-environment-remediation/design.md。用例：.ai/docs/20260930-environment-remediation/testcases.md。
执行者：workflow-manager。目标及验收：requirement.md 五条标准。
include：本任务 .ai/docs、State、TASK、results，.ai/scripts/compare-schema.ps1、.ai/scripts/MysqlJdbc.java、docker/**、st-api 配置、st-core 冲突文件及相关测试、st-team/src/test/**、st-common 租户类及测试、st-web 分享/认证/Sidebar 及测试、st-desktop 认证及测试。
补充精确 include：st-common/src/main/java/com/stcloud/common/config/MyMetaObjectHandler.java 与对应测试、st-admin/src/main/java/com/stcloud/admin/aspect/AuditAspect.java 与对应测试；O02 核查确认异步审计未传播上下文且已有租户实体自动填充仍读取默认上下文，修复不改审计/API契约。
exclude：生产、数据删除、历史 State/审计改写、无关 UI 重构、破坏性 Git、现有暂存改动丢弃。
本次复现补充 include：st-core RecycleBinServiceImpl/TeamStorageMapper/OrphanObjectCleanupService/TenantTaskRunner 与相关 Mapper/测试；st-team/src/main/java/com/stcloud/team/service/impl/TeamServiceImpl.java 共享撤权锁路径及对应并发测试；st-desktop/src/sync/sync-reconcile.ts 与分页兼容专项。均限本次 V03/V06/O02 已发现缺陷，不改 HTTP/API 权限契约。
知识同步 include：.ai/knowledge/local-environment-baseline.md，限本次实际开发环境、迁移/配置/锁序及验证边界事实。
O02真实MQ告警补充 include：st-sync/src/main/java/com/stcloud/sync/listener/SyncChangeMessageConsumer.java、SyncChangeLogListener.java 与对应上下文测试，事件租户先于去重查询/插入建立作用域并精确恢复；保留旧缺租户消息诊断与既有兜底兼容。
共享 Maven 验证与库迁移由主线程串行完成；真实外部缺口如实记录，不用模拟通过替代。
浏览器实测补充 include：st-web/vite.config.ts、st-desktop/package.json；Web 深层路由刷新在原生 Vite preview 证实相对资源 URL 失效，Web 默认 root base，桌面 build:web 显式 desktop mode 维持相对 base。只修构建目标，不改路由与部署路径契约。
V03重复真实移动补充 include：st-desktop/src/sync/sync-recovery.ts 与恢复专项测试。全量对账旧固定 legacy-nodeId 清单在后续保全轮次冲突；新 ID 绑定路径、持久状态基线及游标，同轮失败保持幂等，升级前匹配路径的旧 pending 清单仍续写，旧副本保留。
