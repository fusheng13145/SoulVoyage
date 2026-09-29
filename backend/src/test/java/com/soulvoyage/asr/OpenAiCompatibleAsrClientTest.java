package com.soulvoyage.asr;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI 兼容转写网关的协议与错误收口测试：用 JDK 内置 HttpServer 起本地桩，不依赖真供应商。
 * 一并钉住 multipart 请求体的形状（文件名/字段名/模型名），因为它是最容易被"顺手改坏"的部分。
 */
class OpenAiCompatibleAsrClientTest {

    private static HttpServer stub;
    private static String baseUrl;

    private static volatile int status;
    private static volatile String body;
    private static final AtomicReference<String> lastPath = new AtomicReference<>();
    private static final AtomicReference<String> lastAuth = new AtomicReference<>();
    private static final AtomicReference<String> lastContentType = new AtomicReference<>();
    private static final AtomicReference<byte[]> lastBody = new AtomicReference<>();

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/audio/transcriptions", ex -> {
            lastPath.set(ex.getRequestURI().getPath());
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            lastContentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            lastBody.set(ex.getRequestBody().readAllBytes());
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, payload.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(payload);
            }
        });
        stub.start();
        // 故意带结尾斜杠：客户端应把它规范化掉，否则会拼出 //audio/transcriptions
        baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/";
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @BeforeEach
    void resetStub() {
        status = 200;
        body = "{\"text\":\"  今天有点累  \"}";
        lastPath.set(null);
        lastAuth.set(null);
        lastContentType.set(null);
        lastBody.set(null);
    }

    private OpenAiCompatibleAsrClient client(String url) {
        return new OpenAiCompatibleAsrClient(url, "sk-test", "whisper-1", Duration.ofSeconds(3));
    }

    private static AsrClient.AsrRequest request() {
        return new AsrClient.AsrRequest("fake-audio".getBytes(StandardCharsets.UTF_8), "audio/webm", 1500L);
    }

    @Test
    @DisplayName("成功：返回裁掉空白后的文本、模型名与真实耗时")
    void transcribeSucceeds() {
        AsrClient.AsrResult r = client(baseUrl).doTranscribe(request());

        assertEquals("今天有点累", r.text());
        assertEquals("whisper-1", r.model());
        assertTrue(r.costMs() >= 0, "耗时按真实往返计算");
    }

    @Test
    @DisplayName("base-url 结尾斜杠被规范化，路径不会出现双斜杠")
    void stripsTrailingSlash() {
        client(baseUrl).doTranscribe(request());

        assertEquals("/audio/transcriptions", lastPath.get());
    }

    @Test
    @DisplayName("请求按 OpenAI 兼容口径组装：Bearer 鉴权 + multipart 边界")
    void buildsOpenAiCompatibleRequest() {
        client(baseUrl).doTranscribe(request());

        assertEquals("Bearer sk-test", lastAuth.get());
        assertTrue(lastContentType.get().startsWith("multipart/form-data; boundary="), lastContentType.get());
    }

    @Test
    @DisplayName("multipart 体含 file 段（带 filename 与真实 MIME）与 model 段")
    void multipartCarriesFileAndModel() {
        client(baseUrl).doTranscribe(request());

        String payload = new String(lastBody.get(), StandardCharsets.UTF_8);
        assertTrue(payload.contains("name=\"file\"; filename=\"voice\""), payload);
        assertTrue(payload.contains("Content-Type: audio/webm"), "音频 MIME 原样带入，便于上游识别格式");
        assertTrue(payload.contains("name=\"model\""), payload);
        assertTrue(payload.contains("whisper-1"), payload);
        assertTrue(payload.contains("fake-audio"), "音频字节随请求体发出");
        assertTrue(payload.trim().endsWith("--"), "以结束边界收尾");
    }

    @Test
    @DisplayName("非 2xx 收口成 4004 并带状态码，不退化成 500")
    void nonSuccessStatusIsBusinessError() {
        status = 502;

        BizException ex = assertThrows(BizException.class, () -> client(baseUrl).doTranscribe(request()));

        assertEquals(ErrorCode.ASR_FAILED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("HTTP 502"), ex.getMessage());
    }

    @Test
    @DisplayName("上游回空文本时给可操作话术，不产出空日记")
    void emptyTextIsRejected() {
        body = "{\"text\":\"   \"}";

        BizException ex = assertThrows(BizException.class, () -> client(baseUrl).doTranscribe(request()));

        assertEquals(ErrorCode.ASR_FAILED, ex.getErrorCode());
        assertEquals("没听清说了什么，再试一次？", ex.getMessage());
    }

    @Test
    @DisplayName("缺 text 字段按空文本处理")
    void missingTextFieldIsRejected() {
        body = "{\"duration\":1.2}";

        BizException ex = assertThrows(BizException.class, () -> client(baseUrl).doTranscribe(request()));

        assertEquals(ErrorCode.ASR_FAILED, ex.getErrorCode());
    }

    @Test
    @DisplayName("响应体非法 JSON 时收口成通用话术，不冒泡解析异常")
    void malformedBodyIsOpaque() {
        body = "oops-not-json";

        BizException ex = assertThrows(BizException.class, () -> client(baseUrl).doTranscribe(request()));

        assertEquals(ErrorCode.ASR_FAILED, ex.getErrorCode());
        assertEquals("转写失败了，稍后再试或先用手写的", ex.getMessage());
    }

    @Test
    @DisplayName("上游不可达时收口成同一话术，不冒泡网络异常")
    void unreachableUpstreamIsOpaque() {
        OpenAiCompatibleAsrClient dead = new OpenAiCompatibleAsrClient(
                "http://127.0.0.1:1", "sk", "whisper-1", Duration.ofMillis(500));

        BizException ex = assertThrows(BizException.class, () -> dead.doTranscribe(request()));

        assertEquals(ErrorCode.ASR_FAILED, ex.getErrorCode());
        assertEquals("转写失败了，稍后再试或先用手写的", ex.getMessage());
    }

    @Test
    @DisplayName("经统一出入口 transcribe 调用（默认闸门关闭态）同样能通")
    void transcribeThroughGate() {
        AsrClient.AsrResult r = client(baseUrl).transcribe(request());

        assertNotNull(r);
        assertEquals("今天有点累", r.text());
    }
}
