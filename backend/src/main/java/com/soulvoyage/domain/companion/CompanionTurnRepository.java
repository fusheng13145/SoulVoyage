package com.soulvoyage.domain.companion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanionTurnRepository extends JpaRepository<CompanionTurnEntity, Long> {

    List<CompanionTurnEntity> findBySessionIdOrderByTurnNoAsc(Long sessionId);

    List<CompanionTurnEntity> findBySessionIdAndNoAnalyzeOrderByTurnNoAsc(Long sessionId, Short noAnalyze);

    Optional<CompanionTurnEntity> findBySessionIdAndTurnNo(Long sessionId, Integer turnNo);
}
