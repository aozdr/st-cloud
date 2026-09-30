# 未通过测试用例解决方案

日期：2026-09-27。依据 results.md 当前记录：136 条中 27 PASS、0 FAIL、55 PARTIAL、54 NOT RUN，共 109 条尚未通过。本方案不改变已有用例状态，也不把代码检查或构建成功等同于场景验收。

## 目标与边界

目标：逐条补齐原用例的全部断言，复现并修复实际缺陷，最终使 109 条未通过项均有当前代码版本的执行证据。主线程独立执行，沿用已有需求、设计和 TASK-20260925-TEST-REMAINING。

范围：CR-01～CR-07、数据库与字符串 ID、测试环境和必要的最小业务修复。保留现有工作区改动；不修改生产数据，不部署生产，不为降低测试难度改变验收预期。

当前 0 FAIL 仅表示已登记的失败已修复，不证明未覆盖路径没有缺陷。此前多次只补模块替身测试，导致 PARTIAL 累积；后续按完整调用链组织夹具，每组同时解决相关的多个编号。

## 环境核实与共用夹具

已只读核实 Docker 29.2.1 可用；本地有 mysql:8.0、redis:7-alpine、elasticsearch:8.12.0、rustfs/rustfs:latest 镜像。沙箱直接访问 Docker 管道被拒，受授权的工具提权后读取成功，不是 Docker 服务不可用。镜像存在尚不代表应用测试环境已启动或就绪。

1. 创建专用测试编排和生命周期脚本。唯一 project/container/network/volume 名称，服务端口只绑定 127.0.0.1，独立数据库、桶、索引、Redis 命名空间和测试密钥；不复用默认开发 compose 的数据卷。
2. 分批启动依赖，健康检查通过后再运行用例：MySQL+Redis 支撑鉴权/团队；S3 兼容存储支撑文件/分享/预览；ES 支撑搜索。双实例使用两个独立应用进程与共享测试依赖。
3. 使用固定数据构造器：两租户、两团队、多个权限用户、相邻超安全整数 ID、两同步根、旧版本数据库。重置仅针对本次测试创建的资源。
4. 实现可控屏障和故障点：事务提交前后、刷新 CAS 前后、目录页读取、流首字节/中途、上传任务状态、恢复清单写入、游标持久化。用屏障或时钟控制，避免用不确定 sleep 猜并发顺序。
5. 桌面集成夹具加载实际 SyncEngine、上传/下载/对账模块、真实 SQLite 和临时文件树；重启用独立进程退出后重新打开同一测试目录。保存文件哈希、SQLite 快照、恢复清单、请求序列及游标。
6. 浏览器先验证浏览器运行能力，再连接独立测试前后端。契约错误可注入，但最终字符串 ID、角色保存和预览链路必须至少有真实 API 往返证据。
7. 每组输出机器可读用例结果、日志、必要快照及清理记录。应用日志不得输出测试令牌或密钥。基础设施启动失败单列环境错误，不伪记业务 FAIL 或 PASS。

## 分组方案与通过条件

