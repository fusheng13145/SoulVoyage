package com.soulvoyage.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 增量取值器：只透出目标顶层字段，任何切分位置与转义都不许丢字或漏结构 */
class StreamedJsonFieldTest {

    /** 按给定块大小切喂料，返回拼起来的外发文本 */
    private static String feedAll(String raw, String key, int chunk) {
        var field = new StreamedJsonField(key);
        var out = new StringBuilder();
        for (int i = 0; i < raw.length(); i += chunk) {
            out.append(field.feed(raw.substring(i, Math.min(raw.length(), i + chunk))));
        }
        return out.toString();
    }

    @Test
    void streamsOnlyTopLevelReplyField() {
        String raw = "```json\n{\"reply\":\"早啊，今天想聊点什么？\", \"moodTag\":\"FOLLOW\"}\n```";
        for (int chunk : new int[]{1, 3, 7, 64}) {
            assertEquals("早啊，今天想聊点什么？", feedAll(raw, "reply", chunk), "chunk=" + chunk);
        }
        var field = new StreamedJsonField("reply");
        int emissions = 0;
        for (int i = 0; i < raw.length(); i++) {
            if (!field.feed(String.valueOf(raw.charAt(i))).isEmpty()) emissions++;
        }
        assertTrue(emissions > 5, "逐字符喂料时应多次外发，而不是攒成一次");
    }

    @Test
    void decodesEscapesWhileStreaming() {
        String raw = "{\"reply\":\"第一行\\n制表\\t符\\\"引号\\\" 中文\\u4e2d\\u6587 反斜杠\\\\\",\"moodTag\":\"FOLLOW\"}";
        assertEquals("第一行\n制表\t符\"引号\" 中文中文 反斜杠\\", feedAll(raw, "reply", 5));
    }

    @Test
    void ignoresNestedAndNonStringSiblings() {
        // 嵌套对象里的 reply 不是目标；数字字段之后的同名字段也不该被错配
        String raw = "{\"meta\":{\"reply\":\"内部不许外发\"},\"count\":3,\"tone\":12,\"reply\":\"真的这句\"}";
        assertEquals("真的这句", feedAll(raw, "reply", 4));
    }

    @Test
    void missingFieldEmitsNothingAndStopsAtObjectEnd() {
        var field = new StreamedJsonField("reply");
        assertEquals("", field.feed("{\"emotionCue\":\"\",\"other\":1}"));
        assertTrue(field.isDone(), "顶层对象收尾后应停止查找");
        assertEquals("", field.feed("{\"reply\":\"迟到也不发\"}"), "已结束后不再外发");
    }

    @Test
    void nonStreamingProviderFallsBackToWholeContent() {
        var collected = new StringBuilder();
        LlmClient stub = req -> new LlmClient.LlmResponse("{\"reply\":\"整段给出\"}", "stub", 3, 3, 0);
        var resp = stub.stream(new LlmClient.LlmRequest("t", "s", "u", 10), collected::append);
        assertEquals(resp.content(), collected.toString(), "默认回落应等价于整段给出一次");
    }
}
