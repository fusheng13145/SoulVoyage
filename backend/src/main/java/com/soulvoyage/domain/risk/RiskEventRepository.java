package com.soulvoyage.domain.risk;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RiskEventRepository extends JpaRepository<RiskEventEntity, Long> {
    List<RiskEventEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<RiskEventEntity> findByUserIdAndCreatedAtAfter(Long userId, Instant since);

    List<RiskEventEntity> findByReviewedOrderByCreatedAtAsc(Short reviewed);

    Optional<RiskEventEntity> findByIdAndUserId(Long id, Long userId);

    /** A2 复核队列：reviewed/level 可选过滤，先进先出 */
    @Query("""
            select r from RiskEventEntity r
            where (:rv is null or r.reviewed = :rv)
              and (:lv is null or r.level = :lv)
            order by r.id asc
            """)
    Page<RiskEventEntity> findQueue(@Param("rv") Short reviewed,
                                    @Param("lv") String level,
                                    Pageable pageable);

    /** O3 指标：时间窗内按级别分组计数 [level, count] */
    @Query("select r.level, count(r) from RiskEventEntity r where r.createdAt >= :from group by r.level")
    List<Object[]> countByLevelSince(@Param("from") Instant from);

    /** M12 群体聚合：限定授权成员集、按级别分组计数 [level, count]（个体只进计数不进结果） */
    @Query("select r.level, count(r) from RiskEventEntity r where r.userId in :ids and r.createdAt >= :from group by r.level")
    List<Object[]> countByLevelSinceForUsers(@Param("ids") List<Long> ids, @Param("from") Instant from);
}
