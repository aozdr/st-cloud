# 2026-09-30逐项验收证据

共136条：{"PASS":136,"FAIL":0,"PARTIAL":0,"NOT RUN":0}。

未更新项引用[前次逐项记录](../20260927-test-resume/results.md)及其原始日志，本轮不改写历史；更新项日志均位于本目录。状态是逐项结论，整体回归/自检/流程验收另见testreport.md与acceptance.md。

| 编号 | 状态 | 证据或边界 |
|---|---|---|
| [TC01-01](../20260924-code-review-testcases/CR-01.md) | PASS | UserManageSecurityIntegrationTest：真实管理事务、H2/MyBatis租户链、AuthService及JWT过滤器，禁用/逻辑删除/改密提交后旧Access不能进入受保护探针，旧Refresh拒绝；改密后旧密码失败新密码登录成功，U2保持有效。17项报告见admin-security.log，Redis有状态替身。 |
| [TC01-02](../20260924-code-review-testcases/CR-01.md) | PASS | UserManageSecurityIntegrationTest：撤销ADMIN、改普通角色、删除所持自定义角色三变体，提交后旧Access/Refresh拒绝，新登录权限不含admin:user:manage，无关U2版本/会话不变。真实H2事务及服务/过滤器；admin-security.log。 |
| [TC01-03](../20260924-code-review-testcases/CR-01.md) | PASS | UserManageSecurityIntegrationTest：删除权限、禁用角色、dataScope从3降1；在线U1与未登录U3均递增版本，旧令牌或旧版本认证拒绝，U2不变，新登录权限/范围符合当前数据。admin-security.log。 |
| [TC01-04](../20260924-code-review-testcases/CR-01.md) | PASS | UserManageSecurityIntegrationTest：昵称与配额分别单独修改，精确核对更新字段及未指定字段，版本保持0，旧Access经真实JWT过滤器仍进入受保护探针。admin-security.log。 |
| [TC01-05](../20260924-code-review-testcases/CR-01.md) | PASS | UserManageSecurityIntegrationTest：改密、角色分配、权限分配在安全版本SQL之后触发H2真实唯一键异常；密码/角色/权限/版本均回滚，旧Access保持可用。admin-security.log；不声称MySQL锁语义。 |
| [TC01-06](../20260924-code-review-testcases/CR-01.md) | PASS | 实际签名Access/Refresh串联主库状态：错误租户、不存在/禁用/删除用户、禁用/删除租户全部拒绝；auth-signed-variants.xml，独立H2。 |
| [TC01-07](../20260924-code-review-testcases/CR-01.md) | PASS | 实际签名Access/Refresh的缺失、负值、溢出、小数、数字字符串版本全部拒绝；Redis明确登记对应Refresh仍不能绕过；合法登录后Access可用。auth-signed-variants.xml。 |
| [TC01-08](../20260924-code-review-testcases/CR-01.md) | PASS | UserSecurityRedisIntegrationTest：真实签名Access经JWT过滤器，主库安全状态Mapper抛QueryTimeoutException时不进入受保护探针且未读Redis，恢复真实Mapper后可用。auth-real-redis-fixed.log；异常为查询入口注入。 |
| [TC01-09](../20260924-code-review-testcases/CR-01.md) | PASS | 真实签名非Access在普通HTTP过滤器与真实WS握手均拒绝；download/editor正确节点流通过、错误节点/端点拒绝、到期拒绝；download普通重放拒绝、Range续传通过。auth-complete-matrix.log与auth-websocket.log。 |
| [TC01-10](../20260924-code-review-testcases/CR-01.md) | PASS | 真实Access认证后下游抛ServletException，用户/SecurityContext清理；同线程下一用户正确、匿名不能继承；篡改签名与已过期签名令牌均拒绝。auth-complete-matrix.log。 |
| [TC01-11](../20260924-code-review-testcases/CR-01.md) | PASS | 实际Redis7与Lua CAS并发汇合复现同秒Refresh相同导致双成功；加入随机jti后只有一个成功，最终登记值等于赢家Refresh且旧令牌拒绝。auth-real-redis-before.txt保留失败，auth-real-redis-fixed.log验证修复。 |
| [TC01-12](../20260924-code-review-testcases/CR-01.md) | PASS | 真实Redis/H2事务：Refresh初次版本检查后暂停，撤权提交并重新登录，旧刷新拒绝且不能覆盖新登记值。auth-real-redis-fixed.log。 |
| [TC01-13](../20260924-code-review-testcases/CR-01.md) | PASS | 末次版本检查后撤权：返回的Access/Refresh仍为旧版本并均拒绝；另一变体CAS前删除登记值，刷新比较失败且Redis保持空。auth-real-redis-fixed.log。 |
| [TC01-14](../20260924-code-review-testcases/CR-01.md) | PASS | 登录读取角色前/后屏障暂停并发撤权：签发版本始终为旧0，不能进入受保护业务或刷新；随后新登录版本有效且无撤回权限。真实Mapper/H2/Redis，auth-real-redis-fixed.log。 |
| [TC01-15](../20260924-code-review-testcases/CR-01.md) | PASS | 角色分配与撤权/删除四种先后持锁交错；真实事务第二线程不能越过安全写锁，最终成员、权限、版本及旧会话均符合提交顺序，无部分提交。auth-role-concurrency.log及auth-signed-variants.xml；H2不声称MySQL锁行为。 |
| [TC01-16](../20260924-code-review-testcases/CR-01.md) | PASS | 两独立JVM共享同一个H2主库与Redis：A改密提交后Redis delete异常且F0保留，B仍拒绝旧Access/Refresh，子进程日志不含两种令牌。auth-two-process.log、auth-complete-matrix.log。 |
| [TC01-17](../20260924-code-review-testcases/CR-01.md) | PASS | UserManageSecurityIntegrationTest：A在旧实体读取后、安全写锁前屏障暂停，B改密提交，A仅改昵称继续；新密码和版本1保留，旧密码/Access/Refresh拒绝，新密码登录成功。真实双线程/事务/H2，admin-security.log。 |
| [TC01-18](../20260924-code-review-testcases/CR-01.md) | PASS | UserManageSecurityIntegrationTest：同一锁前屏障交错B禁用与A更新；A仅改昵称保持禁用及版本1，A显式启用提交版本2，禁用前令牌与版本1均不能复活。admin-security.log。 |
| [TC01-19](../20260924-code-review-testcases/CR-01.md) | PASS | 真实随机端口Tomcat/Java WS连接，主库撤权提交后分别触发接收、sendToUser、sendToTenantUser；关闭1003，无新业务消息且会话计数0。auth-websocket.log（12项通过）。 |
| [TC01-20](../20260924-code-review-testcases/CR-01.md) | PASS | 真实WS连接跨过签名令牌实际到期时间后，三个收发入口均关闭1003并清理会话；过期/缺失版本握手拒绝。auth-websocket.log。 |
| [TC01-21](../20260924-code-review-testcases/CR-01.md) | PASS | 独立JVM B已鉴权请求屏障暂停，A提交撤权，B在途请求按约定完成；B之后全新认证和刷新均拒绝。真实共享H2 TCP主库和Redis；auth-complete-matrix.log。 |
| [TC01-22](../20260924-code-review-testcases/CR-01.md) | PASS | 注册与管理新建×提交/回滚：事务内独立连接看不到用户且Redis无登记；注册提交后令牌/刷新可用，回滚后用户/登记不存在且已生成令牌不能鉴权；成功新用户角色无管理权限。auth-complete-matrix.log，真实H2事务/Redis。 |
| [TC02-01](../20260924-code-review-testcases/CR-02.md) | PASS | 真实MVC/H2直接邀请大ID精确落库和响应（team-full-regression.log，23项接口测试通过）；实际浏览器组件请求、选项及重新打开回显逐字符核对（browser-team-final.log、browser-team-requests.json）。浏览器API受控，后端另层验证。 |
| [TC02-02](../20260924-code-review-testcases/CR-02.md) | PASS | TeamRoleApiIntegrationTest：MVC创建大ID邀请、接受、列表响应与真实H2成员role精确相同字符串，成员实际具备upload，非默认查看者。team-role-contract-concurrency-fixed.log。 |
| [TC02-03](../20260924-code-review-testcases/CR-02.md) | PASS | 真实权限集成验证R→R2后ACL反转；browser-team-final.log验证成员更新后再次打开仍为R2，完整字符串提交；team-full-regression.log。 |
| [TC02-04](../20260924-code-review-testcases/CR-02.md) | PASS | MVC直接邀请/链接/成员更新覆盖数值及字符串0/1/2，响应role均文本，数据库精确；省略邀请role默认2。使用生产JacksonConfig，team-role-contract-concurrency-fixed.log。 |
| [TC02-05](../20260924-code-review-testcases/CR-02.md) | PASS | JSON/query非法空/null/负值/小数/科学计数/布尔/超Long/大数字13组，成员/邀请数和原角色无变化；大自定义JSON数字拒绝，query合法大ID不存在仍拒绝。team-role-contract-concurrency-fixed.log。 |
| [TC02-06](../20260924-code-review-testcases/CR-02.md) | PASS | TeamServicePermissionIntegrationTest：H2 真 Mapper 对不存在、停用、逻辑删除、他空间及他租户的大 ID 角色逐一拒绝成员分配，原成员角色保持 2。 |
| [TC02-07](../20260924-code-review-testcases/CR-02.md) | PASS | 真实MVC+H2查看通过、上传入口拒绝且无节点/用量变化；D授权upload后仅D进入UploadService，相邻目录仍拒绝。存储服务受控，不声称物理上传；team-full-regression.log。 |
| [TC02-08](../20260924-code-review-testcases/CR-02.md) | PASS | TeamServicePermissionIntegrationTest：H2 真 Mapper 将大 ID 有效空角色赋给成员且无 ACL；checkPermission 两个重载分别以 null 与普通等级为最低要求，四组合全部拒绝。 |
| [TC02-09](../20260924-code-review-testcases/CR-02.md) | PASS | TeamServicePermissionIntegrationTest：大 ID 有效空角色叠加成员目录 ACL 后 view/upload 均通过；角色停用但 ACL 保留时授权直接拒绝。 |
| [TC02-10](../20260924-code-review-testcases/CR-02.md) | PASS | 两独立JVM共享H2 TCP主库：A两次预热查看与搜索，B自动提交角色权限撤回/恢复及成员角色切换，不发送缓存失效；A下一次授权及搜索立即对应false/0、true/1、false/0。team-process.xml。 |
| [TC02-11](../20260924-code-review-testcases/CR-02.md) | PASS | TeamServicePermissionIntegrationTest：创建链接后分别停用/删除角色、令链接过期/撤销，再以邀请人接受四变体均拒绝，未新增成员。 |
| [TC02-12](../20260924-code-review-testcases/CR-02.md) | PASS | TeamServicePermissionIntegrationTest：大 ID 角色被成员或有效邀请引用时删除均拒绝且角色保留；成员改回预设、邀请撤销后可删，仅过期邀请引用也可删。 |
| [TC02-13](../20260924-code-review-testcases/CR-02.md) | PASS | 真实空间行锁8种两线程交错：直接邀请/链接创建/成员切换引用先则删除拒绝，删除先则新增拒绝；接受邀请已有有效引用，两种顺序均删除拒绝且加入成功。最终无悬空引用，team-role-contract-concurrency-fixed.log；H2证据。 |
| [TC02-14](../20260924-code-review-testcases/CR-02.md) | PASS | TeamServicePermissionIntegrationTest：拥有者改为非管理员、修改/删除 0/1/2 预设角色、修改/删除他空间角色均拒绝；原成员角色及自定义角色名称不变。 |
| [TC02-15](../20260924-code-review-testcases/CR-02.md) | PASS | TeamAccessRevocationIntegrationTest：到期/关闭外部协作/移除成员分别拒绝查看、上传、目录权限和实际搜索输出；H2真实策略、ES受控候选。external-off原缺陷复现后修复，team-full-regression.log。 |
| [TC02-16](../20260924-code-review-testcases/CR-02.md) | PASS | TeamServicePermissionIntegrationTest：相邻超安全 ID 9007199254740993/995 的 role ACL 只授权本角色目录；切换后结果反转，旧等级仍为小整数 2。 |
| [TC02-17](../20260924-code-review-testcases/CR-02.md) | PASS | 实际浏览器直接邀请/链接/成员选项精确去重，保存并重新打开保持；未知大ID显示无效角色而非查看者。browser-team-final.log；API受控。 |
| [TC02-18](../20260924-code-review-testcases/CR-02.md) | PASS | 浏览器403与网络失败覆盖直接邀请、链接、成员修改；保留选择、未保存提示、无虚假持久化，恢复后重试成功。修复成员失败时丢失选择；browser-team-actions-4.log复现、browser-team-final.log验证。 |
| [TC02-19](../20260924-code-review-testcases/CR-02.md) | PASS | 浏览器同名大ID角色分别编辑ACL，提交subjectId字符串并重新打开核对各自upload结果；混合预设选项按ID保持。browser-team-final.log及请求记录。 |
| [TC02-20](../20260924-code-review-testcases/CR-02.md) | PASS | 角色/ACL×授予/撤回×提交/回滚8个真实H2事务交错：独立连接看不到未提交权限，提交后立即使用新授权，回滚原授权；抑制缓存清理仍成立。team-full-regression.log。 |
| [TC02-21](../20260924-code-review-testcases/CR-02.md) | PASS | 普通/外部成员失效角色在checkPermission、view/upload、目录权限、显式主体策略及实际搜索服务一致拒绝，搜索无节点输出；真实H2与受控ES，team-full-regression.log。 |
| [TC03-01](../20260924-code-review-testcases/CR-03.md) | PASS | TeamCopyDatabaseIntegrationTest：真实Controller→授权→H2链，目标只有view拒绝；节点、对象引用、空间用量、Outbox和活动调用快照不变，无存储访问。team-copy-db-complete.log。 |
| [TC03-02](../20260924-code-review-testcases/CR-03.md) | PASS | 源view通过且download明确拒绝，目标upload允许后实际复制；新增1节点、引用+1、空间用量+1024、索引/同步各1条Outbox、复制活动1次，无外部存储调用。team-copy-db-complete.log。 |
| [TC03-03](../20260924-code-review-testcases/CR-03.md) | PASS | null/0两种根参数分别先拒绝无副作用，再授予根upload后复制成功且引用+1；真实Controller/权限/事务/H2。team-copy-db-complete.log。 |
| [TC03-04](../20260924-code-review-testcases/CR-03.md) | PASS | 真实无基础权限角色，仅F1可view、目标可upload；[F1,F2]/[F2,F1]均整体拒绝，节点/引用/用量/Outbox/活动快照不变。team-copy-db-complete.log。 |
| [TC03-05](../20260924-code-review-testcases/CR-03.md) | PASS | FileServiceFlowIntegrationTest：他空间源/目标、回收站源、未完成上传源及目录子项、普通文件目标均拒绝；节点/对象/用户用量与事件快照不变。首次复现非法副本残留，修复后通过。 |
| [TC03-06](../20260924-code-review-testcases/CR-03.md) | PASS | FileServiceFlowIntegrationTest：H2 真实 Mapper 的文件和嵌套目录分别超团队配额时，复制前拒绝；节点、对象、用量和成功事件均不增加。首次复现先插入非法副本，修复后通过。 |
| [TC03-07](../20260924-code-review-testcases/CR-03.md) | PASS | 真实服务复制单文件/批量/两层目录，已有同名目标生成(1)，子树parent/path完整；每文件引用+1、用量精确，逐新节点核对FILE_INDEX与SYNC_CHANGE Outbox的ID/path/parent/space一致。team-copy-db-complete.log；未声称真实MQ/ES消费。 |
| [TC03-08](../20260924-code-review-testcases/CR-03.md) | PASS | 真实浏览器打开复制对话框后模拟服务端撤权，两次提交均拒绝且保持选择，无虚假成功（browser-copy.log/png）；真实Controller/H2撤权后两次复制副作用快照不变（team-copy-db-final.log），提交隔离另由TC02-20验证。分层测试，非同一HTTP端到端。 |
| [TC03-09](../20260924-code-review-testcases/CR-03.md) | PASS | 预设/有效自定义/有效空角色+ACL实际复制并增加对象引用；失效角色即使保留同样ACL仍拒绝，数据库及活动副作用快照不变。team-copy-db-complete.log。 |
| [TC04-01](../20260924-code-review-testcases/CR-04.md) | PASS | SyncServiceProjectionTest：真实 delta 服务逻辑，受控 Mapper 日志；文件/目录 × MOVE/RENAME × 四种根边界共16组合，类型、相对路径、状态和大ID字符串逐项断言。 |
| [TC04-02](../20260924-code-review-testcases/CR-04.md) | PASS | 当前SyncServiceProjectionTest含MOVE/RENAME排除双向、同路径脏事件与相似前缀；desktop-regression.log实际chokidar新建/改名排除子树无上传，普通文件正常上传；backend-xml对应Projection报告。 |
| [TC04-03](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：两个实际引擎、独立目录和同一落盘SQLite的两组rootId；A→B迁移分别A拉取断网/B下载断网，成功根独立提交精确大cursor，失败根状态保持；跨进程重开后A原件保全、B绑定N且内容完整，B原哨兵不变，无反向写入。证据 desktop-test-final.log。 |
| [TC04-04](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：实际引擎+SQLite，移入101文件、两层子树、空目录和排除目录；第二页失败不提交，独立进程恢复仅补缺失文件，最终内容及游标正确。 |
| [TC04-05](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：父目录mtime等于基线但子文件已修改，移出后已修改及未跟踪字节完整保存在恢复树，无反向上传，根B不变。 |
| [TC04-06](../20260924-code-review-testcases/CR-04.md) | PASS | SyncServiceProjectionTest：501条原始日志中前500条范围外，空页仍返回扫描游标和hasMore，下一页有效事件不丢；sync-cursor.test.cjs验证客户端空页续传及完成后提交。 |
| [TC04-07](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：实际 SyncEngine+落盘 SQLite；同页第二事件下载失败，首事件文件/绑定持久化但页游标不变；新进程重放首事件无新下载或写入，第二事件完成后提交，根 B 快照不变。 |
| [TC04-08](../20260924-code-review-testcases/CR-04.md) | PASS | sync-cursor.test.cjs：数字/非法游标、损坏 changes/hasMore、缺失/错误 v2、hasMore 不前进均拒绝且不固化游标。 |
| [TC04-09](../20260924-code-review-testcases/CR-04.md) | PASS | 历史SyncServiceProjectionTest已验证缺oldPath及根移动的服务端标记；本轮实际客户端接受reconcileRequired，根改名/跨父目录移动/缺oldPath触发的受控响应分别注入列举、根详情失败（6项），失败保留根与cursor，跨进程恢复补齐当前树并保全旧内容，使用同一cloudFolderNodeId，B不变。证据 sync-root-reconcile.log、desktop-test-final.log；服务端与客户端分层验证，非真实HTTP端到端。 |
| [TC04-10](../20260924-code-review-testcases/CR-04.md) | PASS | sync-path/recovery/restart.test.cjs：合法路径正常；实际引擎+SQLite 对当前路径/oldPath 的 ../、盘符、目录联接及恢复目录联接七组合拒绝，根外哨兵/源文件/状态/游标保持不变。修复恢复目录联接重定向写入缺陷，复现见 recovery-link-repro.log。 |
| [TC04-11](../20260924-code-review-testcases/CR-04.md) | PASS | SyncScopeIntegrationTest真实H2/MyBatis租户拦截与FileService/SyncService：跨用户、跨租户、跨根delta/排除配置/节点读取拒绝，另根数据不变。sync-scope-run3.log及sync-scope.xml，1项通过。 |
| [TC04-12](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：历史CREATE/MOVE分别对应当前新位置、根外、仍有根内路径但已回收的节点；HTTP 403请求拒绝及详情超时均保留旧字节/身份/cursor，独立进程恢复后只补当前树或安全保全旧内容，不重建过时路径，根B不变。8项补强定向验证通过，见 sync-historical-final.log；受控API，不声称真实HTTP服务集成。 |
| [TC04-13](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：旧 DELETE 分别对应已返回的同节点 N 和占用原路径的新节点 X；实际引擎保留原字节及正确节点绑定，无下载/恢复搬移，完成页游标。 |
| [TC04-14](../20260924-code-review-testcases/CR-04.md) | PASS | desktop-regression.log：MOVE/RENAME×源存在/源缺失/目标被占用及目录补齐，源缺失下载失败保留状态/cursor、恢复成功才删除旧映射，watcher自身事件抑制另有完整回归。 |
| [TC04-15](../20260924-code-review-testcases/CR-04.md) | PASS | desktop-regression.log：旧路径X且mtime未改，失败保护X，独立进程恢复后N/X位置与内容各自正确。 |
| [TC04-16](../20260924-code-review-testcases/CR-04.md) | PASS | desktop-regression.log：未知身份原件保留不绑定N，直接消费同路径脏MOVE不能改绑X，失败保留基线、独立进程恢复。 |
| [TC04-17](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：已改目录移动后第二页失败，恢复树完整、目标pending及旧cursor持久化；新进程只补4个缺失文件，最终synced且根B不变。 |
| [TC04-18](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：源目录不存在、第二页失败后分别同引擎与新进程续传完成；其他节点占用目标时拒绝且原字节/绑定/游标不变。 |
| [TC04-19](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：真实 SQLite 旧快照 N 在全量扫描时被当前同路径 X 替换；实际清理环节重读占用者，保留 X 内容/状态，无恢复搬移。 |
| [TC04-20](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：从syncVersion=1经实际start升级，历史节点详情超时保留旧版本/cursor/修改内容；无状态文件登记needs_review；跨进程启动恢复后版本4、残留原件保全、无状态字节保留且日志提示人工核对，根B配置/状态/内容不变。watcher为替身，本项验证升级链。证据 desktop-test-final.log。 |
| [TC04-21](../20260924-code-review-testcases/CR-04.md) | PASS | sync-size.test.cjs：真实文件流对 delta 与目录列表的 "3"、"0"、null 分别下载并校验 3/0/3 字节；负数、非数字、超安全整数和小数在两入口均拒绝且无文件写入；相邻大 nodeId/cursor 全程字符串。 |
| [TC04-22](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：实际引擎/下载/SQLite，历史 V1 日志与当前 V2 详情/流分别同页、跨页及同/不同大小四组合，均只下载一次 V2，精确提交最终大游标。 |
| [TC04-23](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：详情后流变化的等长/变长 V3、稳定元数据下截断/篡改四组合均不覆盖原件/不提交游标；重启刷新详情后正确下载并提交，根 B 快照不变。 |
| [TC04-24](../20260924-code-review-testcases/CR-04.md) | PASS | 真实SyncEngine、upload-manager、scheduler、SQLite transfer_tasks与relay分片上传：初始化失败和合并一直未返回（上传等待模块受控时钟超过10分钟）均传播失败，临时源字节完整、history error、原基线和cursor不变；恢复后成功。desktop-conflict-watcher-rerun.log；API受控。 |
| [TC04-25](../20260924-code-review-testcases/CR-04.md) | PASS | 真实上传任务链对local_wins/latest_wins分别失败/超时，旧字节和基线保持且cursor不提交；独立进程恢复上传任务成功后才提交。desktop-conflict-watcher-rerun.log。 |
| [TC04-26](../20260924-code-review-testcases/CR-04.md) | PASS | 实际引擎/SQLite/任务服务对仅下载失败、仅上传失败、同时失败连续两轮跨进程重试；原nodeId/MD5/localMtime/cursor保持，第三轮恢复两份版本后提交。desktop-conflict-watcher-rerun.log。 |
| [TC04-27](../20260924-code-review-testcases/CR-04.md) | PASS | 真实引擎全量对账：旧N、旧X、无状态三种身份×三种副本失败，连续两轮跨进程保持原基线/version，恢复后才提交。desktop-dedup-regression.log；复用副本的新断言4项通过conflict-reuse-regression.log。 |
| [TC04-28](../20260924-code-review-testcases/CR-04.md) | PASS | 真实引擎/SQLite跨进程重放：成功冲突后同页失败不反向覆盖；连续三轮部分失败只保存一份已完成副本；副本详情失败保守拒绝，副本被改后重新保全。5项通过desktop-dedup-regression.log，保留原始复现日志。 |
| [TC04-29](../20260924-code-review-testcases/CR-04.md) | PASS | sync-recovery.test.cjs + sync-restart.test.cjs：当前原子移动实现的初始清单写、源移动、complete 写/提交阶段故障及独立进程退出/恢复；DELETE、历史日志、全量清理均先完成 pending 再删状态/提交游标，副本缺失时拒绝确认，根 B 不变。已取消的 copy/hash/unlink/rmdir 映射为原子移动阶段，预期不降低；首次引擎绕过 pending 复现见 sync-restart-repro.log。 |
| [TC04-30](../20260924-code-review-testcases/CR-04.md) | PASS | 原探针 10/10；本轮真实文件恢复 11/11，含最后时刻改写及重叠拒绝。 |
| [TC04-31](../20260924-code-review-testcases/CR-04.md) | PASS | 实际chokidar+SyncEngine+SQLite+上传任务链：MOVE/DELETE自激均不回传，独立编辑仍上传，云端故障期间编辑恢复后补传。4项通过desktop-dedup-regression.log；Windows夹具使用长路径，避免Node原生短路径assert。 |
| [TC04-32](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：DELETE/MOVE/UPDATE/CREATE 各在页面事件完成、cursor 写入前 process.exit；新进程重开 SQLite 重放，不重复下载/改写 mtime/迁移身份/写历史，最终提交游标且根 B 配置/状态/字节不变。 |
| [TC04-33](../20260924-code-review-testcases/CR-04.md) | PASS | sync-reconcile.test.cjs：真实本地哨兵文件及完整状态快照；业务错误、缺 records、null/负数/小数/页数回退、第二页失败均以对应列举错误拒绝，无清理或 cursor 写入；空目录 0 页合法但仍在根内的旧节点不被清理；恢复后第二页补齐新文件并经 syncOnce 将 cursor 从 1 推到 2。修复 null 被 Number 转成 0 的缺陷，证据 sync-reconcile-repro.log。 |
| [TC04-34](../20260924-code-review-testcases/CR-04.md) | PASS | sync-cursor.test.cjs：第一页游标写入真实 SQLite；第二页网络失败后重开数据库与引擎，从第二页游标继续并固化最终游标。 |
| [TC05-01](../20260924-code-review-testcases/CR-05.md) | PASS | 同一生产分享服务实际MySQL8.0.46/InnoDB，5请求源打开后屏障争limit1；唯一完整字节响应，失败0字节，最终count1。share-mysql-complete.xml，继承H2矩阵原DisplayName保留H2但此类数据源实际MySQL。 |
| [TC05-02](../20260924-code-review-testcases/CR-05.md) | PASS | 两个独立JVM共用隔离MySQL，B预签名准备后暂停，A四流源打开后同时释放；URL授予数+完整流数=1，失败流0字节，无URL泄漏，count1。share-mysql-complete.xml。 |
| [TC05-03](../20260924-code-review-testcases/CR-05.md) | PASS | 真实MySQL矩阵：错误提取码与达到验证码阈值后缺验证码，两种URL/流入口均前置拒绝且count0、存储无调用；继承取消/过期/下载关闭/缺download变体通过。share-mysql-complete.xml。 |
| [TC05-04](../20260924-code-review-testcases/CR-05.md) | PASS | 既有四变体不存在/回收站/未完成上传/子树外节点在本轮MySQL数据源重新执行，拒绝不计次且无源对象访问。share-mysql-complete.xml。 |
| [TC05-05](../20260924-code-review-testcases/CR-05.md) | PASS | ShareServiceImplNoTransactionIntegrationTest：源流打开失败、URL 签名失败均抛错，count=0 且无文件响应。 |
| [TC05-06](../20260924-code-review-testcases/CR-05.md) | PASS | ShareServiceImplNoTransactionIntegrationTest：源流打开后另一 URL 先授予；当前流被拒绝并关闭，响应输出流与文件长度头均未设置，count=1。 |
| [TC05-07](../20260924-code-review-testcases/CR-05.md) | PASS | ShareServiceImplNoTransactionIntegrationTest：首字节前及写出部分字节后源流抛 IOException；授予 count=1 不退款、源流关闭、响应未附加 JSON。 |
| [TC05-08](../20260924-code-review-testcases/CR-05.md) | PASS | 实际InnoDB独立连接持行锁，授权UPDATE等待超时后无响应字节/文件长度、源流关闭、count0；释放后新申请成功count1。继承授予后响应丢失再重试仍拒绝场景通过；断连为响应/读取异常注入。share-mysql-complete.xml。 |
| [TC05-09](../20260924-code-review-testcases/CR-05.md) | PASS | ShareServiceImplNoTransactionIntegrationTest：limit=null，连续三轮 URL+流各授予一次；每轮 count 精确增加 2，输出完整。 |
| [TC05-10](../20260924-code-review-testcases/CR-05.md) | PASS | 三个入口缓存回归中分享重复缩略图不计次；有效/不支持/超限/损坏后无原图旁路，原流计次1，PUT故障恢复无缓存残留。thumbnail-entry-complete.xml。 |
| [TC05-11](../20260924-code-review-testcases/CR-05.md) | PASS | 实际MySQL初检后取消/关闭下载/已过期变体；另等待数据库真实DATETIME(0)到期边界后占位拒绝。口令初检后改动不撤回已授权在途请求，随后旧口令新申请拒绝。share-mysql-complete.xml。 |
| [TC05-12](../20260924-code-review-testcases/CR-05.md) | PASS | 真实MySQL+6MiB确定性内容完整流，耗时≥1.1秒对应既有5MiB/s限速，外部源打开与每次read均断言无数据库事务，count只+1。share-mysql-complete.xml。 |
| [TC05-13](../20260924-code-review-testcases/CR-05.md) | PASS | 任务专用RustFS S3实际上传、AWS SDK真实预签名：应用首次URL授予count1，再申请拒绝；有效期内同URL真实HTTP GET两次均200且字节完整，count仍1。share-mysql-complete.xml，临时对象存储与MySQL。 |
| [TC06-01](../20260924-code-review-testcases/CR-06.md) | PASS | 独立JVM A→B→A三页共六项逐ID无重复遗漏，Spring实际读取同一进程环境测试密钥。search-process-final.xml；ES/元数据受控稳定候选。 |
| [TC06-02](../20260924-code-review-testcases/CR-06.md) | PASS | 停止A并重启独立JVM，同密钥消费既有游标，结果逐项与重启前第三页一致；包含另一存活实例B交替请求。search-process-final.xml。 |
| [TC06-03](../20260924-code-review-testcases/CR-06.md) | PASS | TeamSearchServiceTest：ApplicationContextRunner 启动真实搜索 Bean，缺失、空串、纯空白密钥均初始化失败；错误由 bean 初始化期抛出。 |
| [TC06-04](../20260924-code-review-testcases/CR-06.md) | PASS | 真实子进程环境变量注入密钥后正常搜索；原TeamSearchServiceTest覆盖属性注入与不含搜索Bean的模块启动。search-process-final.xml、search-service-final.xml。 |
| [TC06-05](../20260924-code-review-testcases/CR-06.md) | PASS | 独立进程覆盖异密钥、payload单字节修改、签名修改、截断、非法编码、非规范尾部编码、额外段，均在ES调用前拒绝。search-process-final.xml。 |
| [TC06-06](../20260924-code-review-testcases/CR-06.md) | PASS | 保持游标逐项修改tenant/user/space/folder/keyword/size/nodeType/suffixes/sizeMin/sizeMax/dateFrom/dateTo十二维，均拒绝且ES调用0。search-process-final.xml。 |
| [TC06-07](../20260924-code-review-testcases/CR-06.md) | PASS | 两个独立JVM，真实时钟短TTL到期前B接受，到达签名expiresAt两实例拒绝，之后再等1.1秒仍拒绝且不访问ES。search-process-final.xml。 |
| [TC06-08](../20260924-code-review-testcases/CR-06.md) | PASS | 旧密钥实例退出后两个新密钥实例均拒绝旧游标，新查询跨两个实例可继续分页；重启保持密钥的对照通过。search-process-final.xml。 |
| [TC06-09](../20260924-code-review-testcases/CR-06.md) | PASS | 独立进程合法/非法/缺失配置路径采集输出并断言无测试密钥泄漏，响应不含密钥；配置源码默认空、示例仅占位值已核查。search-process-final.xml、search-service-final.xml。仅合成隔离密钥。 |
| [TC07-01](../20260924-code-review-testcases/CR-07.md) | PASS | 普通/历史/分享三入口真实ImageIO，jpg/jpeg/png/gif/bmp全部JPEG可解码且受尺寸限制，正常入口sm/md/lg，历史lg、分享sm；两帧红/蓝GIF输出红首帧。浏览器GIF主图使用专用token原流，未禁动画链。thumbnail-entry-complete.xml、browser-preview-final.log。 |
| [TC07-02](../20260924-code-review-testcases/CR-07.md) | PASS | WebP/SVG缩略图拒绝且不访问S3/解码，普通/历史主图使用授权后当前/历史对象URL；真实浏览器WebP/SVG通过img展示，失败有稳定提示。thumbnail-entry-complete.xml、browser-preview-final.log。 |
| [TC07-03](../20260924-code-review-testcases/CR-07.md) | PASS | 三个实际入口B-1/B/B+1，合法尾部填充PNG：边界内生成有效JPEG，B+1不打开源流、无缓存PUT。thumbnail-entry-complete.xml。 |
| [TC07-04](../20260924-code-review-testcases/CR-07.md) | PASS | 三个入口HEAD低报/缺失及源流途中追加字节，超限停止且读取≤B+8192，源流关闭、无缓存对象，正常请求恢复。thumbnail-entry-complete.xml。 |
| [TC07-05](../20260924-code-review-testcases/CR-07.md) | PASS | 三个入口注入真实ImageIO注册Reader探针：P-1/P解码，P+1/零/负/INT_MAX平方在read前拒绝；dispose、图像流关闭及临时文件清理断言。thumbnail-entry-complete.xml。 |
| [TC07-06](../20260924-code-review-testcases/CR-07.md) | PASS | 三个入口非图片/截断头/截断内容与Reader实际格式wbmp均拒绝，无空JPEG缓存；源流/Reader/图像流及临时文件释放。thumbnail-entry-complete.xml。 |
| [TC07-07](../20260924-code-review-testcases/CR-07.md) | PASS | ThumbnailRendererTest：C=1，闩锁占用第一个许可，第二请求在读取前拒绝，释放后第三请求成功。 |
| [TC07-08](../20260924-code-review-testcases/CR-07.md) | PASS | 三个入口源读取、尺寸解析、read解码、JPEG Writer编码、缓存PUT逐阶段故障；无无效缓存、源流关闭，Reader阶段额外验证dispose/图像流关闭/临时文件/许可恢复，随后正常生成。thumbnail-entry-complete.xml。 |
| [TC07-09](../20260924-code-review-testcases/CR-07.md) | PASS | ThumbnailRendererTest：Spring 启动对 B/P/C 的 0/负数六变体均失败；自定义低上限启动后实际生效。 |
| [TC07-10](../20260924-code-review-testcases/CR-07.md) | PASS | 当前小历史超限只拒绝历史；反向当前超限历史小输出历史蓝色JPEG且不读当前对象，节点fileSize固定1不能绕过历史HEAD。thumbnail-entry-complete.xml。 |
| [TC07-11](../20260924-code-review-testcases/CR-07.md) | PASS | 普通/历史validateAccessible拒绝，以及分享错误提取码/关闭下载/子树外节点均在S3/下载URL/渲染前拒绝；真实H2分享范围，文件访问拒绝为策略注入。thumbnail-entry-complete.xml。 |
| [TC07-12](../20260924-code-review-testcases/CR-07.md) | PASS | 真实H2分享支持/不支持WebP和SVG/超限/损坏缩略图count保持0且不输出原图；随后原流字节完整count=1。thumbnail-entry-complete.xml。 |
| [TC07-13](../20260924-code-review-testcases/CR-07.md) | PASS | 真实浏览器大图/WebP/SVG胶卷404、业务错误、坏图片降级图标，文件名可访问与前后导航保持；请求快照不变，无批量原图/无限重试；主图仅允许一次用户重试。browser-preview-final.log及请求JSON。 |
| [TC07-14](../20260924-code-review-testcases/CR-07.md) | PASS | 浏览器合成脚本/外链SVG仅img展示，DOM无object/iframe/embed，脚本标记为空、外链请求0；主图URL来自带认证请求的预览API，损坏SVG稳定提示并仅一次显式重试。browser-preview-final.log/png。 |
| [TC07-15](../20260924-code-review-testcases/CR-07.md) | PASS | 三个入口有效缓存不读取源流，403/超时拒绝且不发原图URL；清空缓存404后才生成。thumbnail-entry-complete.xml。 |
| [TC07-16](../20260924-code-review-testcases/CR-07.md) | PASS | 普通/历史同时持有同一Renderer的C=2许可，分享立即忙拒绝；释放后分享成功。两个独立服务/Renderer实例各C=1并发同键生成，各许可归零后恢复且最终JPEG完整，允许2次PUT；同JVM独立实例，非全局并发保证。thumbnail-entry-complete.xml。 |
| [TCDB-01](../20260924-code-review-testcases/DB-ID.md) | PASS | migration-probe.ps1：独立临时 MySQL 8.0.46 使用原 44/45 SQL；旧行、异常角色、备注、默认值、大 ID、存量/新增安全版本均通过。 |
| [TCDB-02](../20260924-code-review-testcases/DB-ID.md) | PASS | migration-probe.ps1：独立临时 MySQL 8.0.46 使用原 44/45 SQL；旧行、异常角色、备注、默认值、大 ID、存量/新增安全版本均通过。 |
| [TCDB-03](../20260924-code-review-testcases/DB-ID.md) | PASS | full-migration-probe.ps1：完整 02～43 初始化、迁移前 compare-schema 退出 1、原 44/45、测试版 schema_version 登记、迁移后 compare-schema 退出 0。 |
| [TCDB-04](../20260924-code-review-testcases/DB-ID.md) | PASS | large-id-schema.xml 4项、schema-consistency.xml 3项；core/auth/team实际H2 DDL的BIGINT/NOT NULL/默认值及三组大ID精确存读、安全版本到Long.MAX。MySQL结构门禁仍由execution-20260925原full-migration-probe前后两次compare证据支持，H2与MySQL分开记录。 |
| [TCDB-05](../20260924-code-review-testcases/DB-ID.md) | PASS | 独立 MySQL 8 临时库：原 44 成功、给 45 注入列冲突后失败且未登记版本；移除冲突重试成功，旧数据保留，重复版本登记被唯一键拒绝。证据 db-chaos.log。 |
| [TCDB-06](../20260924-code-review-testcases/DB-ID.md) | PASS | large-id-schema.xml：旧Integer Jackson数字/字符串0/1/2兼容，三组大ID均反序列化拒绝及Math.toIntExact拒绝溢出；历史db-chaos.log实际MySQL缩列失败原大角色值保留。旧类型契约夹具，不宣称运行旧发行包；回退保留BIGINT且不重分配角色。 |
| [TCDB-07](../20260924-code-review-testcases/DB-ID.md) | PASS | id-contract.xml：三组大ID全真实响应DTO字段及邀请请求精确；browser-matrix.log三次轮换空间/角色/成员/邀请/ACL及复制目录请求；browser-version-share-complete.log三组文件/版本/分享目录与节点/路由/query，实际UI与API受控；desktop-id-config.log实际引擎root/node/cursor消费，SQLite三组存读/迁移另见历史sqlite-migration-complete-rerun.log与当前桌面回归。分层证据，非同一HTTP全链路。 |
| [TCDB-08](../20260924-code-review-testcases/DB-ID.md) | PASS | desktop-probes.cjs：裸数字/字符串大 ID、缺失与损坏 JWT 均按预期；实际 api-client.ts。 |
| [TCDB-09](../20260924-code-review-testcases/DB-ID.md) | PASS | SQLite真实整数SQL字面量迁移，6项通过；全部实际ID列/cursor为TEXT且逐字保留，非ID列不变、复合主键与两根隔离保留，导出重开后二次迁移数据及sqlite_master不变。sqlite-migration-complete-rerun.log。 |
| [TCDB-10](../20260924-code-review-testcases/DB-ID.md) | PASS | sync-cursor.test.cjs：相邻大游标、真实 SQLite 持久化重开后继续分页；hasMore 不前进拒绝且不改库。 |
| [TCDB-11](../20260924-code-review-testcases/DB-ID.md) | PASS | jwt-release.xml及backend-xml Redis矩阵：真实Redis+H2 TCP、第二JVM拒绝旧无版本Access/Refresh，重新登录可用、改密后两实例拒绝旧会话；仅验签旧行为模拟仍接受撤销令牌，未运行旧发行包。排空旧行为前不得声称撤权保证。 |
| [TCDB-12](../20260924-code-review-testcases/DB-ID.md) | PASS | backend-xml SearchProcess：真实进程缺共享密钥/无效密钥启动失败；desktop-id-config.log缺失/旧/错误scopeProjectionVersion不推进；browser-matrix.log无效角色、预览降级和一次重试；thumbnail-config.xml缺阈值使用20MiB/1600万像素/并发2安全默认，0/-1初始化失败，定制阈值实际拒绝。完整集合为发布演练，不是部署验收。 |
