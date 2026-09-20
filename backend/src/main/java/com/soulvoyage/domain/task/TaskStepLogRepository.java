package com.soulvoyage.domain.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface TaskStepLogRepository extends JpaRepository<TaskStepLogEntity, Long> {
    List<TaskStepLogEntity> findByTaskIdOrderByStepSeqAscIdAsc(Long taskId);

    /** O3 指标：时间窗内成本相关行 [agentCode, costMs, llmCalls, tokensIn, tokensOut, createdAt]，聚合在 Java 侧完成 */
    @Query("""
            select t.agentCode, t.costMs, t.llmCalls, t.tokensIn, t.tokensOut, t.createdAt
            from TaskStepLogEntity t
            where t.createdAt >= :from
            """)
    List<Object[]> costRowsSince(@Param("from") Instant from);
}
