package com.soulvoyage.domain.checkin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MoodCheckInRepository extends JpaRepository<MoodCheckInEntity, Long> {

    Optional<MoodCheckInEntity> findByUserIdAndCheckDate(Long userId, LocalDate checkDate);

    List<MoodCheckInEntity> findByUserIdAndCheckDateBetweenOrderByCheckDateAsc(Long userId, LocalDate from, LocalDate to);

    List<MoodCheckInEntity> findByUserIdOrderByCheckDateDesc(Long userId);

    List<MoodCheckInEntity> findByUserIdOrderByCheckDateAsc(Long userId);

    /** M12 群体聚合（单行）：[去重打卡人数, 打卡人次, 平均 rating, 平均 energy]，只读明文列 */
    @Query("""
            select count(distinct m.userId), count(m), avg(m.rating), avg(m.energy)
            from MoodCheckInEntity m
            where m.userId in :ids and m.checkDate between :from and :to
            """)
    List<Object[]> aggregateForUsers(@Param("ids") List<Long> ids,
                                     @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** M12 群体聚合：16 情绪分布 [emotionCode, count]，按人数降序 */
    @Query("""
            select m.emotionCode, count(m) from MoodCheckInEntity m
            where m.userId in :ids and m.checkDate between :from and :to
            group by m.emotionCode order by count(m) desc, m.emotionCode asc
            """)
    List<Object[]> emotionDistributionForUsers(@Param("ids") List<Long> ids,
                                               @Param("from") LocalDate from, @Param("to") LocalDate to);
}
