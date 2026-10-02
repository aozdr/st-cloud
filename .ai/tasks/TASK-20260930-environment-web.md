# V04 浏览器专项

目标：在当前源码用真实 Chromium/React 补 V04，必要时修复胶卷陈旧 error 状态。使用新证据，不直接复用历史通过结论。

include: st-web/src/components/preview/PreviewModal.tsx、该组件必要专项测试；.ai/docs/20260930-environment-remediation/web-*；.ai/runtime/results/DISPATCH-env-web-01.json。
exclude: State、其他 UI、后端、数据库、Git、Maven、依赖锁文件、安装依赖。

验证：Tab/Space/Enter/左右键、同一挂载 updatedAt 和 URL变化、旧异步error不得影响新src、失效图标对比度量化、名称与焦点。无必要不修改产品源码。真实浏览器不可用如实返回缺口。使用现有Node和bundled Playwright/esbuild。主线程负责集成与Evaluate。

输出中文独立结果；只建议部分 IMPLEMENTED，revision env-code-r1，by /root/web_validation。保留运行日志/脚本/截图。
