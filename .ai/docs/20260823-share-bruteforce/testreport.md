# Test Report：分享防枚举

结论：PASS

## 已验证

| 目标 | 用例 | 结果 |
|---|---|---|
| 全局配置服务 | `SysConfigServiceTest`（6） | 通过 |
| H2/MySQL schema 一致性 | `SchemaConsistencyTest`（3） | 通过 |
| 分享码加固 | `ShareServiceImplShareCodeUnitTest`（3） | 通过 |
| 验证码服务 | `ShareCaptchaServiceTest`（2 组 + 空参/过期） | 通过 |
| 防爆破守卫（单码/IP） | `ShareBruteForceGuardTest`（2） | 通过 |
| 防爆破集成 | `ShareServiceImplBruteForceIntegrationTest`（5） | 通过 |
| 全部 st-share 测试 | `com.stcloud.share.*` | 通过 |
| 前端 | `tsc + vite build` | 通过 |

## 说明

- `st-share` 集成测试基于 H2 + 真实 MyBatis-Plus，覆盖失败计数、锁定、成功清除、验证码触发、不存在分享跳过、正常访问不误伤、验证码下发。
- 分享码验证为 12 位 57 字符集（符集排除 `0/O/1/I/l`）。
- 前端构建通过，验证码交互与错误码提示已接入。

## 结论

功能与回归测试全部通过，可进入验收。
