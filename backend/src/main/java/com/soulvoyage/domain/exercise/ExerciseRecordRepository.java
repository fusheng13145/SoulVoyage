package com.soulvoyage.domain.exercise;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExerciseRecordRepository extends JpaRepository<ExerciseRecordEntity, Long> {
    List<ExerciseRecordEntity> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);
}
