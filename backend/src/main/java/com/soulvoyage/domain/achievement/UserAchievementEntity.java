package com.soulvoyage.domain.achievement;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** G3 成就徽章：全部指向"自我关照行为"，不指向分数竞争 */
@Getter
@Setter
@Entity
@Table(name = "user_achievement")
public class UserAchievementEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** FIRST_DIARY / FIRST_A_GRADE / STREAK_7 / STREAK_30 / ALL_EXERCISES / NO_REPEAT_DISTORTION / COMPANION_OPENED */
    @Column(nullable = false, length = 32)
    private String code;

    @Column(name = "unlocked_at", nullable = false)
    private Instant unlockedAt;

    @PrePersist
    void onCreate() {
        if (unlockedAt == null) unlockedAt = Instant.now();
    }
}
