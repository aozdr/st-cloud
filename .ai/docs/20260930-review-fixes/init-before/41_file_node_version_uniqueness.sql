SET NAMES utf8mb4;
-- ============================================================
-- 41_file_node_version_uniqueness.sql
-- P1-02/P1-03：有效同级节点与同文件版本号的数据库最终约束。
-- 迁移前只读预检；发现存量冲突时由 ALTER/ADD UNIQUE 失败并停止，禁止自动删除、改名或合并。
-- ============================================================

-- 只读预检：结果必须由人工处理后才能成功建立唯一键。
SELECT tenant_id,
       parent_id,
       CASE WHEN space_id IS NOT NULL AND space_id > 0
            THEN CONCAT('T:', space_id) ELSE CONCAT('P:', owner_id) END AS scope_key,
       name,
       COUNT(*) AS duplicate_count
FROM file_node
WHERE deleted = 0 AND status = 0
GROUP BY tenant_id, parent_id,
         CASE WHEN space_id IS NOT NULL AND space_id > 0
              THEN CONCAT('T:', space_id) ELSE CONCAT('P:', owner_id) END,
         name
HAVING COUNT(*) > 1;

SELECT tenant_id, file_node_id, version_num, COUNT(*) AS duplicate_count
FROM file_version
GROUP BY tenant_id, file_node_id, version_num
HAVING COUNT(*) > 1;

-- 下面的 DDL 故意不包含任何冲突清理逻辑；若预检有结果，ADD UNIQUE 会失败并保留原数据。
ALTER TABLE file_node
    ADD COLUMN active_scope_key VARCHAR(80)
        GENERATED ALWAYS AS (
            CASE WHEN space_id IS NOT NULL AND space_id > 0
                 THEN CONCAT('T:', space_id) ELSE CONCAT('P:', owner_id) END
        ) STORED;

ALTER TABLE file_node
    ADD COLUMN active_name VARCHAR(300)
        GENERATED ALWAYS AS (
            CASE WHEN deleted = 0 AND status = 0
                 THEN name ELSE NULL END
        ) STORED;

ALTER TABLE file_node
    ADD UNIQUE KEY uk_file_node_active (tenant_id, parent_id, active_scope_key, active_name);

ALTER TABLE file_version
    ADD UNIQUE KEY uk_file_version_node_num (tenant_id, file_node_id, version_num);
