SET NAMES utf8mb4;
-- ============================================================
-- 新增「edit 编辑文档」权限点（2026-08-15）
-- 团队文件夹权限与分享权限的既有 JSON 行补 edit:true（管理/编辑类角色）；
-- 幂等：仅对不含 edit 的行生效，重复执行不覆盖已配置数据
-- ============================================================

-- 团队文件夹权限：permission=0（管理）/1（编辑）且 JSON 含 view 的行补 edit
UPDATE team_folder_permission
SET permissions = REPLACE(permissions, '"view":true', '"view":true,"edit":true')
WHERE permission IN (0, 1)
  AND permissions IS NOT NULL
  AND permissions LIKE '%"view":true%'
  AND permissions NOT LIKE '%"edit"%';

-- 分享权限：permission=3（编辑）且 JSON 含 view 的行补 edit
UPDATE file_share
SET permissions = REPLACE(permissions, '"view":true', '"view":true,"edit":true')
WHERE permission = 3
  AND permissions IS NOT NULL
  AND permissions LIKE '%"view":true%'
  AND permissions NOT LIKE '%"edit"%';
