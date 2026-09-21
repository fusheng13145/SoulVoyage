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

    /** 危机生命周期状态（下篇·S1）：NORMAL/CRISIS/COOLING/REVIEW，取代旧 crisis_flag 布尔位 */
    @Column(name = "crisis_state", nullable = false, length = 16)
    private String crisisState = "NORMAL";

    @Column(name = "crisis_started_at")
    private Instant crisisStartedAt;

    /** CRISIS=强干预到期时间；COOLING=进入冷却时间（新风险事件水位） */
    @Column(name = "crisis_ends_at")
    private Instant crisisEndsAt;

    @Column(name = "policy_version", length = 16)
    private String policyVersion;

    /**
     * 微信小程序身份绑定（M14）：openid 是"同一应用内对同一用户的假名"，不是手机号那样的联系方式，
     * 且必须按等值查得到才能完成登录，所以与 username 同列明文同类、不占 ✦ 加密域。
     * 唯一约束即"一个微信对一个账号"；注销执行时随其它个人信息一起置空。
     */
    @Column(name = "wx_openid", length = 64, unique = true)
    private String wxOpenid;

    @Column(name = "deletion_requested_at")
    private Instant deletionRequestedAt;

    @Column(name = "agreed_policy_at")
    private Instant agreedPolicyAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
