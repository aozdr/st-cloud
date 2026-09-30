# 体验设计独立评审

修订：review-fixes-design-v1。执行者：/root/exp_design_review。Dispatch：DISPATCH-20260930-REVIEW-EXP_DESIGN-4caa9c。

## 背景

原报告 F8 指出：分享胶卷支持格式缩略图加载失败仅隐藏 img，留下空白按钮。本次只评审定版设计是否覆盖 uispec 的页面与状态，不作实现或运行时验收。

## 输入

- TASK-20260930-REVIEW-EXP_DESIGN、当前任务 State 的 taskId/design revision/EXP_DESIGN 与依赖快照。
- 当前 requirement.md、uispec.md、design.md、testcases.md，以及原始 review.md 的 S1/F1–F8。
- 本任务 baseline/hashes.json 与 baseline/st-web/src/components/preview/PreviewModal.tsx；只读当前 PreviewModal.tsx、lib/utils.ts、types/index.ts。
- CR-07 requirement.md 的 R07-4/R07-6，用作原问题的范围与资源语义依据；没有加载历史 State。

Envelope 的必需字段、允许字段、身份三元组、版本、写入范围与 forbidSpawn 已核对。skillRefs 为 `-`，无附加技能。

## 分析

已执行的事实检查：PreviewModal 的声明 baseline、baseline 副本和当前源码 SHA256 均为 `b68dbfabd4b79a65f7fcbd487e42c33e3bef79ea5f0a2ec40fca0e6d3d9cd7fd`。当前第 514–540 行保留 button/title/aria-label/onClick，但失败处理仍只是隐藏 img；WebP/SVG 使用当前主文件 config.icon。原 F8 在设计审查时仍存在，不能将本结论解释为已经修复。

| uispec 页面/状态 | 定版设计覆盖与依据 | 结论 |
| --- | --- | --- |
| 分享胶卷正常缩略图 | uispec 明确保留尺寸、活动边框、焦点和提示；design 第 6 项限定失败分支 | 覆盖 |
| WebP/SVG 不支持服务端缩略图 | uispec 与 design 第 6 项要求和请求失败分支使用同一通用图片图标 | 覆盖 |
| 损坏、超限或网络失败 | 按对应缩略图 URL 记录失败并条件渲染图标，避免只有隐藏图片留下空白；图标居中且有可见对比 | 覆盖 |
| 失败后选择与导航 | uispec 保留名称、title/aria-label、键盘与点击；现有 button 与全局左右键导航可保留 | 覆盖 |
| 分享来源或内容版本变化 | design 第 6 项要求 URL 包含来源与版本，uispec 要求不同 URL 可重新请求 | 覆盖 |
| 含其他文件类型的预览列表 | uispec 禁止误用主文件类型图标，胶卷统一使用图片图标 | 覆盖 |

九项范围追踪仅确认当前文档边界，不提出实现通过结论：S1 对应 design 第 7 项及迁移用例；F1/F2 对应第 1/2 项及 MySQL 用例；F3 对应第 3 项及 2008/403 用例；F4/F6 对应第 4 项及内容基线/重放用例；F5/F7 对应第 5 项及启动顺序/重试用例；F8 对应第 6 项及失败降级用例。前八项没有新增 UI 页面，不需要扩展本次胶卷视觉范围。

判断：技术方案覆盖当前 uispec 全部页面与状态。设计没有要求自动下载原图，符合原 R07-4 的资源边界；保持既有授权路径和缩略图接口，未发现需要用户重新裁决的范围、兼容性或风险问题。

## 决策

建议 EXP_DESIGN 通过，validatedRevision 为 review-fixes-design-v1。此结论限于设计覆盖与可实现性；不代表 F8 已落地或 EXP_ACCEPT 通过。

## State Delta（仅 proposal）

提出 EXP_DESIGN outcome=pass，evidenceRef 为本 dispatch 的独立结果 JSON。未修改 State，未执行 Evaluate，未定义或判定 Goal。

## 风险

实现时必须让实际 URL 随节点 updatedAt 等已有版本依据变化；否则同节点内容更新后会沿用失败状态。失败状态按 URL 键控，异步旧 img 的 error 不应污染新的 URL。通用图标不得取当前主文件 config。以上均为定版要求的实现约束，不是新增范围。

本次未运行浏览器、未触发真实 img error、未测量图标对比度、未执行 Web 构建，未运行后端、MySQL 或桌面验证。

## 下一步

实现后按 F8 用例验证：正常图、WebP/SVG、error 后图标；失败状态下名称、Tab/Enter/Space、左右键和点击切换；分享来源或内容版本改变后重新请求；不自动加载胶卷原图。由主线程完成 EXP_DESIGN Evaluate，再进入后续门禁。

## 变更影响

