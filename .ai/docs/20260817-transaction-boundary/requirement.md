# 需求文档：事务边界治理——将 S3/外部网络调用移出数据库事务

# 一、需求概述

## 1.1 功能名称

事务边界治理：S3/外部网络调用移出数据库事务 + 事务标注与超时治理

## 1.2 背景说明

2026-08-17 代码核查（证据见各条文件行号）发现事务边界问题：

1. **核心写路径在 `@Transactional` 内执行 S3/外部网络 I/O**：连接池连接被长事务占用，网络延迟直接决定事务时长；S3 成功但 DB 回滚会产生孤儿对象。
   - `ArchiveServiceImpl.extractArchive`（:94）：ZIP 下载 + 逐文件上传全在事务内，异步线程执行
   - `EditorCallbackServiceImpl.handleCallback`（:74）：OnlyOffice 回调 = 外部 URL 下载到临时文件 + S3 上传，全在事务内
   - `UploadServiceImpl.simpleUpload`（:131）：限速 S3 上传在事务内
   - `UploadServiceImpl.mergeChunks`（:377）：S3 completeMultipart/abort/delete 在事务内
   - `SyncBlockServiceImpl.blockCheck`（:69）：只读方法开事务，且事务内调 S3 initMultipartUpload
   - `SyncBlockServiceImpl.blockUpload`（:142）：事务内循环 UploadPartCopy（每块一次网络调用）
   - `RecycleBinServiceImpl.permanentDelete/emptyRecycleBin/purgeNode/permanentDeleteAdmin`（:134/182/209/220）：事务循环内删 S3 物理对象
2. **只读方法误开事务**：`ShareServiceImpl.getDownloadUrl`（:362，纯读+预签名 URL）；`UploadServiceImpl.checkInstantUpload`（:86，非秒传路径只读）
3. **无事务超时兜底**：全项目未配置事务超时（仅有 HTTP/RocketMQ 超时），S3 挂起时连接可能无限占用

## 1.3 用户角色

| 用户角色 | 影响 |
|---------|------|
| 终端用户 | 上传/解压/保存回调期间 DB 连接占用降低，连接池耗尽风险下降 |
| 开发人员 | 获得"事务内禁止网络 I/O"的明确规范，新增代码不再踩同类问题 |

# 二、业务需求分析

## 2.1 用户故事

- 作为平台，我希望上传/解压/OnlyOffice 保存等长耗时网络操作不占用数据库事务，从而避免并发高峰连接池耗尽。
- 作为开发，我希望事务边界规则显式可查，新增写路径默认"S3 先做、DB 后落"。

## 2.2 功能列表

| 编号 | 功能 | 描述 | 优先级 |
|------|------|------|--------|
| F1 | 标注修正（低风险） | `getDownloadUrl` 去事务；`blockCheck` 去事务 + S3 init 移出；`checkInstantUpload` 拆"只读检查 + 命中才写" | P0 |
| F2 | 上传路径 S3 移出事务 | `simpleUpload`、`mergeChunks` 先完成 S3 操作再开事务落 DB 元数据 | P0 |
| F3 | 同步块路径 S3 移出事务 | `blockUpload` 的 UploadPartCopy 循环移出事务，最后 DB 落版本块布局 | P1 |
| F4 | 删除路径 S3 移出事务 | 回收站永久删除系列：DB 先记录/删除，S3 物理删除异步补偿 | P1 |
| F5 | 解压/回调路径改造 | `extractArchive`、`handleCallback`、`overwriteContent` 先完成 S3/外部下载再事务落库 | P1 |
| F6 | 事务超时与只读标注 | 长事务配置 timeout；只读方法标记 `readOnly=true` | P0 |
| F7 | 规范固化 | AGENTS.md / 设计规范写入"核心写路径禁止事务内网络 I/O" | P0 |

## 2.3 业务流程

```
现状：DB 事务开启 → S3/外部网络调用（耗时） → DB 写入 → 提交（连接被网络占用）
目标：S3/外部网络调用（无事务） → 成功 → DB 事务落元数据 → 提交
删除类目标：DB 事务落"待删除"标记/引用归零 → 提交 → 异步删除 S3 → 失败进补偿队列
```

# 三、功能边界

## 包含范围

- 上表 F1–F7 七个修复项
- 新增/调整对应单元与集成测试

## 不包含范围

- 不改变业务规则（配额、引用计数、去重、权限语义不变）
- 不引入新的消息队列/存储组件（补偿复用现有 outbox/定时任务机制）
- 不动 move/copy/restore 等纯 DB 事务（已合理）

# 四、业务规则

