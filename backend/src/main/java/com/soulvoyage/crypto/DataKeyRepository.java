package com.soulvoyage.crypto;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DataKeyRepository extends JpaRepository<DataKeyEntity, Long> {
    Optional<DataKeyEntity> findFirstByOwnerUserIdAndStatusOrderByVersionDesc(Long ownerUserId, short status);
    Optional<DataKeyEntity> findByOwnerUserIdAndVersion(Long ownerUserId, int version);
    List<DataKeyEntity> findByOwnerUserIdAndStatus(Long ownerUserId, short status);
    List<DataKeyEntity> findByOwnerUserIdAndStatusNot(Long ownerUserId, short status);

    /** A5 密钥看板：按状态聚合 [status, count, maxVersion, distinctOwners] */
    @Query("select k.status, count(k), max(k.version), count(distinct k.ownerUserId) from DataKeyEntity k group by k.status")
    List<Object[]> statsByStatus();
}
