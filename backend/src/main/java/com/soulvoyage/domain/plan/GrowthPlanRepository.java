package com.soulvoyage.domain.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface GrowthPlanRepository extends JpaRepository<GrowthPlanEntity, Long> {

    List<GrowthPlanEntity> findByUserIdAndStatusOrderByEndDateAsc(Long userId, String status);

    List<GrowthPlanEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 到期且未生成小结的活跃计划（定时任务扫描） */
    List<GrowthPlanEntity> findByStatusAndEndDateBefore(String status, LocalDate date);
}
