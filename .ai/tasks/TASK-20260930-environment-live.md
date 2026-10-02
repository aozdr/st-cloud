# 当前真实服务认证与同步验证

目标：在本次8080完整后端、真实MySQL/Redis/MQ/S3上补 B01 与 V03 当前证据。
include: .ai/docs/20260930-environment-remediation/live-*；.ai/runtime/results/DISPATCH-env-live-01.json；独立本任务测试文件目录（docs/live-runtime下）。
exclude: 产品源码、State、迁移、历史证据、其他用户资源、Git、Maven、依赖安装。不要停止8080、Docker或其他任务进程。8080为本轮主线程启动，身份backend-running.json；启动日志backend-8080.log。只创建随机专用测试账号/空间/文件/同步根，所有token只在内存或隔离本地持久fixture，不写报告/日志。禁止操作既有用户账号或旧资源。允许对刚创建fixture做软删除/恢复以验证，保留审计资源，不清共享库。
验证1：实际桌面api-client+前端auth-session代码，真实HTTP/Redis至少2轮并发401（制造无效access，但refresh真实），旧refresh拒绝，新对持久恢复后再轮换；主进程渲染器共用协调验证。已有auth-test-harness.cjs可参考，保留测试边界（无Electron实机不能称实机）。
验证2：实际桌面SyncEngine、sql.js持久库、真实文件和chokidar，对8080实际文件/S3/MQ：删除→恢复→旧DELETE重放；离线MOVE/RENAME+UPDATE；新进程读取持久映射重放；启动故障受控注入后重试。旧DELETE/启动失败故障注入必须明确；其余API不能替换为模拟后端。初次API返回失败要识别业务配置/权限并向主线程报告，不改产品。
实际业务就绪/Outbox异步等待使用有限时长，不随意删除fixture。当前接口见源码，不执行历史脚本。需要文档目录权限可require_escalated。输出中文TEST_PASS部分建议env-code-r1；不能替整个测试门禁宣布通过。
