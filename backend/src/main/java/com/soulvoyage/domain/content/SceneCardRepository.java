package com.soulvoyage.domain.content;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SceneCardRepository extends JpaRepository<SceneCardEntity, Long> {
    List<SceneCardEntity> findByStatusOrderByCodeAsc(Short status);

    Optional<SceneCardEntity> findByCode(String code);
}
