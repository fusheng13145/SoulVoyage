package com.soulvoyage.account;

import com.soulvoyage.audit.AuditService;
import com.soulvoyage.auth.JwtService;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 账号注销即遗忘（下篇·S2，手册 §7.1 合规闭环）。
 * 申请（密码二次确认）→ status=3 + epoch 吊销全部令牌 → 冷静期内可登录撤回 →
 * 到期定时任务销毁用户 DEK：密文物理不可读，仅保留审计哈希链所需最小记录（匿名化昵称、清手机号）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserRepository userRepo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final CryptoService crypto;
    private final AuditService audit;

    @Value("${soulvoyage.account.deletion-cooldown}")
    private Duration cooldown;

    /** 注销申请：二次确认 = 复核密码；提交即吊销所有会话（前端随后跳登录页） */
    @Transactional
    public void requestDeletion(long userId, String password, String ip) {
        UserEntity u = mustGet(userId);
        if (!encoder.matches(password, u.getPasswordHash())) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "密码校验失败");
        }
        if (u.getStatus() == 3) {
            throw new BizException(ErrorCode.BAD_PARAMS, "注销申请已在冷静期中");
        }
        u.setStatus((short) 3);
        u.setDeletionRequestedAt(Instant.now());
        userRepo.save(u);
        jwt.revokeAll(userId);                       // 所有已发 access/refresh 立即失效
        audit.record(userId, "DELETE_REQUEST", "user:" + userId, ip);
    }

    /** 冷静期撤回：登录（status=3 放行）后显式调用 */
    @Transactional
    public void cancelDeletion(long userId, String ip) {
        UserEntity u = mustGet(userId);
        if (u.getStatus() != 3) {
            throw new BizException(ErrorCode.BAD_PARAMS, "当前无进行中的注销申请");
        }
        Instant requested = u.getDeletionRequestedAt();
        if (requested != null && requested.plus(cooldown).isBefore(Instant.now())) {
            throw new BizException(ErrorCode.FORBIDDEN, "冷静期已过，数据已销毁，无法撤回");
        }
        u.setStatus((short) 1);
        u.setDeletionRequestedAt(null);
        userRepo.save(u);
        audit.record(userId, "DELETE_CANCEL", "user:" + userId, ip);
    }

    /** 修改密码：改密视为凭证变更，epoch 吊销全部旧会话（S3 联动） */
    @Transactional
    public void changePassword(long userId, String oldPwd, String newPwd, String ip) {
        UserEntity u = mustGet(userId);
        if (!encoder.matches(oldPwd, u.getPasswordHash())) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "原密码错误");
        }
        if (newPwd == null || newPwd.length() < 8 || newPwd.length() > 64) {
            throw new BizException(ErrorCode.BAD_PARAMS, "新密码需 8-64 位");
        }
        u.setPasswordHash(encoder.encode(newPwd));
        userRepo.save(u);
        jwt.revokeAll(userId);
        audit.record(userId, "PASSWORD_CHANGED", "user:" + userId, ip);
    }

    /** 冷静期到期的注销执行（DailySweeper 每日调用）：密钥销毁 + 匿名化 + deleted_at */
    @Transactional
    public int processExpiredDeletions() {
        List<UserEntity> expired = userRepo.findByStatusAndDeletionRequestedAtBefore(
                (short) 3, Instant.now().minus(cooldown));
        for (UserEntity u : expired) {
            crypto.destroyUserKeys(u.getId());      // 注销即遗忘：DEK 销毁，密文永久不可解
            u.setNickname("已注销用户");
            u.setPhoneEnc(null);
            u.setDeletedAt(Instant.now());
            userRepo.save(u);
            audit.record(u.getId(), "DELETE_EXECUTED", "user:" + u.getId(), null);
            log.info("deletion cooling expired, keys destroyed for user {}", u.getId());
        }
        return expired.size();
    }

    private UserEntity mustGet(long userId) {
        return userRepo.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));
    }
}
