package com.soulvoyage.kg;

import com.soulvoyage.domain.content.ContentStore;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;

/**
 * Neo4j KG 装配（M16）：仅当 {@code soulvoyage.neo4j.enabled=true} 时由图实现接管 {@link KgSearchService}。
 *
 * <p>装配策略沿用 M13 四份状态面的既有范式——默认实现（{@link DbKgService}，保持 {@code @Component} 不动）
 * 继续是装配图的基座，图实现在本配置类里以 {@code @Primary} 覆盖。开关关掉时装配图与 M14 及之前逐字相同，
 * 因此 H2 回归、本机开发与 CI 都不需要 Neo4j 实例，也不为测试另开 profile。
 *
 * <p>{@link Driver} 是长生命周期对象（内含连接池），交给 Spring 管理并实现 {@code AutoCloseable} 关闭；
 * 不做启动期连通性校验——实例晚于应用就绪是常见情形，首次查询的懒同步会自然重试（见 Neo4jKgService）。
 *
 * <p><b>超时必须收敛（2026-09-29 真机实测）</b>：driver 默认 {@code maxTransactionRetryTime=30s}，
 * 图库拒连时会在整个重试窗口内反复建连，实测把一条 SIMULATE 复盘链路从 0s 拖到 32s——虽未失败，
 * 但延迟退化不可接受。故显式把建连/重试窗口压到秒级，配合 Neo4jKgService 的短路熔断，
 * 使"图不可用"的代价降到单次调用秒级以内。
 */
@Configuration
@ConditionalOnProperty(name = "soulvoyage.neo4j.enabled", havingValue = "true")
public class Neo4jKgConfig {

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver(@Value("${soulvoyage.neo4j.uri}") String uri,
                              @Value("${soulvoyage.neo4j.username}") String username,
                              @Value("${soulvoyage.neo4j.password}") String password,
                              @Value("${soulvoyage.neo4j.connection-timeout-ms:2000}") long connectionTimeoutMs,
                              @Value("${soulvoyage.neo4j.retry-time-ms:2000}") long retryTimeMs) {
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password),
                Config.builder()
                        .withConnectionTimeout(connectionTimeoutMs, TimeUnit.MILLISECONDS)
                        .withMaxTransactionRetryTime(retryTimeMs, TimeUnit.MILLISECONDS)
                        .withConnectionAcquisitionTimeout(connectionTimeoutMs, TimeUnit.MILLISECONDS)
                        .build());
    }

    @Bean
    @Primary
    public Neo4jKgService neo4jKgService(ContentStore store,
                                         Driver neo4jDriver,
                                         DbKgService dbKgService,
                                         @Value("${soulvoyage.neo4j.database:neo4j}") String database) {
        return new Neo4jKgService(store, neo4jDriver, dbKgService, database);
    }
}
