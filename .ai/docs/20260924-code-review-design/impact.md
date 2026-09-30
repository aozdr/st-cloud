# 影响分析

修订：design-v1。整体按大型跨模块整改规划，但本轮实际写入仅限设计与流程文档。

## 模块与契约矩阵

| CR | 后端范围 | 前端/客户端 | 数据 | 主要兼容影响 |
|---|---|---|---|---|
| 01 | common/auth/admin，sync 现有 WS 认证 | Web/桌面重新登录路径回归 | sys_user.security_version | Access/Refresh 新声明；旧会话失效 |
| 02 | team 角色引用、权限解析，相关 common 接口契约核对 | TeamSpacePage/TeamInvitePage/角色与 ACL 对话框/types | team_member.role、team_invite.role 扩容 | role 响应 number→string |
| 03 | team 复制权限，core 复制副作用核对 | 无新交互，既有权限错误反馈 | 无 | 越权复制从成功变为拒绝 |
| 04 | sync delta/DTO，core 事件字段核对 | 桌面引擎/下载/对账/状态 | 云端无计划 schema；根状态保留、恢复清单 | delta 可选能力与对账字段，客户端协同升级 |
| 05 | share 下载/URL/计数 | PreviewModal 计数语义注释 | 无 | 资格授予后失败也计次 |
| 06 | search 服务初始化和部署配置 | 原搜索游标错误处理复用 | 无 | 未配置不能启动，旧随机游标不迁移 |
| 07 | preview/share/core 最小共享渲染器 | PreviewModal，图片能力判断 | 无 | 部分缩略图返回可降级错误，原图授权不变 |

## 跨任务关系

- CR-01 和 CR-04 都涉及 st-sync，但前者只触及 WS 鉴权，后者触及 delta 与桌面同步；不能把认证错误解释为云端文件已删除。
- CR-02 与 CR-03 共享团队授权链；CR-03 可先用现有权限点修复，CR-02 完成后再次确认自定义角色覆盖。
- CR-05 与 CR-07 共享 ShareServiceImpl、PreviewModal。合并时保留“主图流计次，胶卷缩略图不计次”，不能因原图 fallback 绕过计数。
- CR-02 与 CR-06 共享团队搜索消费者但不共享密钥逻辑；角色字符串不会进入游标签名的新协议字段，搜索最终 fresh 鉴权保持。
- CR-01/02 的迁移分文件登记，不能只改 Java 或 init 中历史建表文件。

## 后续实现顺序（仅计划）

CR-03 → CR-05 → CR-01 → CR-02 → CR-04 → CR-06 → CR-07。每项未来需按规模建立编码 TASK 与测试用例，通过相关验证再进入下一项；共享构建缓存不得并行争用。本轮不执行该序列。

## 验证影响（未执行）

后端未来覆盖 common/auth/admin/team/core/share/sync/search/preview 对应模块；H2 SchemaConsistencyTest 与 MySQL 迁移前后对比不可互相替代。Web 需类型检查和构建；桌面 npm test 显式列出测试文件，新同步验证必须接入脚本，后续再运行 lint/build。这里仅定义受影响验证范围，不包含步骤化测试用例或测试结果。

## 数据安全及运维

权限版本从主库查询，不能因读副本滞后重新放权。role 扩 BIGINT 后有自定义成员即不能回退旧 Integer 服务或缩列。同步恢复清单必须保留文件内容和原路径，禁止为重建状态全局 resetSyncData。图片临时文件有硬大小和并发上限。部署前应确认共享搜索秘密实际进入应用进程。
