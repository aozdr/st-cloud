# 影响核对
涉及既有 CR-01～07 和 DB-ID 测试；首批仅桌面同步夹具，无 API、数据库及生产 UI 修改。后续前端验收沿用既有 UI 约定，不增加设计。保留所有已有工作区修改。

影响补充：Refresh随机jti仅改变新令牌唯一性，旧令牌验证兼容；TeamSpacePage仅成员错误重试状态，既有接口不变。涉及UI，EXP_DESIGN/EXP_ACCEPT改为适用。
