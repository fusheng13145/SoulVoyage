package com.soulvoyage.asr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * OpenAI Audio Transcriptions 兼容网关（Whisper / DashScope compatible-mode / 自建网关均适用）。
 * 启用：SV_ASR_PROVIDER=openai + SV_ASR_BASE_URL + SV_ASR_API_KEY + SV_ASR_MODEL
 * （base-url 与 api-key 缺省沿用 LLM 那一对，同一家供应商通常两个接口一起开）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "soulvoyage.asr.provider", havingValue = "openai")
public class OpenAiCompatibleAsrClient implements AsrClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public OpenAiCompatibleAsrClient(@Value("${soulvoyage.asr.base-url:}") String baseUrl,
                                     @Value("${soulvoyage.asr.api-key:}") String apiKey,
                                     @Value("${soulvoyage.asr.model:whisper-1}") String model,
                                     @Value("${soulvoyage.asr.timeout:30s}") Duration timeout) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
    }

    @Override
    public AsrResult doTranscribe(AsrRequest req) {
        long t0 = System.currentTimeMillis();
        try {
            byte[] body = multipart(req);
            var resp = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/audio/transcriptions"))
                            .timeout(timeout)
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new BizException(ErrorCode.ASR_FAILED,
                        "转写服务暂时不可用（HTTP " + resp.statusCode() + "）");
            }
            String text = mapper.readTree(resp.body()).path("text").asText("").trim();
            if (text.isEmpty()) throw new BizException(ErrorCode.ASR_FAILED, "没听清说了什么，再试一次？");
            return new AsrResult(text, model, System.currentTimeMillis() - t0);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("asr call failed: {}", e.toString());
            throw new BizException(ErrorCode.ASR_FAILED, "转写失败了，稍后再试或先用手写的");
        }
    }

    private static final String BOUNDARY = "----SoulVoyageAsrBoundary7MA4YWxkTrZu0gW";

    private byte[] multipart(AsrRequest req) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String head = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"voice\"\r\n"
                + "Content-Type: " + req.contentType() + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(req.audio());
        out.write(("\r\n--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n"
                + model + "\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
