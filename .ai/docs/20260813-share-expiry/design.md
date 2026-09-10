# 程序设计文档：文件分享可选过期时间（20260813-share-expiry）

## 一、现状与问题确认

### 已有实现

- `docker/mysql/init/02_create_tables.sql`：`file_share.expire_at DATETIME DEFAULT NULL` 已存在。
- `st-share`：`FileShare.expireAt`、`CreateShareRequest.expireAt`、`UpdateShareRequest.expireAt`、`ShareVO.expireAt` 均已存在。
- `ShareServiceImpl.createShare` 保存 `expireAt`；`validateShareAccess` 统一校验过期并抛 `SHARE_EXPIRED(3002)`（覆盖访问/下载/列表/流式预览）。
- `st-web`：`ShareDialog` 有效期选项（1/5/7 天/无限期，默认 7 天）；`ShareManagePage` 展示有效期。

### 待修复问题

| 编号 | 问题 | 修复方案 |
|------|------|---------|
| P1 | 无任何分享模块自动化测试 | 新增 st-share 测试基础设施 + 集成测试 |
| P2 | 创建/更新可传过去时间 | 服务层校验 `expireAt.isAfter(now)`，否则 `BAD_REQUEST` |
| P3 | 更新无法清除过期（null 不生效） | `UpdateShareRequest` 新增 `clearExpireAt` 布尔字段 |
| P4 | 管理页已过期仍显示"有效" | 前端按 `expireAt` 计算展示"已过期" |
| P5 | 前端 UTC ISO（带 Z）与后端 LocalDateTime 语义不一致 | 前端改发本地时间 `yyyy-MM-ddTHH:mm:ss` |
| P6 | H2 schema 缺 `file_share` | 补齐 st-core 与 st-share 测试 schema |
| P7 | `ShareAccessVO.isExpired` 死代码（恒 false，前端未用） | 删除该字段 |

## 二、技术方案

### 2.1 时间语义约定

- 统一使用 **Asia/Shanghai 本地墙钟时间**：后端 `LocalDateTime` 存取，比较基准 `LocalDateTime.now(ZoneId.of("Asia/Shanghai"))`（沿用现有 `validateShareAccess` 逻辑）。
- 前端提交格式：`yyyy-MM-ddTHH:mm:ss`（本地时间，无时区后缀），与 `LocalDateTime.ISO_LOCAL_TIME` 解析兼容。
- 后端返回格式：`yyyy-MM-dd HH:mm:ss`（沿用 `ShareVO.expireAt` 的 `@JsonFormat`）。

### 2.2 后端改动（st-share）

1. `UpdateShareRequest` 新增字段：
   - `private Boolean clearExpireAt;`（`@Schema(description = "是否清除过期时间（设为永久）")`，null 视为 false）。
2. `ShareServiceImpl.createShare`：
   - 在 `setExpireAt` 前校验：`request.getExpireAt() != null && !request.getExpireAt().isAfter(LocalDateTime.now(ZoneId.of("Asia/Shanghai")))` → 抛 `BusinessException(BAD_REQUEST, "过期时间必须晚于当前时间")`。
3. `ShareServiceImpl.updateShare`：
   - `if (Boolean.TRUE.equals(request.getClearExpireAt()))` → `wrapper.set(FileShare::getExpireAt, null)`；
   - `else if (request.getExpireAt() != null)` → 先做未来时间校验，再 `wrapper.set(...)`；
   - 其余字段逻辑不变（向后兼容：老客户端不传 `clearExpireAt` 时行为不变）。
4. `ShareAccessVO`：删除 `isExpired` 字段与 `accessShare` 中的 `vo.setIsExpired(false)`。
5. 核心逻辑（过期校验、状态流转）保持并补充中文注释。

### 2.3 前端改动（st-web）

1. `ShareDialog.tsx` `computeExpireAt`：
   - 由 `new Date(...).toISOString()` 改为本地时间格式化：`YYYY-MM-DDTHH:mm:ss`（手写补零，不依赖时区转换）。
   - 有效期选项、默认 7 天保持不变。
2. `ShareManagePage.tsx`：
   - 新增 `isExpiredShare(share)` 判断：`status === 1 && expireAt && new Date(expireAt.replace(' ', 'T')) <= new Date()`。
   - 状态列：已过期展示琥珀色"已过期"徽标（仍保留取消按钮）。
   - 不改动筛选 Tab（全部/有效/已取消），保持改动最小。
