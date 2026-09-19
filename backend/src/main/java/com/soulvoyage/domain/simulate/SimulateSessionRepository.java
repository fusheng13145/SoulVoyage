package com.soulvoyage.domain.simulate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SimulateSessionRepository extends JpaRepository<SimulateSessionEntity, Long> {

    Optional<SimulateSessionEntity> findByIdAndUserId(Long id, Long userId);

    List<SimulateSessionEntity> findByUserIdAndStatusInOrderByStartedAtDesc(Long userId, List<String> statuses);
}
