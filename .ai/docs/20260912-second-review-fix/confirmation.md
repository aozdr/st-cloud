# 本轮需求与设计定版证据

用户在 2026-09-12 当前任务明确要求：“PLEASE IMPLEMENT THIS PLAN”，并再次给出 Phase 1→7 的完整方案，说明“本轮计划无需再次确认”。该指令确认本轮 `requirement.md`、`uispec.md`、`architecture-review.md` 和 `design.md` 的既定范围与顺序；不是对未来公开 API、数据库 schema/migration 或新设定的预先授权。

执行中如果发现这些范围变化，主线程须先提交具体变更供用户裁决；现有 Phase 1→7 范围内不重复请求计划确认。

用户后续明确授权：PC 安装包构建可以跳过，由用户自行打包；追加修复客户端上传和下载均报 21 values for 20 columns 的任务创建错误。此授权不扩展到无关 Team 基线测试修复。

用户随后明确要求修正 Team 基线测试：授权将 TeamServicePermissionIntegrationTest 的请求 subjectId 改为 String，保留权限断言，不改生产代码、API 或数据库。