仅新增本体验评审报告和独立结果 JSON。业务源码、数据库、配置和 Loop State 均未修改。

---

# 体验验收独立评审（EXP_ACCEPT）

修订：review-fixes-code-3ae1050e4646f449。执行者：/root/exp_accept。Dispatch：DISPATCH-20260930-REVIEW-EXP_ACCEPT-647c69。

## 背景

本次独立审查定版 uispec 对应的分享图片胶卷修复及九项 finding 的范围证据。只比较本任务开始时 baseline 与当前改动，不评审无关历史问题，不代替 CODE_REVIEW、SECURITY_REVIEW、TEST_PASS 或最终 ACCEPT。

## 输入

- 本次 TASK、Envelope、当前 State 的 taskId/revision/EXP_ACCEPT/IMPLEMENTED 依赖信息；没有加载历史 State。
- requirement.md、design.md、testcases.md、uispec.md、changereport.md，以及原始 review.md 的 S1/F1–F8。
- baseline/hashes.json、八份 baseline 副本、source-revision.json、当前对应源码和新增用例。
- browser-filmstrip.py、browser-filmstrip.log、browser-filmstrip-requests.json、browser-filmstrip-fallback.png、web-build.log、desktop-review.log、backend-regression.log、mysql-migration.log、schema-before.log、schema-after.log。

Envelope 的 required 字段、允许字段、schemaVersion=2、角色、ACK 三元组、范围与 forbidSpawn/forbidGitMvn 已按 dispatch.schema.json 核对；skillRefs 为 `-`。Python 环境没有 jsonschema 包，未声称执行 JSON Schema 库校验。

## 分析

审查者实际执行了源码哈希核验、baseline/current 文本差异比对、浏览器脚本及请求日志检查、截图目视检查和相关用例/日志检查。source-revision.json 的全部 15 项当前哈希匹配，八份 baseline 副本均匹配 baseline/hashes.json。当前 PreviewModal.tsx SHA256 为 `6ae60c218a06d1aaaf8b443abb5d079d4d9a9be0c07a547eb9fb35a75f8e6800`；baseline 为 `b68dbfabd4b79a65f7fcbd487e42c33e3bef79ea5f0a2ec40fca0e6d3d9cd7fd`。sync-reconcile.ts 与 baseline 相同；F5 的实际修复位于启动前加载规则的 sync-manager.ts，没有把原有全量实现冒充新改动。

### uispec 与实现、运行证据

| 状态或规则 | 当前实现与证据 | 审查结论 |
| --- | --- | --- |
| 支持格式缩略图 503 或图片解码失败 | PreviewModal.tsx 第 16–23 行以 failedUrl===src 渲染 ImageIcon；浏览器 fixture 分别返回 503 与无效 PNG，逐个断言 svg 可见且 img 不存在；日志 PASS | F8 的空白按钮问题已消除 |
| WebP/SVG | 第 537–548 行使用同一 ShareFilmstripThumbnail，supported=false 进入同一 ImageIcon 分支；截图中两个图标可见，requests 未请求其缩略图 | 不再采用当前主文件 config.icon |
| 正常缩略图、尺寸与活动边框 | normal.jpg 在真实 React 页中断言 img 可见；截图显示蓝色缩略图与其他图标；button 的尺寸、边框和 class 与 baseline 相同 | 正常状态保留 |
| 名称、点击与键盘 | 第 525–532 行 title、aria-label、onClick 与 baseline 相同；浏览器按图片名定位，点击 broken.png、Enter 选择 network.jpg 并断言活动边框 | 已运行名称定位、点击、Enter；Tab/Space 和左右键专项未运行，其 button 语义与既有 keydown 代码没有改动 |
| 图标居中且可见 | 两种失败共用 w-5 h-5、mx-auto、mt-1、text-white/60；1280×900 截图中四个降级图标可见，未出现空白失败按钮 | 目视满足现有胶卷样式；未做自动对比度测量 |
| 分享来源或内容版本变化 | 第 541 行将 updatedAt 放入 v 参数；src 含 shareCode/password/节点 id，failedUrl 只与当前 src 比较。requests 中同节点 100 在 review-a 和 review-b 各请求一次，均包含 v | 分享来源变化有浏览器证据；同一挂载组件内 updatedAt 变化与异步旧 error 的专项未运行，URL 比较逻辑已核对 |
| 不自动加载胶卷原图 | 三个支持格式请求 thumbnail，两个不支持格式没有 thumbnail 请求；stream 只出现当前初始图片及主动选择图片 100/101 | 请求记录未显示因胶卷失败自动下载其他原图 |

browser-filmstrip.py 使用真实页面与真实 React 组件，Playwright 的 `page.route('**/api/**', api)` 受控所有 API 响应。browser-filmstrip.log 的 PASS、requests 中的来源/version/节点与截图相互一致，且脚本断言无 pageerror。浏览器执行者为主线程；本 reviewer 没有重跑浏览器，也没有将该证据描述成真实 HTTP 后端端到端测试。Web 构建日志包含 tsc 与 Vite 生产构建成功。

