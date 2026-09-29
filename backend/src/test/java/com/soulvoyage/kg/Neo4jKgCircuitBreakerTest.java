package com.soulvoyage.kg;

import com.soulvoyage.domain.content.ContentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M16 降级与熔断的单元测试：<b>不依赖 Neo4j 实例</b>，因此 CI 与 H2 回归恒跑，
 * 而 Neo4jKgContractTest 只在有真图实例时执行——两者互补，前者钉"图挂了怎么办"，
 * 后者钉"图活着时结果是否逐字段等价"。
 *
 * <p>缘起于 2026-09-29 真机故障注入：只靠 driver 超时（默认 30s）时，图拒连会把一条 SIMULATE
 * 复盘链路从 0s 拖到 32s。修复为"秒级超时 + 30s 短路熔断"，本测试即断言该机制：
 * 建连失败后不得反复重试，冷却窗口过后须放行半开探测，使图库恢复时能自然闭环。
 */
@ExtendWith(MockitoExtension.class)
class Neo4jKgCircuitBreakerTest {

    @Mock ContentStore store;
    @Mock Driver driver;
    @Mock KgSearchService fallback;

    private Neo4jKgService service;

    @BeforeEach
    void setUp() {
        when(store.version()).thenReturn(7L);
        when(store.distortions()).thenReturn(List.of());
        when(store.techniques()).thenReturn(List.of());
        when(store.psyTopics()).thenReturn(List.of());
        when(store.commCases()).thenReturn(List.of());
        when(store.stressorMap()).thenReturn(Map.of());
        // 图侧任何取会话的动作都失败，模拟实例不可达（database 非空，走 session(SessionConfig) 重载）
        when(driver.session(any(SessionConfig.class))).thenThrow(new RuntimeException("connection refused"));
        when(fallback.distortionsFor(anyString(), anyList(), anyInt())).thenReturn(List.of());
        service = new Neo4jKgService(store, driver, fallback, "neo4j", 200L);
    }

    @Test
    @DisplayName("图不可达：首次建连失败即回落 DB，其后调用被熔断短路、不再建连")
    void graphDownTripsCircuitAndSubsequentCallsSkipGraph() {
        for (int i = 0; i < 3; i++) {
            assertTrue(service.distortionsFor("焦虑", List.of(), 3).isEmpty(),
                    "图不可用时应回落 DbKgService 且不抛异常");
        }
        // 短路生效：三次调用只尝试建连一次
        verify(driver, times(1)).session(any(SessionConfig.class));
        verify(fallback, times(3)).distortionsFor(anyString(), anyList(), anyInt());
    }

    @Test
    @DisplayName("冷却窗口过后半开：放行一次探测，图仍不可用则再次回落")
    void halfOpenProbeAfterCooldown() throws InterruptedException {
        service.distortionsFor("焦虑", List.of(), 3);
        verify(driver, times(1)).session(any(SessionConfig.class));

        Thread.sleep(250);   // 超过 200ms 注入的冷却窗口

        service.distortionsFor("焦虑", List.of(), 3);
        verify(driver, times(2)).session(any(SessionConfig.class));     // 半开放行，重新尝试建连
        verify(fallback, times(2)).distortionsFor(anyString(), anyList(), anyInt());
    }
}
