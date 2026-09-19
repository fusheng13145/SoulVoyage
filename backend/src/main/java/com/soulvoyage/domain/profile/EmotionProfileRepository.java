package com.soulvoyage.domain.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmotionProfileRepository extends JpaRepository<EmotionProfileEntity, Long> {
    Optional<EmotionProfileEntity> findByUserIdAndStatWeek(Long userId, String statWeek);

    List<EmotionProfileEntity> findByUserIdOrderByStatWeekDesc(Long userId);
}
