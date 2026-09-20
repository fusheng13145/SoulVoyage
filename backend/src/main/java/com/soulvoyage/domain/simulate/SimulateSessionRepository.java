package com.soulvoyage.domain.simulate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SimulateSessionRepository extends JpaRepository<SimulateSessionEntity, Long> {

    Optional<SimulateSessionEntity> findByIdAndUserId(Long id, Long userId);

    List<SimulateSessionEntity> findByUserIdAndStatusInOrderByStartedAtDesc(Long userId, List<String> statuses);

    /** C3 会话列表：可按状态过滤分页 */
    Page<SimulateSessionEntity> findByUserIdOrderByStartedAtDesc(Long userId, Pageable pageable);

    Page<SimulateSessionEntity> findByUserIdAndStatusOrderByStartedAtDesc(Long userId, String status, Pageable pageable);

    /** C3 同场景最近一次有弱项留档的复盘：续练时注入 NPC 教练记忆 */
    Optional<SimulateSessionEntity> findFirstByUserIdAndSceneCodeAndWeaknessesEncIsNotNullOrderByIdDesc(
            Long userId, String sceneCode);

    List<SimulateSessionEntity> findByUserId(Long userId);
}
