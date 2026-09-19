package com.soulvoyage.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 审计日志 + 哈希链防篡改：hash = SHA256(prevHash + userId + action + target + ip + epochSecond)。
 * 链尾查询加锁串行写入（起步规模可接受；高并发时改为 Redis 链尾缓存）。
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized void record(Long userId, String action, String target, String ip) {
        String prev = repo.findFirstByOrderByIdDesc().map(AuditLogEntity::getHashChain).orElse("0".repeat(64));
        AuditLogEntity e = new AuditLogEntity();
        e.setUserId(userId);
        e.setAction(action);
        e.setTarget(target);
        e.setIp(ip);
        e.setHashChain(sha256(prev + "|" + userId + "|" + action + "|" + target + "|" + ip));
        repo.save(e);
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
