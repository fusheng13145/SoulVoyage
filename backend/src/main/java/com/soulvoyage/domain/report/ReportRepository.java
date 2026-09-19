package com.soulvoyage.domain.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface ReportRepository extends org.springframework.data.jpa.repository.JpaRepository<ReportEntity, Long> {
    Page<ReportEntity> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<ReportEntity> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    java.util.List<ReportEntity> findByTaskId(Long taskId);

    java.util.List<ReportEntity> findByUserIdAndTypeAndDeletedAtIsNull(Long userId, String type);
}
