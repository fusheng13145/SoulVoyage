package com.soulvoyage.llm;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** O3：LLM 调用观测——供应商无关，Mock 真流一视同仁；成本口径以 task_step_log 落库为准 */
@Configuration
public class LlmMetricsConfig {

    LlmMetricsConfig(MeterRegistry reg,
                     @Value("${soulvoyage.llm.rate-per-minute:600}") long ratePerMinute,
                     @Value("${soulvoyage.llm.max-in-flight:32}") int maxInFlight,
                     @Value("${soulvoyage.llm.queue-wait:1500ms}") Duration queueWait) {
        LlmGuard.configure(ratePerMinute, maxInFlight, queueWait, reg);
        LlmUsageCollector.setHook(resp -> {
            String model = resp.model() == null ? "unknown" : resp.model();
            reg.timer("sv.llm.call", "model", model).record(Duration.ofMillis(resp.costMs()));
            reg.counter("sv.llm.tokens", "model", model, "dir", "in").increment(resp.tokensIn());
            reg.counter("sv.llm.tokens", "model", model, "dir", "out").increment(resp.tokensOut());
        });
    }
}
