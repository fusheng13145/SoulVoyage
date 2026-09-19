package com.soulvoyage.domain.emotion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface EmotionTrajectoryRepository extends JpaRepository<EmotionTrajectoryEntity, Long> {
    List<EmotionTrajectoryEntity> findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(Long userId, LocalDate from, LocalDate to);

    List<EmotionTrajectoryEntity> findByUserIdAndRecordDateAndSourceType(Long userId, LocalDate date, String sourceType);
}
