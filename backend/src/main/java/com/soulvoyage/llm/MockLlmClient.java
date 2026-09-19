package com.soulvoyage.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.orchestrator.agent.LlmUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 开发期 Mock：按 prompt 模板路由到确定性样例输出，让全链路可跑、契约测试可回放。
 * 输出刻意贴近真实模型格式（含 markdown 包裹噪声），以验证解析器的健壮性。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "soulvoyage.llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public LlmResponse chat(LlmRequest req) {
        long t0 = System.currentTimeMillis();
        sleep(300);   // 模拟网络延迟，便于观察 SSE 进度体验
        String content = switch (req.template()) {
            case "emotion_v1" -> "```json\n" + emotionMock(req.user()) + "\n```";
            case "trace_v1" -> "```json\n" + traceMock(req.user()) + "\n```";
            case "npc_v1" -> "```json\n" + npcMock(req.user()) + "\n```";
            case "simulate_review_v1" -> "```json\n" + reviewMock(req.user()) + "\n```";
            case "support_v1" -> "```json\n" + supportMock(req.user()) + "\n```";
            case "companion_v1" -> "```json\n" + companionMock(req.user()) + "\n```";
            case "growth_letter_v1" -> "```json\n" + letterMock(req.user()) + "\n```";
            default -> throw new LlmUnavailableException("Mock 未覆盖模板: " + req.template());
        };
        return new LlmResponse(content, "mock-llm-v1",
                estTokens(req.system() + req.user()), estTokens(content), System.currentTimeMillis() - t0);
    }

    private String emotionMock(String text) {
        String primary = "平静";
        double valence = 0.2, intensity = 0.3;
        if (contains(text, "烦", "气", "冲突", "吵", "骂")) { primary = "愤怒"; valence = -0.6; intensity = 0.75; }
        else if (contains(text, "压", "考试", "ddl", "答辩", "赶")) { primary = "焦虑"; valence = -0.5; intensity = 0.7; }
        else if (contains(text, "难过", "伤心", "哭", "失恋", "想家")) { primary = "悲伤"; valence = -0.7; intensity = 0.65; }
        else if (contains(text, "开心", "高兴", "顺利", "好消息", "感谢")) { primary = "喜悦"; valence = 0.8; intensity = 0.6; }
        else if (contains(text, "累", "麻木", "没感觉", "提不起")) { primary = "麻木"; valence = -0.2; intensity = 0.4; }
        String tag = switch (primary) {
            case "愤怒" -> "{\"tag\":\"人际冲突\",\"scene\":\"宿舍\",\"evidence\":\"（mock）检测到冲突类表述\"}";
            case "焦虑" -> "{\"tag\":\"学业压力\",\"scene\":\"学习\",\"evidence\":\"（mock）检测到压力类表述\"}";
            case "悲伤" -> "{\"tag\":\"关系失落\",\"scene\":\"亲密/家庭\",\"evidence\":\"（mock）检测到低落类表述\"}";
            case "喜悦" -> "{\"tag\":\"成就事件\",\"scene\":\"学习/生活\",\"evidence\":\"（mock）检测到积极表述\"}";
            default -> "{\"tag\":\"日常\",\"scene\":\"生活\",\"evidence\":\"（mock）无明显事件词\"}";
        };
        return """
        {"primaryEmotion":"%s","secondaryEmotions":[],"intensity":%.2f,"valence":%.2f,
         "eventTags":[%s],"profileDelta":{"stressorFreqUpdate":{}}}
        """.formatted(primary, intensity, valence, tag);
    }

    /** trace_v1 的 user 是 TraceAgent 组装的结构化 JSON：从注入的候选卡确定性拼装，保证契约测试可回放 */
    private String traceMock(String userJson) {
        JsonNode req;
        try {
            req = mapper.readTree(userJson);
        } catch (Exception e) {
            req = mapper.createObjectNode();
        }
        String emotion = req.path("emotionResult").path("primaryEmotion").asText("平静");
        String diary = req.path("diaryText").asText("");
        String excerpt = diary.length() > 40 ? diary.substring(0, 40) + "…" : diary;
        JsonNode candidates = req.path("kgCandidates");
        JsonNode stressorHint = req.path("stressorHint");

        var out = mapper.createObjectNode();
        ArrayNode stressors = out.putArray("stressors");
        if (stressorHint.isArray() && stressorHint.size() > 0) {
            String source = stressorHint.get(0).asText("其他");
            ObjectNode s = stressors.addObject();
            s.put("source", source);
            s.put("confidence", 0.62);
            s.putArray("evidence")
                    .add("（mock）事件标签指向「" + source + "」")
                    .add("（mock）日记节选：" + excerpt);
        }
        ArrayNode distortions = out.putArray("cognitiveDistortions");
        ArrayNode socratic = out.putArray("socraticQuestions");
        for (JsonNode c : candidates) {
            if (distortions.size() < 2) {
                ObjectNode d = distortions.addObject();
                d.put("name", c.path("name").asText());
                d.put("kgNodeId", c.path("kgNodeId").asText());
                d.put("trigger", "（mock）与典型句式「" + c.path("typicalSignature").asText() + "」相符");
                d.put("challengeQuestion", c.path("socraticTemplate").asText());
            }
            if (socratic.size() < 3) socratic.add(c.path("socraticTemplate").asText());
        }
        if (socratic.isEmpty()) socratic.add("如果一周后再看这件事，你会用哪个词形容当时的自己？");
        ObjectNode report = out.putObject("report");
        report.put("eventSummary", "（mock）日记摘录：" + excerpt);
        report.put("emotionSummary", "（mock）主导情绪为「" + emotion + "」，强度信号来自冲突/压力类表述。");
        report.put("thoughtSummary", candidates.isEmpty()
                ? "（mock）未匹配到候选认知误区卡。"
                : "（mock）想法中可能夹带「" + candidates.get(0).path("name").asText() + "」式的自动判断。");
        report.put("insight", "（mock）想法只是大脑的第一版草稿，不等于事实本身，可以拿出来检验。");
        report.put("suggestion", "（mock）今晚睡前写下三件顺利的小事，训练注意力的分配。");
        out.putArray("riskSignals");
        if (candidates.isEmpty() && stressorHint.size() == 0) out.put("insufficientEvidence", true);
        return out.toString();
    }

    private boolean contains(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    /** npc_v1 的 user 是 SimulationService 组装的逐轮 JSON：按导演档位出确定性台词 */
    private String npcMock(String userJson) {
        JsonNode req = parse(userJson);
        String mood = req.path("mood").asText("NEUTRAL");
        String lastUser = "";
        JsonNode tr = req.path("transcript");
        for (int i = tr.size() - 1; i >= 0; i--) {
            if (tr.get(i).hasNonNull("user")) { lastUser = tr.get(i).path("user").asText(); break; }
        }
        String echo = lastUser.length() > 24 ? lastUser.substring(0, 24) + "…" : lastUser;
        String reply;
        String cue;
        switch (mood) {
            case "ESCALATED" -> {
                reply = "又是这套？你说「" + echo + "」的时候，有没有想过我这边什么情况？我真是受够了。";
                cue = "被顶到痛处，音调升高";
            }
            case "DISSATISFIED" -> {
                reply = "「" + echo + "」说得倒是轻巧。别急着给我派任务，先说说你打算怎么配合？";
                cue = "防御性后撤，开始讲条件";
            }
            case "SOFTENED" -> {
                reply = "……行，「" + echo + "」这句我听见了，是我之前把话说死了。那咱们定个具体的？";
                cue = "语气放缓，愿意给台阶";
            }
            default -> {
                reply = "嗯，「" + echo + "」，我大概明白你的意思。那我先说说我的情况，你再看看怎么合排。";
                cue = "就事论事，保持保留";
            }
        }
        var out = mapper.createObjectNode();
        out.put("reply", reply);
        out.put("emotionCue", "（mock）" + cue);
        return out.toString();
    }

    /** simulate_review_v1：从逐轮 stateTag 确定性映射 NVO 评分，保证契约测试可回放 */
    private String reviewMock(String userJson) {
        JsonNode req = parse(userJson);
        JsonNode turns = req.path("turns");
        JsonNode goals = req.path("goalDimensions");

        var out = mapper.createObjectNode();
        ArrayNode scores = out.putArray("turnScores");
        ArrayNode moments = out.putArray("keyMoments");
        ArrayNode rewrites = out.putArray("rewriteSuggestions");
        int sum = 0, n = 0, aCnt = 0, dCnt = 0;
        int i = 0;
        for (JsonNode t : turns) {
            String tag = t.path("stateTag").asText("");
            int turn = t.path("turn").asInt(1);
            String user = t.path("userText").asText("");
            String quote = user.length() > 60 ? user.substring(0, 60) + "…" : user;
            String dim = goals.size() > 0
                    ? goals.get(i % goals.size()).asText("LISTEN")
                    : (i % 2 == 0 ? "LISTEN" : "EMPATHY");
            String grade;
            String comment;
            String momentType = null;
            switch (tag) {
                case "BOUNDARY_SET" -> { grade = "A"; comment = "（mock）事实+感受+具体请求齐备，推进了对话"; momentType = "明确立边界"; }
                case "DE_ESCALATION" -> { grade = "A"; comment = "（mock）主动接住对方情绪并给出台阶"; dim = "EMPATHY"; momentType = "主动缓和"; }
                case "ACKNOWLEDGED" -> { grade = "B"; comment = "（mock）有确认倾听，但还没落到自己的请求"; dim = "LISTEN"; }
                case "CONFLICT_UP" -> { grade = "D"; comment = "（mock）出现人格指控式表述，触发对方防御"; dim = "CONCESSION"; momentType = "升级冲突"; }
                default -> { grade = (i % 2 == 0) ? "B" : "C"; comment = "（mock）表达了自己的立场，观察/请求要素不完整"; }
            }
            scores.addObject().put("turn", turn).put("dimension", dim).put("grade", grade)
                    .put("comment", comment);
            if (momentType != null && moments.size() < 6) {
                moments.addObject().put("turn", turn).put("type", momentType).put("quote", quote);
            }
            if (("D".equals(grade) || "C".equals(grade)) && rewrites.size() < 5) {
                rewrites.addObject().put("turn", turn).put("original", quote)
                        .put("optimized", "我注意到" + quote + "（观察），我感到有点着急（感受），因为我需要咱们把安排定下来（需要），"
                                + "所以我希望我们约一个具体时间再谈十分钟（请求）。");
            }
            sum += switch (grade) { case "A" -> 92; case "B" -> 75; case "C" -> 60; default -> 42; };
            n++;
            if ("A".equals(grade)) aCnt++;
            if ("D".equals(grade)) dCnt++;
            i++;
        }

        var overall = out.putObject("overall");
        overall.put("avgScore", n == 0 ? 60 : Math.round((float) sum / n));
        ArrayNode st = overall.putArray("strengths");
        if (aCnt > 0) st.add("能使用『我注意到+我希望』的句式立边界（" + aCnt + " 轮）");
        if (st.isEmpty()) st.add("全程保持了表达意愿，没有中途离场");
        ArrayNode wk = overall.putArray("weaknesses");
        if (dCnt > 0) wk.add("有 " + dCnt + " 轮出现指控式表达，直接推高冲突");
        else wk.add("请求多为模糊提议，缺少可执行的时间与数字");
        out.put("overallAdvice", "（mock）开场先复述对方一句再讲事实；把「你总是」换成「这周有 X 次」；每个诉求落在一个具体可执行的小请求上。");
        return out.toString();
    }

    /** support_v1：从注入的候选练习确定性组装方案，保证契约测试可回放（exerciseId 全部来自候选闭集） */
    private String supportMock(String userJson) {
        JsonNode req = parse(userJson);
        JsonNode candidates = req.path("candidates");
        String emotion = req.path("emotionResult").path("primaryEmotion").asText("情绪");
        String anchor = req.path("psyAnchor").asText("node:psy_self_regulation_body");
        String[] schedules = {"此刻", "今晚睡前", "明天早上"};

        var out = mapper.createObjectNode();
        out.put("planTitle", emotion + "·今日轻量自助小方案");
        ArrayNode matched = out.putArray("matchedExercises");
        int i = 0;
        for (JsonNode c : candidates) {
            if (matched.size() >= 3) break;
            ObjectNode m = matched.addObject();
            m.put("exerciseId", c.path("id").asText());
            m.put("reason", "（mock）匹配当前「" + emotion + "」，" + c.path("name").asText()
                    + "约 " + c.path("durationMin").asInt() + " 分钟即可");
            m.put("schedule", schedules[Math.min(i, schedules.length - 1)]);
            i++;
        }
        if (matched.isEmpty()) {   // 兜底：至少给一条通用 grounding
            ObjectNode m = matched.addObject();
            m.put("exerciseId", "ex_54321");
            m.put("reason", "（mock）无更精准匹配，先用感官着陆稳定当下");
            m.put("schedule", "此刻");
        }
        ObjectNode edu = out.putObject("psyEducation");
        edu.put("topic", "「" + emotion + "」时身体在发生什么");
        edu.put("content", "（mock）情绪是身体对处境的信号，而非事实本身。当" + emotion
                + "升起时，呼吸、肌肉与注意力都会随之变化；先照顾身体的反应，思路往往会慢慢回到可处理的状态。"
                + "下面是几个不需要意志力就能开始的小动作。");
        edu.put("kgSource", anchor);
        // G4：把 matchedExercises 排成 3 天可执行计划（每日 1 项，轮转候选）
        out.put("planDays", 3);
        ArrayNode planItems = out.putArray("planItems");
        int pi = 0;
        for (JsonNode m : matched) {
            ObjectNode it = planItems.addObject();
            it.put("day", (pi % 3) + 1);
            it.put("exerciseId", m.path("exerciseId").asText());
            it.put("guidance", "（mock）第 " + ((pi % 3) + 1) + " 天："
                    + m.path("exerciseId").asText() + "，做完就算赢。");
            pi++;
        }
        out.put("disclaimer", true);
        return out.toString();
    }

    /** growth_letter_v1：user 即素材 JSON；引文逐字取自素材闭集，保证反向校验可回放 */
    private String letterMock(String materialJson) {
        JsonNode mat = parse(materialJson);
        JsonNode quotes = mat.path("quotes");
        var out = mapper.createObjectNode();
        var sb = new StringBuilder("见字如面。（mock）这一周，小岛收到了你 ")
                .append(mat.path("dataPoints").asInt(0)).append(" 次记录");
        if (mat.path("emotionTop").asText("").length() > 0) {
            sb.append("，出现最多的情绪是").append(mat.path("emotionTop").asText());
        }
        if (mat.path("prevAvgValence").isNumber()) {
            sb.append("；心情均值从 ").append(mat.path("prevAvgValence").asDouble())
                    .append(" 走到了 ").append(mat.path("avgValence").asDouble());
        }
        if (mat.path("exercisesDone").asInt(0) > 0) {
            sb.append("。你还完成了 ").append(mat.path("exercisesDone").asInt(0)).append(" 次练习");
        }
        if (!quotes.isEmpty()) {
            sb.append("。读到你写下「").append(quotes.get(0).asText())
                    .append("」那句话时，我在岛这头记了很久。");
        }
        sb.append("下周也按自己的节奏来，记录本身就是在照顾自己。 落款：心屿小岛");
        out.put("letter", sb.toString());
        out.put("weekGlow", quotes.isEmpty() ? "这周的亮点，是你还愿意记录。"
                : "最亮的一刻：你写下「" + quotes.get(0).asText() + "」。");
        out.put("insufficientEvidence", mat.path("insufficient").asBoolean(false));
        out.put("disclaimer", true);
        return out.toString();
    }

    /** companion_v1：按情绪响应策略档位出确定性台词；profileFollowUp 时回引画像事件（"记得你"演示锚点） */
    private String companionMock(String userJson) {
        JsonNode req = parse(userJson);
        String mood = req.path("mood").asText("FOLLOW");
        String lastUser = "";
        JsonNode tr = req.path("transcript");
        for (int i = tr.size() - 1; i >= 0; i--) {
            if (tr.get(i).hasNonNull("user")) { lastUser = tr.get(i).path("user").asText(); break; }
        }
        String echo = lastUser.length() > 24 ? lastUser.substring(0, 24) + "…" : lastUser;
        String reply;
        if (req.path("profileFollowUp").asBoolean(false) && !req.path("profileEntity").asText("").isBlank()) {
            reply = "你来啦。上次你说的「" + req.path("profileEntity").asText()
                    + "」那摊事，后来怎么样了？";
        } else {
            reply = switch (mood) {
                case "LOW_ENERGY" -> "嗯，我在听。「" + echo + "」——这种事摊上谁都不好受，不用急着好起来。";
                case "WARM_UP" -> "听起来你今天松了一点？「" + echo + "」挺好的，愿意多说说吗？";
                default -> "「" + echo + "」，然后呢？我想听。";
            };
        }
        var out = mapper.createObjectNode();
        out.put("reply", reply);
        out.put("moodTag", mood);
        return out.toString();
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private int estTokens(String s) { return Math.max(1, s.length() / 2); }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
