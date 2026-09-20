package com.soulvoyage.kg;

import java.util.List;
import java.util.Optional;

/**
 * 知识图谱检索服务（手册 §5.3）：Agent 不裸查库，由本服务预先注入候选集。
 * 当前实现：DbKgService（kg_node 表真源 + ContentStore 版本缓存，classpath JSON 仅作种子）。
 * Neo4j 就绪后：新增 driver 实现并按 soulvoyage.neo4j.enabled 切换，接口不变。
 */
public interface KgSearchService {

    /** 按主导情绪 + 事件标签召回认知误区候选（至多 maxCount 个，供 Agent 闭集选择） */
    List<DistortionCard> distortionsFor(String primaryEmotion, List<String> eventTags, int maxCount);

    /** 按事件标签召回压力源候选 */
    List<String> stressorsFor(List<String> eventTags);

    /** 压力源闭集枚举（注入 Prompt 与 Mock 使用，与 schema enum 同源维护） */
    List<String> stressorOptions();


    /** N2：按压力源/情绪召回心理科普候选（SUPPORT 真 kgSource、每日一读供给共用） */
    List<PsyTopicCard> psyTopicsFor(List<String> stressors, String primaryEmotion, int maxCount);

    /** N2：按 code 精确取科普（收藏夹回放等） */
    Optional<PsyTopicCard> psyTopic(String code);

    /** N2：按场景标签 + 误区召回沟通案例（REVIEW 复盘引用） */
    List<CommCaseCard> casesFor(String sceneTag, List<String> distortionIds, int maxCount);

    /** N2：科普节点是否存在（SUPPORT 输出 kgSource 反向校验） */
    boolean psyTopicExists(String code);

    /** N2：沟通案例节点是否存在（REVIEW 输出引用反向校验） */
    boolean commCaseExists(String code);

    record DistortionCard(String kgNodeId, String name, String definition,
                          String typicalSignature, String socraticTemplate, List<String> socraticTemplates) {
        /** 兼容旧构造：单模板即模板列表首条 */
        public DistortionCard(String kgNodeId, String name, String definition,
                              String typicalSignature, String socraticTemplate) {
            this(kgNodeId, name, definition, typicalSignature, socraticTemplate, List.of(socraticTemplate));
        }
    }

    record PsyTopicCard(String kgNodeId, String title, String summary, String microAction,
                        List<String> aboutTags, int readingSec) {}

    record CommCaseCard(String kgNodeId, String title, String scene, String situation,
                        String unhelpful, String helpful, List<String> distortionRefs, List<String> techniqueRefs) {}
}
