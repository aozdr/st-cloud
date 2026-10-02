# V04 浏览器专项结果

## 背景

本次只处理 TASK-20260930-environment-web：胶卷的键盘、同一挂载下版本/来源变化、陈旧 error 和量化对比度。历史通过记录未用于本次判定。

## 输入

当前 PreviewModal.tsx、env-design-r1 设计和 WEB01。Dispatch 为 DISPATCH-env-web-01；执行者 /root/web_validation。读取最小 State 快照，未写 State。

## 分析

事实：真实 Chrome 154.0.8037.57 加载当前 React 18、PreviewModal 和当前 Tailwind 配置，图片请求走独立 loopback HTTP。基线 16 项中 13 项通过、3 项失败：保存的旧 v2 onError 会清除当前 v3 的降级状态；迟到 v0 error 后，返回先前成功 v0 会继续显示失败图标；未选中图标叠加透明度后，观察到 2.607:1 对比度。

旧异步回调专项保存的是实际 React DOM 中的 onError，随后在新请求失败/成功后通过 flushSync 受控调用。这验证真实组件对陈旧回调的处理，不宣称浏览器网络栈一定自然产生此顺序。

## 决策

失败状态绑定每次 src 变化产生的请求身份；onError 仅接受当前身份。URL 返回先前值也形成新身份并重试。降级图标由 white/60 调为 white/90，保留按钮原有选择、名称和透明度。只修改 PreviewModal.tsx，未改其他产品模块或安装依赖。

最终 17 项全部通过：Tab/Space/Enter、左右键及边界、名称与焦点保持；同一按钮 DOM/外层挂载的 updatedAt 更新、失败后新版本、旧回调、URL 返回、分享码与编码密码变化；降级与 Escape；无未处理浏览器异常。

动画结束后从实际 computed style 合成背景并按 WCAG 相对亮度公式计算：未选中 4.508:1，已选中 15.862:1，均超过图标 3:1 门槛。键盘 :focus-visible 成立，截图中焦点边界可见；保留浏览器默认焦点指示。

当前组件 SHA-256：ef5b11927235e1c917e780985906ed421cd44e0e6da1cee5886b0d06973d8d57。

## State Delta（proposal）

建议将 V04 部分 IMPLEMENTED 接受，validatedRevision 为 env-code-r1。未设置全局 IMPLEMENTED、EXP_ACCEPT 或 ACCEPT。正式 proposal 见本 dispatch 独立结果。

## 风险

这是实际 Chromium/React 和受控 HTTP 专项；没有覆盖真实后端分享权限、Electron 实机或其他页面。量化值针对该胶卷图标的当前样式和测试背景，未宣称全站可访问性验收。先前 tsc -b 因沙箱禁止缓存写入失败，改用不写缓存的类型检查后退出 0。

## 下一步

主线程串行集成 Web 生产构建和独立审查。当前证据不替代其最终验收。

## 变更影响

分享胶卷缩略图在 updatedAt、来源或密码变化后重新尝试；旧失败回调不影响新请求，图标更清晰。API 与分享 URL 契约未变。

## 验证与产物

复现命令：`node .ai/docs/20260930-environment-remediation/web-filmstrip-test.cjs --label=final`。脚本使用现有 esbuild、React、Tailwind 和 bundled Playwright，不安装依赖。浏览器路径可用 STCLOUD_BROWSER_PATH 覆盖。

类型检查：在 st-web 执行 `node node_modules/typescript/bin/tsc --project tsconfig.app.json --noEmit --incremental false --pretty false`，退出 0。

- web-baseline.log / web-baseline-results.json：基线 13/16；3 项失败保留。
- web-final.log / web-final-results.json：当前源码 17/17，退出 0，含浏览器版本、源码哈希、请求和量化值。
- web-final-focus.png / web-final-fallback.png：键盘焦点和失败降级截图，已人工查看焦点截图。
- web-typecheck.log：类型检查退出码。
- web-filmstrip-test.cjs：当前源码专项脚本。
