package com.soulvoyage.domain.export;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface ExportRecordRepository extends JpaRepository<ExportRecordEntity, Long> {

    Optional<ExportRecordEntity> findByFileRef(String fileRef);

    Page<ExportRecordEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<ExportRecordEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Transactional
    @Modifying
    @Query("update ExportRecordEntity e set e.status = 'EXPIRED' "
            + "where e.status = 'PENDING' and e.createdAt < :before")
    int markExpired(@Param("before") Instant before);
}
