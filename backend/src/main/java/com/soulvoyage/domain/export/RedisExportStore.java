package com.soulvoyage.domain.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.crypto.CryptoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Redis 导出快照暂存（M13 多节点）：A 节点生成、B 节点领取。
 * 隐私红线：Redis 里只落 **信封加密后的密文**（属主 DEK 包裹），明文从不出进程；
 * 注销即遗忘销毁 DEK 后，即便 key 有残留也永久不可解。
 * value 格式 `{userId}:{base64(密文)}`，take 用一条 Lua 原子 GET+DEL 保住"领取即焚"。
 */
@Slf4j
public class RedisExportStore implements ExportStore {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String PREFIX = "export:snapshot:";

    private static final RedisScript<String> TAKE = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if v == false then return nil end
            local sep = string.find(v, ':', 1, true)
            if sep and string.sub(v, 1, sep - 1) == ARGV[1] then
              redis.call('DEL', KEYS[1])     -- 只有属主取走才销毁
            end
            return v
            """, String.class);

    private final StringRedisTemplate redis;
    private final CryptoService crypto;
    private final ObjectMapper mapper;

    public RedisExportStore(StringRedisTemplate redis, CryptoService crypto, ObjectMapper mapper) {
        this.redis = redis;
        this.crypto = crypto;
        this.mapper = mapper;
    }

    @Override
    public void put(String fileId, long userId, ObjectNode data) {
        byte[] enc = crypto.encryptUserField(userId, data.toString());
        redis.opsForValue().set(PREFIX + fileId,
                userId + ":" + Base64.getEncoder().encodeToString(enc), TTL);
    }

    @Override
    public Taken take(String fileId, long requesterId) {
        String raw = redis.execute(TAKE, List.of(PREFIX + fileId), String.valueOf(requesterId));
        if (raw == null) return Taken.MISSING;
        long owner;
        byte[] enc;
        try {
            int sep = raw.indexOf(':');
            owner = Long.parseLong(raw.substring(0, sep));
            enc = Base64.getDecoder().decode(raw.substring(sep + 1));
        } catch (Exception e) {
            log.warn("export snapshot record corrupted for {}", fileId);
            return Taken.MISSING;
        }
        if (owner != requesterId) return Taken.FOREIGN;   // 越权访问不销毁，链接仍归属主
        try {
            ObjectNode data = (ObjectNode) mapper.readTree(crypto.decryptUserField(owner, enc));
            return new Taken(Outcome.CLAIMED, data);
        } catch (Exception e) {
            // 密钥已销毁/字节损坏：领取失败与"不存在"同语义，不回 500
            log.warn("export snapshot take failed for {}", fileId, e);
            return Taken.MISSING;
        }
    }
}
