package com.soulvoyage.domain.achievement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserAchievementRepository extends JpaRepository<UserAchievementEntity, Long> {

    List<UserAchievementEntity> findByUserIdOrderByUnlockedAtAsc(Long userId);

    Optional<UserAchievementEntity> findByUserIdAndCode(Long userId, String code);
}
