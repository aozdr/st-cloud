-- ============================================================
-- 团队成员置顶字段（st-team 模块）
-- ============================================================
ALTER TABLE team_member ADD COLUMN is_pinned TINYINT NOT NULL DEFAULT 0 COMMENT '是否置顶：0-否 1-是';