package com.soulvoyage.wechat;

import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 微信登录回放器（默认，`SV_WX_PROVIDER` 缺省即此）：不起网络，把 jscode 稳定映射成一个 openid。
 *
 * 为什么能这样替：本应用只用到 openid 的两条性质——**同一微信两次登录给同一个值**、
 * **不同微信给不同值**。Mock 按 jscode 的哈希满足这两条，于是绑定/登录/解绑/注销释放的全部
 * 判据都能在无密钥、无小程序 AppID 的环境里逐字测到（与 MockLlmClient/MockAsrClient 同一口径）。
 *
 * 保留一个注入点给契约测试走真失败分支：以 `wx-boom` 开头的 jscode 模拟微信侧拒绝。
 */
@Component
@ConditionalOnProperty(name = "soulvoyage.wechat.provider", havingValue = "mock", matchIfMissing = true)
public class MockWxAuthClient implements WxAuthClient {

    @Override
    public String exchangeOpenid(String jsCode) {
        if (jsCode == null || jsCode.isBlank()) {
            throw new BizException(ErrorCode.BAD_PARAMS, "缺少微信登录凭证，请重新进入小程序");
        }
        String code = jsCode.trim();
        if (code.startsWith("wx-boom")) {
            throw new BizException(ErrorCode.WX_LOGIN_FAILED, "微信登录失败了，稍后再试一次");
        }
        return "mock-" + sha256(code).substring(0, 20);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
