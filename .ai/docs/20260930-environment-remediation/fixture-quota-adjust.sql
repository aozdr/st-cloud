SET NAMES utf8mb4;
-- 仅本轮新建0B浏览器fixture，按精确账号/空间/原配额CAS缩减测试分配；不删除行或权限。
START TRANSACTION;
UPDATE team_space s JOIN sys_user u ON u.id=s.owner_id AND u.tenant_id=s.tenant_id
SET s.storage_quota=1048576
WHERE s.tenant_id=1 AND s.deleted=0 AND s.storage_used=0 AND s.storage_quota=10737418240
AND NOT EXISTS (SELECT 1 FROM file_node n WHERE n.space_id=s.id AND n.node_type=1)
AND ((s.id=2105477777673355265 AND u.id=2105477769045671938 AND u.username='env_exp_muovzh4w3ff77d' AND s.space_name='EXP团队-env_exp_muovzh4w3ff77d')
OR (s.id=2105477988504240129 AND u.id=2105477981680107521 AND u.username='env_exp_muow0kkf12c526' AND s.space_name='EXP团队-env_exp_muow0kkf12c526'));
SELECT ROW_COUNT() AS adjusted_fixture_rows;
COMMIT;
SELECT s.id,s.space_name,u.username,s.storage_used,s.storage_quota,s.deleted
FROM team_space s JOIN sys_user u ON u.id=s.owner_id
WHERE s.id IN (2105477777673355265,2105477988504240129);