| 顺序 | 分组与未通过数 | 具体解决措施 | 通过条件 |
|---|---|---|---|
| 1 | CR-04 同步：29 | 补服务端 delta 范围投影、多租户与双根；用真实引擎+SQLite连接冲突、MOVE/DELETE、全量对账；在副本成功/清单提交/游标落库处断进程再重放；实际 watcher 验证自激与用户修改；补 oldPath/恢复目录越界矩阵。 | 失败页不确认；恢复后可继续；根外和根 B 哨兵不变；有效内容不丢失、不误绑节点；完整记录重复副本次数，幂等不达标则修复。 |
| 2 | CR-01 撤权：22 | 管理服务实际事务+签名 Access/Refresh+HTTP 过滤器；MySQL 屏障执行两种持锁顺序与回滚；真实 Redis 并发 CAS；双应用进程验证撤权、Redis 失败和主库失败；真实 WS 握手与收发检查。 | 提交后旧会话拒绝，回滚后字段与版本一致；刷新仅一胜；旧权限不能配新版本；WS 失权/过期关闭且注销；上下文与日志无泄漏。 |
| 3 | CR-02 角色：14 | JSON/query 参数化请求，角色/成员/邀请实际存读；双实例权限更新与搜索输出前校验；空间锁屏障覆盖引用新增与删除；外部成员到期和关闭协作；浏览器完整邀请/改角色/失败重试/重名角色矩阵。 | 非法 ID 无写副作用；相邻大 ID 精确区分；失效角色所有入口拒绝；无悬空新引用；回滚不泄漏权限；UI 回显和请求 ID 始终为字符串。 |
| 4 | CR-03 复制：7 | 将真实团队权限服务、复制入口、文件服务和 Mapper 串联；源 view/目标 upload、自定义角色、目录 ACL、批量顺序与 null/0 根全矩阵；记录节点、对象引用、用量及事件快照；浏览器打开后撤权再提交。 | 拒绝时所有写副作用为零；合法复制内容/引用/用量/事件一致；重试与撤权不绕过权限。 |
| 5 | CR-05 分享：9 | H2 已有断言迁移到真实 MySQL，双实例争最后一次额度；补错误提取码/验证码、DB 锁超时/断连、口令变更和到期边界；真实 S3 流/签名 URL/缩略图；检查事务边界与限速。 | 最后额度只有一个授予；未授予不计次、无输出；授予后传输失败不退款；打开流被拒时关闭；S3 直连 URL 复用符合原授予语义。 |
| 6 | CR-06 搜索：8 | 两应用进程+真实 ES 交替分页、重启、密钥轮换；实际环境变量启动；补所有上下文绑定、畸形编码和到期边界；采集响应与日志检查密钥泄漏。 | 分页无重漏；同密钥跨实例可续；异密钥/过期/错上下文拒绝；缺密钥启动失败；日志响应无密钥。 |
| 7 | CR-07 预览：14 | 真实 S3 对象+预览/分享入口，历史版本与双实例缓存；注入 HEAD 404/403/超时、源对象增长和 PUT 失败；补 GIF 首帧、坏图及各阶段资源释放；浏览器导航、失败回退、WebP/SVG 原图。 | 不越权读历史或分享对象；大小/像素/并发限额始终生效；资源与许可释放；缓存错误不吞权限错误；前端回退不串图、不循环。 |
| 8 | DB-ID/兼容：6 | 补所有 H2/MySQL 大 ID 存读及 SQLite 旧列/索引迁移；三组大 ID 贯穿 API/路由/状态/请求/桌面；隔离旧/新客户端或契约夹具演练；新旧鉴权进程混跑与排空；配置能力缺失启动矩阵。 | ID 不经 Number/parseInt 或数值排序；旧库迁移保持数据/索引/根隔离；有大角色时禁止缩列和旧 Integer 链路；排空旧鉴权实例前不宣称撤权达标。 |

顺序按数据安全和授权风险安排，主线程串行集成验证。第 4 组依赖第 3 组权限夹具；第 7 组复用第 5 组对象存储与分享夹具；第 8 组最后汇总跨模块兼容，但大 ID 断言从每组开始就执行。

## 已有修复与重点待证实问题

- 已修复并有定向证据：缺失对象 HEAD 长度、团队复制非法源/超配额、同步路径穿越与联接、目录页数 null、原件移动后 pending 清单无法重试。补完整链路时仍需覆盖其原始触发方式。
- TC04-24～29：当前模块测试不能证明真实任务重试和游标推进正确；优先验证连续两轮失败后恢复，以及同页后续事件失败引起的成功冲突重放。若发现重复副本，采用持久化事件/副本关联复用已完成阶段；具体数据结构在复现后写入设计，再做最小实现。
- TC04-29：现实现采用原子移动，已无 copy/hash/unlink/rmdir 删除链。对应故障点应映射到清单准备、原子移动、清单完成和游标提交；原“内容保全、pending 可续、失败不确认”预期保持。必须追加进程重启验证。
- TC01-12～18、TC02-13/20、TC05-01/02：替身和 H2 不能证明 MySQL 锁/CAS/提交语义；必须用真实数据库及屏障，不从代码阅读推断通过。
- 未发现复现失败的路径先补证据，不预先扩大生产代码修改范围；发现失败后保存失败输出，再修复和运行对应回归。

## 执行与验收规则

每条用例按“准备数据 → 执行全部变体 → 比较业务结果与副作用 → 注入失败并恢复 → 保存证据 → 判定”完成。缺任一原始断言保持 PARTIAL；完全没运行保持 NOT RUN；明确违背预期为 FAIL。

