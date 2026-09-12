# TASK-20260912-stcore-security-consistency-P1-04-06

## 任务类型

P1-04～P1-06 大目录、统计与 ZIP 资源边界。

## 前置条件

- P0 与 P1-01～03 已集成并通过验证。
- 已确认 `design.md` 中的批处理、FolderSizeVO 完整性和 ZIP preflight 方案。

## 允许写入

- `st-core/src/main/java/com/stcloud/core/service/impl/FileServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/DownloadServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/service/DownloadService.java`
- `st-core/src/main/java/com/stcloud/core/dto/FolderSizeVO.java`
- `st-core/src/main/java/com/stcloud/core/controller/FileController.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/FileServiceFlowIntegrationTest.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/DownloadServiceImplTest.java`
- `.ai/runtime/results/DISPATCH-20260912-P1-04-06-01.json`

## 禁止写入

- 数据库 schema/迁移、`st-team`、前端、Loop State、共享报告。
- 不以无限递归或单次超大 IN 查询替代批处理。

## 实现要求

1. 子树收集与文件夹树使用批量查询/分页、visited、最大节点和深度上限；不得产生递归 N+1 或无限循环。
2. FolderSizeVO 明确 `complete`、`calculatedAt` 或等价完整性标记；达到上限不能静默返回“完整”结果。
3. ZIP 下载先完成权限、节点状态、条目和总大小预检，再写响应；预检失败不得输出半个 ZIP。保留 500 MiB 总量上限并处理 IO 失败。
4. 补充大树、异常 parent、空目录、多个根节点、ZIP 预检超限和下载中断测试。

## 验收标准

- 大目录操作具有明确批次/上限，资源消耗可控。
- FolderSizeVO 能区分完整与受限结果。
- ZIP 超限/无权/IO 失败在响应输出前明确失败。

## 验证

- `mvn -pl st-core -am -DskipTests compile`
- 运行文件流、目录统计和 ZIP 相关测试。
- `git diff --check`、`git diff --name-only`。

## 结果契约

结果写入 `.ai/runtime/results/DISPATCH-20260912-P1-04-06-01.json`，包含 `criterionProposal.id=IMPLEMENTED`，不得修改 Loop State。
