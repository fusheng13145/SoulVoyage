package com.soulvoyage.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 审计日志 + 哈希链防篡改：hash = SHA256(prevHash + "|" + userId + "|" + action + "|" + target + "|" + ip)。
 * 链尾读取与插入必须同事务串行：JVM 锁覆盖到 REQUIRES_NEW 提交完成之后再释放，
 * 否则并发写入会读到同一链尾造成断链（起步规模可接受；高并发时改为 Redis 链尾缓存）。
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repo;
    private final Tx tx;
    private final Object chainLock = new Object();

    /** 链尾读取+插入放独立 bean：锁包在事务外，提交后才放锁 */
    @org.springframework.stereotype.Component
    static class Tx {
        private final AuditLogRepository repo;

        Tx(AuditLogRepository repo) {
            this.repo = repo;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void append(Long userId, String action, String target, String ip) {
            String prev = repo.findFirstByOrderByIdDesc().map(AuditLogEntity::getHashChain).orElse("0".repeat(64));
            AuditLogEntity e = new AuditLogEntity();
            e.setUserId(userId);
            e.setAction(action);
            e.setTarget(target);
            e.setIp(ip);
            e.setHashChain(sha256(prev + "|" + userId + "|" + action + "|" + target + "|" + ip));
            repo.save(e);
        }
    }

    public void record(Long userId, String action, String target, String ip) {
        synchronized (chainLock) {
            tx.append(userId, action, target, ip);
        }
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A5 审计链校验：从头重算全链，返回首个断链行 id（0=完好）与行数 */
    public synchronized VerifyResult verifyChain() {
        String prev = "0".repeat(64);
        long checked = 0;
        Long brokenAtId = null;
        for (AuditLogEntity e : repo.findAllByOrderByIdAsc()) {
            String expect = sha256(prev + "|" + e.getUserId() + "|" + e.getAction()
                    + "|" + e.getTarget() + "|" + e.getIp());
            if (!expect.equals(e.getHashChain())) {
                brokenAtId = e.getId();
                break;
            }
            prev = e.getHashChain();
            checked++;
        }
        return new VerifyResult(checked, brokenAtId);
    }

    public record VerifyResult(long checked, Long brokenAtId) {
        public boolean intact() {
            return brokenAtId == null;
        }
    }
}
