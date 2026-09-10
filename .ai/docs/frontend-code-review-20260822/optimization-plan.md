# 星云盘前端评审后端核查与优化计划

> 日期：2026-08-22 / 依据：《code-review.md》及 10 份分模块报告
> 方法：带着评审「待确认项」逐条核对后端源码定版，再输出分批实施计划

## 一、后端核实结论（待确认项定版）

| # | 评审疑点 | 后端事实（出处） | 定版结论 |
|---|---|---|---|
| 1 | D3-2 refresh 字段 `token` vs `accessToken` | LoginResponse 只有 `token`+`refreshToken`（AuthService.buildLoginResponse）；refresh 为轮换制，旧值存 Redis 校验后作废（AuthService:155-184） | **desktop 读 accessToken 是必现 bug**；轮换制坐实 W2-5 多标签并发刷新互踢 |
| 2 | W1-2/W6-3 legacy 权限语义 | 权威实现 FolderPermissionService:173-199：`0=全部, 1=编辑者集, 2={view}, 其它=空集`；legacyLevelOf：manage→0，任一内容操作→1，仅view→2 | 前端 `permissions.ts` 反向映射**正确**；错在正向 `legacyPermissionFromPerms`（产出错误的 1/2/3）与 ShareAccessPage 的 `>=1/>=3` 比较方向（把全权者判为不可下载）。均为前端 bug |
| 3 | W2-1/D3-4 秒传弱指纹有无后端兜底 | checkInstantUpload 仅按 `tenantId+MD5` 匹配 file_object，**不比对 fileSize**，完全信任客户端哈希，无二次校验（UploadServiceImpl:86-106） | 弱指纹=真实静默错文件风险，P0 维持，必须前后端联合修 |
| 4 | W6-1 提取码有无防爆破 | validateShareAccess 明文 `equals` 比较，无频控无锁定（ShareServiceImpl:553-564）；密码明文存储（FileShare 注释自认） | 联合缺陷坐实：4 位码 ≈92 万组合可在线枚举 |
| 5 | D1-1 关 webSecurity 的替代方案 | CORS 白名单已配置化 `stcloud.cors.allowed-origins`（SecurityConfig:76-94，支持 originPatterns） | 正解存在：白名单加桌面端 Origin 即可，无需关 webSecurity |
| 6 | W1-8 stream `?token=` 兜底 | JwtAuthenticationFilter:193 显式支持 query token；download-token 为 5 分钟短期令牌（FileController:267） | 设计成立，维持 P2；真正问题只在 FileThumbnail 把 7 天 access token 放进 URL |
| 7 | W1-4 团队文件走 `/file/*` 下载 | download-token 签发仅验个人权限码 `file:download/preview`（FileController:268-276），团队节点归属依赖 dataScope 兜底 | 功能可用但契约脆弱；列入计划改调团队端点（低风险清理项） |

## 二、分批优化计划

### 批次 A：安全加固（必修，约 3 人日）

| 项 | 内容 | 涉及文件 | 验收标准 |
|---|---|---|---|
| A1 [P0] 移除 webSecurity:false | 后端 allowed-origins 增加 `app://*`；desktop main.ts 删该行；如仍拦截则主进程 net.fetch 代理兜底 | SecurityConfig.java、application*.yml、st-desktop/src/main.ts | 桌面端登录/上传/下载/同步全链路回归，DevTools 无 CORS 报错 |
| A2 [P0] 秒传指纹全量化 | web calculateMd5 改 FileReader 分块流式全量 hash；desktop doUpload 改用 calculateFileMd5；后端匹配键增加 fileSize（findByTenantAndMd5AndSize），**不加列零迁移** | useUpload.tsx、md5.ts 调用点、UploadServiceImpl/UploadCheckRequest | 双端同文件指纹一致；构造同前缀不同内容大文件不再误秒传；mvn test 全绿 |
| A3 [P1] 提取码强化 | 前端 crypto.getRandomValues 生成 6 位；后端 validateShareAccess 增加 Redis 双维度频控（IP + 设备指纹，规则见下方 A3 附）；密码 BCrypt 兼容升级；editor-config 改 POST body。**分享链接继续携带 ?pwd=（已拍板）** | ShareDialog.tsx、ShareServiceImpl.java、editor.ts、ShareController.java、新增 ShareAccessGuard 组件 | 枚举测试触发 IP 封禁与设备封禁；存量链接不受影响 |
| A4 [P1] XSS 面收敛替代凭证搬迁 | index.html 加 CSP meta；修 W1-3（分类页改 sanitizeHighlight）、W3-2（缩略图兜底改一次性 token） | index.html、fileSource.ts、FileThumbnail.tsx | CSP 无阻断性违规；缩略图 URL 中不再出现长效 token |
| A5 [P1] 后台入口 | AdminPage 初始 tab 取首个有权 tab，零权限显示无权页；路由加 RequireAnyPermission | AdminPage.tsx、App.tsx | 零权限用户进 /admin 不触发任何管理接口请求 |

### 批次 B：数据正确性（必修，约 3 人日）

