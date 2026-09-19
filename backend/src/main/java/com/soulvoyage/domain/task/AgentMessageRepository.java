package com.soulvoyage.domain.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentMessageRepository extends JpaRepository<AgentMessageEntity, Long> {
    List<AgentMessageEntity> findByTaskIdOrderByStepSeqAscIdAsc(Long taskId);
}
