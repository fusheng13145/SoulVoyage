package com.soulvoyage.domain.notify;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    Page<NotificationEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<NotificationEntity> findByUserIdAndDedupKey(Long userId, String dedupKey);

    long countByUserIdAndReadAtIsNull(Long userId);

    /** 频率护栏：单人每日站内通知 ≤ 2 条（G5） */
    long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, Instant since);

    List<NotificationEntity> findByKindAndReadAtIsNull(String kind);

    List<NotificationEntity> findByUserIdAndReadAtIsNull(Long userId);
}