| 项 | 内容 | 涉及文件 | 验收标准 |
|---|---|---|---|
| B1 [P1] 权限语义单源化 | 重写 legacyPermissionFromPerms 对齐 legacyLevelOf；ShareAccessPage 删除数字比较，统一经 legacyToPermissions 展开 | lib/permissions.ts、ShareAccessPage.tsx | 往返映射幂等单测；旧值 0/1/2 分享在前端显隐与后端一致 |
| B2 [P1] 同步边界四连 | 冲突副本上传携带原子目录；下载 .part+rename；resetSyncData 按 rootId 清理；本地删除前校验 failCount==0 且 mtime 未变 | sync-engine.ts、database.ts | 四场景集成测试（临时目录+mock API）全绿 |
| B3 [P1] 上传健壮性 | chunk-url 轮询加上限退避（web+desktop）；Web 复用 /file/upload/status 实现断点续传重试；Electron addFilePaths 透传 spaceId | useUpload.tsx、upload-manager.ts、preload/ipc-handlers | 掐断网络重试不空转；续传跳过已传片；团队空间桌面上传落点正确 |
| B4 [P1] 刷新令牌修正 | desktop accessToken→token 并回写渲染层；web 多标签页 Web Locks 单飞刷新 | st-desktop/api-client.ts、store/auth.ts | 双标签并发只发一次刷新；桌面端挂机过 TTL 自愈 |

### 批次 C：稳定性与性能（随迭代排入）

C1 sql.js persist 防抖合并（500ms 窗口+退出 flush+进度字段降频落库）｜C2 FileBrowser 请求序号防竞态 + PreviewModal 取消令牌（复用 TextEditorPage 范本）｜C3 缩略图 URL 模块级 LRU｜C5 SW 缓存策略收缩（去 status 0、限定公开端点）｜C6 登录跳转带 redirect。

### 批次 D：清理项（顺手处理）

双格式化器合一、ShareManagePage prettier、setupTaskUpdateForwarding 死代码删除、PERMISSION_KEYS 注释修正、忽略清单补 node_modules、eslint+tsc 纳入提交门禁。

## 三、数据库与兼容说明

- A2 推荐**零 schema 变更**方案（匹配键加 fileSize 属查询逻辑变更）；若评审要求加 md5_algorithm 列，则走 docker/mysql/init 新增脚本 + H2 schema 同步 + compare-schema.ps1 门禁。
- A3 密码 BCrypt 兼容旧明文：先 equals 后 matches，命中即升级重写，无批量迁移脚本需求。
- 所有 API 变更（editor-config 改 POST、status 接口复用）向后兼容：旧客户端路径保留一个版本周期。

## 四、决策点拍板结果（2026-08-22）

1. 分享链接**继续携带 `?pwd=`** ✅ 已并入 A3
2. HttpOnly Cookie **暂不做**，A4 以 CSP+XSS 收敛为终态
3. 团队下载端点切换**暂不做**，W1-4 保留为备忘项

### A3 附：提取码防爆破封禁设计（Redis）

**封禁规则（2026-08-22 二次修订，最终版）**
- 正常态：同维度（IP 或设备）**连续错 10 次** → 封禁该维度 **5 小时**
- 封禁期内：**所有访问请求直接拦截，不做任何密码校验、不读分享数据**，仅执行两个动作——① 连续请求计数 +1；② 计数每满 **5 次** → 封禁到期时间刷新为「当前 + 5 小时」（可无限续期）
- 拦截响应与「提取码错误」的普通失败**完全一致**（同文案同状态码），攻击者无法区分被封与猜错，也无法用任何输入探测正确提取码
- 成功访问即清空失败计数（仅正常态可能发生）；等封禁自然过期后恢复走正常校验逻辑
- 实现上作为 validateShareAccess 前置守卫：命中 ban 键走快路径（INCR+判断续期+抛统一异常），其余逻辑短路

**Redis 结构**
- 失败计数：`share:fail:ip:{ip}` / `share:fail:dev:{hash}`（INCR，24h 惰性过期，成功清零）
- 封禁键：`share:ban:ip:{ip}` / `share:ban:dev:{hash}`（SET EX 18000，value 存拦截期请求计数；每 +5 重置 EXPIRE 18000）

**双维度键：IP 之外加「设备指纹」对抗 VPN 换 IP**

| 层 | 信号 | 获取方式 | 强度 | 成本/风险 |
|---|---|---|---|---|
| L1 | IP | X-Forwarded-For 首个可信段 | 换 VPN 即变 | 零改动；NAT 办公网误伤 |
| L2 | 设备ID | 匿名页首访生成 UUID 写 localStorage+Cookie 双写 | 清存储才失效，普通用户不清 | 极低；无痕模式失效 |
| L3 | 环境簇哈希 | `SHA1(deviceId \| UA \| Accept-Language \| 屏幕宽高\|时区)` 由前端一次性提交 | 无痕+换IP 后仍部分稳定 | 低；可伪造但伪造者需持续构造 |
| L4 | 登录态 userId | 已登录用户直接绑账号 | 最强 | 仅覆盖登录场景 |
| L5（远期） | TLS JA3 / Canvas/WebGL 指纹 | 网关层 / FingerprintJS 类库 | 高 | 有隐私合规顾虑，本期不做 |

推荐实现取 **L1+L2+L3 组合键**：`devHash = SHA1(uuid + ua + lang + screen)`，服务端对 `ip` 与 `devHash` 两个桶独立计数、任一触线即封对应维度；封禁期内命中任一 `share:ban:*` 以与普通失败一致的响应拒绝（不返回 423 等可区分信号）。Electron 桌面壳可在主进程补 OS machine-id 注入 header 作为 L3 加强项（可选）。

诚实边界：换 VPN + 无痕 + 清存储仍可绕过——本方案的目标是把枚举成本从「零」抬到「每几次尝试就要重建环境」，配合失败速率异常告警（如单 IP 每分钟 >20 次触发日志告警）已足够实用；若后续仍有滥用再加验证码阶梯。

## 五、执行门禁

每批完成后：`mvn test` 全绿 + `cd st-web && npm run build` + 桌面端 `npm run lint`(tsc) 通过；涉及 DB 变更另跑 compare-schema.ps1。验收对照各分报告对应问题编号逐项销项。
