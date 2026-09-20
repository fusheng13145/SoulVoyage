package com.soulvoyage.domain.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentMessageRepository extends JpaRepository<AgentMessageEntity, Long> {
    List<AgentMessageEntity> findByTaskIdOrderByStepSeqAscIdAsc(Long taskId);

    /** 技术债 3：按属主+产出方+类型直查，不再线性扫密文逐条试解 */
    Optional<AgentMessageEntity> findFirstByTaskIdAndFromAgentAndMsgTypeOrderByIdAsc(
            Long taskId, String fromAgent, String msgType);
}