3. `types/index.ts`：`ShareAccessVO` 删除 `isExpired: boolean`。

### 2.4 测试基础设施（st-share 新增）

- `st-share/pom.xml`：新增 `spring-boot-starter-test`、`com.h2database:h2`（scope=test，写法对齐 st-core）。
- `st-share/src/test/resources/application-test.yml`：H2 `MODE=MySQL` 内存库 + `schema.sql` + MyBatis-Plus 配置（对齐 st-core）。
- `st-share/src/test/resources/schema.sql`：`file_share` + `file_node` 表（列定义对齐 MySQL init SQL 与 st-core schema）。
- `st-share/src/test/java/com/stcloud/share/ShareTestApplication.java`：仿 `CoreTestApplication`（禁用 Redis/Security/Web，`@MapperScan` 覆盖 `com.stcloud.share.mapper` + `com.stcloud.core.mapper`，`@Import` MyBatis 配置）。
- `st-share/src/test/java/com/stcloud/share/AbstractShareIntegrationTest.java`：`@SpringBootTest(ShareTestApplication.class)` + `@ActiveProfiles("test")` + `@Transactional`，提供 `setUpUser` 与 `insertFileNode`。
- 测试用 `@TestConfiguration` 提供 Mock 的 `StorageService`/`DownloadService`/`FileService`（分享链路只用其 `validateAccessible`/`generateDownloadUrl` 方法），`FileShareMapper`/`FileNodeMapper` 用真实 H2。
- `st-core/src/test/resources/schema.sql`：追加 `file_share` 表定义（保持 H2 与 MySQL 对齐）。

### 2.5 测试用例（详见 testcases.md）

覆盖：创建（有限期/永久/过去时间拒绝）、访问（到期前成功/到期后 3002）、下载/列表/流式预览过期拒绝、更新（改期/清除/过去时间拒绝）、列表 VO 返回 expireAt、`clearExpireAt` 语义、时间格式解析。

## 三、接口契约

### `POST /api/share/create`

请求体 `CreateShareRequest`（不变，`expireAt` 可选）：

```json
{ "fileNodeId": 1, "shareType": 0, "expireAt": "2026-08-20T23:59:59" }
```

校验：`expireAt` 为空=永久；非空必须晚于当前时间，否则 `400`（message: 过期时间必须晚于当前时间）。

### `PUT /api/share/{shareId}`

请求体 `UpdateShareRequest`（新增可选字段 `clearExpireAt`，向后兼容）：

```json
{ "clearExpireAt": true }
```

语义：`clearExpireAt=true` 时清除过期时间（永久）；否则若传 `expireAt` 则修改（需晚于当前时间）。

### 响应

- `ShareVO.expireAt`：`yyyy-MM-dd HH:mm:ss` 或 null（不变）。
- `ShareAccessVO`：删除 `isExpired` 字段。
- 过期访问：`Result` code=3002，message="分享已过期"（不变）。

## 四、数据库变更

- MySQL：**无 DDL 变更**（`expire_at` 已存在），无需迁移脚本、无需 schema_version 记录。
- H2：`st-core/src/test/resources/schema.sql` 与 `st-share/src/test/resources/schema.sql` 补齐 `file_share` 表（仅测试资源）。

## 五、风险与兼容性

| 风险 | 影响 | 缓解 |
|------|------|------|
| `clearExpireAt` 契约扩展 | 老客户端不传该字段，行为不变 | 默认 null=false，向后兼容 |
| 前端时间格式变更 | 只影响新创建/更新的分享 | 后端两种格式均可人工验证；新增解析测试锁定 |
| 测试环境搭建复杂（FileService 依赖重） | 集成测试启动失败 | 分享链路依赖的方法以 Mock 隔离，Mapper 用真实 H2 |
| 其他模块（团队邀请）存在同类时区隐患 | 不在本迭代范围 | 在 changereport 风险章节备注，另立迭代 |

## 六、验证方式

1. `mvn -pl st-share -am test`（含新增集成测试）全绿。
2. `mvn test`（全模块）全绿。
3. `cd st-web && npm run build` 通过。
4. Code Review + Security Review 通过。
