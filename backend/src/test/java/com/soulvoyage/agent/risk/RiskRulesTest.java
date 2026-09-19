package com.soulvoyage.agent.risk;

import com.soulvoyage.agent.risk.RiskRules.Level;
import com.soulvoyage.agent.risk.RiskRules.RuleHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 风险规则引擎单测：危机句式 100% 召回（CI 卡点，手册 §九）+ 中危信号 + 误报边界 */
class RiskRulesTest {

    @Test
    void crisisPhrasesFullRecall() {
        for (String p : RiskRules.crisisPhrases()) {
            assertTrue(RiskRules.isCrisis("最近很累，" + p + "了。"), "漏检: " + p);
            List<RuleHit> hits = RiskRules.scan("算了，" + p);
            assertEquals(Level.HIGH, RiskRules.maxLevel(hits), "级别错误: " + p);
            assertEquals("RISK_CRISIS", hits.get(0).ruleCode());
        }
    }

    @Test
    void crisisVariantsWithInsertedIntensifiersAreRecalled() {
        for (String v : List.of("活着真没意思", "活着好没意思", "活着没什么意思", "活着没啥意思", "活着太没意思了")) {
            assertTrue(RiskRules.isCrisis("最近很累，" + v + "。"), "漏检变体: " + v);
            assertEquals(Level.HIGH, RiskRules.maxLevel(RiskRules.scan(v)), "级别错误: " + v);
        }
        // 误报边界：不含「活着…没意思」骨架的口语不得被正则误伤
        assertFalse(RiskRules.isCrisis("这剧真没意思，不看了"));
        assertFalse(RiskRules.isCrisis("要好好活着，没什么过不去的坎"));
    }

    @Test
    void persistentLowAndSelfDenigratingUpgradeToMedium() {
        var hits = RiskRules.scan("已经两周了，一直很低落，觉得自己一无是处。");
        assertEquals(Level.MEDIUM, RiskRules.maxLevel(hits));
        assertTrue(hits.stream().anyMatch(h -> h.ruleCode().equals("LOW_MOOD_PERSISTENT")));
        assertTrue(hits.stream().anyMatch(h -> h.ruleCode().equals("SELF_DENIGRATING")));
    }

    @Test
    void crisisBeatsMediumInSameText() {
        var hits = RiskRules.scan("两周没笑了，我是废物，真的撑不下去。");
        assertEquals(1, hits.size(), "命中危机即封顶，不再叠加中危");
        assertEquals(Level.HIGH, hits.get(0).level());
    }

    @Test
    void ordinaryNegativeSpeechStaysLow() {
        assertEquals(Level.LOW, RiskRules.maxLevel(RiskRules.scan("和室友吵了一架，越想越气。")));
        assertEquals(Level.LOW, RiskRules.maxLevel(RiskRules.scan("")));
        assertEquals(Level.LOW, RiskRules.maxLevel(RiskRules.scan(null)));
        assertFalse(RiskRules.isCrisis("考试周压力好大"));
    }

    @Test
    void higherKeepsMonotonicOrder() {
        assertEquals(Level.HIGH, RiskRules.higher(Level.MEDIUM, Level.HIGH));
        assertEquals(Level.MEDIUM, RiskRules.higher(Level.MEDIUM, Level.LOW));
        assertEquals(Level.LOW, RiskRules.higher(Level.LOW, Level.LOW));
    }

    /** 下篇·S1 否定/引文检测：命中点前窗口含否定词 → 降 MEDIUM + needsReview（进复核队列，不丢弃） */
    @Test
    void negatedCrisisHitDowngradesToReview() {
        for (String s : List.of("新闻说有人自杀", "朋友说他想自杀", "千万别自杀", "不要自杀")) {
            List<RuleHit> hits = RiskRules.scan(s);
            assertEquals(Level.MEDIUM, RiskRules.maxLevel(hits), "应降级: " + s);
            assertTrue(hits.get(0).needsReview(), "降级命中必须标记复核: " + s);
            assertEquals("KEYWORD_RULE_NEGATED", hits.get(0).triggerType());
            assertFalse(RiskRules.isCrisis(s));
        }
        // 反例：否定词在窗口之外（隔着标点后的独立分句）不误伤真实危机表达
        assertTrue(RiskRules.isCrisis("什么都没有了，活着没意思。前半句不含窗内否定词"));
        assertTrue(RiskRules.isCrisis("最近很累，不想活了"));
    }
}
