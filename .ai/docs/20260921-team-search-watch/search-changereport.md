# 团队搜索测试修复变更报告

## 背景

本次 dispatch 在 `tsw-code-r1` 已有团队搜索实现、个人搜索回归测试和独立真实 ES fixture 的基础上，收敛 `st-search` testCompile 阻塞。既有服务接口保持不变，团队搜索的权限、游标和元数据安全约束继续由现有实现与测试覆盖。

## 实际改动

- `TeamSearchServiceTest.nonMemberIsRejectedBeforeEs` 声明 Mockito Elasticsearch 调用可能抛出的 `IOException`，消除测试编译阻塞。
- `SearchServiceImplTest` 的多片段高亮和文件名高亮用例改用已有 `searchRecords(...)` helper，从 `SearchResultPage.records` 读取记录，保持个人 `SearchService` 接口与 `SearchResultPage` 契约不变。
- 保留并复核既有团队搜索边界：最终 DB/MD5 元数据复核、授权 lookahead、目录路径初筛及父链复核、600 秒 HMAC 游标默认值；保留 `updateMeta` 不写入正文 `fileMd5` 的回归断言。

## 验证证据

- `.ai/docs/20260921-team-search-watch/compile-initial.log` 记录修复前唯一 3 个 `st-search` testCompile 错误，分别对应上述三处；本 dispatch 已逐项修复。
- `rg` 静态检查：未再发现 `List<SearchResultVO> = searchService.searchContent(...)` 或遗漏的 `IOException`；团队测试中所有 ES 调用已在允许抛 checked exception 的测试上下文内。
- `git diff --check`：通过，无 whitespace 错误。

## 未执行与风险

- 按 TASK 约束，本 dispatch 未运行 Maven、共享缓存构建、数据库迁移或 schema 对比；主线程需串行执行 testCompile、相关单测和集成验证。
- 本 dispatch 未启动真实 Elasticsearch；独立 fixture 仍需在具备本机 ES 8.x 的环境执行，未执行不能声称正文召回 E2E 通过。

## 偏离

无个人搜索 API、团队搜索接口、权限实现或设计范围偏离；未修改 State、TASK、数据库、前端或其他任务文件。
