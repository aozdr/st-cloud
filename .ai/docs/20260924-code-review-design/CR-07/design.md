# CR-07 程序设计

修订：design-v1；主线程自检；未实现。

## 1. 组件与依赖

PreviewServiceImpl 与 ShareServiceImpl 都依赖 st-core，st-share 不依赖 st-preview。将最小的格式识别、有限读入、尺寸检查和缩放编码组件放在 st-core 内，建议名 `ThumbnailRenderer`，只处理输入流与输出 JPEG，不负责用户权限/分享资格；对应限制配置放同模块。两个原服务保留各自鉴权、存储路径和缓存 URL/流职责。不为共享几十行算法引入 st-share→st-preview 依赖或新图像框架。

文件依据：`st-preview/src/main/java/com/stcloud/preview/service/impl/PreviewServiceImpl.java` 的 preview、previewVersion、getThumbnailUrl、generateThumbnail；`st-share/src/main/java/com/stcloud/share/service/impl/ShareServiceImpl.java` 的 streamShareThumbnail、ensureShareThumbnail；`st-web/src/components/preview/PreviewModal.tsx` 及 `st-web/src/lib/utils.ts` 图片分类。

## 2. 配置与预算

建议默认：`stcloud.preview.max-thumbnail-source-bytes=20971520`（20 MiB）、`max-thumbnail-pixels=16000000`（1600 万像素）、`max-thumbnail-concurrent-generations=2`。启动校验全部大于零，数值计算使用 long，宽高异常直接拒绝。这些是设计默认值，不是压测结论；部署需结合 JVM heap 调低/调整，不能仅靠“压缩源小”判断内存安全。

单图 1600 万像素按常见 4 字节像素仅主缓冲就约 64 MB，另有 reader/颜色转换/输出缓冲；两路并发需额外预算。信号量只覆盖首次生成路径，在读取大源文件之前获取，无许可立即返回暂不可生成；不在内存排无界任务。命中已生成预览对象不占解码许可。不同实例允许各自执行，不增加分布式锁。

## 3. 生成算法

1. 调用方完成节点/版本/分享权限校验，并判断服务端格式白名单；不支持格式直接返回不可生成，不打开原对象解码。
2. 检查已有缩略图。对象 HEAD 仅 404 表示不存在，其他存储异常按错误返回，不误当存在或缓存为空。
3. 获取解码许可，读取源对象元数据大小：当前/历史版本使用对应对象 HEAD，不能用当前节点大小替代历史版本大小。HEAD 超限直接返回；元数据缺失时仍执行流硬限额。
4. 将源流最多 maxBytes+1 字节拷贝到服务端受控临时文件；超过立即关闭并删本次临时文件。不用 readAllBytes，也不依赖可伪造的后缀/声明大小。对象变化或元数据不符仍由读入上限兜底。
5. 用 FileImageInputStream/ImageReader 读取第 0 帧宽高与实际格式名；reader 不支持、格式不在白名单、尺寸非正、乘积超过上限则停止，尚未调用 reader.read。读取头部也受源文件上限约束。
6. 确认尺寸安全后可设置 sourceSubsampling 降低工作量，再 reader.read(0, param)；缩放到 sm=150、md=400、lg=1200 边长内，宽高至少 1。GIF 缩略图仅第一帧，主图动画沿用原流。
7. 生成 JPEG 并确认 ImageIO.write 返回成功；仅成功编码后 PUT 到现有 preview bucket 键。Reader/图像输入流/原流/Graphics/临时文件/信号量在 finally 释放，不捕获 OOM 假装普通成功。

缓存键沿用当前节点和历史版本命名空间，不在本项重构所有缩略图缓存失效策略。仅写完整对象，不先创建空占位 JPEG。相同键并发完成允许最后一个等价结果覆盖。

## 4. API 与显示分派

`GET /api/preview/{nodeId}/thumbnail` 保持 Result<String>。对白名单外/超限/无 decoder 使用现有 BAD_REQUEST 并给出明确“无法生成缩略图”说明；真实存储失败沿用 STORAGE_SERVICE_ERROR。前端无需依赖中文文本区分，导航资源任一失败显示图标，不循环请求。

preview/previewVersion 区分 IMAGE_PREVIEW_TYPES 与 THUMBNAIL_TYPES：WebP/SVG 在已完成权限与版本归属校验后返回 image + 对应原对象的现有授权 URL；JPG 等尝试缩略图，受限时返回 unsupported，不自动返回大原图。版本不能回退成当前节点内容。download/editor token 以及普通文件流入口保持既有授权，不因渲染器共享而绕过。

PreviewModal 普通 WebP/SVG 主图改调用已有预览 API 获取授权原图 URL；GIF 保留原流。其他格式缩略图失败时显示图标与“暂无法预览，可下载原文件”，下载动作仍走原下载权限。全局图片分类和历史文件类型清单不为此收窄。

分享缩略图只支持服务端白名单，失败不得回退原文件字节或原文件 URL。分享主图继续既有 stream，按 CR-05 计资格；胶卷 WebP/SVG 直接图标，受支持格式通过缩略图接口获取，onError 转图标并停止同次重试。所有提取码/验证码/下载开关检查先于渲染。

SVG 仅允许 `<img>` 消费，不通过 innerHTML/object/iframe 展示；不新增未经鉴权的原图公开地址。本项不引入 SVG 服务端解析库。

## 5. 发布、回退与映射

先发布能处理缩略图失败及字符串 URL 原图分派的前端，再发布能力收窄/资源保护后端；可同窗口整体切换。服务端限制收紧可能使部分旧图片只显示图标，属设计降级。回退前端保留后端限制，不为兼容 UI 恢复无界解码。

R07-1/2→格式分派；R07-3/7→有限文件读入、头部检查和信号量；R07-4→uispec；R07-5→共享渲染器；R07-6→分享边界。新组件与配置仅为设计，没有新源码文件、依赖或测试。本轮只做静态核对，不承诺资源参数已通过运行验证。
