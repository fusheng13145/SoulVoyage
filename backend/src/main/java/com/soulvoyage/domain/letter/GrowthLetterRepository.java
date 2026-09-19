package com.soulvoyage.domain.letter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GrowthLetterRepository extends JpaRepository<GrowthLetterEntity, Long> {

    List<GrowthLetterEntity> findByUserIdOrderByStatWeekDesc(Long userId);

    Optional<GrowthLetterEntity> findByUserIdAndStatWeek(Long userId, String statWeek);
}
