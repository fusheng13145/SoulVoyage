package com.soulvoyage.wechat;

import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * 一次性绑定票（M14）：微信侧验过身份，但这个人还没有心屿账号时，发一张短命票据给他，
 * 让他去填"已有账号的口令"或"注册新用户"，填完拿票换正式令牌。
 *
 * 为什么票里不写 openid：客户端拿到的是一串随机数，openid 只在服务端 Redis 里对应存放——
 * 前端因此没有机会伪造"我绑定了别人的微信"，也没有多泄露一个标识符。
 *
 * 为什么直接放 Redis 而不做进程内双实现：登录失败计数、令牌黑名单与吊销 epoch 早就在 Redis 上
 * （{@link com.soulvoyage.auth.AuthService}/{@link com.soulvoyage.auth.JwtService}），
 * 认证链本身不具备"多节点各说各话"的余地，这里再开一份内存实现只会让绑定票在两台之间不一致。
 */
@Component
@RequiredArgsConstructor
public class WxBindTicketStore {

    private static final String PREFIX = "wx:bind:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private static final RedisScript<String> CONSUME = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if v then redis.call('DEL', KEYS[1]) end
            return v
            """, String.class);

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public String issue(String openid) {
        byte[] buf = new byte[16];
        random.nextBytes(buf);
        String ticket = HexFormat.of().formatHex(buf);
        redis.opsForValue().set(PREFIX + ticket, openid, TTL);
        return ticket;
    }

    /**
     * 取走即焚（与导出链接同一语义）：一张票只能完成一次绑定。
     * 用 Lua 而不是 GETDEL——本机开发用的 Redis 5.x 没有 GETDEL，脚本在 5.x/7.x 都能原子跑完，
     * 免得"读到了但没删掉"留出一张能被第二次用掉的票。
     */
    public String consumeOnce(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new BizException(ErrorCode.WX_BIND_EXPIRED, null);
        }
        String openid = redis.execute(CONSUME, List.of(PREFIX + ticket.trim()));
        if (openid == null) {
            throw new BizException(ErrorCode.WX_BIND_EXPIRED, null);
        }
        return openid;
    }

    /** 绑定失败时把票还回去（见 WechatAuthService 的"失败不牵连他人"口径） */
    public void restore(String ticket, String openid) {
        redis.opsForValue().set(PREFIX + ticket.trim(), openid, TTL);
    }

    /** 只按精确 key 删：本机 Redis 是多项目共用的，回收自己发过的票不能靠模式匹配 */
    public void drop(String ticket) {
        if (ticket != null && !ticket.isBlank()) redis.delete(PREFIX + ticket.trim());
    }
}
