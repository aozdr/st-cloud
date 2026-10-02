# 第二次修复（review-fixes-code-r2）

主线程接受 CODE_REVIEW/SECURITY_REVIEW 在 r1 的两条可复现失败并保留原独立结果。原设计契约不变，补充 T08–T10 后再实现。

启动时 observedStorage 与 loadCredentials 真正读取的 raw 绑定；不再二次读取并掩盖内存旧对。覆盖两种锁模式的启动续期/退出交错。

浏览器请求前保存按页/会话/修订的 rotation marker，仅存 id/serverUrl/sessionId/revision，无 token。成功 pair 保存失败、网络结果不确定时保留；别页拒绝检查未完成标记，不能清空已成功页会话。成功新修订保存后删除较旧标记。标记也无法保存时不消费 token，返回临时错误并保留原对。旧标记不阻塞请求，无法确定的失败可能需显式登录。桌面仍用 IPC，原磁盘失败恢复保持。

主线程三文件 Node 一次执行退出0：auth-tabs 21、auth-startup 8、auth-rotation 25，共54，失败0/跳过0；Web tsc 退出0。新增写失败用例最初错误地要求未保存的 sessionStorage 镜像也更新，修正为断言内存完整 pair；保存恢复后仍断言另一页内存/sessionStorage/共享对及标记清理。

core 三文件 SHA256 与 r1 不变，26项本轮 H2 证据适用。实际 MySQL/S3/UI 未测边界保持；新的 attempt 独立复核 r2。
