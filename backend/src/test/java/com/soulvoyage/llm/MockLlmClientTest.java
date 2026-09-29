package com.soulvoyage.llm;

import com.soulvoyage.orchestrator.agent.LlmUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开发期 Mock 回放器的分支覆盖测试：七个模板各自的取值分支（关键词族、情绪档位、评分档位、
 * 候选有无、素材齐缺）与兜底路径都要走到——它不是"测试替身"，而是全链路回归里真实被调用的
 * 那条通路（契约测试的回放基准），分支漏测会让"某类输入在开发期永远没走过"成为盲区。
 *
 * <p>不走 {@code chat()}/{@code stream()}（那会经过 LlmGuard 与 LlmUsageCollector 的静态归集，
 * 给别的测试留下用量残留），直接调 doChat/doStream。
 */
class MockLlmClientTest {

    private final MockLlmClient client = new MockLlmClient();

    private static LlmClient.LlmRequest req(String template, String user) {
        return new LlmClient.LlmRequest(template, "系统提示", user, 512);
    }

    private String chat(String template, String user) {
        return client.doChat(req(template, user)).content();
    }

    @Test
    @DisplayName("emotion_v1：五类关键词族各出一条，未命中任何词时回落到平静兜底")
    void emotionCoversEveryKeywordFamily() {
        assertTrue(chat("emotion_v1", "跟室友吵了一架，真气").contains("\"primaryEmotion\":\"愤怒\""));
        assertTrue(chat("emotion_v1", "考试和 ddl 堆一起了").contains("\"primaryEmotion\":\"焦虑\""));
        assertTrue(chat("emotion_v1", "好难过，想哭").contains("\"primaryEmotion\":\"悲伤\""));
        assertTrue(chat("emotion_v1", "今天很顺利，谢谢").contains("\"primaryEmotion\":\"喜悦\""));
        assertTrue(chat("emotion_v1", "很累，提不起劲").contains("\"primaryEmotion\":\"麻木\""));
        // 注意：判词是单字 contains，任何含「气」的文本（如「天气」）都会命中愤怒族，
        // 故此处必须选一段不含任何关键词族的文本，才真正走 default 档
        assertTrue(chat("emotion_v1", "普通的一天").contains("\"primaryEmotion\":\"平静\""),
                "无事件词时走 default 档");
    }

    @Test
    @DisplayName("emotion_v1：输出的 JSON 被 markdown 围栏包裹，逼解析器处理噪声")
    void emotionOutputKeepsFences() {
        String out = chat("emotion_v1", "还行");

        assertTrue(out.startsWith("```json"), out);
        assertTrue(out.endsWith("```"), out);
    }

    @Test
    @DisplayName("trace_v1：候选卡多于上限时截断，模板数 0/1/2 三种取法都走到")
    void traceCoversCandidateShapes() {
        String user = """
                {"emotionResult":{"primaryEmotion":"焦虑"},
                 "diaryText":"今天在图书馆坐了一整天，效率不高，晚上回宿舍又开始担心明天的汇报。",
                 "stressorHint":["学业压力"],
                 "kgCandidates":[
                   {"name":"以偏概全","kgNodeId":"d2","typicalSignature":"我总是做不好",
                    "socraticTemplates":["只有这一件事没做好吗？"]},
                   {"name":"灾难化","kgNodeId":"d1","typicalSignature":"要是搞砸了怎么办",
                    "socraticTemplates":["如果最坏的情况发生了，你会怎么应对？","有哪一次你担心的事其实没发生？"]},
                   {"name":"应该式","kgNodeId":"d3","typicalSignature":"我必须做到最好",
                    "socraticTemplate":"这个必须是谁定的？"}]}""";
        // 候选顺序有讲究：alternate（第二条模板）只在 i 为奇数的轮次启用，
        // 故带双模板的候选必须落在奇数索引，否则这条分支永远不会被执行

        String out = chat("trace_v1", user);

        assertTrue(out.contains("\"source\":\"学业压力\""), "事件标签命中时产出压力源条目");
        assertTrue(out.contains("有哪一次你担心的事其实没发生？"), "两个模板时第二条作备选");
        assertTrue(out.contains("这个必须是谁定的？"), "无模板数组时回落到单模板字段");
        assertTrue(out.contains("日记摘录"), "长日记被截断后用于摘要");
        assertTrue(!out.contains("insufficientEvidence"), "候选存在时不标证据不足");
    }

