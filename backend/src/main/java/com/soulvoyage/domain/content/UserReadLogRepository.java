package com.soulvoyage.domain.content;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface UserReadLogRepository extends JpaRepository<UserReadLogEntity, Long> {
    List<UserReadLogEntity> findByUserIdAndReadDate(Long userId, LocalDate readDate);

    List<UserReadLogEntity> findByUserIdOrderByReadDateDescIdDesc(Long userId);
}
