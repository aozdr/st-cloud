# 第三轮技术方案独立评审

背景：依据 TASK-20260912-round3-design-review 评审候选方案，只读源码，不实现、不构建、不判定整体完成。

输入：已确认需求的 requirement.md、uispec.md、TASK 候选方案及当前工作区源码。State 顶层 revision.design/code 均为 null；本评审针对候选方案版本 round3-candidate-20260912，不代表用户已确认设计。

## 分析：事实与证据

- 四个写入点均使用 tenantId/md5 路径：UploadServiceImpl.java:183、ArchiveServiceImpl.java:170、TextFileServiceImpl.java:89、EditorCallbackServiceImpl.java:190。失败补偿依靠瞬时对象查询决定 DELETE，不能保护查询后的并发提交。
- FileObjectServiceImpl.acquireByPath 返回同租户 MD5 竞争胜者；UploadCommitManager.java:350 附近解压节点使用胜者 storagePath。新增候选不能改变这项权威路径规则，所有写入点均需核对。
- RelayBufferManager.java:131 提前修改 lastSeq；UploadServiceImpl.java:605 认领后读流、追加、分片落库，仅 IOException 进入外层 abort。业务异常和存储 RuntimeException 也可能发生在部分写入之后。
- st-desktop/src/upload-manager.ts:408 起恢复统一调用 status、uploadChunks、merge，确实没有模式分流。
- ShareServiceImpl.java:510 起 stream 校验提取码/验证码、分享开关、权限集、次数、节点有效性、可访问性和分享根边界；成功输出后才原子增加 download_count。
- PreviewServiceImpl.java:133 的 getThumbnailUrl 对非图片直接返回原文件 URL，不能直接当严格分享缩略图接口。st-preview 仅依赖 st-core/st-common；st-share 当前依赖 core/common/team，因此新增 share→preview 依赖不会形成上述模块环。

## 决策建议：对象候选

UUID 独立物理路径、持久候选和数据库 CAS 是可实施方案。仅增加等待时间或查询引用没有封闭并发窗口：GC 查无引用，上传提交引用，GC DELETE 即误删；JVM 锁不能覆盖多实例。独立路径是附件目标内必要技术调整，但新增表属于须确认的数据库设计，不能称为零架构影响。

PUT 前以独立已提交短事务登记 WRITING；UUID 路径永不复用。上传结束后在节点/版本/对象引用事务中先认领候选，CAS 失败必须回滚。采用自己的路径时 WRITING→ADOPTED；复用竞争胜者时 WRITING→DISCARDED。异常标记失败也允许留在 WRITING 由 GC 兜底。不能先提交节点再补标 ADOPTED。元数据行及所有节点必须使用 acquireByPath 返回的权威路径。

GC 仅对到龄 WRITING/DISCARDED 以条件更新认领 DELETING，事务提交后检查对象、节点、版本和会话引用再在事务外 DELETE。引用查询是防御检查，安全性的依据是同一候选行 CAS 排斥所有未来引用。扫描需索引、分页、重试时间和跨实例条件更新；ADOPTED 不属于候选孤儿回收范围，不能仅因 ref_count 为零就走该协议。旧 canonical 和未知历史对象保持只读/复用，不进入新候选扫描。

必须补全长 PUT 反例：PUT 开始→超过 60 分钟→GC 认领并 DELETE（对象尚不存在）→PUT 完成→提交 CAS 失败。若 GC 已删除候选记录，将永久泄漏。建议保留 DELETING 墓碑并持续定期重试 DELETE，直到有可证明的写入终止边界才允许清除。单次 HEAD 不存在、两次 DELETE 或有限宽限都不能证明无未来迟到 PUT。没有存储端请求寿命硬上限时，永久墓碑与周期重试是保守可证明方案；需要明确表增长、扫描费用及退避。上传心跳可降低长上传被回收概率，但不能替代 CAS 和墓碑。

并发验证应覆盖：同 MD5 一败一成、竞争败者成功返回但其独立对象可回收、PUT 后进程崩溃、GC 赢/提交赢两个 CAS 顺序、DELETE 失败重试、DELETE 先于迟到 PUT、事务回滚恢复候选状态、旧 canonical 复用。

## 决策建议：relay 与分享

relay 使用 committedSeq 和 inFlightSeq，首块从 1 开始；仅 committedSeq+1 可认领，已提交重复返回成功，处理中重复不能成功，跳号拒绝。读流结束、长度校验、append/flush 和分片状态记录全部成功才提交 seq。部分写入后所有异常统一明确 abort，不能仅清空 inFlight 后重试追加。finalize、abort、超时清理与 inFlight 同步，避免 finalize 关闭正在写入的临时文件。多次 flush 不能仅记录最后一个分片，需记录每次成功 flush 的分片结果。

