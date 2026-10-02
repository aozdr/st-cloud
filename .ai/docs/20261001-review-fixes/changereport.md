# 本轮修复集成记录

当前修订 review-fixes-code-r4，设计 review-fixes-design-r1。主线程集成初始认证/回收修复后，关闭独立复核的启动快照、部分写失败、换账号交错及较旧共享修订遮蔽当前明确拒绝的问题；详见 r2/r3/r4 的 corrections.md。IMPLEMENTED 为 workflow-manager，child身份及旧失败证据保留。工作区原有其他修改未改动、未暂存、未提交。

## 本轮行为

浏览器认证之前只有每页内存快照且不监听共享记录变化；A 页完成 R0→R1 后 B 页仍可发 R0，服务端拒绝导致共享记录被删除。现在事件和上下文读取重读完整 pair；Web Locks 排队，普通 HTTP 用按页 bakery ticket 与租约协调；排队后重读并复用其他页成功结果。旧登录的迟到结果、低修订和不确定锁所有者的拒绝不能覆盖或清空当前会话。Electron 仍走现有主进程 IPC。细节及边界见 auth-changes.md，独立结果 DISPATCH-review-fixes-auth-01.json。

TeamStorageMapper.lockRecycleSpace 之前 AND deleted=0，使保留期已过的回收节点在所属空间软删除后无法进入系统清理。现在锁定该租户空间墓碑；用户入口仍按有效空间/成员授权，系统仍仅清理 RECYCLED，保持空间→节点锁序。RecycleBinServiceImpl 仅补注释。新增回归验证软删除空间目录混合上传者清理、最后对象引用释放及一次删除事件、重复幂等、用户权限拒绝、其他空间隔离、正常节点保留。细节见 core-changes.md。

当前六个产品/测试文件见 source-manifest-r4.json，旧 manifest 保留。自动拒绝按旧 server/session/revision 及更早版本失效，不删除完整共享新 pair；显式退出语义不变。无 HTTP/IPC/数据模型契约变更，无迁移、部署、现存云端文件删除和 Git index 改动。正常文件在删除空间后的生命周期不属于本轮变更。

## 真实验证

- 当前认证/启动主线程 Node 回归 60/60（多页27、启动8、desktop25）；旧 child 计数仅表示旧修订。独立 VM、共享存储与真实本机 HTTP/CAS，锁和事件调度使用测试模拟。
- 主线程当前后端 Maven 离线构建/测试退出0，H2 26/26：团队回收21、SchemaConsistency3、租户扫描1、清理任务租户隔离1。外部存储与事件受控，没有实际 S3 删除。
- 主线程两端 TypeScript 检查退出0；独立 tester 将核对冻结修订并重跑。
- TEST_TEAM_RECYCLE_MYSQL_URL/USER/PASSWORD 本轮为空；不将旧 MySQL 报告计入当前证据。无 DDL 变更。

## 风险与限制

协调存储不可用、页挂起或租约失效时采用保守拒绝，可能临时返回续期错误；保留最新会话而不推断其已失效。持久化失败时仅保证本页内存 pair，重载依赖最后成功写入。未执行真实浏览器 UI、MySQL 或 S3 验证，不声明上线效果。独立代码/安全复核和测试/验收结果将逐项记录在当前 State。
