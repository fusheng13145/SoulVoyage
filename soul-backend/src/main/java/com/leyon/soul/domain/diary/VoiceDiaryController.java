package com.leyon.soul.domain.diary;

import com.leyon.soul.asr.AsrClient;
import com.leyon.soul.auth.AuthPrincipal;
import com.leyon.soul.common.api.ApiResponse;
import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import com.leyon.soul.domain.diary.dto.DiaryDtos.VoiceTranscriptionResp;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.Set;

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
    private final com.leyon.soul.common.state.SlidingWindowLimiter windowLimiter;
    private final long maxBytes;

    public VoiceDiaryController(AsrClient asr, MeterRegistry reg,
                                com.leyon.soul.common.state.SlidingWindowLimiter windowLimiter,
                                @Value("${soul.asr.max-bytes:5242880}") long maxBytes) {
        this.asr = asr;
        this.reg = reg;
        this.windowLimiter = windowLimiter;
        this.maxBytes = maxBytes;
    }

    @PostMapping(value = "/voice-transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<VoiceTranscriptionResp> transcribe(
            @AuthenticationPrincipal AuthPrincipal p,
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
        // B-17：Content-Type 可伪造，必须用容器魔数二次确认——避免借语音口投喂任意字节流
        if (!matchesMagic(audio, type)) throw new BizException(ErrorCode.BAD_PARAMS, "只支持语音文件哦");
        AsrClient.AsrResult r = asr.transcribe(
                new AsrClient.AsrRequest(audio, type, durationMs == null ? 0 : durationMs));
        reg.counter("sv.asr.call", "model", r.model() == null ? "unknown" : r.model()).increment();
        reg.timer("sv.asr.latency").record(Duration.ofMillis(r.costMs()));
        log.info("voice transcribed user={} bytes={} chars={} cost={}ms",
                file.getSize(), audio.length, r.text().length(), r.costMs());

        return ApiResponse.ok(new VoiceTranscriptionResp(
                r.text(), r.model(), r.costMs(), durationMs == null ? 0 : durationMs));
    }

    /** 与漫聊同构的用户级滑动窗：换设备/换会话都在同一窗内照限；多节点时同一 Redis 窗 */
    private void admitRate(long userId) {
        if (!windowLimiter.tryAcquire("voice:" + userId, RATE_PER_MINUTE, Duration.ofSeconds(60))) {
            throw new BizException(ErrorCode.LLM_RATE_LIMIT, "转写得太快啦，稍等一会儿再录");
        }
    }

    /** B-17 魔数校验：声明类型与容器头必须一致（webm/ogg/mp4/m4a/aac/mp3/wav） */
    private static boolean matchesMagic(byte[] b, String type) {
        if (b == null || b.length < 4) return false;
        return switch (type) {
            case "audio/webm" -> (b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45
                    && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3;
            case "audio/ogg" -> b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S';
            case "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/mp4a-latm" -> isMp4(b);
            case "audio/aac" -> isMp4(b) || isAdts(b);
            case "audio/mpeg" -> (b.length >= 3 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') || isMpegFrame(b);
            case "audio/wav", "audio/x-wav" -> b.length >= 12
                    && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E';
            default -> false;
        };
    }

    private static boolean isMp4(byte[] b) {
        return b.length >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
    }

    private static boolean isAdts(byte[] b) {
        return b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xF6) == 0xF0;
    }

    private static boolean isMpegFrame(byte[] b) {
        return b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0;
    }
}
