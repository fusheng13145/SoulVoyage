package com.leyon.soul.admin;

import com.leyon.soul.admin.dto.AdminDtos.GuardrailsResp;
import com.leyon.soul.common.observability.SlowRequestRecorder;
import com.leyon.soul.common.time.BusinessCalendar;
import com.leyon.soul.domain.report.ReportEntity;
import com.leyon.soul.domain.report.ReportRepository;
import com.leyon.soul.domain.risk.RiskEventRepository;
import com.leyon.soul.domain.task.TaskInstanceEntity;
import com.leyon.soul.domain.task.TaskInstanceRepository;
import com.leyon.soul.domain.task.TaskStepLogRepository;
import com.leyon.soul.kg.Neo4jKgService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/** H-05 护栏阈值与空态：失败告警、成本预算、质量抽检、慢请求 TopN。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminMetricsServiceTest {

    @Mock TaskInstanceRepository taskRepo;
    @Mock TaskStepLogRepository stepRepo;
    @Mock RiskEventRepository riskRepo;
    @Mock ReportRepository reportRepo;
    @Mock MeterRegistry registry;
    @Mock BusinessCalendar cal;
    @Mock ObjectProvider<Neo4jKgService> neo4jKg;

    private SlowRequestRecorder recorder;
    private AdminMetricsService service;

    @BeforeEach
    void setUp() {
        recorder = new SlowRequestRecorder();
        when(cal.zone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        when(neo4jKg.getIfAvailable()).thenReturn(null);
        when(stepRepo.costRowsSince(any())).thenReturn(List.of());
        when(taskRepo.findFailedSince(any(), any())).thenReturn(List.of());
        when(taskRepo.countByStatusSince(any())).thenReturn(List.of());
        when(reportRepo.findByDeletedAtIsNullAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        service = new AdminMetricsService(taskRepo, stepRepo, riskRepo, reportRepo, registry, cal,
                neo4jKg, recorder, 1_000_000, 0.001, 0.002, 10.0, "CNY", 80);
    }

    private Object[] row(String status, long count) {
        return new Object[]{status, count};
    }

    private TaskInstanceEntity task(String no) {
        TaskInstanceEntity t = new TaskInstanceEntity();
        t.setTaskNo(no);
        t.setPipelineCode("DIARY_PIPELINE");
        t.setStatus("FAILED");
        t.setErrorMsg("boom");
        t.setCreatedAt(Instant.now());
        return t;
    }

    private ReportEntity report(long id, String feedback) {
        ReportEntity r = new ReportEntity();
        r.setId(id);
        r.setType("TRACE");
        r.setTitle("报告 " + id);
        r.setRiskLevel("LOW");
        r.setFeedback(feedback);
        r.setCreatedAt(Instant.now().minusSeconds(id));
        return r;
    }

    @Test
    @DisplayName("失败告警：3/10 失败为 CRITICAL，并回最近失败任务")
    void failureAlertCritical() {
        when(taskRepo.countByStatusSince(any()))
                .thenReturn(List.<Object[]>of(row("FAILED", 3), row("SUCCESS", 7)));
        when(taskRepo.findFailedSince(any(), any())).thenReturn(List.of(task("t1")));

        GuardrailsResp g = service.guardrails(10);

        assertEquals("CRITICAL", g.failureAlert().level());
        assertEquals(3, g.failureAlert().failed());
        assertEquals(10, g.failureAlert().finished());
        assertEquals(30.0, g.failureAlert().failureRate());
        assertEquals(1, g.failureAlert().recent().size());
        assertEquals("t1", g.failureAlert().recent().get(0).taskNo());
    }

    @Test
    @DisplayName("失败告警：无失败为 OK；单条失败为 WARN")
    void failureAlertLevels() {
        when(taskRepo.countByStatusSince(any())).thenReturn(List.<Object[]>of(row("SUCCESS", 5)));
        assertEquals("OK", service.guardrails(10).failureAlert().level());

        when(taskRepo.countByStatusSince(any()))
                .thenReturn(List.<Object[]>of(row("FAILED", 1), row("SUCCESS", 99)));
        assertEquals("WARN", service.guardrails(10).failureAlert().level());
    }

    @Test
    @DisplayName("成本预算：token 占比与成本估算，90% 为 WARN")
    void costBudgetLevels() {
        when(stepRepo.costRowsSince(any())).thenReturn(List.<Object[]>of(
                new Object[]{"EMOTION", 100, (short) 1, 600_000, 300_000, Instant.now()}));

        GuardrailsResp g = service.guardrails(10);

        assertEquals(900_000, g.costBudget().totalTokens());
        assertEquals(90.0, g.costBudget().usedPercent());
        assertEquals("WARN", g.costBudget().level());
        assertEquals(0, g.costBudget().estimatedCost().compareTo(new java.math.BigDecimal("1.2000")));
    }

    @Test
    @DisplayName("质量抽检：差评率 50% 为 WARN，差评条目优先")
    void qualitySampleWarn() {
        when(reportRepo.findByDeletedAtIsNullAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                report(1, "USEFUL"), report(2, "UNHELPFUL"), report(3, "UNSURE"),
                report(4, "UNHELPFUL"), report(5, "USEFUL"), report(6, "UNHELPFUL"))));

        GuardrailsResp g = service.guardrails(10);

        assertEquals(6, g.qualitySample().sampled());
        assertEquals(6, g.qualitySample().rated());
        assertEquals(3, g.qualitySample().unhelpful());
        assertEquals(50.0, g.qualitySample().unhelpfulRate());
        assertEquals("WARN", g.qualitySample().level());
        assertTrue(g.qualitySample().items().get(0).needsReview());
    }

    @Test
    @DisplayName("质量抽检：样本不足为 INSUFFICIENT")
    void qualitySampleInsufficient() {
        when(reportRepo.findByDeletedAtIsNullAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(report(1, "USEFUL"))));

        GuardrailsResp g = service.guardrails(10);

        assertEquals("INSUFFICIENT", g.qualitySample().level());
        assertEquals(0.0, g.qualitySample().unhelpfulRate());
    }

    @Test
    @DisplayName("慢请求：低于阈值不记录，TopN 按耗时倒序")
    void slowRequestsTopN() {
        recorder.record("GET", "/api/v1/a", 200, 100, 500);
        recorder.record("GET", "/api/v1/b", 200, 900, 500);
        recorder.record("POST", "/api/v1/c", 500, 1200, 500);

        GuardrailsResp g = service.guardrails(10);

        assertEquals(2, g.slowRequests().size());
        assertEquals("/api/v1/c", g.slowRequests().get(0).path());
        assertEquals("/api/v1/b", g.slowRequests().get(1).path());
        assertEquals(0, recorder.top(10, Instant.now().minusSeconds(60)).size() - 2);
    }

    @Test
    @DisplayName("空态：无任务无 LLM 无报告时全部返回可读空态")
    void emptyGuardrails() {
        GuardrailsResp g = service.guardrails(10);

        assertEquals("OK", g.failureAlert().level());
        assertEquals(0, g.costBudget().totalTokens());
        assertEquals("OK", g.costBudget().level());
        assertEquals("INSUFFICIENT", g.qualitySample().level());
        assertTrue(g.slowRequests().isEmpty());
    }
}
