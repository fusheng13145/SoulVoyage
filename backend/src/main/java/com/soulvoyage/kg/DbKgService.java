package com.soulvoyage.kg;

import com.soulvoyage.domain.content.ContentStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * KG 的 DB 实现（M8 · N2/N4）：词条来自 kg_node 表（ContentStore 版本缓存，热更新即生效）。
 * 误区召回打分：情绪精确命中 +2 / 每个事件标签命中压力源关联 +1 / 典型句式含主导情绪 +1，取 TopN。
 */
@Component
@RequiredArgsConstructor
public class DbKgService implements KgSearchService {

    private final ContentStore store;

    @Override
    public List<DistortionCard> distortionsFor(String primaryEmotion, List<String> eventTags, int maxCount) {
        List<String> stressors = stressorsFor(eventTags);
        return store.distortions().stream()
                .map(e -> {
                    int score = 0;
                    if (e.emotions().contains(primaryEmotion)) score += 2;
                    for (String s : stressors) if (e.emotions().contains(s)) score += 1;
                    if (e.typicalSignature().contains(primaryEmotion)) score += 1;
                    return java.util.Map.entry(e, score);
                })
                .filter(en -> en.getValue() > 0)
                .sorted(Comparator.comparingInt((java.util.Map.Entry<ContentStore.DistortionEntry, Integer> en) -> -en.getValue())
                        .thenComparing(en -> en.getKey().kgNodeId()))
                .limit(maxCount)
                .map(en -> new DistortionCard(en.getKey().kgNodeId(), en.getKey().name(),
                        en.getKey().definition(), en.getKey().typicalSignature(),
                        en.getKey().socraticTemplates().get(0), en.getKey().socraticTemplates()))
                .toList();
    }

    @Override
    public List<PsyTopicCard> psyTopicsFor(List<String> stressors, String primaryEmotion, int maxCount) {
        return store.psyTopics().stream()
                .map(t -> {
                    int score = 0;
                    for (String s : stressors) if (t.aboutTags().contains(s)) score += 2;
                    if (primaryEmotion != null && t.aboutTags().contains(primaryEmotion)) score += 1;
                    return java.util.Map.entry(t, score);
                })
                .filter(en -> en.getValue() > 0)
                .sorted(Comparator.comparingInt((java.util.Map.Entry<ContentStore.PsyEntry, Integer> en) -> -en.getValue())
                        .thenComparing(en -> en.getKey().kgNodeId()))
                .limit(maxCount)
                .map(en -> toCard(en.getKey()))
                .toList();
    }

    @Override
    public Optional<PsyTopicCard> psyTopic(String code) {
        return store.psyTopics().stream().filter(t -> t.kgNodeId().equals(code))
                .findFirst().map(this::toCard);
    }

    @Override
    public List<CommCaseCard> casesFor(String sceneTag, List<String> distortionIds, int maxCount) {
        return store.commCases().stream()
                .map(c -> {
                    int score = 0;
                    if (sceneTag != null && (c.scene().contains(sceneTag) || c.aboutTags().contains(sceneTag)))
                        score += 2;
                    for (String d : distortionIds) if (c.distortionRefs().contains(d)) score += 2;
                    return java.util.Map.entry(c, score);
                })
                .filter(en -> en.getValue() > 0)
                .sorted(Comparator.comparingInt((java.util.Map.Entry<ContentStore.CaseEntry, Integer> en) -> -en.getValue())
                        .thenComparing(en -> en.getKey().kgNodeId()))
                .limit(maxCount)
                .map(en -> toCard(en.getKey()))
                .toList();
    }

    @Override
    public List<String> stressorsFor(List<String> eventTags) {
        return eventTags.stream()
                .map(t -> store.stressorMap().get(t))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    @Override
    public List<String> stressorOptions() {
        return store.stressorMap().values().stream().distinct().sorted().toList();
    }


    @Override
    public boolean psyTopicExists(String code) {
        return store.psyTopics().stream().anyMatch(t -> t.kgNodeId().equals(code));
    }

    @Override
    public boolean commCaseExists(String code) {
        return store.commCases().stream().anyMatch(c -> c.kgNodeId().equals(code));
    }

    private PsyTopicCard toCard(ContentStore.PsyEntry t) {
        return new PsyTopicCard(t.kgNodeId(), t.title(), t.summary(), t.microAction(), t.aboutTags(), t.readingSec());
    }

    private CommCaseCard toCard(ContentStore.CaseEntry c) {
        return new CommCaseCard(c.kgNodeId(), c.title(), c.scene(), c.situation(),
                c.unhelpful(), c.helpful(), c.distortionRefs(), c.techniqueRefs());
    }
}
