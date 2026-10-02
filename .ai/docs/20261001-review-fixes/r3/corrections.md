# 当前修复 review-fixes-code-r3

r1 的启动双读与部分写失败回归保持已关闭；r2 旧拒绝扫描与新登录交错也已按真实 HTTP 两种模式补用例并修正。

浏览器自动拒绝不删除共享完整 pair，而按 server/session/revision 写无 token 拒绝标记；同步和启动恢复仅拒绝匹配的旧修订，内存/本页 sessionStorage 清空，旧兼容 refresh 镜像移除。新账号和同会话更高修订不受旧标记影响。显式用户退出仍调用原 beginAuthSession 删除共享 pair。旧失效 record 可能保留至显式退出/新登录，不能被当前代码恢复。初始化 snapshot、Web Locks/bakery 与未完成轮换标记保持。

主线程当前 Node 三文件执行退出0：多页25+启动8+desktop25=58/58，失败0/跳过0；Web tsc退出0。普通拒绝传播和重载不恢复旧 record、随后新账号可恢复有新对照；新登录在 marker 原子读之间完成的旧401交错不会删除新 pair。core 三个SHA256未变，26项本轮H2证据复用，不声称本轮真实MySQL/S3/UI测试。
