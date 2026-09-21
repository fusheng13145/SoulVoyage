package com.soulvoyage.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 真微信网关（启用：SV_WX_PROVIDER=http + SV_WX_APPID + SV_WX_SECRET）。
 * 协议是微信服务端的 jscode2session：一个 GET，返回 {openid, session_key, errcode?}。
 *
 * 两条刻意立场：① session_key 拿到即丢——本应用不做小程序解密通信，留着它只会多一份可泄露的密钥；
 * ② 上游任何失败（非 2xx、errcode≠0、缺 openid、网络异常）统一收口成 1005 中文话术，
 * 不把微信的 errcode/errmsg 透给客户端，也不退化成 500（与 4004 语音转写同一口径）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "soulvoyage.wechat.provider", havingValue = "http")
public class HttpWxAuthClient implements WxAuthClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String appId;
    private final String appSecret;
    private final String endpoint;
    private final Duration timeout;

    public HttpWxAuthClient(@Value("${soulvoyage.wechat.app-id:}") String appId,
                            @Value("${soulvoyage.wechat.app-secret:}") String appSecret,
                            @Value("${soulvoyage.wechat.code2session-url:https://api.weixin.qq.com/sns/jscode2session}")
                            String endpoint,
                            @Value("${soulvoyage.wechat.timeout:5s}") Duration timeout) {
        if (appId.isBlank() || appSecret.isBlank()) {
            throw new IllegalStateException("SV_WX_PROVIDER=http 需要同时提供 SV_WX_APPID 与 SV_WX_SECRET");
        }
        this.appId = appId;
        this.appSecret = appSecret;
        this.endpoint = endpoint;
        this.timeout = timeout;
    }

    @Override
    public String exchangeOpenid(String jsCode) {
        if (jsCode == null || jsCode.isBlank()) {
            throw new BizException(ErrorCode.BAD_PARAMS, "缺少微信登录凭证，请重新进入小程序");
        }
        try {
            String url = endpoint + "?appid=" + enc(appId) + "&secret=" + enc(appSecret)
                    + "&js_code=" + enc(jsCode.trim()) + "&grant_type=authorization_code";
            var resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new BizException(ErrorCode.WX_LOGIN_FAILED,
                        "微信登录服务暂时不可用（HTTP " + resp.statusCode() + "）");
            }
            var body = mapper.readTree(resp.body());
            int errcode = body.path("errcode").asInt(0);
            if (errcode != 0) {
                log.warn("jscode2session errcode={}", errcode);
                throw new BizException(ErrorCode.WX_LOGIN_FAILED,
                        errcode == 40029 ? "登录凭证已用过，请重新用微信进入" : "微信登录失败了，稍后再试一次");
            }
            String openid = body.path("openid").asText("");
            if (openid.isEmpty()) throw new BizException(ErrorCode.WX_LOGIN_FAILED, "微信没返回身份标识");
            return openid;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("wx login call failed: {}", e.toString());
            throw new BizException(ErrorCode.WX_LOGIN_FAILED, "微信登录失败了，稍后再试一次");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