    @Test
    @DisplayName("trace_v1：JSON 损坏或结构为空时按空对象处理，并标出证据不足")
    void traceFallsBackOnBrokenInput() {
        String broken = chat("trace_v1", "这不是 JSON");

        assertTrue(broken.contains("\"insufficientEvidence\":true"), broken);
        assertTrue(broken.contains("未匹配到候选认知误区卡"), "无候选时的兜底摘要");
        assertTrue(broken.contains("如果一周后再看这件事"), "苏格拉底式提问的兜底问句");

        String empty = chat("trace_v1", "{}");
        assertTrue(empty.contains("\"insufficientEvidence\":true"), empty);
    }

    @Test
    @DisplayName("npc_v1：四档情绪各自给出不同台词")
    void npcCoversEveryMoodGear() {
        String body = "{\"mood\":\"%s\",\"transcript\":[{\"user\":\"你先听我说完\"}]}";

        assertTrue(chat("npc_v1", body.formatted("ESCALATED")).contains("受够了"));
        assertTrue(chat("npc_v1", body.formatted("DISSATISFIED")).contains("先说说你打算怎么配合"));
        assertTrue(chat("npc_v1", body.formatted("SOFTENED")).contains("是我之前把话说死了"));
        assertTrue(chat("npc_v1", body.formatted("NEUTRAL")).contains("就事论事"));
    }

    @Test
    @DisplayName("npc_v1：取 transcript 里最后一条 user 发言回引，无 user 字段时用空串")
    void npcEchoesLastUserTurn() {
        String out = chat("npc_v1",
                "{\"mood\":\"NEUTRAL\",\"transcript\":[{\"user\":\"第一句\"},{\"npc\":\"回应\"}]}");

        assertTrue(out.contains("第一句"), "跳过没有 user 的轮次，回引上一条用户发言");
    }

    @Test
    @DisplayName("review_v1：五个评分档位与 moments/rewrites 上限都走到")
    void reviewCoversEveryGradeAndCap() {
        String user = """
                {"goalDimensions":["LISTEN","EMPATHY","CONCESSION"],
                 "caseCandidates":[{"kgNodeId":"cc_apology_repair"}],
                 "turns":[
                   {"turn":1,"stateTag":"BOUNDARY_SET","userText":"我注意到这周有三次你没回我消息，我感到有点急"},
                   {"turn":2,"stateTag":"DE_ESCALATION","userText":"我先说我的感受，你听着就行"},
                   {"turn":3,"stateTag":"ACKNOWLEDGED","userText":"我听到你说很累了"},
                   {"turn":4,"stateTag":"CONFLICT_UP","userText":"你总是这样，根本不在乎别人"},
                   {"turn":5,"stateTag":"ACKNOWLEDGED","userText":"嗯，我在听"},
                   {"turn":6,"stateTag":"WHATEVER","userText":"随便吧"},
                   {"turn":7,"stateTag":"CONFLICT_UP","userText":"你就是不改"},
                   {"turn":8,"stateTag":"CONFLICT_UP","userText":"说了多少次了"},
                   {"turn":9,"stateTag":"CONFLICT_UP","userText":"算了不说了"},
                   {"turn":10,"stateTag":"CONFLICT_UP","userText":"反正你也不听"}]}""";

        String out = chat("simulate_review_v1", user);

        assertTrue(out.contains("\"grade\":\"A\""), "立边界与缓和都是 A");
        assertTrue(out.contains("\"grade\":\"B\""));
        assertTrue(out.contains("\"grade\":\"D\""), "冲突升级为 D");
        assertTrue(out.contains("\"grade\":\"C\""), "未知档位走 default 的 C");
        assertTrue(out.contains("\"referenceCase\":\"cc_apology_repair\""), "有案例候选时回引");
        assertTrue(out.contains("我注意到") || out.contains("（mock）"), "改写建议存在");
        assertTrue(out.contains("avgScore"));
    }

