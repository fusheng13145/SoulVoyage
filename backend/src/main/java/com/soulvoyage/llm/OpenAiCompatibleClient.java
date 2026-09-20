package com.soulvoyage.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.orchestrator.agent.LlmUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 兼容网关（DashScope compatible-mode / 自建网关均适用）。
 * 启用：SV_LLM_PROVIDER=openai + SV_LLM_BASE_URL + SV_LLM_API_KEY + SV_LLM_MODEL
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "soulvoyage.llm.provider", havingValue = "openai")
public class OpenAiCompatibleClient implements LlmClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public OpenAiCompatibleClient(@Value("${soulvoyage.llm.base-url}") String baseUrl,
                                  @Value("${soulvoyage.llm.api-key}") String apiKey,
                                  @Value("${soulvoyage.llm.model}") String model,
                                  @Value("${soulvoyage.llm.timeout:60s}") Duration timeout) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
    }

    @Override
    public LlmResponse doChat(LlmRequest req) {
        long t0 = System.currentTimeMillis();
        try {
            var resp = http.send(request(body(req, false, false), false), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new LlmUnavailableException("LLM HTTP " + resp.statusCode() + ": "
                        + snippet(resp.body()));
            }
            JsonNode root = mapper.readTree(resp.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            return new LlmResponse(content, root.path("model").asText(model),
                    root.path("usage").path("prompt_tokens").asInt(0),
                    root.path("usage").path("completion_tokens").asInt(0),
                    System.currentTimeMillis() - t0);
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new LlmUnavailableException("LLM 超时", e);
        } catch (Exception e) {
            throw new LlmUnavailableException("LLM 调用失败", e);
        }
    }

    /**
     * 真流式：{@code stream:true} + {@code stream_options.include_usage}，逐行吃 SSE，
     * 把 delta.content 原样交给 sink，末尾从带 usage 的收尾 chunk 取真实计费口径。
     * 首 token 之前的 HTTP 非 2xx 直接抛（此时用户还没看到半个字，可由上层降级）。
     * 收尾必须以 {@code [DONE]} 为准：连接被中途掐断时最后一块可能只吐了一半，
     * 且带 usage 的收尾 chunk 必然还没到——这种"看着完整"的半句比报错更坏，按失败上抛换取降级。
     */
    @Override
    public LlmResponse doStream(LlmRequest req, TokenSink sink) {
        long t0 = System.currentTimeMillis();
        StringBuilder full = new StringBuilder();
        int[] usage = new int[2];   // [prompt_tokens, completion_tokens]
        boolean done = false;
        try {
            var resp = http.send(request(body(req, true, true), true), HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                String err = resp.body() == null ? "" : new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new LlmUnavailableException("LLM HTTP " + resp.statusCode() + ": " + snippet(err));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || !line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.equals("[DONE]")) {
                        done = true;
                        break;
                    }
                    JsonNode chunk = mapper.readTree(payload);
                    JsonNode u = chunk.path("usage");
                    if (!u.isMissingNode() && !u.isNull()) {
                        usage[0] = u.path("prompt_tokens").asInt(usage[0]);
                        usage[1] = u.path("completion_tokens").asInt(usage[1]);
                    }
                    for (JsonNode choice : chunk.path("choices")) {
                        String delta = choice.path("delta").path("content").asText("");
                        if (delta.isEmpty()) continue;
                        full.append(delta);
                        sink.onDelta(delta);
                    }
                }
            }
            if (!done) {
                throw new LlmUnavailableException("LLM 流提前断开（未收到 [DONE]），已收 " + full.length() + " 字");
            }
            sink.onComplete(full.toString());
            return new LlmResponse(full.toString(), model, usage[0], usage[1],
                    System.currentTimeMillis() - t0);
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new LlmUnavailableException("LLM 超时", e);
        } catch (Exception e) {
            throw new LlmUnavailableException("LLM 流式调用失败", e);
        }
    }

    private String body(LlmRequest req, boolean stream, boolean withUsage) throws Exception {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("model", model);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", req.system()),
                Map.of("role", "user", "content", req.user())));
        payload.put("temperature", 0.3);
        payload.put("max_tokens", req.maxTokens() > 0 ? req.maxTokens() : 1600);
        if (stream) {
            payload.put("stream", true);
            if (withUsage) payload.put("stream_options", Map.of("include_usage", true));
        }
        return mapper.writeValueAsString(payload);
    }

    private HttpRequest request(String body, boolean stream) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                // 非流式请求也声明 SSE 会让部分供应商真的按 SSE 回，解析口径跟着翻车
                .header("Accept", stream ? MediaType.TEXT_EVENT_STREAM_VALUE : MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private static String snippet(String s) {
        return s == null ? "" : s.substring(0, Math.min(200, s.length()));
    }
}
