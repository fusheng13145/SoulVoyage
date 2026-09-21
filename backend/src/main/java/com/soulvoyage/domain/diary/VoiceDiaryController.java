package com.soulvoyage.domain.diary;

import com.soulvoyage.asr.AsrClient;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M11 语音日记（手册 §11.3 首项）：录音 → 转写文本 → 交用户校对 → 走既有 DIARY_PIPELINE。
 *
 * 两条刻意的设计约束：
 * 1) 音频不落盘、不入库、不进日志——声纹属生物特征，转写完成即随请求作用域消失；
 *    注销即遗忘因此无需为语音新增任何销毁口子（密文侧与手写日记同域）。
 * 2) 转写与建档分成两步：ASR 必错字，"先给人看一眼再交给 Agent"是产品红线，
 *    也让语音输入复用日记的全部既有护栏（危机一票拦截、限流、加密落库）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/diaries")
public class VoiceDiaryController {

    /** 浏览器 MediaRecorder 与 iOS 录音的常见容器；只收音频，杜绝借上传口投喂别的字节流 */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "audio/webm", "audio/ogg", "audio/mp4", "audio/x-m4a", "audio/m4a",
            "audio/mp4a-latm", "audio/aac", "audio/mpeg", "audio/wav", "audio/x-wav");

    public static final int RATE_PER_MINUTE = 10;      // 用户级滑动窗：语音上行比打字贵一个量级
    public static final long MAX_DURATION_MS = 90_000; // 产品口径：一段最多 90 秒

    private final AsrClient asr;
    private final MeterRegistry reg;
    private final long maxBytes;
    private final Map<Long, Deque<Instant>> rateWindows = new ConcurrentHashMap<>();

    public VoiceDiaryController(AsrClient asr, MeterRegistry reg,
                                @Value("${soulvoyage.asr.max-bytes:5242880}") long maxBytes) {
        this.asr = asr;
        this.reg = reg;
        this.maxBytes = maxBytes;
    }

    @PostMapping(value = "/voice-transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> transcribe(@AuthenticationPrincipal AuthPrincipal p,
                                                       @RequestPart(name = "file", required = false) MultipartFile file,
                                                       @RequestParam(required = false) Long durationMs) {
        String type = file == null || file.getContentType() == null
                ? "" : file.getContentType().split(";")[0].trim().toLowerCase();
        if (file == null || file.isEmpty()) throw new BizException(ErrorCode.BAD_PARAMS, "没收到录音呢");
        if (!ALLOWED_TYPES.contains(type)) throw new BizException(ErrorCode.BAD_PARAMS, "只支持语音文件哦");
        if (file.getSize() > maxBytes) {
            throw new BizException(ErrorCode.BAD_PARAMS,
                    "这段录音太大了（上限 " + maxBytes / 1024 / 1024 + "MB），换一段短一点的");
        }
        if (durationMs != null && (durationMs <= 0 || durationMs > MAX_DURATION_MS)) {
            throw new BizException(ErrorCode.BAD_PARAMS, "一段录音最长 " + MAX_DURATION_MS / 1000 + " 秒");
        }
        admitRate(p.userId());

        byte[] audio;
        try {
            audio = file.getBytes();
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_PARAMS, "录音没能读出来，再试一次？");
        }
        AsrClient.AsrResult r = asr.transcribe(
                new AsrClient.AsrRequest(audio, type, durationMs == null ? 0 : durationMs));
        reg.counter("sv.asr.call", "model", r.model() == null ? "unknown" : r.model()).increment();
        reg.timer("sv.asr.latency").record(Duration.ofMillis(r.costMs()));
        log.info("voice transcribed user={} bytes={} chars={} cost={}ms",
                file.getSize(), audio.length, r.text().length(), r.costMs());

        return ApiResponse.ok(Map.of(
                "text", r.text(),
                "model", r.model(),
                "costMs", r.costMs(),
                "durationMs", durationMs == null ? 0 : durationMs));
    }

    /** 与漫聊同构的用户级滑动窗：换设备/换会话都在同一窗内照限 */
    private void admitRate(long userId) {
        Deque<Instant> q = rateWindows.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            Instant now = Instant.now();
            while (!q.isEmpty() && Duration.between(q.peekFirst(), now).toSeconds() >= 60) q.pollFirst();
            if (q.size() >= RATE_PER_MINUTE) {
                throw new BizException(ErrorCode.LLM_RATE_LIMIT, "转写得太快啦，稍等一会儿再录");
            }
            q.addLast(now);
        }
    }
}
