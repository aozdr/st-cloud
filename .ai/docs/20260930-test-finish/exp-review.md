# 主线程体验设计及验收自检
root执行；当前TASK用户明确单人授权。EXP_DESIGN与EXP_ACCEPT均为主线程自检，UI applicable=true。

沿用定版uispec，不改变布局与功能范围。成员角色选择保留精确字符串、同名角色分开，服务端403/网络失败保留待提交选择、显示未保存、成功才更新保存角色，重新打开回显与重试通过；失效角色显示无效角色。邀请/链接/ACL三组大ID轮换不合并、不错选。

胶卷失败退图标，title/aria-label为文件名；主图失败稳定提示，每文件/版本一次显式重试，导航与正常GIF保留。真实浏览器断言无原图批量回退、无无限请求；SVG仅img且脚本/外链不执行。已人工查看browser-preview-svg.png、browser-preview-fallback.png：名称、主图区、导航及失败提示正常，无布局遮挡。

个人文件历史版本current/content契约与三组file/version路由精确；分享目录parentId和预览nodeId query精确。浏览器API受控，后端业务另层测试，不声称浏览器直连H2。

结果：定版UI状态覆盖完整，无未决体验裁决。证据browser-matrix.log、browser-version-share-complete.log及请求JSON/截图；web-scoped-lint.log与web-build.log。
State Delta：EXP_DESIGN validatedRevision=finish-confirmed-v1；建议EXP_ACCEPT pass validatedRevision=finish-contract-tests-v9。
