# TASK：团队全文搜索后端及显式主体授权

- taskId：TASK-20260919-team-search-watch-search
- 模型：GPT-5.6-Luna，max；不得更换模型。
- State：.ai/state/20260919-team-search-watch.yaml；design revision：tsw-design-r1。
- 输入：.ai/docs/20260919-team-search-watch/{requirement,uispec,impact,architecture-review,design,testcases}.md（均已存在）。

## 目标
实现 design.md §2、§3 的团队全文搜索和共用授权接口及后端测试。严格固定接口，个人搜索兼容，所有敏感输出必须在当前权限检查之后产生。

## 写入范围
- st-search/**。
- st-common/src/main/java/com/stcloud/common/access/TeamFileAccessPolicy.java（新建）。
- st-team/src/main/java/com/stcloud/team/service/TeamFileAccessPolicyImpl.java（新建；包路径可在 service 下细分，但向主线程报告）。
- st-team/src/main/java/com/stcloud/team/service/FolderPermissionService.java（仅新增 fresh 权限解析及必要小范围复用）。
- st-team/src/test/** 中本任务独立命名的 TeamFileAccessPolicyTest/FolderPermissionFreshTest。
- [planned-output] .ai/docs/20260919-team-search-watch/search-changereport.md。
- .ai/runtime/results/DISPATCH-20260919-TSW-SEARCH-A1.json。

禁止：关注/通知实现、SQL/H2 schema、前端、State、TASK、其他任务产物；禁止无需求重构、Git提交/重置、用户数据删除、派生子 Agent。不得修改现有 TeamServiceImpl，以避免与其他工作争用；授权实现可复用 public 静态角色解析、mapper 与 fresh FolderPermission 方法。

## 关键契约
- TeamFileAccessPolicy 的 isActiveMember(tenantId,userId,spaceId)、canView(tenantId,userId,spaceId,nodeId) 与 design 一致。不得伪造 UserContext；PRIVATE 模式也显式校验 tenant。
- /api/search/team 与游标、MD5/元数据复核、授权后 lookahead、预算2000、最大页50等按文档。
- 密钥通过项目已有安全签名配置/服务或独立配置注入，禁止硬编码；测试密钥可固定。
- 需要新增资源/配置时仅在 st-search 内配置，若必须改其他模块先向主线程申请范围扩展。

## 验证与结果
编写有意义的测试覆盖 S01～S05 与权限边界。编码期不执行 Maven 或共享缓存构建；主线程统一串行验证。可做只读检查/git diff --check。每约 60 秒向主线程回报进度。
结果包含真实改动与未执行测试说明、风险、偏离、依赖和中文 proposal；by=/root/luna_search_0919，criterion=IMPLEMENTED，validatedRevision=tsw-code-r1。完成后不宣称整体完成，不改 State。


