# 代码自检 disable-redis-code-r1

执行者 workflow-manager。依据本轮用户要求主线程独自完成，属于自检，不是独立review。

核对现有ActiveTracker key与FolderPermissionService.cacheKey/RedisTtlCache前缀，新增服务仅匹配相同固定格式。候选完整匹配防止扫描glob过宽；其他用户/ID前后缀/节点和空间同名ID/未知格式测试保留。批量删除上限128，重复SCAN结果可重复DELETE，空结果不删除，Cursor在正常或异常路径关闭。

afterCommit先沿用refresh撤销，再仅在请求DISABLED时清理；昵称/配额/密码/启用不进入额外SCAN，用户删除/角色路径没有扩大。外层事务提交前未删、回滚保留、再次停用与故障后重试由真实隔离Redis验证。配置自动扫描能装配新服务，实际API打包/新进程启动通过。

旧注册测试修正有独立理由：原AuthService源码哈希保持，已有NOT_SUPPORTED/REQUIRES_NEW契约确保注册自己的事务提交后才发令牌；用例现在验证独立可见性，管理端仍验证未提交不可见与回滚。

未发现阻断项。git diff --check通过；源码/JAR清单见source-manifest.json；测试证据见test-result.json、test-evidence/。已有授权请求不能被本变更强制中断；数据库/Redis非原子性保留并在设计说明。
