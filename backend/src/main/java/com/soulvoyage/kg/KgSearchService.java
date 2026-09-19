package com.soulvoyage.kg;

import java.util.List;

/**
 * 知识图谱检索服务（手册 §5.3）：Agent 不裸查库，由本服务预先注入候选集。
 * 当前实现：InMemoryKgService（classpath JSON，词条与 Neo4j seed.cypher 同源）。
 * Neo4j 就绪后：新增 driver 实现并按 soulvoyage.neo4j.enabled 切换，接口不变。
 */
public interface KgSearchService {

    /** 按主导情绪 + 事件标签召回认知误区候选（至多 maxCount 个，供 Agent 闭集选择） */
    List<DistortionCard> distortionsFor(String primaryEmotion, List<String> eventTags, int maxCount);

    /** 按事件标签召回压力源候选 */
    List<String> stressorsFor(List<String> eventTags);

    /** 压力源闭集枚举（注入 Prompt 与 Mock 使用，与 schema enum 同源维护） */
    List<String> stressorOptions();

    /** 误区节点是否存在（用于 Agent 输出的 kgNodeId 反向校验） */
    boolean distortionExists(String kgNodeId);

    record DistortionCard(String kgNodeId, String name, String definition,
                          String typicalSignature, String socraticTemplate) {}
}
