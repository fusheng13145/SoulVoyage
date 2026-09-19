package com.soulvoyage.domain.diary;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DiaryRepository extends JpaRepository<DiaryEntity, Long> {

    List<DiaryEntity> findByUserIdAndDeletedAtIsNullAndRecordDateBetweenOrderByRecordDateDescIdDesc(
            Long userId, LocalDate from, LocalDate to);

    Optional<DiaryEntity> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    Optional<DiaryEntity> findByTaskIdAndUserId(Long taskId, Long userId);

    List<DiaryEntity> findByUserIdAndDeletedAtIsNullOrderByRecordDateDescIdDesc(Long userId);
}
