# CR-01 程序设计

修订：design-v1；主线程自检；仅文档。

## 1. 代码依据与组件

| 现有位置 | 计划职责 |
|---|---|
| `st-common/src/main/java/com/stcloud/common/utils/JwtUtils.java` | Access/Refresh 增加 securityVersion；新 Access 显式 type=access；签名、到期逻辑不变 |
| `st-auth/src/main/java/com/stcloud/auth/entity/SysUser.java`、`mapper/SysUserMapper.java` | Long securityVersion、显式当前状态查询、原子版本递增 |
| `st-auth/src/main/java/com/stcloud/auth/service/AuthService.java` | 注册/登录/刷新通过独立快照组件签发，不让外部 Redis 操作处于 DB 事务内 |
| `st-auth/src/main/java/com/stcloud/auth/security/JwtAuthenticationFilter.java` | 用途分派后统一 Access 校验，成功才设置上下文 |
| `st-admin/src/main/java/com/stcloud/admin/service/impl/UserManageServiceImpl.java` | updateUser/deleteUser 在写事务中同步失效 |
| `st-admin/src/main/java/com/stcloud/admin/service/impl/RoleServiceImpl.java` | assignRolesToUser、assignPermissions、updateRole、deleteRole 的版本传播 |
| `st-sync/src/main/java/com/stcloud/sync/ws/SyncAuthHandshakeInterceptor.java`、`SyncWebSocketHandler.java` | 共用认证判据，消息前复核 |

拟新增 `UserSecurityService`（st-auth，状态校验与递增）及 `AuthSnapshotService`（st-auth，短 DB 快照事务），名字为设计建议，文件尚未创建。若 st-sync 无直接 st-auth 依赖，以 st-common 的小型 Access 校验接口由 st-auth 实现，不使 common 依赖业务模块。

## 2. 表与令牌契约

sys_user 增加 `security_version BIGINT NOT NULL DEFAULT 0`；不改变主键和既有索引，不按版本单独建索引。查询按 userId 与 tenantId 精确定位，明确过滤逻辑删除；启动鉴权未建立 TenantContext 时仍需显式租户条件。令牌 tenantId 必须与 DB 一致，不能仅由 token 设置上下文后相信自动过滤。

新 Access 声明包含 type=access、securityVersion，保留 roles/permissions/dataScope。Refresh 声明增加 tenantId、securityVersion，type 保持 refresh。服务端用整数解析，拒绝缺失、负数、溢出或非整数字段。普通 Access 只接受新用途与版本，不兼容省略版本的历史 token。

Java 全局 Long→String JSON 配置不决定 JWT claim 编码，JWT 生成和解析须使用一致的整数类型。登录响应字段不改名。JwtUtils 所有生产签发调用点一并升级，旧重载不得默默签发缺版本的普通 Access。

## 3. 认证流程与一致性边界

验签及到期检查 → 用途分派 → Access 从主库一次读取用户状态/版本及租户状态 → 对比 tenantId/userId/securityVersion → 构造角色权限上下文。校验读取不得使用副本或二级缓存；不复用请求早前开启的长期快照。数据库异常时不建立认证，沿现有未认证错误路径返回，日志不含 token。

download/editor 继续原端点与 nodeId/jti 约束；refresh 或未知用途不得进入普通认证分支。请求结束清理上下文用真正的 finally 包围后续 filterChain，避免异常遗留线程上下文。

撤权保证的线性化边界为“安全变更提交后开始的认证主库查询”。已校验请求和专用短时流令牌不被强制中断，不宣称回收已发送内容。

## 4. 安全写事务

UserSecurityService 的递增使用 `security_version = security_version + 1`，不把实体旧值加一回写。所有安全写事务首先锁定对应租户行，再修改角色/关联/用户并更新版本，统一顺序为租户 → 角色 → 用户 ID 升序。该租户级串行点仅用于低频安全管理写入，不用于普通请求鉴权和登录读取。

