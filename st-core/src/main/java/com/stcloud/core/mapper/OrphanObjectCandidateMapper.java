package com.stcloud.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.core.entity.OrphanObjectCandidate;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrphanObjectCandidateMapper extends BaseMapper<OrphanObjectCandidate> {

    @Insert("INSERT IGNORE INTO file_orphan_candidate "
            + "(tenant_id, md5, storage_path, active_uploads, status, candidate_at, last_active_at, created_at, updated_at, deleted) "
            + "VALUES (#{tenantId}, #{md5}, #{storagePath}, 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)")
    int insertIfAbsent(OrphanObjectCandidate candidate);

    @Select("SELECT * FROM file_orphan_candidate WHERE tenant_id = #{tenantId} "
            + "AND storage_path = #{storagePath} AND deleted = 0 LIMIT 1")
    OrphanObjectCandidate selectByTenantAndPath(@Param("tenantId") Long tenantId,
                                                @Param("storagePath") String storagePath);

    /** 领取 deleting 状态前禁止新上传登记，避免回收和新写入交叉。 */
    @Update("UPDATE file_orphan_candidate SET active_uploads = active_uploads + 1, status = 0, "
            + "last_active_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status <> 2 AND deleted = 0")
    int incrementActive(@Param("id") Long id);

    @Update("UPDATE file_orphan_candidate SET active_uploads = CASE WHEN active_uploads > 0 "
            + "THEN active_uploads - 1 ELSE 0 END, last_active_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND deleted = 0")
    int decrementActive(@Param("id") Long id);

    @Update("UPDATE file_orphan_candidate SET status = 1, candidate_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND active_uploads = 0 AND status <> 2 AND deleted = 0")
    int markPendingIfInactive(@Param("id") Long id);

    @Select("SELECT * FROM file_orphan_candidate WHERE status = 1 AND active_uploads = 0 "
            + "AND candidate_at <= #{cutoff} AND deleted = 0 ORDER BY id LIMIT #{limit}")
    List<OrphanObjectCandidate> selectDue(@Param("cutoff") LocalDateTime cutoff,
                                          @Param("limit") int limit);

    @Update("UPDATE file_orphan_candidate SET status = 2, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 1 AND active_uploads = 0 AND candidate_at <= #{cutoff} AND deleted = 0")
    int claimForDelete(@Param("id") Long id, @Param("cutoff") LocalDateTime cutoff);

    @Update("UPDATE file_orphan_candidate SET status = 1, candidate_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 2 AND deleted = 0")
    int releaseDelete(@Param("id") Long id);

    @Delete("DELETE FROM file_orphan_candidate WHERE id = #{id}")
    int deleteCandidate(@Param("id") Long id);
}
