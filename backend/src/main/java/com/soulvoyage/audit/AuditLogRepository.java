package com.soulvoyage.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {
    Optional<AuditLogEntity> findFirstByOrderByIdDesc();

    /** A5 验链：按写入序全量重放 */
    List<AuditLogEntity> findAllByOrderByIdAsc();

    /** A5 审计查询：属主/动作可选过滤，新→旧 */
    @Query("""
            select a from AuditLogEntity a
            where (:uid is null or a.userId = :uid)
              and (:ac is null or a.action = :ac)
            order by a.id desc
            """)
    Page<AuditLogEntity> findForAdmin(@Param("uid") Long userId,
                                      @Param("ac") String action,
                                      Pageable pageable);
}