Desktop 持久 uploadMode、relayChunkSize、effectiveRateBytes，进程内另存已确认 seq 与字节偏移；暂停在完整应答边界结束，恢复不得从 S3 分片号推导 relay seq。应答丢失时同 seq 重试；重启丢失可靠内存状态、历史模式不明或服务端会话不存在时提示重新开始。旧 SQLite 列可空并幂等迁移，不把未知默认当 direct。

分享建议 st-share 最小增加 st-preview 依赖，preview 新增仅图片、仅 sm 的缩略图流/资源能力；分享服务先完成与 stream 相同的授权，再调用此能力输出，成功后沿用计数规则。不可把预签名原图 URL 用作失败回退，也不通过普通登录态 PreviewController 绕过分享授权。现有 getThumbnailUrl 行为保持兼容。缩略图响应体不可沿用原图 Content-Length。计数成功后增长的现有并发行为保持，不在本轮改成配额预占；新接口应有成功计数、失败不计数测试。

## 风险与范围

现有 FileObjectServiceImpl.deletePhysical 在删除后标失效，未对新增引用做 CAS；这是读取发现的相邻风险，新候选协议不会自动修复它。主线程需明确是否纳入或作为残余风险记录，不得据此宣称所有物理删除路径已安全。数据库变更须执行增量 SQL、H2 schema、SchemaConsistencyTest、MySQL 两次对比和版本登记。部署应完成迁移再启用新写入；旧实例仍写 canonical 的混跑行为不受新协议保护，发布时应停止旧写入实例。

State Delta：仅建议在设计纳入上述约束并经用户确认后评估 TECH_DESIGN；当前不提出通过。

下一步：主线程补充 design.md 的墓碑终止规则、分享依赖与计数语义，完成用户设计确认后才进入测试用例与实现。

变更影响：仅新增本评审及独立结果文件；未改业务代码、State 或既有改动。验证仅为 PowerShell 7 静态源码读取与路径检索，未运行测试或构建。

## design-v1 定向复评

输入更新：读取主线程 design.md，revision round3-design-v1。该版已纳入独立 UUID、READY 阶段、持久 write_finished、权限及成功计数、严格缩略图流、relay 完整提交和运行中请求协调。技术路径可行；可行性不等于用户确认或测试通过。

必须明确 GC 停止扫描的时序：只有 worker 在发出本次 DELETE 之前已读取 write_finished=true，且此后 DELETE 明确成功，才可条件更新 DELETING→DELETED。反例：worker A 在 finished=false 时删除，随后 PUT 完成并更新 finished=true，A 再读到 true 就标 DELETED，此时对象已经复活并漏收。旧 worker 根据旧快照不得停止扫描；write_finished 只能单调 false→true，状态不能离开 DELETING 回到 READY。多个 worker 延迟 DELETE 是安全的，因为此路径永不复用且永不再次允许引用；晚到 worker 即使重复 DELETE 也不会触及其他 UUID。

write_finished 必须表示所有针对该路径的写入均确定结束：成功 PUT 返回且没有在途异步/SDK 重试。超时、连接中断、客户端取消不能自动视为存储端 PUT 已终止；这些情况继续保留墓碑。若没有上述保证，保留 DELETING 周期扫描，不进入 DELETED。

事务实现建议把“CAS 认领 READY”写成明确的 READY→ADOPTED 条件更新，随后在同一事务内 acquireByPath；竞争复用别的物理路径时再 ADOPTED→DISCARDED。节点/版本/配额与这两步同事务提交；事务回滚恢复 READY。不得有一个单独提交的 ADOPTED 中间态，否则崩溃会留下无法 GC 的候选。GC 只从未被采用状态认领，因此业务事务与 GC 对同一行锁/CAS 排斥足够；引用查询并非原子性的替代品。

历史 canonical 范围明确且合理：只读/去重复用、不扫描未知路径、不替换原路径，不声称修复既有已采用对象的删除协议。候选为空仅允许复用经服务端查得的正常对象，不能接受任意客户端路径；如复用竞争中对象已失效，必须失败或重新走新候选流程，不能生成未经登记的新路径。

结论：补上 write_finished 的确定性定义和 DELETE 前快照条件后，design-v1 技术可行；原评审中“无可靠终止边界则永久墓碑”的要求已由该条件保留。没有必要扩大为历史对象迁移或全生命周期重构。State Delta 仍仅 proposal，用户设计确认由主线程处理。
