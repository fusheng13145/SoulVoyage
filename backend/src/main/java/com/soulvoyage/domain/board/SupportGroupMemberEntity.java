package com.soulvoyage.domain.board;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** M12 群体成员关系（成员身份≠被看：进聚合的前提还有本人 counselor_board_on 授权） */
@Getter
@Setter
@Entity
@Table(name = "support_group_member")
public class SupportGroupMemberEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void stamp() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
