# 第三轮技术设计（待用户确认，revision round3-design-v1）

## 已确认输入
用户回复“执行”，确认 requirement.md 与 uispec.md 的范围及 relay 无可靠恢复状态时提示重新开始的行为。此文是技术设计，不代表测试或实现已经通过。

## 1. P0：独立物理路径与持久候选回收

### 为什么调整附件推荐方案
继续向固定 tenant/md5 写入，单纯延迟后检查引用仍存在检查与 DELETE 的并发窗口；跨实例租约失效也不能撤回已发送的 S3 DELETE。当前无可复用的对象 GC，不能仅记录日志。
采用“新写入独立物理路径 + DB 内容去重 + 延迟候选 GC”。只有未命中去重的四类新写入使用 tenant/objects/md5/UUID；现有 tenant/md5 对象继续可读可复用，不迁移历史对象。UUID 路径永不复用，迟到删除不会影响其他上传的物理对象。tenant+MD5 唯一约束保持。

### 持久候选模型
新增 object_upload_candidate：id、tenant_id、md5、storage_path（唯一）、state、write_finished、created_at、updated_at、next_check_at。枚举使用明确代码并配中文注释。
状态：WRITING（登记后、PUT 尚未确认结束）→ READY（PUT 已成功）→ ADOPTED（物理路径被对象认领）或 DISCARDED（失败/去重败者）；GC 认领为 DELETING，完成后 DELETED。
上传前在独立短事务登记 WRITING，失败则不进行 S3。S3 PUT 在事务外执行。只有 PUT 明确成功返回且没有任何未决的同路径重试时，才记录 write_finished=true 和 READY；超时、连接异常不算明确结束。WRITING 在长上传期间以短 DB 操作续活，不能用创建时间误判仍活跃的限速上传；若候选已被 GC 认领，禁止落库，通知 GC 写入已结束并返回失败。
四类 commit 在原有事务内先按候选 ID/路径 CAS READY→ADOPTED 暂占，CAS 失败整体回滚。若 acquireByPath 复用另一路径，在同一事务将暂占候选变 DISCARDED；否则 ADOPTED。节点必须使用 acquireByPath 返回的权威路径。候选与 file_object/file_node/版本/配额一起提交。候选为空表示只复用旧正常对象，不能用来绕过新路径校验。
提交异常后只将仍属于失败请求且未被采用的候选标记 DISCARDED；不能覆盖 ADOPTED。网络断连导致提交结果不明时，以持久状态和引用为准。失败补偿不立即物理删除。

### GC 与并发规则
默认 grace-period 60 分钟，可配置；分批定时扫描，单批上限与重试间隔可配置。
短事务中 CAS READY/DISCARDED 或过期 WRITING→DELETING，与业务认领互斥；业务认领先提交则 GC 不得删，GC 先认领则业务事务必须失败。
物理删除前检查精确 storage_path 的正常 file_object、file_node、file_version 引用，以及同租户/MD5 的活跃上传会话；存在引用、活跃写入或不确定数据则保留重查。路径不同的正常去重胜者不阻止失败候选最终回收。
S3 DELETE 在事务外；网络异常保持 DELETING 重试。多个 GC 重试最多重复删除同一独立路径，该路径再也不能被提交或复用。
过期 WRITING 存在“DELETE 后迟到 PUT”问题：write_finished 未明确时不得删除候选记录或停止扫描，保留 DELETING 墓碑并周期复查/删除；迟到 PUT 完成时只记录结束，不可恢复 READY。无回调的崩溃请求保留小型墓碑，保障迟到对象最终可回收，不假定某次 404 代表未来永不出现。
DELETING→DELETED 只允许由发出本次 DELETE 之前已确认 write_finished=true 且本次 DELETE 成功的 worker 更新。不能先 DELETE、再发现 write_finished=true 就停止扫描，否则 PUT 可能夹在两者之间完成。所有新独立路径必须携带候选认领凭据，不能在其他 acquireByPath 调用点无校验提交；已存在正常对象的引用复用仍须检查原子增引用成功。后台扫描显式使用候选 tenant_id，不能依赖请求线程 UserContext；引用检查包括可恢复回收站节点和版本。路径和状态扫描建立必要索引。

GC 仅管理本轮登记的独立路径；历史未知 canonical 不在线扫描。正常已采用对象的删除沿用既有流程，本轮不扩展成全盘生命周期重构。独立评审发现现有 deletePhysical 先删除后标墓碑、部分引用增加未检查更新结果的相邻风险；本轮仅在直接涉及候选认领的调用点校验，其余另行记录，不能宣称所有对象删除竞态均已解决。

