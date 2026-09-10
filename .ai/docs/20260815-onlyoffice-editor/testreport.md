# 测试报告：在线文档编辑（OnlyOffice）

> 归属：TEST_PASS（依赖 CODE_REVIEW + SECURITY_REVIEW done；编译/测试由主线程串行统一执行）

## 执行环境

- 后端：`mvn test`（根 POM 聚合全部模块）
- 前端：`npx tsc --noEmit` + `npm run build`
- 数据库：H2（MySQL 兼容模式）；36 号 SQL 已同步 schema.sql，SchemaConsistencyTest 三层校验通过

## 结果

### 后端（BUILD SUCCESS）

| 模块 | 结果 |
|------|------|
| st-common / st-auth / st-core | ✅ |
| st-share（修复测试装配后） | ✅ |
| st-team / st-admin / st-search / st-preview / st-api | ✅ |

### 新增 Editor 测试（26 例全绿）

| 测试类 | 覆盖 |
|-------|------|
| EditorLockServiceTest（8 例） | 编辑标记/保护拦截/协同不互斥/保存锁/幂等（TC-18/19/20 锁部分） |
| EditorPermissionIntegrationTest（6 例） | 个人 owner/非 owner/格式/文件夹/团队入口/回收站（TC-01/02/06） |
| EditorCallbackIntegrationTest（8 例） | 验签（缺失/伪造）、自动保存覆盖不生成版本、关闭生成 source=1 版本+移除编辑标记、幂等、key 不匹配、配额差值、大小超限（TC-07/08/09/10/13/14/20） |
| EditorVersionIntegrationTest（4 例） | source 标记（1/0）、仅裁剪 source=1 上限 20、编辑中版本恢复拦截（TC-15/16/17/19） |

### 前端

- `npx tsc --noEmit`：通过（0 error）
- `npm run build`：通过（EditorPage chunk 已产出；PWA 预缓存正常）

## 用例覆盖对照（testcases.md）

- TC-01~TC-23、TC-27、TC-28：已覆盖（自动化）
- TC-24/25（前端错误回退/入口显隐）：代码实现 + 构建验证，浏览器交互联调留部署环境（无 OnlyOffice 容器）
- TC-26（构建）：✅

## 遗留

- 真实 OnlyOffice 容器端到端联调（编辑→自动保存→关闭→版本生成）需部署环境执行（docker-compose 起 onlyoffice 后验证）
- MySQL 执行 36 号脚本 + schema_version 记录（H2 已验证，正式库待部署）
