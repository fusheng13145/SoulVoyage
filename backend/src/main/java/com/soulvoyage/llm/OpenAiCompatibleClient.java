package com.soulvoyage.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.orchestrator.agent.LlmUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    public LlmResponse chat(LlmRequest req) {
        long t0 = System.currentTimeMillis();
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", req.system()),
                            Map.of("role", "user", "content", req.user())),
                    "temperature", 0.3,
                    "max_tokens", req.maxTokens() > 0 ? req.maxTokens() : 1600));
            var request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/chat/completions"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new LlmUnavailableException("LLM HTTP " + resp.statusCode() + ": "
                        + resp.body().substring(0, Math.min(200, resp.body().length())));
            }
            JsonNode root = mapper.readTree(resp.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            return new LlmResponse(content, model,
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
}
