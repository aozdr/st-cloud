# TASK：试点 BE-02 — st-core SizeFormatter 工具类

> 试点用实现任务，验证 V15 worktree 隔离。产物为独立新增工具类与单元测试，不修改任何既有代码。

## 元信息

- Task ID: `TASK-20260817-worktree-isolation-be02`
- 关联任务 State: `.ai/state/20260817-worktree-isolation.yaml`
- 关联文档: `.ai/docs/20260817-worktree-isolation/design.md`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-17

## 目标

在 worktreeRoot 内新增 `com.stcloud.core.support.SizeFormatter`（文件大小人性化格式化：B/KB/MB/GB/TB，1024 进制，1 位小数；核心逻辑使用中文注释）及单元测试 `SizeFormatterTest`。

## 修改范围

- 新增：`st-core/src/main/java/com/stcloud/core/support/SizeFormatter.java`
- 新增：`st-core/src/test/java/com/stcloud/core/support/SizeFormatterTest.java`

## 禁止修改范围

- 其它任何文件（含既有 `com.stcloud.core.text/**`、`com.stcloud.core.util/**` 与其它 st-* 模块）
- 不运行 mvn / npm；不执行 git；不写 `.ai/` 除 changereport 外的内容
- 不修改主工作树 `D:\code\st-cloud` 下任何源码

## 验收标准

- [ ] 两个新增文件存在于 worktreeRoot，编码 UTF-8
- [ ] 格式化逻辑正确（单位换算/小数位/0 值处理），中文注释
- [ ] 单元测试覆盖至少 4 个用例
- [ ] 未修改任何既有文件

## 测试要求

- 本任务不自行运行构建；主线程合并后由 `mvn test` 统一验证

## 输出要求

完成后追加 `.ai/docs/20260817-worktree-isolation/changereport.md` 的「BE-02」章节（修改文件清单 / 与验收标准对照 / 测试结果 / 风险），并返回 State Delta。
