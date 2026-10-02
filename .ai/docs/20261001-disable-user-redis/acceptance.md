# 验收自检 disable-redis-code-r1

主线程 workflow-manager 按用户明确单人执行偏好，依据本任务Goal判定；非独立验收。

| 标准 | 当前有效证据 |
|---|---|
| A1 三类独占key清除 | 真实停用事务提交前key存在、提交后refresh及跨空间活跃/权限缓存消失；重复停用仍清理。test-evidence/UserSecurityRedisIntegrationTest |
| A2 仅从key判断、不误删 | 单测verifyNoMoreInteractions排除内容读取；真实Redis保留其他用户/1010/同名节点和空间/共享/未知格式key，其他用户会话有效 |
| A3 回滚/重试/故障 | 真实外层回滚保留数据库状态/版本及三类key，原会话有效；SCAN故障时停用已提交、旧会话拒绝，重复停用恢复清理 |
| A4 当前修订验证 | 78执行无失败/错误/跳过，API全依赖打包，新组件开发启动与HTTP探针通过；source-manifest、test-result、runtime-check |

用户收敛范围全部实现。共享内容、数据库/API、实际账号数据未改；生产部署和原生Electron验收不从本授权推导。原浏览器末段补证单独完成，见../20261001-browser-acceptance/report.md；属于此前env-code-r2验证，不冒充本次新代码UI验收。
