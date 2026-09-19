package com.soulvoyage.domain.companion;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CompanionSessionRepository extends JpaRepository<CompanionSessionEntity, Long> {

    Optional<CompanionSessionEntity> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserId(Long userId);

    Optional<CompanionSessionEntity> findByUserIdAndChatDateAndStatus(Long userId, LocalDate chatDate, String status);

    Optional<CompanionSessionEntity> findByUserIdAndChatDateAndSegmentNo(Long userId, LocalDate chatDate, Integer segmentNo);

    List<CompanionSessionEntity> findByUserIdAndChatDateOrderBySegmentNoDesc(Long userId, LocalDate chatDate);

    Page<CompanionSessionEntity> findByUserIdOrderByChatDateDescIdDesc(Long userId, Pageable pageable);

    List<CompanionSessionEntity> findByStatusAndLastTurnAtBefore(String status, Instant cutoff);

    List<CompanionSessionEntity> findByStatus(String status);

    /** 今日全用户回合数（软上限 200 轮/日成本护栏） */
    @Query("""
            SELECT COALESCE(SUM(s.turns), 0) FROM CompanionSessionEntity s
            WHERE s.userId = :userId AND s.chatDate = :date
            """)
    long sumTurnsOnDate(@Param("userId") Long userId, @Param("date") LocalDate date);

    /** 自某业务日起的逐日回合数（防依赖引导与软上限共用；JPQL 不支持 FROM 子查询，聚合后在服务层判定） */
    @Query("""
            SELECT s.chatDate, SUM(s.turns) FROM CompanionSessionEntity s
            WHERE s.userId = :userId AND s.chatDate >= :fromDate
            GROUP BY s.chatDate
            """)
    List<Object[]> sumTurnsByDateSince(@Param("userId") Long userId, @Param("fromDate") LocalDate fromDate);
}
