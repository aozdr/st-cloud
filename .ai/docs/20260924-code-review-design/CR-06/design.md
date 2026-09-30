# CR-06 程序设计

修订：design-v1；设计完成，未实现。

## 文件与现有契约

- `st-search/src/main/java/com/stcloud/search/service/impl/TeamSearchServiceImpl.java`：随机密钥字段、requireCursorSecret、createCursor、parseCursor。
- `st-search/src/main/resources/application.yml`：现有环境变量映射。
- `docker/.env.example`：已有注释形式的密钥示例。
- `docker/docker-compose.yml` 及实际启动配置：核对环境变量是否进入应用进程，不能仅在宿主 .env 定义而未注入容器。
- `st-search/src/test/java/com/stcloud/search/service/impl/TeamSearchServiceTest.java`：当前通过反射设置 cursorSecret，存在空密钥场景，需要在后续验证阶段更新预期。

## 初始化设计

使用 Spring 注入完成后的初始化钩子解析配置一次。属性优先，环境变量作为现有表达式的 fallback；删除请求期 System.getenv 重读和随机生成。null/isBlank 抛启动异常，消息只含配置名及“必须配置”，不打印内容。

初始化后 createCursor/parseCursor 仅使用同一确定值。requireCursorSecret 保留防御性空值检查，以免直接构造对象或测试绕过初始化时重新走随机路径。密钥非空时不擅自 trim 改写实际字节；部署中保持所有实例字节一致。

本项不新增与存量密钥长度相关的拒绝门槛；运维说明建议用密码学随机源生成至少 32 字节熵的值。生产秘密不写入示例文件，测试固定值只能放在测试配置。

## 分页行为

保留 HmacSHA256、Base64URL 规范性校验、恒定时间签名比较、游标格式版本 1 及默认 600 秒 TTL。密钥共享只解决验签一致性，不承诺搜索结果快照隔离或 ES 索引不变化。

## 发布与回退

1. 部署平台生成并分发共享秘密，确认应用实际取得环境变量，不输出值。
2. 全实例统一配置；现有随机游标可能需要用户重新搜索一次。
3. 发布启动强校验代码；健康检查通过后接流量。普通滚动发布不轮换密钥。
4. 回退程序时保留共享秘密，避免旧代码重新随机回退。

需轮换密钥时安排单独变更窗口，接受分页重新开始，不承诺新旧密钥混用期间可跨实例分页。

## 需求映射与验证边界

R06-1/2/3 对应初始化与显式测试配置；R06-4 对应保留游标协议；R06-5/6 对应发布和秘密管理。未来验证覆盖真实 Spring 初始化及两实例互通，不能只直接 new 服务验证。此轮仅核对源码和配置文件，未修改或运行测试。
