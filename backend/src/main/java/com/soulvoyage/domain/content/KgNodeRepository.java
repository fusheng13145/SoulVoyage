package com.soulvoyage.domain.content;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KgNodeRepository extends JpaRepository<KgNodeEntity, Long> {
    List<KgNodeEntity> findByTypeAndStatus(String type, Short status);

    List<KgNodeEntity> findByStatus(Short status);

    Optional<KgNodeEntity> findByCode(String code);
}
