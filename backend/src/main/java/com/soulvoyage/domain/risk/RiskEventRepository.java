package com.soulvoyage.domain.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RiskEventRepository extends JpaRepository<RiskEventEntity, Long> {
    List<RiskEventEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
}
