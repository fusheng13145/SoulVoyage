package com.soulvoyage.domain.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskStepLogRepository extends JpaRepository<TaskStepLogEntity, Long> {
    List<TaskStepLogEntity> findByTaskIdOrderByStepSeqAscIdAsc(Long taskId);
}
