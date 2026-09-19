package com.soulvoyage.domain.crisis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CrisisLifecycleRepository extends JpaRepository<CrisisLifecycleEntity, Long> {
    List<CrisisLifecycleEntity> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    /** 本轮危机链内"进入 CRISIS"的次数：上次回到 NORMAL 之后的迁移数（不含窗口内 HIGH_REFRESH 续时） */
    @Query("""
            select count(c) from CrisisLifecycleEntity c
            where c.userId = :userId and c.toState = 'CRISIS' and c.fromState <> 'CRISIS'
              and c.createdAt > coalesce(
                    (select max(n.createdAt) from CrisisLifecycleEntity n
                      where n.userId = :userId and n.toState = 'NORMAL'),
                    :epoch)
            """)
    long countEntriesSinceLastNormal(@Param("userId") long userId, @Param("epoch") Instant epoch);
}