这是本轮为避免角色成员集合与角色权限变更交错漏递增所选的保守方案。锁内禁止 Redis、S3、HTTP、密码哈希计算；计算密码哈希在事务前完成。现有方法若先查后锁，必须拆出事务入口并在锁后重新读取决策状态；安全写服务使用 READ_COMMITTED，避免锁前快照读影响后续集合查询。用独立 Spring Bean/TransactionTemplate 实现事务边界，不能靠同类自调用注解。

| 写操作 | 版本处理 |
|---|---|
| 禁用/重新启用、密码重置 | 状态实际变化/密码实际重置时对目标用户递增；昵称、配额不单独递增 |
| 删除用户 | 同事务先递增并逻辑删除；认证仍以不可用状态拒绝 |
| 用户角色分配 | 校验目标用户与角色同租户；变更关联与用户递增同事务 |
| 角色权限、有效状态或数据范围变化 | 对角色全部关联用户去重递增；不只针对在线用户 |
| 删除角色 | 删除关联前取得受影响用户，在同事务完成删除和递增 |
| 注册/新建用户 | 初始化版本，默认角色关系同事务落库；不签发未提交用户会话 |

批量受影响用户可分 SQL 批次，但不得分提交造成“权限已改而部分用户版本未改”。大租户修改期间锁等待和事务时长是已知风险；未来优化需保留相同原子保证，不能异步补版本。本轮不新增未存在的强制下线 API。

## 5. 一致签发和刷新

AuthSnapshotService 使用主库独立只读 REPEATABLE_READ 快照事务，一次读取用户状态、密码哈希、securityVersion、租户、角色、权限和 dataScope，返回不可变快照后结束事务。不得把事务前读到的 user 实体和事务内权限拼接。

登录验证密码针对该快照哈希，签名在事务结束后执行；若随后发生安全变更，新签出的旧版本 token 会在认证时被拒绝，安全上允许本次登录重试。首次注册 DB 提交后重新取快照再签发；将当前 register 中 Redis 登记移到提交后的编排层。

刷新顺序：验证 refresh 用途/签名/到期 → Redis token 精确匹配 → 读取完整 DB 快照并比对 refresh 安全版本 → 生成相同快照版本的新 Access/Refresh → Redis 比较旧值后原子替换。比较替换失败不返回 token，避免刷新与 revoke 相互覆盖。即使安全变更在最后一步交错，旧版本刷新令牌也无法继续刷新。

禁用/删除/改密后的 Redis revoke 移至提交后操作；失败记录并重试，但数据库版本与状态已保证旧 Access/Refresh 不可用。角色权限变化同样会使旧 refresh 的版本失效。现有 Redis 登记机制保留，新增版本校验不依赖 Redis 清理成功。

## 6. WebSocket 与兼容

握手只接受 Access，用共享校验取得 tenantId/userId/version/exp，存会话属性；不存 token 日志。sendToUser、sendToTenantUser 与接收消息前复核当前状态/版本/exp，失效则关闭连接并移出注册表。空闲连接可到下一次消息才关闭，但不得再接受或输出业务消息；不新增协议字段。

发布采用 schema 扩展 → 完成所有服务构建 → 排空旧鉴权实例并切换 → 客户端重新登录。普通滚动期间旧服务仍可绕过版本校验，不将混合版本阶段标为整改完成。回退保留新增列；若回退到不校验版本的代码，需暂时限制业务入口，不能声称撤权保证仍成立。

## 7. 迁移、需求映射与限制

迁移遵循总设计的编号/版本登记、H2 schema 同步及 MySQL 前后对比流程；本轮不写 SQL 文件，不连接数据库。R01-1/3 由原子安全写与一致快照保证；R01-2/4 为主库校验；R01-5 为版本绑定与 Redis CAS；R01-6/7/8 为用途分派、升级和 WS 设计。

运行验证尚未进行。性能预算需在后续验证中观察主库读量和管理事务锁等待；不以“Redis miss 回填”优化替换本设计的一致性保证。无待裁决范围问题。