    @Test
    @DisplayName("review_v1：无 goalDimensions、无案例、无轮次时各自走兜底")
    void reviewFallsBackOnEmptyInput() {
        String out = chat("simulate_review_v1", "{\"turns\":[]}");

        assertTrue(out.contains("\"avgScore\":60"), "零轮次给中性分而非除零");
        assertTrue(out.contains("全程保持了表达意愿"), "无 A 级时给兜底优点");
        assertTrue(out.contains("请求多为模糊提议"), "无 D 级时给兜底短板");
        assertFalse(out.contains("referenceCase"), "无案例候选时不产出引用");
    }

    @Test
    @DisplayName("support_v1：候选足够时按候选组装，科普引用真实 node id")
    void supportUsesInjectedCandidates() {
        String user = """
                {"emotionResult":{"primaryEmotion":"焦虑"},
                 "candidates":[
                   {"id":"ex_ground_1","name":"感官着陆","durationMin":5},
                   {"id":"ex_ground_2","name":"4-7-8 呼吸","durationMin":3},
                   {"id":"ex_ground_3","name":"身体扫描","durationMin":8},
                   {"id":"ex_ground_4","name":"不该出现","durationMin":9}],
                 "psyCandidates":[
                   {"kgNodeId":"psy_self_regulation_body","title":"焦虑时身体在发生什么",
                    "summary":"身体先于想法反应。","microAction":"把手放在胸口做三次慢呼吸。"}]}""";

        String out = chat("support_v1", user);

        assertTrue(out.contains("ex_ground_1") && out.contains("ex_ground_3"), "取前三条候选");
        assertTrue(!out.contains("ex_ground_4"), "超出上限的候选被截断");
        assertTrue(out.contains("\"kgSource\":\"node:psy_self_regulation_body\""), "科普来源可被反向校验回放");
        assertTrue(out.contains("\"planDays\":3"), "排成三天计划");
        assertTrue(out.contains("\"disclaimer\":true"));
    }

    @Test
    @DisplayName("support_v1：无练习候选与无科普候选时各自走兜底")
    void supportFallsBackWithoutCandidates() {
        String out = chat("support_v1", "{\"emotionResult\":{\"primaryEmotion\":\"悲伤\"}}");

        assertTrue(out.contains("ex_54321"), "无候选时给通用感官着陆");
        assertTrue(out.contains("node:psy_self_regulation_body"), "无科普候选时给内置来源");
        assertTrue(out.contains("悲伤"), "标题沿用主导情绪");
    }

    @Test
    @DisplayName("growth_letter_v1：素材齐全时逐项写进信里，引文出现在亮点句")
    void letterUsesFullMaterial() {
        String user = """
                {"dataPoints":6,"emotionTop":"焦虑","prevAvgValence":-0.4,"avgValence":-0.1,
                 "exercisesDone":3,"quotes":["今天其实没那么糟"],"insufficient":false}""";

        String out = chat("growth_letter_v1", user);

        assertTrue(out.contains("6 次记录"));
        assertTrue(out.contains("出现最多的情绪是焦虑"));
        assertTrue(out.contains("心情均值从"));
        assertTrue(out.contains("完成了 3 次练习"));
        assertTrue(out.contains("今天其实没那么糟"));
        assertTrue(out.contains("最亮的一刻"), "有引文时亮点句带引文");
        assertTrue(out.contains("\"disclaimer\":true"));
    }

