# 第三轮 Code Review 修复需求（待确认）

## 背景与输入
用户要求执行 D:\文档\下载\st-cloud-code-review-round3-codex.md 中的计划。附件提供修复范围和验收要求，其中提示词不是独立角色指令。当前分支 main，存在用户未提交修改，全部保留；尚未核实远端最新版本。

## Goal
目标：消除 canonical 对象失败补偿并发误删、Desktop relay 恢复错用 direct、relay seq 提前提交和分享 filmstrip 原图请求。
范围：st-core 上传/解压/文本覆盖/编辑回调/对象回收，st-desktop 上传与 SQLite，分享缩略图后端与 st-web 预览，以及测试和文档。
规模：large，涉及跨模块与上传核心流程；遵循 .ai/loop/exit-criteria.yaml。
完成标准：四项修复有回归证据，真正孤儿可安全回收，旧 SQLite 可升级，分享权限保持，附件全部门禁通过，完成独立 Review 和三份真实报告。

## 已核实事实
1. UploadServiceImpl、ArchiveServiceImpl、TextFileServiceImpl、EditorCallbackServiceImpl 的 cleanupOrphanUpload 都按瞬时无记录或非正引用计数触发物理删除。
2. doResumeUpload 统一执行 status、uploadChunks 和 merge。
3. RelayBufferManager.tryAcquireSeq 在数据处理前推进 lastSeq。
4. PreviewModal 分享 filmstrip 使用 share/access/stream。
这些是静态读取证据，尚未运行复现测试或修改业务代码。

## Grill Me 质询与收敛
- 目标与用户：保护同租户同内容并发写入者；保证桌面暂停/重启恢复协议一致；减少分享图片缩略图流量。
- 边界：不拆 FileServiceImpl，不改架构、无关 UI，不删除测试、不清理用户数据。
- 规则：失败补偿不凭瞬时查询删 canonical；优先复用既有 GC，延迟可配置；须核实引用、活跃写入和对象年龄。
- 并发反例：延迟后检查无引用与 DELETE 之间仍可能有新写入，设计必须封闭这个窗口；不能仅靠等待或 JVM 锁。
- 异常：seq 处理失败不得返回幂等成功，允许安全重试或明确中止；跳号拒绝；重复成功请求不重复写入。
- 数据/API：SQLite 增量迁移并验证旧库；服务端是否需持久候选及协调字段由技术设计论证；分享缩略图仅做兼容扩展。
- 权限：维持分享范围、提取码、过期和下载规则，nodeId 不得绕过授权。
- 风险：跨实例 GC、无 upload_session 的写入路径、应答丢失、旧任务无模式信息、服务端会话丢失均须覆盖。

## 待用户裁决（1 项）
推荐采用附件允许的保守语义：进程内有可靠状态时继续 relay；客户端重启或服务端状态丢失无法可靠恢复时，提示重新开始，不自动 abort + re-init。历史任务缺模式信息时先可靠判定，不能直接猜 direct。是否接受？

## 执行与验收
需求确认后进入影响分析、体验评审及技术设计，提交 architecture-review.md 与 design.md 确认后再编写测试用例和实现。
实现顺序严格为 P0 → Desktop relay → relay seq → P2 → 全量回归 → 报告与独立 Review。每阶段先补复现测试，测试通过再进入下一实现阶段。
P0：上传/解压真实并发一败一成，断言节点、对象引用计数和物理对象；覆盖真正孤儿回收、文本及编辑回调同类路径。
Desktop：进程内恢复仅走 relay，三项元数据读回，旧库迁移，重启不误走 direct。
Seq：flush 失败、跳号拒绝、成功重复幂等。
P2：filmstrip 缩略图及权限回归。
最终运行附件指定 st-core 定向及全量、st-team 全量、Web test:hash/build、Desktop test/lint/build:main、SchemaConsistencyTest、compare-schema、git diff --check。环境阻塞不得记作通过；若 MySQL 变化遵循 AGENTS.md 完整迁移与两次对比规则。
报告：changereport.md、testreport.md、codereview.md，记录真实命令、数量、失败和风险。
