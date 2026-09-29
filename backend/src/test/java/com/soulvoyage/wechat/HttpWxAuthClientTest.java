package com.soulvoyage.wechat;

import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真微信网关的协议与错误收口测试：用 JDK 内置 HttpServer 起本地桩（不依赖外网与真 AppID）。
 *
 * <p>验证两条刻意的立场：① 上游任何失败都统一收口成 1005 的中文话术，不把微信的
 * errcode/errmsg 透给客户端、也不退化成 500；② session_key 拿到即弃，不外泄。
 */
class HttpWxAuthClientTest {

    private static HttpServer stub;
    private static String endpoint;

    /** 桩的可变响应：每个用例只改这几项即可模拟不同上游行为 */
    private static volatile int status;
    private static volatile String body;
    private static final AtomicReference<String> lastQuery = new AtomicReference<>();

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/sns/jscode2session", ex -> {
            // 必须取 raw query：URI.getQuery() 是解码后的，%26 会被还原成 &，验不出编码
            lastQuery.set(ex.getRequestURI().getRawQuery());
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, payload.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(payload);
            }
        });
        stub.start();
        endpoint = "http://127.0.0.1:" + stub.getAddress().getPort() + "/sns/jscode2session";
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @BeforeEach
    void resetStub() {
        status = 200;
        body = "{\"openid\":\"o_openid_1\",\"session_key\":\"sk_secret\"}";
        lastQuery.set(null);
    }

    private HttpWxAuthClient client(String appId, String appSecret) {
        return new HttpWxAuthClient(appId, appSecret, endpoint, Duration.ofSeconds(3));
    }

    private HttpWxAuthClient client() {
        return client("wxapp", "secret");
    }

    @Test
    @DisplayName("缺少 AppID 直接拒绝构造，避免把空凭据带到线上")
    void rejectsBlankAppId() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client("  ", "secret"));
        assertTrue(ex.getMessage().contains("SV_WX_APPID"));
    }

    @Test
    @DisplayName("缺少 AppSecret 同样拒绝构造")
    void rejectsBlankAppSecret() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client("wxapp", ""));
        assertTrue(ex.getMessage().contains("SV_WX_SECRET"));
    }

    @Test
    @DisplayName("凭证为空收口成 400（BAD_PARAMS），不发起任何上游调用")
    void blankJsCodeIsBadParams() {
        BizException ex = assertThrows(BizException.class, () -> client().exchangeOpenid("   "));

        assertEquals(ErrorCode.BAD_PARAMS, ex.getErrorCode());
        assertEquals(null, lastQuery.get(), "未成行就短路，不该打上游");
    }

    @Test
    @DisplayName("凭证为 null 同样收口成 400")
    void nullJsCodeIsBadParams() {
        BizException ex = assertThrows(BizException.class, () -> client().exchangeOpenid(null));

        assertEquals(ErrorCode.BAD_PARAMS, ex.getErrorCode());
    }

    @Test
    @DisplayName("成功时返回 openid，且请求按 jscode2session 协议组装")
    void exchangeSucceeds() {
        String openid = client().exchangeOpenid("code_123");

        assertEquals("o_openid_1", openid);
        String q = lastQuery.get();
        assertTrue(q.contains("appid=wxapp"), q);
        assertTrue(q.contains("secret=secret"), q);
        assertTrue(q.contains("js_code=code_123"), q);
        assertTrue(q.contains("grant_type=authorization_code"), q);
    }

    @Test
    @DisplayName("凭证两侧空白被裁掉，不把空格带进上游查询串")
    void trimsJsCode() {
        client().exchangeOpenid("  code_456  ");

        assertTrue(lastQuery.get().contains("js_code=code_456"), lastQuery.get());
        assertTrue(!lastQuery.get().contains("js_code=+code_456"), "去空白应发生在编码之前");
    }

    @Test
    @DisplayName("AppID 含特殊字符时按 URL 编码，不破坏查询串结构")
    void encodesAppId() {
        client("wx app&x", "sec").exchangeOpenid("c");

        String q = lastQuery.get();
        assertTrue(q.contains("appid=wx+app%26x"), q);
    }

    @Test
    @DisplayName("errcode=40029 给可操作话术：凭证已用过")
    void errCode40029HasActionableMessage() {
        body = "{\"errcode\":40029,\"errmsg\":\"code been used\"}";

        BizException ex = assertThrows(BizException.class, () -> client().exchangeOpenid("c"));

        assertEquals(ErrorCode.WX_LOGIN_FAILED, ex.getErrorCode());
        assertEquals("登录凭证已用过，请重新用微信进入", ex.getMessage());
    }

    @Test
    @DisplayName("其他 errcode 不外泄微信原文，只给通用话术")
    void otherErrCodeIsOpaque() {
        body = "{\"errcode\":40013,\"errmsg\":\"invalid appid\"}";

        BizException ex = assertThrows(BizException.class, () -> client().exchangeOpenid("c"));

        assertEquals(ErrorCode.WX_LOGIN_FAILED, ex.getErrorCode());
        assertEquals("微信登录失败了，稍后再试一次", ex.getMessage());
        assertTrue(!ex.getMessage().contains("invalid appid"), "不把上游 errmsg 透给客户端");
    }

    @Test
    @DisplayName("非 2xx 收口成 1005 并带上状态码，不退化成 500")
    void nonSuccessStatusIsBusinessError() {
        status = 503;

        BizException ex = assertThrows(BizException.class, () -> client().exchangeOpenid("c"));

        assertEquals(ErrorCode.WX_LOGIN_FAILED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("HTTP 503"), ex.getMessage());
    }

    @Test
    @DisplayName("微信没回 openid 时也算失败，不放行空身份")
    void missingOpenidFails() {
        body = "{\"session_key\":\"sk\"}";

        BizException ex = assertThrows(BizException.class, () -> client().exchangeOpenid("c"));

        assertEquals(ErrorCode.WX_LOGIN_FAILED, ex.getErrorCode());
        assertEquals("微信没返回身份标识", ex.getMessage());
    }

    @Test
    @DisplayName("响应体不是 JSON 时收口成通用话术，不抛原始解析异常")
    void malformedBodyIsOpaque() {
        body = "not-json-at-all";

        BizException ex = assertThrows(BizException.class, () -> client().exchangeOpenid("c"));

        assertEquals(ErrorCode.WX_LOGIN_FAILED, ex.getErrorCode());
        assertEquals("微信登录失败了，稍后再试一次", ex.getMessage());
    }

    @Test
    @DisplayName("上游不可达（网络层异常）收口成同一话术，不冒泡 SocketException")
    void unreachableUpstreamIsOpaque() {
        HttpWxAuthClient dead = new HttpWxAuthClient("wxapp", "secret",
                "http://127.0.0.1:1/sns/jscode2session", Duration.ofMillis(500));

        BizException ex = assertThrows(BizException.class, () -> dead.exchangeOpenid("c"));

        assertEquals(ErrorCode.WX_LOGIN_FAILED, ex.getErrorCode());
        assertEquals("微信登录失败了，稍后再试一次", ex.getMessage());
    }
}
