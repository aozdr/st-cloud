# 文档产出与留存

必需产物只由 `.ai/loop/exit-criteria.yaml` 的当前定义决定。模板及 docs/newList/ 是编写参考，不能额外增加门禁、强制章节或暂停。

## 默认产物

small 直接路径不落盘；medium 的 design.md 包含需求/范围/影响/方案/风险/验收/测试计划，verification.md 包含修改/自检/命令结果/适用 UI 与安全检查/知识同步/剩余风险。large 增加 requirement.md 与独立 codereview.md；架构评估合入 design，安全评审合入 codereview。

复杂 UI、独立架构决策、用户指定交付格式才单独产出 uispec/ADR/其他文档。无 UI 不创建体验文件，不为知识无变化派发任务。内容写一次，多处引用；现有 testcases/changereport/impact 等文件仍可作为证据，不要求重写。

## 存放与编码

任务文档在 `.ai/docs/<task-id>/`；TASK 在 `.ai/tasks/`；稳定知识在 `.ai/knowledge/`；ADR 在 `.ai/decisions/ADR/`。UTF-8 无 BOM；含中文的 PowerShell 5.1 脚本使用 UTF-8 BOM。历史产物保留原位，不批量删除或重写。

一份文件可以支持多个验证维度，但必须分别记录内容、结果、实际证据及修订；不能把一段“通过”重复登记为多个检查。

## 确认与交付

仅实质范围/兼容/风险未决决策需要确认；设置对应 confirmationRequired=true，并呈现可审阅文档。已有明确授权可复用。其他文档无需暂停或逐段复述。

结果与验证路径在最终报告给出；必要背景简述，不堆积八段空表。V2 State 仍按绑定定义要求原文件，V3 产物合并不追溯改变其门禁。
