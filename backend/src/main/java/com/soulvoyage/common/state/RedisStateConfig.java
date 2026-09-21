package com.soulvoyage.common.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.export.ExportStore;
import com.soulvoyage.domain.export.RedisExportStore;
import com.soulvoyage.llm.LlmGuard;
import com.soulvoyage.llm.RedisGate;
import com.soulvoyage.orchestrator.sse.RedisEventLog;
import com.soulvoyage.orchestrator.sse.RedisEventRelay;
import com.soulvoyage.orchestrator.sse.TaskEventLog;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;

/**
 * 多节点状态面装配（M13，兑现技术债第 2 条）：`SV_DISTRIBUTED=true` 时，
 * 三份原本只活在单进程内存里的状态改由 Redis 承载——
 * ① LLM 闸门（令牌桶 + 在途槽位，Lua 原子）；② 用户级滑动窗（漫聊/语音）；
 * ③ 任务事件流（Stream 缓冲 + pub/sub 中继）；④ 导出快照（**只落信封密文**）。
 * 默认 false 时本类整体不生效，行为与 M12 及之前逐字一致——回归、本机开发、CI 都不需要 Redis。
 * 判据语义、错误码、阈值配置全部沿用原处，变的只是状态存放的位置。
 */
@Configuration
@ConditionalOnProperty(prefix = "soulvoyage", name = "distributed", havingValue = "true")
public class RedisStateConfig {

    @Bean
    @Primary
    SlidingWindowLimiter redisWindowLimiter(StringRedisTemplate redis) {
        return new RedisWindowLimiter(redis);
    }

    @Bean
    TaskEventLog redisEventLog(StringRedisTemplate redis) {
        return new RedisEventLog(redis);
    }

    @Bean
    RedisEventRelay redisEventRelay(StringRedisTemplate redis, ObjectMapper mapper) {
        return new RedisEventRelay(redis, mapper);
    }

    /** 跨节点事件广播：模式订阅 orch:ev:*，回调由 TaskEventBus 在 @PostConstruct 里回填 */
    @Bean
    RedisMessageListenerContainer orchestrationListenerContainer(RedisConnectionFactory cf,
                                                                RedisEventRelay relay) {
        RedisMessageListenerContainer c = new RedisMessageListenerContainer();
        c.setConnectionFactory(cf);
        c.addMessageListener(relay, new PatternTopic(RedisEventRelay.CHANNEL_PREFIX + "*"));
        return c;
    }

    @Bean
    @Primary
    ExportStore redisExportStore(StringRedisTemplate redis, CryptoService crypto, ObjectMapper mapper) {
        return new RedisExportStore(redis, crypto, mapper);
    }

    /**
     * LLM 闸门换 Redis 版。@DependsOn 是为了排在 LlmMetricsConfig 之后：
     * 那个构造函数先用进程内闸门装配过一次，这里拿同一套阈值改挂跨节点闸门，只装配一次即可。
     */
    @Bean
    @DependsOn("llmMetricsConfig")
    RedisGate redisLlmGate(StringRedisTemplate redis, MeterRegistry reg,
                           @Value("${soulvoyage.llm.rate-per-minute:600}") long ratePerMinute,
                           @Value("${soulvoyage.llm.max-in-flight:32}") int maxInFlight,
                           @Value("${soulvoyage.llm.queue-wait:1500ms}") Duration queueWait,
                           @Value("${soulvoyage.llm.inflight-lease:120s}") Duration lease) {
        RedisGate gate = new RedisGate(redis, ratePerMinute, maxInFlight, lease);
        LlmGuard.configure(ratePerMinute, maxInFlight, queueWait, reg, gate);
        return gate;
    }
}
