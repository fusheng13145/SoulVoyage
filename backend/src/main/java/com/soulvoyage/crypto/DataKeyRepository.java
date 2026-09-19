package com.soulvoyage.crypto;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DataKeyRepository extends JpaRepository<DataKeyEntity, Long> {
    Optional<DataKeyEntity> findFirstByOwnerUserIdAndStatusOrderByVersionDesc(Long ownerUserId, short status);
    Optional<DataKeyEntity> findByOwnerUserIdAndVersion(Long ownerUserId, int version);
    List<DataKeyEntity> findByOwnerUserIdAndStatus(Long ownerUserId, short status);
    List<DataKeyEntity> findByOwnerUserIdAndStatusNot(Long ownerUserId, short status);
}
