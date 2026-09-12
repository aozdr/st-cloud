# 影响范围分析

## 1. 现状事实

基线分支为 `main`，HEAD 为 `11ef6c2`。当前已存在 `upload_session` 表、UploadSession 实体和上传状态相关集成测试；本轮目标是修正第二轮 Review 指出的缺口，不重做上一轮能力。

## 2. 模块影响

| 类型 | 模块 | 影响 | 预计变更 |
|---|---|---|---|
| Web | `st-web/src/hooks/useUpload.tsx` | 大文件仍使用前 2MB+size | 完整流式 MD5；保留上传协议 |
| Desktop | `st-desktop/src/utils/md5.ts`, `src/upload-manager.ts` | 上传入口使用 sampled MD5 | `fileMd5` 改用完整文件 MD5；sample helper 不再用于上传指纹 |
| Backend | `st-core` upload/file/recycle/archive | scope、事务、CAS、幂等和输入流边界缺口 | 最小修改服务、Mapper、配置与测试 |
| Team | `st-team` 调用链 | 团队上传/创建必须使用 space scope | 只修必要调用或共享 service 行为，不改公开路由 |
| Database | `file_node`, `upload_session`, `file_chunk`, `file_version` | 依赖现有字段和状态 | 目标是不新增 migration；若发现必须变更，先暂停确认 |
| S3 | multipart init/complete/abort | 外部副作用与 DB 边界 | 维持事务外调用，失败补偿可观测 |
| UI | Web/Desktop UI | 不在范围 | 不修改视觉与组件 |

## 3. 关键调用链覆盖

- 同名：createFolder、rename/move、copy、resolveNameConflict、check/init/simple upload、restore、archive extract、team create/upload。
- Upload Init：新建与替换两条路径的 node、version snapshot、session、chunks。
- 状态：direct/relay merge、abort、失败重试、过期处理、超时清理。
- 回收站：用户清空、单节点永久删除、定时过期清理、管理员清理。

## 4. 数据与 API 影响

- `fileMd5` 语义收紧为完整 MD5，字段名称和 HTTP 请求结构不变。
- 同名查询为内部 Mapper/service 行为变化，公开 API 不变。
- UploadSession 仍使用已有状态字段；增加 CAS 更新接口为内部 Mapper 能力，公开 API 不变。
- Archive 增加可配置属性 `stcloud.archive.max-archive-input-size`；默认值按修复计划采用 1GB，部署可覆盖。
- 目标无 schema 变更、无存量数据清理；现有历史采样值不自动迁移，测试和发布说明必须明确其兼容风险。

## 5. 验证影响

- 新增/调整 Web、Desktop hash contract 测试。
- 扩展 st-core scope、Upload Init 事务、Session 并发、RecycleBin、Archive 测试。
- 按计划执行 `mvn -pl st-core -am test`、`st-team` 编译/测试、Web build、Desktop typecheck/build、SchemaConsistencyTest、schema compare、`git diff --check`。

## 6. 排除范围核对

不修改 FileServiceImpl 大规模结构、不进行 UI 改版、不迁移 SHA-256、不清理存量冲突、不删除测试、不修改无关模块。
