package com.soulvoyage.domain.diary;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DraftRepository extends JpaRepository<DraftEntity, Long> {
    Optional<DraftEntity> findByUserIdAndKind(Long userId, String kind);
}
