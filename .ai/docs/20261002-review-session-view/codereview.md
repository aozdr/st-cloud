# 独立评审（session-view-r3）

本次增量独立审查通过，无 open blocker。范围为 api-client 会话代次作用域、manager 恢复与启动保护、engine 会话绑定及 FileBrowser 临时视图回退；未审查或改写既有全量变更。

安全与异步链：请求发起、成功响应、恢复循环异步边界均校验代次，旧恢复的后续请求不能借用新凭据。正常刷新不推进代次，失败清理仅移除自身引擎实例。engine 的 WS、文件事件和定时调度通过创建时的会话绑定。

UI：移除加载阶段重写持久视图偏好的 effect，Toolbar 与 FileList 共用 effectiveView，加载、空目录、混合目录的临时网格不会清除 waterfall 偏好。

r2 发现的 P2 已关闭：失效会话在 syncOnce 包装层产生的拒绝曾逃出内部 catch，定时器缺少处理；r3 定时器已附加 catch，回归覆盖真实 engine 的该触发路径。

证据：reviewer 独立执行早期会话回归 8/8 通过，随后静态复核 r3 的 timer catch、新增正常令牌轮换测试及 timer 回归。主线程完成的当前 revision 回归和类型检查统一引用 verification.md，不将其冒称为 reviewer 独立运行。未进行真实 Electron/browser 交互验证。

criterionProposal：建议 CODE_REVIEW 通过；未写 State，最终验收由主线程判断。
