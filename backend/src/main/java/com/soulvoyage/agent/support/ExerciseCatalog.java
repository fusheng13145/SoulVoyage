package com.soulvoyage.agent.support;

import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.content.ContentStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 练习库目录（M8 · N4）：读 ContentStore 的 DB 快照（闭集不外扩），管理端改内容即时生效。 */
@Component
@RequiredArgsConstructor
public class ExerciseCatalog {

    private final ContentStore store;

    public List<Exercise> list() {
        return store.exercises();
    }

    public boolean exists(String id) {
        return list().stream().anyMatch(e -> e.id().equals(id));
    }

    public Exercise require(String id) {
        return list().stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.BAD_PARAMS, "未知练习: " + id));
    }
}