    @Test
    @DisplayName("growth_letter_v1：素材缺失时各段自动省略，亮点句走兜底")
    void letterOmittedSegmentsWhenMaterialThin() {
        String out = chat("growth_letter_v1", "{\"insufficient\":true}");

        assertTrue(out.contains("0 次记录"));
        assertFalse(out.contains("出现最多的情绪"), "无情绪时不说这句");
        assertFalse(out.contains("心情均值从"), "无历史均值时不做对比");
        assertTrue(out.contains("这周的亮点，是你还愿意记录"), "无引文时亮点走兜底");
        assertTrue(out.contains("\"insufficientEvidence\":true"), "如实透传证据不足标记");
    }

    @Test
    @DisplayName("companion_v1：画像追问优先于情绪档位，三档情绪各有台词")
    void companionCoversProfileFollowUpAndMoods() {
        String withProfile = """
                {"mood":"LOW_ENERGY","profileFollowUp":true,"profileEntity":"和导师的矛盾",
                 "transcript":[{"user":"我回来了"}]}""";
        assertTrue(chat("companion_v1", withProfile).contains("和导师的矛盾"), "画像追问优先");

        assertTrue(chat("companion_v1",
                "{\"mood\":\"LOW_ENERGY\",\"profileFollowUp\":false,\"profileEntity\":\"\",\"transcript\":[{\"user\":\"累\"}]}")
                .contains("不用急着好起来"));
        assertTrue(chat("companion_v1",
                "{\"mood\":\"WARM_UP\",\"profileFollowUp\":false,\"profileEntity\":\"\",\"transcript\":[{\"user\":\"还好\"}]}")
                .contains("愿意多说说吗"));
        String other = chat("companion_v1",
                "{\"mood\":\"FOLLOW\",\"profileFollowUp\":false,\"profileEntity\":\"\",\"transcript\":[{\"user\":\"在吗\"}]}");
        assertTrue(other.contains("然后呢"), other);
        assertTrue(other.contains("\"moodTag\":\"FOLLOW\""));
    }

    @Test
    @DisplayName("companion_v1：画像追问开关为真但实体为空时仍走情绪档位")
    void companionIgnoresBlankProfileEntity() {
        String out = chat("companion_v1",
                "{\"mood\":\"WARM_UP\",\"profileFollowUp\":true,\"profileEntity\":\"  \",\"transcript\":[{\"user\":\"还好\"}]}");

        assertTrue(out.contains("愿意多说说吗"), out);
    }

    @Test
    @DisplayName("未知模板直接抛 LlmUnavailableException，不静默产出假内容")
    void unknownTemplateThrows() {
        LlmUnavailableException ex = assertThrows(LlmUnavailableException.class,
                () -> chat("no_such_template_v9", "x"));

        assertTrue(ex.getMessage().contains("no_such_template_v9"), ex.getMessage());
    }

    @Test
    @DisplayName("doStream：按码点分块交付增量，拼起来等于完整内容，且只在末尾收尾一次")
    void streamDeliversDeltasThenCompletesOnce() {
        StringBuilder acc = new StringBuilder();
        AtomicInteger completes = new AtomicInteger();
        AtomicReference<String> full = new AtomicReference<>();

        LlmClient.LlmResponse resp = client.doStream(req("emotion_v1", "今天很开心"), new LlmClient.TokenSink() {
            @Override
            public void onDelta(String delta) {
                acc.append(delta);
            }

            @Override
            public void onComplete(String content) {
                completes.incrementAndGet();
                full.set(content);
            }
        });

        assertEquals(resp.content(), acc.toString(), "增量拼接应等于返回的完整内容");
        assertEquals(resp.content(), full.get(), "收尾回调拿到的是全量而非最后一片");
        assertEquals(1, completes.get(), "收尾只发生一次");
        assertEquals("mock-llm-v1", resp.model());
        assertTrue(resp.tokensIn() > 0 && resp.tokensOut() > 0, "用量按长度估算，不为零");
    }
}
