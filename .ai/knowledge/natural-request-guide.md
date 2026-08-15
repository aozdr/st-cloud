# 日常需求使用方式

用户无需学习 Agent Loop。

## 可以直接说

```text
把文件分享链接增加过期时间，并补上测试。
```

```text
上传偶尔失败，帮我查一下原因。
```

```text
优化文件夹权限继承，注意性能。
```

```text
帮我全面 review 一下最近的权限改动。
```

```text
这个接口响应太慢了，帮我定位并优化。
```

## Workflow Manager 自动完成

```text
理解需求
→ Goal
→ 规模判断
→ Plan
→ TASK
→ Dispatch
→ 执行
→ 测试
→ Review
→ Rework
→ 完成
```

用户不需要写：

```text
Goal:
TASK:
Agent:
Dispatch:
Scope:
Acceptance:
Validation:
```

## 什么时候会问用户

只有真正存在业务选择时，例如：

```text
“分享链接过期后，历史链接是立即失效，
还是仅禁止新访问？”
```

如果项目已有明确规则，则直接按项目规则执行，不重复询问。

## 用户最终看到

```text
已完成：

- 增加分享链接过期时间
- 补充数据库字段
- 更新接口
- 增加测试

验证：
- mvn test ✓

风险：
- 无
```

而不是内部 TASK/Dispatch/State 的全部细节。


## 用户无需知道 Dispatch

以上 TASK / Dispatch / ACK 都是 Workflow Manager 的内部运行时协议。

用户仍然只需要说：

```text
把文件分享增加过期时间，并补测试。
```

不要要求用户提供 taskRef、stateRef、scope、acceptance 或 validation。

如果内部 Dispatch 失败，Workflow Manager 应自行恢复。


## Dispatch 对用户完全透明

用户只描述业务需求。Workflow Manager 内部自动生成 TASK、Dispatch Message 并把完整 message 传入 child 创建动作。

如果 child 返回 `DISPATCH_INVALID`，这是 Workflow Manager 的内部运行时错误，自动修复，不要求用户重新描述需求。
