package com.soulvoyage.domain.profile;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmotionProfileRepository extends JpaRepository<EmotionProfileEntity, Long> {
    Optional<EmotionProfileEntity> findByUserIdAndStatWeek(Long userId, String statWeek);

    List<EmotionProfileEntity> findByUserIdOrderByStatWeekDesc(Long userId);

    /** O2：近 N 周参数，DB 层 limit */
    List<EmotionProfileEntity> findByUserIdOrderByStatWeekDesc(Long userId, Pageable pageable);

    List<EmotionProfileEntity> findByUserIdAndStatWeekIn(Long userId, Collection<String> statWeeks);
}
