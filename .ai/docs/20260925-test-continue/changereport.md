# 变更报告

新增 st-core 的真实 ThumbnailRenderer 资源与格式回归 8 项，使用内存图片、闩锁和 Spring 上下文，替身隔离 S3。桌面新增同步游标回归 5 项，接入 npm test 与 test:round3。

st-share 新增五线程流下载、一个 URL 与四个流的额度竞争测试，以及源流/签名失败和源流打开后额度耗尽的测试，使用真 H2 Mapper 和源流屏障；旧非事务测试的固定分享码改为与文件节点关联，避免用例间唯一键冲突。

执行中复现数字游标被 String() 接受的问题，将 fetchDelta 改为仅接受规范十进制字符串。服务端 Jackson 原本按字符串序列化 Long，合法协议不变；不修改 DB 或前端其他 ID 契约。

所有用例的合并状态、实际验证与限制见 results.md。
