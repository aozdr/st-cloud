# 当前修订独立测试汇总

目标：独立判定 TEST_PASS/env-code-r2，有效证据须属于当前实现或其模块 SHA 一致。CODE_REVIEW/SECURITY_REVIEW已通过，主线程已加载最终10028/8080 JAR，最终源身份780项。
输入：requirement/testcases/changereport/current-source-manifest、backend-final-counts/backend-full-r3（此处r3是测试批次名，State修订仍r2）、desktop-final-regression/desktop-ts-tests/desktop-recovery-rounds-pass/desktop-r2-restart、web-r2构建、live/ops/exp-accept独立结果、schema前后/46分支、backend-running-final/final-runtime-smoke/final-runtime-mq/final-runtime-tenant。
include 写入：.ai/docs/20260930-environment-remediation/testreport.md；final-test-*；.ai/runtime/results/DISPATCH-env-final-test-01.json。
exclude：产品源码/配置/State、Git/Maven/依赖安装、凭据提取/输出、真实业务新增/删除。仅独立读取现有证据，有限只读当前SHA/日志/8080 ping检查；不要再跑共享构建或创建fixture。
核对：后端原逐类统计853包含46条件跳过，实际807通过；跳过四类及隔离条件要说明，不能冒充全部853运行。此前缺候选Bean、Mockito重新设桩、测试分片0起始等失败须保留并确认修复后证据。桌面167基线全量先于恢复ID修复，当前34项恢复/状态和20项真实SQLite跨进程受影响回归补验；21TS和认证源码未变，禁止重复合计成唯一全量计数。Web胶卷17为受控错误/真实Chrome组件，最终PNG取图是真实后端/S3/Chrome。
逐项B01、V01–V06、O01–O04映射，实机Electron/长期压力/浏览器恢复后返回原列表、Broker自动health启用/本地ES镜像/历史OOM根因/生产多实例密钥属明确边界；O05未清理授权。测试fixture令牌已脱敏，测试配额被保留导致当前可分配容量0的事实需报告。
正式结果使用中文约定格式，TEST_PASS proposal validatedRevision=env-code-r2、by=实际child；无当前失败和未解决产品缺陷才pass。不能把主线程Goal标完成。
