package com.soulvoyage.domain.content;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContentMetaRepository extends JpaRepository<ContentMetaEntity, Long> {
    Optional<ContentMetaEntity> findByMetaKey(String metaKey);
}
