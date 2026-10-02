# 停用账号独占 Redis key 清理设计

修订 disable-redis-design-r1，规模 medium，无 UI。用户为管理端操作员，场景为现有 updateUser 接口将 status 设为 DISABLED。目标是事务提交后删除从 key 名称可准确识别用户归属的 Redis 状态，同时维持旧令牌拒绝与其他用户隔离。

现状：数据库更新 status 与 security_version，提交后删除 stcloud:refresh:<userId>；主库状态/版本拒绝旧 access 和 refresh。用户要求扩大清理至能从 key 直接判断的独占状态，不读取 value 或 Set 成员。

| 允许删除的现有格式 | 归属依据 |
|---|---|
| stcloud:refresh:<userId> | 精确用户 ID 单键，沿用 AuthService 撤销 |
| team:active:<spaceId>:<userId> | 固定前缀、十进制空间 ID、完整末尾用户 ID |
| stcloud:cache:<spaceId>:<nodeId>:<userId>:<rolePerms> | FolderPermissionService.cacheKey 加 RedisTtlCache 前缀，前三 ID 固定位置 |

新增 st-admin key-only 清理服务：只用 SCAN MATCH、DELETE，不用 KEYS/GET/集合操作；两个限定格式分别扫描，每个候选按完整格式再匹配，128 个 key 一批，关闭 Cursor；扫描重复允许幂等删除。用户 ID 为正 Long，禁止 *<userId>* 模糊匹配；空间/节点段为非负十进制数，权限段无冒号且可为空。

updateUser 原 afterCommit 中先撤销 refresh，只有请求 status=DISABLED 才清理额外独占 key；再次停用也执行，支持幂等重试。改密、启用、删除用户和角色撤权保持现有单 refresh 撤销行为。数据库回滚不清理 Redis。

共享 editor:active、保存锁/幂等、下载消费标记、节点可访问/大小缓存、分享/系统配置/限流状态均不处理，即使 value 含该用户也不读取。缓存关闭时只处理 Redis，内存缓存继续 TTL，不能绕过主库状态校验。

异常沿用现有提交后 Redis 失败语义：异常返回上层，数据库停用/版本已提交，旧令牌仍被主库拒绝；重复停用可重试。不声称跨数据库/Redis 原子提交。已进入业务的请求可能后续重建短 TTL 非认证缓存，本变更不强制中断正在执行的请求或引入分布式事务。

兼容：HTTP 契约、key 格式、数据库结构保持，只扩大停用清理范围。无未决范围/风险裁决，confirmationRequired=false，用户已明确收敛。

完成标准：A1 提交后清除三类独占 key；A2 其他用户、相似 ID、共享/未知 key 保留且不读内容；A3 回滚不删，重复停用幂等，失败时数据库停用和旧令牌拒绝仍正确；A4 本修订相关测试和模块编译通过。单人评审明确标自检。

回归发现旧 TC0122 的 register 断言与先前已验收的 NOT_SUPPORTED + REQUIRES_NEW 实现不一致，修正测试预期：注册自己的事务提交后才签发会话，调用者事务回滚不撤销已独立提交的注册；管理端 createUser 继续参与调用者事务。AuthService 哈希与先前 current-source-manifest 一致，未修改注册产品逻辑。

环境收尾：打包后只替换本轮由主线程启动、命令与监听端口均匹配的开发8080进程；不重建Docker或停用实际账号。新JAR与源码清单保存在本任务目录，启动失败可恢复原已验收JAR。
