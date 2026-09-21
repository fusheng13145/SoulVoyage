package com.soulvoyage.domain.emotion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface EmotionTrajectoryRepository extends JpaRepository<EmotionTrajectoryEntity, Long> {
    List<EmotionTrajectoryEntity> findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(Long userId, LocalDate from, LocalDate to);

    List<EmotionTrajectoryEntity> findByUserIdAndRecordDateAndSourceType(Long userId, LocalDate date, String sourceType);

    /** M12 群体聚合：逐日 [recordDate, 平均效价, 去重贡献人数]（贡献人数供小样本日抑制） */
    @Query("""
            select t.recordDate, avg(t.valence), count(distinct t.userId)
            from EmotionTrajectoryEntity t
            where t.userId in :ids and t.recordDate between :from and :to
            group by t.recordDate order by t.recordDate asc
            """)
    List<Object[]> dailyValenceForUsers(@Param("ids") List<Long> ids,
                                        @Param("from") LocalDate from, @Param("to") LocalDate to);
}
