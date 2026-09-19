package com.soulvoyage.domain.emotion;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 16 情绪盘闭集（与前端 utils/emotions.ts 及 DS2 色源同源）：
 * code → 中文标签（emotion_trajectory.primary_emotion 存中文，与 EMOTION Agent 输出一致）+ 效价/强度锚点。
 * 打卡 → 轨迹点派生、热力图着色共用此表；前端如有调整须同步修改。
 */
public final class EmotionCatalog {

    public record Emotion(String code, String label, double valence, double intensity) {}

    private static final Map<String, Emotion> BY_CODE = Stream.of(
            new Emotion("JOY", "喜悦", 0.8, 0.7),
            new Emotion("CALM", "平静", 0.5, 0.2),
            new Emotion("ANXIETY", "焦虑", -0.5, 0.7),
            new Emotion("ANGER", "愤怒", -0.6, 0.8),
            new Emotion("SADNESS", "悲伤", -0.6, 0.5),
            new Emotion("FEAR", "恐惧", -0.6, 0.75),
            new Emotion("SHAME", "羞耻", -0.7, 0.65),
            new Emotion("LONELY", "孤独", -0.4, 0.45),
            new Emotion("NUMB", "麻木", -0.2, 0.25),
            new Emotion("GRIEVANCE", "委屈", -0.5, 0.55),
            new Emotion("PRESSURE", "压力", -0.4, 0.7),
            new Emotion("GRATITUDE", "感恩", 0.7, 0.45),
            new Emotion("CONFUSED", "困惑", -0.15, 0.4),
            new Emotion("TIRED", "疲惫", -0.3, 0.4),
            new Emotion("EXPECT", "期待", 0.6, 0.6),
            new Emotion("BORED", "无聊", -0.05, 0.2)
    ).collect(Collectors.toUnmodifiableMap(e -> e.code(), Function.identity()));

    private EmotionCatalog() {}

    public static Optional<Emotion> byCode(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(BY_CODE.get(code.toUpperCase()));
    }
}
