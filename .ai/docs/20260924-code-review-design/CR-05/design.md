# CR-05 程序设计

修订：design-v1；主线程自检；仅设计。

## 代码依据

`st-share/src/main/java/com/stcloud/share/service/impl/ShareServiceImpl.java`：`getDownloadUrl` 已执行条件 UPDATE；`streamShareFile` 在 streamCompleted 后 UPDATE；`streamShareThumbnail` 无计数。`st-web/src/components/preview/PreviewModal.tsx` 的分享主图使用 stream 接口，因此属于资格消耗路径。

## 结构与数据流

在 ShareServiceImpl 内收敛 `consumeDownloadPermit(share)` 或同等小型私有方法，由流接口与 URL 接口调用，复用 `FileShareMapper`。不新增 Redis 锁、分布式锁或长事务。

流接口顺序：validateShareAccess → 检查下载权限与目标节点/上传完成/分享边界 → 打开 S3 输入流 → 原子占位 → 设置成功响应头 → 获取输出流并限速发送 → 关闭资源。

条件更新语义：按分享主键及既有逻辑删除/租户边界定位；有效状态、下载开关及尚未过期条件也纳入最终更新；`download_limit IS NULL OR download_count < download_limit` 时 `download_count = download_count + 1`。返回 1 才可输出。权限集和口令已在入口校验，不在数据库锁内执行验证码或 S3 调用。

分享鉴权与授予之间发生口令/权限修改的在途请求按鉴权时状态处理；最终 SQL 复核状态/过期/下载开关/限额。这里不承诺撤回已经授权的流或已发 URL。时间条件采用服务端统一时间来源，所有实例须保持时钟同步。

URL 接口先完成全部检查并生成尚未对外返回的预签名 URL，再占位；占位失败不得返回 URL，也不得记录 URL 日志。生成签名失败不计数。签名生成不处于 DB 事务。

## 异常、资源和事务

- 占位失败：关闭已打开的源流，抛现有 SHARE_ACCESS_DENIED；未调用 getOutputStream、未设置文件 Content-Length，不把业务错误包装成文件响应。
- 源流打开失败：返回现有存储错误，无次数变化。
- 成功占位后失败：保留计数，记录中断原因；响应已提交时不得追加 JSON 错误体。
- 删除流后置计数及 streamCompleted 控制逻辑，避免双扣。限速实现保留。
- 单条 UPDATE 独立提交，无包围外部网络调用的 @Transactional。只允许在提交完成后开始输出文件。
- 快速限额检查可保留以减少无效源流打开，但不能替代最终 UPDATE。

## 契约与发布

表结构和请求参数不变；download_count 的意义明确为已授予次数。现有预签名 URL 的存储端复用限制不在该接口内可控。上线需所有分享实例切换，混合旧实例仍可能绕过。发生问题时关闭限额下载或修复前进；不通过恢复后置扣减回退。

## 计划修改与映射

ShareServiceImpl 的两个下载入口和计数方法为核心；必要时 FileShareMapper 增加明确原子 SQL；PreviewModal 仅同步计数注释，不新加计次 UI。R05-1 对应前置校验，R05-2/3/5 对应共享原子占位，R05-4 对应缩略图与流分离，R05-6 对应发布说明的保证范围。未来验证需观察响应首字节而不只检查计数；本轮不编写或执行验证用例。
