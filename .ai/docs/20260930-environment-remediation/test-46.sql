SET NAMES utf8mb4;
-- 仅独立空测试库执行：构造缺列分支，绝不修改开发/生产用户。
ALTER TABLE stcloud_env_46_20260930.sys_user DROP COLUMN security_version;
