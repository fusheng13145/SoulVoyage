package com.soulvoyage.domain.simulate;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "simulate_turn")
public class SimulateTurnEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "simulate_id", nullable = false)
    private Long simulateId;

    @Column(name = "turn_no", nullable = false)
    private Integer turnNo;

    /** ✦ 用户发言密文（对话记录属敏感数据，手册 §7.1） */
    @Lob
    @Column(name = "user_text_enc", nullable = false)
    private byte[] userTextEnc;

    /** NPC 回复为剧情文本（平台生成，非用户敏感数据），明文存储便于复盘拼装 */
    @Column(name = "npc_text", nullable = false, length = 2000)
    private String npcText;

    /** 导演档位：NEUTRAL/DISSATISFIED/ESCALATED/SOFTENED */
    @Column(name = "npc_emotion", nullable = false, length = 16)
    private String npcEmotion = "NEUTRAL";

    @Column(name = "state_tag", length = 32)
    private String stateTag;

    @Column(name = "crisis_flag", nullable = false)
    private Short crisisFlag = 0;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