- S3/外部网络调用一律在事务外执行；DB 写一律在事务内
- 删除类：DB 事务内只做引用归零与记录状态；S3 物理删除在提交后执行，失败进补偿队列重试，不阻塞用户操作
- 只读查询方法禁止 `@Transactional`（确需一致性快照用 `readOnly=true`）
- 长事务必须显式配置 `timeout`（默认 30s）
- 半成品 S3 对象使用统一前缀（如 `tmp/`），失败清理 + 定时清理兜底

# 五、异常场景

| 场景 | 处理方式 |
|------|---------|
| S3 上传成功、DB 写入失败 | 尽力删除临时对象；DB 记录待清理标记，定时任务兜底 |
| S3 上传失败 | 不上传即不写 DB，直接返回失败，无孤儿对象 |
| S3 物理删除失败（删除类） | DB 已提交，删除进补偿队列重试 |
| 外部 URL 下载失败（OnlyOffice 回调） | 事务未开启，直接返回失败；临时文件清理 |
| 事务超时 | 按配置抛出超时异常，回滚本事务，连接释放 |

# 六、非功能需求

## 性能

- 长耗时网络操作不再占用 DB 连接；同并发下连接占用时间显著下降（目标：解压/回调路径占用时长趋近 DB 写耗时）

## 安全

- 权限校验在事务外照常执行（先校验后操作），不因改造弱化

## 可维护性

- 每个修复项独立可回滚；补偿逻辑复用现有 outbox/定时任务，不新造轮子

# 七、验收标准

- Given：模拟 S3 超时（上传/合并阶段）；When：调用上传接口；Then：DB 无残留记录、连接在 S3 超时后立即释放、接口返回失败
- Given：模拟 DB 写入失败（配额冲突）；When：上传已成功落 S3；Then：临时对象被尽力删除或进入清理标记
- Given：回收站永久删除；When：S3 删除失败；Then：DB 引用已归零、删除进补偿队列、重试成功后对象消失
- Given：`getDownloadUrl`；When：调用；Then：不再开启事务（可通过事务拦截日志/测试断言验证）
- 构建：`mvn test` 全绿 + `npm run build` 通过（如涉及前端无改动则仅 mvn test）

# 八、遗留问题点（Grill Me 拷打收敛）

| 编号 | 遗留问题 | 影响 | 建议方案 | 用户裁决 |
|------|---------|------|---------|---------|
| P1 | 删除类 S3 物理删除的失败补偿策略：先 DB 后删 S3（失败留孤儿，靠补偿队列）还是先删 S3 后 DB（失败则引用不归零） | 决定删除路径事务边界与补偿复杂度 | DB 事务内引用归零并记录待删状态，提交后异步删 S3，失败进补偿队列重试 | 待确认 |
| P2 | 解压/OnlyOffice 回调改造后，半成品对象的清理责任 | 失败残留占用存储 | 统一 `tmp/` 前缀 + 失败尽力删除 + 定时清理任务兜底 | 待确认 |
| P3 | 分批执行范围：F1/F2/F6/F7（低中风险）先行，F3/F4/F5（涉及失败语义）是否同批 | 影响迭代规模与回归面 | 第一迭代做 F1/F2/F6/F7，第二迭代做 F3/F4/F5；均为后端改动，可各按 worktree 并行批次 | 待确认 |

# 九、风险分析

| 风险 | 影响 | 解决方案 |
|------|------|---------|
| 改造核心上传/删除路径引入回归 | 上传/删除不可用 | 每项独立修复 + 现有集成测试（上传、回收站、同步已有测试）回归 |
| 孤儿对象/引用泄漏 | 存储与配额漂移 | 补偿队列 + 定时清理 + 对照 `compare-schema`/配额一致性测试 |
| 删除顺序调整影响一致性 | 物理对象被引用或泄漏 | 引用归零与物理删除解耦，删除前校验 ref_count=0 |

# 附：V15 流程验证说明

本需求后端改造可拆为并行 worktree 批次（模块零重叠）：

- BE-01：`st-core` UploadServiceImpl + TextFileServiceImpl + ShareServiceImpl（F1/F2 中 st-core 部分）
- BE-02：`st-sync` SyncBlockServiceImpl（F3）+ `st-share` getDownloadUrl（F1 中 st-share 部分）
- BE-03：`st-core` RecycleBinServiceImpl / ArchiveServiceImpl / EditorCallbackServiceImpl（F4/F5）

每批次含单元测试，`mvn test` 由主线程合并后串行验证。也可与 move/copy 需求（2BE+1FE）分属不同迭代，避免一次改动面过大。
