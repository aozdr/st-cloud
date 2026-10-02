# 角色职责（委派时按需读取）

主线程负责直接用户需求、风险分级、TASK、Goal、Plan、Evaluate 和最后验收，不要求每个角色常驻。

| 角色 | 实际委派职责 | 边界 |
|---|---|---|
| executor | 可独立实现、调查或设计的子任务 | scope 内工作，返回真实结果，不写 State |
| reviewer | large 的独立代码、安全和适用风险审查 | 与实现者分离，不替代实现修改业务代码 |
| tester | 确有测试环境/专业隔离需要的验证 | 不因有测试标准就强制创建 |

medium 默认主线程实现并自检，测试、知识同步和最终 Goal 验收由主线程完成。large 独立 reviewer 可合并代码、安全、数据库/API 与 UI 维度。主线程接收结果后核对当前修订，不重复创建 accept Agent。

任务与消息契约见 `.ai/knowledge/agent-dispatch-protocol.md`；child 只读 TASK、最小 State 和被选技能，不扫描角色/知识库全集。输出契约见 `.ai/knowledge/agent-output-standard.md`。技术栈与特定操作说明只在需要时读取。
