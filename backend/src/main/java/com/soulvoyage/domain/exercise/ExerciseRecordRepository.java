package com.soulvoyage.domain.exercise;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExerciseRecordRepository extends JpaRepository<ExerciseRecordEntity, Long> {
    List<ExerciseRecordEntity> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    /** C4：同日同练习覆盖式打卡（UK(user,exercise,check_date)） */
    Optional<ExerciseRecordEntity> findByUserIdAndExerciseIdAndCheckDate(Long userId, Long exerciseId,
                                                                         LocalDate checkDate);

    @org.springframework.data.jpa.repository.Query(
            "select count(distinct e.exerciseId) from ExerciseRecordEntity e where e.userId = :userId and e.completed = 1")
    long countDistinctCompletedExercises(@org.springframework.data.repository.query.Param("userId") Long userId);

    long countByUserIdAndCompletedNot(Long userId, Short completed);
}
