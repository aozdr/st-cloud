# 多标签认证与空间墓碑回收

认证共享记录 stcloud:auth 保存完整pair。读取上下文和storage事件均重读当前记录；初始化基线绑定实际读取的raw。浏览器Web Locks优先，普通HTTP以按页bakery ticket/45秒租约协调。消费一次性refresh前持久化不含token的rotation marker；未落盘成功或不确定网络结果保留标记，已保存较新修订清理旧标记。标记无法写时请求前返回临时错误。

自动拒绝只标记对应server/session/revision及更早版本失效；较旧共享记录不能遮挡本页明确拒绝，新账号/较高修订不能被旧拒绝删除。启动/同步忽略被拒绝版本。显式退出沿用全局退出。Electron仍由主进程IPC唯一轮换。

团队空间软删除不使其已有回收节点停止保留期清理。系统先锁该租户空间墓碑再锁节点并复查RECYCLED；用户恢复/删除仍需有效空间与成员。对象释放和物理删除仍走原提交后事件；正常节点不在该系统清理范围。

当前代码/证据：review-fixes-code-r4，.ai/docs/20261001-review-fixes/source-manifest-r4.json、testreport.md、r4/codereview.md。无API/DDL变化；真实MySQL/S3/UI未验证。
