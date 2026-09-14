# 测试报告

## 验证 revision

`20260914-code-review-round3-working-tree`，基线 `main@7be97cc`。

## 通过项

| 门禁 | 命令 | 结果 |
|---|---|---|
| st-core 完整测试 | `mvn -pl st-core -am test` | 187 tests，0 failures，0 errors，BUILD SUCCESS |
| st-team 完整测试 | `mvn -pl st-team -am test` | common 31、auth 8、core 187、team 36 全部通过，BUILD SUCCESS |
| H2 schema | `mvn -pl st-core -am "-Dtest=SchemaConsistencyTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` | 3 tests 全部通过 |
| MySQL/H2 schema | `.ai/scripts/compare-schema.ps1` 连续执行两次 | 两次均 PASS；`file_orphan_candidate` 11 列对齐，SQL 已记录 |
| Relay/P0 定向测试 | `mvn -pl st-core -am "-Dtest=RelayUploadIntegrationTest,OrphanObjectCleanupServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` | 21 tests 全部通过 |
| Web hash/build | `npm run test:hash; npm run build`（`st-web`） | 6 tests 通过，Vite/PWA build 成功 |
| Desktop | `npm run test:round3; npm run lint; npm run build:main`（`st-desktop`） | 19 tests 通过，TypeScript lint 和 main/preload build 成功 |
| Share 编译 | `mvn -pl st-share -am "-DskipTests" package` | BUILD SUCCESS |
| Share 安全回归 | `mvn -pl st-share -am "-Dtest=ShareServiceImplSecurityIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` | 21 tests，0 failures，BUILD SUCCESS |
| 差异检查 | `git diff --check` | 无空白错误；Git 仅提示既有 CRLF 转换警告 |

## 测试契约修复

原 `createShareGenerates4CharSafeShareCode` 测试仍断言历史的 4 位大写码，与当前生产策略（默认 12 位、可配置 8–16、大小写混合字符集）冲突。已将测试改为验证当前正式契约，未降低生产分享码安全策略。

## 数据库执行边界

迁移只执行到本地开发/测试 MySQL，未触及生产数据库。schema 对比在迁移后再次连续两次通过。