通过判定须包含：用例编号、代码 revision、命令、环境标识、输入变体、关键断言、输出日志/快照、实际结果和未覆盖项。环境就绪后以完整分组收敛，避免不断追加孤立测试而不补原编号缺口。

总退出条件：136 条全部通过且不存在未解释跳过；受影响模块测试、桌面 npm test/lint/build、涉及前端的构建与浏览器回归通过；数据库变化遵循前后两次 compare-schema 与唯一 schema_version 登记；当前 State 校验通过，最终 Review/安全检查与 ACCEPT 完成。无法满足时保留具体失败或阻塞证据，不把 51 个桌面测试或 534 个 Java 测试的数量当作 136 条验收用例全通过。

实际执行命令、环境依赖和缺口写入 results.md；本方案仅做现状核对和解决路径定版，不代表 109 条已执行。不存在需要用户再次确认的常规实施决策。

## 全部未通过项索引

下表自动摘录制定方案时的 results.md，保留原编号、状态与缺口，防止分组遗漏。

| 编号 | 当前状态 | 已有证据与待补缺口 |
|---|---|---|
| [TC01-01](../20260924-code-review-testcases/CR-01.md) | PARTIAL | AuthServiceIntegrationTest.login_disabledUser_rejected：仅覆盖禁用登录，未覆盖提交后旧令牌撤权。 |
| [TC01-02](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-03](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-04](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-05](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-06](../20260924-code-review-testcases/CR-01.md) | PARTIAL | UserSecurityServiceIntegrationTest：H2 真 Mapper 对错租户、不存在/禁用/逻辑删除用户及禁用/逻辑删除租户均拒绝；未通过真实 HTTP 过滤器串联全部变体。 |
| [TC01-07](../20260924-code-review-testcases/CR-01.md) | PARTIAL | UserSecurityServiceIntegrationTest：缺失、负值、BigInteger 溢出、小数、数字字符串 securityVersion 均拒绝，合法版本通过；尚未对 Access/Refresh 两种签名令牌完整串联。 |
| [TC01-08](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-09](../20260924-code-review-testcases/CR-01.md) | PARTIAL | JwtAuthenticationFilterPurposeTest：Refresh/未知 type 普通 HTTP 拒绝；download/editor 仅绑定节点流端点通过；Redis 为替身，WS 和期限未覆盖。 |
| [TC01-10](../20260924-code-review-testcases/CR-01.md) | PARTIAL | JwtAuthenticationFilterPurposeTest：下游抛异常后清理 UserContext/TenantContext/SecurityContext，下一匿名请求不继承；未联动真实 Access 签名和过期。 |
| [TC01-11](../20260924-code-review-testcases/CR-01.md) | PARTIAL | AuthServiceIntegrationTest.refreshToken_rotatesWhenStored：仅单请求轮换，未覆盖 Redis CAS 并发。 |
| [TC01-12](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-13](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-14](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-15](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-16](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-17](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-18](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-19](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-20](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-21](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-22](../20260924-code-review-testcases/CR-01.md) | PARTIAL | AuthServiceIntegrationTest.register_createsUserAssignsDefaultRoleAndIssuesValidToken：仅成功注册。 |
| [TC02-01](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-02](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-03](../20260924-code-review-testcases/CR-02.md) | PARTIAL | TeamServicePermissionIntegrationTest：成员在两个相邻大 ID 角色间切换后目录 ACL 结果反转；前端保存后回显尚未浏览器验证。 |
| [TC02-04](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-05](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-07](../20260924-code-review-testcases/CR-02.md) | PARTIAL | TeamServicePermissionIntegrationTest.customRolePermissionsApplied：验证自定义 view+upload，未覆盖 view-only 拒绝。 |
| [TC02-10](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-13](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-15](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-17](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-18](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-19](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-20](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-21](../20260924-code-review-testcases/CR-02.md) | PARTIAL | TeamServicePermissionIntegrationTest.disabledCustomRoleDeniesPermissions：未遍历各授权入口。 |
| [TC03-01](../20260924-code-review-testcases/CR-03.md) | PARTIAL | TeamControllerCopyPermissionTest：目标 upload 拒绝时源 view 已查、复制服务及活动日志完全未调用；未用真实 DB 比对节点/引用/用量。 |
| [TC03-02](../20260924-code-review-testcases/CR-03.md) | PARTIAL | TeamControllerCopyPermissionTest：源仅要求 view，目标 upload 通过后才调用复制；实际节点、引用/容量未由此测试验证。 |
| [TC03-03](../20260924-code-review-testcases/CR-03.md) | PARTIAL | TeamControllerCopyPermissionTest：目标根 0 明确要求 upload，拒绝时无复制/活动调用；null 根与真实授权未覆盖。 |
| [TC03-04](../20260924-code-review-testcases/CR-03.md) | PARTIAL | TeamControllerCopyPermissionTest：两种 [F1,F2] 顺序，只要 F2 view 拒绝，整个批次不调用复制或活动；FileServiceFlowIntegrationTest 另证后续源未完成时前置拒绝且无节点写入。真实事件/用量未全覆盖。 |
| [TC03-07](../20260924-code-review-testcases/CR-03.md) | PARTIAL | FileServiceFlowIntegrationTest：H2 真实 Mapper 批量复制双目录与嵌套子树、同名目标生成 (1)、三个新节点各发布索引事件；单文件对象引用、容量与同步消费未在此场景串联。 |
| [TC03-08](../20260924-code-review-testcases/CR-03.md) | PARTIAL | TeamControllerCopyPermissionTest：提交时目标 upload 被拒绝，服务端入口不执行复制；UI 打开后撤权与重复提交未浏览器联动。 |
| [TC03-09](../20260924-code-review-testcases/CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-01](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-02](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-03](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-04](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-05](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-06](../20260924-code-review-testcases/CR-04.md) | PARTIAL | sync-cursor.test.cjs：第一页 changes=[] 且 hasMore=true，仍请求第二页并消费事件、推进最终游标；服务端范围过滤源链未覆盖。 |
| [TC04-07](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-09](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-10](../20260924-code-review-testcases/CR-04.md) | PARTIAL | sync-path.test.cjs：真实临时根及根外哨兵测试先复现 .. 路径被接受；修复后拒绝越界、含 ..、Windows 盘符、根内指向根外的目录联接，合法路径可用且哨兵字节不变。恶意 delta 经实际 syncOnce 后游标不推进；仍缺 oldPath、恢复入口全矩阵。证据 sync-path-repro.log。 |
| [TC04-11](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-12](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-13](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-14](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-15](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-16](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-17](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-18](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-19](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-20](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-22](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-23](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-24](../20260924-code-review-testcases/CR-04.md) | PARTIAL | sync-conflict.test.cjs：真实上传模块对 failed/running 超时均抛错且旧 nodeId/MD5 不被改写；真实临时文件 keep_both 两类失败均保留上传源、原文件，冲突 history 为 error。未联动引擎游标及真实任务服务。 |
| [TC04-25](../20260924-code-review-testcases/CR-04.md) | PARTIAL | sync-conflict.test.cjs：local_wins/latest_wins 本地较新时上传失败均向上传播，原字节不变；模拟恢复后两策略重试同一源成功。未联动任务服务和引擎 cursor。 |
| [TC04-26](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-27](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-28](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-29](../20260924-code-review-testcases/CR-04.md) | PARTIAL | sync-recovery.test.cjs：初始 manifest 写入失败保留源并可重试；原子移动后最终清单提交失败先复现 ENOENT，修复后同事件核验副本、重试转 complete；目录移动期间新增文件补记且字节完整。当前架构已取消 copy/hash/unlink/rmdir 阶段，尚缺进程级重启及引擎 cursor 联动。证据 sync-recovery-retry-repro.log。 |
| [TC04-31](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-32](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC05-01](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：H2 真 Mapper、五线程在源流屏障并发，只有一份文件字节、count=1；待真实 MySQL 验证。 |
| [TC05-02](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：一个 URL 与四个流在 H2 单实例同时争最后一次额度，仅一个成功；待双实例 MySQL 验证。 |
| [TC05-03](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：取消/过期/关闭下载/缺 download 权限四变体前置拒绝，count=0、源对象未访问；错误提取码/验证码未覆盖。 |
| [TC05-04](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：不存在、回收站、未上传完及分享子树外节点前置拒绝，count=0、源对象未访问。 |
| [TC05-08](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：在真实 H2 Mapper 前注入授权 UPDATE 异常，流已打开但无输出/不计次且关闭；模拟成功授权后响应丢失，客户端重试仍被额度拒绝。真实 DB 超时和网络断连未覆盖。 |
| [TC05-10](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：不支持的缩略图请求拒绝且 count=0，随后原文件流字节完整且 count=1；有效缩略图与存储失败未覆盖。 |
| [TC05-11](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：初检后源流打开期间分别取消分享、关闭下载、设为已过期；最终 H2 条件 UPDATE 均拒绝，count=0、无输出。尚缺时钟边界和口令在途语义。 |
| [TC05-12](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplSecurityIntegrationTest.streamShareFileRateLimitedTo5MBps：未验证事务边界及全部文件场景。 |
| [TC05-13](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：limit=1 时首个预签名 URL 授予成功，应用层重试失败且 count=1；对象存储直接复用旧 URL 未演练。 |
| [TC06-01](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest：两个独立 service 对象共享密钥交替消费三页游标，无重复遗漏；共用 mock ES，未运行双应用实例。 |
| [TC06-02](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest：新 service 对象用同密钥消费旧对象游标；未演练应用进程重启与滚动发布。 |
| [TC06-04](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest：Spring 上下文分别用属性名与环境变量同名属性注入测试密钥，不含搜索 Bean 的上下文可启动；尚未用真实进程环境变量演练。 |
| [TC06-05](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest：不同密钥对象拒绝旧游标，篡改签名测试通过；截断/非法编码全变体未覆盖。 |
| [TC06-06](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest：更换租户、用户、查询词、页大小均拒绝且不查 ES；目录、空间及所有筛选条件未逐项覆盖。 |
| [TC06-07](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest：重签过期游标被拒并返回过期码；到期边界、跨实例时钟未覆盖。 |
| [TC06-08](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest：旧游标在新密钥对象被拒；新密钥重新搜索与全实例轮换未覆盖。 |
| [TC06-09](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest 已运行合法、异密钥/篡改游标及缺密钥路径；源码检查密钥配置默认空、日志模板不含 cursorSecret，docker/.env.example 仅含占位值；未采集独立应用实例的响应与日志。 |
| [TC07-01](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：真实 jpg/jpeg/png/gif/bmp 各三种尺寸均输出可解码且尺寸受限的 JPEG；GIF 多帧首帧与完整入口未覆盖。 |
| [TC07-02](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：WebP/SVG 渲染入口预先拒绝；普通主图浏览器展示未验证。 |
| [TC07-03](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：HEAD 对 B-1/B/B+1 边界检查通过；预览服务缓存写入链未覆盖。 |
| [TC07-04](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：HEAD 低报及缺失长度时实际源流超限均受硬上限阻断；修复缺失长度触发 NPE 的缺陷；对象增长与缓存 PUT 链未覆盖。 |
| [TC07-05](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：真实 PNG 在像素限额 P-1 拒绝、P 通过，伪造 65536² 头在解码前受 long 乘积拦截；无 reader.read 探针和非正尺寸变体。 |
| [TC07-06](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：伪装 jpg 的非图片被拒；损坏/截断头和资源释放全变体未覆盖。 |
| [TC07-08](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：读取 IOException 后流关闭、许可释放，下一请求成功；其他故障阶段未覆盖。 |
| [TC07-10](../20260924-code-review-testcases/CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-11](../20260924-code-review-testcases/CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-12](../20260924-code-review-testcases/CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-13](../20260924-code-review-testcases/CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-14](../20260924-code-review-testcases/CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-15](../20260924-code-review-testcases/CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-16](../20260924-code-review-testcases/CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TCDB-04](../20260924-code-review-testcases/DB-ID.md) | PARTIAL | SchemaConsistencyTest 3 项、auth/team H2 测试及 MySQL 列对比通过；未覆盖所有大 ID 存读子场景。 |
| [TCDB-06](../20260924-code-review-testcases/DB-ID.md) | PARTIAL | 独立 MySQL 8：大角色 9007199254740995 精确存储，回退 TINYINT 因越界失败且原值保留；旧 Integer 服务/客户端未演练。证据 db-chaos.log。 |
| [TCDB-07](../20260924-code-review-testcases/DB-ID.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TCDB-09](../20260924-code-review-testcases/DB-ID.md) | PARTIAL | db-migrate.test.ts：两个根的超安全 INTEGER 字面量 root/node/cursor 迁移为逐字 TEXT，user_id 保留，二次迁移幂等；历史其他列和索引检查未全覆盖。 |
| [TCDB-11](../20260924-code-review-testcases/DB-ID.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TCDB-12](../20260924-code-review-testcases/DB-ID.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
