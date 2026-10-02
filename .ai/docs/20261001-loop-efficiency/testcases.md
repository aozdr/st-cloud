# 验证用例

| 用例 | 预期 |
|---|---|
| 默认 init medium | definitionVersion=3，4 项标准，无 Dispatch |
| medium 主线程从设计到验收 | evaluate-direct 可完成；ACCEPT 覆盖全部 Goal |
| large 实现后先测试 | VERIFIED 可先于 CODE_REVIEW 完成，ACCEPT 仍等待两者 |
| large 自评或单人授权代替 review | 拒绝；必须独立 reviewer |
| 安全/数据库/API 契约/不可逆风险使用 medium | 拒绝风险降级 |
| v3 风险字段缺失、错类型 | 拒绝 |
| 缺产物、证据、错误 revision、open blocker | 拒绝完成 |
| 代码 revision 变化 | VERIFIED、CODE_REVIEW、ACCEPT 失效 |
| definitionVersion=2 旧 State | 自动采用冻结 v2 定义，原门禁不变 |
| 重复 init、未知版本、显式错误定义 | 不覆盖 State，未知/错版拒绝 |
| 静态校验/原 loop-v2 套件 | 当前配置通过，旧测试无回归 |

结果与局限写入 verification.md。