### 九项修复范围追踪

| Finding | 相对 baseline 的改动事实 | 已读取证据及边界 |
| --- | --- | --- |
| S1 | 45 改用 INFORMATION_SCHEMA 条件 DDL，新增 46 同等保护 | mysql-migration.log 记录 45 重跑、46 两次、已有版本 17 保留、20260930.901/.902 登记；schema-before/after 均 PASS，迁移日志两次 exit=0；后端 SchemaConsistencyTest 3/3 |
| F1 | register 写事务 RC，提交后 RR 一致只读快照，isCurrent 与会话/refresh 签发在写事务外 | RegisterMysqlConcurrencyIntegrationTest 真 MySQL/生产 AuthService/真实 JWT/受控 Redis；撤权等待、禁用、回滚三用例，backend-regression.log 3/3 |
| F2 | 七个角色引用写入口 RC，空间锁后检查权限；接受邀请重新读取状态、过期与空间 | TeamRoleMysqlConcurrencyIntegrationTest 日志 16/16，H2 角色并发 16/16；本 reviewer 未重跑数据库测试 |
| F3 | getNodeDetail 先所有权检查，再返回已有回收/删除业务码 | FileServiceFlowIntegrationTest 的回收节点/祖先 2008、他人 403、其他租户拒绝用例；desktop-review.log 的 DELETE 2008 保全推进与 403 不删除不推进均通过。桌面网络受控，不是 Electron/HTTP 端到端 |
| F4 | MOVE/RENAME 仅补下载替换当前内容；普通 rename 保留本地旧 md5/size 基线 | desktop-review.log 六个 MOVE/RENAME × missing/preserved/rename 场景全部通过；用例校验本地字节、保全副本、普通 rename 后续 UPDATE |
| F5 | manager 先严格加载 exclusions 再 engine.start，规则响应无效时抛错 | 用例确认 exclusions 是首个请求、被排除原件/映射不变且不读该节点；规则故障时未对账。desktop-review.log 通过 |
| F6 | 文件目标已成功落地重放时，只清理同节点旧映射，未完成状态拒绝确认 | 两项用例分别确认仅留新映射/游标推进/不重复下载，以及其他节点旧映射保留；desktop-review.log 通过 |
| F7 | 全量失败显式抛错，manager 删除缓存并 stop，允许再次 start | exclusions 与 reconcile 两项故障/恢复用例确认 watcher/timer 首轮未启动、游标与旧版本保留、第二轮启动成功；desktop-review.log 通过 |
| F8 | 独立 URL 失败状态、统一图片图标、URL version 参数 | 上述真实组件浏览器证据与源码差异；Web 生产构建成功 |

上述前八项用于证明 UX 审查遵守本任务范围，没有新增页面。日志显示新增桌面失败路径 13/13、后端回归 BUILD SUCCESS；桌面全回归日志读取时仍在追加，本报告不据此判定全部测试完成。此表为已读取事实与证据对应，不是最终 Goal 验收。

## 决策

建议 EXP_ACCEPT outcome=pass，validatedRevision 为 review-fixes-code-3ae1050e4646f449。F8 已消除原空白状态并满足本次 uispec 的降级图标、名称、选择与 URL 重试设计；未发现本次新改动造成的体验阻断。运行证据和静态覆盖已分别标明，不将未执行专项写成运行通过。

## State Delta（仅 proposal）

criterionProposal：EXP_ACCEPT/pass，by=/root/exp_accept，evidenceRef=.ai/runtime/results/DISPATCH-20260930-REVIEW-EXP_ACCEPT-647c69.json，validatedRevision=review-fixes-code-3ae1050e4646f449。blockerProposals 为空。未修改 State，未执行 Evaluate，未判定 Goal。

## 风险

本次没有独立重跑构建、后端、MySQL、桌面或浏览器。真实后端 HTTP、Electron 外壳、弱网设备与不同分辨率没有覆盖；同一挂载组件的内容版本切换、Tab/Space/左右键及量化图标对比度没有专项运行证据。其余门禁由对应独立 reviewer 与主线程的当前修订验证决定。

## 下一步

由主线程核对本次 attempt、白名单与代码修订后执行 EXP_ACCEPT Evaluate；若后续修改本组件或相关代码，按新 revision 重新获取有效证据。继续完成其他独立评审和串行集成测试。

## 变更影响

仅追加本文件的 EXP_ACCEPT 部分，并新增本 dispatch 独立结果 JSON；保留原 EXP_DESIGN 内容。未修改业务源码、数据库、配置、State，没有 Git 或共享构建操作。
