package com.soulvoyage.domain.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReportRepository extends org.springframework.data.jpa.repository.JpaRepository<ReportEntity, Long>,
        JpaSpecificationExecutor<ReportEntity> {
    Page<ReportEntity> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<ReportEntity> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    List<ReportEntity> findByTaskId(Long taskId);

    List<ReportEntity> findByUserIdAndTypeAndDeletedAtIsNull(Long userId, String type);

    /** L2 反馈 → O3 看板：窗口内各评价档位的报告数（反哺 prompt 迭代） */
    @Query("""
            select r.feedback, count(r) from ReportEntity r
            where r.deletedAt is null and r.feedback is not null and r.createdAt >= :from
            group by r.feedback
            """)
    List<Object[]> countFeedbackSince(@Param("from") Instant from);
}
