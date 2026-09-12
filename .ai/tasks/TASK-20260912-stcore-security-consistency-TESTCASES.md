# TASK-20260912-stcore-security-consistency-TESTCASES

## 任务类型

测试用例设计（大型任务 TESTCASES 门禁）。

## 背景

依据已确认的 `.ai/docs/20260912-stcore-security-consistency/requirement.md`、`design.md`、`impact.md` 和 `architecture-review.md`，为 st-core 文件资源安全与一致性整改编写可执行测试用例。测试用例必须覆盖 Goal 的 8 项完成标准以及 Review 文档中的 P0-01～P0-05、P1-01～P1-08。

## 允许写入

- `.ai/docs/20260912-stcore-security-consistency/testcases.md`
- `.ai/runtime/results/DISPATCH-20260912-TESTCASES-02.json`

## 禁止写入

- 任何 Java、SQL、前端、配置或既有测试源文件。
- `.ai/state/20260912-stcore-security-consistency.yaml`。
- 其他 TASK、结果或共享报告文件。

## 必须覆盖

1. 个人 API 访问团队节点的 detail、rename、move、copy、delete、version、download、recycle 负向测试。
2. personal/team parent-child scope、root、create/copy/move/new blank/upload 场景。
3. path 更新和 meta event 不跨 owner/space，团队移动不能形成环，坏数据遍历有限。
4. upload_session 的用户、tenant、space、节点、S3 标识绑定以及泄露 uploadId/旧客户端字段测试。
5. 乐观锁冲突无副作用、同级有效节点唯一、版本号并发唯一。
6. 大目录批处理、FolderSizeVO 完整性、ZIP preflight、Archive 安全限制、分片上传参数边界。
7. MySQL/H2 schema 一致性和迁移验证步骤，不能把未执行的运行中 MySQL 迁移写成已完成。

## 验收标准

- `testcases.md` 按 P0/P1/数据库/Goal completion criteria 分组，给出编号、前置数据、动作、期望结果和证据定位。
- 每一项 Goal completionCriteria 至少有一条正向或负向用例，并标明建议测试类/接口。
- 对兼容性、存量冲突不自动删除、MySQL 与 H2 差异、外部 S3 流程给出明确验证备注。
- 只产生允许写入的两个文件，并返回独立结果 JSON；不修改代码和 Loop State。

## 验证

- 阅读 design.md、requirement.md 和相关 Controller/Service/Mapper/Entity/DDL/Test 文件。
- 使用 `rg` 核对测试类、接口及表名确实存在；对无法直接执行的用例标注“实现后执行”。
- 检查每项 Goal completionCriteria 均映射到至少一个测试编号。

## 结果契约

独立结果文件必须包含 `dispatchId=DISPATCH-20260912-TESTCASES-02`、`taskId`、`status`、`artifactRefs`、`criterionProposal`、`blockerProposals`，其中 `criterionProposal.id=TESTCASES`，并提供真实文件证据。
