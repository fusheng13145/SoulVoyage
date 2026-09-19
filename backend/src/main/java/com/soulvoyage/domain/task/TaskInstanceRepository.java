package com.soulvoyage.domain.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaskInstanceRepository extends JpaRepository<TaskInstanceEntity, Long> {
    Optional<TaskInstanceEntity> findByTaskNo(String taskNo);

    Optional<TaskInstanceEntity> findByUserIdAndClientReqId(Long userId, String clientReqId);

    @Query("select count(t) from TaskInstanceEntity t where t.userId = :uid and t.status in ('PENDING','RUNNING','WAITING_USER')")
    long countActive(@Param("uid") Long userId);

    List<TaskInstanceEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
}
