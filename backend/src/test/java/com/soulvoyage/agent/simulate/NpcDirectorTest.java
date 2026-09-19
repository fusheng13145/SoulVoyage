package com.soulvoyage.agent.simulate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 导演模块规则单测：状态机迁移 + 难度权重 + 危机句式 100% 召回回归集（手册 §九 CI 卡点） */
class NpcDirectorTest {

    @Test
    void confrontationalSpeechRaisesTension() {
        var first = NpcDirector.react("NORMAL", "你就是自私！你从来不听，烦不烦，闭嘴，我受够你了", 28);
        assertTrue(first.tension() >= 70, "连续指控应直接冲到激化档: " + first.tension());
        assertEquals(NpcDirector.Mood.ESCALATED, first.mood());
        assertEquals("CONFLICT_UP", first.stateTag());
    }

    @Test
    void iMessageSpeechDeEscalatesAndMarksBoundary() {
        var d = NpcDirector.react("NORMAL",
                "我注意到这周有三次很晚，我感到休息不够，我希望我们可以约定一个熄灯时间", 28);
        assertEquals(NpcDirector.Mood.SOFTENED, d.mood());
        assertEquals("BOUNDARY_SET", d.stateTag());
        assertTrue(d.tension() <= 15);
    }

    @Test
    void neutralTalkSlowlyDriftsUp() {
        var d = NpcDirector.react("NORMAL", "嗯，我知道了，再说吧", 28);
        assertEquals(30, d.tension());
        assertEquals(NpcDirector.Mood.NEUTRAL, d.mood());
    }

    @Test
    void difficultyWeightsChangeTrajectoryForSameInput() {
        String text = "你总是这样，自私，懒得理你";   // 3 个指控词
        var mild = NpcDirector.react("MILD", text, 28);
        var hard = NpcDirector.react("HARD", text, 28);
        assertTrue(mild.tension() < hard.tension(),
                "同一段冒犯话在 MILD/HARD 下张力不同: " + mild.tension() + " vs " + hard.tension());
        assertEquals(NpcDirector.Mood.DISSATISFIED, mild.mood());
        assertEquals(NpcDirector.Mood.ESCALATED, hard.mood());
    }

    @Test
    void crisisPhrasesAlwaysRecallRegardlessOfDifficultyAndTension() {
        for (String p : NpcDirector.CRISIS_PHRASES) {
            for (String diff : new String[]{"MILD", "NORMAL", "HARD"}) {
                var d = NpcDirector.react(diff, "算了，" + p + "，就这样吧", 55);
                assertTrue(d.crisis(), "危机句式漏检: " + p + " @" + diff);
                assertEquals("CRISIS_BREAK", d.stateTag());
            }
        }
    }

    @Test
    void tensionIsClampedToRange() {
        var max = NpcDirector.react("HARD", "你总是你每次你从来你就是烦不烦闭嘴自私没素质垃圾蠢笨", 92);
        assertTrue(max.tension() <= 100);
        var min = NpcDirector.react("MILD", "我注意到我感到我觉得我的感受我希望我们可以要不谢谢你理解抱歉辛苦了", 8);
        assertTrue(min.tension() >= 0);
    }
}
