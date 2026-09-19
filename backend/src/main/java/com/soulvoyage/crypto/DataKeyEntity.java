package com.soulvoyage.crypto;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "data_key")
public class DataKeyEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(nullable = false)
    private Integer version;

    @Lob
    @Column(name = "enc_master_key_ref")
    private byte[] encMasterKeyRef;

    @Column(nullable = false, length = 32)
    private String algo = "AES-256-GCM";

    @Column(nullable = false, length = 32)
    private String kdf = "HKDF-SHA256";

    @Column(nullable = false)
    private Short status = 1;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "destroyed_at")
    private Instant destroyedAt;
}
