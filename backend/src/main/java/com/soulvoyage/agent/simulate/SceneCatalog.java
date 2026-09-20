package com.soulvoyage.agent.simulate;

import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.content.ContentStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 场景卡目录（M8 · N4）：读 ContentStore 的 DB 快照，管理端改内容即时生效。 */
@Component
@RequiredArgsConstructor
public class SceneCatalog {

    private final ContentStore store;

    public List<SceneCard> list() {
        return store.scenes();
    }

    public SceneCard require(String code) {
        return store.scenes().stream().filter(c -> c.code().equals(code)).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.BAD_PARAMS, "未知场景: " + code));
    }
}
