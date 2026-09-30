# 变更报告
本轮继续已批准整改，未增加业务范围。
1. 补st-api/LargeIdContractTest：三组大ID逐字段验证生产DTO/Jackson响应和请求。
2. 补ThumbnailRendererTest：遗漏阈值使用既有安全默认，非法阈值失败。
3. 补桌面sync-cursor测试：三组root/node/cursor逐字贯穿实际引擎请求和消费。
4. NgramSearchIntegrationTest改显式test.es.port，任务隔离ES创建/清理流水线并关闭transport；避免默认连接并删除共享开发索引。
5. loop-state.schema.json仅增加EXP_DESIGN单人授权枚举，与协议/loopctl reviewer门禁一致；正例、缺criterion、错task、未知criterion四组验证。未降低依赖/证据门禁。
6. 新TASK/State及本目录证据、浏览器三组ID矩阵和最终报告。初始化history的递归全State快照压缩为taskId/scale/status/revision，保留实际事件身份；没有改变历史State。

续接前已存在的必要生产修复现有真实验证：MOVE/RENAME源缺失目标成功后才清理旧映射；keep_both保留起始mtime、两份成功才确认及完成副本复用；Refresh随机jti防同秒双CAS；外部协作关闭立即撤权；成员保存失败保留选择和重试；胶卷文件名与主图一次显式重试。代码没有因本轮夹具问题再次改动。

API与数据：无新增契约/DDL；兼容旧预设角色0/1/2，大自定义角色旧Integer拒绝；回退保留BIGINT。旧无版本JWT须重新登录，混部仅验签旧实例未排空前不能宣称撤权保证。

验证见testreport.md。未提交、推送或部署，保留原有工作区改动。
