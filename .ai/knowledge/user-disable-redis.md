# 停用用户与 Redis 清理

实现：st-admin UserManageServiceImpl.updateUser 在数据库提交后先撤销 stcloud:refresh:<userId>，请求status=DISABLED时再调用UserRedisKeyCleanupService。

只根据key固定段删除 team:active:<spaceId>:<userId> 与 stcloud:cache:<spaceId>:<nodeId>:<userId>:<rolePerms>。SCAN筛选后完整格式复核，128一批DELETE，不读取value/集合；其余共享或无法由key判断归属的状态保留。内存缓存不在用户要求的Redis清理范围。

回滚不删除，再次停用可幂等重试。Redis失败不能回滚已提交的停用，沿用现有错误传播；主库status/security_version持续拒绝旧access/refresh，重新启用需正常登录。已有授权请求不强制中断，可能短暂重建非认证TTL缓存；不承诺跨MySQL/Redis原子性。

改密、启用、删除用户与角色撤权保持既有撤销范围。本次不改接口/数据库。注册原有NOT_SUPPORTED/REQUIRES_NEW独立提交契约不变，安全回归旧TC0122已对齐。最新验证见.ai/docs/20261001-disable-user-redis/testreport.md与运行证据；旧环境浏览器补验见.ai/docs/20261001-browser-acceptance/report.md。
