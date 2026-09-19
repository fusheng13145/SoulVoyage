package com.soulvoyage.domain.simulate;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "simulate_session")
public class SimulateSessionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "scene_code", nullable = false, length = 32)
    private String sceneCode;

    @Column(nullable = false, length = 8)
    private String difficulty = "NORMAL";

    /** RUNNING / FINISHED / ABORTED_RISK（轮数上限在服务层断言，不占持久状态） */
    @Column(nullable = false, length = 16)
    private String status = "RUNNING";

    @Column(name = "total_turns", nullable = false)
    private Integer totalTurns = 0;

    /** ✦ 导演模块状态快照：{tension, mood, keyEvents[]} JSON 密文 */
    @Lob
    @Column(name = "npc_state_snap_enc")
    private byte[] npcStateSnapEnc;

    @Column(name = "report_id")
    private Long reportId;

    @Column(name = "started_at", insertable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
