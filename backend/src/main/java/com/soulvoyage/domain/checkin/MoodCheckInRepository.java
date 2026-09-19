package com.soulvoyage.domain.checkin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MoodCheckInRepository extends JpaRepository<MoodCheckInEntity, Long> {

    Optional<MoodCheckInEntity> findByUserIdAndCheckDate(Long userId, LocalDate checkDate);

    List<MoodCheckInEntity> findByUserIdAndCheckDateBetweenOrderByCheckDateAsc(Long userId, LocalDate from, LocalDate to);

    List<MoodCheckInEntity> findByUserIdOrderByCheckDateDesc(Long userId);

    List<MoodCheckInEntity> findByUserIdOrderByCheckDateAsc(Long userId);
}
