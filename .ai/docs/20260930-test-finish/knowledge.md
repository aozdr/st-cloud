# 本轮知识记录
没有新增架构、数据模型或API。已定业务规则保持：ID字符串、旧Integer失败关闭、无版本旧JWT重新登录、外部协作关闭即时拒绝、失败同步页不确认、两份冲突成功才提交、缩略图受限安全降级。
新增验证运行约束：Ngram真实ES测试显式test.es.port，禁止默认对共享9200删除测试索引；任务专用ES含IK并自行创建/清理attachment流水线。MySQL条件19条必须单独带端口与S3能力执行，默认跳过不能计通过。源代码SHA256见validated-source-hashes.json，证据范围见testreport.md。
流程schema修复只扩展EXP_DESIGN明确单人授权枚举；未授权、错task及未知标准依然拒绝。所有自检如实标注主线程。
无需另改全局架构/数据/API知识库；本TASK的验证与边界同步在此目录。
