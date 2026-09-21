package com.soulvoyage.domain.board;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupportGroupRepository extends JpaRepository<SupportGroupEntity, Long> {

    List<SupportGroupEntity> findAllByOrderByIdAsc();

    Optional<SupportGroupEntity> findByName(String name);
}
