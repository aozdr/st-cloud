# CR-03 程序设计

修订：design-v1。仅描述未来修改，未修改程序。

## 方案与职责

保持 `TeamController.copyFiles` → `TeamService.requirePermissions` → `FileService.copyTeamFiles` 调用链。控制器现有批量源检查保持 `view`，目标检查改为 `upload`，中文注释同步表达真实规则。不另建复制服务或权限框架。

`TeamServiceImpl.requirePermissions` 已将 null 节点映射为虚拟根 0。目标普通目录的空间归属、正常状态和目录类型由既有权限解析与 `FileServiceImpl.validateTeamParentPath` 校验。不得把 null 根目录当成免鉴权条件。

## 调用顺序

1. 保留现有登录及接口级限制，读取 spaceId、nodeIds、targetParentId。
2. 遍历所有源，逐一检查 view；任何异常立即返回。
3. 检查目标 upload；失败返回 TEAM_PERMISSION_DENIED。
4. 调用已有事务性 `copyTeamFiles`，执行现有数据写入和事件路径。
5. 复制返回成功后保留既有活动日志。

禁止把检查放在复制之后，禁止在复制每一个源后才继续检查后续源权限。权限判断采用请求检查时的状态；本项不提供对已经进入复制事务的请求进行中途撤销的机制。

## 改动清单与契约

| 位置 | 计划修改 |
|---|---|
| `st-team/.../controller/TeamController.java::copyFiles` | 目标权限字符串及说明；源权限保持不变 |
| `st-team` 相关验证材料（后续阶段） | 覆盖拒绝无副作用、根目录、批量和正常复制 |
| `st-core/.../service/impl/FileServiceImpl.java::copyTeamFiles` | 作为回归观察点，本项无计划业务重写 |

省略号仅表示前述 Java 包路径的公共前缀，不是另一个文件。HTTP API、数据库字段、JSON 类型均不变。错误码沿用现有异常映射，不在本项改造全局错误 HTTP 状态。

## 并发、发布与回退

不增加锁和事务。可独立部署；混合新旧实例期间旧实例仍存在漏洞，因此验收必须在全部承接复制请求的实例升级后进行。回退该修复会恢复越权，出现兼容故障时应暂时关闭复制入口或修复前进，不以绕过 upload 作为降级手段。

## 需求映射与设计核对

R03-1/2 对应权限点与根目录归一；R03-3 对应先完整检查后调用写事务；R03-4 对应后端统一鉴权；R03-5 对应复用现有复制实现。设计核对为主线程自检，未执行运行验证，未编写测试用例。
