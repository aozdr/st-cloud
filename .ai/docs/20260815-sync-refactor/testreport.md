# 测试报告：同步引擎 V2 重构

> 归属 TEST_PASS。

# 一、测试范围与结果

| 项目 | 命令 | 结果 |
|------|------|------|
| 类型检查 | `npx tsc --noEmit` | 通过 |
| 单元测试 | `npm test` | 16/16 通过（sync-utils 8 + db-migrate 5 + sync-retry 3） |
| 桌面端构建 | `npm run build:main` | 通过（dist/main.js + preload.js） |
| 服务端编译 | `mvn -pl st-sync -am -DskipTests compile` | 通过 |

# 二、测试内容

- sync-utils：冲突副本识别、唯一命名、本地变更判定、相对路径推导
- db-migrate：INTEGER→TEXT 重建后 sync_version 列与值保留
- sync-retry：退避/重试边界（回归）
- 构建链路：tsup 打包主进程通过

# 三、未执行项与说明

- 服务端全量 `mvn test`：本环境未启动完整依赖（MySQL/RocketMQ/ES 集成测试），留待部署环境补跑
- 桌面端手工联调清单（TC-001~TC-008）：需安装新版桌面端后按 testcases.md 执行

# 四、结论

```
已执行范围内全部通过；服务端集成测试与手工联调作为交付后待办
```
