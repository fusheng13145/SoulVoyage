package com.soulvoyage.domain.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RiskEventRepository extends JpaRepository<RiskEventEntity, Long> {
    List<RiskEventEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<RiskEventEntity> findByUserIdAndCreatedAtAfter(Long userId, Instant since);

    List<RiskEventEntity> findByReviewedOrderByCreatedAtAsc(Short reviewed);

    Optional<RiskEventEntity> findByIdAndUserId(Long id, Long userId);
}
