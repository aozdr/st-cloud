# 第二轮 Review 修复测试用例

本用例集以 `11ef6c2836af548c52052e5860511242741d9d1d` 为基线，严格按 Phase 1→7 执行。每阶段的新增测试及相关既有测试通过后才进入下一阶段。

| Phase | 编号 | 输入与操作 | 预期 |
|---|---|---|---|
| 1 | HASH-01 | 0B、1KB、5MB、11MB、100MB+ 同一字节序列由 Web、Desktop 和 Node crypto 计算 | 三方完整 MD5 相同 |
| 1 | HASH-02 | 两文件同大小、前 2MB 相同、后续不同，分别上传 | MD5 不同；第二文件不得因采样碰撞秒传为第一文件 |
| 1 | HASH-03 | Desktop 同步记录/比较历史采样值 | 新计算使用完整 MD5，不将采样值写作内容指纹 |
| 2 | SCOPE-01 | 两个人用户在同租户根目录创建同名节点 | 均成功 |
| 2 | SCOPE-02 | 两个团队空间在同租户根目录创建同名节点 | 均成功 |
| 2 | SCOPE-03 | 同 owner 或同 space 的同父目录创建同名节点 | 按既有规则冲突或自动命名；数据库唯一约束兜底 |
| 2 | SCOPE-04 | rename、move、copy、resolveNameConflict、check/init/simple、restore、extract、team 调用链 | 所有同名判断仅查当前 scope |
| 3 | INIT-TX-01 | 新建上传第 N 批 chunk 写入失败 | node、session、chunk 均回滚 |
| 3 | INIT-TX-02 | 替换上传 chunk 写入失败 | 原 node 与 version 不变；无新增 session/chunk |
| 3 | INIT-TX-03 | S3 init 成功而 DB commit 失败 | 事务外调用 abortMultipart；abort 失败有日志 |
| 4 | STATE-01 | 20 并发 merge 同一 uploadId | completeMultipart 恰一次；最终 COMPLETED |
| 4 | STATE-02 | merge CAS 成功后在 S3 complete 处暂停，同时 abort | abort 返回 CONFLICT；merge 完成 |
| 4 | STATE-03 | 20 并发 abort 同一 uploadId | 仅一个执行 cleanup；最终 ABORTED |
| 4 | STATE-04 | 可续传的新上传合并失败后重试 | MERGING→FAILED→MERGING→COMPLETED |
| 5 | RECYCLE-01 | 已回收父目录含 100MB 已回收子文件，执行 emptyRecycleBin | 配额仅退还 100MB 一次 |
| 5 | RECYCLE-02 | 同节点重复永久删除，以及父子都出现在旧查询结果 | quota、refCount、对象事件均只执行一次 |
| 6 | ARCHIVE-01 | 输入上限 1MB、流输入 2MB | 抛 FILE_TOO_LARGE；不进入 summarizeArchive |
| 6 | ARCHIVE-02 | 复制超限或读取异常 | 临时 ZIP 删除，异常可见 |
| 7 | GATE-01 | 运行计划第 9 节全部命令 | 记录命令、通过数、失败数及 schema drift 结果；git diff --check 通过 |

本轮不自动清理历史采样 MD5 或冲突数据，不通过删除测试规避失败。