### 兼容与发布
服务端新增表先迁移再部署；旧对象路径不变，公开上传 API 不变；旧实例不认识新候选，在过渡期仍可能运行旧缺陷，因此本轮修复完成的运行前提是全部写入实例升级。回退旧服务版本前停用新写入并保持 GC 独立路径不复用约束。
数据库步骤：新增 docker/mysql/init/ 递增 SQL，首行 SET NAMES utf8mb4;；同步 H2 schema，执行 SchemaConsistencyTest 与 H2 测试，第一次 compare-schema，执行迁移并登记 schema_version，第二次 compare-schema 必须退出 0。未执行前不声称完成。

## 2. Desktop relay 暂停恢复
SQLite 用 PRAGMA table_info 后 ALTER 补 transfer_mode、relay_chunk_size、relay_limit_kb 可空列，保留旧行内容；迁移幂等，create/update/read 三处完整映射。
新 direct 任务明确持久化 direct。历史无模式任务不能直接默认 direct；无可靠证据则显示重新开始。
进程内 relay 上下文按 taskId 保存 uploadId、已确认 seq、块大小和运行中的 Promise。只有 confirmed=true 成功响应才推进 acknowledgedSeq；业务失败响应不能当上传成功。暂停停止发下一个请求，不以展示进度推导 offset。
resume 必须等待前次请求/循环退出后，从 acknowledgedSeq+1 继续 relay-chunk，完成后 relay-finalize；与 direct 分支在调用 status 之前分流。缺失可靠上下文、客户端重启或服务端丢失会话时提示重新开始，不自动 abort/re-init，不进入 direct。
取消、完成清理上下文；保留现有 task 排队和事件机制，防止快速 pause/resume 启动两个上传循环。

## 3. Relay seq 状态
committedSeq 初始 0，inFlightSeq 为空。reserve 只接受 committedSeq+1；seq<=committedSeq 为已提交重复；跳号、非法序号和在处理的同序号明确拒绝，不返回 confirmed=true。
按会话串行协调 reserve、append、commit 与 finalize/cleanup；进程内同步只保护内存 relay 会话，不用来解决对象跨实例正确性。
完整读取、长度校验、append、uploadPart 和分片状态记录全部成功后 commitSeq。flushPart 的 partNumber 仅成功后推进，记录请求内全部已上传 part，避免最后一次 append 返回 0 覆盖之前 flush 结果。
发生 IOException 或 RuntimeException 且可能已有字节写入时，保守进入明确 abort：通过既有 UploadSession CAS 认领中止，在事务外补偿 multipart；清除 in-flight，不允许同 seq 被伪幂等确认。abort 补偿异常保留原错误与日志，不恢复成功状态。
finalize 不得与 in-flight 请求交错；已停止的持久会话不可处理重复请求。

## 4. 分享缩略图
新增 GET /api/share/access/thumbnail/{shareCode}?nodeId=...&size=sm&password=...，沿用 captchaId/captchaCode（需要时）。仅允许 sm 和图片，不返回原图 fallback。
st-share 依赖 st-preview（当前 st-preview 只依赖 core/common，无循环），通过明确的缩略图流能力复用 preview bucket 缓存。每次先在 st-share 验证提取码/验证码/过期/访问权限、下载开关与权限集、下载限额及 nodeId 分享范围，再读取缩略图流。
响应直接输出 image/jpeg，使用 private/no-store，避免绕过后续授权；不暴露长期可用原图 URL。不通过服务端任意 URL 下载预签名结果。
成功后沿用现有 stream 下载计数语义；不在本轮修复已有并发计数窗口。首张未缓存缩略图服务端仍需读取原图生成，客户端只接收缩略图。
PreviewModal 仅 filmstrip 修改请求路径，失败占位，不修改主图。

## Grill Me 与风险裁决
目标、用户、边界、规则、异常、数据/API、兼容风险已分别覆盖上述章节。
待裁决 1 项：是否接受 P0 从固定新写入路径调整为独立物理路径，并新增持久候选表？这是封闭延迟删除并发窗口的设计选择；用户确认后才写迁移和业务代码。
已定选择：relay 不确定状态提示重新开始；缩略图保持现有下载权限和计数规则。
已知代价：并发首次上传可能多写物理对象，败者延迟回收；崩溃 WRITING 可能长期保留小型 GC 墓碑；全实例升级前旧缺陷仍存在。

## 执行与验证安排
保持附件 P0→Desktop→Seq→P2→全量顺序，每阶段复现与定向测试通过后再前进；具体测试用例在本设计确认后编写。
增加 GC 与迟到 PUT/commit 交错验证，Desktop 快速暂停恢复、业务失败响应验证，分享权限与 st-preview 回归。完整门禁包含附件清单及 st-share/st-preview 测试。
开发、测试、独立 Review 使用各自 TASK/Envelope，主线程串行集成验证。最终报告记录真实证据，未完成事项不判 ACCEPT。

