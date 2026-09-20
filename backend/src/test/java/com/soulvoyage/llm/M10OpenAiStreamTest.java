package com.soulvoyage.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.orchestrator.agent.LlmUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAI 兼容网关的真路径（手册 §2.2 / §九）：本机起一个讲 OpenAI Chat Completions + SSE 协议的服务端，
 * 让 {@link OpenAiCompatibleClient} 走真 HTTP、真分块、真 usage，而不是只在 Mock 回放器里自证。
 * 判据按供应商真实会做的六件事拆：正常流、非流式、首 token 前拒绝、卡死超时、流被掐断、不给 usage。
 */
class M10OpenAiStreamTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static HttpServer server;
    private static int port;

    /** 本次服务端该怎么回（由用例设定，静态分派所以不放实例上） */
    private static volatile String mode = "stream";
    private static volatile String lastBody;
    private static volatile String lastAuth;
    private static volatile String lastAccept;

    @BeforeAll
    static void startProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/v1/chat/completions", M10OpenAiStreamTest::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopProvider() {
        server.stop(0);
    }

    @BeforeEach
    void resetProbe() {
        mode = "stream";
        lastBody = null;
        lastAuth = null;
        lastAccept = null;
    }

    /** 不挂 Spring 上下文：网关的供应商语义与业务库无关，直连构造才等于真接入那一刻的调用 */
    private static OpenAiCompatibleClient client() {
        return client(Duration.ofSeconds(5));
    }

    private static OpenAiCompatibleClient client(Duration timeout) {
        return new OpenAiCompatibleClient("http://127.0.0.1:" + port + "/v1", "sk-test", "qoder-stub", timeout);
    }

    private static final LlmClient.LlmRequest REQ = new LlmClient.LlmRequest("companion_v1", "系统", "用户", 300);

    // ---- 用例 ----

    @Test
    void streamForwardsEveryDeltaAndBillsFromRealUsageChunk() throws Exception {
        mode = "stream";
        var deltas = new ArrayList<String>();
        var completed = new StringBuilder();
        var resp = client().doStream(REQ, new LlmClient.TokenSink() {
            @Override
            public void onDelta(String d) {
                deltas.add(d);
            }

            @Override
            public void onComplete(String full) {
                completed.append(full);
            }
        });

        String raw = "{\"reply\":\"我在。慢慢说，今天发生了什么？\"}";
        assertEquals(raw, resp.content(), "分块重组后必须与模型原文逐字一致");
        assertEquals(raw, completed.toString(), "onComplete 交回同一份全文，回合流据此收尾");
        assertTrue(deltas.size() >= 4, "真流式：应是多块增量，实际 " + deltas.size() + " 块");
        assertEquals(raw, String.join("", deltas), "增量不得丢字、不得重排");
        assertEquals(137, resp.tokensIn(), "计费口径取收尾 chunk 的 prompt_tokens");
        assertEquals(24, resp.tokensOut());
        assertEquals("qoder-stub", resp.model());

        var sent = M.readTree(lastBody);
        assertTrue(sent.path("stream").asBoolean(), "流式请求要声明 stream");
        assertTrue(sent.path("stream_options").path("include_usage").asBoolean(), "不带 include_usage 就拿不到真账单");
        assertEquals("qoder-stub", sent.path("model").asText());
        assertEquals(300, sent.path("max_tokens").asInt(), "调用方的 maxTokens 要落到请求里");
        assertEquals(2, sent.path("messages").size());
        assertEquals("Bearer sk-test", lastAuth);
        assertEquals("text/event-stream", lastAccept);
    }

    @Test
    void nonStreamChatParsesAssistantContentAndUsage() throws Exception {
        mode = "json";
        var resp = client().doChat(REQ);
        assertEquals("{\"advice\":\"先写下最刺人的那一句\"}", resp.content());
        assertEquals(91, resp.tokensIn());
        assertEquals(12, resp.tokensOut());
        var sent = M.readTree(lastBody);
        assertFalse(sent.has("stream"), "非流式请求不该带 stream");
        assertEquals("application/json", lastAccept);
    }

    @Test
    void providerRejectionBeforeFirstTokenSurfacesAsUnavailable() {
        mode = "reject";
        var e = assertThrows(LlmUnavailableException.class, () -> client().doStream(REQ, d -> {
        }));
        assertTrue(e.getMessage().contains("429"), "状态码得留在异常里供 4003 降级定位：" + e.getMessage());
    }

    @Test
    void stalledProviderIsCutByClientTimeoutInsteadOfHangingTheTurn() {
        mode = "stall";
        var e = assertThrows(LlmUnavailableException.class,
                () -> client(Duration.ofMillis(400)).doStream(REQ, d -> {
                }));
        assertTrue(e.getMessage().contains("超时"), e.getMessage());
    }

    /** 真接入最阴的一种失败：连接断在半路，文本看着像说完了、usage 永远不到——按失败上抛换取降级 */
    @Test
    void truncatedStreamWithoutDoneNeverClaimsCompletion() {
        mode = "truncate";
        var deltas = new ArrayList<String>();
        var completed = new ArrayList<String>();
        var e = assertThrows(LlmUnavailableException.class,
                () -> client().doStream(REQ, new LlmClient.TokenSink() {
                    @Override
                    public void onDelta(String d) {
                        deltas.add(d);
                    }

                    @Override
                    public void onComplete(String full) {
                        completed.add(full);
                    }
                }));
        assertTrue(e.getMessage().contains("[DONE]"), e.getMessage());
        assertTrue(completed.isEmpty(), "半句不许被当作完整回复收尾");
        assertEquals(2, deltas.size(), "已发出的增量保持原样，由上层用 turn_done 的权威全文覆盖");
    }

    @Test
    void usagelessProviderReportsZeroTokensInsteadOfFabricatingCost() {
        mode = "no_usage";
        var resp = client().doStream(REQ, d -> {
        });
        assertEquals("好，我听见了。", resp.content());
        assertEquals(0, resp.tokensIn());
        assertEquals(0, resp.tokensOut());
    }

    // ---- 服务端脚本：按 mode 决定这一轮回什么 ----

    private static void handle(HttpExchange ex) throws IOException {
        lastBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastAuth = ex.getRequestHeaders().getFirst("Authorization");
        lastAccept = ex.getRequestHeaders().getFirst("Accept");
        switch (mode) {
            case "json" -> writeJson(ex, 200, M.writeValueAsString(Map.of(
                    "model", "qoder-stub-upstream",
                    "choices", List.of(Map.of("message", Map.of("role", "assistant",
                            "content", "{\"advice\":\"先写下最刺人的那一句\"}"))),
                    "usage", Map.of("prompt_tokens", 91, "completion_tokens", 12))));
            case "reject" -> writeJson(ex, 429,
                    "{\"error\":{\"message\":\"You have exceeded your current request limit\",\"type\":\"rate_limit_error\"}}");
            case "stall" -> {
                sleep(1500);   // 比用例里的客户端超时更久：模拟上游排队/网络黑洞
                writeJson(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"迟到的回复\"}}]}");
            }
            case "truncate" -> {
                openSse(ex);
                sse(ex, chunk("我在。慢"));
                sse(ex, chunk("慢说"));   // 没有 finish_reason、没有 [DONE]，连接就地断开
            }
            case "no_usage" -> {
                openSse(ex);
                sse(ex, chunk("好，我"));
                sse(ex, chunk("听见了。"));
                sse(ex, "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
                sse(ex, "[DONE]");
            }
            default -> {
                openSse(ex);
                for (String piece : List.of("{\"reply\":\"", "我在。", "慢慢说，", "今天发生了什么？", "\"}"))
                    sse(ex, chunk(piece));
                sse(ex, "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
                // OpenAI 的收尾 chunk：只有 usage，choices 为空——很多实现会漏读，这里钉住
                sse(ex, "{\"choices\":[],\"usage\":{\"prompt_tokens\":137,\"completion_tokens\":24}}");
                sse(ex, "[DONE]");
            }
        }
        ex.close();
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 一个 content 增量 chunk */
    private static String chunk(String content) throws IOException {
        return M.writeValueAsString(Map.of("choices",
                List.of(Map.of("index", 0, "delta", Map.of("content", content)))));
    }

    private static void openSse(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream;charset=utf-8");
        ex.sendResponseHeaders(200, 0);   // chunked：边写边 flush，才是真流式
    }

    private static void sse(HttpExchange ex, String payload) throws IOException {
        var os = ex.getResponseBody();
        os.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static void writeJson(HttpExchange ex, int status, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json;charset=utf-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().flush();
    }
}
