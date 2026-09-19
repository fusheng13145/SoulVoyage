package com.soulvoyage.domain.simulate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SimulateTurnRepository extends JpaRepository<SimulateTurnEntity, Long> {

    List<SimulateTurnEntity> findBySimulateIdOrderByTurnNoAsc(Long simulateId);
}
