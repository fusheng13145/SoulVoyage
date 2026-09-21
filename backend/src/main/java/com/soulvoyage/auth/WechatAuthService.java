package com.soulvoyage.auth;

import com.soulvoyage.audit.AuditService;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.common.state.SlidingWindowLimiter;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.dto.AuthDtos.*;
import com.soulvoyage.wechat.WxAuthClient;
import com.soulvoyage.wechat.WxBindTicketStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 小程序端登录（M14，§11.3「微信小程序多端」）。
 *
 * 一条产品级取舍：**微信不建立新账号，只做既有账号的第二个入口。**
 * 因为口令在本工程里不只是登录凭证——A2 复核证据要口令重验、注销与改密要口令、
 * `POST /users/me/delete` 也要口令。若存在"只有微信、没有口令"的账号，这些护栏会在小程序里集体失守，
 * 而"在小程序里补设口令"又是一套新的状态机。所以未绑定的微信访客只能二选一：
 * 绑定已有账号（走与 Web 完全同一条 authenticate，含失败锁定与审计），或注册一个新账号。
 *
 * 三处刻意的语义：
 * ① 未绑定不发半吊子令牌——只回一张一次性绑定票，openid 留在服务端；
 * ② 绑定失败（口令错/用户名重复/该账号已绑别的微信）会把票还回去，用户不必重走一次微信授权，
 *    与"越权领取不消耗导出链接"是同一条立场：失败不该顺带毁掉别人的凭据；
 * ③ 这个端点是匿名可达的，且每次都要敲一次外部服务（真微信 API），所以先过一道 IP 级滑动窗，
 *    复用 M13 的 {@link SlidingWindowLimiter}，不新写一套限流。
 */
@Service
@RequiredArgsConstructor
public class WechatAuthService {

    private static final int LOGIN_PER_MIN_PER_IP = 20;
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final WxAuthClient wx;
    private final WxBindTicketStore tickets;
    private final AuthService auth;
    private final UserRepository userRepo;
    private final AuditService audit;
    private final SlidingWindowLimiter windowLimiter;

    public WxLoginResp login(WxLoginReq req, String ip) {
        if (!windowLimiter.tryAcquire("wechat:" + ip, LOGIN_PER_MIN_PER_IP, WINDOW)) {
            throw new BizException(ErrorCode.LLM_RATE_LIMIT, "登录得太快了，歇一下再进");
        }
        String openid = wx.exchangeOpenid(req.code());
        return userRepo.findByWxOpenidAndDeletedAtIsNull(openid)
                .map(u -> {
                    // 与口令登录同口径：冻结账号（status=2）不因换了入口而放行
                    if (u.getStatus() != 1 && u.getStatus() != 3) {
                        throw new BizException(ErrorCode.FORBIDDEN, "账号当前不可登录，请联系管理员");
                    }
                    audit.record(u.getId(), "WX_LOGIN", "user:" + u.getId(), ip);
                    return new WxLoginResp(true, null, null, auth.tokenResp(u));
                })
                .orElseGet(() -> new WxLoginResp(false, tickets.issue(openid), 600L, null));
    }

    @Transactional
    public TokenResp bind(WxBindReq req, String ip) {
        return withTicketReused(req.bindTicket(), openid -> {
            UserEntity u = auth.authenticate(new LoginReq(req.username(), req.password()), ip);
            return bindAndIssue(u, openid, ip);
        });
    }

    @Transactional
    public TokenResp register(WxRegisterReq req, String ip) {
        return withTicketReused(req.bindTicket(), openid -> {
            UserEntity u = auth.registerUser(
                    new RegisterReq(req.username(), req.password(), req.nickname()), ip);
            return bindAndIssue(u, openid, ip);
        });
    }

    /** 解绑：用户随时可收回第三方入口，且即时生效（下一次微信登录回到未绑定分支） */
    @Transactional
    public void unbind(long userId, String ip) {
        UserEntity u = userRepo.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));
        if (u.getWxOpenid() == null) {
            throw new BizException(ErrorCode.BAD_PARAMS, "这个账号还没绑定微信");
        }
        u.setWxOpenid(null);
        userRepo.save(u);
        audit.record(userId, "WX_UNBIND", "user:" + userId, ip);
    }

    // ---------------- internals ----------------

    /** 票是一次性的，但"填错口令"不该让人重走一次微信授权：失败即原样还回票 */
    private TokenResp withTicketReused(String ticket, java.util.function.Function<String, TokenResp> work) {
        String openid = tickets.consumeOnce(ticket);
        try {
            return work.apply(openid);
        } catch (BizException e) {
            tickets.restore(ticket, openid);
            throw e;
        }
    }

    private TokenResp bindAndIssue(UserEntity u, String openid, String ip) {
        String bound = u.getWxOpenid();
        if (bound != null && !bound.equals(openid)) {
            throw new BizException(ErrorCode.FORBIDDEN, "这个账号已经绑定了另一个微信");
        }
        userRepo.findByWxOpenidAndDeletedAtIsNull(openid).ifPresent(other -> {
            if (!other.getId().equals(u.getId())) {
                throw new BizException(ErrorCode.FORBIDDEN, "这个微信已经绑定了另一个账号");
            }
        });
        if (bound == null) {
            u.setWxOpenid(openid);
            userRepo.save(u);
            audit.record(u.getId(), "WX_BIND", "user:" + u.getId(), ip);
        }
        return auth.tokenResp(u);
    }
}
