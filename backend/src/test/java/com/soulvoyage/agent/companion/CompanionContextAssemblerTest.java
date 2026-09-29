package com.soulvoyage.agent.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.domain.profile.EmotionProfileEntity;
import com.soulvoyage.domain.profile.EmotionProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * "记得你"上下文组装的分支覆盖：近 7 天有轨迹时按主导情绪 + 高频事件归纳，轨迹为空时退回最近一周画像，
 * 两者都空时给出空块（不编造记忆）；画像基调三档与压力源 JSON 的损坏兜底。
 *
 * <p>注入给 prompt 的只有聚合结论，任何一条降级路径都必须返回"空块"而不是抛异常，
 * 否则陪聊第一轮就会因为历史数据外伤而打不开。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanionContextAssemblerTest {

    @Mock EmotionTrajectoryRepository trajRepo;
    @Mock EmotionProfileRepository profileRepo;

    private final ObjectMapper mapper = new ObjectMapper();
    private final BusinessCalendar cal = new BusinessCalendar("Asia/Shanghai");

    private CompanionContextAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new CompanionContextAssembler(trajRepo, profileRepo, cal, mapper);
    }

    private LocalDate today() {
        return cal.today();
    }

    private EmotionTrajectoryEntity traj(LocalDate d, String emotion, String eventTags) {
        EmotionTrajectoryEntity t = new EmotionTrajectoryEntity();
        t.setUserId(1L);
        t.setRecordDate(d);
        t.setPrimaryEmotion(emotion);
        t.setValence(new BigDecimal("0.100"));
        t.setIntensity(new BigDecimal("0.500"));
        t.setSourceType("DIARY");
        t.setEventTags(eventTags);
        return t;
    }

    private EmotionProfileEntity profile(String valence, String stressorJson) {
        EmotionProfileEntity p = new EmotionProfileEntity();
        p.setUserId(1L);
        p.setStatWeek("2026-W38");
        p.setAvgValence(valence == null ? null : new BigDecimal(valence));
        p.setStressorTopJson(stressorJson);
        return p;
    }

    private void stubWeek(List<EmotionTrajectoryEntity> week) {
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(1L, today().minusDays(6), today()))
                .thenReturn(week);
    }

    // ---------------- 有本周轨迹 ----------------

    @Test
    @DisplayName("assemble：本周有轨迹时归纳主导情绪与最高频事件")
    void assembleSummarisesWeekPoints() {
        stubWeek(List.of(
                traj(today().minusDays(1), "焦虑", "[{\"tag\":\"学业压力\"}]"),
                traj(today(), "焦虑", "[{\"tag\":\"学业压力\"},{\"tag\":\"人际冲突\"}]")));

        CompanionContextAssembler.ProfileBlock block = assembler.assemble(1L);

        assertEquals("近 7 天主导情绪：焦虑；高频事件：学业压力", block.summary());
        assertEquals("学业压力", block.topEventTag());
    }

    @Test
    @DisplayName("assemble：轨迹都没有事件标签时只给主导情绪，不编造高频事件")
    void assembleWithoutEventTagsOmitsTagSegment() {
        stubWeek(List.of(
                traj(today(), "平静", null),
                traj(today().minusDays(1), "平静", "[]"),
                traj(today().minusDays(2), "平静", "[{\"tag\":\"\"}]")));

        CompanionContextAssembler.ProfileBlock block = assembler.assemble(1L);

        assertEquals("近 7 天主导情绪：平静", block.summary());
        assertNull(block.topEventTag());
    }

    @Test
    @DisplayName("assemble：轨迹主导情绪为 null 时用占位横线，不让摘要出现 null 字样")
    void assembleHandlesNullEmotion() {
        stubWeek(List.of(traj(today(), null, "[]")));

        CompanionContextAssembler.ProfileBlock block = assembler.assemble(1L);

        assertTrue(block.summary().contains("主导情绪：—"));
    }

    @Test
    @DisplayName("assemble：事件标签 JSON 损坏时整块降级为空块，不冒泡异常")
    void assembleDegradesOnBrokenEventTags() {
        stubWeek(List.of(traj(today(), "焦虑", "{ 这不是数组")));

        CompanionContextAssembler.ProfileBlock block = assembler.assemble(1L);

        assertEquals("", block.summary());
        assertNull(block.topEventTag());
    }

    @Test
    @DisplayName("assemble：轨迹仓库抛异常时同样降级为空块")
    void assembleDegradesOnRepositoryFailure() {
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), eq(today().minusDays(6)), eq(today())))
                .thenThrow(new IllegalStateException("db down"));

        CompanionContextAssembler.ProfileBlock block = assembler.assemble(1L);

        assertEquals("", block.summary());
        assertNull(block.topEventTag());
    }

    // ---------------- 退回画像 ----------------

    @Test
    @DisplayName("assemble：本周无轨迹时退回最近一周画像，基调偏低并带上压力源")
    void assembleFallsBackToLatestProfile() {
        stubWeek(List.of());
        when(profileRepo.findByUserIdOrderByStatWeekDesc(1L))
                .thenReturn(List.of(profile("-0.500", "[{\"name\":\"睡眠不足\"}]")));

        CompanionContextAssembler.ProfileBlock block = assembler.assemble(1L);

        assertEquals("上周整体基调：偏低", block.summary());
        assertEquals("睡眠不足", block.topEventTag());
    }

    @Test
    @DisplayName("assemble：画像基调三档——偏暖 / 平稳 / 偏低")
    void assembleProfileToneBuckets() {
        stubWeek(List.of());
        when(profileRepo.findByUserIdOrderByStatWeekDesc(1L)).thenReturn(List.of(profile("0.500", "[]")));
        assertEquals("上周整体基调：偏暖", assembler.assemble(1L).summary());

        when(profileRepo.findByUserIdOrderByStatWeekDesc(1L)).thenReturn(List.of(profile("0.100", "[]")));
        assertEquals("上周整体基调：平稳", assembler.assemble(1L).summary());

        when(profileRepo.findByUserIdOrderByStatWeekDesc(1L)).thenReturn(List.of(profile("-0.160", "[]")));
        assertEquals("上周整体基调：偏低", assembler.assemble(1L).summary());
    }

    @Test
    @DisplayName("assemble：画像无平均效价时摘要为空串，不硬凑一句话")
    void assembleProfileWithoutValenceGivesEmptySummary() {
        stubWeek(List.of());
        when(profileRepo.findByUserIdOrderByStatWeekDesc(1L))
                .thenReturn(List.of(profile(null, "[{\"name\":\"学业压力\"}]")));

        CompanionContextAssembler.ProfileBlock block = assembler.assemble(1L);

        assertEquals("", block.summary());
        assertEquals("学业压力", block.topEventTag());
    }

    @Test
    @DisplayName("assemble：画像压力源 JSON 为 null 或损坏时不带上事件")
    void assembleProfileWithoutUsableStressor() {
        stubWeek(List.of());
        when(profileRepo.findByUserIdOrderByStatWeekDesc(1L))
                .thenReturn(List.of(profile("0.500", null)));
        assertNull(assembler.assemble(1L).topEventTag());

        when(profileRepo.findByUserIdOrderByStatWeekDesc(1L))
                .thenReturn(List.of(profile("0.500", "{ 坏")));
        assertNull(assembler.assemble(1L).topEventTag());

        when(profileRepo.findByUserIdOrderByStatWeekDesc(1L))
                .thenReturn(List.of(profile("0.500", "[]")));
        assertNull(assembler.assemble(1L).topEventTag());
    }

    @Test
    @DisplayName("assemble：本周无轨迹且从未有画像时给空块，新用户第一句不硬凑记忆")
    void assembleGivesEmptyBlockForBrandNewUser() {
        stubWeek(List.of());
        when(profileRepo.findByUserIdOrderByStatWeekDesc(1L)).thenReturn(List.of());

        CompanionContextAssembler.ProfileBlock block = assembler.assemble(1L);

        assertEquals("", block.summary());
        assertNull(block.topEventTag());
    }
}
