package com.soulvoyage.domain.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "user")
public class UserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, unique = true)
    private String username;

    @Column(nullable = false, length = 64)
    private String nickname = "";

    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash;

    @Lob
    @Column(name = "phone_enc")
    private byte[] phoneEnc;

    @Column(nullable = false, length = 16)
    private String role = "USER";

    @Column(nullable = false)
    private Short status = 1;

    @Column(name = "crisis_flag", nullable = false)
    private Short crisisFlag = 0;

    @Column(name = "agreed_policy_at")
    private Instant agreedPolicyAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
