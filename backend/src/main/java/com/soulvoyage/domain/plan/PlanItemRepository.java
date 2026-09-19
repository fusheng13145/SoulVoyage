package com.soulvoyage.domain.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PlanItemRepository extends JpaRepository<PlanItemEntity, Long> {

    List<PlanItemEntity> findByPlanIdOrderBySeqAsc(Long planId);

    List<PlanItemEntity> findByPlanIdAndScheduledDateOrderBySeqAsc(Long planId, LocalDate date);

    Optional<PlanItemEntity> findByPlanIdAndSeq(Long planId, Integer seq);
}
