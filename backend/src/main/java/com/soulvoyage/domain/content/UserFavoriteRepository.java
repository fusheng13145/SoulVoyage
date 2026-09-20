package com.soulvoyage.domain.content;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserFavoriteRepository extends JpaRepository<UserFavoriteEntity, Long> {
    List<UserFavoriteEntity> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    Optional<UserFavoriteEntity> findByUserIdAndTypeAndRefCode(Long userId, String type, String refCode);
}
