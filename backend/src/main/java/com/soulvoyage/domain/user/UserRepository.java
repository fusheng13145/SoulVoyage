package com.soulvoyage.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsernameAndDeletedAtIsNull(String username);
    Optional<UserEntity> findByIdAndDeletedAtIsNull(Long id);

    List<UserEntity> findByCrisisState(String crisisState);

    List<UserEntity> findByCrisisStateAndCrisisEndsAtBefore(String crisisState, Instant cutoff);

    /** S2 注销冷静期到期候选：status=3 且申请时间早于冷静期终点 */
    List<UserEntity> findByStatusAndDeletionRequestedAtBefore(Short status, Instant cutoff);
}
