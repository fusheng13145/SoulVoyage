package com.soulvoyage.domain.task;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "agent_message")
public class AgentMessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_no", nullable = false, length = 26, unique = true)
    private String messageNo;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "step_seq", nullable = false)
    private Integer stepSeq;

    @Column(name = "from_agent", nullable = false, length = 32)
    private String fromAgent;

    @Column(name = "to_agent", nullable = false, length = 32)
    private String toAgent;

    @Column(name = "msg_type", nullable = false, length = 16)
    private String msgType;

    @Lob
    @Column(name = "payload_enc", nullable = false)
    private byte[] payloadEnc;

    @Column(name = "context_ref", length = 128)
    private String contextRef;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
